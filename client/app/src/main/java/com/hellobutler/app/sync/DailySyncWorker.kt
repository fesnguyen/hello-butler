package com.hellobutler.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class DailySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        val today = LocalDate.now()
        return runCatching {
            container.dailyEventRepository.synchronize(
                listOf(today.toString(), today.plusDays(1).toString()) +
                    inputData.getStringArray("plan_dates").orEmpty().toList()
            )
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context, dates: List<String> = emptyList()) {
            val request = OneTimeWorkRequestBuilder<DailySyncWorker>()
                .setInputData(Data.Builder().putStringArray("plan_dates", dates.distinct().toTypedArray()).build())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "daily-event-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailySyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "periodic-daily-event-sync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
