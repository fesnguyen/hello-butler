package com.hellobutler.app.execution.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Owns the service's sole player and focus request, without owning an execution lifecycle. */
internal class AudioResourcePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audio: AudioManager,
    private val selectResource: (String) -> String,
    private val currentVolume: () -> Float,
    private val canStart: suspend () -> Boolean,
    private val onPlaybackStarted: () -> Unit,
    private val onFocusLost: () -> Unit,
) {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Plays one resource; stale prepared and cancellation callbacks cannot replace the current player. */
    suspend fun play(resource: String, speechPath: String, privateRoute: Boolean, onStarted: () -> Unit = {}): Boolean {
        val raw = if (resource == "<speech>") null else AudioResources.resolve(resource, selectResource)
        if (resource != "<speech>" && raw == null) { Log.w(TAG, "Skipping missing optional audio: $resource"); return false }
        if (resource == "<speech>" && !File(speechPath).isFile) return false
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(if (privateRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build()
        acquireFocus(attributes, privateRoute)
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val media = MediaPlayer()
                player = media
                /** Releases this resource without clearing a replacement player and resumes only a live caller. */
                fun complete(success: Boolean) {
                    if (player === media) player = null
                    runCatching { media.release() }
                    if (continuation.isActive) continuation.resume(success)
                }
                continuation.invokeOnCancellation { if (player === media) player = null; runCatching { media.release() } }
                try {
                    media.setAudioAttributes(attributes)
                    val volume = currentVolume()
                    media.setVolume(volume, volume)
                    media.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    media.setOnPreparedListener { prepared ->
                        scope.launch {
                            if (player !== prepared || !continuation.isActive) return@launch
                            if (!canStart()) {
                                complete(false)
                                return@launch
                            }
                            if (player !== prepared || !continuation.isActive) return@launch
                            try { prepared.start() }
                            catch (error: RuntimeException) {
                                Log.w(TAG, "Audio start failed", error); complete(false); return@launch
                            }
                            onStarted()
                            onPlaybackStarted()
                        }
                    }
                    media.setOnCompletionListener { complete(true) }
                    media.setOnErrorListener { _, _, _ -> complete(false); true }
                    if (raw != null) (context.resources.openRawResourceFd(raw) ?: throw java.io.IOException("Missing raw audio descriptor")).use { descriptor ->
                        media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    } else media.setDataSource(speechPath)
                    media.prepareAsync()
                } catch (error: Exception) { Log.w(TAG, "Audio resource failed: $resource", error); complete(false) }
            }
        } ?: false
    }

    /** Keeps one transient focus request across resources until the service releases the execution. */
    private fun acquireFocus(attributes: AudioAttributes, privateRoute: Boolean) {
        if (focusRequest == null) {
            lateinit var focus: AudioFocusRequest
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { if (it < 0 && focusRequest === focus) onFocusLost() }.build()
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw CancellationException("Audio focus denied")
            focusRequest = focus
            audio.mode = if (privateRoute) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !privateRoute
        }
    }

    /** Applies live Butler gain without modifying Android stream volume. */
    fun volume(value: Float) { player?.setVolume(value, value) }

    /** Releases player and routing after the owner has invalidated and cancelled its execution. */
    fun release() {
        val media = player
        player = null
        runCatching { media?.release() }
        focusRequest?.let {
            audio.abandonAudioFocusRequest(it)
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
        focusRequest = null
    }

    companion object {
        private const val TAG = "ButlerAudio"
    }
}
