package com.hellobutler.app.execution.audio

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hellobutler.app.data.local.DailyEventDao
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.execution.LocalTextToSpeech
import com.hellobutler.app.execution.matchesExecution
import com.hellobutler.app.execution.nativeSpeechChunks
import com.hellobutler.app.execution.spokenText
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Resolves Daily Event speech at the speech step; all audible output uses the shared player. */
internal class EventSpeechPlayback(
    private val dao: DailyEventDao,
    private val repository: DailyEventRepository,
    private val nativeTts: LocalTextToSpeech,
    private val network: ConnectivityManager,
    private val cacheDir: File,
    private val allows: (AudioExecution) -> Boolean,
    private val playFile: suspend (File, () -> Unit) -> Boolean,
    private val showDeferred: (DailyEventEntity) -> Unit,
) {
    /** Prefers prepared speech, then bounded recovery, then offline synthesis without replaying partial speech. */
    suspend fun speak(execution: AudioExecution): Boolean {
        val success = SpeechSourceResolver(
            local = { withContext(Dispatchers.IO) { local(execution) } },
            recover = {
                withContext(Dispatchers.IO) {
                    repository.recoverSpeech(execution.eventId!!, execution.eventVersion!!)
                    local(execution)
                }
            },
            online = {
                network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            },
            eligible = { valid(execution) && allows(execution) },
            play = ::play,
            invalidate = { current(execution)?.let { withContext(Dispatchers.IO) { repository.discardPreparedSpeech(it) } } },
            native = { native(execution) },
        ).speak()
        if (!success) current(execution)?.let { showDeferred(it) }
        return success
    }

    /** Loads the current matching event so fallback never uses stale text or eligibility. */
    private suspend fun current(execution: AudioExecution) =
        dao.get(execution.eventId!!)?.takeIf { it.matchesExecution(execution) }

    /** Obtains a protected snapshot only when the canonical event still matches. */
    private suspend fun local(execution: AudioExecution) =
        current(execution)?.let { repository.snapshotPreparedSpeech(it) }

    /** Distinguishes inaudible failure from partial speech so fallback cannot repeat an audible segment. */
    private suspend fun play(file: File): SpeechOutput {
        var started = false
        val successful = playFile(file) { started = true }
        return if (successful) SpeechOutput.COMPLETE else if (started) SpeechOutput.INTERRUPTED else SpeechOutput.UNAVAILABLE
    }

    /** Renders bounded offline chunks through the shared player and always removes temporary WAV files. */
    private suspend fun native(execution: AudioExecution): Boolean {
        val text = current(execution)?.spokenText().orEmpty()
        var complete = text.isNotBlank()
        for (chunk in nativeSpeechChunks(text)) {
            if (!valid(execution)) throw CancellationException("Event changed")
            val file = File(cacheDir, "native-speech-${UUID.randomUUID()}.wav")
            try {
                if (!nativeTts.renderOffline(chunk, file) || !valid(execution) || play(file) != SpeechOutput.COMPLETE) {
                    complete = false
                    break
                }
            } finally { file.delete() }
        }
        return complete
    }

    /** Checks the current canonical event before recovery, synthesis, and audible output. */
    private suspend fun valid(execution: AudioExecution): Boolean =
        dao.get(execution.eventId!!).matchesExecution(execution)

    /** Stops native synthesis before the service cancels the execution job. */
    fun stop() = nativeTts.stop()
}
