package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okio.BufferedSource
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QuickSrPageUpscaler(
    private val app: Application,
) {

    private val inferenceLock = Any()

    @Volatile
    private var engine: QuickSrEngine? = null

    @Volatile
    private var engineInitFailure: Throwable? = null

    fun upscaleSourceToJpeg(
        source: BufferedSource,
        onProgress: ((processedTiles: Int, totalTiles: Int) -> Unit)? = null,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(source.peek().inputStream(), null, bounds)
        if (!isSafeToUpscale(bounds.outWidth, bounds.outHeight)) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val sourceBitmap = BitmapFactory.decodeStream(source.peek().inputStream(), null, decodeOptions) ?: return null
        return sourceBitmap.useAndRecycle {
            upscaleBitmapToJpeg(sourceBitmap, onProgress)
        }
    }

    fun upscaleFileToJpeg(
        inputFile: File,
        outputFile: File,
        onProgress: ((processedTiles: Int, totalTiles: Int) -> Unit)? = null,
    ): Boolean {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
        if (!isSafeToUpscale(bounds.outWidth, bounds.outHeight)) {
            return false
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val sourceBitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodeOptions) ?: return false
        val jpegBytes = sourceBitmap.useAndRecycle {
            upscaleBitmapToJpeg(sourceBitmap, onProgress)
        } ?: return false

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(jpegBytes)
        return true
    }

    private fun upscaleBitmapToJpeg(
        sourceBitmap: Bitmap,
        onProgress: ((processedTiles: Int, totalTiles: Int) -> Unit)?,
    ): ByteArray? {
        val targetWidth = sourceBitmap.width * TARGET_SCALE
        val targetHeight = sourceBitmap.height * TARGET_SCALE
        val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)

        return outputBitmap.useAndRecycle {
            synchronized(inferenceLock) {
                val engine = getOrCreateEngine()
                val inputPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
                val inputBytes = ByteArray(INPUT_BUFFER_BYTES)
                val outputTilePixels = IntArray(DOWNSCALED_OUTPUT_SIZE * DOWNSCALED_OUTPUT_SIZE)

                val totalTiles = ceilDiv(sourceBitmap.width, CORE_TILE_SIZE) * ceilDiv(sourceBitmap.height, CORE_TILE_SIZE)
                var processedTiles = 0
                var sourceY = 0
                while (sourceY < sourceBitmap.height) {
                    val coreHeight = minOf(CORE_TILE_SIZE, sourceBitmap.height - sourceY)
                    var sourceX = 0
                    while (sourceX < sourceBitmap.width) {
                        val coreWidth = minOf(CORE_TILE_SIZE, sourceBitmap.width - sourceX)
                        fillInputBytes(
                            sourceBitmap = sourceBitmap,
                            coreX = sourceX,
                            coreY = sourceY,
                            inputPixels = inputPixels,
                            inputBytes = inputBytes,
                        )
                        engine.superResolveToX2(inputBytes, outputTilePixels)
                        outputBitmap.setPixels(
                            outputTilePixels,
                            0,
                            DOWNSCALED_OUTPUT_SIZE,
                            sourceX * TARGET_SCALE,
                            sourceY * TARGET_SCALE,
                            coreWidth * TARGET_SCALE,
                            coreHeight * TARGET_SCALE,
                        )
                        processedTiles++
                        onProgress?.invoke(processedTiles, totalTiles)
                        sourceX += CORE_TILE_SIZE
                    }
                    sourceY += CORE_TILE_SIZE
                }
            }

            ByteArrayOutputStream().use { output ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, UPSCALE_JPEG_QUALITY, output)
                output.toByteArray()
            }
        }
    }

    private fun fillInputBytes(
        sourceBitmap: Bitmap,
        coreX: Int,
        coreY: Int,
        inputPixels: IntArray,
        inputBytes: ByteArray,
    ) {
        val inputStartX = coreX - INPUT_PADDING
        val inputStartY = coreY - INPUT_PADDING
        if (
            inputStartX >= 0 &&
            inputStartY >= 0 &&
            inputStartX + MODEL_INPUT_SIZE <= sourceBitmap.width &&
            inputStartY + MODEL_INPUT_SIZE <= sourceBitmap.height
        ) {
            sourceBitmap.getPixels(
                inputPixels,
                0,
                MODEL_INPUT_SIZE,
                inputStartX,
                inputStartY,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
            )
        } else {
            var destIndex = 0
            val maxX = sourceBitmap.width - 1
            val maxY = sourceBitmap.height - 1
            for (tileY in 0 until MODEL_INPUT_SIZE) {
                val sourceY = (inputStartY + tileY).coerceIn(0, maxY)
                for (tileX in 0 until MODEL_INPUT_SIZE) {
                    val sourceX = (inputStartX + tileX).coerceIn(0, maxX)
                    inputPixels[destIndex++] = sourceBitmap.getPixel(sourceX, sourceY)
                }
            }
        }

        var byteIndex = 0
        for (pixel in inputPixels) {
            inputBytes[byteIndex++] = ((pixel shr 16) and 0xFF).toByte()
            inputBytes[byteIndex++] = ((pixel shr 8) and 0xFF).toByte()
            inputBytes[byteIndex++] = (pixel and 0xFF).toByte()
        }
    }

    private fun getOrCreateEngine(): QuickSrEngine {
        engine?.let { return it }
        engineInitFailure?.let { throw IllegalStateException("QuickSR engine is unavailable", it) }

        return synchronized(this) {
            engine?.let { return@synchronized it }
            engineInitFailure?.let { throw IllegalStateException("QuickSR engine is unavailable", it) }

            runCatching {
                val modelBytes = app.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
                val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(modelBytes)
                modelBuffer.rewind()
                QuickSrEngine(modelBuffer)
            }
                .onFailure { engineInitFailure = it }
                .getOrThrow()
                .also { engine = it }
        }
    }

    private fun isSafeToUpscale(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false
        }

        val targetWidth = sourceWidth * TARGET_SCALE
        val targetHeight = sourceHeight * TARGET_SCALE
        return (
            targetWidth <= MAX_OUTPUT_DIMENSION &&
                targetHeight <= MAX_OUTPUT_DIMENSION &&
                targetWidth.toLong() * targetHeight.toLong() <= MAX_OUTPUT_PIXELS
            )
    }

    private class QuickSrEngine(
        modelBuffer: ByteBuffer,
    ) {
        private val retainedModelBuffer = modelBuffer
        private val interpreter = Interpreter(
            retainedModelBuffer,
            Interpreter.Options().apply {
                setNumThreads(INFERENCE_THREADS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUseNNAPI(true)
                }
            },
        )
        private val inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_BYTES).order(ByteOrder.nativeOrder())
        private val outputBuffer = ByteBuffer.allocateDirect(OUTPUT_BUFFER_BYTES).order(ByteOrder.nativeOrder())
        private val outputBytes = ByteArray(OUTPUT_BUFFER_BYTES)

        init {
            interpreter.allocateTensors()

            check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, MODEL_CHANNELS))) {
                "Unexpected QuickSR input tensor shape"
            }
            check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, MODEL_OUTPUT_SIZE, MODEL_OUTPUT_SIZE, MODEL_CHANNELS))) {
                "Unexpected QuickSR output tensor shape"
            }
            check(interpreter.getInputTensor(0).dataType() == DataType.UINT8) {
                "Unexpected QuickSR input tensor type"
            }
            check(interpreter.getOutputTensor(0).dataType() == DataType.UINT8) {
                "Unexpected QuickSR output tensor type"
            }
        }

        fun superResolveToX2(
            inputBytes: ByteArray,
            destinationTile: IntArray,
        ) {
            inputBuffer.rewind()
            inputBuffer.put(inputBytes)
            inputBuffer.rewind()

            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            outputBuffer.get(outputBytes)

            var destIndex = 0
            for (outputY in 0 until DOWNSCALED_OUTPUT_SIZE) {
                val topY = HIGH_RES_MARGIN_SIZE + outputY * DOWNSCALE_FACTOR
                val bottomY = topY + 1
                val topRow = topY * OUTPUT_ROW_STRIDE
                val bottomRow = bottomY * OUTPUT_ROW_STRIDE
                for (outputX in 0 until DOWNSCALED_OUTPUT_SIZE) {
                    val left = (HIGH_RES_MARGIN_SIZE + outputX * DOWNSCALE_FACTOR) * MODEL_CHANNELS
                    val red = averageChannel(topRow + left, topRow + left + MODEL_CHANNELS, bottomRow + left, bottomRow + left + MODEL_CHANNELS, 0)
                    val green = averageChannel(topRow + left, topRow + left + MODEL_CHANNELS, bottomRow + left, bottomRow + left + MODEL_CHANNELS, 1)
                    val blue = averageChannel(topRow + left, topRow + left + MODEL_CHANNELS, bottomRow + left, bottomRow + left + MODEL_CHANNELS, 2)

                    destinationTile[destIndex++] =
                        (0xFF shl 24) or
                            (red shl 16) or
                            (green shl 8) or
                            blue
                }
            }
        }

        private fun averageChannel(
            topLeft: Int,
            topRight: Int,
            bottomLeft: Int,
            bottomRight: Int,
            channelOffset: Int,
        ): Int {
            return (
                (outputBytes[topLeft + channelOffset].toInt() and 0xFF) +
                    (outputBytes[topRight + channelOffset].toInt() and 0xFF) +
                    (outputBytes[bottomLeft + channelOffset].toInt() and 0xFF) +
                    (outputBytes[bottomRight + channelOffset].toInt() and 0xFF)
                ) / 4
        }
    }

    private inline fun <T> Bitmap.useAndRecycle(block: (Bitmap) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }

    companion object {
        private const val MODEL_ASSET_PATH = "ml/QuickSRNetSmall-w8a8.tflite"

        private const val UPSCALE_JPEG_QUALITY = 95
        private const val TARGET_SCALE = 2
        private const val MODEL_SCALE = 4
        private const val DOWNSCALE_FACTOR = MODEL_SCALE / TARGET_SCALE

        private const val MODEL_INPUT_SIZE = 128
        private const val MODEL_OUTPUT_SIZE = 512
        private const val MODEL_CHANNELS = 3

        private const val INPUT_PADDING = 8
        private const val CORE_TILE_SIZE = MODEL_INPUT_SIZE - INPUT_PADDING * 2
        private const val HIGH_RES_MARGIN_SIZE = INPUT_PADDING * MODEL_SCALE
        private const val DOWNSCALED_OUTPUT_SIZE = CORE_TILE_SIZE * TARGET_SCALE
        private const val OUTPUT_ROW_STRIDE = MODEL_OUTPUT_SIZE * MODEL_CHANNELS

        private const val INPUT_BUFFER_BYTES = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * MODEL_CHANNELS
        private const val OUTPUT_BUFFER_BYTES = MODEL_OUTPUT_SIZE * MODEL_OUTPUT_SIZE * MODEL_CHANNELS

        private const val INFERENCE_THREADS = 4
        private const val MAX_OUTPUT_DIMENSION = 24_576
        private const val MAX_OUTPUT_PIXELS = 64_000_000L

        private fun ceilDiv(
            value: Int,
            divisor: Int,
        ): Int {
            return (value + divisor - 1) / divisor
        }
    }
}
