package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

class HistorySyncLibraryStateStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = Database(
        driver,
        historyAdapter = History.Adapter(DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
        chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
    )
    private val provider = mockk<HttpSource>().also { source ->
        every { source.id } returns 123L
        every { source.baseUrl } returns "https://mangabuff.ru"
        every { source.getMangaUrl(any()) } answers { "https://mangabuff.ru" + firstArg<SManga>().url }
        every { source.getChapterUrl(any()) } answers { "https://mangabuff.ru" + firstArg<SChapter>().url }
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } answers {
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched title" }, emptyList())
        }
    }
    private val sources = mockk<SourceManager>().also { manager ->
        every { manager.isInitialized } returns MutableStateFlow(true)
        every { manager.get(any<Long>()) } answers { if (firstArg<Long>() == 123L) provider else null }
        every { manager.getAll() } returns listOf(provider)
    }
    private val peer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val store = HistorySyncLocalStore(database, sources) { 10_000L }

    @BeforeEach
    fun prepare() = runBlocking {
        Database.Schema.create(driver).await()
        store.initialize()
        store.selectAccount("first")
    }

    @AfterEach
    fun close() = driver.close()

    private suspend fun manga(id: Long = 1, favorite: Boolean = false, source: Long = 123L) {
        driver.execute(
            null,
            """
            INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added)
            VALUES($id,$source,'/manga/$id','Title $id',0,${if (favorite) 1 else 0},1,0,0,0,123000)
            """.trimIndent(),
            0,
        ).await()
    }

    private suspend fun favorite(value: Boolean, id: Long = 1) {
        driver.execute(null, "UPDATE mangas SET favorite = ${if (value) 1 else 0} WHERE _id = $id", 0).await()
    }

    private fun remote(id: Long = 1, included: Boolean = true, time: Long = 5_000_000_000_000): HistorySyncDocument {
        val source = "mangabuff"
        val manga = "/manga/$id"
        val record = HistorySyncLibraryRecord(
            HistorySyncLibraryProtocol.titleId(source, manga),
            source,
            manga,
            HistorySyncMetadata(mangaTitle = "Remote title $id", mangaUrl = "https://mangabuff.ru$manga"),
            234_000,
            HistorySyncLibraryMembership(included, time, peer),
        )
        return HistorySyncDocument(
            HistorySyncProtocol.FORMAT,
            1,
            peer,
            emptyList(),
            libraryState = HistorySyncLibraryStateProtocol.state(listOf(record)),
        )
    }

    @Test
    fun `baseline seeds only actual unread favorites at zero and not read nonfavorites`() = runBlocking {
        store.selectAccount("")
        manga(1, favorite = true)
        manga(2)
        store.selectAccount("fresh")
        val document = store.reconcile().document
        val record = requireNotNull(document.libraryState).records.single { it.source == "mangabuff" }
        assertTrue(record.membership.included)
        assertEquals(0L, record.membership.modifiedAt)
        assertEquals("/manga/1", record.manga)
        assertTrue(document.records.isEmpty())
    }

    @Test
    fun `actual native changes capture durable outbox but unchanged favorite writes do not`() = runBlocking {
        manga()
        favorite(true)
        val pending = database.historySyncLibraryQueries.getPending().awaitAsList().single()
        assertTrue(pending.included)
        assertTrue(pending.event_clock > 0)
        driver.execute(
            null,
            "UPDATE mangas SET favorite = favorite, title = 'Metadata changed' WHERE _id = 1",
            0,
        ).await()
        assertEquals(listOf(pending), database.historySyncLibraryQueries.getPending().awaitAsList())
        favorite(false)
        val second = database.historySyncLibraryQueries.getPending().awaitAsList().last()
        assertFalse(second.included)
        assertTrue(second.event_clock > pending.event_clock)
    }

    @Test
    fun `rollback rolls back native favorite clock and outbox together`() = runBlocking {
        manga()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.transaction {
                    favorite(true)
                    error("rollback")
                }
            }
        }
        assertFalse(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertTrue(database.historySyncLibraryQueries.getPending().awaitAsList().isEmpty())
        assertEquals(0L, database.historySyncLibraryQueries.getMeta().awaitAsOne().logical_clock)
    }

    @Test
    fun `rapid offline toggles survive process restart and retain final false tombstone`() = runBlocking {
        manga()
        favorite(true)
        favorite(false)
        favorite(true)
        favorite(false)
        val restarted = HistorySyncLocalStore(database, sources) { 10_000L }
        val first = restarted.reconcile().document
        val record = requireNotNull(first.libraryState).records.single()
        assertFalse(record.membership.included)
        assertTrue(record.membership.modifiedAt > 0)
        assertTrue(database.historySyncLibraryQueries.getPending().awaitAsList().isEmpty())
        assertEquals(first.libraryState, store.reconcile().document.libraryState)
    }

    @Test
    fun `remote additions and removals apply to exact existing rows without local echo`() = runBlocking {
        manga()
        store.reconcile()
        val add = store.reconcile(listOf(remote()), apply = true)
        assertTrue(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertEquals(1, add.libraryApplied)
        assertTrue(database.historySyncLibraryQueries.getPending().awaitAsList().isEmpty())
        val remove = store.reconcile(listOf(remote(included = false, time = 5_000_000_000_001)), apply = true)
        assertFalse(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertEquals(1, remove.libraryApplied)
        assertTrue(database.historySyncLibraryQueries.getPending().awaitAsList().isEmpty())
    }

    @Test
    fun `native edit after observing a future remote stamp gets a newer independent library clock`() = runBlocking {
        manga()
        store.reconcile(listOf(remote()), apply = true)
        favorite(false)
        val record = requireNotNull(store.reconcile().document.libraryState).records.single()
        assertFalse(record.membership.included)
        assertEquals(5_000_000_000_001, record.membership.modifiedAt)
        assertEquals(0L, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
    }

    @Test
    fun `account switch isolates pending membership and stored tombstones`() = runBlocking {
        manga()
        favorite(true)
        store.selectAccount("second")
        favorite(false)
        val second = requireNotNull(store.reconcile().document.libraryState).records.single()
        assertFalse(second.membership.included)
        store.selectAccount("first")
        val first = requireNotNull(store.reconcile().document.libraryState).records.single()
        assertTrue(first.membership.included)
        assertTrue(first.membership.deviceId != second.membership.deviceId)
    }

    @Test
    fun `removal survives deleted title and missing source without raw URL leaks`() = runBlocking {
        manga(favorite = true)
        store.reconcile()
        every { sources.get(any<Long>()) } returns null
        driver.execute(null, "UPDATE mangas SET url = '/private?secret=do-not-publish' WHERE _id = 1", 0).await()
        driver.execute(null, "DELETE FROM mangas WHERE _id = 1", 0).await()
        val document = store.reconcile().document
        val record = requireNotNull(document.libraryState).records.single { it.source == "mangabuff" }
        assertEquals("mangabuff", record.source)
        assertFalse(record.membership.included)
        val encoded = HistorySyncProtocol.encode(document).toString(Charsets.UTF_8)
        assertFalse(encoded.contains("secret="))
    }

    @Test
    fun `malformed peer membership prevents all chapter and membership commits`() = runBlocking {
        manga()
        favorite(true)
        val before = database.historySyncLibraryQueries.getPending().awaitAsList()
        val incoming = remote().let { doc ->
            doc.copy(
                libraryState = requireNotNull(doc.libraryState).copy(
                    records = listOf(
                        requireNotNull(doc.libraryState).records.single().copy(id = "bad"),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile(listOf(incoming), apply = true) }
        }
        assertEquals(before, database.historySyncLibraryQueries.getPending().awaitAsList())
        assertTrue(database.historySyncLibraryQueries.getRecords().awaitAsList().isEmpty())
    }

    @Test
    fun `unresolved additions do not block existing removals or require placeholder chapters`() = runBlocking {
        manga(favorite = true)
        store.reconcile()
        val result = store.reconcile(listOf(remote(included = false), remote(id = 2)), apply = true)
        assertFalse(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertEquals(1, result.libraryApplied)
        assertEquals(1, result.libraryPending)
        assertEquals(1, database.mangasQueries.getAllManga().awaitAsList().size)
        assertTrue(result.document.records.isEmpty())
    }

    @Test
    fun `new iPhone unread title materializes by exact route without fabricated history`() = runBlocking {
        store.reconcile(listOf(remote()), apply = true)
        assertEquals(1, store.materializeLibrary())
        val native = database.mangasQueries.getAllManga().awaitAsList().single()
        assertTrue(native.favorite)
        assertEquals("/manga/1", native.url)
        assertEquals("Fetched title", native.title)
        assertTrue(database.historySyncLibraryQueries.getPending().awaitAsList().isEmpty())
        assertTrue(store.reconcile().document.records.isEmpty())
        coVerify(exactly = 1) { provider.getMangaUpdate(any(), any(), true, true) }
    }

    @Test
    fun `account switch during metadata fetch discards materialization`() = runBlocking {
        store.reconcile(listOf(remote()), apply = true)
        coEvery { provider.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            store.selectAccount("different")
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched" }, emptyList())
        }
        assertEquals(0, store.materializeLibrary())
        assertTrue(database.mangasQueries.getAllManga().awaitAsList().isEmpty())
    }

    @Test
    fun `newer remote removal during fetch prevents late addition`() = runBlocking {
        store.reconcile(listOf(remote()), apply = true)
        coEvery { provider.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            store.reconcile(listOf(remote(included = false, time = 5_000_000_000_001)), apply = true)
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched" }, emptyList())
        }
        assertEquals(0, store.materializeLibrary())
        assertTrue(database.mangasQueries.getAllManga().awaitAsList().isEmpty())
    }

    @Test
    fun `failed title is cooled down so a later valid addition can materialize`() = runBlocking {
        store.reconcile(listOf(remote(id = 1), remote(id = 2)), apply = true)
        coEvery { provider.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            val input = firstArg<SManga>()
            if (input.url == "/manga/1") error("rate limited")
            SMangaUpdate(input.copy().apply { title = "Fetched" }, emptyList())
        }
        store.materializeLibrary()
        store.materializeLibrary()
        assertEquals(listOf("/manga/2"), database.mangasQueries.getAllManga().awaitAsList().map { it.url })
        assertTrue(store.libraryIssues().any { it.source == "mangabuff" && it.retryAt > 10_000 })
    }

    @Test
    fun `an overlong native title remains represented without blocking either library envelope`() = runBlocking {
        manga(favorite = true)
        driver.execute(null, "UPDATE mangas SET title = ? WHERE _id = 1", 1) {
            bindString(0, "T".repeat(17_000))
        }.await()
        val document = store.reconcile().document
        assertEquals(1, requireNotNull(document.libraryState).records.size)
        assertEquals(null, requireNotNull(document.libraryState).records.single().metadata.mangaTitle)
        assertEquals(null, requireNotNull(document.librarySnapshot).entries.single().metadata.mangaTitle)
    }

    @Test
    fun `pending newer local action cannot hide a conflicting obsolete stored stamp`() = runBlocking {
        manga(favorite = true)
        val original = requireNotNull(store.reconcile().document.libraryState).records.single()
        favorite(false)
        val corrupted = remote().copy(
            libraryState = HistorySyncLibraryStateProtocol.state(
                listOf(
                    original.copy(membership = original.membership.copy(included = false)),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile(listOf(corrupted), apply = true) }
        }
        assertEquals(1, database.historySyncLibraryQueries.getPending().awaitAsList().size)
    }

    @Test
    fun `materialized real chapters allow cloud read state to match on the following sync`() = runBlocking {
        val chapter = SChapter.create().apply {
            url = "/chapter/1"
            name = "Chapter 1"
            chapter_number = 1f
        }
        coEvery { provider.getMangaUpdate(any(), any(), any(), any()) } answers {
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched" }, listOf(chapter))
        }
        val identity = HistorySyncIdentity("mangabuff", "/manga/1", "/chapter/1")
        val history = HistorySyncRecord(
            identity.recordId(),
            identity,
            progress = HistorySyncProgress(5, 10, 100, peer),
            completion = HistorySyncCompletion(true, 100, peer),
            history = HistorySyncHistory(100, 100, peer),
        )
        val incoming = remote().copy(records = listOf(history))
        store.reconcile(listOf(incoming), apply = true)
        assertEquals(1, store.materializeLibrary())
        val result = store.reconcile(listOf(incoming), apply = true)
        assertEquals(1, result.applied)
        assertTrue(database.chaptersQueries.getChaptersByMangaId(1, false).awaitAsList().single().read)
        assertEquals(4L, database.chaptersQueries.getChaptersByMangaId(1, false).awaitAsList().single().last_page_read)
    }

    @Test
    fun `native library intent during fetch prevents committing its obsolete snapshot`() = runBlocking {
        store.reconcile(listOf(remote()), apply = true)
        coEvery { provider.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            manga(id = 9, favorite = true)
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched" }, emptyList())
        }
        assertEquals(0, store.materializeLibrary())
        assertEquals(listOf("/manga/9"), database.mangasQueries.getAllManga().awaitAsList().map { it.url })
        assertEquals(1, database.historySyncLibraryQueries.getPending().awaitAsList().size)
    }

    @Test
    fun `exhausted membership intent remains pending while valid chapter history still applies`() = runBlocking {
        manga()
        val incoming = remote(time = HistorySyncProtocol.MAX_TIME)
        store.reconcile(listOf(incoming), apply = true)
        favorite(false)
        val pending = database.historySyncLibraryQueries.getPending().awaitAsList().single()
        assertEquals(HistorySyncProtocol.MAX_TIME + 1, pending.event_clock)
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
            VALUES(1,1,'/chapter/1','Chapter 1',0,0,0,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        val identity = HistorySyncIdentity("mangabuff", "/manga/1", "/chapter/1")
        val chapter = HistorySyncRecord(
            identity.recordId(),
            identity,
            progress = HistorySyncProgress(5, 10, 100, peer),
            completion = HistorySyncCompletion(true, 100, peer),
            history = HistorySyncHistory(100, 100, peer),
        )
        val result = store.reconcile(listOf(incoming.copy(records = listOf(chapter))), apply = true)
        assertEquals(1, result.applied)
        assertTrue(result.libraryClockExhausted)
        assertEquals(1, result.libraryPending)
        assertFalse(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertTrue(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
        assertEquals(listOf(pending), database.historySyncLibraryQueries.getPending().awaitAsList())
        assertEquals(100L, database.historySyncQueries.getMeta().awaitAsOne().logical_clock)
        assertEquals(incoming.libraryState, result.document.libraryState)
    }

    @Test
    fun `favorited native route migration captures old removal and new addition at different stamps`() = runBlocking {
        manga(favorite = true)
        store.reconcile()
        driver.execute(null, "UPDATE mangas SET url = '/manga/2' WHERE _id = 1", 0).await()
        val events = database.historySyncLibraryQueries.getPending().awaitAsList()
        assertEquals(listOf(false, true), events.map { it.included })
        assertEquals(listOf("/manga/1", "/manga/2"), events.map { it.manga_url })
        assertTrue(events[1].event_clock > events[0].event_clock)
        val state = requireNotNull(store.reconcile().document.libraryState)
        assertFalse(state.records.single { it.manga == "/manga/1" }.membership.included)
        assertTrue(state.records.single { it.manga == "/manga/2" }.membership.included)
        assertTrue(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
    }

    @Test
    fun `new unresolved route never borrows the previous portable binding`() = runBlocking {
        manga(favorite = true)
        store.reconcile()
        every { sources.get(any<Long>()) } returns null
        driver.execute(null, "UPDATE mangas SET url = '/new?provider_secret=private' WHERE _id = 1", 0).await()
        val document = store.reconcile().document
        val records = requireNotNull(document.libraryState).records
        assertFalse(records.single { it.source == "mangabuff" }.membership.included)
        assertTrue(records.single { it.source.startsWith("local:android:") }.membership.included)
        assertFalse(HistorySyncProtocol.encode(document).toString(Charsets.UTF_8).contains("provider_secret"))
    }

    @Test
    fun `source installation promotes original decision while newer portable removal wins`() = runBlocking {
        every { sources.get(any<Long>()) } returns null
        manga(favorite = true)
        val unresolved = requireNotNull(store.reconcile().document.libraryState).records.single()
        every { sources.get(any<Long>()) } answers { if (firstArg<Long>() == 123L) provider else null }
        val removed = remote(included = false)
        val promoted = requireNotNull(store.reconcile(listOf(removed), apply = true).document.libraryState)
        assertFalse(promoted.records.single { it.id == unresolved.id }.membership.included)
        assertTrue(
            promoted.records.single {
                it.id == unresolved.id
            }.membership.modifiedAt > unresolved.membership.modifiedAt,
        )
        val portable = promoted.records.single { it.source == "mangabuff" }
        assertFalse(portable.membership.included)
        assertEquals(requireNotNull(removed.libraryState).records.single().membership, portable.membership)
        assertFalse(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        val restarted = HistorySyncLocalStore(database, sources) { 10_000L }
        assertEquals(promoted, restarted.reconcile().document.libraryState)
        assertEquals(2, promoted.records.size)
    }

    @Test
    fun `foreign and unscoped opaque removals cannot overwrite a fresh devices reused native id`() = runBlocking {
        every { sources.get(any<Long>()) } returns null
        manga(favorite = true)
        val foreign = listOf("local:android:$peer:123", "local:android:123").map { source ->
            HistorySyncLibraryRecord(
                HistorySyncLibraryProtocol.titleId(source, "id:1"),
                source,
                "id:1",
                HistorySyncMetadata(mangaTitle = "Other installation title"),
                membership = HistorySyncLibraryMembership(false, 5_000_000_000_000, peer),
            )
        }
        val incoming = remote().copy(libraryState = HistorySyncLibraryStateProtocol.state(foreign))
        val initial = store.reconcile(listOf(incoming), apply = true).document
        val own = requireNotNull(initial.libraryState).records.single { it.membership.included }
        assertEquals("local:android:${initial.deviceId}:123", own.source)
        assertTrue(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        // The separately maintained 0.1.38 snapshot contract is unchanged.
        assertEquals("local:android:123", requireNotNull(initial.librarySnapshot).entries.single().source)
        every { sources.get(any<Long>()) } answers { if (firstArg<Long>() == 123L) provider else null }
        val promoted = requireNotNull(store.reconcile(listOf(incoming), apply = true).document.libraryState)
        val portable = promoted.records.single { it.source == "mangabuff" }
        assertTrue(portable.membership.included)
        assertEquals(own.membership, portable.membership)
        assertTrue(database.mangasQueries.getMangaById(1).awaitAsOne().favorite)
        assertEquals(foreign.toSet(), promoted.records.filter { it.id in foreign.map { value -> value.id } }.toSet())
        assertEquals(4, promoted.records.size)
    }

    @Test
    fun `whole document size failure preserves native intent outbox and journal`() = runBlocking {
        manga()
        favorite(true)
        val before = database.historySyncLibraryQueries.getPending().awaitAsList()
        val records = (1..600).map { index ->
            val manga = "/manga/$index"
            HistorySyncLibraryRecord(
                HistorySyncLibraryProtocol.titleId("mangabuff", manga),
                "mangabuff",
                manga,
                HistorySyncMetadata(mangaTitle = "T".repeat(16_000)),
                membership = HistorySyncLibraryMembership(true, 1, peer),
            )
        }
        val incoming = remote().copy(libraryState = HistorySyncLibraryStateProtocol.state(records))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile(listOf(incoming), apply = true) }
        }
        assertEquals(before, database.historySyncLibraryQueries.getPending().awaitAsList())
        assertTrue(database.historySyncLibraryQueries.getRecords().awaitAsList().isEmpty())
    }
}
