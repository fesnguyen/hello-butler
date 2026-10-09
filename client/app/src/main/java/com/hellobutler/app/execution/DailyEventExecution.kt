package com.hellobutler.app.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.sync.DailySyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SpeechPlaybackState {
    private val mutableSpeaking = MutableStateFlow(false)
    val speaking = mutableSpeaking.asStateFlow()
    internal fun setSpeaking(value: Boolean) { mutableSpeaking.value = value }
}

class DailyEventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_EVENT) return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val pending = goAsync()
        (context.applicationContext as ButlerApplication).applicationScope.launch {
            try {
                val container = (context.applicationContext as ButlerApplication).container
                val event = container.database.dailyEventDao().get(eventId) ?: return@launch
                if (!event.isDueForSpeech()) return@launch
                DeferredSpeechNotification.show(context, event)
                if (container.soundVoice.state.value.allows(com.hellobutler.app.execution.audio.AudioWorkflows.forEvent(event.eventType))) {
                    try { SpeechForegroundService.start(context, eventId) }
                    catch (_: IllegalStateException) { DailyEventExecutionWorker.enqueue(context, eventId, 0) }
                    catch (_: SecurityException) { DailyEventExecutionWorker.enqueue(context, eventId, 0) }
                }
            } finally { pending.finish() }
        }
    }
}

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )) {
            ScheduleRestoreWorker.enqueue(context)
            NightlyPlanSyncWorker.scheduleNext(context)
            DailySyncWorker.enqueue(context)
        }
    }
}

/** Compatibility entry point for existing scheduled Listen intents; playback has one owner. */
class SpeechForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SPEECH) ButlerAudioPlaybackService.stop(this)
        else intent?.getStringExtra(EXTRA_EVENT_ID)?.let { eventId ->
            val channel = NotificationChannel("butler_speech", "Butler speech", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = NotificationCompat.Builder(this, "butler_speech")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Hello Butler").setContentText("Preparing speech…").build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(4102, notification)
            try { ButlerAudioPlaybackService.playEvent(this, eventId) }
            finally { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_STOP_SPEECH = "com.hellobutler.app.STOP_SPEECH"
        fun start(context: Context, eventId: String) = ButlerAudioPlaybackService.playEvent(context, eventId, automatic = true)
        fun stop(context: Context) = ButlerAudioPlaybackService.stop(context)
    }
}

class DailyEventExecutionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(EXTRA_EVENT_ID) ?: return Result.success()
        return try {
            val container = (applicationContext as ButlerApplication).container
            val event = container.database.dailyEventDao().get(eventId)
            if (event != null && event.isDueForSpeech()) {
                // A delayed worker has no background FGS exemption. The Listen action does.
                if (!DeferredSpeechNotification.show(applicationContext, event)) return Result.retry()
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("HelloButlerTTS", "Could not offer scheduled playback", error)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, eventId: String, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<DailyEventExecutionWorker>()
                .setInputData(Data.Builder().putString(EXTRA_EVENT_ID, eventId).build())
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                executionWorkName(eventId), ExistingWorkPolicy.REPLACE, request
            )
        }

        fun cancel(context: Context, eventId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(executionWorkName(eventId))
        }

        private fun executionWorkName(eventId: String) = "daily-event-execution-$eventId"
    }
}

class ScheduleRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        container.database.dailyEventDao().pendingSpokenEvents().forEach(container.eventScheduler::schedule)
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
