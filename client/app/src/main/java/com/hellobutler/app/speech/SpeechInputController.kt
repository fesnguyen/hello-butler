package com.hellobutler.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputController(context: Context) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private var isHolding = false
    private var sessionActive = false
    private var currentPartial = ""
    private val segments = mutableListOf<String>()
    private var onPartial: (String) -> Unit = {}
    private var onFinal: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(results: Bundle) {
                currentPartial = text(results)
                onPartial(combinedText(includePartial = true))
            }

            override fun onResults(results: Bundle) {
                appendSegment(text(results))
                currentPartial = ""

                if (isHolding) {
                    onPartial(combinedText())
                    startListening()
                } else {
                    deliverFinal()
                }
            }

            override fun onError(error: Int) {
                if (isHolding && isRecoverable(error)) {
                    currentPartial = ""
                    startListening()
                    return
                }

                if (!isHolding && (segments.isNotEmpty() || currentPartial.isNotBlank())) {
                    deliverFinal()
                    return
                }

                sessionActive = false
                onError("Speech recognition failed ($error)")
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (sessionActive) recognizer.cancel()

        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        isHolding = true
        sessionActive = true
        currentPartial = ""
        segments.clear()
        startListening()
    }

    fun stop() {
        if (!sessionActive) return
        isHolding = false
        recognizer.stopListening()
    }

    fun destroy() {
        isHolding = false
        sessionActive = false
        recognizer.cancel()
        recognizer.destroy()
    }

    private fun startListening() {
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        )
    }

    private fun appendSegment(value: String) {
        val segment = value.trim()
        if (segment.isNotEmpty()) segments += segment
    }

    private fun combinedText(includePartial: Boolean = false): String =
        buildList {
            addAll(segments)
            if (includePartial && currentPartial.isNotBlank()) add(currentPartial.trim())
        }.joinToString(" ")

    private fun deliverFinal() {
        if (!sessionActive) return
        val finalText = combinedText(includePartial = true).trim()
        sessionActive = false
        currentPartial = ""
        segments.clear()
        onFinal(finalText)
    }

    private fun isRecoverable(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH

    private fun text(results: Bundle): String =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
