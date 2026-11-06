package eu.kanade.tachiyomi.data.historysync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne
import java.security.MessageDigest

/** One coordinator is shared by foreground, manual, and WorkManager triggers.
 * The SQL outbox survives process death; a failed pull never replaces a cloud
 * replica or discards a local action. */
class HistorySyncManager(
    private val context: Context,
    private val database: Database,
    val preferences: HistorySyncPreferences,
    private val credentials: HistorySyncCredentialStore,
    private val store: HistorySyncLocalStore,
) {
    enum class Phase { IDLE, SYNCING, SUCCESS, FAILED, NEEDS_CREDENTIALS, DISABLED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val lastSuccess: Long? = null,
        val applied: Int = 0,
        val unmatched: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    @Volatile private var running: Job? = null
    private var started = false

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.initialize()
            database.historySyncQueries.getPendingCount().subscribeToOne().collect { pending ->
                if (pending > 0 && preferences.enabled.get()) {
                    requests.trySend(Unit)
                    HistorySyncJob.enqueue(context)
                }
            }
        }
        scope.launch {
            requests.receiveAsFlow().debounce(1_500).collect {
                // Disabling or changing accounts cancels this operation, not
                // the long-lived request collector needed after re-enabling.
                supervisorScope { launch { syncNow() }.join() }
            }
        }
        scope.launch {
            preferences.enabled.changes().collect { enabled ->
                HistorySyncJob.configure(context, enabled)
                if (enabled) {
                    requests.trySend(Unit)
                } else {
                    running?.cancel()
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.DISABLED)
                }
            }
        }
    }

    fun foreground() {
        start()
        if (preferences.enabled.get()) requests.trySend(Unit)
    }

    fun background() {
        if (preferences.enabled.get()) HistorySyncJob.enqueue(context)
    }

    fun requestSync() {
        if (preferences.enabled.get()) requests.trySend(Unit)
    }

    fun setEnabled(enabled: Boolean) {
        preferences.enabled.set(enabled)
        if (!enabled) running?.cancel()
    }

    suspend fun readCredentials(): HistorySyncCredentials? = withContext(Dispatchers.IO) { credentials.load() }

    suspend fun saveCredentials(value: HistorySyncCredentials) = withContext(Dispatchers.IO) {
        val root = WebDavHistorySyncTransport.validateRoot(value.root).toString()
        require(value.username.isNotBlank() && value.password.isNotEmpty()) {
            "Enter your WebDAV login and app password"
        }
        // Never let an old in-flight account pull apply after account changes.
        running?.cancel()
        mutex.withLock {
            store.initialize()
            // Native history writers share this transaction lock: actions
            // cannot be tagged with the old account after credentials save.
            database.transaction {
                credentials.save(value.copy(root = root))
                store.selectAccount(accountKey(root, value.username))
            }
        }
        mutableStatus.value = mutableStatus.value.copy(phase = Phase.IDLE)
        requestSync()
    }

    /** False requests a later WorkManager retry. Error details deliberately omit
     * server response bodies, URLs, and credentials from UI and logs. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!preferences.enabled.get()) return@withLock true
            running = currentCoroutineContext().job
            try {
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.SYNCING)
                val account = credentials.load()
                if (account == null) {
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.NEEDS_CREDENTIALS)
                    return@withLock true
                }
                store.initialize()
                store.selectAccount(accountKey(account.root, account.username))
                store.reconcile()
                val transport = WebDavHistorySyncTransport(account)
                val replicas = transport.pull()
                // The store batch-validates every replica before any application.
                val result = store.reconcile(replicas, apply = true)
                transport.push(result.document)
                mutableStatus.value =
                    Status(Phase.SUCCESS, System.currentTimeMillis(), result.applied, result.unmatched)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED)
                false
            } finally {
                running = null
            }
        }
    }

    private fun accountKey(root: String, username: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${WebDavHistorySyncTransport.validateRoot(root)}\n$username".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
