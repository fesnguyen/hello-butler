package com.hellobutler.app.execution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

class DailyEventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_EVENT) return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        DailyEventExecutionWorker.enqueue(context, eventId, 0)
    }
}

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ScheduleRestoreWorker.enqueue(context)
        }
    }
}

class DailyEventExecutionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(EXTRA_EVENT_ID) ?: return Result.success()
        val container = (applicationContext as ButlerApplication).container
        val dao = container.database.dailyEventDao()
        val event = dao.get(eventId) ?: return Result.success()
        if (event.content.isNullOrBlank() || !event.speakAloud) return Result.success()

        // Claim before TTS initialization: retries, duplicate alarms, and process restarts
        // must never turn one proactive brief into repeated speech.
        if (dao.claimPlayback(event.id, Instant.now().toString()) != 1) return Result.success()
        val spoken = withTimeoutOrNull(120_000) {
            LocalTextToSpeech(applicationContext).speak(event.content)
        } ?: false
        if (!spoken) Log.w("HelloButlerTTS", "Local TTS could not play event ${event.id}")
        return Result.success() // TTS failures are terminal for this occurrence, not retryable work.
    }

    companion object {
        fun enqueue(context: Context, eventId: String, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<DailyEventExecutionWorker>()
                .setInputData(Data.Builder().putString(EXTRA_EVENT_ID, eventId).build())
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                executionWorkName(eventId), ExistingWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context, eventId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(executionWorkName(eventId))
        }

        private fun executionWorkName(eventId: String) = "daily-event-execution-$eventId"
    }
}

class ScheduleRestoreWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        container.database.dailyEventDao().pendingSpokenEvents().forEach(
            container.eventScheduler::schedule
        )
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "restore-daily-event-schedules",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduleRestoreWorker>().build(),
            )
        }
    }
}
