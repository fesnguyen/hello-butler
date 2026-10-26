
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

/**
 * Owns the service's sole MediaPlayer and audio-focus request.
 *
 * The caller owns the execution lifecycle, cancellation, and workflow state.
 */
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

    // ------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------

    /**
     * Plays a bundled resource or prepared speech file.
     *
     * Returns false for missing resources, playback failures, or timeouts.
     * Cancellation remains owned by the calling coroutine.
     */
    suspend fun play(
        resource: String,
        speechPath: String,
        privateRoute: Boolean,
        onStarted: () -> Unit = {},
    ): Boolean {
        val raw = if (resource == "<speech>") {
            null
        } else {
            AudioResources.resolve(resource, selectResource)
        }

        if (resource != "<speech>" && raw == null) {
            Log.w(TAG, "Skipping missing optional audio: $resource")
            return false
        }

        if (resource == "<speech>" && !File(speechPath).isFile) {
            return false
        }

        val attributes = createAudioAttributes(privateRoute)
        acquireFocus(attributes, privateRoute)

        return withTimeoutOrNull(120_000) {
            playMedia(raw, speechPath, attributes, resource, onStarted)
        } ?: false
    }

    /**
     * Applies the current Butler gain to the active player.
     *
     * Does not modify Android's system stream volume.
     */
    fun volume(value: Float) {
        player?.setVolume(value, value)
    }

    /**
     * Releases the active player, audio focus, and routing settings.
     *
     * The caller must invalidate and cancel its execution first.
     */
    fun release() {
        val media = player
        player = null

        runCatching { media?.release() }

        focusRequest?.let { focus ->
            audio.abandonAudioFocusRequest(focus)

            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false

            audio.mode = AudioManager.MODE_NORMAL
        }

        focusRequest = null
    }

    // ------------------------------------------------------------
    // MediaPlayer Lifecycle
    // ------------------------------------------------------------

    /**
     * Creates and prepares a MediaPlayer for one resource.
     *
     * Callback completion and cancellation release only their own
     * player, preventing stale callbacks from clearing a replacement.
     */
    private suspend fun playMedia(
        raw: Int?,
        speechPath: String,
        attributes: AudioAttributes,
        resource: String,
        onStarted: () -> Unit,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val media = MediaPlayer()
        player = media

        // Release this specific player without affecting a replacement.
        fun complete(success: Boolean) {
            if (player === media) player = null

            runCatching { media.release() }

            if (continuation.isActive) {
                continuation.resume(success)
            }
        }

        continuation.invokeOnCancellation {
            if (player === media) player = null
            runCatching { media.release() }
        }

        try {
            media.setAudioAttributes(attributes)

            val gain = currentVolume()
            media.setVolume(gain, gain)

            media.setWakeMode(
                context,
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
            )

            media.setOnPreparedListener { prepared ->
                scope.launch {
                    // An obsolete player must never start playback.
                    if (player !== prepared || !continuation.isActive) {
                        return@launch
                    }

                    if (!canStart()) {
                        complete(false)
                        return@launch
                    }

                    // Recheck after suspension in canStart().
                    if (player !== prepared || !continuation.isActive) {
                        return@launch
                    }

                    try {
                        prepared.start()
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Audio start failed", error)
                        complete(false)
                        return@launch
                    }

                    onStarted()
                    onPlaybackStarted()
                }
            }

            media.setOnCompletionListener {
                complete(true)
            }

            media.setOnErrorListener { _, _, _ ->
                complete(false)
                true
            }

            setDataSource(media, raw, speechPath)
            media.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Audio resource failed: $resource", error)
            complete(false)
        }
    }

    /**
     * Configures the source of a bundled resource or prepared speech.
     *
     * The raw-resource descriptor is closed after MediaPlayer
     * receives its file descriptor and offsets.
     */
    private fun setDataSource(
        media: MediaPlayer,
        raw: Int?,
        speechPath: String,
    ) {
        if (raw == null) {
            media.setDataSource(speechPath)
            return
        }

        val descriptor = context.resources.openRawResourceFd(raw)
            ?: throw java.io.IOException("Missing raw audio descriptor")

        descriptor.use {
            media.setDataSource(
                it.fileDescriptor,
                it.startOffset,
                it.length,
            )
        }
    }

    // ------------------------------------------------------------
    // Audio Focus and Routing
    // ------------------------------------------------------------

    /**
     * Creates audio attributes for private or normal playback.
     *
     * Private playback uses voice-communication usage while
     * normal playback uses AI Assistant usage.
     */
    private fun createAudioAttributes(
        privateRoute: Boolean,
    ): AudioAttributes {
        val usage = if (privateRoute) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_ASSISTANT
        }

        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(usage)
            .build()
    }

    /**
     * Acquires transient audio focus and configures the output route.
     *
     * Reuses an existing focus request until release() is called.
     */
    private fun acquireFocus(
        attributes: AudioAttributes,
        privateRoute: Boolean,
    ) {
        if (focusRequest != null) return

        lateinit var focus: AudioFocusRequest

        focus = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener {
                if (it < 0 && focusRequest === focus) {
                    onFocusLost()
                }
            }
            .build()

        if (
            audio.requestAudioFocus(focus) !=
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            throw CancellationException("Audio focus denied")
        }

        focusRequest = focus

        audio.mode = if (privateRoute) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }

        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = !privateRoute
    }

    companion object {
        private const val TAG = "ButlerAudio"
    }
}
