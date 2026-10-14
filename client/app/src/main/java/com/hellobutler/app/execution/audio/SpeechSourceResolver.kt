package com.hellobutler.app.execution.audio

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class SpeechOutput { COMPLETE, UNAVAILABLE, INTERRUPTED }

/** A single source decision at PLAY(<speech>); preparation never calls playback. */
class SpeechSourceResolver(
    private val local: suspend () -> File?,
    private val recover: suspend () -> File?,
    private val online: () -> Boolean,
    private val eligible: suspend () -> Boolean,
    private val play: suspend (File) -> SpeechOutput,
    private val native: suspend () -> Boolean,
    private val invalidate: suspend () -> Unit = {},
    private val recoveryTimeoutMillis: Long = 5_000,
) {
    suspend fun speak(): Boolean {
        if (!eligible()) return false
        val prepared = try { local() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (prepared != null) {
            when (play(prepared)) {
                SpeechOutput.COMPLETE -> return true
                SpeechOutput.INTERRUPTED -> return false
                SpeechOutput.UNAVAILABLE -> invalidate()
            }
        }
        val recovered = if (online()) withTimeoutOrNull(recoveryTimeoutMillis) {
            try { recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        currentCoroutineContext().ensureActive()
        if (!eligible()) return false
        if (recovered != null) {
            when (play(recovered)) {
                SpeechOutput.COMPLETE -> return true
                SpeechOutput.INTERRUPTED -> return false
                SpeechOutput.UNAVAILABLE -> invalidate()
            }
        }
        return eligible() && native()
    }
}
