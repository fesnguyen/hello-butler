package com.hellobutler.app.execution.audio

import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.execution.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*

/** Creates journaled executions without owning an Android service or playback job. */
internal class AudioExecutionPreparer(
    private val database: ButlerDatabase,
    private val events: DailyEventRepository,
    private val responses: ButlerRepository,
    private val store: AudioWorkflowStore,
    private val scheduler: AudioWorkflowScheduler,
    private val settings: SoundVoiceSettings,
    private val showDeferred: (DailyEventEntity) -> Unit,
    private val dismissDeferred: (String) -> Unit,
    private val reserveActiveId: (String) -> Unit,
) {
    /** Selects event or response preparation while preserving event precedence. */
    suspend fun prepare(
        requestId: String?, eventId: String?, auto: Boolean, private: Boolean,
        manualId: String, expectedVersion: Int = -1, expectedTrigger: Long = -1,
    ): AudioExecution? = when {
        eventId != null -> prepareEvent(eventId, auto, expectedVersion, expectedTrigger)
        requestId != null -> prepareResponse(requestId, auto, private, manualId)
        else -> null
    }

    /** Claims a valid occurrence independently of audio readiness and journals its snapshot. */
    private suspend fun prepareEvent(
        eventId: String, auto: Boolean, expectedVersion: Int, expectedTrigger: Long,
    ): AudioExecution? {
        val dao = database.dailyEventDao()
        val event = dao.get(eventId) ?: return null
        if (auto && (!event.isDueForSpeech() || (expectedVersion >= 0 && !event.matchesAlarm(expectedVersion, expectedTrigger)))) return null
        val workflow = AudioWorkflows.forEvent(event.eventType)
        if (auto && !settings.state.value.allows(workflow)) {
            showDeferred(event)
            return null
        }
        val autoId = "event:${event.id}:${event.version}"
        if (auto && store.get(autoId) != null) return null
        if (!auto) store.all().filter { it.eventId == eventId }
            .forEach { scheduler.cancel(it.id) }
        if (!events.claimEventPlayback(event, manual = !auto)) return null
        val id = if (auto) autoId else "manual:$autoId:${UUID.randomUUID()}"
        currentCoroutineContext().ensureActive()
        dismissDeferred(eventId)
        return AudioExecution(id, workflow, events.workflowSpeechFile(event).absolutePath,
            eventId, event.version, automatic = auto, manualEvent = !auto, eventText = event.spokenText(),
            eventTriggerAt = event.triggerAtMillis(), eventType = event.eventType, eventSpeakAloud = event.speakAloud)
            .also(store::save)
    }

    /** Reserves automatic response identity before fetching audio to prevent duplicate speech. */
    private suspend fun prepareResponse(
        requestId: String, auto: Boolean, private: Boolean, manualId: String,
    ): AudioExecution? {
        if (!auto) {
            val autoId = "response:$requestId"
            val prior = store.get(autoId)
            if (prior != null) scheduler.cancel(autoId)
            else {
                val suppressed = AudioExecution(autoId, AudioWorkflow.BUTLER_RESPONSE, "", requestId = requestId, status = "cancelled")
                if (!store.reserve(suppressed)) scheduler.cancel(autoId)
            }
        }
        val id = if (auto) "response:$requestId" else manualId
        val reservation = if (auto) {
            if (!settings.state.value.responses || store.get(id) != null) return null
            val request = database.butlerConversationDao().request(requestId) ?: return null
            if (runCatching { Instant.parse(request.createdAt).isBefore(Instant.now().minusSeconds(600)) }.getOrDefault(true)) return null
            // Reserve before downloading so FCM, reconciliation, and the audio worker cannot race to announce twice.
            val preparing = AudioExecution(id, AudioWorkflow.BUTLER_RESPONSE, "", requestId = requestId, status = "preparing")
            if (!store.reserve(preparing)) return null
            reserveActiveId(id)
            preparing
        } else null
        val file = withContext(Dispatchers.IO) { withTimeoutOrNull(120_000) {
            var audio = responses.ensureAudio(requestId)
            while (audio == null && !responses.audioUnavailable(requestId)) { delay(2_000); audio = responses.ensureAudio(requestId) }
            audio
        } } ?: run {
            if (auto) scheduler.cancel(id)
            return null
        }
        currentCoroutineContext().ensureActive()
        val execution = AudioExecution(id, AudioWorkflow.BUTLER_RESPONSE, file.absolutePath, requestId = requestId,
            automatic = auto, privateRoute = private,
            createdAt = reservation?.createdAt ?: System.currentTimeMillis())
        return if (reservation == null) execution.also(store::save)
        else execution.takeIf { store.transition(reservation, execution) {} }
    }

}
