package com.hellobutler.app.execution

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Offline file synthesis; the shared MediaPlayer owns all audible output and focus. */
class LocalTextToSpeech(private val context: Context) {
    @Volatile private var stopAction: (() -> Unit)? = null
    fun stop() { stopAction?.invoke() }

    suspend fun renderOffline(text: String, target: File): Boolean = withContext(Dispatchers.Main.immediate) {
        if (text.isBlank() || text.length > TextToSpeech.getMaxSpeechInputLength()) return@withContext false
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                stop()
                val finished = AtomicBoolean(false)
                var engine: TextToSpeech? = null
                val utterance = "butler-fallback-${UUID.randomUUID()}"
                fun finish(success: Boolean) {
                    if (!finished.compareAndSet(false, true)) return
                    runCatching { engine?.stop(); engine?.shutdown() }
                    stopAction = null
                    val valid = success && target.isFile && target.length() > 44
                    if (!valid) target.delete()
                    if (continuation.isActive) continuation.resume(valid)
                }
                stopAction = { finish(false) }
                continuation.invokeOnCancellation { finish(false) }
                try {
                    engine = TextToSpeech(context.applicationContext) { status ->
                        Handler(Looper.getMainLooper()).post {
                            val tts = engine
                            if (finished.get()) { tts?.shutdown(); return@post }
                            if (tts == null || status != TextToSpeech.SUCCESS) { finish(false); return@post }
                            val locale = Locale.getDefault()
                            val voices = tts.voices.orEmpty().filter {
                                !it.isNetworkConnectionRequired && it.locale.language in setOf(locale.language, "en") &&
                                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                            }.sortedByDescending {
                                when { it.locale == locale -> 3; it.locale.language == locale.language -> 2; else -> 1 }
                            }
                            val voice = voices.firstOrNull { tts.isLanguageAvailable(it.locale) >= TextToSpeech.LANG_AVAILABLE }
                            if (voice == null || tts.setVoice(voice) == TextToSpeech.ERROR) { finish(false); return@post }
                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) = Unit
                                override fun onDone(id: String?) { if (id == utterance) finish(true) }
                                @Deprecated("Deprecated in Android")
                                override fun onError(id: String?) { if (id == utterance) finish(false) }
                                override fun onError(id: String?, errorCode: Int) { if (id == utterance) finish(false) }
                            })
                            if (tts.synthesizeToFile(text, Bundle(), target, utterance) == TextToSpeech.ERROR) finish(false)
                        }
                    }
                } catch (_: RuntimeException) { finish(false) }
            }
        } ?: false
    }
}

internal fun nativeSpeechChunks(text: String, limit: Int = TextToSpeech.getMaxSpeechInputLength()): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var remaining = text.trim()
    while (remaining.length > limit) {
        var boundary = remaining.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
        if (remaining[boundary - 1].isHighSurrogate() && remaining[boundary].isLowSurrogate()) boundary--
        chunks.add(remaining.substring(0, boundary))
        remaining = remaining.substring(boundary).trimStart()
    }
    if (remaining.isNotEmpty()) chunks.add(remaining)
    return chunks
}
