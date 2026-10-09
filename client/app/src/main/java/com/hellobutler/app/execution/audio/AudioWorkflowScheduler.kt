package com.hellobutler.app.execution.audio

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.MainActivity
import com.hellobutler.app.core.AppVisibility
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import java.util.concurrent.TimeUnit

class AudioWorkflowScheduler(private val context: Context, private val store: AudioWorkflowStore) {
    fun schedule(execution: AudioExecution) {
        WorkManager.getInstance(context).enqueueUniqueWork("${workName(execution.id)}:${execution.next}:${execution.dueAt}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudioContinuationWorker>()
                .addTag(workName(execution.id))
                .setInputData(Data.Builder().putString("execution_id", execution.id).build())
                .setInitialDelay((execution.dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
        showWaiting(execution)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, execution.dueAt, alarm(execution.id)) }
        }
    }
    private fun showWaiting(execution: AudioExecution) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Butler audio ready", NotificationManager.IMPORTANCE_LOW))
        runCatching { manager.notify(execution.id, NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Hello Butler")
            .setContentText("Butler speech is scheduled to continue shortly.").setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", ButlerAudioPlaybackService.cancelIntent(context, execution.id)).build()) }
    }
    fun dismiss(id: String) { context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID) }
    fun cancel(id: String) {
        store.get(id)?.let { store.save(it.copy(status = "cancelled")) }
        WorkManager.getInstance(context).cancelAllWorkByTag(workName(id))
        context.getSystemService(AlarmManager::class.java).cancel(alarm(id))
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
    }
    fun recover() {
        store.prune()
        store.all().forEach { execution ->
            when (execution.status) {
                "waiting", "offered", "ready" -> schedule(execution.copy(dueAt = execution.dueAt.coerceAtLeast(System.currentTimeMillis())))
                "playing", "preparing" -> cancel(execution.id) // Audible output cannot be atomically committed; prefer no duplicate alarm.
            }
        }
    }
    fun clear() { store.all().forEach { cancel(it.id) }; store.clear() }
    fun offer(execution: AudioExecution): Boolean {
        val latest = store.get(execution.id) ?: return true
        if (latest.next != execution.next || latest.dueAt != execution.dueAt || latest.status in setOf("playing", "complete", "cancelled")) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Butler audio ready", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val listen = PendingIntent.getForegroundService(context, execution.id.hashCode(),
            ButlerAudioPlaybackService.resumeIntent(context, execution.id, manual = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, execution.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            store.transition(execution, execution.copy(status = "offered")) {
                manager.notify(execution.id, NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Hello Butler")
                .setContentText("Your Butler speech is ready. Tap Listen.").setContentIntent(open)
                .addAction(android.R.drawable.ic_media_play, "Listen", listen).setAutoCancel(true).build())
            }
            true
        }.getOrDefault(false)
    }
    private fun alarm(id: String) = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, AudioContinuationReceiver::class.java).setData(Uri.parse("hellobutler://audio/${Uri.encode(id)}"))
            .putExtra("execution_id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    companion object {
        private const val CHANNEL = "butler_audio_ready"
        private const val NOTIFICATION_ID = 4210
        private fun workName(id: String) = "butler-audio-continuation-$id"
    }
}

class AudioContinuationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("execution_id") ?: return
        // Exact alarm provides a background foreground-service exemption; WorkManager remains the durable fallback.
        runCatching { ButlerAudioPlaybackService.resume(context, id) }
    }
}

class AudioContinuationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("execution_id") ?: return Result.failure()
        val container = (applicationContext as ButlerApplication).container
        val execution = container.audioWorkflowStore.get(id) ?: return Result.success()
        if (execution.status in setOf("complete", "cancelled", "playing", "preparing")) return Result.success()
        if (!container.authRepository.hasSession() || (execution.automatic && execution.requestId != null &&
            System.currentTimeMillis() - execution.createdAt > 600_000)) {
            container.audioWorkflowScheduler.cancel(id)
            return Result.success()
        }
        if (execution.dueAt > System.currentTimeMillis()) return Result.retry()
        if (execution.automatic && !container.soundVoice.state.value.allows(execution.workflow)) {
            return if (container.audioWorkflowScheduler.offer(execution)) Result.success() else Result.retry()
        }
        if (AppVisibility.isForeground) {
            try { ButlerAudioPlaybackService.resume(applicationContext, id); return Result.success() }
            catch (_: IllegalStateException) { /* Offer user-initiated playback below. */ }
            catch (_: SecurityException) { /* Offer user-initiated playback below. */ }
        }
        return if (container.audioWorkflowScheduler.offer(execution)) Result.success() else Result.retry()
    }
}
