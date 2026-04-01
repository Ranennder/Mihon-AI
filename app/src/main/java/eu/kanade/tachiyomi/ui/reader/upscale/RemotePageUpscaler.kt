package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RemotePageUpscaler(
    app: Application,
    private val readerPreferences: ReaderPreferences,
    networkHelper: NetworkHelper,
) {

    private val client = networkHelper.nonCloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    private val discovery = RemoteAiServerDiscovery(app, readerPreferences, networkHelper)

    @Volatile
    private var lastErrorMessage: String? = null

    fun consumeLastErrorMessage(): String? {
        return lastErrorMessage.also { lastErrorMessage = null }
    }

    fun upscaleSource(source: BufferedSource): ByteArray? {
        lastErrorMessage = null

        val baseUrlResolution = discovery.resolveBaseUrl()
        if (baseUrlResolution == null) {
            lastErrorMessage =
                "Remote AI companion was not found on local Wi-Fi. Set the server URL manually if needed."
            return null
        }

        val preparedImage = prepareRequestImage(source.peek().readByteArray()) ?: return null
        val initialAttempt = runUpscaleRequest(
            baseUrl = baseUrlResolution.baseUrl,
            preparedImage = preparedImage,
        )
        if (initialAttempt.bytes != null) {
            return initialAttempt.bytes
        }

        if (!baseUrlResolution.isAutoDiscovered || initialAttempt.failureKind != UpscaleFailureKind.NETWORK) {
            return null
        }

        discovery.clearCachedBaseUrl()
        val rediscoveredBaseUrl = discovery.resolveBaseUrl(forceRediscovery = true)
            ?.takeIf { it.baseUrl != baseUrlResolution.baseUrl }
            ?: return null

        return runUpscaleRequest(
            baseUrl = rediscoveredBaseUrl.baseUrl,
            preparedImage = preparedImage,
        ).bytes
    }

    private fun runUpscaleRequest(
        baseUrl: String,
        preparedImage: PreparedImage,
    ): UpscaleAttempt {
        val requestUrl = "$baseUrl/api/upscale".toHttpUrlOrNull()
        if (requestUrl == null) {
            lastErrorMessage = "Remote AI server URL is invalid"
            return UpscaleAttempt.failure(UpscaleFailureKind.CONFIGURATION)
        }

        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("Accept", "image/jpeg, image/png;q=0.9")
            .header("X-Reader-AI-Input-Format", preparedImage.extension)
            .header("X-Reader-AI-Output-Format", REMOTE_OUTPUT_FORMAT)
            .header("X-Reader-AI-Model-Name", readerPreferences.remoteAiModel.get().companionModelName)

        readerPreferences.remoteAiToken.get().trim()
            .takeIf { it.isNotEmpty() }
            ?.let { requestBuilder.header("X-Reader-AI-Token", it) }

        val request = requestBuilder
            .post(preparedImage.bytes.toRequestBody(preparedImage.mediaType.toMediaType()))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorMessage = response.body.string()
                        .takeIf(String::isNotBlank)
                        ?: "Remote AI server returned HTTP ${response.code}"
                    return@use UpscaleAttempt.failure(UpscaleFailureKind.SERVER)
                }

                val responseBytes = response.body.bytes()
                if (responseBytes.isEmpty()) {
                    lastErrorMessage = "Remote AI server returned an empty image"
                    return@use UpscaleAttempt.failure(UpscaleFailureKind.SERVER)
                }

                val normalizedResponseBytes = normalizeResponseImage(
                    responseBytes = responseBytes,
                ) ?: return@use UpscaleAttempt.failure(UpscaleFailureKind.SERVER)

                lastErrorMessage = null
                UpscaleAttempt.success(normalizedResponseBytes)
            }
        }
            .onFailure {
                lastErrorMessage = it.message ?: "Remote AI request failed"
                logcat(LogPriority.WARN, it) { "Failed to run remote AI upscale" }
            }
            .getOrElse {
                UpscaleAttempt.failure(UpscaleFailureKind.NETWORK)
            }
    }

    private fun prepareRequestImage(sourceBytes: ByteArray): PreparedImage? {
        if (sourceBytes.isEmpty()) {
            lastErrorMessage = "Source image is empty"
            return null
        }

        return when (val inputKind = detectInputKind(sourceBytes)) {
            InputKind.Jpeg, InputKind.Png -> {
                val imageSize = decodeImageSize(sourceBytes)
                    ?: run {
                        lastErrorMessage = "Failed to read source image size for remote AI upload"
                        return null
                    }
                PreparedImage(
                    bytes = sourceBytes,
                    mediaType = inputKind.mediaType,
                    extension = inputKind.extension,
                    width = imageSize.width,
                    height = imageSize.height,
                )
            }
            InputKind.Webp, InputKind.Gif, InputKind.Unknown -> {
                val normalizedImage = normalizeToPng(sourceBytes) ?: return null
                PreparedImage(
                    bytes = normalizedImage.bytes,
                    mediaType = InputKind.Png.mediaType,
                    extension = InputKind.Png.extension,
                    width = normalizedImage.width,
                    height = normalizedImage.height,
                )
            }
        }
    }

    private fun normalizeToPng(sourceBytes: ByteArray): NormalizedImage? {
        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
        if (bitmap == null) {
            lastErrorMessage = "Failed to decode source image for remote AI upload"
            return null
        }

        return bitmap.useAndRecycle { decodedBitmap ->
            runCatching {
                ByteArrayOutputStream().use { output ->
                    check(decodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Failed to encode normalized PNG upload"
                    }
                    NormalizedImage(
                        bytes = output.toByteArray(),
                        width = decodedBitmap.width,
                        height = decodedBitmap.height,
                    )
                }
            }
                .onFailure {
                    lastErrorMessage = it.message ?: "Failed to encode normalized PNG upload"
                }
                .getOrNull()
        }
    }

    private fun normalizeResponseImage(
        responseBytes: ByteArray,
    ): ByteArray? {
        return responseBytes
    }

    private fun decodeImageSize(sourceBytes: ByteArray): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }
        return ImageSize(options.outWidth, options.outHeight)
    }

    private fun detectInputKind(sourceBytes: ByteArray): InputKind {
        return when {
            sourceBytes.size >= 4 &&
                sourceBytes[0] == 0xFF.toByte() &&
                sourceBytes[1] == 0xD8.toByte() &&
                sourceBytes[2] == 0xFF.toByte() -> InputKind.Jpeg
            sourceBytes.size >= 8 &&
                sourceBytes[0] == 0x89.toByte() &&
                sourceBytes[1] == 0x50.toByte() &&
                sourceBytes[2] == 0x4E.toByte() &&
                sourceBytes[3] == 0x47.toByte() -> InputKind.Png
            sourceBytes.size >= 4 &&
                sourceBytes[0] == 'G'.code.toByte() &&
                sourceBytes[1] == 'I'.code.toByte() &&
                sourceBytes[2] == 'F'.code.toByte() &&
                sourceBytes[3] == '8'.code.toByte() -> InputKind.Gif
            sourceBytes.size >= 12 &&
                sourceBytes[0] == 'R'.code.toByte() &&
                sourceBytes[1] == 'I'.code.toByte() &&
                sourceBytes[2] == 'F'.code.toByte() &&
                sourceBytes[3] == 'F'.code.toByte() &&
                sourceBytes[8] == 'W'.code.toByte() &&
                sourceBytes[9] == 'E'.code.toByte() &&
                sourceBytes[10] == 'B'.code.toByte() &&
                sourceBytes[11] == 'P'.code.toByte() -> InputKind.Webp
            else -> InputKind.Unknown
        }
    }

    private data class PreparedImage(
        val bytes: ByteArray,
        val mediaType: String,
        val extension: String,
        val width: Int,
        val height: Int,
    )

    private data class NormalizedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private data class ImageSize(
        val width: Int,
        val height: Int,
    )

    private data class UpscaleAttempt(
        val bytes: ByteArray?,
        val failureKind: UpscaleFailureKind?,
    ) {
        companion object {
            fun success(bytes: ByteArray) = UpscaleAttempt(bytes = bytes, failureKind = null)

            fun failure(kind: UpscaleFailureKind) = UpscaleAttempt(bytes = null, failureKind = kind)
        }
    }

    private enum class UpscaleFailureKind {
        CONFIGURATION,
        NETWORK,
        SERVER,
    }

    private enum class InputKind(
        val extension: String,
        val mediaType: String,
    ) {
        Jpeg("jpg", "image/jpeg"),
        Png("png", "image/png"),
        Gif("gif", "image/gif"),
        Webp("webp", "image/webp"),
        Unknown("png", "image/png"),
    }

    companion object {
        private const val REMOTE_OUTPUT_FORMAT = "jpg"
    }
}

private inline fun <T> Bitmap.useAndRecycle(block: (Bitmap) -> T): T {
    return try {
        block(this)
    } finally {
        recycle()
    }
}
