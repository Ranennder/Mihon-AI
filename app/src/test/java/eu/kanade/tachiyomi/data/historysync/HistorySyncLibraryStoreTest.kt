package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.source.service.SourceManager

class HistorySyncLibraryStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = Database(
        driver,
        historyAdapter = History.Adapter(DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
        chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
    )
    private val provider = mockk<HttpSource>().also { source ->
        every { source.baseUrl } returns "https://mangabuff.ru"
        every { source.getMangaUrl(any()) } answers { "https://mangabuff.ru" + firstArg<SManga>().url }
        every { source.getChapterUrl(any()) } answers { "https://mangabuff.ru" + firstArg<SChapter>().url }
    }
    private val sourceManager = mockk<SourceManager>().also { manager ->
        every { manager.isInitialized } returns MutableStateFlow(true)
        every { manager.get(any<Long>()) } answers { if (firstArg<Long>() == 123L) provider else null }
    }
    private var clock = 1_000L
    private val store = HistorySyncLocalStore(database, sourceManager) { clock }

    @AfterEach
    fun close() = driver.close()

    @Test
    fun `all native favorites include unread titles without chapters and exclude read nonfavorites`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        manga(2, favorite = false)
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
            VALUES(1,2,'/chapter/1','Read without favorite',1,0,4,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        val document = store.reconcile().document
        val snapshot = requireNotNull(document.librarySnapshot)
        assertEquals(document.deviceId, snapshot.deviceId)
        assertEquals(1, snapshot.entries.size)
        val entry = snapshot.entries.single()
        assertEquals("mangabuff", entry.source)
        assertEquals("/manga/1", entry.manga)
        assertEquals("https://mangabuff.ru/manga/1", entry.metadata.mangaUrl)
        assertEquals(123_000L, entry.dateAdded)
        assertEquals(1, document.records.size)
        assertEquals("mangabuff", document.records.single().identity.source)
        assertEquals("/manga/2", document.records.single().identity.manga)
    }

    @Test
    fun `an empty library and an unavailable favorite source remain different complete lists`() = runBlocking {
        prepare()
        val empty = requireNotNull(store.reconcile().document.librarySnapshot)
        assertTrue(empty.entries.isEmpty())
        manga(1, favorite = true, source = -999)
        driver.execute(null, "UPDATE mangas SET url = ? WHERE _id = 1", 1) {
            bindString(0, "/manga/1?provider_access=fixture_secret")
        }.await()
        val document = store.reconcile().document
        val unresolved = requireNotNull(document.librarySnapshot)
        assertEquals(1, unresolved.entries.size)
        assertEquals("local:android:-999", unresolved.entries.single().source)
        assertEquals("id:1", unresolved.entries.single().manga)
        assertNull(unresolved.entries.single().metadata.mangaUrl)
        assertTrue(unresolved.modifiedAt > empty.modifiedAt)
        val encoded = HistorySyncProtocol.encode(document).toString(Charsets.UTF_8)
        assertTrue(!encoded.contains("provider_access") && !encoded.contains("fixture_secret"))
    }

    @Test
    fun `unchanged polls and native favorite timestamp updates keep a durable stamp across restart`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        val first = store.reconcile().document
        clock = 9_000
        driver.execute(null, "UPDATE mangas SET favorite = favorite WHERE _id = 1", 0).await()
        val next = store.reconcile().document
        val restarted = HistorySyncLocalStore(database, sourceManager) { 20_000 }.reconcile().document
        assertEquals(first, next)
        assertEquals(first, restarted)
        assertEquals(0L, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
    }

    @Test
    fun `a peer chapter clock at its limit still applies while library revisions remain independent`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
            VALUES(1,1,'/chapter/1','Chapter 1',0,0,0,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        val peer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val identity = HistorySyncIdentity("mangabuff", "/manga/1", "/chapter/1")
        val maximum = HistorySyncProtocol.MAX_TIME
        val record = HistorySyncRecord(
            identity.recordId(),
            identity,
            progress = HistorySyncProgress(5, 10, maximum, peer),
            completion = HistorySyncCompletion(true, maximum, peer),
            history = HistorySyncHistory(2_000, maximum, peer),
        )
        val incoming = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, peer, listOf(record))
        val first = store.reconcile(listOf(incoming), apply = true)
        assertEquals(1, first.applied)
        assertTrue(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
        assertEquals(4L, database.chaptersQueries.getChapterById(1).awaitAsOne().last_page_read)
        assertEquals(2_000L, database.historyQueries.getHistoryByMangaId(1).awaitAsList().single().last_read?.time)
        val firstLibrary = requireNotNull(first.document.librarySnapshot)
        assertEquals(1_000L, firstLibrary.modifiedAt)
        assertEquals(maximum, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
        assertEquals(firstLibrary, store.reconcile().document.librarySnapshot)

        manga(2, favorite = true)
        val changed = store.reconcile().document
        val changedLibrary = requireNotNull(changed.librarySnapshot)
        assertEquals(1_001L, changedLibrary.modifiedAt)
        assertEquals(2, changedLibrary.entries.size)
        assertEquals(record.progress, changed.records.single().progress)
        assertEquals(record.completion, changed.records.single().completion)
        assertEquals(record.history, changed.records.single().history)
        assertEquals(maximum, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
    }

    @Test
    fun `same-count replacements and metadata changes advance only the library payload stamp`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        manga(2, favorite = false)
        val first = store.reconcile().document
        driver.execute(null, "UPDATE mangas SET favorite = CASE WHEN _id = 2 THEN 1 ELSE 0 END", 0).await()
        val second = store.reconcile().document
        driver.execute(null, "UPDATE mangas SET title = 'Changed title' WHERE _id = 2", 0).await()
        val third = store.reconcile().document
        val firstLibrary = requireNotNull(first.librarySnapshot)
        val secondLibrary = requireNotNull(second.librarySnapshot)
        val thirdLibrary = requireNotNull(third.librarySnapshot)
        assertEquals(1, firstLibrary.entries.size)
        assertEquals(1, secondLibrary.entries.size)
        assertNotEquals(firstLibrary.entries, secondLibrary.entries)
        assertTrue(secondLibrary.modifiedAt > firstLibrary.modifiedAt)
        assertTrue(thirdLibrary.modifiedAt > secondLibrary.modifiedAt)
        assertEquals("Changed title", thirdLibrary.entries.single().metadata.mangaTitle)
        assertTrue(first.records.isEmpty() && second.records.isEmpty() && third.records.isEmpty())
        driver.execute(null, "UPDATE mangas SET title = '' WHERE _id = 2", 0).await()
        val fourth = requireNotNull(store.reconcile().document.librarySnapshot)
        assertNull(fourth.entries.single().metadata.mangaTitle)
        assertTrue(fourth.modifiedAt > thirdLibrary.modifiedAt)
    }

    @Test
    fun `account snapshots are isolated and peer library lists are never forwarded`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        val first = store.reconcile().document
        val firstStored = database.historySyncQueries.getLibrarySnapshot().awaitAsOne()
        store.selectAccount("second")
        assertNull(database.historySyncQueries.getLibrarySnapshot().awaitAsOneOrNull())
        val second = store.reconcile(listOf(first)).document
        assertNotEquals(first.deviceId, second.deviceId)
        assertEquals(second.deviceId, second.librarySnapshot?.deviceId)
        assertEquals(first.librarySnapshot?.entries, second.librarySnapshot?.entries)
        store.selectAccount("first")
        assertEquals(firstStored, database.historySyncQueries.getLibrarySnapshot().awaitAsOne())
        assertEquals(first, store.reconcile(listOf(second)).document)
    }

    @Test
    fun `newer own cloud stamps are observed and equal stamp conflicts cannot overwrite local state`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        val first = store.reconcile().document
        val initial = requireNotNull(first.librarySnapshot)
        val cloud = first.copy(librarySnapshot = initial.copy(modifiedAt = 5_000, entries = emptyList()))
        val restored = requireNotNull(store.reconcile(listOf(cloud)).document.librarySnapshot)
        assertEquals(5_001L, restored.modifiedAt)
        assertEquals(initial.entries, restored.entries)
        val saved = database.historySyncQueries.getLibrarySnapshot().awaitAsOne()
        val conflict = first.copy(librarySnapshot = restored.copy(entries = emptyList()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.reconcile(listOf(conflict)) } }
        assertEquals(saved, database.historySyncQueries.getLibrarySnapshot().awaitAsOne())
    }

    @Test
    fun `a favorite edit during source resolution retries before any stale snapshot can commit`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        var edited = false
        every { provider.getMangaUrl(any()) } answers {
            if (!edited) {
                edited = true
                runBlocking { driver.execute(null, "UPDATE mangas SET favorite = 0 WHERE _id = 1", 0).await() }
            }
            "https://mangabuff.ru" + firstArg<SManga>().url
        }
        val result = store.reconcile().document
        assertTrue(edited)
        assertTrue(requireNotNull(result.librarySnapshot).entries.isEmpty())
        val persisted = HistorySyncProtocol.json.decodeFromString<HistorySyncLibrarySnapshot>(
            database.historySyncQueries.getLibrarySnapshot().awaitAsOne(),
        )
        assertEquals(result.librarySnapshot, persisted)
    }

    @Test
    fun `the serialized size limit retains the previous snapshot and logical clock atomically`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        val initial = store.reconcile().document
        val persisted = database.historySyncQueries.getLibrarySnapshot().awaitAsOne()
        val observed = database.historySyncQueries.getMeta().awaitAsOne().logical_clock
        for (id in 2L..600L) manga(id, favorite = true)
        driver.execute(null, "UPDATE mangas SET title = ?", 1) {
            bindString(0, "x".repeat(15_000))
        }.await()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.reconcile() } }
        assertEquals(persisted, database.historySyncQueries.getLibrarySnapshot().awaitAsOne())
        assertEquals(observed, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
        assertTrue(database.historySyncQueries.getRecords().awaitAsList().isEmpty())
        assertEquals(1, initial.librarySnapshot?.entries?.size)
    }

    @Test
    fun `a malformed peer snapshot fails before authoring or applying any local state`() = runBlocking {
        prepare()
        manga(1, favorite = true)
        val author = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val malformed = HistorySyncDocument(
            HistorySyncProtocol.FORMAT,
            1,
            author,
            emptyList(),
            HistorySyncLibrarySnapshot(
                HistorySyncLibraryProtocol.FORMAT,
                1,
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                1,
                emptyList(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile(listOf(malformed), apply = true) }
        }
        assertNull(database.historySyncQueries.getLibrarySnapshot().awaitAsOneOrNull())
        assertEquals(0L, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
        assertEquals(1, database.historySyncQueries.getLocalFavorites().awaitAsList().size)
    }

    private suspend fun prepare() {
        Database.Schema.create(driver).await()
        store.initialize()
        store.selectAccount("first")
    }

    private suspend fun manga(id: Long, favorite: Boolean, source: Long = 123) {
        driver.execute(
            null,
            """
            INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added)
            VALUES($id,$source,'/manga/$id','Title $id',0,${if (favorite) 1 else 0},1,0,0,0,123000)
            """.trimIndent(),
            0,
        ).await()
    }
}
