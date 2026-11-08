package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.io.IOException

class HistorySyncManagerTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterEach
    fun close() = drivers.forEach { it.close() }

    @Test
    fun `opening syncs immediately and idle foreground pulls every ten seconds`() = runTest {
        val fixture = fixture()
        fixture.manager.foreground()
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        fixture.manager.start()
        fixture.manager.foreground()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, fixture.transport.pulls)
        assertEquals(listOf(true), fixture.configurations)
    }

    @Test
    fun `background stops polling and returning syncs immediately`() = runTest {
        val fixture = fixture()
        fixture.manager.foreground()
        runCurrent()
        fixture.manager.background()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        assertEquals(1, fixture.enqueued)
        fixture.manager.foreground()
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, fixture.transport.pulls)
    }

    @Test
    fun `enabling while open syncs immediately and disabling stops all timers`() = runTest {
        val fixture = fixture(enabled = false)
        fixture.manager.foreground()
        runCurrent()
        assertEquals(HistorySyncManager.Phase.DISABLED, fixture.manager.status.value.phase)
        assertEquals(0, fixture.transport.pulls)
        fixture.manager.setEnabled(true)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        fixture.pending.tryEmit(1)
        runCurrent()
        fixture.manager.setEnabled(false)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        assertEquals(HistorySyncManager.Phase.DISABLED, fixture.manager.status.value.phase)
        fixture.manager.setEnabled(true)
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, fixture.transport.pulls)
        assertEquals(listOf(false, true, false, true), fixture.configurations)
    }

    @Test
    fun `continuous local changes cannot postpone the first batch past fifteen hundred milliseconds`() = runTest {
        val fixture = fixture()
        fixture.manager.start()
        runCurrent()
        repeat(6) { index ->
            fixture.pending.tryEmit(index.toLong() + 1)
            runCurrent()
            advanceTimeBy(250)
        }
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        repeat(6) { index ->
            fixture.pending.tryEmit(index.toLong() + 7)
            runCurrent()
            advanceTimeBy(250)
        }
        runCurrent()
        assertEquals(3, fixture.transport.pulls)
        assertEquals(12, fixture.enqueued)
    }

    @Test
    fun `a burst of committed changes is coalesced before the deadline`() = runTest {
        val fixture = fixture()
        fixture.manager.start()
        runCurrent()
        repeat(5) { fixture.pending.tryEmit(it.toLong() + 1) }
        runCurrent()
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
    }

    @Test
    fun `foreground local changes do not bypass batching through WorkManager`() = runTest {
        val fixture = fixture()
        fixture.manager.foreground()
        runCurrent()
        fixture.pending.tryEmit(1)
        runCurrent()
        assertEquals(0, fixture.enqueued)
        fixture.manager.background()
        assertEquals(1, fixture.enqueued)
        fixture.pending.tryEmit(2)
        runCurrent()
        assertEquals(2, fixture.enqueued)
    }

    @Test
    fun `manual requests bypass a pending local batch delay`() = runTest {
        val fixture = fixture()
        fixture.manager.start()
        runCurrent()
        fixture.pending.tryEmit(1)
        runCurrent()
        fixture.manager.requestSync()
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `slow pull serializes foreground manual and local triggers and conflates their backlog`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            release.await()
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        repeat(5) {
            fixture.manager.requestSync()
            fixture.pending.tryEmit(it.toLong() + 1)
            runCurrent()
        }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, fixture.transport.pulls)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        assertEquals(1, fixture.transport.maximumActive)
    }

    @Test
    fun `failed pull does not apply or upload and next foreground poll retries`() = runTest {
        val fixture = fixture()
        fixture.transport.onPull = { throw IOException("offline") }
        fixture.manager.foreground()
        runCurrent()
        assertEquals(HistorySyncManager.Phase.FAILED, fixture.manager.status.value.phase)
        coVerify(exactly = 0) { fixture.store.reconcile(any(), true) }
        assertTrue(fixture.transport.pushes.isEmpty())
        fixture.transport.onPull = { emptyList() }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
        assertEquals(1, fixture.transport.pushes.size)
    }

    @Test
    fun `failed upload is retried even when no local action follows`() = runTest {
        val fixture = fixture()
        fixture.transport.onPush = { throw IOException("offline") }
        fixture.manager.foreground()
        runCurrent()
        assertEquals(HistorySyncManager.Phase.FAILED, fixture.manager.status.value.phase)
        fixture.transport.onPush = { fixture.transport.replicas = listOf(it) }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
        assertEquals(2, fixture.transport.pushes.size)
    }

    @Test
    fun `disabling cancels an in flight pull and reenabling preserves the collector`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            release.await()
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        fixture.manager.setEnabled(false)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        coVerify(exactly = 0) { fixture.store.reconcile(any(), true) }
        assertTrue(fixture.transport.pushes.isEmpty())
        fixture.manager.setEnabled(true)
        runCurrent()
        assertEquals(2, fixture.transport.pulls)
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
    }

    @Test
    fun `cancellation is checked after a transport that ignores cancellation returns`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            withContext(NonCancellable) { release.await() }
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        fixture.manager.setEnabled(false)
        release.complete(Unit)
        runCurrent()
        coVerify(exactly = 0) { fixture.store.reconcile(any(), true) }
        assertTrue(fixture.transport.pushes.isEmpty())
        assertEquals(HistorySyncManager.Phase.DISABLED, fixture.manager.status.value.phase)
    }

    @Test
    fun `changing accounts cancels old pull and immediately syncs new account`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        val oldTransport = fixture.transport
        oldTransport.onPull = {
            release.await()
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        val newTransport = FakeTransport()
        fixture.transport = newTransport
        fixture.manager.saveCredentials(fixture.account!!.copy(username = "second@example.com"))
        runCurrent()
        assertEquals("second@example.com", fixture.account!!.username)
        assertTrue(oldTransport.pushes.isEmpty())
        assertEquals(1, newTransport.pulls)
        coVerify(exactly = 1) { fixture.store.reconcile(any(), true) }
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
    }

    @Test
    fun `queued background sync cannot start another old account pull ahead of credential save`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            release.await()
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        val queuedWorker = launch { fixture.manager.syncNow() }
        runCurrent()
        val newAccount = fixture.account!!.copy(username = "second@example.com")
        fixture.transport = FakeTransport()
        fixture.manager.saveCredentials(newAccount)
        runCurrent()
        queuedWorker.join()
        assertEquals(listOf("reader@example.com", "second@example.com"), fixture.requestedAccounts.map { it.username })
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
    }

    @Test
    fun `saving credentials while disabled preserves disabled status and does no network work`() = runTest {
        val fixture = fixture(enabled = false)
        fixture.manager.start()
        runCurrent()
        fixture.manager.saveCredentials(fixture.account!!)
        runCurrent()
        assertEquals(HistorySyncManager.Phase.DISABLED, fixture.manager.status.value.phase)
        assertEquals(0, fixture.transport.pulls)
    }

    @Test
    fun `invalid Basic auth credentials are rejected before cancelling a running sync`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            release.await()
            emptyList()
        }
        fixture.manager.foreground()
        runCurrent()
        val account = fixture.account!!
        val invalidCredentials = listOf(
            account.copy(username = "reader:other"),
            account.copy(username = "reader\nother"),
            account.copy(password = "secret\rvalue"),
        )
        for (invalid in invalidCredentials) {
            var rejected = false
            try {
                fixture.manager.saveCredentials(invalid)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(account, fixture.account)
            assertEquals(HistorySyncManager.Phase.SYNCING, fixture.manager.status.value.phase)
        }
        release.complete(Unit)
        runCurrent()
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
    }

    @Test
    fun `external cancellation leaves usable status and a later request succeeds`() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onPull = {
            release.await()
            emptyList()
        }
        val operation = launch { fixture.manager.syncNow() }
        runCurrent()
        operation.cancel()
        runCurrent()
        assertEquals(HistorySyncManager.Phase.IDLE, fixture.manager.status.value.phase)
        release.complete(Unit)
        assertTrue(fixture.manager.syncNow())
        assertEquals(HistorySyncManager.Phase.SUCCESS, fixture.manager.status.value.phase)
    }

    @Test
    fun `missing credentials reports actionable status and never opens a transport`() = runTest {
        val fixture = fixture()
        fixture.account = null
        assertTrue(fixture.manager.syncNow())
        assertEquals(HistorySyncManager.Phase.NEEDS_CREDENTIALS, fixture.manager.status.value.phase)
        assertEquals(0, fixture.transport.pulls)
    }

    @Test
    fun `unchanged current cloud replica avoids upload but a deleted replica is recreated`() = runTest {
        val fixture = fixture()
        assertTrue(fixture.manager.syncNow())
        assertTrue(fixture.manager.syncNow())
        assertEquals(2, fixture.transport.pulls)
        assertEquals(1, fixture.transport.pushes.size)
        fixture.transport.replicas = emptyList()
        assertTrue(fixture.manager.syncNow())
        assertEquals(2, fixture.transport.pushes.size)
    }

    @Test
    fun `another devices equal records never suppress upload of our own replica`() = runTest {
        val fixture = fixture()
        fixture.transport.replicas = listOf(fixture.document.copy(deviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        assertTrue(fixture.manager.syncNow())
        assertEquals(1, fixture.transport.pushes.size)
    }

    @Test
    fun `record ordering does not cause redundant uploads`() = runTest {
        val fixture = fixture()
        fixture.transport.replicas = listOf(fixture.document.copy(records = fixture.document.records.reversed()))
        assertTrue(fixture.manager.syncNow())
        assertEquals(0, fixture.transport.pushes.size)
        assertEquals(HistorySyncManager.Status(HistorySyncManager.Phase.SUCCESS, 0, 2, 3), fixture.manager.status.value)
    }

    @Test
    fun `changed own replica is uploaded even when identity and record count match`() = runTest {
        val fixture = fixture()
        fixture.transport.replicas = listOf(
            fixture.document.copy(
                records = fixture.document.records.map {
                    it.copy(progress = it.progress.copy(page = 1))
                },
            ),
        )
        assertTrue(fixture.manager.syncNow())
        assertEquals(listOf(fixture.document), fixture.transport.pushes)
    }

    @Test
    fun `sync failure requests WorkManager retry while disabled sync succeeds without network`() = runTest {
        val fixture = fixture()
        fixture.transport.onPull = { throw IOException("offline") }
        assertFalse(fixture.manager.syncNow())
        fixture.manager.setEnabled(false)
        runCurrent()
        assertTrue(fixture.manager.syncNow())
        assertEquals(1, fixture.transport.pulls)
    }

    private suspend fun TestScope.fixture(enabled: Boolean = true): Fixture = Fixture(this, enabled).also {
        Database.Schema.create(it.driver).await()
    }

    private inner class Fixture(scope: TestScope, initiallyEnabled: Boolean) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        private val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
            chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
        )
        val pending = MutableSharedFlow<Long>(extraBufferCapacity = 64)
        val configurations = mutableListOf<Boolean>()
        val requestedAccounts = mutableListOf<HistorySyncCredentials>()
        var enqueued = 0
        var account: HistorySyncCredentials? = HistorySyncCredentials(
            "https://example.com/dav/",
            "reader@example.com",
            "secret",
        )
        var transport = FakeTransport()
        val document = HistorySyncDocument(
            HistorySyncProtocol.FORMAT,
            1,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            listOf("first", "second").map { chapter ->
                val identity = HistorySyncIdentity("https://example.com", "/manga", "/$chapter")
                HistorySyncRecord(
                    id = identity.recordId(),
                    identity = identity,
                    progress = HistorySyncProgress(5, 10, 1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                    completion = HistorySyncCompletion(false, 1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                    history = HistorySyncHistory(1, 1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                )
            },
        )
        val store = mockk<HistorySyncLocalStore> {
            coEvery { initialize() } returns Unit
            coEvery { selectAccount(any()) } returns Unit
            coEvery { reconcile(any(), any()) } answers { HistorySyncLocalStore.Result(document, 2, 3) }
        }
        private val enabled = MutableStateFlow(initiallyEnabled)
        private val enabledPreference = mockk<Preference<Boolean>> {
            every { get() } answers { enabled.value }
            every { set(any()) } answers { enabled.value = firstArg() }
            every { changes() } returns enabled
        }
        private val preferences = mockk<HistorySyncPreferences>().also {
            every { it.enabled } returns enabledPreference
        }
        private val credentials = mockk<HistorySyncCredentialStore> {
            every { load() } answers { account }
            every { save(any()) } answers { account = firstArg() }
        }
        val manager = HistorySyncManager(
            database,
            preferences,
            credentials,
            store,
            HistorySyncRuntime(
                scope = scope.backgroundScope,
                dispatcher = StandardTestDispatcher(scope.testScheduler),
                pendingChanges = pending,
                configureWork = { configurations += it },
                enqueueWork = { enqueued += 1 },
                transportFactory = {
                    requestedAccounts += it
                    transport
                },
                now = { scope.testScheduler.currentTime },
            ),
        )
    }

    private class FakeTransport : HistorySyncTransport {
        var pulls = 0
        val pushes = mutableListOf<HistorySyncDocument>()
        var replicas = emptyList<HistorySyncDocument>()
        private var active = 0
        var maximumActive = 0
        var onPull: suspend () -> List<HistorySyncDocument> = { replicas }
        var onPush: suspend (HistorySyncDocument) -> Unit = { replicas = listOf(it) }

        override suspend fun pull(): List<HistorySyncDocument> {
            pulls += 1
            active += 1
            maximumActive = maxOf(maximumActive, active)
            return try {
                onPull()
            } finally {
                active -= 1
            }
        }

        override suspend fun push(document: HistorySyncDocument) {
            pushes += document
            onPush(document)
        }
    }
}
