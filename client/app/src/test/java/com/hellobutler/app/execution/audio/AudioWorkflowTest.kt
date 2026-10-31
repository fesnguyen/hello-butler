package com.hellobutler.app.execution.audio

import org.junit.Assert.*
import org.junit.Test

class AudioWorkflowTest {
    @Test fun strictParsing() = AudioWorkflowChecks.parser()
    @Test fun independentShuffleCycles() = AudioWorkflowChecks.rotation()
    @Test fun volumeAndAutomaticPreferences() = AudioWorkflowChecks.preferences()
    @Test fun nightlyReminderAndResponseOrder() = AudioWorkflowChecks.sequences()
    @Test fun morningDelayBeginsAfterPlaybackAndResumes() = AudioWorkflowChecks.persistentDelay()
    @Test fun optionalFailuresAndCancellation() = AudioWorkflowChecks.failuresAndCancellation()
    @Test fun continuationsRejectSupersededTimesStepsAndTerminalStates() {
        val waiting = AudioExecution("run", AudioWorkflow.REMINDER, "speech.ogg", next = 2, dueAt = 100, status = "waiting")
        assertTrue(waiting.canContinue(2, 100))
        assertFalse(waiting.copy(dueAt = 200).canContinue(2, 100))
        assertFalse(waiting.copy(next = 3).canContinue(2, 100))
        for (status in listOf("cancelled", "complete", "playing", "preparing")) {
            assertFalse(waiting.copy(status = status).canContinue(2, 100))
        }
        for (status in listOf("ready", "offered")) {
            assertTrue(waiting.copy(status = status).canContinue(2, 100))
        }
        assertFalse(waiting.copy(createdAt = waiting.createdAt + 1).matchesCheckpoint(waiting))
    }

}
