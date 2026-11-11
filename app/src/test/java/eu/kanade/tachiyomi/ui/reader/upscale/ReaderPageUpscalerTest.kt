package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class ReaderPageUpscalerTest {

    @TempDir
    lateinit var cacheDir: File

    private lateinit var preferences: ReaderPreferences
    private lateinit var remote: RemotePageUpscaler
    private lateinit var upscaler: ReaderPageUpscaler
    private lateinit var page: ReaderPage
    private var sourceReads = 0

    @BeforeEach
    fun setUp() {
        preferences = ReaderPreferences(InMemoryPreferenceStore()) { 1320 }.apply {
            upscalePagesX2.set(true)
            aiBackendMode.set(ReaderPreferences.AiBackendMode.REMOTE)
            remoteAiDirectDownload.set(true)
            remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.SINGLE)
        }
        remote = mockk(relaxed = true)
        val app = mockk<Application>()
        every { app.cacheDir } returns cacheDir
        upscaler = ReaderPageUpscaler(
            app = app,
            readerPreferences = preferences,
            networkHelper = mockk(),
            remotePageUpscaler = remote,
        )
        page = ReaderPage(0).apply {
            chapter = ReaderChapter(
                ChapterImpl().apply {
                    id = 1L
                    manga_id = 2L
                    name = "Chapter"
                    url = "/chapter"
                },
            )
            remoteImageRequest = { Request.Builder().url("https://source.example/page.jpg").build() }
            stream = {
                sourceReads++
                "original".byteInputStream()
            }
        }
    }

    @Test
    fun `direct success and cached display never download the source on the phone`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returns "upscaled".toByteArray()

        assertEquals("upscaled", upscaler.processPage(page).readUtf8())
        assertEquals("upscaled", upscaler.processPage(page).readUtf8())

        assertEquals(0, sourceReads)
        verify(exactly = 1) { remote.upscaleDirectPage(any(), any(), any(), any()) }
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any(), any()) }
    }

    @Test
    fun `failed direct request loads the original only for upload fallback`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returns null
        every { remote.upscaleSource(any(), any(), any(), any()) } answers {
            assertEquals("original", firstArg<okio.BufferedSource>().peek().readUtf8())
            "fallback".toByteArray()
        }

        assertEquals("fallback", upscaler.processPage(page).readUtf8())
        assertEquals(1, sourceReads)
    }

    @Test
    fun `cached pages are isolated by model and arbitrary output width`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } answers {
            arg<ReaderPreferences.RemoteAiConfig>(3).cacheKey.toByteArray()
        }
        val original = upscaler.processPage(page).readUtf8()
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
        assertFalse(upscaler.hasCachedPage(page))
        assertEquals("fast-native-input-v2-w1733", upscaler.processPage(page).readUtf8())
        preferences.remoteAiModel.set(ReaderPreferences.RemoteAiModel.DETAILED)
        assertFalse(upscaler.hasCachedPage(page))
        assertEquals("detailed-native4-native-input-v2-w3960", upscaler.processPage(page).readUtf8())
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.DETAILED, 1733)
        assertFalse(upscaler.hasCachedPage(page))
        assertEquals("detailed-native4-native-input-v2-w1733", upscaler.processPage(page).readUtf8())
        preferences.remoteAiModel.set(ReaderPreferences.RemoteAiModel.FAST)
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, -1)
        assertEquals(original, upscaler.processPage(page).readUtf8())
        verify(exactly = 4) { remote.upscaleDirectPage(any(), any(), any(), any()) }
        verify(atLeast = 1) { remote.invalidateRemoteWorkScope() }
    }

    @Test
    fun `legacy remote images are regenerated without deleting originals or local AI caches`() = runBlocking {
        val chapterCache = File(cacheDir, "reader_ai_cache_v12/2/1").apply { mkdirs() }
        val localCache = File(chapterCache, "gpu-000-base.jpg").apply { writeText("local GPU result") }
        val original = File(cacheDir, "original-page.jpg").apply { writeText("original source bytes") }
        page.stream = {
            sourceReads++
            original.inputStream()
        }
        val legacyCaches = ReaderPreferences.RemoteAiModel.entries.associateWith { model ->
            File(chapterCache, "remote-${model.cacheKey}-w1440-000-base.jpg").apply {
                writeText("legacy pre-reduced input")
            }
        }
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } answers {
            val config = arg<ReaderPreferences.RemoteAiConfig>(3)
            assertEquals(1440, config.targetWidth)
            "${config.model.name} original-size inference".toByteArray()
        }

        for (model in ReaderPreferences.RemoteAiModel.entries) {
            preferences.remoteAiModel.set(model)
            preferences.setRemoteAiTargetWidth(model, 1440)
            assertFalse(upscaler.hasCachedPage(page))
            val expected = "${model.name} original-size inference"
            assertEquals(expected, upscaler.processPage(page).readUtf8())
            assertTrue(upscaler.hasCachedPage(page))
            assertEquals(expected, upscaler.processPage(page).readUtf8())
            assertEquals("legacy pre-reduced input", legacyCaches.getValue(model).readText())
        }

        assertEquals(0, sourceReads)
        assertEquals("original source bytes", original.readText())
        verify(exactly = 2) { remote.upscaleDirectPage(any(), any(), any(), any()) }
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any(), any()) }
        preferences.aiBackendMode.set(ReaderPreferences.AiBackendMode.GPU)
        assertTrue(upscaler.hasCachedPage(page))
        assertEquals("local GPU result", localCache.readText())
    }

    @Test
    fun `a setting change during direct download cancels the stale upload fallback`() {
        val captured = preferences.remoteAiConfig()
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } answers {
            assertEquals(captured, arg<ReaderPreferences.RemoteAiConfig>(3))
            preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
            null
        }
        assertThrows(CancellationException::class.java) { runBlocking { upscaler.processPage(page) } }
        assertFalse(upscaler.hasCachedPage(page))
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any(), any()) }
    }

    @Test
    fun `changing width during a stalled phone download prevents stale remote work`() = runBlocking {
        preferences.remoteAiDirectDownload.set(false)
        val sourceStarted = CompletableDeferred<Unit>()
        val releaseSource = CompletableDeferred<Unit>()
        val processing = async {
            runCatching {
                upscaler.processPage(
                    page = page,
                    source = {
                        sourceStarted.complete(Unit)
                        releaseSource.await()
                        Buffer().writeUtf8("original")
                    },
                )
            }
        }

        try {
            withTimeout(5_000) { sourceStarted.await() }
            preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
            assertFalse(upscaler.hasCachedPage(page))
            releaseSource.complete(Unit)

            assertTrue(withTimeout(5_000) { processing.await() }.exceptionOrNull() is CancellationException)
            verify(exactly = 0) { remote.upscaleSource(any(), any(), any(), any()) }
            verify(atLeast = 1) { remote.invalidateRemoteWorkScope() }
        } finally {
            releaseSource.complete(Unit)
        }
    }

    @Test
    fun `chapter ZIP preparation preserves its captured model width`() = runBlocking {
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.status = Page.State.Ready
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val captured = preferences.remoteAiConfig()
        val submitted = CompletableDeferred<ReaderPreferences.RemoteAiConfig>()
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) } answers {
            submitted.complete(arg(4))
            mockk<RemotePageUpscaler.StartedChapterJob>()
        }
        every { remote.streamChapterPages(any(), any(), any(), any()) } returns
            RemotePageUpscaler.ChapterStreamFetchResult.Completed

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
            assertEquals(captured, withTimeout(5_000) { submitted.await() })
            assertFalse(upscaler.hasCachedPage(page))
        } finally {
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `background preference changes invalidate remote work without requesting another page`() = runBlocking {
        val observablePreferences = ReaderPreferences(ObservablePreferenceStore()) { 1320 }
        val invalidated = Channel<Unit>(Channel.UNLIMITED)
        every { remote.invalidateRemoteWorkScope() } answers {
            invalidated.trySend(Unit)
            Unit
        }
        val app = mockk<Application>()
        every { app.cacheDir } returns cacheDir
        ReaderPageUpscaler(app, observablePreferences, mockk(), remote)

        observablePreferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
        withTimeout(5_000) { invalidated.receive() }
        observablePreferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.DETAILED, 2819)
        withTimeout(5_000) { invalidated.receive() }
        observablePreferences.remoteAiModel.set(ReaderPreferences.RemoteAiModel.DETAILED)
        withTimeout(5_000) { invalidated.receive() }

        verify(exactly = 3) { remote.invalidateRemoteWorkScope() }
    }

    @Test
    fun `manual retry replaces a previously cached bad result`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returnsMany listOf(
            "bad image".toByteArray(),
            "repaired image".toByteArray(),
        )

        assertEquals("bad image", upscaler.processPage(page).readUtf8())
        assertEquals("repaired image", upscaler.processPage(page, forceReload = true).readUtf8())
        assertEquals("repaired image", upscaler.processPage(page).readUtf8())
        assertEquals(0, sourceReads)
        verify(exactly = 2) { remote.upscaleDirectPage(any(), any(), any(), any()) }
    }

    @Test
    fun `failed manual retry removes the bad cached result`() {
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returns "bad image".toByteArray()
        runBlocking { upscaler.processPage(page) }
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returns null
        every { remote.upscaleSource(any(), any(), any(), any()) } returns null

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                upscaler.processPage(page, forceReload = true, fallbackToSourceOnFailure = false)
            }
        }
        assertFalse(upscaler.hasCachedPage(page))
        assertEquals(ReaderPageUpscaler.UpscaleStage.FAILED, upscaler.progress(page).value.stage)
    }

    @Test
    fun `an old chapter response cannot overwrite a manually retried page`() = runBlocking {
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page))
        every { remote.startDirectChapterJob(any(), any(), any()) } returns mockk()
        every { remote.upscaleDirectPage(any(), any(), any(), any()) } returns "fresh image".toByteArray()
        val streamStarted = CompletableDeferred<Unit>()
        val streamFinished = CompletableDeferred<Unit>()
        val releaseStream = CountDownLatch(1)
        every { remote.streamChapterPages(any(), any(), any(), any()) } answers {
            val onPageReady = arg<(Int, ByteArray) -> Unit>(3)
            streamStarted.complete(Unit)
            assertTrue(releaseStream.await(5, TimeUnit.SECONDS))
            onPageReady(0, "old broken image".toByteArray())
            streamFinished.complete(Unit)
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
            withTimeout(5_000) { streamStarted.await() }
            assertEquals("fresh image", upscaler.processPage(page, forceReload = true).readUtf8())
        } finally {
            releaseStream.countDown()
        }
        withTimeout(5_000) { streamFinished.await() }
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.SINGLE)

        assertEquals("fresh image", upscaler.processPage(page).readUtf8())
    }

    @Test
    fun `accepted direct chapter failure uploads only the remaining pages`() = runBlocking {
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        val secondPage = ReaderPage(1).apply {
            chapter = page.chapter
            remoteImageRequest = page.remoteImageRequest
            stream = {
                sourceReads++
                "second original".byteInputStream()
            }
        }
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page, secondPage))
        val directJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        val uploadJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        every { remote.startDirectChapterJob(any(), any(), any()) } returns directJob
        every { remote.streamChapterPages(directJob, any(), any(), any()) } answers {
            arg<(Int, ByteArray) -> Unit>(3)(0, "direct first".toByteArray())
            RemotePageUpscaler.ChapterStreamFetchResult.Failed("Source returned 403", retryable = false)
        }
        every { remote.prepareChapterUploadPage(1, any()) } answers {
            assertEquals("second original", secondArg<ByteArray>().decodeToString())
            RemotePageUpscaler.PreparedChapterUploadPage(1, secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 1, any(), any(), any()) } returns uploadJob
        every { remote.streamChapterPages(uploadJob, setOf(1), any(), any()) } answers {
            arg<(Int, ByteArray) -> Unit>(3)(1, "uploaded second".toByteArray())
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        upscaler.scheduleWholeChapterRemotePrefetch(listOf(page, secondPage))
        withTimeout(5_000) { upscaler.progress(secondPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY } }
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.SINGLE)

        assertEquals("direct first", upscaler.processPage(page).readUtf8())
        assertEquals("uploaded second", upscaler.processPage(secondPage).readUtf8())
        assertEquals(1, sourceReads)
        verify(exactly = 1) { remote.startDirectChapterJob(any(), any(), any()) }
        verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a terminal polled direct job uploads the remaining pages without retrying the failed job`() = runBlocking {
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        val secondPage = ReaderPage(1).apply {
            chapter = page.chapter
            remoteImageRequest = page.remoteImageRequest
            stream = {
                sourceReads++
                "second original".byteInputStream()
            }
        }
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page, secondPage))
        val directJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        val uploadJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        every { remote.startDirectChapterJob(any(), any(), any()) } returns directJob
        every { remote.streamChapterPages(directJob, any(), any(), any()) } returns
            RemotePageUpscaler.ChapterStreamFetchResult.Unsupported
        every { remote.fetchChapterPage(directJob, 0, any()) } returns
            RemotePageUpscaler.ChapterPageFetchResult.Ready("direct first".toByteArray())
        every { remote.fetchChapterPage(directJob, 1, any()) } returns
            RemotePageUpscaler.ChapterPageFetchResult.Failed("Source connection refused", retryable = false)
        every { remote.prepareChapterUploadPage(1, any()) } answers {
            assertEquals("second original", secondArg<ByteArray>().decodeToString())
            RemotePageUpscaler.PreparedChapterUploadPage(1, secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 1, any(), any(), any()) } returns uploadJob
        every { remote.streamChapterPages(uploadJob, setOf(1), any(), any()) } answers {
            arg<(Int, ByteArray) -> Unit>(3)(1, "uploaded second".toByteArray())
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(listOf(page, secondPage))
            withTimeout(5_000) {
                upscaler.progress(secondPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }

            assertEquals("direct first", upscaler.processPage(page).readUtf8())
            assertEquals("uploaded second", upscaler.processPage(secondPage).readUtf8())
            assertEquals(1, sourceReads)
            verify(exactly = 1) { remote.fetchChapterPage(directJob, 0, any()) }
            verify(exactly = 1) { remote.fetchChapterPage(directJob, 1, any()) }
            verify(exactly = 1) { remote.startDirectChapterJob(any(), any(), any()) }
            verify(exactly = 1) { remote.startChapterJobFromArchive(any(), 1, any(), any(), any()) }
        } finally {
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `a transient gateway failure and pending page keep waiting for the same direct job`() = runBlocking {
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val directJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        every { remote.startDirectChapterJob(any(), any(), any()) } returns directJob
        every { remote.streamChapterPages(directJob, any(), any(), any()) } returns
            RemotePageUpscaler.ChapterStreamFetchResult.Unsupported
        every { remote.fetchChapterPage(directJob, 0, any()) } returnsMany listOf(
            RemotePageUpscaler.ChapterPageFetchResult.Failed("Gateway temporarily unavailable", retryable = true),
            RemotePageUpscaler.ChapterPageFetchResult.Pending("upscaling", null),
            RemotePageUpscaler.ChapterPageFetchResult.Ready("direct result".toByteArray()),
        )

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
            withTimeout(5_000) {
                upscaler.progress(page).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }

            assertEquals("direct result", upscaler.processPage(page).readUtf8())
            assertEquals(0, sourceReads)
            verify(exactly = 3) { remote.fetchChapterPage(directJob, 0, any()) }
            verify(exactly = 1) { remote.startDirectChapterJob(any(), any(), any()) }
            verify(exactly = 0) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
        } finally {
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `phone chapter upload caches the first AI page before a later source finishes loading`() = runBlocking {
        preferences.remoteAiChapterMode().set(ReaderPreferences.RemoteAiChapterMode.PARALLEL)
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.status = Page.State.Ready
        val laterSourceStarted = CompletableDeferred<Unit>()
        val releaseLaterSource = CountDownLatch(1)
        val laterPage = ReaderPage(9).apply {
            chapter = page.chapter
            status = Page.State.Ready
            stream = {
                laterSourceStarted.complete(Unit)
                check(releaseLaterSource.await(5, TimeUnit.SECONDS))
                "later source".byteInputStream()
            }
        }
        val pages = listOf(page, laterPage)
        page.chapter.state = ReaderChapter.State.Loaded(pages)
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) } answers {
            assertEquals(2, arg<RemotePageUpscaler.ChapterJobMetadata>(2).totalPages)
            assertEquals(1, arg<Int>(1))
            ZipFile(firstArg<File>()).use { archive ->
                assertEquals(1, archive.size())
                assertTrue(archive.entries().nextElement().name in setOf("0000.jpg", "0009.jpg"))
            }
            mockk<RemotePageUpscaler.StartedChapterJob>()
        }
        every { remote.streamChapterPages(any(), any(), any(), any()) } answers {
            for (index in arg<Set<Int>>(1).toList()) {
                arg<(Int, ByteArray) -> Unit>(3)(index, "AI page $index".toByteArray())
            }
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(pages)
            withTimeout(5_000) { laterSourceStarted.await() }

            withTimeout(5_000) { upscaler.progress(page).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY } }
            assertTrue(upscaler.hasCachedPage(page))
            assertFalse(upscaler.hasCachedPage(laterPage))
            verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }

            releaseLaterSource.countDown()
            withTimeout(5_000) {
                upscaler.progress(laterPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }
            verify(exactly = 2) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
        } finally {
            releaseLaterSource.countDown()
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `parallel upload submits the next archive before the first AI result returns`() = runBlocking {
        preferences.remoteAiChapterMode().set(ReaderPreferences.RemoteAiChapterMode.PARALLEL)
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.status = Page.State.Ready
        val laterPage = ReaderPage(9).apply {
            chapter = page.chapter
            status = Page.State.Ready
            stream = { "later source".byteInputStream() }
        }
        val pages = listOf(page, laterPage)
        page.chapter.state = ReaderChapter.State.Loaded(pages)
        val firstJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        val laterJob = mockk<RemotePageUpscaler.StartedChapterJob>()
        val firstReceiveStarted = CompletableDeferred<Unit>()
        val secondSubmitted = CompletableDeferred<Unit>()
        val releaseFirstResult = CountDownLatch(1)
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) } answers {
            ZipFile(firstArg<File>()).use { archive ->
                if (archive.getEntry("0000.jpg") != null) {
                    firstJob
                } else {
                    assertTrue(archive.getEntry("0009.jpg") != null)
                    secondSubmitted.complete(Unit)
                    laterJob
                }
            }
        }
        every { remote.streamChapterPages(any(), any(), any(), any()) } answers {
            if (firstArg<RemotePageUpscaler.StartedChapterJob>() === firstJob) {
                firstReceiveStarted.complete(Unit)
                check(releaseFirstResult.await(5, TimeUnit.SECONDS))
            }
            for (index in arg<Set<Int>>(1).toList()) {
                arg<(Int, ByteArray) -> Unit>(3)(index, "AI page $index".toByteArray())
            }
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(pages)
            withTimeout(5_000) {
                firstReceiveStarted.await()
                secondSubmitted.await()
            }
            assertFalse(upscaler.hasCachedPage(page))
            verify(exactly = 2) { remote.startChapterJobFromArchive(any(), 1, any(), any(), any()) }
            releaseFirstResult.countDown()
            withTimeout(5_000) {
                upscaler.progress(laterPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }
            assertTrue(upscaler.hasCachedPage(page))
        } finally {
            releaseFirstResult.countDown()
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `classic is the default and submits one archive after all source pages are ready`() = runBlocking {
        assertEquals(ReaderPreferences.RemoteAiChapterMode.CLASSIC, preferences.remoteAiChapterMode().get())
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.status = Page.State.Ready
        val laterSourceStarted = CompletableDeferred<Unit>()
        val releaseLaterSource = CountDownLatch(1)
        val laterPage = ReaderPage(9).apply {
            chapter = page.chapter
            status = Page.State.Ready
            stream = {
                laterSourceStarted.complete(Unit)
                check(releaseLaterSource.await(5, TimeUnit.SECONDS))
                "later source".byteInputStream()
            }
        }
        val pages = listOf(page, laterPage)
        page.chapter.state = ReaderChapter.State.Loaded(pages)
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 2, any(), any(), any()) } answers {
            ZipFile(firstArg<File>()).use { archive ->
                assertEquals(setOf("0000.jpg", "0009.jpg"), archive.entries().asSequence().map { it.name }.toSet())
            }
            mockk<RemotePageUpscaler.StartedChapterJob>()
        }
        every { remote.streamChapterPages(any(), any(), any(), any()) } answers {
            for (index in arg<Set<Int>>(1).toList()) {
                arg<(Int, ByteArray) -> Unit>(3)(index, "AI page $index".toByteArray())
            }
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(pages)
            withTimeout(5_000) { laterSourceStarted.await() }
            verify(exactly = 0) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
            releaseLaterSource.countDown()
            withTimeout(5_000) {
                upscaler.progress(laterPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }
            assertTrue(upscaler.hasCachedPage(page))
            verify(exactly = 1) { remote.startChapterJobFromArchive(any(), 2, any(), any(), any()) }
        } finally {
            releaseLaterSource.countDown()
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `chapter without streaming retains its existing batch sizes`() = runBlocking {
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER)
        val pages = (0..6).map { index ->
            ReaderPage(index).apply {
                chapter = page.chapter
                status = Page.State.Ready
                stream = { "source $index".byteInputStream() }
            }
        }
        page.chapter.state = ReaderChapter.State.Loaded(pages)
        val uploadedCounts = mutableListOf<Int>()
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) } answers {
            uploadedCounts += arg<Int>(1)
            mockk<RemotePageUpscaler.StartedChapterJob>()
        }
        every { remote.fetchChapterPage(any(), any(), any()) } answers {
            RemotePageUpscaler.ChapterPageFetchResult.Ready("AI page ${arg<Int>(1)}".toByteArray())
        }
        try {
            upscaler.scheduleWholeChapterRemotePrefetch(pages)
            withTimeout(5_000) {
                upscaler.progress(pages.last()).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }
            assertEquals(listOf(1, 4, 2), uploadedCounts)
            assertTrue(pages.all(upscaler::hasCachedPage))
        } finally {
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `canceling during source preparation never submits the prepared chapter batch`() = runBlocking {
        preferences.remoteAiDirectDownload.set(false)
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.status = Page.State.Ready
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val preparing = CompletableDeferred<Unit>()
        val releasePreparation = CountDownLatch(1)
        every { remote.prepareChapterUploadPage(any(), any()) } answers {
            preparing.complete(Unit)
            check(releasePreparation.await(5, TimeUnit.SECONDS))
            RemotePageUpscaler.PreparedChapterUploadPage(firstArg(), secondArg(), "jpg")
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
            withTimeout(5_000) { preparing.await() }
            upscaler.invalidateRemoteWorkScope()
            releasePreparation.countDown()
            withTimeout(5_000) {
                while (cacheDir.walkTopDown().any { it.isFile && it.name.startsWith("chapter-upload-") }) {
                    delay(10)
                }
            }

            verify(exactly = 0) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
            assertFalse(upscaler.hasCachedPage(page))
        } finally {
            releasePreparation.countDown()
            upscaler.invalidateRemoteWorkScope()
        }
    }

    @Test
    fun `failed chapter upload fallback stops instead of resubmitting direct work`() = runBlocking {
        preferences.remoteAiBatchMode.set(ReaderPreferences.RemoteAiBatchMode.CHAPTER_STREAM)
        page.chapter.state = ReaderChapter.State.Loaded(listOf(page))
        every { remote.startDirectChapterJob(any(), any(), any()) } returns mockk()
        every { remote.prepareChapterUploadPage(0, any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(0, secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 1, any(), any(), any()) } returns mockk()
        every { remote.streamChapterPages(any(), any(), any(), any()) } returns
            RemotePageUpscaler.ChapterStreamFetchResult.Failed("Page unavailable", retryable = false)

        upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
        withTimeout(5_000) { upscaler.progress(page).first { it.stage == ReaderPageUpscaler.UpscaleStage.FAILED } }

        assertFalse(upscaler.hasCachedPage(page))
        assertEquals(1, sourceReads)
        verify(exactly = 1) { remote.startDirectChapterJob(any(), any(), any()) }
        verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `disabling AI resolves the deferred source`() = runBlocking {
        preferences.upscalePagesX2.set(false)
        var prepared = false
        page.prepareStream = { prepared = true }

        assertEquals("original", upscaler.processPage(page).readUtf8())
        assertEquals(true, prepared)
        assertEquals(1, sourceReads)
        verify(exactly = 0) { remote.upscaleDirectPage(any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation does not start a fallback upload or mark the page failed`() {
        page.remoteImageRequest = { throw CancellationException("Reader closed") }

        assertThrows(CancellationException::class.java) { runBlocking { upscaler.processPage(page) } }
        assertEquals(0, sourceReads)
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any(), any()) }
    }

    private class ObservablePreference<T>(private val delegate: Preference<T>) : Preference<T> by delegate {
        private val updates = MutableStateFlow(delegate.get())

        override fun changes() = updates

        override fun set(value: T) {
            delegate.set(value)
            updates.value = value
        }
    }

    private class ObservablePreferenceStore(
        private val delegate: PreferenceStore = InMemoryPreferenceStore(),
    ) : PreferenceStore by delegate {
        override fun getInt(key: String, defaultValue: Int): Preference<Int> =
            ObservablePreference(delegate.getInt(key, defaultValue))

        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = ObservablePreference(
            delegate.getObjectFromString(key, defaultValue, serializer, deserializer),
        )
    }
}
