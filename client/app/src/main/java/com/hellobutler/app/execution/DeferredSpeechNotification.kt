package com.hellobutler.app.execution

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.MainActivity

internal object DeferredSpeechNotification {
    private const val CHANNEL = "butler_scheduled_listen"
    private const val ID = 4103

    fun show(context: Context, event: DailyEventEntity): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled Butler speech", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Listen to prepared speech when automatic alarms are unavailable" }
        )
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            Log.w("HelloButlerTTS", "Scheduled Listen notification unavailable; enable notifications or exact alarms")
            return false // Keep the Room occurrence unclaimed and the work retryable.
        }
        val listen = PendingIntent.getForegroundService(
            context, 0,
            Intent(context, SpeechForegroundService::class.java)
                .setData(Uri.parse("hellobutler://listen/${Uri.encode(event.id)}"))
                .putExtra(EXTRA_EVENT_ID, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, event.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            event.id, ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle(event.title)
                .setContentText("Your prepared Butler speech is ready. Tap Listen.")
                .setContentIntent(listen)
                .addAction(android.R.drawable.ic_media_play, "Listen", listen)
                .addAction(android.R.drawable.ic_menu_view, "Open in App", open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build(),
        )
        return true
    }

    fun cancel(context: Context, eventId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(eventId, ID)
    }
}
