package com.hellobutler.app.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID

class ButlerAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

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
            startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "Recording started codec=aac container=m4a bitrate_bps=48000 sample_rate_hz=16000 channels=1")
        } catch (error: Exception) {
            Log.e(TAG, "Recording start failed error_type=${error.javaClass.simpleName}", error)
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
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val bytes = file?.length() ?: 0L
            val estimatedRawBytes = elapsedMs * 16_000L * 2L / 1_000L
            Log.i(
                TAG,
                "Recording compressed codec=aac bytes=$bytes duration_ms=$elapsedMs estimated_ratio=${ratio(bytes, estimatedRawBytes)}",
            )
            file?.takeIf { bytes > 0L }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Recording stop failed error_type=${error.javaClass.simpleName}", error)
            file?.delete()
            null
        } finally {
            startedAt = 0L
            active.release()
        }
    }

    fun cancel() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        active?.release()
        val deleted = output?.delete() ?: false
        Log.i(TAG, "Recording cancelled temporary_file_deleted=$deleted")
        output = null
        startedAt = 0L
    }

    private fun ratio(compressed: Long, raw: Long) = if (raw > 0L) "%.3f".format(compressed.toDouble() / raw) else "unknown"

    companion object { private const val TAG = "ButlerAudio" }
}
