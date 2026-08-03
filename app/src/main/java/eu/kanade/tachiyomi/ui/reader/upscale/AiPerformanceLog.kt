package eu.kanade.tachiyomi.ui.reader.upscale

import android.content.Context
import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

object AiPerformanceLog {
    private const val FILE_NAME = "ai-performance.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private val lock = Any()

    fun append(context: Context, message: String) {
        synchronized(lock) {
            val file = file(context)
            if (file.length() >= MAX_BYTES) {
                file.copyTo(File(file.parentFile, "$FILE_NAME.old"), overwrite = true)
                file.writeText("")
            }
            file.appendText("${OffsetDateTime.now()} $message\n")
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        listOf(File(file(context).parentFile, "$FILE_NAME.old"), file(context))
            .filter(File::isFile)
            .joinToString(separator = "") { it.readText() }
    }

    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
        File(file(context).parentFile, "$FILE_NAME.old").delete()
    }

    fun eventListener(context: Context): EventListener = PerformanceEventListener(context.applicationContext)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private class PerformanceEventListener(private val context: Context) : EventListener() {
        private val startedAt = ConcurrentHashMap<Call, Long>()
        private val uploadedBytes = ConcurrentHashMap<Call, Long>()
        private val statusCodes = ConcurrentHashMap<Call, Int>()

        override fun callStart(call: Call) {
            startedAt[call] = SystemClock.elapsedRealtime()
            append(context, "PHONE start ${label(call)}")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            uploadedBytes[call] = byteCount
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            statusCodes[call] = response.code
        }

        override fun callEnd(call: Call) = finish(call, null)

        override fun callFailed(call: Call, ioe: IOException) {
            finish(call, ioe.javaClass.simpleName + ": " + (ioe.message ?: "unknown error"))
        }

        private fun finish(call: Call, error: String?) {
            val elapsed = startedAt.remove(call)?.let { SystemClock.elapsedRealtime() - it }
            val details = buildString {
                append("PHONE end ${label(call)}")
                elapsed?.let { append(" elapsed=${it}ms") }
                statusCodes.remove(call)?.let { append(" status=$it") }
                uploadedBytes.remove(call)?.let { append(" uploaded=$it") }
                error?.let { append(" error=$it") }
            }
            append(context, details)
        }

        private fun label(call: Call): String {
            val request = call.request()
            val traceId = request.header("X-Reader-AI-Trace-Id") ?: "none"
            val endpoint = "${request.url.scheme}://${request.url.host}${request.url.encodedPath}"
            return "trace=$traceId ${request.method} $endpoint"
        }
    }
}
