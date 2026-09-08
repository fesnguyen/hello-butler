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
        val triggerAt = event.triggerAtMillis()
        if (triggerAt == null || event.status != "planned" || !event.speakAloud ||
            event.content.isNullOrBlank() || event.playbackAttemptedAt != null) {
            cancel(event)
            return
        }
        if (triggerAt <= System.currentTimeMillis()) {
            // Do not erase a due Listen notification on routine sync, or replay old alarms.
            if (!event.isDueForSpeech()) cancel(event)
            return
        }
        cancel(event)

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
            // No background service launch from a delayed worker: offer a local Listen action.
            DailyEventExecutionWorker.enqueue(context, event.id, triggerAt - System.currentTimeMillis())
        }
    }

    fun cancel(event: DailyEventEntity) {
        alarms.cancel(alarmPendingIntent(event.id))
        DailyEventExecutionWorker.cancel(context, event.id)
        DeferredSpeechNotification.cancel(context, event.id)
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

internal fun DailyEventEntity.triggerAtMillis(): Long? {
    val date = runCatching { LocalDate.parse(eventDate) }.getOrNull() ?: return null
    val time = runCatching { startTime?.let(LocalTime::parse) }.getOrNull() ?: return null
    return LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

internal fun DailyEventEntity.isDueForSpeech(nowMillis: Long = System.currentTimeMillis()): Boolean {
    val due = triggerAtMillis() ?: return false
    return status == "planned" && speakAloud && !content.isNullOrBlank() &&
        playbackAttemptedAt == null && due <= nowMillis &&
        eventDate == java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

internal const val ACTION_EXECUTE_EVENT = "com.hellobutler.app.EXECUTE_DAILY_EVENT"
internal const val EXTRA_EVENT_ID = "daily_event_id"
