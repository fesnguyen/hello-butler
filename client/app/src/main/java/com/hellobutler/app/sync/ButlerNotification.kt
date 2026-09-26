package com.hellobutler.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hellobutler.app.MainActivity
import com.hellobutler.app.core.AppVisibility
import com.hellobutler.app.data.remote.ButlerResultDto
import com.hellobutler.app.execution.ButlerAudioPlaybackService

object ButlerNotification {
    private const val CHANNEL = "butler_responses"
    fun showCompleted(context: Context, result: ButlerResultDto) {
        if (AppVisibility.isForeground || result.responseText.isNullOrBlank()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Butler responses", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, result.requestId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hello Butler")
            .setContentText(result.responseText).setStyle(NotificationCompat.BigTextStyle().bigText(result.responseText))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (result.audioStatus in setOf("pending", "processing", "ready")) {
            builder.addAction(android.R.drawable.ic_lock_silent_mode_off, "Play aloud", ButlerAudioPlaybackService.intent(context, result.requestId, false))
                .addAction(android.R.drawable.sym_call_incoming, "Listen privately", ButlerAudioPlaybackService.intent(context, result.requestId, true))
        }
        builder.addAction(android.R.drawable.ic_menu_view, "Open in App", open)
        manager.notify(result.requestId.hashCode(), builder.build())
    }
}
