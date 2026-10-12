package com.hellobutler.app.execution

import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.retainedPlaybackAttempt
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
        assertFalse(event().copy(playbackAttemptedAt = "attempt").canPrepareSpeech(today))
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
}
