package com.hellobutler.app.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.MainActivity
import com.hellobutler.app.execution.audio.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.resume

/** Single player for manual responses, scheduled announcements, and volume preview. */
class ButlerAudioPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as ButlerApplication).container
    private var job: Job? = null
    private var eventWatcher: Job? = null
    // Execution ownership stays on the main dispatcher; resource ownership lives in the player.
    private val active = ActivePlayback()
    private var generation = 0
    private val resourcePlayer by lazy {
        AudioResourcePlayer(
            this, scope, getSystemService(AudioManager::class.java), container.audioWorkflowStore::select,
            currentVolume = { butlerVolume(container.soundVoice.state.value.volume) },
            canStart = ::canStartPlayback,
            onPlaybackStarted = ::onPlaybackStarted,
            onFocusLost = { finish(cancel = true) },
        )
    }
    private val preparer by lazy {
        AudioExecutionPreparer(
            container.database, container.dailyEventRepository, container.butlerRepository,
            container.audioWorkflowStore, container.audioWorkflowScheduler, container.soundVoice,
            showDeferred = { DeferredSpeechNotification.show(this, it) },
            dismissDeferred = { DeferredSpeechNotification.cancel(this, it) },
            reserveActiveId = { active.id = it },
        )
    }
    private val eventSpeech by lazy {
        EventSpeechPlayback(
            container.database.dailyEventDao(), container.dailyEventRepository, LocalTextToSpeech(this),
            getSystemService(ConnectivityManager::class.java), cacheDir,
            allows = { !active.automatic || container.soundVoice.state.value.allows(it.workflow) },
            playFile = { file, started -> playResource("<speech>", file.absolutePath, started) },
            showDeferred = { DeferredSpeechNotification.show(this, it) },
        )
    }

    /** Creates the playback channel and observes live gain and automatic-speech eligibility. */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Butler playback", NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            container.soundVoice.state.collect { settings ->
                resourcePlayer.volume(butlerVolume(settings.volume))
                val execution = active.id?.let(container.audioWorkflowStore::get)
                if (execution != null && active.automatic && !settings.allows(execution.workflow)) finish(cancel = true)
            }
        }
    }

    /** Routes control commands first, then admits and starts a playback request. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (handleControl(intent, startId)) return START_NOT_STICKY
        val request = decodePlayback(intent)
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val existing = request.resumeId?.let(container.audioWorkflowStore::get)
        if (acceptPlayback(request, existing, startId)) startPlayback(request, existing)
        return START_NOT_STICKY
    }

    /** Releases playback before cancelling the scope so stale jobs cannot retain service ownership. */
    override fun onDestroy() {
        releasePlayback(cancel = true)
        scope.cancel()
        super.onDestroy()
    }

    /** This started foreground service does not expose a bound-service API. */
    override fun onBind(intent: Intent?): IBinder? = null

    // Commands and request admission --------------------------------------------

    /** Handles stop and targeted cancellation without interpreting them as new playback. */
    private fun handleControl(intent: Intent?, startId: Int): Boolean {
        if (intent?.action == ACTION_CANCEL_WORKFLOW) {
            intent.getStringExtra(EXTRA_EXECUTION)?.let { id ->
                container.audioWorkflowScheduler.cancel(id)
                if (active.id == id) finish(cancel = true)
            }
            if (active.id == null) stopSelf(startId)
            return true
        }
        if (intent?.action == ACTION_CANCEL_EVENT) {
            if (intent.getStringExtra(EXTRA_EVENT_ID) == active.eventId) finish(cancel = true)
            if (active.id == null) stopSelf(startId)
            return true
        }
        if (intent?.action == ACTION_STOP) {
            finish(cancel = true)
            return true
        }
        if (intent?.action == ACTION_STOP_PREVIEW) {
            if (active.preview) finish(cancel = false)
            return true
        }
        return false
    }

    /** Captures Intent inputs once, retaining the existing defaults and valid request combinations. */
    private fun decodePlayback(intent: Intent?): PlaybackRequest? {
        if (intent == null) return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val resumeId = intent.getStringExtra(EXTRA_EXECUTION)
        val preview = intent.action == ACTION_PREVIEW
        if (!preview && requestId == null && eventId == null && resumeId == null) return null
        return PlaybackRequest(
            requestId, eventId, resumeId, preview,
            intent.getBooleanExtra(EXTRA_PRIVATE, false),
            intent.getBooleanExtra(EXTRA_AUTOMATIC, false),
            intent.getBooleanExtra("manual_resume", false),
            intent.getIntExtra(EXTRA_EVENT_VERSION, -1),
            intent.getLongExtra(EXTRA_EVENT_TRIGGER, -1),
        )
    }

    /** Preserves resume validation, duplicate suppression, and automatic deferral in their original order. */
    private fun acceptPlayback(request: PlaybackRequest, existing: AudioExecution?, startId: Int): Boolean {
        val requestId = request.requestId
        val eventId = request.eventId
        val isPreview = request.preview
        val requestedPrivate = request.privateRoute
        val requestedAutomatic = request.automatic
        val manualResume = request.manualResume
        if (request.resumeId != null && (existing == null || existing.status in setOf("complete", "cancelled") || existing.dueAt > System.currentTimeMillis())) {
            if (active.id == null) stopSelf(startId)
            return false
        }
        if (existing != null && !manualResume && existing.automatic && existing.requestId != null &&
            System.currentTimeMillis() - existing.createdAt > 600_000) {
            container.audioWorkflowScheduler.cancel(existing.id)
            if (active.id == null) stopSelf(startId)
            return false
        }
        if (existing != null && !manualResume && existing.automatic && !container.soundVoice.state.value.allows(existing.workflow)) {
            container.audioWorkflowScheduler.offer(existing)
            if (active.id == null) stopSelf(startId)
            return false
        }
        if (isPreview && active.id != null) return false
        if (eventId != null && eventId == active.eventId) return false
        if ((request.resumeId != null && active.id == request.resumeId) ||
            (requestId != null && active.requestId == requestId && active.privateRoute == requestedPrivate)) return false
        if (requestedAutomatic && active.id != null) {
            if (existing != null) {
                val deferred = existing.copy(dueAt = System.currentTimeMillis() + 15_000, status = "waiting")
                container.audioWorkflowStore.save(deferred)
                container.audioWorkflowScheduler.schedule(deferred)
            }
            else if (eventId != null) DailyEventExecutionWorker.enqueue(this, eventId, 15_000)
            return false
        }
        return true
    }

    // Execution orchestration ---------------------------------------------------

    /** Replaces the previous owner and enters the foreground before starting asynchronous work. */
    private fun startPlayback(request: PlaybackRequest, existing: AudioExecution?) {
        val requestId = request.requestId
        val eventId = request.eventId
        val resumeId = request.resumeId
        val isPreview = request.preview
        val requestedPrivate = request.privateRoute
        val requestedAutomatic = request.automatic
        val manualResume = request.manualResume
        releasePlayback(cancel = true)
        active.id = resumeId ?: if (isPreview) "preview" else UUID.randomUUID().toString()
        active.requestId = requestId ?: existing?.requestId
        active.eventId = eventId ?: existing?.eventId
        active.privateRoute = existing?.privateRoute ?: requestedPrivate
        active.automatic = if (manualResume) false else existing?.automatic ?: requestedAutomatic
        active.preview = isPreview
        ButlerPlayback.update(ButlerPlaybackState(active.requestId, active.privateRoute, PlaybackPhase.LOADING, active.eventId))
        try {
            startForegroundNow(notification("Preparing audio…"))
        } catch (error: RuntimeException) {
            existing?.let { container.audioWorkflowScheduler.offer(it) }
            if (eventId != null) DailyEventExecutionWorker.enqueue(this, eventId, 0)
            finish(cancel = false)
            return
        }
        if (existing != null) container.audioWorkflowStore.save(existing.copy(status = "playing"))
        job = launchPlayback(request, existing, ++generation)
    }

    /** Keeps cancellation and finalization tied to the generation that launched this job. */
    private fun launchPlayback(request: PlaybackRequest, existing: AudioExecution?, token: Int): Job {
        return scope.launch {
            try {
                executePlayback(request, existing)
            } catch (cancelled: CancellationException) {
                if (generation == token) {
                    active.id?.let { id ->
                        container.audioWorkflowStore.get(id)?.let { saved ->
                            if (saved.status !in setOf("waiting", "complete", "cancelled")) container.audioWorkflowScheduler.cancel(id)
                        }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Butler audio workflow failed", error)
                if (generation == token) active.id?.let(container.audioWorkflowScheduler::cancel)
            } finally {
                // A cancelled download must not finish a replacement request.
                if (generation == token) finish(cancel = false)
            }
        }
    }

    /** Prepares or resumes an execution, attaches its watcher, and runs the unchanged sequence engine. */
    private suspend fun CoroutineScope.executePlayback(request: PlaybackRequest, existing: AudioExecution?) {
        val requestId = request.requestId
        val eventId = request.eventId
        val isPreview = request.preview
        val requestedPrivate = request.privateRoute
        val requestedAutomatic = request.automatic
        val manualResume = request.manualResume
        if (isPreview) {
            playResource("volume_preview", "")
        } else {
            val execution = if (existing != null) {
                if (manualResume) existing.copy(automatic = false) else existing
            } else preparer.prepare(
                requestId, eventId, requestedAutomatic, requestedPrivate, active.id!!,
                request.expectedVersion, request.expectedTrigger,
            )
            ensureActive()
            if (execution != null) {
                active.id = execution.id
                active.requestId = execution.requestId
                if (execution.automatic && !container.soundVoice.state.value.allows(execution.workflow)) {
                    container.audioWorkflowScheduler.cancel(execution.id)
                    return
                }
                if (!validEvent(execution)) {
                    container.audioWorkflowScheduler.cancel(execution.id)
                    return
                }
                container.audioWorkflowScheduler.dismiss(execution.id)
                execution.eventId?.let { id ->
                    eventWatcher = scope.launch {
                        container.database.dailyEventDao().observe(id).collect { event ->
                            if (!event.matchesExecution(execution)) finish(cancel = true)
                        }
                    }
                }
                AudioSequence(
                    container.audioWorkflowStore::save,
                    play = { resource ->
                        ensureActive()
                        if (active.automatic && !container.soundVoice.state.value.allows(execution.workflow)) throw CancellationException("Automatic speech disabled")
                        if (!validEvent(execution)) throw CancellationException("Event changed")
                        if (resource == "<speech>" && execution.eventId != null) eventSpeech.speak(execution)
                        else playResource(resource, execution.speechPath)
                    },
                    schedule = container.audioWorkflowScheduler::schedule,
                ).run(execution)
            }
        }
    }

    /** Checks the canonical event against the execution snapshot before continuing playback. */
    private suspend fun validEvent(execution: AudioExecution): Boolean {
        val id = execution.eventId ?: return true
        val event = container.database.dailyEventDao().get(id) ?: return false
        return event.matchesExecution(execution)
    }

    /** Routes every bundled or generated resource through the same player. */
    private suspend fun playResource(resource: String, speechPath: String, onStarted: () -> Unit = {}): Boolean =
        resourcePlayer.play(resource, speechPath, active.privateRoute, onStarted)

    /** Rechecks event and automatic preferences immediately before the player starts. */
    private suspend fun canStartPlayback(): Boolean {
        val execution = active.id?.let(container.audioWorkflowStore::get)
        if (active.eventId != null && (execution == null || execution.status == "cancelled" || !validEvent(execution))) return false
        return execution == null || !active.automatic || container.soundVoice.state.value.allows(execution.workflow)
    }

    /** Publishes speaking state only after the current player has started successfully. */
    private fun onPlaybackStarted() {
        SpeechPlaybackState.setSpeaking(!active.preview && active.requestId == null)
        ButlerPlayback.update(ButlerPlaybackState(active.requestId, active.privateRoute, PlaybackPhase.SPEAKING, active.eventId))
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification(if (active.preview) "Volume preview" else "Butler is speaking"),
        )
    }

    // Foreground presentation and ordered cleanup -------------------------------

    /** Builds the foreground playback notification with the existing Stop and Open actions. */
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hello Butler").setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent(this))
            .addAction(android.R.drawable.ic_menu_view, "Open in App", open).build()
    }
    /** Starts foreground playback with the platform-required media service type. */
    private fun startForegroundNow(value: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, value)
    }
    /** Invalidates ownership before cancelling jobs and releasing synthesis, player, and focus. */
    private fun releasePlayback(cancel: Boolean) {
        generation++
        val previousId = active.id
        active.id = null // Clear ownership before cancellation callbacks execute.
        if (cancel && previousId != null) container.audioWorkflowScheduler.cancel(previousId)
        eventWatcher?.cancel(); eventWatcher = null
        eventSpeech.stop()
        job?.cancel(); job = null
        resourcePlayer.release()
        active.requestId = null
        active.eventId = null
        SpeechPlaybackState.setSpeaking(false)
        ButlerPlayback.update(ButlerPlaybackState())
    }
    /** Releases execution resources before removing the foreground notification and stopping the service. */
    private fun finish(cancel: Boolean) {
        releasePlayback(cancel)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Immutable decoded request, separate from the service's changing execution ownership. */
    private data class PlaybackRequest(
        val requestId: String?,
        val eventId: String?,
        val resumeId: String?,
        val preview: Boolean,
        val privateRoute: Boolean,
        val automatic: Boolean,
        val manualResume: Boolean,
        val expectedVersion: Int,
        val expectedTrigger: Long,
    )

    /** Main-dispatcher execution identity; jobs and generation retain their service lifetime. */
    private class ActivePlayback(
        var id: String? = null,
        var requestId: String? = null,
        var eventId: String? = null,
        var privateRoute: Boolean = false,
        var automatic: Boolean = false,
        var preview: Boolean = false,
    )

    companion object {
        private const val CHANNEL = "butler_response_playback"
        private const val NOTIFICATION_ID = 4202
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PRIVATE = "private"
        private const val EXTRA_AUTOMATIC = "automatic"
        private const val EXTRA_EXECUTION = "execution_id"
        private const val ACTION_CANCEL_WORKFLOW = "com.hellobutler.app.CANCEL_AUDIO_WORKFLOW"
        private const val ACTION_CANCEL_EVENT = "com.hellobutler.app.CANCEL_EVENT_AUDIO"
        private const val ACTION_STOP = "com.hellobutler.app.STOP_BUTLER_AUDIO"
        private const val ACTION_PREVIEW = "com.hellobutler.app.PREVIEW_VOLUME"
        private const val ACTION_STOP_PREVIEW = "com.hellobutler.app.STOP_VOLUME_PREVIEW"
        private const val TAG = "ButlerAudio"
        /** Creates the stable response-and-route action used by widgets and notifications. */
        fun intent(context: Context, requestId: String, private: Boolean): PendingIntent = PendingIntent.getForegroundService(
            context, requestId.hashCode() * 2 + if (private) 1 else 0,
            Intent(context, ButlerAudioPlaybackService::class.java).setData(Uri.parse("hellobutler://response/${Uri.encode(requestId)}/$private"))
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        /** Identifies a targeted workflow cancellation without starting new playback. */
        fun cancelIntent(context: Context, id: String): PendingIntent = PendingIntent.getService(context, id.hashCode(),
            Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_CANCEL_WORKFLOW)
                .setData(Uri.parse("hellobutler://audio/cancel/${Uri.encode(id)}")).putExtra(EXTRA_EXECUTION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        /** Creates the shared action that immediately stops the current playback owner. */
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(context, 0,
            Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        /** Requests cancellation of whichever execution currently owns playback. */
        fun stop(context: Context) { context.startService(Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP)) }
        /** Starts response playback through the single foreground-service owner. */
        fun play(context: Context, requestId: String, private: Boolean, automatic: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private).putExtra(EXTRA_AUTOMATIC, automatic))
        }
        /** Starts event playback with optional alarm version and trigger validation. */
        fun playEvent(context: Context, eventId: String, automatic: Boolean = false, expectedVersion: Int? = null, expectedTrigger: Long? = null) {
            ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_AUTOMATIC, automatic)
                .putExtra(EXTRA_EVENT_VERSION, expectedVersion ?: -1).putExtra(EXTRA_EVENT_TRIGGER, expectedTrigger ?: -1))
        }
        /** Cancels persisted event workflows and any active service playback for the event. */
        fun cancelEvent(context: Context, eventId: String) {
            val container = (context.applicationContext as? ButlerApplication)?.container
            container?.audioWorkflowStore?.all()?.filter { it.eventId == eventId }
                ?.forEach { container.audioWorkflowScheduler.cancel(it.id) }
            runCatching { context.startService(Intent(context, ButlerAudioPlaybackService::class.java)
                .setAction(ACTION_CANCEL_EVENT).putExtra(EXTRA_EVENT_ID, eventId)) }
        }
        /** Preserves continuation identity and explicitly distinguishes manual Listen from automatic resume. */
        fun resumeIntent(context: Context, id: String, manual: Boolean = false) = Intent(context, ButlerAudioPlaybackService::class.java)
            .setData(Uri.parse("hellobutler://audio/resume/${Uri.encode(id)}")).putExtra(EXTRA_EXECUTION, id)
            .putExtra(EXTRA_AUTOMATIC, !manual).putExtra("manual_resume", manual)
        /** Requests foreground continuation of an already journaled execution. */
        fun resume(context: Context, id: String) { ContextCompat.startForegroundService(context, resumeIntent(context, id)) }
        /** Requests the prerecorded volume sample through the same playback owner. */
        fun preview(context: Context) { ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_PREVIEW)) }
        /** Stops a preview without interrupting ordinary speech. */
        fun stopPreview(context: Context) { context.startService(Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP_PREVIEW)) }
    }
}
