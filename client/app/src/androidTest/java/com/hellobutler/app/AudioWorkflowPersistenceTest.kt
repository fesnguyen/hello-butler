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
    @Test fun terminalPruningKeepsSnapshotReferencedByActiveRun() {
        val store = AudioWorkflowStore(context)
        store.clear()
        val file = java.io.File(context.filesDir, "workflow_audio/shared.ogg")
        file.parentFile!!.mkdirs(); file.writeText("protected")
        val old = AudioExecution("old-run", AudioWorkflow.REMINDER, file.path,
            eventId = "event", eventVersion = 1, status = "complete")
        store.save(old)
        store.save(old.copy(id = "active-run", status = "waiting", manualEvent = true))
        store.prune(System.currentTimeMillis() + 8 * 86400_000L)
        assertNull(store.get("old-run")); assertTrue(file.exists())
        assertEquals("waiting", AudioWorkflowStore(context).get("active-run")!!.status)
        file.delete(); store.clear()
    }

    /** Scheduler presentation must never downgrade a newer checkpoint or an active/terminal execution. */
    @Test fun schedulerOfferPreservesNewerOrTerminalState() {
        val store = AudioWorkflowStore(context)
        val scheduler = AudioWorkflowScheduler(context, store)
        val execution = AudioExecution("stale-offer-test", AudioWorkflow.REMINDER, "speech.ogg", status = "waiting")
        try {
            val newer = execution.copy(next = 1)
            store.save(newer)
            assertTrue(scheduler.offer(execution))
            assertEquals(1, store.get(execution.id)!!.next)
            for (status in listOf("playing", "complete", "cancelled")) {
                store.save(execution.copy(status = status))
                assertTrue(scheduler.offer(execution))
                assertEquals(status, store.get(execution.id)!!.status)
            }
        } finally { scheduler.dismiss(execution.id); store.clear() }
    }

    /** A prepared notification action still requires the journal guard at publication time. */
    @Test fun preparedNotificationCannotPublishAfterCheckpointChanges() {
        val store = AudioWorkflowStore(context)
        val scheduler = AudioWorkflowScheduler(context, store)
        val notifications = AudioWorkflowNotifications(context)
        val execution = AudioExecution("guarded-notification-test", AudioWorkflow.REMINDER, "speech.ogg", status = "waiting")
        scheduler.dismiss(execution.id)
        store.save(execution)
        try {
            val publish = notifications.prepareOffer(execution)
            if (publish == null) {
                assertFalse(scheduler.offer(execution))
                assertEquals("waiting", store.get(execution.id)!!.status)
            } else {
                store.save(execution.copy(next = 1))
                assertFalse(store.transition(execution, execution.copy(status = "offered"), publish))
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                assertFalse(manager.activeNotifications.any { it.tag == execution.id })
                assertEquals(1, store.get(execution.id)!!.next)
            }
        } finally { scheduler.dismiss(execution.id); store.clear() }
    }

    @Test fun cancellationDefeatsAlreadyReadCheckpointsAndPreservesCompletedExecutions() {
        val store = AudioWorkflowStore(context)
        val waiting = AudioExecution("cancel-race-test", AudioWorkflow.REMINDER, "", next = 2, dueAt = 100, status = "waiting")
        try {
            store.save(waiting)
            var removed = false
            store.cancel(waiting.id) { removed = store.get(waiting.id)?.status == "cancelled" }
            assertTrue(removed) // Invalidated before removing alarm/work effects.
            assertFalse(store.transition(waiting, waiting.copy(status = "playing")) { fail("Cancelled audio resumed") })
            val newer = waiting.copy(dueAt = 200)
            store.save(newer)
            store.cancel(waiting.id, waiting) { fail("Stale worker cancelled a replacement") }
            assertEquals(200L, store.get(waiting.id)!!.dueAt)
            store.save(waiting.copy(status = "complete"))
            store.cancel(waiting.id) {}
            assertEquals("complete", store.get(waiting.id)!!.status)
        } finally { store.clear() }
    }

    @Test fun repeatedAndRescheduledContinuationsLeaveOnePendingWorkAndCannotReviveAfterCancel() {
        val store = AudioWorkflowStore(context)
        val scheduler = AudioWorkflowScheduler(context, store)
        val work = androidx.work.WorkManager.getInstance(context)
        val waiting = AudioExecution("single-continuation-test", AudioWorkflow.REMINDER, "",
            dueAt = System.currentTimeMillis() + 86400_000L, status = "waiting")
        val tag = "butler-audio-continuation-${waiting.id}"
        fun pending() = work.getWorkInfosByTag(tag).get(10, java.util.concurrent.TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        fun await(assertion: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (!assertion()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "WorkManager did not reconcile" }
                Thread.sleep(20)
            }
        }
        try {
            store.save(waiting)
            repeat(3) { scheduler.schedule(waiting) }
            await { pending().size == 1 }
            val original = pending().single().id
            val newer = waiting.copy(dueAt = waiting.dueAt + 60_000)
            store.save(newer)
            scheduler.schedule(newer)
            await { pending().let { it.size == 1 && it.single().id != original } }
            scheduler.schedule(waiting) // Stale scheduling must leave the replacement intact.
            assertEquals(newer.dueAt, store.get(waiting.id)!!.dueAt)
            scheduler.cancel(waiting.id)
            scheduler.schedule(newer)
            await { pending().isEmpty() }
            assertEquals("cancelled", store.get(waiting.id)!!.status)
        } finally { scheduler.cancel(waiting.id); store.clear() }
    }

    @Test fun recoveryPreservesCheckpointAndNeverReplaysInterruptedAudio() {
        val store = AudioWorkflowStore(context)
        val scheduler = AudioWorkflowScheduler(context, store)
        // A future timestamp avoids actually running the test's durable worker.
        val waiting = AudioExecution("recover-waiting-test", AudioWorkflow.REMINDER, "",
            dueAt = System.currentTimeMillis() + 86400_000L, status = "waiting")
        val interrupted = waiting.copy(id = "recover-playing-test", status = "playing")
        val offered = waiting.copy(id = "recover-offered-test", status = "offered")
        val overdue = waiting.copy(id = "recover-overdue-test", dueAt = 1)
        try {
            store.save(waiting); store.save(interrupted); store.save(offered); store.save(overdue)
            scheduler.recover(cancelInterrupted = false)
            assertEquals("playing", store.get(interrupted.id)!!.status)
            scheduler.recover()
            assertEquals(waiting.dueAt, store.get(waiting.id)!!.dueAt)
            assertEquals("waiting", store.get(waiting.id)!!.status)
            assertEquals("offered", store.get(offered.id)!!.status)
            assertEquals(1L, store.get(overdue.id)!!.dueAt)
            assertEquals("cancelled", store.get(interrupted.id)!!.status)
        } finally {
            listOf(waiting, interrupted, offered, overdue).forEach { scheduler.cancel(it.id) }
            store.clear()
        }
    }

    @Test fun fallbackReconciliationUpdatesAndRemovesSchedulesWithoutExactAccess() {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms())
        val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        val event = com.hellobutler.app.data.local.DailyEventEntity(
            "fallback-lifecycle-test", null, tomorrow, "Reminder", "Water", "reminder", "planned", "14:00",
            null, null, "exact", null, null, false, 0, 1, "user", true, null,
        )
        val scheduler = com.hellobutler.app.execution.DailyEventScheduler(context)
        val work = androidx.work.WorkManager.getInstance(context)
        fun pending() = work.getWorkInfosForUniqueWork("daily-event-execution-${event.id}")
            .get(10, java.util.concurrent.TimeUnit.SECONDS).filter { !it.state.isFinished }
        fun await(assertion: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (!assertion()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Fallback work did not reconcile" }
                Thread.sleep(20)
            }
        }
        try {
            scheduler.schedule(event)
            await { pending().size == 1 }
            val old = pending().single().id
            val changed = event.copy(startTime = "15:00", version = 2)
            repeat(3) { scheduler.reconcile(listOf(event), listOf(changed)) }
            await { pending().let { it.size == 1 && it.single().id != old } }
            scheduler.schedule(changed.copy(status = "cancelled"))
            await { pending().isEmpty() }
            scheduler.schedule(changed)
            await { pending().size == 1 }
            scheduler.reconcile(listOf(changed), emptyList()) // Canonical deletion.
            await { pending().isEmpty() }
            scheduler.schedule(changed) // Same reconciliation used after reboot/update.
            await { pending().size == 1 }
            scheduler.schedule(changed.copy(playbackAttemptedAt = "already-attempted"))
            await { pending().isEmpty() }
        } finally { scheduler.cancel(event) }
    }

    @Test fun automaticReservationCannotOverwriteManualSuppressionOrDownloadCancellation() {
        val store = AudioWorkflowStore(context)
        val preparing = AudioExecution("reservation-race-test", AudioWorkflow.BUTLER_RESPONSE, "", status = "preparing")
        try {
            assertTrue(store.reserve(preparing))
            assertFalse(store.reserve(preparing.copy(status = "ready")))
            store.cancel(preparing.id) {}
            assertFalse(store.transition(preparing, preparing.copy(status = "ready")) { fail("Late download resurrected speech") })
            assertFalse(store.reserve(preparing))
            assertEquals("cancelled", store.get(preparing.id)!!.status)
        } finally { store.clear() }
    }

}
