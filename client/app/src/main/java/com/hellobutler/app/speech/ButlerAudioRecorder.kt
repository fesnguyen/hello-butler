package com.hellobutler.app.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

class ButlerAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): File {
        check(recorder == null) { "Recording is already active" }
        val file = File(context.cacheDir, "butler-input-${UUID.randomUUID()}.m4a")
        val active = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioEncodingBitRate(48_000)
            active.setAudioSamplingRate(16_000)
            active.setOutputFile(file.absolutePath)
            active.prepare()
            active.start()
        } catch (error: Exception) {
            active.release()
            file.delete()
            throw error
        }
        recorder = active
        output = file
        return file
    }

    fun stop(): File? {
        val active = recorder ?: return null
        val file = output
        recorder = null
        output = null
        return try {
            active.stop()
            file?.takeIf { it.length() > 0L }
        } catch (_: RuntimeException) {
            file?.delete()
            null
        } finally {
            active.release()
        }
    }

    fun cancel() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        active?.release()
        output?.delete()
        output = null
    }
}
