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

private val NIGHTLY_PLAN_TIME = java.time.LocalTime.of(22, 30)

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

        val today = LocalDate.now()
        val date = runCatching { LocalDate.parse(inputData.getString("summary_date")) }.getOrNull()
        if (date == null || date.isBefore(today.minusDays(1)) || date.isAfter(today)) {
            scheduleNext(applicationContext)
            return Result.success()
        }
        return try {
            container.dailyEventRepository.prepareEvening(date.toString(), date.plusDays(1).toString())
            scheduleNext(applicationContext)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep future nights independent of a prolonged retry for this date.
            scheduleNext(applicationContext)
            Result.retry()
        }
    }

    companion object {
        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now()
            val cutoff = now.withHour(NIGHTLY_PLAN_TIME.hour).withMinute(NIGHTLY_PLAN_TIME.minute).withSecond(0).withNano(0)
            val next = if (cutoff.isAfter(now)) cutoff else cutoff.plusDays(1)
            enqueue(context, next.toLocalDate(), Duration.between(now, next).toMillis())
            val missed = nightlyPlanningDate(now)
            val prepared = context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE)
                .getString("prepared_date", null)
            if (!eveningAlreadyPrepared(prepared, missed.toString())) enqueue(context, missed, 0)
        }

        private fun enqueue(context: Context, date: LocalDate, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<NightlyPlanSyncWorker>()
                .setInputData(androidx.work.Data.Builder().putString("summary_date", date.toString()).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            // KEEP joins an active date's request; the repository records server success durably.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "nightly-plan-sync-$date", ExistingWorkPolicy.KEEP, request
            )
        }
    }
}

/** Recover only the latest due evening, rather than regenerating an unbounded backlog. */
internal fun nightlyPlanningDate(now: ZonedDateTime): LocalDate =
    if (now.toLocalTime() >= NIGHTLY_PLAN_TIME) now.toLocalDate() else now.toLocalDate().minusDays(1)

internal fun eveningAlreadyPrepared(prepared: String?, requested: String): Boolean =
    prepared != null && prepared >= requested // ISO local dates sort chronologically.
