package com.hellobutler.app.execution.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hellobutler.app.MainActivity
import com.hellobutler.app.execution.ButlerAudioPlaybackService

/** Presents continuation notifications without deciding or persisting workflow state. */
internal class AudioWorkflowNotifications(private val context: Context) {
    /** Prepares platform actions; the caller invokes publication inside its guarded transition. */
    fun prepareOffer(execution: AudioExecution): (() -> Unit)? {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Butler audio ready", NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return null
        val listen = PendingIntent.getForegroundService(
            context, execution.id.hashCode(),
            ButlerAudioPlaybackService.resumeIntent(context, execution.id, manual = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, execution.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return {
            manager.notify(
                execution.id, NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Hello Butler")
                    .setContentText("Your Butler speech is ready. Tap Listen.")
                    .setContentIntent(open)
                    .addAction(android.R.drawable.ic_media_play, "Listen", listen)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** Shows the delayed-playback notification without making notification failures fatal. */
    fun showWaiting(execution: AudioExecution) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Butler audio ready", NotificationManager.IMPORTANCE_LOW),
        )
        runCatching {
            manager.notify(
                execution.id, NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Hello Butler")
                    .setContentText("Butler speech is scheduled to continue shortly.")
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_media_pause, "Stop", ButlerAudioPlaybackService.cancelIntent(context, execution.id))
                    .build(),
            )
        }
    }

    /** Removes only this execution's continuation notification. */
    fun dismiss(id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL = "butler_audio_ready"
        private const val NOTIFICATION_ID = 4210
    }
}
