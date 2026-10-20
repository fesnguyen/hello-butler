package com.hellobutler.app

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.execution.audio.AudioResourcePlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioResourcePlayerTest {
    /** A suspended old preparation callback must not start or release its replacement player. */
    @Test fun cancelledPreparedCallbackCannotStartReplacement() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Android 15 permits audio focus only for the top app or a foreground service.
        val activity = instrumentation.startActivitySync(
            android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val speaking = CompletableDeferred<Unit>()
        var validations = 0
        var starts = 0
        val file = silentWave(context.cacheDir)
        val player = AudioResourcePlayer(
            context, scope, context.getSystemService(AudioManager::class.java),
            selectResource = { error("Unexpected group selection") },
            currentVolume = { 0f },
            canStart = {
                if (++validations == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                true
            },
            onPlaybackStarted = { starts++; speaking.complete(Unit) },
            onFocusLost = { error("Unexpected focus loss") },
        )
        try {
            val first = scope.async { player.play("<speech>", file.path, false) }
            withTimeout(5_000) { entered.await() }
            first.cancelAndJoin()
            withContext(Dispatchers.Main.immediate) { player.release() }
            val replacement = scope.async { player.play("<speech>", file.path, false) }
            withTimeout(5_000) { speaking.await() }
            release.complete(Unit)
            // Drain the old prepared callback on the same dispatcher before checking ownership.
            withContext(Dispatchers.Main.immediate) { yield() }
            assertEquals(1, starts)
            assertTrue(replacement.isActive)
            replacement.cancelAndJoin()
        } finally {
            release.complete(Unit)
            scope.cancel()
            withContext(Dispatchers.Main.immediate) { player.release(); activity.finish() }
            file.delete()
        }
    }

    /** Supplies valid long silent audio so lifecycle assertions do not depend on bundled asset length. */
    private fun silentWave(directory: File): File {
        val samples = ByteArray(16_000 * 2 * 30)
        return File(directory, "player-${UUID.randomUUID()}.wav").apply {
            writeBytes(ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(16_000); putInt(32_000)
                putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(samples.size); put(samples)
            }.array())
        }
    }
}
