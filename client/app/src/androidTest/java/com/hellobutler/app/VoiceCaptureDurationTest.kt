package com.hellobutler.app

import com.hellobutler.app.ui.main.shouldDiscardVoiceCapture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureDurationTest {
    @Test
    fun discardsCaptureShorterThanOneSecond() {
        assertTrue(shouldDiscardVoiceCapture(0L))
        assertTrue(shouldDiscardVoiceCapture(999L))
    }

    @Test
    fun keepsCaptureAtOrAboveOneSecond() {
        assertFalse(shouldDiscardVoiceCapture(1_000L))
        assertFalse(shouldDiscardVoiceCapture(1_001L))
    }
}
