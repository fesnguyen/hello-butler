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
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var privateRoute = false
    private var activeId: String? = null
    private var activeRequestId: String? = null
    private var automatic = false
    private var preview = false
    private var generation = 0
    private var activeEventId: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Butler playback", NotificationManager.IMPORTANCE_LOW))
        scope.launch { container.soundVoice.state.collect { settings ->
            val volume = butlerVolume(settings.volume)
            player?.setVolume(volume, volume)
            val execution = activeId?.let(container.audioWorkflowStore::get)
            if (execution != null && automatic && !settings.allows(execution.workflow)) finish(cancel = true)
        } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_WORKFLOW) {
            intent.getStringExtra(EXTRA_EXECUTION)?.let { id ->
                container.audioWorkflowScheduler.cancel(id)
                if (activeId == id) finish(cancel = true)
            }
            if (activeId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { finish(cancel = true); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP_PREVIEW) { if (preview) finish(cancel = false); return START_NOT_STICKY }
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID)
        val resumeId = intent?.getStringExtra(EXTRA_EXECUTION)
        val isPreview = intent?.action == ACTION_PREVIEW
        if (intent == null || (!isPreview && requestId == null && eventId == null && resumeId == null)) { stopSelf(startId); return START_NOT_STICKY }
        val requestedPrivate = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val requestedAutomatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
        val manualResume = intent.getBooleanExtra("manual_resume", false)
        val existing = resumeId?.let(container.audioWorkflowStore::get)
        if (resumeId != null && (existing == null || existing.status in setOf("complete", "cancelled") || existing.dueAt > System.currentTimeMillis())) {
            if (activeId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (existing != null && !manualResume && existing.automatic && existing.requestId != null &&
            System.currentTimeMillis() - existing.createdAt > 600_000) {
            container.audioWorkflowScheduler.cancel(existing.id)
            if (activeId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (existing != null && !manualResume && existing.automatic && !container.soundVoice.state.value.allows(existing.workflow)) {
            container.audioWorkflowScheduler.offer(existing)
            if (activeId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isPreview && activeId != null) return START_NOT_STICKY
        if (eventId != null && eventId == activeEventId) return START_NOT_STICKY
        if ((resumeId != null && activeId == resumeId) ||
            (requestId != null && activeRequestId == requestId && privateRoute == requestedPrivate)) return START_NOT_STICKY
        if (requestedAutomatic && activeId != null) {
            if (existing != null) {
                val deferred = existing.copy(dueAt = System.currentTimeMillis() + 15_000, status = "waiting")
                container.audioWorkflowStore.save(deferred)
                container.audioWorkflowScheduler.schedule(deferred)
            }
            else if (eventId != null) DailyEventExecutionWorker.enqueue(this, eventId, 15_000)
            return START_NOT_STICKY
        }
        releasePlayback(cancel = true)
        activeId = resumeId ?: if (isPreview) "preview" else UUID.randomUUID().toString()
        activeRequestId = requestId ?: existing?.requestId
        activeEventId = eventId ?: existing?.eventId
        privateRoute = existing?.privateRoute ?: requestedPrivate
        automatic = if (manualResume) false else existing?.automatic ?: requestedAutomatic
        preview = isPreview
        ButlerPlayback.update(ButlerPlaybackState(activeRequestId, privateRoute, PlaybackPhase.LOADING))
        try { startForegroundNow(notification("Preparing audio…")) }
        catch (error: RuntimeException) {
            existing?.let { container.audioWorkflowScheduler.offer(it) }
            if (eventId != null) DailyEventExecutionWorker.enqueue(this, eventId, 0)
            finish(cancel = false)
            return START_NOT_STICKY
        }
        if (existing != null) container.audioWorkflowStore.save(existing.copy(status = "playing"))
        val token = ++generation
        job = scope.launch {
            try {
                if (isPreview) {
                    playResource("volume_preview", "")
                } else {
                    val execution = if (existing != null) {
                        if (manualResume) existing.copy(automatic = false) else existing
                    } else prepare(requestId, eventId, requestedAutomatic, requestedPrivate)
                    ensureActive()
                    if (execution != null) {
                        activeId = execution.id
                        activeRequestId = execution.requestId
                        if (execution.automatic && !container.soundVoice.state.value.allows(execution.workflow)) {
                            container.audioWorkflowScheduler.cancel(execution.id)
                            return@launch
                        }
                        if (!validEvent(execution)) { container.audioWorkflowScheduler.cancel(execution.id); return@launch }
                        container.audioWorkflowScheduler.dismiss(execution.id)
                        AudioSequence(container.audioWorkflowStore::save,
                            play = { resource ->
                                ensureActive()
                                if (automatic && !container.soundVoice.state.value.allows(execution.workflow)) throw CancellationException("Automatic speech disabled")
                                if (!validEvent(execution)) throw CancellationException("Event changed")
                                playResource(resource, execution.speechPath)
                            }, schedule = container.audioWorkflowScheduler::schedule).run(execution)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == token) activeId?.let { id -> container.audioWorkflowStore.get(id)?.let { saved ->
                    if (saved.status !in setOf("waiting", "complete", "cancelled")) container.audioWorkflowScheduler.cancel(id)
                } }
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Butler audio workflow failed", error)
                if (generation == token) activeId?.let(container.audioWorkflowScheduler::cancel)
            } finally {
                // A cancelled download must not finish a replacement request.
                if (generation == token) finish(cancel = false)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun prepare(requestId: String?, eventId: String?, auto: Boolean, private: Boolean): AudioExecution? {
        if (eventId != null) {
            val dao = container.database.dailyEventDao()
            val event = dao.get(eventId) ?: return null
            if (!event.isDueForSpeech()) return null
            val workflow = AudioWorkflows.forEvent(event.eventType)
            if (auto && !container.soundVoice.state.value.allows(workflow)) {
                DeferredSpeechNotification.show(this, event)
                return null
            }
            val id = "event:${event.id}:${event.version}"
            if (container.audioWorkflowStore.get(id) != null) return null
            val file = withContext(Dispatchers.IO) { withTimeoutOrNull(120_000) {
                if (workflow == AudioWorkflow.REMINDER) container.dailyEventRepository.prepareReminderSpeech(eventId, event.version)
                var audio = container.dailyEventRepository.speechAudio(eventId, event.version)
                while (audio == null) { delay(2_000); audio = container.dailyEventRepository.speechAudio(eventId, event.version) }
                audio
            } } ?: run { DeferredSpeechNotification.show(this, event); return null }
            currentCoroutineContext().ensureActive()
            val durable = withContext(Dispatchers.IO) {
                val directory = File(filesDir, "workflow_audio").apply { mkdirs() }
                file.copyTo(File(directory, "${event.id}-${event.version}.ogg"), overwrite = true)
            }
            if (dao.claimPlayback(event.id, Instant.now().toString(), event.eventDate, event.startTime.orEmpty(), event.content.orEmpty(), event.version) != 1) return null
            DeferredSpeechNotification.cancel(this, eventId)
            return AudioExecution(id, workflow, durable.absolutePath, eventId, event.version, automatic = auto).also(container.audioWorkflowStore::save)
        }
        if (requestId == null) return null
        if (!auto) {
            val autoId = "response:$requestId"
            val prior = container.audioWorkflowStore.get(autoId)
            if (prior != null) container.audioWorkflowScheduler.cancel(autoId)
            else container.audioWorkflowStore.save(AudioExecution(autoId, AudioWorkflow.BUTLER_RESPONSE, "", requestId = requestId, status = "cancelled"))
        }
        val id = if (auto) "response:$requestId" else activeId!!
        if (auto) {
            if (!container.soundVoice.state.value.responses || container.audioWorkflowStore.get(id) != null) return null
            val request = container.database.butlerConversationDao().request(requestId) ?: return null
            if (runCatching { Instant.parse(request.createdAt).isBefore(Instant.now().minusSeconds(600)) }.getOrDefault(true)) return null
            // Reserve before downloading so FCM, reconciliation, and the audio worker cannot race to announce twice.
            activeId = id
            container.audioWorkflowStore.save(AudioExecution(id, AudioWorkflow.BUTLER_RESPONSE, "", requestId = requestId, status = "preparing"))
        }
        val file = withContext(Dispatchers.IO) { withTimeoutOrNull(120_000) {
            val repository = container.butlerRepository
            var audio = repository.ensureAudio(requestId)
            while (audio == null && !repository.audioUnavailable(requestId)) { delay(2_000); audio = repository.ensureAudio(requestId) }
            audio
        } } ?: run {
            if (auto) container.audioWorkflowScheduler.cancel(id)
            return null
        }
        currentCoroutineContext().ensureActive()
        return AudioExecution(id, AudioWorkflow.BUTLER_RESPONSE, file.absolutePath, requestId = requestId,
            automatic = auto, privateRoute = private).also(container.audioWorkflowStore::save)
    }

    private suspend fun validEvent(execution: AudioExecution): Boolean {
        val id = execution.eventId ?: return true
        val event = container.database.dailyEventDao().get(id) ?: return false
        return event.status == "planned" && event.version == execution.eventVersion &&
            event.eventDate == java.time.LocalDate.now().toString()
    }

    private suspend fun playResource(resource: String, speechPath: String): Boolean {
        val raw = if (resource == "<speech>") null else AudioResources.resolve(resource, container.audioWorkflowStore::select)
        if (resource != "<speech>" && raw == null) { Log.w(TAG, "Skipping missing optional audio: $resource"); return false }
        if (resource == "<speech>" && !File(speechPath).isFile) return false
        val audio = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(if (privateRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build()
        if (focusRequest == null) {
            lateinit var focus: AudioFocusRequest
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { if (it < 0 && focusRequest === focus) finish(cancel = true) }.build()
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw CancellationException("Audio focus denied")
            focusRequest = focus
            audio.mode = if (privateRoute) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !privateRoute
        }
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val media = MediaPlayer()
                player = media
                fun complete(success: Boolean) {
                    if (player === media) player = null
                    runCatching { media.release() }
                    if (continuation.isActive) continuation.resume(success)
                }
                continuation.invokeOnCancellation { if (player === media) player = null; runCatching { media.release() } }
                try {
                    media.setAudioAttributes(attributes)
                    val volume = butlerVolume(container.soundVoice.state.value.volume)
                    media.setVolume(volume, volume)
                    media.setWakeMode(this@ButlerAudioPlaybackService, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    media.setOnPreparedListener {
                        if (player !== it || !continuation.isActive) return@setOnPreparedListener
                        it.start()
                        SpeechPlaybackState.setSpeaking(!preview && activeRequestId == null)
                        ButlerPlayback.update(ButlerPlaybackState(activeRequestId, privateRoute, PlaybackPhase.SPEAKING))
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(if (preview) "Volume preview" else "Butler is speaking"))
                    }
                    media.setOnCompletionListener { complete(true) }
                    media.setOnErrorListener { _, _, _ -> complete(false); true }
                    if (raw != null) (resources.openRawResourceFd(raw) ?: throw java.io.IOException("Missing raw audio descriptor")).use { descriptor ->
                        media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    } else media.setDataSource(speechPath)
                    media.prepareAsync()
                } catch (error: Exception) { Log.w(TAG, "Audio resource failed: $resource", error); complete(false) }
            }
        } ?: false
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hello Butler").setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent(this))
            .addAction(android.R.drawable.ic_menu_view, "Open in App", open).build()
    }
    private fun startForegroundNow(value: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, value)
    }
    private fun releasePlayback(cancel: Boolean) {
        generation++
        val previousId = activeId
        activeId = null // Clear ownership before cancellation callbacks execute.
        if (cancel && previousId != null) container.audioWorkflowScheduler.cancel(previousId)
        job?.cancel(); job = null
        val media = player; player = null
        runCatching { media?.release() }
        val audio = getSystemService(AudioManager::class.java)
        focusRequest?.let {
            audio.abandonAudioFocusRequest(it)
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
        focusRequest = null
        activeRequestId = null
        activeEventId = null
        SpeechPlaybackState.setSpeaking(false)
        ButlerPlayback.update(ButlerPlaybackState())
    }
    private fun finish(cancel: Boolean) { releasePlayback(cancel); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { releasePlayback(cancel = true); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "butler_response_playback"
        private const val NOTIFICATION_ID = 4202
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PRIVATE = "private"
        private const val EXTRA_AUTOMATIC = "automatic"
        private const val EXTRA_EXECUTION = "execution_id"
        private const val ACTION_CANCEL_WORKFLOW = "com.hellobutler.app.CANCEL_AUDIO_WORKFLOW"
        private const val ACTION_STOP = "com.hellobutler.app.STOP_BUTLER_AUDIO"
        private const val ACTION_PREVIEW = "com.hellobutler.app.PREVIEW_VOLUME"
        private const val ACTION_STOP_PREVIEW = "com.hellobutler.app.STOP_VOLUME_PREVIEW"
        private const val TAG = "ButlerAudio"
        fun intent(context: Context, requestId: String, private: Boolean): PendingIntent = PendingIntent.getForegroundService(
            context, requestId.hashCode() * 2 + if (private) 1 else 0,
            Intent(context, ButlerAudioPlaybackService::class.java).setData(Uri.parse("hellobutler://response/${Uri.encode(requestId)}/$private"))
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun cancelIntent(context: Context, id: String): PendingIntent = PendingIntent.getService(context, id.hashCode(),
            Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_CANCEL_WORKFLOW)
                .setData(Uri.parse("hellobutler://audio/cancel/${Uri.encode(id)}")).putExtra(EXTRA_EXECUTION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(context, 0,
            Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun stop(context: Context) { context.startService(Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP)) }
        fun play(context: Context, requestId: String, private: Boolean, automatic: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private).putExtra(EXTRA_AUTOMATIC, automatic))
        }
        fun playEvent(context: Context, eventId: String, automatic: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_AUTOMATIC, automatic))
        }
        fun resumeIntent(context: Context, id: String, manual: Boolean = false) = Intent(context, ButlerAudioPlaybackService::class.java)
            .setData(Uri.parse("hellobutler://audio/resume/${Uri.encode(id)}")).putExtra(EXTRA_EXECUTION, id)
            .putExtra(EXTRA_AUTOMATIC, !manual).putExtra("manual_resume", manual)
        fun resume(context: Context, id: String) { ContextCompat.startForegroundService(context, resumeIntent(context, id)) }
        fun preview(context: Context) { ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_PREVIEW)) }
        fun stopPreview(context: Context) { context.startService(Intent(context, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP_PREVIEW)) }
    }
}
