package com.hellobutler.app.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hellobutler.app.MainActivity
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.sync.DailySyncWorker
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object SpeechPlaybackState {
    private val mutableSpeaking = MutableStateFlow(false)
    val speaking = mutableSpeaking.asStateFlow()
    internal fun setSpeaking(value: Boolean) { mutableSpeaking.value = value }
}

class DailyEventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_EVENT) return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        try {
            SpeechForegroundService.start(context, eventId)
        } catch (error: IllegalStateException) {
            // Permission can be revoked between scheduling and delivery. Do not consume the claim.
            DailyEventExecutionWorker.enqueue(context, eventId, 0)
        } catch (error: SecurityException) {
            DailyEventExecutionWorker.enqueue(context, eventId, 0)
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

class SpeechForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playback: Job? = null
    private var tts: LocalTextToSpeech? = null
    private var activeStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Butler speech", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while Butler is speaking" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SPEECH) {
            stopSpeech()
            return START_NOT_STICKY
        }
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startAsForeground(notification("Preparing Butler speech…"))
        } catch (error: IllegalStateException) {
            DailyEventExecutionWorker.enqueue(this, eventId, 0)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeStartId = startId
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelloButler:ScheduledSpeech")
            .apply { acquire(130_000) } // Bound CPU wake time while the locked device initializes TTS.
        playback?.cancel()
        tts?.stop()
        playback = scope.launch {
            try {
                speak(eventId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("HelloButlerTTS", "Scheduled speech failed", error)
            } finally {
                if (activeStartId == startId) finish(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun speak(eventId: String) {
        val container = (application as ButlerApplication).container
        val dao = container.database.dailyEventDao()
        val event = dao.get(eventId)
        if (event == null || !event.isDueForSpeech()) {
            return
        }
        if (dao.claimPlayback(
                event.id, Instant.now().toString(), event.eventDate, event.startTime.orEmpty(),
                event.content.orEmpty(), event.version,
            ) != 1) {
            return
        }
        DeferredSpeechNotification.cancel(this, eventId)
        SpeechPlaybackState.setSpeaking(true)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification(event.title)
        )
        val player = LocalTextToSpeech(applicationContext)
        tts = player
        val spoken = withTimeoutOrNull(120_000) { player.speak(event.content.orEmpty()) } ?: false
        if (!spoken) Log.w("HelloButlerTTS", "Local TTS could not play event ${event.id}")
    }

    private fun stopSpeech() {
        tts?.stop()
        playback?.cancel()
        finish(null)
    }

    private fun finish(startId: Int?) {
        tts?.stop()
        tts = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        SpeechPlaybackState.setSpeaking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun notification(title: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, SpeechForegroundService::class.java).setAction(ACTION_STOP_SPEECH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Butler is speaking")
            .setContentText(title)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .addAction(android.R.drawable.ic_menu_view, "Open in App", open)
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        tts?.stop()
        scope.cancel()
        SpeechPlaybackState.setSpeaking(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "butler_speech"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP_SPEECH = "com.hellobutler.app.STOP_SPEECH"

        fun start(context: Context, eventId: String) {
            val intent = Intent(context, SpeechForegroundService::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpeechForegroundService::class.java).setAction(ACTION_STOP_SPEECH)
            )
        }
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
