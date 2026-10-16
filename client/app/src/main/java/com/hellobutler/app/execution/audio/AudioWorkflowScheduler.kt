
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


// ------------------------------------------------------------
// Scheduler - Audio Workflow Continuation
// ------------------------------------------------------------

/**
 * Manages delayed audio workflow execution.
 *
 * Uses WorkManager for durable scheduling and exact alarms
 * for more timely execution when Android permits them.
 *
 * Also manages waiting notifications, cancellation, and
 * recovery of workflows after application restarts.
 */
class AudioWorkflowScheduler(
    private val context: Context,
    private val store: AudioWorkflowStore,
) {

    // --------------------------------------------------------
    // Entry Point - Schedule Workflow
    // --------------------------------------------------------

    /**
     * Schedules the next step of an audio workflow.
     *
     * Registers a durable WorkManager task, displays a waiting
     * notification, and attempts to register an exact alarm.
     *
     * Both scheduling mechanisms target the same execution,
     * allowing the continuation handler to reject stale work.
     */
    fun schedule(execution: AudioExecution) {

        // ----------------------------------------------------
        // Durable Scheduling - WorkManager
        // ----------------------------------------------------

        // Enqueue a uniquely identified continuation task.
        // Include the step and due time so distinct checkpoints
        // can have separate WorkManager records.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${workName(execution.id)}:${execution.next}:${execution.dueAt}",
            ExistingWorkPolicy.KEEP,

            // Construct a one-time worker to resume this execution.
            OneTimeWorkRequestBuilder<AudioContinuationWorker>()

                // Group all continuation tasks by execution ID
                // so they can be cancelled together.
                .addTag(workName(execution.id))

                // Pass the execution ID to the worker.
                .setInputData(
                    Data.Builder()
                        .putString("execution_id", execution.id)
                        .build()
                )

                // Convert the absolute due time into a relative delay.
                // Clamp negative values so overdue work runs promptly.
                .setInitialDelay(
                    (execution.dueAt - System.currentTimeMillis())
                        .coerceAtLeast(0),
                    TimeUnit.MILLISECONDS,
                )
                .build(),
        )

        // Inform the user that playback is waiting to continue.
        showWaiting(execution)

        // ----------------------------------------------------
        // Timely Scheduling - Exact Alarm
        // ----------------------------------------------------

        // Obtain Android's alarm scheduling service.
        val alarms = context.getSystemService(AlarmManager::class.java)

        // Android 12+ requires exact-alarm capability.
        // Earlier Android versions do not use this check.
        if (
            Build.VERSION.SDK_INT < 31 ||
            alarms.canScheduleExactAlarms()
        ) {
            // Attempt an exact alarm without failing the whole
            // scheduling operation if the alarm cannot be registered.
            runCatching {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    execution.dueAt,
                    alarm(execution.id),
                )
            }
        }
    }

    // --------------------------------------------------------
    // Entry Point - Cancel Workflow
    // --------------------------------------------------------

    /**
     * Cancels a workflow and all of its pending continuations.
     *
     * Marks the stored execution as cancelled, removes its
     * WorkManager tasks and exact alarm, and clears its notification.
     */
    fun cancel(id: String) {

        // Mark the execution cancelled so future callbacks
        // can recognize that it must not resume.
        store.get(id)?.let { execution ->
            store.save(
                execution.copy(status = "cancelled")
            )
        }

        // Cancel all WorkManager tasks associated with this execution.
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(workName(id))

        // Remove the exact alarm for this execution.
        context.getSystemService(AlarmManager::class.java)
            .cancel(alarm(id))

        // Remove the waiting or Listen notification.
        context.getSystemService(NotificationManager::class.java)
            .cancel(id, NOTIFICATION_ID)
    }

    // --------------------------------------------------------
    // Entry Point - Recover Workflows
    // --------------------------------------------------------

    /**
     * Reconciles persisted audio workflows after a restart.
     *
     * Reschedules workflows that can still continue and cancels
     * executions interrupted during preparation or playback
     * to avoid unintentionally replaying audio.
     */
    fun recover() {

        // Remove obsolete execution records and eligible old files.
        store.prune()

        // Inspect every persisted execution to determine recovery behavior.
        store.all().forEach { execution ->

            when (execution.status) {

                // Restore workflows that have not completed playback.
                "waiting", "offered", "ready" -> {
                    schedule(
                        execution.copy(
                            // Avoid scheduling a recovered task in the past.
                            dueAt = execution.dueAt.coerceAtLeast(
                                System.currentTimeMillis()
                            ),
                        )
                    )
                }

                // Cancel playback/preparation interrupted by process death.
                // Replaying these could duplicate speech already heard.
                "playing", "preparing" -> {
                    cancel(execution.id)
                }
            }
        }
    }

    // --------------------------------------------------------
    // Entry Point - Clear All Workflows
    // --------------------------------------------------------

    /**
     * Removes all persisted audio workflows and their scheduled work.
     *
     * Ensures each execution's alarms, workers, and notifications
     * are cancelled before clearing the execution journal.
     */
    fun clear() {

        // Cancel every execution using the normal cleanup path.
        store.all().forEach { execution ->
            cancel(execution.id)
        }

        // Remove all remaining workflow records and rotation data.
        store.clear()
    }

    // --------------------------------------------------------
    // Entry Point - Offer Manual Playback
    // --------------------------------------------------------

    /**
     * Offers a Listen notification when automatic playback cannot proceed.
     *
     * Checks the current execution state, verifies notification
     * availability, and offers user-initiated playback.
     *
     * Returns false when the notification cannot be offered.
     */
    fun offer(execution: AudioExecution): Boolean {

        // Load the latest persisted execution.
        // A missing execution requires no further notification work.
        val latest = store.get(execution.id) ?: return true

        // ----------------------------------------------------
        // Validate Execution State
        // ----------------------------------------------------

        // Ignore stale callbacks or workflows that are already
        // active, completed, or cancelled.
        if (
            latest.next != execution.next ||
            latest.dueAt != execution.dueAt ||
            latest.status in setOf("playing", "complete", "cancelled")
        ) {
            return true
        }

        // ----------------------------------------------------
        // Check Notification Availability
        // ----------------------------------------------------

        // Obtain the system notification manager.
        val manager = context.getSystemService(NotificationManager::class.java)

        // Ensure the channel for audio continuation exists.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Butler audio ready",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        // Do not offer playback when notifications are disabled
        // globally or for this channel.
        if (
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL).importance ==
            NotificationManager.IMPORTANCE_NONE
        ) {
            return false
        }

        // ----------------------------------------------------
        // Prepare Notification Actions
        // ----------------------------------------------------

        // Create a user-initiated action to resume playback manually.
        val listen = PendingIntent.getForegroundService(
            context,
            execution.id.hashCode(),
            ButlerAudioPlaybackService.resumeIntent(
                context,
                execution.id,
                manual = true,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
        )

        // Create an action to open the Butler conversation screen.
        val open = PendingIntent.getActivity(
            context,
            execution.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(
                    MainActivity.EXTRA_OPEN_CONVERSATION,
                    true,
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
        )

        // ----------------------------------------------------
        // Publish Listen Notification
        // ----------------------------------------------------

        // Attempt the notification operation without propagating
        // ordinary notification failures to the caller.
        return runCatching {

            // Check that the execution still matches the expected
            // checkpoint before publishing the Listen notification.
            store.transition(
                execution,
                execution.copy(status = "offered"),
            ) {

                // Publish a notification with manual playback access.
                manager.notify(
                    execution.id,
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle("Hello Butler")
                        .setContentText(
                            "Your Butler speech is ready. Tap Listen."
                        )
                        .setContentIntent(open)
                        .addAction(
                            android.R.drawable.ic_media_play,
                            "Listen",
                            listen,
                        )
                        .setAutoCancel(true)
                        .build(),
                )
            }

            // Preserve the existing success result even if the
            // transition declines because the state changed.
            true
        }.getOrDefault(false)
    }

    // --------------------------------------------------------
    // Entry Point - Dismiss Waiting Notification
    // --------------------------------------------------------

    /**
     * Removes the waiting notification for an execution.
     *
     * Allows playback coordination to clear the notification
     * without cancelling the execution itself.
     */
    fun dismiss(id: String) {

        // Cancel only the notification associated with this ID.
        context.getSystemService(NotificationManager::class.java)
            .cancel(id, NOTIFICATION_ID)
    }

    // --------------------------------------------------------
    // Internal Logic - Waiting Notification
    // --------------------------------------------------------

    /**
     * Displays a notification while an audio workflow is delayed.
     *
     * Keeps the user informed that playback will continue later
     * and provides an action to cancel the pending workflow.
     */
    private fun showWaiting(execution: AudioExecution) {

        // Obtain Android's notification manager.
        val manager = context.getSystemService(NotificationManager::class.java)

        // Create the low-importance notification channel if needed.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Butler audio ready",
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        // Avoid failing workflow scheduling if notification
        // publication encounters an exception.
        runCatching {
            manager.notify(
                execution.id,
                NOTIFICATION_ID,

                // Build an ongoing notification for delayed playback.
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Hello Butler")
                    .setContentText(
                        "Butler speech is scheduled to continue shortly."
                    )
                    .setOngoing(true)

                    // Allow the user to cancel the scheduled workflow.
                    .addAction(
                        android.R.drawable.ic_media_pause,
                        "Stop",
                        ButlerAudioPlaybackService.cancelIntent(
                            context,
                            execution.id,
                        ),
                    )
                    .build(),
            )
        }
    }

    // --------------------------------------------------------
    // Internal Logic - Alarm Identity
    // --------------------------------------------------------

    /**
     * Creates the PendingIntent identifying an execution's exact alarm.
     *
     * Uses a stable execution-specific URI so scheduling and
     * cancellation refer to the same Android alarm.
     */
    private fun alarm(id: String) = PendingIntent.getBroadcast(
        context,
        id.hashCode(),

        // Target the receiver responsible for resuming playback.
        Intent(context, AudioContinuationReceiver::class.java)
            .setData(
                Uri.parse("hellobutler://audio/${Uri.encode(id)}")
            )
            .putExtra("execution_id", id),

        PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
    )

    // --------------------------------------------------------
    // Constants & Utility Functions
    // --------------------------------------------------------

    companion object {

        // Identify the shared audio continuation notification channel.
        private const val CHANNEL = "butler_audio_ready"

        // Identify notifications belonging to this scheduler.
        private const val NOTIFICATION_ID = 4210

        /**
         * Generates a stable WorkManager identifier for an execution.
         *
         * Used as a common tag for cancelling every continuation
         * associated with the same audio workflow.
         */
        private fun workName(id: String) =
            "butler-audio-continuation-$id"
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

    /**
     * Handles an incoming alarm broadcast.
     *
     * Extracts the execution ID and requests playback continuation
     * without allowing service-launch failures to crash the receiver.
     */
    override fun onReceive(context: Context, intent: Intent) {

        // Ignore broadcasts that do not identify an execution.
        val id = intent.getStringExtra("execution_id") ?: return

        // Attempt playback continuation.
        // Background service restrictions can prevent launching.
        runCatching {
            ButlerAudioPlaybackService.resume(context, id)
        }
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
        if (
            execution.status in setOf(
                "complete",
                "cancelled",
                "playing",
                "preparing",
            )
        ) {
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
            container.audioWorkflowScheduler.cancel(id)

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
            container.audioWorkflowScheduler.cancel(id)

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
                    id,
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
