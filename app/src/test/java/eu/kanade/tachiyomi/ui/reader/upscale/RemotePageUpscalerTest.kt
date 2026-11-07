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
import okhttp3.Request
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

    @Test
    fun `every processing route sends the captured model and arbitrary width`() {
        for (model in ReaderPreferences.RemoteAiModel.entries) {
            for (width in listOf(0, 1733, 16384)) {
                for (route in listOf("image", "archive", "direct", "https")) {
                    val preferences = ReaderPreferences(InMemoryPreferenceStore()) { 1320 }
                    val config = ReaderPreferences.RemoteAiConfig(model, width)
                    // The queued settings must win even when the active settings have changed.
                    preferences.remoteAiModel.set(
                        if (model == ReaderPreferences.RemoteAiModel.FAST) {
                            ReaderPreferences.RemoteAiModel.DETAILED
                        } else {
                            ReaderPreferences.RemoteAiModel.FAST
                        },
                    )
                    var captured: Request? = null
                    val remote = remoteWithHandler(preferences) { request ->
                        captured = request
                        response(request, 400, "Test request captured")
                    }

                    submitProcessingRequest(remote, route, config)

                    assertEquals(model.companionModelName, captured?.header("X-Reader-AI-Model-Name"), route)
                    assertEquals(width.toString(), captured?.header("X-Reader-AI-Target-Width"), route)
                    val expectedPath = when (route) {
                        "direct" -> "/api/upscale-chapter-direct"
                        "image" -> "/api/upscale"
                        else -> "/api/upscale-chapter"
                    }
                    assertEquals(expectedPath, captured?.url?.encodedPath, route)
                }
            }
        }
    }

    @Test
    fun `custom widths reject old companions and mismatched acknowledgements`() {
        for (route in listOf("image", "archive", "direct", "https")) {
            for (acknowledgedWidth in listOf(null, "2048", "invalid")) {
                val remote = remoteWithHandler(ReaderPreferences(InMemoryPreferenceStore()) { 1320 }) { request ->
                    response(request, 200, "unused").newBuilder().apply {
                        acknowledgedWidth?.let { header("X-Reader-AI-Target-Width", it) }
                    }.build()
                }
                submitProcessingRequest(
                    remote,
                    route,
                    ReaderPreferences.RemoteAiConfig(ReaderPreferences.RemoteAiModel.FAST, 1733),
                )
                assertTrue(remote.consumeLastErrorMessage()?.contains("Update the PC companion") == true, route)
            }
        }
    }

    @Test
    fun `native output supports legacy companions and custom width requires exact acknowledgement`() {
        val remote = remoteWithResponse(400, "unused")
        val method = RemotePageUpscaler::class.java.declaredMethods.single { it.name == "acceptsTargetWidth" }
            .apply { isAccessible = true }
        val native = ReaderPreferences.RemoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED, 0)
        val custom = native.copy(targetWidth = 1733)

        assertEquals(true, method.invoke(remote, null, native))
        assertEquals(true, method.invoke(remote, "1733", custom))
        assertEquals(false, method.invoke(remote, null, custom))
        assertEquals(false, method.invoke(remote, "2048", custom))
    }

    private fun submitProcessingRequest(
        remote: RemotePageUpscaler,
        route: String,
        config: ReaderPreferences.RemoteAiConfig,
    ) {
        val baseUrl = if (route == "https") "https://companion.example" else "http://companion.example"
        val resolution = RemoteAiServerDiscovery.Resolution(baseUrl, false)
        val progress: (Int) -> Unit = {}
        val imageProgress: (RemotePageUpscaler.ProgressStage, Int) -> Unit = { _, _ -> }
        val methodName: String
        val arguments: Array<Any?>
        when (route) {
            "direct" -> {
                methodName = "runStartDirectChapterJobRequest"
                arguments = arrayOf(resolution, "{}", null, 1L, config)
            }
            "archive" -> {
                methodName = "runStartChapterJobRequest"
                val archive = File(cacheDir, "test.zip").apply { writeBytes(byteArrayOf(1)) }
                arguments = arrayOf(resolution, archive, 1, null, 1L, progress, config)
            }
            else -> {
                methodName = "runUpscaleRequest"
                val imageType = RemotePageUpscaler::class.java.declaredClasses
                    .single { it.simpleName == "PreparedImage" }
                val image = imageType.declaredConstructors.single().apply { isAccessible = true }
                    .newInstance(byteArrayOf(1), "image/jpeg", "jpg", 1, 1)
                arguments = arrayOf(baseUrl, image, null, 1L, imageProgress, config)
            }
        }
        RemotePageUpscaler::class.java.declaredMethods.single { it.name == methodName }
            .apply { isAccessible = true }.invoke(remote, *arguments)
    }

    private fun remoteWithHandler(
        preferences: ReaderPreferences,
        handler: (Request) -> Response,
    ): RemotePageUpscaler {
        val client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
        val app = mockk<Application>()
        every { app.cacheDir } returns cacheDir
        val network = mockk<NetworkHelper>()
        every { network.client } returns client
        return RemotePageUpscaler(app, preferences, network)
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test response")
        .body(body.toResponseBody())
        .build()

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
        return RemotePageUpscaler(app, ReaderPreferences(InMemoryPreferenceStore()) { 1320 }, network)
    }
}
