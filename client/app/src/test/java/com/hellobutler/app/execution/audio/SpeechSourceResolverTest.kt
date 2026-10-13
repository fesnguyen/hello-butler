package com.hellobutler.app.execution.audio

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpeechSourceResolverTest {
    private val file = File("prepared.ogg")

    @Test fun preparedSpeechNeverCallsRecoveryOrNative() = runBlocking {
        val played = mutableListOf<File>()
        assertTrue(SpeechSourceResolver({ file }, { error("Recovery") }, { error("Network lookup") },
            { true }, { played.add(it); SpeechOutput.COMPLETE }, { error("Native") }).speak())
        assertEquals(listOf(file), played)
    }
    @Test fun singleRecoveryCanSupplyPreparedSpeech() = runBlocking {
        var recoveries = 0
        var plays = 0
        assertTrue(SpeechSourceResolver({ null }, { recoveries++; file }, { true }, { true },
            { plays++; SpeechOutput.COMPLETE }, { error("Native") }).speak())
        assertEquals(1, recoveries); assertEquals(1, plays)
    }
    @Test fun offlineUsesNativeWithoutBackend() = runBlocking {
        var nativeCalls = 0
        assertTrue(SpeechSourceResolver({ null }, { error("Backend") }, { false }, { true },
            { error("Prepared") }, { nativeCalls++; true }).speak())
        assertEquals(1, nativeCalls)
    }
    @Test fun pendingAndNetworkFailureEachFallBackOnce() = runBlocking {
        for (failure in listOf(false, true)) {
            var recoveries = 0; var nativeCalls = 0
            assertTrue(SpeechSourceResolver({ null }, { recoveries++; if (failure) throw java.io.IOException("offline"); null },
                { true }, { true }, { error("Prepared") }, { nativeCalls++; true }).speak())
            assertEquals(1, recoveries); assertEquals(1, nativeCalls)
        }
    }
    @Test fun recoveryTimeoutDoesNotPollOrPlayLateAudio() = runBlocking {
        var recoveries = 0; var nativeCalls = 0
        assertTrue(SpeechSourceResolver({ null }, { recoveries++; awaitCancellation() }, { true }, { true },
            { error("Late playback") }, { nativeCalls++; true }, recoveryTimeoutMillis = 10).speak())
        assertEquals(1, recoveries); assertEquals(1, nativeCalls)
    }
    @Test fun unavailableNativeDoesNotClaimSuccessfulPlayback() = runBlocking {
        assertFalse(SpeechSourceResolver({ null }, { null }, { false }, { true },
            { error("Prepared") }, { false }).speak())
    }
    @Test fun eventEditOrCancellationDuringRecoveryPreventsSpeech() = runBlocking {
        var eligible = true
        assertFalse(SpeechSourceResolver({ null }, { eligible = false; file }, { true }, { eligible },
            { error("Stale playback") }, { error("Stale native") }).speak())
        val started = CompletableDeferred<Unit>()
        val job = async {
            SpeechSourceResolver({ null }, { started.complete(Unit); awaitCancellation() }, { true }, { true },
                { error("Cancelled playback") }, { error("Cancelled native") }).speak()
        }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun corruptFileCanFallBackButPartiallyAudibleFileNeverRepeats() = runBlocking {
        var invalidations = 0
        assertTrue(SpeechSourceResolver({ file }, { null }, { false }, { true },
            { SpeechOutput.UNAVAILABLE }, { true }, { invalidations++ }).speak())
        assertEquals(1, invalidations)
        assertFalse(SpeechSourceResolver({ file }, { error("Repeat recovery") }, { true }, { true },
            { SpeechOutput.INTERRUPTED }, { error("Repeat native") }).speak())
    }
}
