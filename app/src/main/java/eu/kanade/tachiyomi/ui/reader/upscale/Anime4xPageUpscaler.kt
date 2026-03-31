package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Anime4xPageUpscaler(
    private val app: Application,
    private val readerPreferences: ReaderPreferences,
) {

    private data class AttemptFailure(
        val backend: Int,
        val exitCode: Int,
        val output: String,
    ) {
        fun shouldDisableGpu(): Boolean {
            if (backend == CPU_BACKEND) {
                return false
            }

            return exitCode == 139 ||
                output.contains("segmentation fault", ignoreCase = true) ||
                output.contains("session null", ignoreCase = true)
        }
    }

    private data class RuntimeFiles(
        val directory: File,
        val binary: File,
        val model: File,
    )

    private val runtimeLock = Any()

    @Volatile
    private var runtimeFiles: RuntimeFiles? = null

    @Volatile
    private var runtimeInitFailure: Throwable? = null

    @Volatile
    private var lastErrorMessage: String? = null

    fun isSupportedOnThisDevice(): Boolean {
        return Build.SUPPORTED_ABIS.any { it == SUPPORTED_ABI }
    }

    fun consumeLastErrorMessage(): String? {
        return lastErrorMessage.also { lastErrorMessage = null }
    }

    suspend fun upscaleSource(
        source: BufferedSource,
    ): ByteArray? {
        lastErrorMessage = null
        if (!isSupportedOnThisDevice()) {
            lastErrorMessage = "AI upscale requires an arm64-v8a device"
            return null
        }

        val runtime = getOrCreateRuntime()
        val workDir = File(runtime.directory, WORK_DIR_NAME).apply { mkdirs() }
        val sourceBytes = source.peek().readByteArray()
        if (sourceBytes.isEmpty()) {
            return null
        }

        val token = UUID.randomUUID().toString()
        val inputKind = detectInputKind(sourceBytes)
        val inputFile = File(workDir, "input-$token.${inputKind.inputExtension}")
        val outputFile = File(workDir, "output-$token.$OUTPUT_FORMAT")

        return try {
            writeInputFile(
                sourceBytes = sourceBytes,
                inputKind = inputKind,
                inputFile = inputFile,
            )
            runInference(
                runtime = runtime,
                inputFile = inputFile,
                outputFile = outputFile,
            )
            if (!outputFile.isFile || outputFile.length() == 0L) {
                lastErrorMessage = "AI upscale finished without creating an output image"
                logcat(LogPriority.WARN) { "AI upscale finished without an output file for ${inputFile.name}" }
                null
            } else {
                lastErrorMessage = null
                outputFile.readBytes()
            }
        } catch (e: Throwable) {
            lastErrorMessage = e.message ?: e.javaClass.simpleName
            logcat(LogPriority.WARN, e) { "Failed to run MNN AI upscale for ${inputFile.name}" }
            null
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    private fun writeInputFile(
        sourceBytes: ByteArray,
        inputKind: InputKind,
        inputFile: File,
    ) {
        inputFile.parentFile?.mkdirs()
        when (inputKind) {
            InputKind.Jpeg, InputKind.Png -> inputFile.writeBytes(sourceBytes)
            InputKind.Webp, InputKind.Gif, InputKind.Unknown -> normalizeToPng(sourceBytes, inputFile)
        }
    }

    private fun normalizeToPng(
        sourceBytes: ByteArray,
        outputFile: File,
    ) {
        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: error("Failed to decode source image for AI normalization")
        bitmap.useAndRecycle {
            outputFile.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Failed to write normalized PNG input"
                }
            }
        }
    }

    private suspend fun runInference(
        runtime: RuntimeFiles,
        inputFile: File,
        outputFile: File,
    ) {
        val backends = listOf(
            when (readerPreferences.aiBackendMode.get()) {
                ReaderPreferences.AiBackendMode.CPU -> CPU_BACKEND
                ReaderPreferences.AiBackendMode.GPU -> OPENCL_BACKEND
                ReaderPreferences.AiBackendMode.NPU -> NN_BACKEND
                ReaderPreferences.AiBackendMode.REMOTE -> CPU_BACKEND
            },
        )

        var lastFailure: AttemptFailure? = null
        for (backend in backends) {
            val failure = runInferenceAttempt(
                runtime = runtime,
                inputFile = inputFile,
                outputFile = outputFile,
                backend = backend,
            )

            if (failure == null) {
                return
            }

            lastFailure = failure
            if (failure.shouldDisableGpu()) {
                markCpuOnlyMode(runtime.directory)
            }
            outputFile.delete()
        }

        val failure = checkNotNull(lastFailure)
        error("mnnsr process failed with exit code ${failure.exitCode}\n${failure.output}")
    }

    private suspend fun runInferenceAttempt(
        runtime: RuntimeFiles,
        inputFile: File,
        outputFile: File,
        backend: Int,
    ): AttemptFailure? {
        val processBuilder = ProcessBuilder("sh")
        processBuilder.directory(runtime.directory)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val processOutput = StringBuilder()
        val readerThread = thread(start = true, isDaemon = true, name = "anime4x-stdout") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        processOutput.appendLine(line)
                    }
                }
            } catch (_: InterruptedIOException) {
                // Expected when the process is cancelled and its pipe is closed externally.
            } catch (_: IOException) {
                // Expected during process teardown.
            } catch (e: Throwable) {
                if (process.isAlive) {
                    logcat(LogPriority.WARN, e) { "Failed to read MNN process output" }
                }
            }
        }

        process.outputStream.bufferedWriter().use { writer ->
            writer.appendLine("cd ${runtime.directory.absolutePath.shellEscape()}")
            writer.appendLine("export LD_LIBRARY_PATH=${runtime.directory.absolutePath.shellEscape()}")
            writer.appendLine("export TMPDIR=${runtime.directory.absolutePath.shellEscape()}")
            writer.appendLine("chmod 700 ${"./$BINARY_NAME".shellEscape()}")
            writer.appendLine(
                buildList {
                    add("./$BINARY_NAME")
                    add("-i")
                    add(inputFile.absolutePath)
                    add("-o")
                    add(outputFile.absolutePath)
                    add("-m")
                    add(runtime.model.absolutePath)
                    add("-s")
                    add(TARGET_SCALE.toString())
                    add("-b")
                    add(backend.toString())
                    backendTileSize(backend)?.let {
                        add("-t")
                        add(it.toString())
                    }
                    add("-f")
                    add(OUTPUT_FORMAT)
                }.joinToString(" ") { it.shellEscape() },
            )
            writer.appendLine("status=$?")
            writer.appendLine("exit \$status")
        }

        val timeoutAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(PROCESS_TIMEOUT_MINUTES)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    break
                }
                if (System.nanoTime() >= timeoutAt) {
                    process.destroyForcibly()
                    error("mnnsr process timed out after ${PROCESS_TIMEOUT_MINUTES} minutes")
                }
            }
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        } finally {
            runCatching { process.inputStream.close() }
            readerThread.join(1_000)
        }

        val exitCode = process.exitValue()
        return if (exitCode == 0) null else AttemptFailure(backend, exitCode, processOutput.toString())
    }

    private fun getOrCreateRuntime(): RuntimeFiles {
        runtimeFiles?.let { return it }
        runtimeInitFailure?.let { throw IllegalStateException("AI runtime is unavailable", it) }

        return synchronized(runtimeLock) {
            runtimeFiles?.let { return@synchronized it }
            runtimeInitFailure?.let { throw IllegalStateException("AI runtime is unavailable", it) }

            runCatching {
                val rootDir = File(app.cacheDir, RUNTIME_ROOT_DIR).apply { mkdirs() }
                val runtimeDir = File(rootDir, RUNTIME_VERSION_DIR)
                val readyMarker = File(runtimeDir, READY_MARKER)

                if (!readyMarker.isFile) {
                    rootDir.listFiles()
                        ?.filter { it != runtimeDir }
                        ?.forEach { it.deleteRecursively() }
                    runtimeDir.deleteRecursively()
                    runtimeDir.mkdirs()

                    RUNTIME_ASSET_FILES.forEach { fileName ->
                        app.assets.open("$RUNTIME_ASSET_ROOT/$fileName").use { assetStream ->
                            File(runtimeDir, fileName).outputStream().use { outputStream ->
                                assetStream.copyTo(outputStream)
                            }
                        }
                    }

                    check(File(runtimeDir, BINARY_NAME).setExecutable(true, true)) {
                        "Failed to mark $BINARY_NAME as executable"
                    }
                    runtimeDir.listFiles()?.forEach { it.setExecutable(true, false) }
                    readyMarker.writeText(RUNTIME_VERSION_DIR)
                } else {
                    check(File(runtimeDir, BINARY_NAME).setExecutable(true, true)) {
                        "Failed to mark $BINARY_NAME as executable"
                    }
                    runtimeDir.listFiles()?.forEach { it.setExecutable(true, false) }
                }

                RuntimeFiles(
                    directory = runtimeDir,
                    binary = File(runtimeDir, BINARY_NAME),
                    model = File(runtimeDir, MODEL_NAME),
                )
            }
                .onFailure { runtimeInitFailure = it }
                .getOrThrow()
                .also { runtimeFiles = it }
        }
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

    private fun isCpuOnlyMode(runtimeDir: File): Boolean {
        return File(runtimeDir, CPU_ONLY_MARKER).isFile
    }

    private fun markCpuOnlyMode(runtimeDir: File) {
        runCatching {
            File(runtimeDir, CPU_ONLY_MARKER).writeText("cpu")
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to persist CPU-only AI backend fallback" }
        }
    }

    private fun backendTileSize(backend: Int): Int? {
        return when (backend) {
            NN_BACKEND -> 64
            else -> null
        }
    }

    private enum class InputKind(
        val inputExtension: String,
    ) {
        Jpeg("jpg"),
        Png("png"),
        Gif("png"),
        Webp("png"),
        Unknown("png"),
    }

    companion object {
        private const val SUPPORTED_ABI = "arm64-v8a"
        private const val TARGET_SCALE = 2
        private const val CPU_BACKEND = 0
        private const val OPENCL_BACKEND = 3
        private const val NN_BACKEND = 5
        private const val OUTPUT_FORMAT = "png"
        private const val PROCESS_TIMEOUT_MINUTES = 10L
        private const val PROCESS_POLL_MILLIS = 250L

        private const val RUNTIME_ROOT_DIR = "reader_ai_runtime"
        private const val RUNTIME_VERSION_DIR = "nmkd-upgiflitev2-x2-arm64-v1"
        private const val READY_MARKER = ".ready"
        private const val WORK_DIR_NAME = "work"
        private const val CPU_ONLY_MARKER = ".cpu-only"

        private const val RUNTIME_ASSET_ROOT = "ai/mnnsr/arm64"
        private const val BINARY_NAME = "mnnsr-ncnn"
        private const val MODEL_NAME = "2x_NMKD-UpgifLiteV2_210k.mnn"
        private val RUNTIME_ASSET_FILES = listOf(
            BINARY_NAME,
            "libc++_shared.so",
            "libMNN.so",
            "libMNN_CL.so",
            "libMNN_Vulkan.so",
            "libomp.so",
            MODEL_NAME,
        )
    }
}

private fun String.shellEscape(): String {
    return "'" + replace("'", "'\\''") + "'"
}

private inline fun <T> Bitmap.useAndRecycle(block: (Bitmap) -> T): T {
    return try {
        block(this)
    } finally {
        recycle()
    }
}
