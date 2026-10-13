package com.hellobutler.app.execution

import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.retainedPlaybackAttempt
import com.hellobutler.app.data.repository.retimeSpeech
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduledAudioPolicyTest {
    private val today = LocalDate.of(2026, 10, 12)
    private fun event() = DailyEventEntity("id", "plan", today.toString(), "Reminder", "Drink water", "reminder",
        "planned", "14:00", null, null, "exact", null, null, false, 0, 1, "user", true, null)

    @Test fun remindersAndOrdinarySpeechShareEligibility() {
        assertTrue(event().canPrepareSpeech(today))
        assertTrue(event().copy(eventType = "custom", speakAloud = true).canPrepareSpeech(today))
        assertFalse(event().copy(eventType = "custom").canPrepareSpeech(today))
        assertFalse(event().copy(syncedFromServer = false).canPrepareSpeech(today))
        assertFalse(event().copy(status = "skipped").canPrepareSpeech(today))
        assertFalse(event().copy(eventDate = today.minusDays(1).toString()).canPrepareSpeech(today))
        assertTrue(event().copy(playbackAttemptedAt = "attempt").canPrepareSpeech(today))
    }
    @Test fun retriesDistinguishTemporaryAndTerminalResponses() {
        listOf(null, 202, 408, 424, 429, 500, 503).forEach { assertTrue(eventAudioRetryable(it)) }
        listOf(400, 401, 403, 404, 409, 410, 422).forEach { assertFalse(eventAudioRetryable(it)) }
    }
    @Test fun meaningfulUpdatesResetClaimsButUnchangedVersionsDoNotReplay() {
        val prior = event().copy(playbackAttemptedAt = "attempt")
        assertEquals("attempt", event().copy(version = 2).retainedPlaybackAttempt(prior))
        assertNull(event().copy(startTime = "15:00").retainedPlaybackAttempt(prior))
        assertNull(event().copy(description = "New speech").retainedPlaybackAttempt(prior))
        assertNull(event().copy(eventDate = today.plusDays(1).toString()).retainedPlaybackAttempt(prior))
    }
    @Test fun preparationIdentityIncludesVersion() {
        assertNotEquals(DailyEventAudioWorker.workName("id", 1), DailyEventAudioWorker.workName("other", 1))
        assertNotEquals(DailyEventAudioWorker.workName("id", 1), DailyEventAudioWorker.workName("id", 2))
    }
    @Test fun oldAlarmAndExecutionCannotPlayAfterRescheduling() {
        val old = event()
        val changed = old.copy(startTime = "15:00")
        assertFalse(changed.matchesAlarm(old.version, old.triggerAtMillis()!!))
        val execution = com.hellobutler.app.execution.audio.AudioExecution("run", com.hellobutler.app.execution.audio.AudioWorkflow.REMINDER, "", old.id,
            old.version, manualEvent = true, eventText = old.spokenText(), eventTriggerAt = old.triggerAtMillis(), eventType = old.eventType)
        assertTrue(old.matchesExecution(execution))
        assertFalse(changed.matchesExecution(execution))
        assertFalse(old.copy(status = "cancelled").matchesExecution(execution))
    }
    @Test fun onlyLiteralOwnClockReferencesAreRetimed() {
        val old = event().copy(content = "Meeting at 2 PM")
        assertEquals("Meeting at 3 PM", old.copy(startTime = "15:00").retimeSpeech(old).spokenText())
        val independent = event().copy(content = "Drink water")
        assertEquals("Drink water", independent.copy(startTime = "15:00").retimeSpeech(independent).spokenText())
        val minute = event().copy(startTime = "14:30", content = "Meeting at 2:30 PM; call at 2 PM")
        assertEquals("Meeting at 3:30 PM; call at 2 PM", minute.copy(startTime = "15:30").retimeSpeech(minute).spokenText())
        val brief = old.copy(eventType = "morning_brief")
        assertEquals(old.spokenText(), brief.copy(startTime = "15:00").retimeSpeech(brief).spokenText())
    }
    @Test fun nativeChunksPreserveTextAndInputLimit() {
        val text = "A long description with several words and an uninterruptedidentifier"
        val chunks = nativeSpeechChunks(text, 20)
        assertTrue(chunks.all { it.length <= 20 })
        assertEquals(text.filterNot(Char::isWhitespace), chunks.joinToString("").filterNot(Char::isWhitespace))
        assertTrue(nativeSpeechChunks("", 20).isEmpty())
        val emoji = "a".repeat(19) + "\uD83D\uDE00" + "b".repeat(20)
        val unicodeChunks = nativeSpeechChunks(emoji, 20)
        assertEquals(emoji, unicodeChunks.joinToString(""))
        assertTrue(unicodeChunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

}
