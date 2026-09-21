package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class HttpPageLoaderTest {

    @TempDir
    lateinit var cacheDir: File

    private lateinit var preferences: ReaderPreferences
    private lateinit var source: HttpSource
    private lateinit var loader: HttpPageLoader
    private lateinit var page: ReaderPage

    @BeforeEach
    fun setUp() = runBlocking {
        preferences = ReaderPreferences(InMemoryPreferenceStore()).apply {
            upscalePagesX2.set(true)
            aiBackendMode.set(ReaderPreferences.AiBackendMode.REMOTE)
            remoteAiDirectDownload.set(true)
        }
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 1L
                manga_id = 2L
                name = "Chapter"
                url = "/chapter"
            },
        )
        val imageUrl = "https://source.example/page.jpg"
        val imageFile = File(cacheDir, "original.jpg").apply { writeText("original") }
        var cached = false
        val cache = mockk<ChapterCache>(relaxed = true)
        every { cache.getPageListFromCache(any()) } returns listOf(Page(0, imageUrl = imageUrl))
        every { cache.isImageInCache(imageUrl) } answers { cached }
        every { cache.getImageFile(imageUrl) } returns imageFile
        every { cache.putImageToCache(imageUrl, any()) } answers { cached = true }
        source = mockk(relaxed = true)
        coEvery { source.getImage(any(), any()) } coAnswers {
            yield()
            mockk(relaxed = true)
        }
        loader = HttpPageLoader(chapter, source, cache, preferences)
        page = loader.getPages().single().also { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        chapter.pageLoader = loader
    }

    @AfterEach
    fun tearDown() {
        loader.recycle()
    }

    private suspend fun awaitReady() = withTimeout(5_000) {
        val load = launch { loader.loadPage(page) }
        try {
            page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            assertEquals(Page.State.Ready, page.status)
        } finally {
            load.cancelAndJoin()
        }
    }

    @Test
    fun `direct mode prepares the page without downloading its original`() = runBlocking {
        awaitReady()

        coVerify(exactly = 0) { source.getImage(any(), any()) }
    }

    @Test
    fun `deferred original uses the source extension once for concurrent consumers`() = runBlocking {
        awaitReady()

        val contents = List(2) { async { page.openStream().use { it.readBytes().decodeToString() } } }.awaitAll()

        assertEquals(listOf("original", "original"), contents)
        coVerify(exactly = 1) { source.getImage(any(), any()) }
    }

    @Test
    fun `turning AI off restores source downloading`() = runBlocking {
        awaitReady()
        preferences.upscalePagesX2.set(false)

        page.openStream().use { assertEquals("original", it.readBytes().decodeToString()) }

        coVerify(exactly = 1) { source.getImage(any(), any()) }
    }

    @Test
    fun `retry of a ready source bypasses its cached image`() = runBlocking {
        awaitReady()
        page.openStream().close()
        preferences.upscalePagesX2.set(false)

        loader.retryPage(page)
        awaitReady()

        coVerify(exactly = 2) { source.getImage(any(), any()) }
    }
}
