package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class ReaderPageUpscaler(
    app: Application,
    private val readerPreferences: ReaderPreferences,
    networkHelper: NetworkHelper,
) {

    private val cacheRoot = File(app.cacheDir, "reader_ai_cache_v12").apply { mkdirs() }
    private val serialProcessingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val remotePrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageLocks = ConcurrentHashMap<String, Mutex>()
    private val scheduledJobs = ConcurrentHashMap<String, Job>()
    private val forcedBlockingPages = ConcurrentHashMap<String, Unit>()
    private val remoteQueuedPages = LinkedHashMap<String, RemotePrefetchTask>()
    private val remoteQueueMutex = Mutex()
    private val remotePrefetchSignal = Channel<Unit>(Channel.CONFLATED)
    private val remotePrefetchSequence = AtomicLong(0L)
    private val anime4xPageUpscaler = Anime4xPageUpscaler(app, readerPreferences)
    private val ncnnPageUpscaler = NcnnPageUpscaler(app)
    private val remotePageUpscaler = RemotePageUpscaler(app, readerPreferences, networkHelper)

    @Volatile
    private var lastFailureMessage: String? = null

    @Volatile
    private var remoteRetainedPaths: Set<String> = emptySet()

    init {
        repeat(REMOTE_PREFETCH_LANE_COUNT) { lane ->
            remotePrefetchScope.launch {
                runRemotePrefetchLoop(lane)
            }
        }
    }

    fun isEnabled(): Boolean = readerPreferences.upscalePagesX2.get()

    suspend fun processPage(
        page: ReaderPage,
        source: BufferedSource,
        allowBlocking: Boolean = true,
        schedulePrefetchOnMiss: Boolean = true,
        fallbackToSourceOnFailure: Boolean = true,
    ): BufferedSource {
        if (!isEnabled()) {
            return source
        }

        val cacheFile = cacheFile(page)
        if (cacheFile.isReadyCacheFile()) {
            return cacheFile.toBuffer()
        }

        val forceBlocking = forcedBlockingPages.remove(cacheFile.absolutePath) != null
        if (!allowBlocking && !forceBlocking) {
            if (schedulePrefetchOnMiss) {
                schedulePrefetch(page)
            }
            return source
        }

        val backendMode = ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get())
        if (backendMode == ReaderPreferences.AiBackendMode.REMOTE && (allowBlocking || forceBlocking)) {
            dropRemotePrefetch(cacheFile.absolutePath)
        }

        val lock = pageLocks.getOrPut(cacheFile.absolutePath) { Mutex() }
        return lock.withLock {
            if (cacheFile.isReadyCacheFile()) {
                return@withLock cacheFile.toBuffer()
            }

            lastFailureMessage = null
            val processedBytes = runCatching {
                when (backendMode) {
                    ReaderPreferences.AiBackendMode.GPU -> ncnnPageUpscaler.upscaleSource(source)
                    ReaderPreferences.AiBackendMode.CPU,
                    ReaderPreferences.AiBackendMode.NPU,
                    ->
                        anime4xPageUpscaler.upscaleSource(source)
                    ReaderPreferences.AiBackendMode.REMOTE -> remotePageUpscaler.upscaleSource(source)
                }
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Failed to AI-upscale reader page ${cacheFile.name}" } }
                .getOrNull()

            if (processedBytes == null) {
                lastFailureMessage = when (backendMode) {
                    ReaderPreferences.AiBackendMode.GPU -> ncnnPageUpscaler.consumeLastErrorMessage()
                    ReaderPreferences.AiBackendMode.CPU,
                    ReaderPreferences.AiBackendMode.NPU,
                    ->
                        anime4xPageUpscaler.consumeLastErrorMessage()
                    ReaderPreferences.AiBackendMode.REMOTE -> remotePageUpscaler.consumeLastErrorMessage()
                } ?: "AI upscale did not produce an output image"
                if (!fallbackToSourceOnFailure) {
                    error(lastFailureMessage ?: "AI upscale did not produce an output image")
                }
                return@withLock source
            }

            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytesAtomically(processedBytes)
            lastFailureMessage = null
            Buffer().write(processedBytes)
        }
    }

    suspend fun prefetchPage(page: ReaderPage) {
        if (!isEnabled()) {
            return
        }

        val stream = page.stream ?: return
        val cacheFile = cacheFile(page)
        if (cacheFile.isReadyCacheFile()) {
            return
        }

        val original = stream().use { Buffer().readFrom(it) }
        processPage(page, original)
    }

    fun schedulePrefetch(
        page: ReaderPage,
        priority: Int = Int.MAX_VALUE,
        lane: Int = REMOTE_PRIMARY_LANE,
    ) {
        if (!isEnabled()) {
            return
        }

        if (isRemoteBackendSelected()) {
            enqueueRemotePrefetch(page, priority, lane)
            return
        }

        val cachePath = cacheFile(page).absolutePath
        val existingJob = scheduledJobs[cachePath]
        if (existingJob?.isActive == true) {
            return
        }

        val scheduledJob = processingScopeForCurrentBackend().launch {
            try {
                awaitPageReady(page)
                prefetchPage(page)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to schedule AI-upscale for reader page ${page.index}" }
            } finally {
                val currentJob = coroutineContext[Job]
                if (currentJob != null) {
                    scheduledJobs.remove(cachePath, currentJob)
                } else {
                    scheduledJobs.remove(cachePath)
                }
            }
        }
        scheduledJobs[cachePath] = scheduledJob
    }

    private fun processingScopeForCurrentBackend(): CoroutineScope {
        return when (ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get())) {
            ReaderPreferences.AiBackendMode.GPU,
            ReaderPreferences.AiBackendMode.CPU,
            ReaderPreferences.AiBackendMode.NPU,
            -> serialProcessingScope
            ReaderPreferences.AiBackendMode.REMOTE -> serialProcessingScope
        }
    }

    fun retainScheduledPrefetches(pages: Collection<ReaderPage>) {
        val keepPaths = pages.map { cacheFile(it).absolutePath }.toHashSet()
        if (isRemoteBackendSelected()) {
            remoteRetainedPaths = keepPaths
            scheduledJobs.entries.forEach { entry ->
                entry.value.cancel()
                scheduledJobs.remove(entry.key, entry.value)
            }
            remotePrefetchScope.launch {
                remoteQueueMutex.withLock {
                    remoteQueuedPages.entries.removeIf { it.key !in keepPaths }
                }
                remotePrefetchSignal.trySend(Unit)
            }
            return
        }

        remoteRetainedPaths = emptySet()
        remotePrefetchScope.launch {
            remoteQueueMutex.withLock {
                remoteQueuedPages.clear()
            }
        }
        scheduledJobs.entries.forEach { entry ->
            if (entry.key !in keepPaths) {
                entry.value.cancel()
                scheduledJobs.remove(entry.key, entry.value)
            }
        }
    }

    fun requestBlockingReload(page: ReaderPage) {
        if (!isEnabled()) {
            return
        }

        forcedBlockingPages[cacheFile(page).absolutePath] = Unit
    }

    fun hasCachedPage(page: ReaderPage): Boolean {
        return cacheFile(page).isReadyCacheFile()
    }

    fun consumeLastFailureMessage(): String? {
        return lastFailureMessage.also { lastFailureMessage = null }
    }

    private suspend fun awaitPageReady(page: ReaderPage) {
        if (page.status == Page.State.Ready) {
            return
        }

        val loader = page.chapter.pageLoader ?: return
        coroutineScope {
            val loadJob = if (page.status == Page.State.Queue || page.status is Page.State.Error) {
                launch(Dispatchers.IO) {
                    loader.loadPage(page)
                }
            } else {
                null
            }

            try {
                page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            } finally {
                loadJob?.cancelAndJoin()
            }
        }
    }

    fun retainChapters(
        mangaId: Long,
        chapterIds: Set<Long>,
    ) {
        val mangaDir = File(cacheRoot, mangaId.toString())
        if (!mangaDir.exists()) {
            return
        }

        val keepDirNames = chapterIds.map(Long::toString).toSet()
        mangaDir.listFiles()
            ?.filter { it.isDirectory && it.name !in keepDirNames }
            ?.forEach { it.deleteRecursively() }
    }

    fun clearTransientChapters(
        mangaId: Long,
        keepChapterId: Long?,
    ) {
        retainChapters(
            mangaId = mangaId,
            chapterIds = keepChapterId?.let(::setOf) ?: emptySet(),
        )
    }

    private fun cacheFile(page: ReaderPage): File {
        val chapter = page.chapter.chapter
        val mangaId = chapter.manga_id ?: 0L
        val chapterId = chapter.id ?: 0L
        val pageRole = if (page is InsertPage) "insert" else "base"
        val backendMode = ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get())
        val backend = buildString {
            append(backendMode.name.lowercase())
            if (backendMode == ReaderPreferences.AiBackendMode.REMOTE) {
                append('-')
                append(readerPreferences.remoteAiModel.get().cacheKey)
            }
        }
        val extension = when (backendMode) {
            ReaderPreferences.AiBackendMode.GPU -> "jpg"
            ReaderPreferences.AiBackendMode.CPU, ReaderPreferences.AiBackendMode.NPU -> "png"
            ReaderPreferences.AiBackendMode.REMOTE -> "jpg"
        }
        return File(
            File(cacheRoot, mangaId.toString()),
            buildString {
                append(chapterId)
                append('/')
                append(backend)
                append('-')
                append(page.index.toString().padStart(3, '0'))
                append('-')
                append(pageRole)
                append('.')
                append(extension)
            },
        )
    }

    private fun File.toBuffer(): BufferedSource {
        return inputStream().use { Buffer().readFrom(it) }
    }

    private fun File.writeBytesAtomically(bytes: ByteArray) {
        parentFile?.mkdirs()
        val tmpFile = File(parentFile, "$name.tmp-${UUID.randomUUID()}")
        tmpFile.writeBytes(bytes)
        if (!tmpFile.renameTo(this)) {
            writeBytes(bytes)
            tmpFile.delete()
        }
    }

    private fun File.isReadyCacheFile(): Boolean {
        return isFile && length() > 0L
    }

    private suspend fun runRemotePrefetchLoop(lane: Int) {
        while (true) {
            val nextTask = remoteQueueMutex.withLock {
                remoteQueuedPages.values
                    .filter { it.lane == lane && it.cachePath in remoteRetainedPaths }
                    .minWithOrNull(compareBy<RemotePrefetchTask> { it.priority }.thenBy { it.sequence })
                    ?.also { remoteQueuedPages.remove(it.cachePath) }
            }

            if (nextTask == null) {
                remotePrefetchSignal.receive()
                continue
            }

            try {
                if (nextTask.cachePath !in remoteRetainedPaths || cacheFile(nextTask.page).isReadyCacheFile()) {
                    continue
                }
                awaitPageReady(nextTask.page)
                if (nextTask.cachePath !in remoteRetainedPaths || cacheFile(nextTask.page).isReadyCacheFile()) {
                    continue
                }
                prefetchPage(nextTask.page)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to schedule AI-upscale for reader page ${nextTask.page.index}" }
            }
        }
    }

    private fun enqueueRemotePrefetch(
        page: ReaderPage,
        priority: Int,
        lane: Int,
    ) {
        val cachePath = cacheFile(page).absolutePath
        if (cacheFile(page).isReadyCacheFile()) {
            return
        }

        remotePrefetchScope.launch {
            remoteQueueMutex.withLock {
                val existingTask = remoteQueuedPages[cachePath]
                if (existingTask != null && existingTask.priority <= priority) {
                    return@withLock
                }
                remoteQueuedPages[cachePath] = RemotePrefetchTask(
                    cachePath = cachePath,
                    page = page,
                    priority = priority,
                    lane = lane,
                    sequence = remotePrefetchSequence.incrementAndGet(),
                )
            }
            remotePrefetchSignal.trySend(Unit)
        }
    }

    private fun dropRemotePrefetch(cachePath: String) {
        remotePrefetchScope.launch {
            remoteQueueMutex.withLock {
                remoteQueuedPages.remove(cachePath)
            }
        }
    }

    private fun isRemoteBackendSelected(): Boolean {
        return ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get()) ==
            ReaderPreferences.AiBackendMode.REMOTE
    }

    private data class RemotePrefetchTask(
        val cachePath: String,
        val page: ReaderPage,
        val priority: Int,
        val lane: Int,
        val sequence: Long,
    )

    companion object {
        const val REMOTE_PRIMARY_LANE = 0
        const val REMOTE_SECONDARY_LANE = 1
        private const val REMOTE_PREFETCH_LANE_COUNT = 2
    }
}
