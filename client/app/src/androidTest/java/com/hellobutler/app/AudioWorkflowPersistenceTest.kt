package com.hellobutler.app

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.execution.audio.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioWorkflowPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test fun resourceGroupsResolveAndUnknownOptionalResourceIsAbsent() {
        AudioWorkflows.resources.forEach { name ->
            val id = AudioResources.resolve(name) { error("Unexpected group") }
            assertNotNull(id)
            InstrumentationRegistry.getInstrumentation().targetContext.resources.openRawResource(id!!).use {
                assertTrue(it.available() > 0)
            }
        }
        val selected = mutableListOf<String>()
        AudioWorkflows.groups.keys.forEach { group ->
            assertNotNull(AudioResources.resolve(group) { selected.add(it); AudioWorkflows.groups.getValue(it).first() })
        }
        assertEquals(AudioWorkflows.groups.keys.toList(), selected)
        assertNull(AudioResources.resolve("optional_missing") { error("Unknown group") })
    }

    @Test fun rotationsSurviveStoreRecreationAndRemainIndependent() {
        context.getSharedPreferences("butler_audio_workflows", Context.MODE_PRIVATE).edit().clear().commit()
        val morning = mutableSetOf<String>()
        val evening = mutableSetOf<String>()
        repeat(5) {
            assertTrue(morning.add(AudioWorkflowStore(context).select("morning_warmup")))
            assertTrue(evening.add(AudioWorkflowStore(context).select("evening_warmup")))
        }
        assertEquals(AudioWorkflows.groups.getValue("morning_warmup").toSet(), morning)
        assertEquals(AudioWorkflows.groups.getValue("evening_warmup").toSet(), evening)
    }

    @Test fun continuationAndTerminalJournalSurviveRestartAndRejectStaleUpdates() {
        val store = AudioWorkflowStore(context)
        val execution = AudioExecution("persistence-test", AudioWorkflow.MORNING_BRIEF, "speech.ogg",
            eventId = "event", eventVersion = 2, next = 3, dueAt = 123456L, status = "waiting")
        store.save(execution)
        val restored = AudioWorkflowStore(context).get(execution.id)!!
        assertEquals(3, restored.next)
        assertEquals(123456L, restored.dueAt)
        assertEquals("waiting", restored.status)
        store.save(restored.copy(status = "playing"))
        assertFalse(store.transition(restored, restored.copy(status = "offered")) { fail("Stale work notified") })
        store.save(restored.copy(status = "cancelled"))
        assertEquals("cancelled", AudioWorkflowStore(context).get(execution.id)?.status)
    }

    @Test fun settingsPersistWithoutChangingAndroidVolume() {
        context.getSharedPreferences("butler_sound_voice", Context.MODE_PRIVATE).edit().clear().commit()
        val audio = context.getSystemService(AudioManager::class.java)
        val nativeVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val settings = SoundVoiceSettings(context)
        assertEquals(70, settings.state.value.volume)
        settings.volume(23)
        settings.toggle("reminders", true)
        settings.toggle("responses", true)
        settings.toggle("briefings", false)
        val restored = SoundVoiceSettings(context).state.value
        assertEquals(23, restored.volume)
        assertTrue(restored.reminders)
        assertTrue(restored.responses)
        assertFalse(restored.briefings)
        assertEquals(nativeVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
