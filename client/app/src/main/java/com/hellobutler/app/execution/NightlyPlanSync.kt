package com.hellobutler.app.execution

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class NightlyPlanSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) {
            scheduleNext(applicationContext)
            return Result.success()
        }

        return runCatching {
            val synced = container.dailyEventRepository.refresh(LocalDate.now().plusDays(1).toString())
            if (!synced) return Result.retry() // Planning may still be finishing; retry until tomorrow exists.
            scheduleNext(applicationContext)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "nightly-plan-sync"
        private const val SYNC_HOUR = 23
        private const val SYNC_MINUTE = 10

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(SYNC_HOUR).withMinute(SYNC_MINUTE).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<NightlyPlanSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
