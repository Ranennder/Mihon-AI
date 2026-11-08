package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.source.service.SourceManager
import java.util.Date

class HistorySyncLocalStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = Database(
        driver,
        historyAdapter = History.Adapter(DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
        chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
    )
    private val sourceReady = MutableStateFlow(true)
    private val sourceManager = mockk<SourceManager>().also { manager ->
        every { manager.get(any<Long>()) } returns null
        every { manager.isInitialized } returns sourceReady
    }
    private val store = HistorySyncLocalStore(database, sourceManager)
    private val remoteDevice = "ffffffff-ffff-4fff-8fff-ffffffffffff"

    private suspend fun prepare(read: Boolean = false, page: Long = 0, date: Long = 0) {
        Database.Schema.create(driver).await()
        driver.execute(
            null,
            """
            INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added)
            VALUES(1,123,'/manga/1','Example',0,1,1,0,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
            VALUES(1,1,'/chapter/1','Chapter 1',${if (read) 1 else 0},0,$page,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        database.historyQueries.upsert(1, Date(date), 1234)
        store.initialize()
        store.selectAccount("fixture-account")
    }

    private fun remote(
        read: Boolean = true,
        page: Long = 8,
        history: Long? = 2000,
        time: Long = 2000,
    ): HistorySyncDocument {
        val identity = HistorySyncIdentity("local:android:123", "/manga/1", "/chapter/1")
        val record = HistorySyncRecord(
            identity.recordId(),
            identity,
            progress = HistorySyncProgress(page, 10, time, remoteDevice),
            completion = HistorySyncCompletion(read, time, remoteDevice),
            history = HistorySyncHistory(history, time, remoteDevice),
        )
        return HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, remoteDevice, listOf(record))
    }

    @AfterEach
    fun close() {
        driver.close()
    }

    @Test
    fun `native action and durable outbox commit or roll back together`() = runBlocking {
        prepare()
        val repository = ChapterRepositoryImpl(database)
        try {
            database.transaction {
                repository.update(
                    ChapterUpdate(1, read = true, lastPageRead = 3, syncProgressPage = 4, syncTotalPages = 10),
                )
                error("rollback fixture")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
        repository.update(ChapterUpdate(1, read = true, lastPageRead = 3, syncProgressPage = 4, syncTotalPages = 10))
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        assertEquals(4L, pending.progress_page)
        assertEquals(true, pending.completion_read)
        assertTrue(pending.event_clock > 0)
    }

    @Test
    fun `remote apply preserves reading duration and emits no outbox echo`() = runBlocking {
        prepare()
        store.reconcile(listOf(remote()), apply = true)
        val chapter = database.chaptersQueries.getChapterById(1).awaitAsOne()
        assertTrue(chapter.read)
        assertEquals(7L, chapter.last_page_read)
        assertEquals(1234L, database.historyQueries.getReadDuration().awaitAsOne())
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
        assertEquals(2000L, database.historyQueries.getHistoryByMangaId(1).awaitAsList().single().last_read?.time)
        // A stale reader's ordinary page write never carries cached false.
        ChapterRepositoryImpl(
            database,
        ).update(ChapterUpdate(1, lastPageRead = 2, syncProgressPage = 3, syncTotalPages = 10))
        assertEquals(null, database.historySyncQueries.getPending().awaitAsList().single().completion_read)
        val merged = store.reconcile().document.records.single()
        assertTrue(merged.completion.read)
        assertEquals(3L, merged.progress.page)
    }

    @Test
    fun `untouched first binding cannot override cloud read at seed one`() = runBlocking {
        prepare()
        val result = store.reconcile(listOf(remote(time = 1, history = null)), apply = true)
        assertTrue(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
        assertTrue(result.document.records.single().completion.read)
    }

    @Test
    fun `explicit unread survives restart and history deletion does not change read duration`() = runBlocking {
        prepare(read = true, page = 5, date = 1000)
        store.reconcile()
        ChapterRepositoryImpl(database).update(ChapterUpdate(1, read = false, lastPageRead = 0, syncProgressPage = 0))
        HistoryRepositoryImpl(database).resetHistoryByMangaId(1)
        val second = HistorySyncLocalStore(database, sourceManager)
        second.initialize()
        val result = second.reconcile(listOf(remote(time = 1500)), apply = true).document.records.single()
        assertFalse(result.completion.read)
        assertEquals(0L, result.progress.page)
        assertEquals(null, result.history.lastReadAt)
        assertEquals(1234L, database.historyQueries.getReadDuration().awaitAsOne())
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
        assertEquals(result, second.reconcile().document.records.single())
    }

    @Test
    fun `corrupt incoming batch preserves native values and unprocessed outbox`() = runBlocking {
        prepare()
        ChapterRepositoryImpl(database).update(ChapterUpdate(1, lastPageRead = 1, syncProgressPage = 2))
        val broken = remote().copy(version = 2)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile(listOf(remote(), broken), apply = true) }
        }
        assertFalse(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
        assertEquals(1L, database.chaptersQueries.getChapterById(1).awaitAsOne().last_page_read)
        assertEquals(1L, database.historySyncQueries.getPendingCount().awaitAsOne())
    }

    @Test
    fun `ambiguous native matches are retained without applying to either chapter`() = runBlocking {
        prepare()
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload)
            VALUES(2,1,'/chapter/1','Duplicate',0,0,0,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        val result = store.reconcile(listOf(remote()), apply = true)
        assertEquals(1, result.unmatched)
        assertEquals(0, result.applied)
        assertTrue(database.chaptersQueries.getChaptersByMangaId(1, 0).awaitAsList().none { it.read })
        assertEquals(1, result.document.records.size)
    }

    @Test
    fun `history reading event observes remote clock without duplicating duration`() = runBlocking {
        prepare()
        val future = System.currentTimeMillis() + 100_000
        store.reconcile(listOf(remote(time = future)), apply = true)
        HistoryRepositoryImpl(database).upsertHistory(HistoryUpdate(1, Date(3000), 22))
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        assertTrue(pending.event_clock > future)
        assertEquals(1256L, database.historyQueries.getReadDuration().awaitAsOne())
        val result = store.reconcile().document.records.single()
        assertEquals(3000L, result.history.lastReadAt)
        assertEquals(future, result.completion.modifiedAt)
    }

    @Test
    fun `live history refreshes preserve duration until native pause accounts the session once`() = runBlocking {
        prepare()
        val repository = HistoryRepositoryImpl(database)
        repeat(3) { index -> repository.upsertHistory(HistoryUpdate(1, Date(2000L + index), 0)) }
        assertEquals(1234L, database.historyQueries.getReadDuration().awaitAsOne())
        assertEquals(2002L, store.reconcile().document.records.single().history.lastReadAt)
        repository.upsertHistory(HistoryUpdate(1, Date(3000), 22))
        assertEquals(1256L, database.historyQueries.getReadDuration().awaitAsOne())
        assertEquals(3000L, store.reconcile().document.records.single().history.lastReadAt)
    }

    @Test
    fun `duplicate native identities retain actions until a single chapter can own them`() = runBlocking {
        prepare(read = true, page = 4, date = 1000)
        driver.execute(
            null,
            """
            INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,
                                 chapter_number,source_order,date_fetch,date_upload)
            VALUES(2,1,'/chapter/1','Duplicate',1,0,4,1,0,0,0)
            """.trimIndent(),
            0,
        ).await()
        ChapterRepositoryImpl(database).update(
            ChapterUpdate(1, read = false, lastPageRead = 0, syncProgressPage = 0),
        )
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        assertTrue(store.reconcile().document.records.isEmpty())
        assertEquals(listOf(pending), database.historySyncQueries.getPending().awaitAsList())
        driver.execute(null, "DELETE FROM chapters WHERE _id = 2", 0).await()
        val restored = store.reconcile().document.records.single()
        assertFalse(restored.completion.read)
        assertEquals(0L, restored.progress.page)
        assertEquals(pending.event_clock, restored.completion.modifiedAt)
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
    }

    @Test
    fun `account switch retains old pending actions without exporting its unmatched cloud records`() = runBlocking {
        prepare()
        val foreignIdentity = HistorySyncIdentity("other-site", "/other-manga", "/other-chapter")
        val foreignRecord = remote().records.single().copy(id = foreignIdentity.recordId(), identity = foreignIdentity)
        val first = store.reconcile(listOf(remote().copy(records = listOf(foreignRecord)))).document
        ChapterRepositoryImpl(database).update(ChapterUpdate(1, lastPageRead = 2, syncProgressPage = 3))
        assertEquals(1L, database.historySyncQueries.getPendingCount().awaitAsOne())
        store.selectAccount("second-account")
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
        val second = store.reconcile().document
        assertNotEquals(first.deviceId, second.deviceId)
        assertTrue(second.records.none { it.id == foreignRecord.id })
        store.selectAccount("fixture-account")
        assertEquals(1L, database.historySyncQueries.getPendingCount().awaitAsOne())
        val restored = store.reconcile().document
        assertEquals(first.deviceId, restored.deviceId)
        assertTrue(restored.records.any { it.id == foreignRecord.id })
    }

    @Test
    fun `never configured sync does not accumulate an outbox while local reading still seeds later`() = runBlocking {
        prepare()
        store.selectAccount("")
        val repository = ChapterRepositoryImpl(database)
        repeat(20) { repository.update(ChapterUpdate(1, lastPageRead = it.toLong(), syncProgressPage = it + 1L)) }
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
        store.selectAccount("first-account")
        assertEquals(20L, store.reconcile().document.records.single().progress.page)
    }

    @Test
    fun `account aliases can merge different enrollments from the same installation`() = runBlocking {
        prepare(page = 1, date = 1000)
        val first = store.reconcile().document
        ChapterRepositoryImpl(database).update(ChapterUpdate(1, lastPageRead = 4, syncProgressPage = 5))
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        store.selectAccount("same-folder-alias")
        val second = store.reconcile().document
        assertNotEquals(first.deviceId, second.deviceId)
        assertNotEquals(first.records.single().progress.deviceId, second.records.single().progress.deviceId)
        assertEquals(1000L, first.records.single().progress.modifiedAt)
        assertEquals(1000L, second.records.single().progress.modifiedAt)
        val combined = store.reconcile(listOf(first), apply = true).document
        assertEquals(HistorySyncProtocol.merge(second.deviceId, first, second), combined)
        store.selectAccount("fixture-account")
        assertEquals(listOf(pending), database.historySyncQueries.getPending().awaitAsList())
    }

    @Test
    fun `exhausted remote clock retains the next local action instead of losing an LWW tie`() = runBlocking {
        prepare()
        store.reconcile(listOf(remote(time = HistorySyncProtocol.MAX_TIME)), apply = true)
        ChapterRepositoryImpl(database).update(
            ChapterUpdate(1, lastPageRead = 1, syncProgressPage = 2, syncTotalPages = 10),
        )
        assertEquals(1L, database.chaptersQueries.getChapterById(1).awaitAsOne().last_page_read)
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        assertEquals(HistorySyncProtocol.MAX_TIME + 1, pending.event_clock)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reconcile() }
        }
        assertTrue(error.message.orEmpty().contains("clock is exhausted"))
        assertEquals(listOf(pending), database.historySyncQueries.getPending().awaitAsList())
    }

    @Test
    fun `source startup preserves pending actions and allows native writes before identity resolution`() = runBlocking {
        prepare()
        sourceReady.value = false
        val repository = ChapterRepositoryImpl(database)
        repository.update(ChapterUpdate(1, lastPageRead = 1, syncProgressPage = 2))
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { store.reconcile() }
        assertFalse(attempt.isCompleted)
        assertEquals(1L, database.historySyncQueries.getPendingCount().awaitAsOne())
        assertTrue(database.historySyncQueries.getRecords().awaitAsList().isEmpty())
        // A native write can still commit while source initialization is pending.
        repository.update(ChapterUpdate(1, lastPageRead = 2, syncProgressPage = 3))
        assertEquals(2L, database.historySyncQueries.getPendingCount().awaitAsOne())
        sourceReady.value = true
        assertEquals(3L, attempt.await().document.records.single().progress.page)
        assertEquals(0L, database.historySyncQueries.getPendingCount().awaitAsOne())
    }

    @Test
    fun `finishing an already read chapter beats an earlier unseen cloud unread intent`() = runBlocking {
        prepare(read = true, page = 3, date = 1000)
        store.reconcile()
        ChapterRepositoryImpl(database).update(
            ChapterUpdate(1, read = true, lastPageRead = 9, syncProgressPage = 10, syncTotalPages = 10),
        )
        val pending = database.historySyncQueries.getPending().awaitAsList().single()
        assertEquals(true, pending.completion_read)
        val result = store.reconcile(listOf(remote(read = false, page = 2, history = 1500, time = 1500)), apply = true)
        assertTrue(result.document.records.single().completion.read)
        assertEquals(pending.event_clock, result.document.records.single().completion.modifiedAt)
        assertEquals(10L, result.document.records.single().progress.page)
        assertTrue(database.chaptersQueries.getChapterById(1).awaitAsOne().read)
    }
}
