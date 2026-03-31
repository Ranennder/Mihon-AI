package eu.kanade.tachiyomi.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.upscale.Anime4xPageUpscaler
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class UpscaleDebugService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        startForeground(
            NOTIFICATION_ID,
            notificationBuilder(Notifications.CHANNEL_COMMON) {
                setSmallIcon(R.drawable.ic_mihon)
                setAutoCancel(false)
                setOngoing(true)
                setShowWhen(false)
                setContentTitle("AI debug")
                setContentText("Preparing full-page anime6b upscale")
                setProgress(100, 0, true)
            }.build(),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val inputPath = intent?.getStringExtra(EXTRA_INPUT_PATH)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)
        if (inputPath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            val inputFile = File(inputPath)
            val outputFile = File(outputPath)
            try {
                logcat { "Starting anime6b AI debug upscale for ${inputFile.absolutePath}" }
                val upscaler = Anime4xPageUpscaler(application, Injekt.get<ReaderPreferences>())
                val source = inputFile.inputStream().use { Buffer().readFrom(it) }
                val upscaledBytes = upscaler.upscaleSource(source)
                check(upscaledBytes != null) { "AI upscaler returned no output for ${inputFile.name}" }
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(upscaledBytes)
                logcat {
                    "anime6b AI debug upscale finished in ${SystemClock.elapsedRealtime() - startTime} ms: ${outputFile.absolutePath}"
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "anime6b AI debug upscale failed" }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_PATH = "output_path"

        private const val NOTIFICATION_ID = 42024
    }
}
