package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class RemotePageUpscalerTest {

    @TempDir
    lateinit var cacheDir: File

    private val job = RemotePageUpscaler.StartedChapterJob(
        baseUrl = "https://companion.example",
        jobId = "direct-job",
        clientId = "phone",
        workScope = 1L,
    )

    @BeforeEach
    fun setUp() {
        mockkObject(AiPerformanceLog)
        every { AiPerformanceLog.eventListener(any()) } returns EventListener.NONE
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AiPerformanceLog)
    }

    @Test
    fun `a terminal companion job error immediately permits upload fallback`() {
        val remote = remoteWithResponse(502, "Source connection refused", jobState = "failed")

        val result = remote.fetchChapterPage(job, 0) as RemotePageUpscaler.ChapterPageFetchResult.Failed

        assertFalse(result.retryable)
        assertEquals("Source connection refused", result.message)
    }

    @Test
    fun `an unmarked gateway error remains retryable`() {
        val remote = remoteWithResponse(502, "Gateway temporarily unavailable")

        val result = remote.fetchChapterPage(job, 0) as RemotePageUpscaler.ChapterPageFetchResult.Failed

        assertTrue(result.retryable)
    }

    @Test
    fun `an unfinished job marker does not disable gateway retries`() {
        val remote = remoteWithResponse(502, "Gateway temporarily unavailable", jobState = "running")

        val result = remote.fetchChapterPage(job, 0) as RemotePageUpscaler.ChapterPageFetchResult.Failed

        assertTrue(result.retryable)
    }

    private fun remoteWithResponse(code: Int, body: String, jobState: String? = null): RemotePageUpscaler {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Test response")
                    .body(body.toResponseBody())
                    .apply { jobState?.let { header("X-Reader-AI-Job-State", it) } }
                    .build()
            }
            .build()
        val app = mockk<Application>()
        every { app.cacheDir } returns cacheDir
        val network = mockk<NetworkHelper>()
        every { network.client } returns client
        return RemotePageUpscaler(app, ReaderPreferences(InMemoryPreferenceStore()), network)
    }
}
