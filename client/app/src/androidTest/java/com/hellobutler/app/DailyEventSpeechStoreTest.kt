package com.hellobutler.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.DailyEventSpeechStore
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyEventSpeechStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var store: DailyEventSpeechStore
    private lateinit var event: DailyEventEntity
    private val ogg = "OggS".encodeToByteArray() + ByteArray(24) + "OpusHead".encodeToByteArray()

    /** Isolates storage and preferences from the application's actual speech cache. */
    @Before fun setup() {
        root = File(context.filesDir, "speech-store-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs = context.getSharedPreferences(root.name, Context.MODE_PRIVATE)
        store = DailyEventSpeechStore(root, File(root, "cache"), prefs)
        event = DailyEventEntity(
            id = "event", dailyPlanId = "plan", eventDate = LocalDate.now().toString(),
            title = "Reminder", description = "Drink water", eventType = "reminder", status = "planned",
            startTime = "12:00", endTime = null, durationMinutes = null, scheduledPrecision = "exact",
            content = null, reminderMinutesBefore = null, speakAloud = false, sortOrder = 0,
            version = 1, origin = "user", syncedFromServer = true, playbackAttemptedAt = null,
        )
    }

    /** Removes only this test's files and preferences. */
    @After fun cleanup() {
        context.getSharedPreferences(root.name, Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    /** Prepared cleanup must leave the workflow snapshot usable until journal-owned pruning. */
    @Test fun atomicPublicationAndPreparedCleanupPreserveProtectedSnapshot() {
        val partial = store.partial(event.id, event.version).apply { writeBytes(ogg) }
        assertNull(store.preparedSpeech(event))
        val prepared = store.publish(partial, event.id, event.version)
        assertFalse(partial.exists())
        assertEquals(prepared, store.preparedSpeech(event))
        val snapshot = store.snapshot(event)!!
        assertArrayEquals(ogg, snapshot.readBytes())
        store.reconcile(event, null)
        assertFalse(prepared.exists())
        assertTrue(snapshot.exists())
    }

    /** Voice invalidation must survive recreation and block both prepared lookup and snapshot reuse upstream. */
    @Test fun persistedVoiceInvalidationRejectsOldPreparedBytes() {
        store.publish(store.partial(event.id, event.version).apply { writeBytes(ogg) }, event.id, event.version)
        store.snapshot(event)
        store.invalidate(listOf(event))
        store = DailyEventSpeechStore(root, File(root, "cache"), context.getSharedPreferences(root.name, Context.MODE_PRIVATE))
        assertTrue(store.invalidatedByVoiceChange(event))
        assertNull(store.preparedSpeech(event))
        assertTrue(store.workflowSpeechFile(event).exists())
    }

    /** Recovery removes stale partials and obsolete prepared versions without touching fresh transfers or snapshots. */
    @Test fun recoverySeparatesAbandonedTransfersFromProtectedFiles() {
        val obsolete = store.publish(store.partial(event.id, 2).apply { writeBytes(ogg) }, event.id, 2)
        val oldPartial = store.partial(event.id, 1).apply { writeBytes(ogg); setLastModified(1L) }
        val freshPartial = store.partial(event.id, 1).apply { writeBytes(ogg) }
        val protected = store.workflowSpeechFile(event).apply { parentFile!!.mkdirs(); writeBytes(ogg) }
        store.recover(listOf(event))
        assertFalse(obsolete.exists())
        assertFalse(oldPartial.exists())
        assertTrue(freshPartial.exists())
        assertTrue(protected.exists())
    }

    /** Only a proven local-edit echo may bind existing bytes to a newer canonical version. */
    @Test fun remoteVersionCannotRebindButConfirmedLocalEditCan() {
        store.publish(store.partial(event.id, 1).apply { writeBytes(ogg) }, event.id, 1)
        val next = event.copy(version = 2)
        store.rebind(event, next)
        assertNull(store.preparedSpeech(next))
        store.rebind(event.copy(syncedFromServer = false), next)
        assertNotNull(store.preparedSpeech(next))
    }
}
