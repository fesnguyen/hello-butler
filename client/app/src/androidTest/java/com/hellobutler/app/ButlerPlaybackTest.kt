package com.hellobutler.app

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.data.local.ConversationMessageEntity
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import com.hellobutler.app.execution.ButlerPlayback
import com.hellobutler.app.execution.ButlerPlaybackState
import com.hellobutler.app.execution.PlaybackPhase
import com.hellobutler.app.execution.playbackPhase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ButlerPlaybackTest {
    @Test fun pendingAndDownloadingStayLoadingAndOnlyActiveRouteShowsStop() {
        val idle = ButlerPlaybackState()
        for (cache in listOf("pending", "downloading")) {
            assertEquals(PlaybackPhase.LOADING, playbackPhase("id", false, cache, idle))
            assertEquals(PlaybackPhase.LOADING, playbackPhase("id", true, cache, idle))
        }
        assertEquals(PlaybackPhase.READY, playbackPhase("id", false, "cached", idle))
        val speaking = ButlerPlaybackState("id", false, PlaybackPhase.SPEAKING)
        assertEquals(PlaybackPhase.SPEAKING, playbackPhase("id", false, "cached", speaking))
        assertEquals(PlaybackPhase.READY, playbackPhase("id", true, "cached", speaking))
        val switching = ButlerPlaybackState("id", true, PlaybackPhase.LOADING)
        assertEquals(PlaybackPhase.LOADING, playbackPhase("id", false, "cached", switching))
    }

    @Test fun cachedAssetPlaysSwitchesRouteAndStopsWithoutRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ButlerApplication
        val id = "playback-test"
        val file = File(context.cacheDir, "$id.wav")
        val samples = ByteArray(16000 * 2 * 30)
        file.writeBytes(ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(16000); putInt(32000)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(samples.size); put(samples)
        }.array())
        app.container.database.butlerConversationDao().upsertMessage(ConversationMessageEntity(
            id = "$id:butler", requestId = id, role = "butler", text = "Canonical text remains visible",
            createdAt = "2026-09-30T00:00:00Z", deliveryState = "completed", inputSource = "text",
            audioCacheState = "cached", localAudioPath = file.absolutePath, responseAudioMimeType = "audio/wav",
        ))
        try {
            ButlerAudioPlaybackService.play(context, id, false)
            awaitSpeaking(false)
            ButlerAudioPlaybackService.play(context, id, true)
            awaitSpeaking(true)
            ButlerAudioPlaybackService.play(context, id, false)
            awaitSpeaking(false)
            ButlerAudioPlaybackService.stop(context)
            withTimeout(3000) { while (ButlerPlayback.state.value.requestId != null) delay(20) }
            assertEquals(AudioManager.MODE_NORMAL, (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode)
            delay(300)
            assertEquals(ButlerPlaybackState(), ButlerPlayback.state.value)
            assertEquals("Canonical text remains visible", app.container.database.butlerConversationDao().message(id, "butler")?.text)
        } finally {
            ButlerAudioPlaybackService.stop(context)
            file.delete()
        }
    }

    private suspend fun awaitSpeaking(private: Boolean) = withTimeout(5000) {
        while (ButlerPlayback.state.value.phase != PlaybackPhase.SPEAKING ||
            ButlerPlayback.state.value.privateRoute != private) delay(20)
    }
}
