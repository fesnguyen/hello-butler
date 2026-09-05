package com.hellobutler.app.execution

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class LocalTextToSpeech(private val context: Context) {
    suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        val completed = AtomicBoolean(false)
        var engine: TextToSpeech? = null
        var ownsFocus = false

        fun finish(success: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            engine?.shutdown()
            if (ownsFocus) audio.abandonAudioFocusRequest(focus)
            if (continuation.isActive) continuation.resume(success)
        }

        continuation.invokeOnCancellation { finish(false) }
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                finish(false)
                return@TextToSpeech
            }
            val language = tts.setLanguage(Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                finish(false)
                return@TextToSpeech
            }
            ownsFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!ownsFocus) {
                finish(false)
                return@TextToSpeech
            }
            val utteranceId = "morning-brief-${UUID.randomUUID()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) = finish(true)
                @Deprecated("Deprecated in Android")
                override fun onError(id: String?) = finish(false)
                override fun onError(id: String?, errorCode: Int) = finish(false)
            })
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.ERROR) {
                finish(false)
            }
        }
    }
}
