package eu.kanade.tachiyomi.data.historysync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
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
class HistorySyncManager internal constructor(
    private val database: Database,
    val preferences: HistorySyncPreferences,
    private val credentials: HistorySyncCredentialStore,
    private val store: HistorySyncLocalStore,
    private val runtime: HistorySyncRuntime,
) {
    constructor(
        context: Context,
        database: Database,
        preferences: HistorySyncPreferences,
        credentials: HistorySyncCredentialStore,
        store: HistorySyncLocalStore,
    ) : this(
        database,
        preferences,
        credentials,
        store,
        HistorySyncRuntime(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            dispatcher = Dispatchers.IO,
            pendingChanges = database.historySyncQueries.getPendingCount().subscribeToOne(),
            configureWork = { HistorySyncJob.configure(context, it) },
            enqueueWork = { HistorySyncJob.enqueue(context) },
            transportFactory = { account ->
                when (account.provider) {
                    HistorySyncProvider.WEBDAV -> WebDavHistorySyncTransport(account)
                    HistorySyncProvider.GOOGLE_DRIVE -> GoogleDriveHistorySyncTransport(
                        GoogleDriveAuthorization(context).tokensFor(account),
                    )
                }
            },
        ),
    )

    enum class Phase { IDLE, SYNCING, SUCCESS, FAILED, NEEDS_CREDENTIALS, NEEDS_GOOGLE_RECONNECT, DISABLED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val lastSuccess: Long? = null,
        val applied: Int = 0,
        val unmatched: Int = 0,
    )

    private val scope get() = runtime.scope
    private val mutex = Mutex()
    private val credentialChanges = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(
        Status(phase = if (preferences.enabled.get()) Phase.IDLE else Phase.DISABLED),
    )
    val status = mutableStatus.asStateFlow()

    @Volatile private var running: Job? = null
    private var started = false

    @Volatile private var isForeground = false
    private var changingAccount = false
    private var automaticEnabled: Boolean? = null
    private var foregroundPolling: Job? = null
    private var localChanges: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.initialize()
            runtime.pendingChanges.collect { pending ->
                if (pending > 0 && preferences.enabled.get()) {
                    scheduleLocalChanges()
                    if (!isForeground) runtime.enqueueWork()
                }
            }
        }
        scope.launch {
            requests.receiveAsFlow().collect {
                // Disabling or changing accounts cancels this operation, not
                // the long-lived request collector needed after re-enabling.
                supervisorScope { launch { syncNow() }.join() }
            }
        }
        scope.launch {
            preferences.enabled.changes().distinctUntilChanged().collect {
                updateEnabled()
            }
        }
        updateEnabled()
    }

    @Synchronized
    fun foreground() {
        start()
        if (isForeground) return
        isForeground = true
        startForegroundPolling()
        requestSync()
    }

    @Synchronized
    fun background() {
        isForeground = false
        foregroundPolling?.cancel()
        foregroundPolling = null
        if (preferences.enabled.get()) runtime.enqueueWork()
    }

    fun requestSync() {
        if (preferences.enabled.get()) requests.trySend(Unit)
    }

    fun setEnabled(enabled: Boolean) {
        preferences.enabled.set(enabled)
        start()
        updateEnabled()
    }

    @Synchronized
    private fun updateEnabled() {
        val enabled = preferences.enabled.get()
        if (automaticEnabled == enabled) return
        automaticEnabled = enabled
        runtime.configureWork(enabled)
        if (enabled) {
            startForegroundPolling()
            requestSync()
        } else {
            foregroundPolling?.cancel()
            foregroundPolling = null
            localChanges?.cancel()
            localChanges = null
            running?.cancel()
            while (requests.tryReceive().isSuccess) {
                // Discard obsolete automatic requests.
            }
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.DISABLED)
        }
    }

    @Synchronized
    private fun startForegroundPolling() {
        if (!isForeground || !preferences.enabled.get() || foregroundPolling?.isActive == true) return
        foregroundPolling = scope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_MILLIS)
                requestSync()
            }
        }
    }

    @Synchronized
    private fun scheduleLocalChanges() {
        if (!preferences.enabled.get() || localChanges?.isActive == true) return
        localChanges = scope.launch {
            // Start the deadline at the first committed change. A quiet-period
            // debounce would never fire while the reader keeps changing pages.
            delay(LOCAL_CHANGES_MILLIS)
            synchronized(this@HistorySyncManager) { localChanges = null }
            requestSync()
        }
    }

    suspend fun readCredentials(): HistorySyncCredentials? = withContext(runtime.dispatcher) { credentials.load() }

    suspend fun saveCredentials(value: HistorySyncCredentials) = withContext(runtime.dispatcher) {
        val normalized = when (value.provider) {
            HistorySyncProvider.WEBDAV -> {
                val root = WebDavHistorySyncTransport.validateRoot(value.root).toString()
                require(
                    value.username.isNotBlank() && value.username.none { it == ':' || it.isISOControl() } &&
                        value.password.isNotEmpty() && value.password.none { it.isISOControl() },
                ) { "Enter your WebDAV login and app password" }
                value.copy(root = root)
            }
            HistorySyncProvider.GOOGLE_DRIVE -> {
                require(
                    value.googleProjectNumber.matches(Regex("[0-9]{1,32}")) &&
                        value.googlePermissionId.matches(Regex("[0-9]{1,128}")) && value.googleEmail.isNotBlank() &&
                        value.googleEmail.none(Char::isISOControl),
                ) { "Invalid Google account identity" }
                value.copy(root = "", username = "", password = "")
            }
        }
        credentialChanges.withLock {
            // A queued sync must not start another old-account pull while the
            // cancelled operation is releasing the shared operation mutex.
            synchronized(this@HistorySyncManager) {
                changingAccount = true
                running?.cancel()
            }
            try {
                mutex.withLock {
                    store.initialize()
                    // Native history writers share this transaction lock: actions
                    // cannot be tagged with the old account after credentials save.
                    database.transaction {
                        credentials.save(normalized)
                        store.selectAccount(accountKey(normalized))
                    }
                }
                mutableStatus.value = Status(phase = if (preferences.enabled.get()) Phase.IDLE else Phase.DISABLED)
            } finally {
                synchronized(this@HistorySyncManager) { changingAccount = false }
                requestSync()
            }
        }
    }

    /** Disconnect only this installation; revoking Google's shared project grant
     * would also disconnect the user's other phone. Cloud replicas are retained. */
    suspend fun disconnect() = withContext(runtime.dispatcher) {
        credentialChanges.withLock {
            synchronized(this@HistorySyncManager) {
                changingAccount = true
                running?.cancel()
            }
            try {
                mutex.withLock {
                    preferences.enabled.set(false)
                    credentials.clear()
                    mutableStatus.value = Status(phase = Phase.DISABLED)
                }
                updateEnabled()
            } finally {
                synchronized(this@HistorySyncManager) { changingAccount = false }
            }
        }
    }

    /** False requests a later WorkManager retry. Error details deliberately omit
     * server response bodies, URLs, and credentials from UI and logs. */
    suspend fun syncNow(): Boolean = withContext(runtime.dispatcher) {
        mutex.withLock {
            val operation = currentCoroutineContext().job
            val canSync = synchronized(this@HistorySyncManager) {
                (preferences.enabled.get() && !changingAccount).also { enabled ->
                    if (enabled) running = operation
                }
            }
            if (!canSync) return@withLock true
            try {
                currentCoroutineContext().ensureActive()
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.SYNCING)
                val account = credentials.load()
                if (account == null) {
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.NEEDS_CREDENTIALS)
                    return@withLock true
                }
                store.initialize()
                store.selectAccount(accountKey(account))
                store.reconcile()
                currentCoroutineContext().ensureActive()
                val transport = runtime.transportFactory(account)
                val replicas = transport.pull()
                currentCoroutineContext().ensureActive()
                // The store batch-validates every replica before any application.
                val result = store.reconcile(replicas, apply = true)
                currentCoroutineContext().ensureActive()
                // A successful pull must actually contain our current replica.
                // A cache of an earlier upload would miss a deleted cloud file.
                val uploaded = replicas.any { replica ->
                    replica.deviceId == result.document.deviceId &&
                        replica.records.associateBy { it.id } == result.document.records.associateBy { it.id }
                }
                if (!uploaded) transport.push(result.document)
                currentCoroutineContext().ensureActive()
                mutableStatus.value =
                    Status(Phase.SUCCESS, runtime.now(), result.applied, result.unmatched)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: GoogleDriveReconnectRequired) {
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.NEEDS_GOOGLE_RECONNECT)
                true
            } catch (_: Exception) {
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED)
                false
            } finally {
                running = null
                if (mutableStatus.value.phase == Phase.SYNCING) {
                    mutableStatus.value = mutableStatus.value.copy(
                        phase = if (preferences.enabled.get()) Phase.IDLE else Phase.DISABLED,
                    )
                }
            }
        }
    }

    private fun accountKey(account: HistorySyncCredentials): String = MessageDigest.getInstance("SHA-256")
        .digest(
            when (account.provider) {
                HistorySyncProvider.WEBDAV ->
                    "${WebDavHistorySyncTransport.validateRoot(account.root)}\n${account.username}"
                HistorySyncProvider.GOOGLE_DRIVE ->
                    "google-drive:${account.googleProjectNumber}:${account.googlePermissionId}"
            }.toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val FOREGROUND_POLL_MILLIS = 10_000L
        const val LOCAL_CHANGES_MILLIS = 1_500L
    }
}

/** Platform effects and time are replaceable so lifecycle and cancellation can
 * be verified with virtual time, without Android or a live WebDAV account. */
internal class HistorySyncRuntime(
    val scope: CoroutineScope,
    val dispatcher: CoroutineDispatcher,
    val pendingChanges: Flow<Long>,
    val configureWork: (Boolean) -> Unit,
    val enqueueWork: () -> Unit,
    val transportFactory: (HistorySyncCredentials) -> HistorySyncTransport = { WebDavHistorySyncTransport(it) },
    val now: () -> Long = System::currentTimeMillis,
)
