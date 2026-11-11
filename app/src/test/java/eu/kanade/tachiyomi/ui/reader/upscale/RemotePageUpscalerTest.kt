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
        targetWidth = 0,
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
            for ((acknowledgedWidth, inputPolicy) in listOf(
                null to "native-input-v2",
                "2048" to "native-input-v2",
                "invalid" to "native-input-v2",
                "1733" to null,
                "1733" to "legacy",
            )) {
                val remote = remoteWithHandler(ReaderPreferences(InMemoryPreferenceStore()) { 1320 }) { request ->
                    response(request, 200, "unused").newBuilder().apply {
                        acknowledgedWidth?.let { header("X-Reader-AI-Target-Width", it) }
                        inputPolicy?.let { header("X-Reader-AI-Input-Policy", it) }
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
    fun `native output supports legacy companions and custom width requires both acknowledgements`() {
        val remote = remoteWithResponse(400, "unused")
        val method = RemotePageUpscaler::class.java.declaredMethods.single { it.name == "acceptsTargetWidth" }
            .apply { isAccessible = true }
        val request = Request.Builder().url("https://companion.example").build()
        val legacy = response(request, 200, "image")
        val confirmed = legacy.newBuilder()
            .header("X-Reader-AI-Target-Width", "1733")
            .header("X-Reader-AI-Input-Policy", "native-input-v2")
            .build()

        assertEquals(true, method.invoke(remote, legacy, 0))
        assertEquals(true, method.invoke(remote, confirmed, 1733))
        assertEquals(false, method.invoke(remote, legacy, 1733))
        assertEquals(false, method.invoke(remote, confirmed, 2048))
    }

    @Test
    fun `only the corrected companion can return a cacheable custom-width image`() {
        var inputPolicy: String? = null
        val remote = remoteWithHandler(ReaderPreferences(InMemoryPreferenceStore()) { 1320 }) { request ->
            response(request, 200, "corrected image").newBuilder()
                .header("X-Reader-AI-Target-Width", "1733")
                .apply { inputPolicy?.let { header("X-Reader-AI-Input-Policy", it) } }
                .build()
        }
        val config = ReaderPreferences.RemoteAiConfig(ReaderPreferences.RemoteAiModel.FAST, 1733)
        for (policy in listOf(null, "legacy", "native-input-v2")) {
            inputPolicy = policy
            val attempt = submitProcessingRequest(remote, "image", config)!!
            val getter = attempt.javaClass.getDeclaredMethod("getBytes").apply { isAccessible = true }
            val bytes = getter.invoke(attempt) as ByteArray?
            if (policy == "native-input-v2") {
                assertEquals("corrected image", bytes?.decodeToString())
            } else {
                assertEquals(null, bytes)
                assertTrue(remote.consumeLastErrorMessage()?.contains("Update the PC companion") == true)
            }
        }
    }

    @Test
    fun `chapter pending images and streams require the captured width and processing policy`() {
        val captured = job.copy(targetWidth = 1733)
        for (policy in listOf(null, "legacy", "native-input-v2")) {
            for (code in listOf(200, 202)) {
                val remote = remoteWithHandler(ReaderPreferences(InMemoryPreferenceStore()) { 1320 }) { request ->
                    response(request, code, "untrusted image").newBuilder()
                        .header("X-Reader-AI-Target-Width", if (policy == "native-input-v2") "2048" else "1733")
                        .apply { policy?.let { header("X-Reader-AI-Input-Policy", it) } }
                        .build()
                }
                val page = remote.fetchChapterPage(captured, 0) as RemotePageUpscaler.ChapterPageFetchResult.Failed
                assertFalse(page.retryable)
                assertTrue(page.message.contains("Update the PC companion"))
                var published = false
                val stream = remote.streamChapterPages(captured, setOf(0)) { _, _ -> published = true }
                    as RemotePageUpscaler.ChapterStreamFetchResult.Failed
                assertFalse(stream.retryable)
                assertFalse(published)
            }
        }
        val remote = remoteWithHandler(ReaderPreferences(InMemoryPreferenceStore()) { 1320 }) { request ->
            response(request, 200, "corrected chapter image").newBuilder()
                .header("X-Reader-AI-Target-Width", "1733")
                .header("X-Reader-AI-Input-Policy", "native-input-v2")
                .build()
        }
        val ready = remote.fetchChapterPage(captured, 0) as RemotePageUpscaler.ChapterPageFetchResult.Ready
        assertEquals("corrected chapter image", ready.bytes.decodeToString())
        val legacy = remoteWithResponse(200, "native image")
        assertTrue(legacy.fetchChapterPage(job, 0) is RemotePageUpscaler.ChapterPageFetchResult.Ready)
    }

    private fun submitProcessingRequest(
        remote: RemotePageUpscaler,
        route: String,
        config: ReaderPreferences.RemoteAiConfig,
    ): Any? {
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
        return RemotePageUpscaler::class.java.declaredMethods.single { it.name == methodName }
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
