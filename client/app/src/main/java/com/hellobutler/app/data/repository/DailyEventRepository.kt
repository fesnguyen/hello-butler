package com.hellobutler.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.local.DailyEventDao
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.local.DailyPlanEntity
import com.hellobutler.app.data.local.PendingSyncOperationEntity
import com.hellobutler.app.data.remote.DailyPlanSnapshotDto
import com.hellobutler.app.data.remote.EventMutationDto
import com.hellobutler.app.data.remote.EventSyncOperationDto
import com.hellobutler.app.data.remote.PlanningApi
import com.hellobutler.app.data.remote.PrepareDayRequestDto
import com.hellobutler.app.data.remote.SyncApi
import com.hellobutler.app.data.remote.SyncBatchRequestDto
import com.hellobutler.app.data.remote.SyncBatchResultDto
import com.hellobutler.app.data.remote.SyncEventDto
import com.hellobutler.app.data.remote.UpcomingEventDto
import com.hellobutler.app.data.remote.UpcomingEventMutationDto
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import com.hellobutler.app.execution.spokenText
import com.hellobutler.app.execution.DailyEventAudioWorker
import com.hellobutler.app.execution.DailyEventScheduler
import com.hellobutler.app.execution.canPrepareSpeech
import com.hellobutler.app.execution.eveningAlreadyPrepared
import com.hellobutler.app.execution.isDueForSpeech
import com.hellobutler.app.sync.DailySyncWorker
import com.hellobutler.app.speech.isOggOpusContainer
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.Response

/** Owns daily-event persistence and synchronization, including audio-state concurrency boundaries. */
class DailyEventRepository(
    private val context: Context,
    private val database: ButlerDatabase,
    private val api: SyncApi,
    private val planningApi: PlanningApi,
    private val auth: AuthRepository,
    private val scheduler: DailyEventScheduler,
    private val json: Json,
    private val enqueueSync: (List<String>) -> Unit = { DailySyncWorker.enqueue(context, it) },
    private val enqueueAudio: (DailyEventEntity) -> Unit = { DailyEventAudioWorker.enqueue(context, it) },
) {
    private val dao: DailyEventDao = database.dailyEventDao()
    private val pending = database.pendingSyncOperationDao()
    // Lock order: sync -> audio state, or download -> audio state; never acquire either in reverse.
    private val syncMutex = Mutex()
    private val downloadMutex = Mutex()
    private val audioStateMutex = Mutex() // Room/file commits only; never held during network calls.
    private val speechStore = DailyEventSpeechStore(
        context.filesDir, context.cacheDir,
        context.getSharedPreferences("butler_speech_preparation", Context.MODE_PRIVATE),
    )
    private val _upcomingEvents = MutableStateFlow<List<UpcomingEventDto>>(emptyList())
    val upcomingEvents = _upcomingEvents.asStateFlow()

    // Public event and audio operations -----------------------------------------

    /** Exposes Room event updates for the requested day. */
    fun observeDate(date: String): Flow<List<DailyEventEntity>> = dao.observeDate(date)

    /** Local-only lookup; this method must never refresh authentication or fetch audio. */
    fun preparedSpeech(event: DailyEventEntity): File? = speechStore.preparedSpeech(event)

    /** Publishes only for the current unclaimed occurrence; readiness is a local file check. */
    suspend fun offerEventPlayback(
        event: DailyEventEntity,
        publish: (DailyEventEntity, Boolean) -> Boolean,
    ): Boolean = audioStateMutex.withLock {
        val current = dao.get(event.id)
        if (!auth.hasSession() || current == null || current != event || !current.isDueForSpeech()) return@withLock true
        publish(current, preparedSpeech(current) != null)
    }

    /** Returns the stable protected speech path without preparing or claiming playback. */
    fun workflowSpeechFile(event: DailyEventEntity) = speechStore.workflowSpeechFile(event)

    /** Claims an eligible occurrence under the audio-state lock without waiting for prepared speech. */
    suspend fun claimEventPlayback(event: DailyEventEntity, manual: Boolean = false): Boolean = audioStateMutex.withLock {
        val current = dao.get(event.id) ?: return@withLock false
        if (!auth.hasSession() || current != event || current.status in setOf("cancelled", "skipped")) return@withLock false
        if (manual) {
            if (current.status == "planned" && current.playbackAttemptedAt == null) {
                dao.claimPlayback(event.id, Instant.now().toString(), event.eventDate, event.startTime.orEmpty(), event.content.orEmpty(), event.version)
            }
            return@withLock true
        }
        if (!current.isDueForSpeech()) return@withLock false
        dao.claimPlayback(event.id, Instant.now().toString(), event.eventDate, event.startTime.orEmpty(), event.content.orEmpty(), event.version) == 1
    }

    /** Called only at PLAY(<speech>); active snapshots outlive prepared-file cleanup. */
    suspend fun snapshotPreparedSpeech(event: DailyEventEntity): File? = audioStateMutex.withLock {
        val current = dao.get(event.id) ?: return@withLock null
        if (current != event || !current.syncedFromServer || speechStore.invalidatedByVoiceChange(current) ||
            current.status in setOf("cancelled", "skipped")) return@withLock null
        speechStore.snapshot(current)
    }

    /** Snapshots local speech before attempting the existing automatic occurrence claim. */
    suspend fun claimPreparedSpeech(event: DailyEventEntity): File? {
        val file = snapshotPreparedSpeech(event) ?: return null
        return file.takeIf { claimEventPlayback(event) }
    }

    /** Joins active preparation or attempts one transfer with the existing absolute recovery deadline. */
    suspend fun recoverSpeech(eventId: String, version: Int): File? {
        if (downloadMutex.isLocked) return downloadMutex.withLock {
            dao.get(eventId)?.takeIf { it.version == version }?.let(::preparedSpeech)
        }
        return speechAudio(eventId, version, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
    }

    /** Removes unusable prepared and snapshot files under the shared audio-state lock. */
    suspend fun discardPreparedSpeech(event: DailyEventEntity) = audioStateMutex.withLock {
        speechStore.discard(event)
    }

    /** Downloads versioned speech and publishes it only after locked canonical-state validation. */
    suspend fun speechAudio(eventId: String, version: Int, transferDeadlineNanos: Long? = null): File? = downloadMutex.withLock {
        val event = dao.get(eventId) ?: return@withLock null
        if (event.version != version || !event.canPrepareSpeech() || pending.forEvent(eventId) != null) return@withLock null
        preparedSpeech(event)?.let { return@withLock it }
        val preparation = authorized { token -> api.prepareEventSpeech("Bearer $token", eventId, version) }
        if (!preparation.isSuccessful) throw ApiException("Event preparation failed (${preparation.code()})", preparation.code())
        when (preparation.body()?.audioStatus) {
            "pending", "processing" -> return@withLock null
            "ready" -> Unit
            else -> throw ApiException("Unexpected event speech state", 424)
        }
        val response = authorized { token -> api.eventAudio("Bearer $token", eventId, version) }
        if (response.code() == 202) { response.body()?.close(); return@withLock null }
        if (!response.isSuccessful) throw ApiException("Event speech failed (${response.code()})", response.code())
        if (response.headers()["X-Event-Version"] != version.toString()) {
            response.body()?.close()
            throw ApiException("Event audio version mismatch", 409)
        }
        val partial = speechStore.partial(eventId, version)
        try {
            val body = response.body() ?: return@withLock null
            // Coroutine cancellation alone cannot interrupt a blocking streaming read.
            // Recovery uses one absolute deadline across preparation, headers and body.
            if (transferDeadlineNanos != null) body.source().timeout().deadlineNanoTime(transferDeadlineNanos)
            body.use { source -> source.byteStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } } }
            if (!partial.isOggOpusContainer()) throw java.io.IOException("Invalid event speech audio")
            currentCoroutineContext().ensureActive()
            audioStateMutex.withLock publish@{
                val current = dao.get(eventId)
                if (!auth.hasSession() || current == null || current.copy(playbackAttemptedAt = event.playbackAttemptedAt) != event || !current.canPrepareSpeech() || pending.forEvent(eventId) != null) return@publish null
                speechStore.publish(partial, eventId, version)
            }
        } finally {
            partial.delete()
        }
    }

    /** Repairs prepared-file cleanup and missing preparation work under the audio-state lock. */
    suspend fun recoverAudioPreparation() = audioStateMutex.withLock {
        speechStore.clearLegacyCache()
        val events = dao.all()
        speechStore.recover(events)
        events.forEach { reconcileAudio(null, it) }
    }

    /** Invalidates old voice identities before cancellation, cleanup, and canonical synchronization. */
    suspend fun reconcileTtsMethod(method: String) = audioStateMutex.withLock {
        val previous = speechStore.ttsMethod()
        if (previous != null && previous != method) {
            val events = dao.all()
            speechStore.invalidate(events)
            events.forEach { ButlerAudioPlaybackService.cancelEvent(context, it.id) }
            DailyEventAudioWorker.clear(context)
            speechStore.deletePrepared()
            queueSynchronization(events.map { it.eventDate })
        }
        speechStore.saveTtsMethod(method)
    }

    /** Persists a local mutation and its outbox operation before reconciling alarms and preparation. */
    suspend fun update(requested: DailyEventEntity) = syncMutex.withLock {
        audioStateMutex.withLock {
            val current = dao.get(requested.id)
            val action = when {
                current == null -> "create"
                requested.status == "completed" && current.status != "completed" -> "complete"
                requested.status == "skipped" && current.status != "skipped" -> "skip"
                requested.status == "cancelled" && current.status != "cancelled" -> "cancel"
                requested.startTime != current.startTime -> "delay"
                else -> "edit"
            }
            queue(requested.retimeSpeech(current), current, action)
        }
    }

    /** Queues canonical deletion while removing local state and reconciling execution effects. */
    suspend fun delete(event: DailyEventEntity) = syncMutex.withLock {
        audioStateMutex.withLock {
            val existing = pending.forEvent(event.id)
            database.withTransaction {
                dao.delete(event.id)
                if (existing?.operationType == "create") {
                    pending.delete(existing.operationId)
                } else {
                    pending.upsert(
                        PendingSyncOperationEntity(
                            operationId = existing?.operationId ?: UUID.randomUUID().toString(),
                            eventId = event.id,
                            operationType = "delete",
                            baseVersion = existing?.baseVersion ?: event.version,
                            payload = null,
                            createdAt = existing?.createdAt ?: Instant.now().toString(),
                        )
                    )
                }
            }
            scheduler.cancel(event)
            reconcileAudio(event, null)
            enqueueSync(emptyList())
        }
    }

    // This lock covers upload only, never the AI request. Direct edits keep their local path.
    /** Serializes pending uploads without holding the synchronization lock over an AI request. */
    suspend fun flushPending() = syncMutex.withLock { pushPending() }

    /** Requests background reconciliation through the existing synchronization worker. */
    fun queueSynchronization(dates: List<String>) = enqueueSync(dates)

    /** Uploads pending edits before pulling each distinct requested date. */
    suspend fun synchronize(dates: List<String>): Set<String> = syncMutex.withLock {
        pushPending()
        val pulled = mutableSetOf<String>()
        for (date in dates.distinct()) {
            if (pull(date)) pulled += date
        }
        pulled
    }

    /** Flushes local edits before requesting and reconciling a regenerated daily plan. */
    suspend fun recreatePlan(date: String) = syncMutex.withLock {
        pushPending()
        val response = authorized { token ->
            planningApi.prepare("Bearer $token", PrepareDayRequestDto(date))
        }
        if (!response.isSuccessful) throw ApiException("Plan preparation failed (${response.code()})")
        if (!pull(date)) throw ApiException("Prepared plan was not available for sync")
    }

    /** Updates an authoritative upcoming occurrence and refreshes its projected list. */
    suspend fun mutateUpcomingEvent(
        event: UpcomingEventDto,
        action: String,
        scope: String,
        title: String? = null,
        startsOn: String? = null,
        endsOn: String? = null,
        startTime: String? = null,
        endTime: String? = null,
    ) = syncMutex.withLock {
        val response = authorized { token ->
            api.mutateUpcomingEvent(
                "Bearer $token",
                event.sourceContextId,
                UpcomingEventMutationDto(
                    action = action,
                    scope = scope,
                    baseVersion = event.sourceContextVersion,
                    occurrenceDate = event.occurrenceDate,
                    title = title,
                    startsOn = startsOn,
                    endsOn = endsOn,
                    startTime = startTime,
                    endTime = endTime,
                ),
            )
        }
        if (!response.isSuccessful) {
            throw ApiException("Upcoming Event update failed (${response.code()})")
        }
        _upcomingEvents.value = response.body()?.upcomingEvents
            ?: throw ApiException("Upcoming Event update returned an empty response")
    }

    /** Flushes edits before preparing the evening summary and reconciling both affected days. */
    suspend fun prepareEvening(today: String, tomorrow: String) = syncMutex.withLock {
        pushPending()
        val preferences = context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE)
        if (!eveningAlreadyPrepared(preferences.getString("prepared_date", null), today)) {
            val response = authorized { token ->
                planningApi.prepareEvening(
                    "Bearer $token",
                    com.hellobutler.app.data.remote.EveningPrepareRequestDto(today),
                )
            }
            if (!response.isSuccessful) throw ApiException("Evening preparation failed (${response.code()})")
            // Record server success before pulling: a failed pull must not regenerate the plan.
            check(preferences.edit().putString("prepared_date", today).commit())
        }
        if (!pull(today) || !pull(tomorrow)) throw ApiException("Evening plans were not available for sync")
    }

    /** Cancels preparation and alarms before clearing event, outbox, and speech state under existing locks. */
    suspend fun clear() = syncMutex.withLock {
        audioStateMutex.withLock {
            DailyEventAudioWorker.clear(context)
            speechStore.clear()
            check(context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE).edit().clear().commit())
            dao.pendingSpokenEvents().forEach(scheduler::cancel)
            database.withTransaction {
                pending.deleteAll()
                dao.deleteAll()
                database.dailyPlanDao().deleteAll()
                _upcomingEvents.value = emptyList()
            }
        }
    }

    // Locked reconciliation and synchronization internals -----------------------

    /** Coordinates cancellation and preparation around storage reconciliation while the caller owns the state lock. */
    private fun reconcileAudio(previous: DailyEventEntity?, current: DailyEventEntity?) {
        val id = current?.id ?: previous?.id ?: return
        if (previous != null && previous != current) {
            DailyEventAudioWorker.cancel(context, id)
            if (current == null || previous.copy(playbackAttemptedAt = current.playbackAttemptedAt) != current) {
                ButlerAudioPlaybackService.cancelEvent(context, id)
            }
            speechStore.rebind(previous, current)
        }
        val keep = speechStore.reconcile(previous, current)
        if (current != null && current.canPrepareSpeech() && keep != null && preparedSpeech(current) == null) enqueueAudio(current)
    }

    /** Atomically records an event and its merged outbox operation before scheduling local effects. */
    private suspend fun queue(
        requested: DailyEventEntity,
        current: DailyEventEntity?,
        action: String,
    ) {
        val existing = pending.forEvent(requested.id)
        val baseVersion = existing?.baseVersion ?: current?.version ?: 0
        val mergedAction = if (existing?.operationType == "create") "create" else action
        val local = requested.copy(
            version = baseVersion,
            origin = "user",
            syncedFromServer = false,
            playbackAttemptedAt = requested.retainedPlaybackAttempt(current),
        )
        database.withTransaction {
            dao.upsert(local)
            pending.upsert(
                PendingSyncOperationEntity(
                    operationId = existing?.operationId ?: UUID.randomUUID().toString(),
                    eventId = local.id,
                    operationType = mergedAction,
                    baseVersion = baseVersion,
                    payload = json.encodeToString(local.toMutation()),
                    createdAt = existing?.createdAt ?: Instant.now().toString(),
                )
            )
        }
        scheduler.schedule(local)
        reconcileAudio(current, local)
        enqueueSync(emptyList())
    }

    /** Uploads ordered batches and preserves failed operations for retry. */
    private suspend fun pushPending() {
        while (true) {
            val rows = pending.next()
            if (rows.isEmpty()) return
            val operations = syncOperations(rows)
            val response = runCatching {
                authorized { token -> api.syncEvents("Bearer $token", SyncBatchRequestDto(operations)) }
            }.getOrElse { error ->
                pending.recordFailure(rows.map { it.operationId }, error.message ?: "Network failure")
                throw error
            }
            reconcileResults(syncBody(response), rows.associate { it.operationId to it.eventId })
        }
    }

    /** Maps persisted operations without altering their identity, version, or payload semantics. */
    private fun syncOperations(rows: List<PendingSyncOperationEntity>): List<EventSyncOperationDto> =
        rows.map { row ->
            EventSyncOperationDto(
                operationId = row.operationId,
                action = row.operationType,
                eventId = row.eventId,
                baseVersion = row.baseVersion,
                event = row.payload?.let { json.decodeFromString<EventMutationDto>(it) },
            )
        }

    /** Applies batch acknowledgements sequentially using the original per-result transaction boundary. */
    private suspend fun reconcileResults(
        batch: SyncBatchResultDto,
        operationEventIds: Map<String, String>,
    ) {
        for (result in batch.results) {
            reconcileResult(result, operationEventIds)
        }
    }

    /** Applies one canonical acknowledgement before reconciling alarms and prepared audio. */
    private suspend fun reconcileResult(
        result: com.hellobutler.app.data.remote.EventSyncResultDto,
        operationEventIds: Map<String, String>,
    ) {
        audioStateMutex.withLock {
            val eventId = result.event?.id ?: operationEventIds[result.operationId]
            val old = eventId?.let { dao.get(it) }
            database.withTransaction {
                val canonical = result.event
                if (canonical == null || canonical.deletedAt != null) {
                    old?.let { dao.delete(it.id) }
                } else {
                    dao.upsert(canonical.toEntity(old))
                }
                pending.delete(result.operationId)
            }
            old?.let(scheduler::cancel)
            result.event?.takeIf { it.deletedAt == null }?.let {
                dao.get(it.id)?.let(scheduler::schedule)
            }
            reconcileAudio(old, eventId?.let { dao.get(it) })
        }
    }

    /** Applies a canonical day snapshot without overwriting pending local edits. */
    private suspend fun pull(date: String): Boolean {
        val snapshot = planBody(authorized { token -> api.dailyPlan("Bearer $token", date) })
        if (date == java.time.LocalDate.now().toString()) {
            _upcomingEvents.value = snapshot.upcomingEvents
        }
        val plan = snapshot.plan ?: return false
        audioStateMutex.withLock {
            val previous = dao.getDate(date)
            val pendingIds = pending.eventIds().toSet()
            val byId = previous.associateBy { it.id }
            val incoming = snapshot.events.filterNot { it.id in pendingIds }
                .map { it.toEntity(byId[it.id]) }
            database.withTransaction {
                database.dailyPlanDao().upsert(
                    DailyPlanEntity(plan.id, plan.planDate, plan.status, plan.updatedAt)
                )
                dao.deleteServerDateExceptPending(date)
                dao.upsertAll(incoming)
            }
            scheduler.reconcile(previous, dao.getDate(date))
            val current = dao.getDate(date)
            previous.filter { old -> current.none { it.id == old.id } }.forEach { reconcileAudio(it, null) }
            current.forEach { event -> reconcileAudio(previous.find { it.id == event.id }, event) }
        }
        return true
    }

    /** Retries an API operation once after refreshing a rejected access token. */
    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): Response<T> {
        var token = auth.accessToken()
        var response = call(token)
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call(token)
        }
        return response
    }

    /** Validates a plan response before exposing its canonical snapshot. */
    private fun planBody(response: Response<DailyPlanSnapshotDto>): DailyPlanSnapshotDto {
        if (!response.isSuccessful) throw ApiException("Plan sync failed (${response.code()})")
        return response.body() ?: throw ApiException("Plan sync returned an empty response")
    }

    /** Validates an upload response before applying its acknowledgements. */
    private fun syncBody(response: Response<SyncBatchResultDto>): SyncBatchResultDto {
        if (!response.isSuccessful) throw ApiException("Event sync failed (${response.code()})")
        return response.body() ?: throw ApiException("Event sync returned an empty response")
    }
}

/** Maps local editable fields into the existing synchronization contract. */
private fun DailyEventEntity.toMutation() = EventMutationDto(
    id, eventDate, title, description, eventType, status, startTime, endTime,
    durationMinutes, scheduledPrecision, content, reminderMinutesBefore, speakAloud, sortOrder,
)

/** Builds canonical Room state while retaining only a compatible playback attempt. */
private fun SyncEventDto.toEntity(previous: DailyEventEntity?) = DailyEventEntity(
    id = id,
    dailyPlanId = dailyPlanId,
    eventDate = eventDate,
    title = title,
    description = description,
    eventType = eventType,
    status = status,
    startTime = startTime,
    endTime = endTime,
    durationMinutes = durationMinutes,
    scheduledPrecision = scheduledPrecision,
    content = content,
    reminderMinutesBefore = reminderMinutesBefore,
    speakAloud = speakAloud,
    sortOrder = sortOrder,
    version = version,
    origin = origin,
    syncedFromServer = true,
    playbackAttemptedAt = null,
).let { it.copy(playbackAttemptedAt = it.retainedPlaybackAttempt(previous)) }

/** Retains an occurrence claim only when its timing, eligibility, and spoken content remain compatible. */
internal fun DailyEventEntity.retainedPlaybackAttempt(previous: DailyEventEntity?): String? {
    if (previous == null) return null
    val text = content?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() } ?: title
    val oldText = previous.content?.takeIf { it.isNotBlank() } ?: previous.description?.takeIf { it.isNotBlank() } ?: previous.title
    return previous.playbackAttemptedAt.takeIf {
        eventDate == previous.eventDate && startTime == previous.startTime && eventType == previous.eventType &&
            reminderMinutesBefore == previous.reminderMinutesBefore && speakAloud == previous.speakAloud && text == oldText
    }
}

/** Retiming changes only a literal reference to this event's previous clock time. */
internal fun DailyEventEntity.retimeSpeech(previous: DailyEventEntity?): DailyEventEntity {
    if (previous == null || startTime == previous.startTime || spokenText() != previous.spokenText() ||
        eventType in setOf("morning_brief", "good_night_summary")) return this
    val old = runCatching { java.time.LocalTime.parse(previous.startTime) }.getOrNull() ?: return this
    val new = runCatching { java.time.LocalTime.parse(startTime) }.getOrNull() ?: return this
    val marker = if (old.hour < 12) "AM" else "PM"
    val minute = if (old.minute == 0) "(?::00)?" else ":%02d".format(old.minute)
    val hour = old.hour % 12
    val oldHour = if (hour == 0) 12 else hour
    val pattern = Regex("(?<![\\w:])(?:${Regex.escape(old.toString())}|$oldHour$minute\\s*$marker)(?![\\w:])", RegexOption.IGNORE_CASE)
    val text = pattern.replace(spokenText()) { match ->
        if (!match.value.contains("am", true) && !match.value.contains("pm", true)) new.toString()
        else {
            var suffix = if (new.hour < 12) "AM" else "PM"
            if (match.value == match.value.lowercase()) suffix = suffix.lowercase()
            val minutes = if (new.minute != 0 || ':' in match.value) ":%02d".format(new.minute) else ""
            val space = if (' ' in match.value) " " else ""
            "${if (new.hour % 12 == 0) 12 else new.hour % 12}$minutes$space$suffix"
        }
    }
    return when { !content.isNullOrBlank() -> copy(content = text); !description.isNullOrBlank() -> copy(description = text); else -> copy(title = text) }
}
