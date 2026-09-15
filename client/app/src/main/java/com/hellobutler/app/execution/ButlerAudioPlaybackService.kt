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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hellobutler.app.ButlerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ButlerAudioPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var privateRoute = false
    private var activeRequestId: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Butler playback", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return START_NOT_STICKY
        activeRequestId = requestId
        privateRoute = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        Log.i(TAG, "Playback requested request_id=$requestId route=${if (privateRoute) "private" else "speaker"}")
        startForegroundNow(notification("Preparing audio…"))
        job?.cancel()
        player?.release()
        job = scope.launch {
            val file = runCatching { (application as ButlerApplication).container.butlerRepository.ensureAudio(requestId) }
                .onFailure { Log.e(TAG, "Playback audio unavailable request_id=$requestId error_type=${it.javaClass.simpleName}", it) }
                .getOrNull()
            if (file == null) { finish(); return@launch }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(if (privateRoute) "Private listening" else "Playing aloud"))
            Log.i(TAG, "Playback starting request_id=$requestId bytes=${file.length()}")
            play(file.absolutePath)
        }
        return START_NOT_STICKY
    }

    private fun play(path: String) {
        val audio = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(if (privateRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0) finish() }.build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { finish(); return }
        focusRequest = focus
        audio.mode = if (privateRoute) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = !privateRoute
        player = MediaPlayer().apply {
            setAudioAttributes(attributes); setDataSource(path)
            setOnCompletionListener { Log.i(TAG, "Playback completed request_id=$activeRequestId"); finish() }
            setOnErrorListener { _, what, extra -> Log.w(TAG, "Playback failed request_id=$activeRequestId what=$what extra=$extra"); finish(); true }
            prepare(); start()
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, ButlerAudioPlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Hello Butler").setContentText(text).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop).build()
    }

    private fun startForegroundNow(value: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, value)
    }

    private fun finish() {
        job?.cancel(); job = null
        runCatching { player?.stop() }; player?.release(); player = null
        val audio = getSystemService(AudioManager::class.java)
        focusRequest?.let(audio::abandonAudioFocusRequest); focusRequest = null
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        activeRequestId = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        player?.release()
        val audio = getSystemService(AudioManager::class.java)
        focusRequest?.let(audio::abandonAudioFocusRequest)
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "butler_response_playback"
        private const val NOTIFICATION_ID = 4202
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PRIVATE = "private"
        private const val ACTION_STOP = "com.hellobutler.app.STOP_BUTLER_AUDIO"
        private const val TAG = "ButlerAudio"

        fun intent(context: Context, requestId: String, private: Boolean): PendingIntent =
            PendingIntent.getForegroundService(
                context, requestId.hashCode() * 2 + if (private) 1 else 0,
                Intent(context, ButlerAudioPlaybackService::class.java).putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun play(context: Context, requestId: String, private: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, ButlerAudioPlaybackService::class.java).putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_PRIVATE, private))
        }
    }
}
