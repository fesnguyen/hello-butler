package com.hellobutler.app.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.hellobutler.app.data.local.DailyEventEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class DailyEventScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun reconcile(previous: List<DailyEventEntity>, current: List<DailyEventEntity>) {
        val currentIds = current.mapTo(mutableSetOf(), DailyEventEntity::id)
        previous.filter { it.syncedFromServer && it.id !in currentIds }.forEach(::cancel)
        current.forEach(::schedule)
    }

    fun schedule(event: DailyEventEntity) {
        cancel(event)
        val triggerAt = event.triggerAtMillis() ?: return
        if (event.status != "planned" || !event.speakAloud || event.content.isNullOrBlank() || event.playbackAttemptedAt != null) return
        if (triggerAt <= System.currentTimeMillis()) return

        val pending = alarmPendingIntent(event.id)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }.onFailure {
                DailyEventExecutionWorker.enqueue(
                    context, event.id, triggerAt - System.currentTimeMillis()
                )
            }
        } else {
            // Android 12+ may withhold exact-alarm access; WorkManager preserves execution,
            // with the explicit trade-off that playback can be delayed by the OS.
            DailyEventExecutionWorker.enqueue(context, event.id, triggerAt - System.currentTimeMillis())
        }
    }

    fun cancel(event: DailyEventEntity) {
        alarms.cancel(alarmPendingIntent(event.id))
        DailyEventExecutionWorker.cancel(context, event.id)
    }

    private fun alarmPendingIntent(eventId: String): PendingIntent {
        val intent = Intent(context, DailyEventAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_EVENT
            data = Uri.parse("hellobutler://daily-event/${Uri.encode(eventId)}")
            putExtra(EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private fun DailyEventEntity.triggerAtMillis(): Long? {
    val date = runCatching { LocalDate.parse(eventDate) }.getOrNull() ?: return null
    val time = runCatching { startTime?.let(LocalTime::parse) }.getOrNull() ?: return null
    return LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

internal const val ACTION_EXECUTE_EVENT = "com.hellobutler.app.EXECUTE_DAILY_EVENT"
internal const val EXTRA_EVENT_ID = "daily_event_id"
