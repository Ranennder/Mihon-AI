package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    private val remoteChapterJobs = ConcurrentHashMap<String, RemoteChapterPrefetchJob>()
    private val remoteChapterMetadata = ConcurrentHashMap<String, RemotePageUpscaler.ChapterJobMetadata>()
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
        val backendMode = ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get())
        val wholeChapterRemoteMode =
            backendMode == ReaderPreferences.AiBackendMode.REMOTE && isRemoteWholeChapterModeSelected()
        if (wholeChapterRemoteMode) {
            page.chapter.pages
                ?.takeIf { it.isNotEmpty() }
                ?.let(::scheduleWholeChapterRemotePrefetch)
        }
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

        if (backendMode == ReaderPreferences.AiBackendMode.REMOTE && (allowBlocking || forceBlocking)) {
            dropRemotePrefetch(cacheFile.absolutePath)
        }

        val lock = pageLocks.getOrPut(cacheFile.absolutePath) { Mutex() }
        return lock.withLock {
            if (cacheFile.isReadyCacheFile()) {
                return@withLock cacheFile.toBuffer()
            }
            if (wholeChapterRemoteMode && waitForWholeChapterCache(page, cacheFile)) {
                return@withLock cacheFile.toBuffer()
            }
            if (wholeChapterRemoteMode && hasActiveWholeChapterJob(page)) {
                throw WholeChapterPagePendingException()
            }

            lastFailureMessage = null
            val processedBytes = runCatching {
                when (backendMode) {
                    ReaderPreferences.AiBackendMode.GPU -> ncnnPageUpscaler.upscaleSource(source)
                    ReaderPreferences.AiBackendMode.CPU,
                    ReaderPreferences.AiBackendMode.NPU,
                    ->
                        anime4xPageUpscaler.upscaleSource(source)
                    ReaderPreferences.AiBackendMode.REMOTE -> remotePageUpscaler.upscaleSource(
                        source = source,
                        pageMetadata = pageRequestMetadata(page),
                    )
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

        if (isRemoteWholeChapterModeSelected()) {
            page.chapter.pages
                ?.takeIf { it.isNotEmpty() }
                ?.let(::scheduleWholeChapterRemotePrefetch)
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

        if (isRemoteWholeChapterModeSelected()) {
            page.chapter.pages
                ?.takeIf { it.isNotEmpty() }
                ?.let(::scheduleWholeChapterRemotePrefetch)
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

    fun scheduleWholeChapterRemotePrefetch(
        pages: List<ReaderPage>,
        mangaTitle: String? = null,
    ) {
        if (!isEnabled() || !isRemoteWholeChapterModeSelected() || pages.isEmpty()) {
            return
        }

        rememberChapterMetadata(
            pages = pages,
            mangaTitle = mangaTitle,
        )

        val firstPage = pages.first()
        val chapter = firstPage.chapter.chapter
        val chapterId = chapter.id ?: return
        val mangaId = chapter.manga_id ?: 0L
        val cacheTargets = pages.map { page ->
            ChapterCacheTarget(
                page = page,
                cacheFile = cacheFile(page),
            )
        }
        val jobKey = buildRemoteChapterJobKey(
            mangaId = mangaId,
            chapterId = chapterId,
        )
        val existingJob = remoteChapterJobs[jobKey]
        if (existingJob?.job?.isActive == true) {
            return
        }

        val job = remotePrefetchScope.launch {
            try {
                runWholeChapterRemotePrefetch(cacheTargets)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to prefetch whole chapter via remote AI ($chapterId)" }
            } finally {
                remoteChapterJobs.remove(jobKey)
                remoteChapterMetadata.remove(jobKey)
            }
        }
        remoteChapterJobs[jobKey] = RemoteChapterPrefetchJob(
            mangaId = mangaId,
            chapterId = chapterId,
            job = job,
        )
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

    fun invalidateRemoteWorkScope() {
        remoteRetainedPaths = emptySet()
        forcedBlockingPages.clear()
        remoteChapterJobs.values.forEach { it.job.cancel() }
        remoteChapterJobs.clear()
        remoteChapterMetadata.clear()
        remotePrefetchScope.launch {
            remoteQueueMutex.withLock {
                remoteQueuedPages.clear()
            }
            remotePrefetchSignal.trySend(Unit)
        }
        remotePageUpscaler.invalidateRemoteWorkScope()
    }

    fun rememberChapterMetadata(
        pages: List<ReaderPage>,
        mangaTitle: String? = null,
    ) {
        if (pages.isEmpty()) {
            return
        }

        val firstPage = pages.first()
        val chapter = firstPage.chapter.chapter
        val chapterId = chapter.id ?: return
        val mangaId = chapter.manga_id ?: 0L
        val jobKey = buildRemoteChapterJobKey(
            mangaId = mangaId,
            chapterId = chapterId,
        )
        val chapterTitle = chapter.name
            .trim()
            .takeIf { it.isNotEmpty() }
        val resolvedMangaTitle = mangaTitle
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: remoteChapterMetadata[jobKey]?.mangaTitle
        remoteChapterMetadata[jobKey] = RemotePageUpscaler.ChapterJobMetadata(
            mangaTitle = resolvedMangaTitle,
            chapterTitle = chapterTitle,
            totalPages = pages.size,
        )
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

        remoteChapterJobs.entries.removeIf { (_, chapterJob) ->
            if (chapterJob.mangaId != mangaId || chapterJob.chapterId in chapterIds) {
                return@removeIf false
            }
            chapterJob.job.cancel()
            remoteChapterMetadata.remove(
                buildRemoteChapterJobKey(
                    mangaId = chapterJob.mangaId,
                    chapterId = chapterJob.chapterId,
                ),
            )
            true
        }
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

    private suspend fun runWholeChapterRemotePrefetch(cacheTargets: List<ChapterCacheTarget>) {
        val pendingTargets = cacheTargets.filterNot { it.cacheFile.isReadyCacheFile() }
        if (pendingTargets.isEmpty()) {
            return
        }

        val firstTarget = pendingTargets.first()
        val chapter = firstTarget.page.chapter.chapter
        val chapterId = chapter.id ?: return
        val mangaId = chapter.manga_id ?: 0L
        val chapterJobKey = buildRemoteChapterJobKey(
            mangaId = mangaId,
            chapterId = chapterId,
        )
        val chapterMetadata = remoteChapterMetadata[chapterJobKey] ?: RemotePageUpscaler.ChapterJobMetadata(
            mangaTitle = null,
            chapterTitle = chapter.name.trim().takeIf { it.isNotEmpty() },
            totalPages = cacheTargets.size,
        )

        val archiveFile = File(cacheRoot, "chapter-upload-${UUID.randomUUID()}.zip")
        val preparedPageCount = try {
            ZipOutputStream(archiveFile.outputStream().buffered()).use { zipOutput ->
                var preparedPageCount = 0
                pendingTargets.forEach { target ->
                    awaitPageReady(target.page)
                    if (target.cacheFile.isReadyCacheFile()) {
                        return@forEach
                    }

                    val stream = target.page.stream ?: return@forEach
                    val sourceBytes = stream().use { input ->
                        Buffer().readFrom(input).readByteArray()
                    }
                    val preparedPage = remotePageUpscaler.prepareChapterUploadPage(
                        pageIndex = target.page.index,
                        sourceBytes = sourceBytes,
                    ) ?: return
                    val entryName = "${preparedPage.pageIndex.toString().padStart(4, '0')}.${preparedPage.extension}"
                    val entryBytes = preparedPage.bytes
                    val entryCrc = CRC32().apply { update(entryBytes) }.value
                    zipOutput.putNextEntry(
                        ZipEntry(entryName).apply {
                            method = ZipEntry.STORED
                            size = entryBytes.size.toLong()
                            compressedSize = size
                            crc = entryCrc
                        },
                    )
                    zipOutput.write(entryBytes)
                    zipOutput.closeEntry()
                    preparedPageCount += 1
                }
                preparedPageCount
            }
        } catch (e: Throwable) {
            archiveFile.delete()
            throw e
        }
        if (preparedPageCount <= 0) {
            archiveFile.delete()
            return
        }

        val chapterJob = try {
            remotePageUpscaler.startChapterJobFromArchive(
                archiveFile = archiveFile,
                pageCount = preparedPageCount,
                metadata = chapterMetadata,
            )
        } finally {
            archiveFile.delete()
        } ?: return
        val remainingTargets = pendingTargets.associateBy { it.page.index }.toMutableMap()
        val nextPollAt = remainingTargets.keys.associateWith { 0L }.toMutableMap()
        val pendingPollCounts = mutableMapOf<Int, Int>()
        val retryableFailureCounts = mutableMapOf<Int, Int>()
        var consecutiveRetryableFailures = 0
        repeat(2_400) {
            val now = System.currentTimeMillis()
            val duePageIndexes = remainingTargets.keys.filter { pageIndex ->
                (nextPollAt[pageIndex] ?: 0L) <= now
            }
            if (duePageIndexes.isEmpty()) {
                val nextDueAt = nextPollAt.values.minOrNull()
                delay(
                    when {
                        nextDueAt == null -> WHOLE_CHAPTER_IDLE_POLL_DELAY_MS
                        else -> (nextDueAt - now)
                            .coerceAtLeast(WHOLE_CHAPTER_MIN_POLL_DELAY_MS)
                            .coerceAtMost(WHOLE_CHAPTER_MAX_POLL_DELAY_MS)
                    },
                )
                return@repeat
            }

            val iterator = remainingTargets.iterator()
            var sawProgress = false
            var sawRetryableFailure = false
            while (iterator.hasNext()) {
                val (pageIndex, target) = iterator.next()
                if (pageIndex !in duePageIndexes) {
                    continue
                }
                if (target.cacheFile.isReadyCacheFile()) {
                    nextPollAt.remove(pageIndex)
                    pendingPollCounts.remove(pageIndex)
                    retryableFailureCounts.remove(pageIndex)
                    iterator.remove()
                    continue
                }

                when (val fetchResult = remotePageUpscaler.fetchChapterPage(chapterJob, pageIndex)) {
                    is RemotePageUpscaler.ChapterPageFetchResult.Pending -> {
                        val pendingCount = pendingPollCounts.getOrDefault(pageIndex, 0) + 1
                        pendingPollCounts[pageIndex] = pendingCount
                        nextPollAt[pageIndex] = now + wholeChapterPendingBackoffMillis(pendingCount)
                    }
                    is RemotePageUpscaler.ChapterPageFetchResult.Cancelled -> {
                        return
                    }
                    is RemotePageUpscaler.ChapterPageFetchResult.Ready -> {
                        target.cacheFile.parentFile?.mkdirs()
                        target.cacheFile.writeBytesAtomically(fetchResult.bytes)
                        nextPollAt.remove(pageIndex)
                        pendingPollCounts.remove(pageIndex)
                        retryableFailureCounts.remove(pageIndex)
                        iterator.remove()
                        sawProgress = true
                    }
                    is RemotePageUpscaler.ChapterPageFetchResult.Failed -> {
                        if (!fetchResult.retryable) {
                            lastFailureMessage = fetchResult.message
                            return
                        }
                        sawRetryableFailure = true
                        val failureCount = retryableFailureCounts.getOrDefault(pageIndex, 0) + 1
                        retryableFailureCounts[pageIndex] = failureCount
                        nextPollAt[pageIndex] = now + wholeChapterRetryableFailureBackoffMillis(failureCount)
                        logcat(LogPriority.WARN) {
                            "Transient remote AI chapter fetch failure for page $pageIndex: ${fetchResult.message}"
                        }
                    }
                }
            }

            if (remainingTargets.isEmpty()) {
                return
            }

            consecutiveRetryableFailures = when {
                sawProgress -> 0
                sawRetryableFailure -> consecutiveRetryableFailures + 1
                else -> 0
            }
            if (consecutiveRetryableFailures >= WHOLE_CHAPTER_RETRYABLE_FAILURE_LIMIT) {
                lastFailureMessage =
                    "Remote AI chapter job stopped responding while waiting for chapter pages"
                return
            }

            if (sawProgress) {
                val acceleratedNextPollAt = System.currentTimeMillis() + WHOLE_CHAPTER_PROGRESS_POLL_DELAY_MS
                nextPollAt.entries.forEach { entry ->
                    if (entry.value > acceleratedNextPollAt) {
                        entry.setValue(acceleratedNextPollAt)
                    }
                }
            }

            delay(
                when {
                    sawProgress -> WHOLE_CHAPTER_PROGRESS_POLL_DELAY_MS
                    sawRetryableFailure -> WHOLE_CHAPTER_RETRYABLE_FAILURE_DELAY_MS
                    else -> WHOLE_CHAPTER_IDLE_POLL_DELAY_MS
                },
            )
        }

        lastFailureMessage = "Remote AI chapter job timed out before all pages were ready"
    }

    private suspend fun waitForWholeChapterCache(
        page: ReaderPage,
        cacheFile: File,
    ): Boolean {
        val attempts = if (hasActiveWholeChapterJob(page)) {
            WHOLE_CHAPTER_ACTIVE_WAIT_ATTEMPTS
        } else {
            WHOLE_CHAPTER_DEFAULT_WAIT_ATTEMPTS
        }

        repeat(attempts) {
            if (cacheFile.isReadyCacheFile()) {
                return true
            }
            delay(100L)
        }
        return cacheFile.isReadyCacheFile()
    }

    private fun hasActiveWholeChapterJob(page: ReaderPage): Boolean {
        val chapter = page.chapter.chapter
        val chapterId = chapter.id ?: return false
        val mangaId = chapter.manga_id ?: 0L
        val jobKey = buildRemoteChapterJobKey(
            mangaId = mangaId,
            chapterId = chapterId,
        )
        return remoteChapterJobs[jobKey]?.job?.isActive == true
    }

    private fun wholeChapterPendingBackoffMillis(pendingCount: Int): Long {
        val normalizedCount = pendingCount.coerceAtLeast(1) - 1
        val delayMillis = WHOLE_CHAPTER_MIN_POLL_DELAY_MS + (normalizedCount * WHOLE_CHAPTER_PENDING_POLL_STEP_MS)
        return delayMillis.coerceAtMost(WHOLE_CHAPTER_MAX_POLL_DELAY_MS)
    }

    private fun wholeChapterRetryableFailureBackoffMillis(failureCount: Int): Long {
        val normalizedCount = failureCount.coerceAtLeast(1) - 1
        val delayMillis =
            WHOLE_CHAPTER_RETRYABLE_FAILURE_DELAY_MS + (normalizedCount * WHOLE_CHAPTER_FAILURE_POLL_STEP_MS)
        return delayMillis.coerceAtMost(WHOLE_CHAPTER_MAX_FAILURE_DELAY_MS)
    }

    private fun pageRequestMetadata(page: ReaderPage): RemotePageUpscaler.PageRequestMetadata? {
        val chapter = page.chapter.chapter
        val chapterId = chapter.id ?: return null
        val mangaId = chapter.manga_id ?: 0L
        val jobKey = buildRemoteChapterJobKey(
            mangaId = mangaId,
            chapterId = chapterId,
        )
        val metadata = remoteChapterMetadata[jobKey] ?: return null
        val totalPages = page.chapter.pages?.size ?: metadata.totalPages
        return RemotePageUpscaler.PageRequestMetadata(
            mangaTitle = metadata.mangaTitle,
            chapterTitle = metadata.chapterTitle,
            pageIndex = page.index,
            totalPages = totalPages,
        )
    }

    private fun isRemoteBackendSelected(): Boolean {
        return ReaderPreferences.normalizeAiBackendMode(readerPreferences.aiBackendMode.get()) ==
            ReaderPreferences.AiBackendMode.REMOTE
    }

    private fun isRemoteWholeChapterModeSelected(): Boolean {
        return isRemoteBackendSelected() && readerPreferences.remoteAiBatchMode.get().shouldQueueWholeChapter
    }

    private fun buildRemoteChapterJobKey(
        mangaId: Long,
        chapterId: Long,
    ): String {
        return buildString {
            append(mangaId)
            append(':')
            append(chapterId)
            append(':')
            append(readerPreferences.remoteAiModel.get().cacheKey)
        }
    }

    private data class RemotePrefetchTask(
        val cachePath: String,
        val page: ReaderPage,
        val priority: Int,
        val lane: Int,
        val sequence: Long,
    )

    private data class ChapterCacheTarget(
        val page: ReaderPage,
        val cacheFile: File,
    )

    private data class RemoteChapterPrefetchJob(
        val mangaId: Long,
        val chapterId: Long,
        val job: Job,
    )

    class WholeChapterPagePendingException : IllegalStateException(
        "Remote AI chapter job is still processing this page",
    )

    companion object {
        const val REMOTE_PRIMARY_LANE = 0
        const val REMOTE_SECONDARY_LANE = 1
        private const val REMOTE_PREFETCH_LANE_COUNT = 2
        private const val WHOLE_CHAPTER_DEFAULT_WAIT_ATTEMPTS = 8
        private const val WHOLE_CHAPTER_ACTIVE_WAIT_ATTEMPTS = 150
        private const val WHOLE_CHAPTER_RETRYABLE_FAILURE_LIMIT = 20
        private const val WHOLE_CHAPTER_MIN_POLL_DELAY_MS = 250L
        private const val WHOLE_CHAPTER_PENDING_POLL_STEP_MS = 200L
        private const val WHOLE_CHAPTER_MAX_POLL_DELAY_MS = 1_500L
        private const val WHOLE_CHAPTER_PROGRESS_POLL_DELAY_MS = 150L
        private const val WHOLE_CHAPTER_IDLE_POLL_DELAY_MS = 600L
        private const val WHOLE_CHAPTER_RETRYABLE_FAILURE_DELAY_MS = 900L
        private const val WHOLE_CHAPTER_FAILURE_POLL_STEP_MS = 400L
        private const val WHOLE_CHAPTER_MAX_FAILURE_DELAY_MS = 2_500L
    }
}
