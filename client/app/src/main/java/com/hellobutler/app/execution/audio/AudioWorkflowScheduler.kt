
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
import com.hellobutler.app.execution.matchesExecution
import java.util.concurrent.TimeUnit


/** Coordinates durable continuations; notification presentation has a separate owner. */
class AudioWorkflowScheduler(
    private val context: Context,
    private val store: AudioWorkflowStore,
) {
    private val notifications = AudioWorkflowNotifications(context)

    // Scheduling and cancellation ------------------------------------------------

    /** Registers durable work and an optional exact alarm for the persisted checkpoint. */
    fun schedule(execution: AudioExecution) {
        if (!execution.canContinue(execution.next, execution.dueAt)) return
        store.transition(execution, execution) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(execution.id), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AudioContinuationWorker>()
                    .addTag(workName(execution.id)) // Also cancels pre-upgrade checkpoint-specific work.
                    .setInputData(Data.Builder().putString("execution_id", execution.id)
                        .putInt("checkpoint", execution.next).putLong("due_at", execution.dueAt).build())
                    .setInitialDelay((execution.dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build(),
            )
            if (execution.status == "waiting") notifications.showWaiting(execution)
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                runCatching {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, execution.dueAt,
                        alarm(execution.id, execution.next, execution.dueAt))
                }
            }
        }
    }

    /** Marks cancellation before removing effects; a stale validator cannot cancel newer state. */
    fun cancel(id: String, expected: AudioExecution? = null) {
        store.cancel(id, expected) {
            WorkManager.getInstance(context).cancelAllWorkByTag(workName(id))
            context.getSystemService(AlarmManager::class.java).cancel(alarm(id))
            notifications.dismiss(id)
        }
    }

    /** Repairs delayed checkpoints after restart without replaying ambiguous audible segments. */
    fun recover(cancelInterrupted: Boolean = true) {
        store.prune()
        store.all().forEach { execution ->
            when (execution.status) {
                "waiting", "offered", "ready" -> schedule(execution)
                "playing", "preparing" -> if (cancelInterrupted) cancel(execution.id, execution)
            }
        }
    }

    /** Cancels scheduled effects before removing execution and rotation records. */
    fun clear() {
        store.all().forEach { cancel(it.id) }
        store.clear()
    }

    // Manual continuation presentation ------------------------------------------

    /** Offers Listen only for a matching checkpoint; publication stays inside the store guard. */
    fun offer(execution: AudioExecution): Boolean {
        val latest = store.get(execution.id) ?: return true
        if (latest.next != execution.next || latest.dueAt != execution.dueAt ||
            latest.status in setOf("playing", "complete", "cancelled")) return true
        val publish = notifications.prepareOffer(execution) ?: return false
        return runCatching {
            // Publish before saving 'offered', under the existing shared transition lock.
            store.transition(execution, execution.copy(status = "offered"), publish)
            true // A declined stale transition is already handled, rather than retryable.
        }.getOrDefault(false)
    }

    /** Removes presentation without changing execution state or scheduled work. */
    fun dismiss(id: String) = notifications.dismiss(id)

    /** Retains the execution-specific alarm identity for both registration and cancellation. */
    private fun alarm(id: String, next: Int = -1, dueAt: Long = -1) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, AudioContinuationReceiver::class.java)
            .setData(Uri.parse("hellobutler://audio/${Uri.encode(id)}"))
            .putExtra("execution_id", id).putExtra("checkpoint", next).putExtra("due_at", dueAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Groups all checkpoint-specific work for execution-wide cancellation. */
        private fun workName(id: String) = "butler-audio-continuation-$id"
    }
}

// ------------------------------------------------------------
// Receiver - Exact Alarm Continuation
// ------------------------------------------------------------

/**
 * Handles exact-alarm broadcasts for delayed audio workflows.
 *
 * Attempts to resume the requested execution when Android
 * delivers its scheduled alarm.
 */
class AudioContinuationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("execution_id") ?: return
        val container = (context.applicationContext as ButlerApplication).container
        val execution = container.audioWorkflowStore.get(id) ?: return
        val next = intent.getIntExtra("checkpoint", -1)
        val dueAt = intent.getLongExtra("due_at", -1)
        if (!execution.canContinue(next, dueAt) || dueAt > System.currentTimeMillis()) return
        // The service checks this checkpoint again before claiming it.
        runCatching { ButlerAudioPlaybackService.resume(context, id, next, dueAt) }
        // Durable work remains available if Android rejects the foreground launch.
    }
}


// ------------------------------------------------------------
// Worker - Durable Audio Continuation
// ------------------------------------------------------------

/**
 * Resumes delayed audio workflows through WorkManager.
 *
 * Provides a durable fallback when exact alarms are unavailable
 * or delayed, while validating execution eligibility before
 * attempting playback or offering a Listen notification.
 */
class AudioContinuationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    // --------------------------------------------------------
    // Entry Point - Execute Scheduled Work
    // --------------------------------------------------------

    /**
     * Processes a scheduled audio continuation request.
     *
     * Validates persisted execution state, session availability,
     * event consistency, timing, and speech preferences before
     * choosing automatic playback or manual notification.
     */
    override suspend fun doWork(): Result {

        // ----------------------------------------------------
        // Resolve Scheduled Execution
        // ----------------------------------------------------

        // Reject worker requests without an execution identifier.
        val id = inputData.getString("execution_id")
            ?: return Result.failure()

        // Access the application's shared dependencies.
        val container = (applicationContext as ButlerApplication).container

        // Treat missing executions as already handled.
        val execution = container.audioWorkflowStore.get(id)
            ?: return Result.success()

        // ----------------------------------------------------
        // Validate Execution Status
        // ----------------------------------------------------

        // Ignore executions that are finished or currently active.
        // These must not be started again by a delayed worker.
        if (!execution.canContinue(inputData.getInt("checkpoint", -1), inputData.getLong("due_at", -1))) {
            return Result.success()
        }

        // ----------------------------------------------------
        // Validate Associated Daily Event
        // ----------------------------------------------------

        // Ensure an event-backed execution still matches
        // the current event data before continuing playback.
        if (
            execution.eventId != null &&
            !container.database.dailyEventDao()
                .get(execution.eventId)
                .matchesExecution(execution)
        ) {

            // Cancel stale executions when the event has changed.
            container.audioWorkflowScheduler.cancel(id, execution)

            return Result.success()
        }

        // ----------------------------------------------------
        // Validate Session & Response Expiration
        // ----------------------------------------------------

        // Reject workflows without an authenticated session.
        // Also expire automatic responses older than ten minutes.
        if (
            !container.authRepository.hasSession() ||
            (
                    execution.automatic &&
                            execution.requestId != null &&
                            System.currentTimeMillis() - execution.createdAt > 600_000
                    )
        ) {

            // Cancel workflows that are no longer eligible.
            container.audioWorkflowScheduler.cancel(id, execution)

            return Result.success()
        }

        // ----------------------------------------------------
        // Validate Scheduled Time
        // ----------------------------------------------------

        // Retry when WorkManager runs before the execution is due.
        if (execution.dueAt > System.currentTimeMillis()) {
            return Result.retry()
        }

        // ----------------------------------------------------
        // Check Automatic Speech Preferences
        // ----------------------------------------------------

        // Do not automatically speak when the corresponding
        // workflow preference is disabled.
        if (
            execution.automatic &&
            !container.soundVoice.state.value.allows(execution.workflow)
        ) {

            // Offer user-initiated playback instead.
            // Retry if the notification could not be offered.
            return if (container.audioWorkflowScheduler.offer(execution)) {
                Result.success()
            } else {
                Result.retry()
            }
        }

        // ----------------------------------------------------
        // Attempt Automatic Playback
        // ----------------------------------------------------

        // Attempt automatic continuation only when the app
        // is currently in the foreground.
        if (AppVisibility.isForeground) {
            try {

                // Request the playback service to resume this execution.
                ButlerAudioPlaybackService.resume(
                    applicationContext,
                    id, execution.next, execution.dueAt,
                )

                // The service launch was requested successfully.
                return Result.success()

            } catch (_: IllegalStateException) {
                // Fall back to manual playback notification
                // when Android prevents service startup.

            } catch (_: SecurityException) {
                // Fall back to manual playback notification
                // when the launch is not permitted.
            }
        }

        // ----------------------------------------------------
        // Fallback - Manual Playback Notification
        // ----------------------------------------------------

        // When automatic playback is unavailable, offer Listen.
        // Retry if the notification cannot be published.
        return if (container.audioWorkflowScheduler.offer(execution)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
