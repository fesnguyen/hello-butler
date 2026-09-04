package com.hellobutler.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputController(context: Context) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private var recorder: AudioRecord? = null
    private var source: ParcelFileDescriptor? = null
    private var sink: ParcelFileDescriptor? = null
    private var recording = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startLegacy(onPartial, onFinal, onError)
            return
        }

        val sampleRate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, encoding).coerceAtLeast(4096)
        val (readPipe, writePipe) = ParcelFileDescriptor.createPipe()

        source = readPipe
        sink = writePipe
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channel, encoding, bufferSize)
        recognizer.setRecognitionListener(listener(onPartial, onFinal, onError))
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, encoding)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        )

        recording = true
        recorder!!.startRecording()
        thread = Thread {
            val buffer = ByteArray(bufferSize)
            ParcelFileDescriptor.AutoCloseOutputStream(writePipe).use { output ->
                while (recording) {
                    val count = recorder?.read(buffer, 0, buffer.size) ?: break
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
        }.apply { start() }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            recognizer.stopListening()
            return
        }

        recording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
        sink?.close()
        sink = null
        thread = null
    }

    fun destroy() {
        stop()
        closeSource()
        recognizer.destroy()
    }

    private fun startLegacy(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        recognizer.setRecognitionListener(listener(onPartial, onFinal, onError))
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        )
    }

    private fun listener(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) = object : RecognitionListener {
        override fun onPartialResults(results: Bundle) = onPartial(text(results))
        override fun onResults(results: Bundle) {
            closeSource()
            onFinal(text(results))
        }
        override fun onError(error: Int) {
            closeSource()
            onError("Speech recognition failed ($error)")
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun closeSource() {
        source?.close()
        source = null
    }

    private fun text(results: Bundle): String =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
