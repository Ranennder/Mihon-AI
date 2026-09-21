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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
        preferences = ReaderPreferences(InMemoryPreferenceStore()).apply {
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
        every { remote.upscaleDirectPage(any(), any(), any()) } returns "upscaled".toByteArray()

        assertEquals("upscaled", upscaler.processPage(page).readUtf8())
        assertEquals("upscaled", upscaler.processPage(page).readUtf8())

        assertEquals(0, sourceReads)
        verify(exactly = 1) { remote.upscaleDirectPage(any(), any(), any()) }
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any()) }
    }

    @Test
    fun `failed direct request loads the original only for upload fallback`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any()) } returns null
        every { remote.upscaleSource(any(), any(), any()) } answers {
            assertEquals("original", firstArg<okio.BufferedSource>().peek().readUtf8())
            "fallback".toByteArray()
        }

        assertEquals("fallback", upscaler.processPage(page).readUtf8())
        assertEquals(1, sourceReads)
    }

    @Test
    fun `manual retry replaces a previously cached bad result`() = runBlocking {
        every { remote.upscaleDirectPage(any(), any(), any()) } returnsMany listOf(
            "bad image".toByteArray(),
            "repaired image".toByteArray(),
        )

        assertEquals("bad image", upscaler.processPage(page).readUtf8())
        assertEquals("repaired image", upscaler.processPage(page, forceReload = true).readUtf8())
        assertEquals("repaired image", upscaler.processPage(page).readUtf8())
        assertEquals(0, sourceReads)
        verify(exactly = 2) { remote.upscaleDirectPage(any(), any(), any()) }
    }

    @Test
    fun `failed manual retry removes the bad cached result`() {
        every { remote.upscaleDirectPage(any(), any(), any()) } returns "bad image".toByteArray()
        runBlocking { upscaler.processPage(page) }
        every { remote.upscaleDirectPage(any(), any(), any()) } returns null
        every { remote.upscaleSource(any(), any(), any()) } returns null

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
        every { remote.startDirectChapterJob(any(), any()) } returns mockk()
        every { remote.upscaleDirectPage(any(), any(), any()) } returns "fresh image".toByteArray()
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
        every { remote.startDirectChapterJob(any(), any()) } returns directJob
        every { remote.streamChapterPages(directJob, any(), any(), any()) } answers {
            arg<(Int, ByteArray) -> Unit>(3)(0, "direct first".toByteArray())
            RemotePageUpscaler.ChapterStreamFetchResult.Failed("Source returned 403", retryable = false)
        }
        every { remote.prepareChapterUploadPage(1, any()) } answers {
            assertEquals("second original", secondArg<ByteArray>().decodeToString())
            RemotePageUpscaler.PreparedChapterUploadPage(1, secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 1, any(), any()) } returns uploadJob
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
        verify(exactly = 1) { remote.startDirectChapterJob(any(), any()) }
        verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any()) }
    }

    @Test
    fun `phone chapter upload caches the first AI page before a later source finishes loading`() = runBlocking {
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
        every { remote.startChapterJobFromArchive(any(), any(), any(), any()) } answers {
            assertEquals(2, arg<RemotePageUpscaler.ChapterJobMetadata>(2).totalPages)
            assertEquals(1, arg<Int>(1))
            ZipFile(firstArg<File>()).use { archive ->
                assertEquals(1, archive.size())
                assertTrue(archive.entries().nextElement().name in setOf("0000.jpg", "0009.jpg"))
            }
            mockk<RemotePageUpscaler.StartedChapterJob>()
        }
        every { remote.streamChapterPages(any(), any(), any(), any()) } answers {
            for (index in arg<Set<Int>>(1)) {
                arg<(Int, ByteArray) -> Unit>(3)(index, "AI page $index".toByteArray())
            }
            RemotePageUpscaler.ChapterStreamFetchResult.Completed
        }

        try {
            upscaler.scheduleWholeChapterRemotePrefetch(pages)
            withTimeout(5_000) { laterSourceStarted.await() }

            assertTrue(upscaler.hasCachedPage(page))
            assertFalse(upscaler.hasCachedPage(laterPage))
            verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any()) }

            releaseLaterSource.countDown()
            withTimeout(5_000) {
                upscaler.progress(laterPage).first { it.stage == ReaderPageUpscaler.UpscaleStage.READY }
            }
            verify(exactly = 2) { remote.startChapterJobFromArchive(any(), any(), any(), any()) }
        } finally {
            releaseLaterSource.countDown()
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

            verify(exactly = 0) { remote.startChapterJobFromArchive(any(), any(), any(), any()) }
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
        every { remote.startDirectChapterJob(any(), any()) } returns mockk()
        every { remote.prepareChapterUploadPage(0, any()) } answers {
            RemotePageUpscaler.PreparedChapterUploadPage(0, secondArg(), "jpg")
        }
        every { remote.startChapterJobFromArchive(any(), 1, any(), any()) } returns mockk()
        every { remote.streamChapterPages(any(), any(), any(), any()) } returns
            RemotePageUpscaler.ChapterStreamFetchResult.Failed("Page unavailable", retryable = false)

        upscaler.scheduleWholeChapterRemotePrefetch(listOf(page))
        withTimeout(5_000) { upscaler.progress(page).first { it.stage == ReaderPageUpscaler.UpscaleStage.FAILED } }

        assertFalse(upscaler.hasCachedPage(page))
        assertEquals(1, sourceReads)
        verify(exactly = 1) { remote.startDirectChapterJob(any(), any()) }
        verify(exactly = 1) { remote.startChapterJobFromArchive(any(), any(), any(), any()) }
    }

    @Test
    fun `disabling AI resolves the deferred source`() = runBlocking {
        preferences.upscalePagesX2.set(false)
        var prepared = false
        page.prepareStream = { prepared = true }

        assertEquals("original", upscaler.processPage(page).readUtf8())
        assertEquals(true, prepared)
        assertEquals(1, sourceReads)
        verify(exactly = 0) { remote.upscaleDirectPage(any(), any(), any()) }
    }

    @Test
    fun `cancellation does not start a fallback upload or mark the page failed`() {
        page.remoteImageRequest = { throw CancellationException("Reader closed") }

        assertThrows(CancellationException::class.java) { runBlocking { upscaler.processPage(page) } }
        assertEquals(0, sourceReads)
        verify(exactly = 0) { remote.upscaleSource(any(), any(), any()) }
    }
}
