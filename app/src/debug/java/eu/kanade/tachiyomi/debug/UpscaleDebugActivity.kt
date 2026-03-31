package eu.kanade.tachiyomi.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class UpscaleDebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        thread(name = "UpscaleDebugActivity") {
            try {
                val inputPath = intent.getStringExtra(EXTRA_INPUT_PATH) ?: error("Missing input_path")
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: error("Missing output_path")

                upscaleFile(
                    inputFile = File(inputPath),
                    outputFile = File(outputPath),
                )
                Log.i(TAG, "Upscale finished: $outputPath")
                runOnUiThread {
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Upscale failed", t)
                runOnUiThread {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    private fun upscaleFile(
        inputFile: File,
        outputFile: File,
    ) {
        val sourceBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            ?: error("Unable to decode ${inputFile.absolutePath}")

        val outputBitmap = Bitmap.createBitmap(
            sourceBitmap.width * TARGET_SCALE,
            sourceBitmap.height * TARGET_SCALE,
            Bitmap.Config.RGB_565,
        )

        try {
            val modelBytes = assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
                .put(modelBytes)
            modelBuffer.rewind()

            val interpreter = Interpreter(
                modelBuffer,
                Interpreter.Options().apply {
                    setNumThreads(INFERENCE_THREADS)
                },
            )

            try {
                val inputPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
                val outputTilePixels = IntArray(DOWNSCALED_OUTPUT_SIZE * DOWNSCALED_OUTPUT_SIZE)
                val inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_BYTES).order(ByteOrder.nativeOrder())
                val outputBuffer = ByteBuffer.allocateDirect(OUTPUT_BUFFER_BYTES).order(ByteOrder.nativeOrder())
                val outputFloatBuffer = outputBuffer.asFloatBuffer()
                val outputFloats = FloatArray(MODEL_OUTPUT_SIZE * MODEL_OUTPUT_SIZE * MODEL_CHANNELS)
                val sharpenBuffer = IntArray(DOWNSCALED_OUTPUT_SIZE * DOWNSCALED_OUTPUT_SIZE)
                val totalTiles =
                    ((sourceBitmap.width + CORE_TILE_SIZE - 1) / CORE_TILE_SIZE) *
                        ((sourceBitmap.height + CORE_TILE_SIZE - 1) / CORE_TILE_SIZE)
                var processedTiles = 0

                interpreter.allocateTensors()

                var sourceY = 0
                while (sourceY < sourceBitmap.height) {
                    val coreHeight = minOf(CORE_TILE_SIZE, sourceBitmap.height - sourceY)
                    var sourceX = 0
                    while (sourceX < sourceBitmap.width) {
                        val coreWidth = minOf(CORE_TILE_SIZE, sourceBitmap.width - sourceX)
                        fillInputPixels(sourceBitmap, sourceX, sourceY, inputPixels)
                        superResolveToX2(
                            interpreter = interpreter,
                            inputPixels = inputPixels,
                            destinationTile = outputTilePixels,
                            inputBuffer = inputBuffer,
                            outputBuffer = outputBuffer,
                            outputFloatBuffer = outputFloatBuffer,
                            outputFloats = outputFloats,
                            sharpenBuffer = sharpenBuffer,
                        )
                        outputBitmap.setPixels(
                            outputTilePixels,
                            OUTPUT_MARGIN_SIZE * DOWNSCALED_OUTPUT_SIZE + OUTPUT_MARGIN_SIZE,
                            DOWNSCALED_OUTPUT_SIZE,
                            sourceX * TARGET_SCALE,
                            sourceY * TARGET_SCALE,
                            coreWidth * TARGET_SCALE,
                            coreHeight * TARGET_SCALE,
                        )
                        processedTiles++
                        if (processedTiles % 100 == 0 || processedTiles == totalTiles) {
                            Log.i(TAG, "Processed $processedTiles/$totalTiles tiles")
                        }
                        sourceX += CORE_TILE_SIZE
                    }
                    sourceY += CORE_TILE_SIZE
                }
            } finally {
                interpreter.close()
            }

            val data = ByteArrayOutputStream().use { output ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, UPSCALE_JPEG_QUALITY, output)
                output.toByteArray()
            }
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(data)
        } finally {
            outputBitmap.recycle()
            sourceBitmap.recycle()
        }
    }

    private fun fillInputPixels(
        sourceBitmap: Bitmap,
        coreX: Int,
        coreY: Int,
        inputPixels: IntArray,
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
            return
        }

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

    private fun superResolveToX2(
        interpreter: Interpreter,
        inputPixels: IntArray,
        destinationTile: IntArray,
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        outputFloatBuffer: java.nio.FloatBuffer,
        outputFloats: FloatArray,
        sharpenBuffer: IntArray,
    ) {
        inputBuffer.rewind()
        for (pixel in inputPixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
            inputBuffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
            inputBuffer.putFloat((pixel and 0xFF).toFloat())
        }

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        outputFloatBuffer.rewind()
        outputFloatBuffer.get(outputFloats)

        var destIndex = 0
        for (outputY in 0 until DOWNSCALED_OUTPUT_SIZE) {
            val highResTop = (outputY * 2) * MODEL_OUTPUT_SIZE * MODEL_CHANNELS
            val highResBottom = highResTop + MODEL_OUTPUT_SIZE * MODEL_CHANNELS
            for (outputX in 0 until DOWNSCALED_OUTPUT_SIZE) {
                val left = outputX * 2 * MODEL_CHANNELS
                val topLeft = highResTop + left
                val topRight = topLeft + MODEL_CHANNELS
                val bottomLeft = highResBottom + left
                val bottomRight = bottomLeft + MODEL_CHANNELS

                val red = averageChannel(outputFloats, topLeft, topRight, bottomLeft, bottomRight, 0)
                val green = averageChannel(outputFloats, topLeft, topRight, bottomLeft, bottomRight, 1)
                val blue = averageChannel(outputFloats, topLeft, topRight, bottomLeft, bottomRight, 2)

                destinationTile[destIndex++] =
                    (0xFF shl 24) or
                        (red shl 16) or
                        (green shl 8) or
                        blue
            }
        }

        applySharpen(destinationTile, sharpenBuffer)
    }

    private fun averageChannel(
        outputFloats: FloatArray,
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        channelOffset: Int,
    ): Int {
        val value =
            (
                outputFloats[topLeft + channelOffset] +
                    outputFloats[topRight + channelOffset] +
                    outputFloats[bottomLeft + channelOffset] +
                    outputFloats[bottomRight + channelOffset]
                ) / 4f
        return value.toInt().coerceIn(0, 255)
    }

    private fun applySharpen(
        destinationTile: IntArray,
        sharpenBuffer: IntArray,
    ) {
        destinationTile.copyInto(sharpenBuffer)
        val lastIndex = DOWNSCALED_OUTPUT_SIZE - 1

        for (y in 1 until lastIndex) {
            for (x in 1 until lastIndex) {
                val index = y * DOWNSCALED_OUTPUT_SIZE + x
                val center = sharpenBuffer[index]
                val left = sharpenBuffer[index - 1]
                val right = sharpenBuffer[index + 1]
                val top = sharpenBuffer[index - DOWNSCALED_OUTPUT_SIZE]
                val bottom = sharpenBuffer[index + DOWNSCALED_OUTPUT_SIZE]

                val red = sharpenChannel(center, left, right, top, bottom, 16)
                val green = sharpenChannel(center, left, right, top, bottom, 8)
                val blue = sharpenChannel(center, left, right, top, bottom, 0)

                destinationTile[index] =
                    (0xFF shl 24) or
                        (red shl 16) or
                        (green shl 8) or
                        blue
            }
        }
    }

    private fun sharpenChannel(
        center: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        shift: Int,
    ): Int {
        val centerValue = (center shr shift) and 0xFF
        val leftValue = (left shr shift) and 0xFF
        val rightValue = (right shr shift) and 0xFF
        val topValue = (top shr shift) and 0xFF
        val bottomValue = (bottom shr shift) and 0xFF

        val sharpened = centerValue * 5f - leftValue - rightValue - topValue - bottomValue
        val contrasted = ((sharpened - 128f) * 1.08f) + 128f
        return contrasted.toInt().coerceIn(0, 255)
    }

    companion object {
        private const val TAG = "UpscaleDebugActivity"
        private const val MODEL_ASSET_PATH = "ml/ESRGAN.tflite"
        private const val UPSCALE_JPEG_QUALITY = 95
        private const val TARGET_SCALE = 2
        private const val MODEL_INPUT_SIZE = 50
        private const val MODEL_OUTPUT_SIZE = 200
        private const val MODEL_CHANNELS = 3
        private const val INPUT_PADDING = 5
        private const val CORE_TILE_SIZE = MODEL_INPUT_SIZE - INPUT_PADDING * 2
        private const val DOWNSCALED_OUTPUT_SIZE = MODEL_OUTPUT_SIZE / 2
        private const val OUTPUT_MARGIN_SIZE = INPUT_PADDING * TARGET_SCALE
        private const val INPUT_BUFFER_BYTES = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * MODEL_CHANNELS * Float.SIZE_BYTES
        private const val OUTPUT_BUFFER_BYTES = MODEL_OUTPUT_SIZE * MODEL_OUTPUT_SIZE * MODEL_CHANNELS * Float.SIZE_BYTES
        private const val INFERENCE_THREADS = 4

        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }
}
