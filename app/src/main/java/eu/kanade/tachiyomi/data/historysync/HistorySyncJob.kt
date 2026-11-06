package eu.kanade.tachiyomi.data.historysync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.workManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class HistorySyncJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (Injekt.get<HistorySyncManager>().syncNow()) Result.success() else Result.retry()

    companion object {
        private const val PERIODIC = "HistorySyncPeriodic"
        private const val PENDING = "HistorySyncPending"
        private val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        fun configure(context: Context, enabled: Boolean) {
            if (!enabled) {
                context.workManager.cancelUniqueWork(PERIODIC)
                context.workManager.cancelUniqueWork(PENDING)
                return
            }
            val request = PeriodicWorkRequestBuilder<HistorySyncJob>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
            enqueue(context)
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistorySyncJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniqueWork(PENDING, ExistingWorkPolicy.KEEP, request)
        }
    }
}
