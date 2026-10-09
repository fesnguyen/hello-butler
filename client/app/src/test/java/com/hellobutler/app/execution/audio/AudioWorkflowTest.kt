package com.hellobutler.app.execution.audio

import org.junit.Test

class AudioWorkflowTest {
    @Test fun strictParsing() = AudioWorkflowChecks.parser()
    @Test fun independentShuffleCycles() = AudioWorkflowChecks.rotation()
    @Test fun volumeAndAutomaticPreferences() = AudioWorkflowChecks.preferences()
    @Test fun nightlyReminderAndResponseOrder() = AudioWorkflowChecks.sequences()
    @Test fun morningDelayBeginsAfterPlaybackAndResumes() = AudioWorkflowChecks.persistentDelay()
    @Test fun optionalFailuresAndCancellation() = AudioWorkflowChecks.failuresAndCancellation()
}
