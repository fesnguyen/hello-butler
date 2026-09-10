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
import com.hellobutler.app.execution.DailyEventScheduler
import com.hellobutler.app.sync.DailySyncWorker
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.Response

class DailyEventRepository(
    private val context: Context,
    private val database: ButlerDatabase,
    private val api: SyncApi,
    private val planningApi: PlanningApi,
    private val auth: AuthRepository,
    private val scheduler: DailyEventScheduler,
    private val json: Json,
    private val enqueueSync: (List<String>) -> Unit = { DailySyncWorker.enqueue(context, it) },
) {
    private val dao: DailyEventDao = database.dailyEventDao()
    private val pending = database.pendingSyncOperationDao()
    private val syncMutex = Mutex()

    fun observeDate(date: String): Flow<List<DailyEventEntity>> = dao.observeDate(date)

    suspend fun update(requested: DailyEventEntity) = syncMutex.withLock {
        val current = dao.get(requested.id)
        val action = when {
            current == null -> "create"
            requested.status == "completed" && current.status != "completed" -> "complete"
            requested.status == "skipped" && current.status != "skipped" -> "skip"
            requested.status == "cancelled" && current.status != "cancelled" -> "cancel"
            requested.startTime != current.startTime -> "delay"
            else -> "edit"
        }
        queue(requested, current, action)
    }

    suspend fun delete(event: DailyEventEntity) = syncMutex.withLock {
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
        enqueueSync(emptyList())
    }

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
            playbackAttemptedAt = if (
                current == null || requested.startTime != current.startTime ||
                requested.content != current.content || requested.speakAloud != current.speakAloud
            ) null else requested.playbackAttemptedAt,
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
        enqueueSync(emptyList())
    }

    // This lock covers upload only, never the AI request. Direct edits keep their local path.
    suspend fun flushPending() = syncMutex.withLock { pushPending() }

    fun queueSynchronization(dates: List<String>) = enqueueSync(dates)

    suspend fun synchronize(dates: List<String>): Set<String> = syncMutex.withLock {
        pushPending()
        val pulled = mutableSetOf<String>()
        for (date in dates.distinct()) {
            if (pull(date)) pulled += date
        }
        pulled
    }

    suspend fun recreatePlan(date: String) = syncMutex.withLock {
        pushPending()
        val response = authorized { token ->
            planningApi.prepare("Bearer $token", PrepareDayRequestDto(date))
        }
        if (!response.isSuccessful) throw ApiException("Plan preparation failed (${response.code()})")
        if (!pull(date)) throw ApiException("Prepared plan was not available for sync")
    }

    suspend fun prepareEvening(today: String, tomorrow: String) = syncMutex.withLock {
        pushPending()
        val response = authorized { token ->
            planningApi.prepareEvening(
                "Bearer $token",
                com.hellobutler.app.data.remote.EveningPrepareRequestDto(today),
            )
        }
        if (!response.isSuccessful) throw ApiException("Evening preparation failed (${response.code()})")
        pull(today)
        pull(tomorrow)
    }

    private suspend fun pushPending() {
        while (true) {
            val rows = pending.next()
            if (rows.isEmpty()) return
            val operations = rows.map { row ->
                EventSyncOperationDto(
                    operationId = row.operationId,
                    action = row.operationType,
                    eventId = row.eventId,
                    baseVersion = row.baseVersion,
                    event = row.payload?.let { json.decodeFromString<EventMutationDto>(it) },
                )
            }
            val response = runCatching {
                authorized { token -> api.syncEvents("Bearer $token", SyncBatchRequestDto(operations)) }
            }.getOrElse { error ->
                pending.recordFailure(rows.map { it.operationId }, error.message ?: "Network failure")
                throw error
            }
            reconcileResults(syncBody(response), rows.associate { it.operationId to it.eventId })
        }
    }

    private suspend fun reconcileResults(
        batch: SyncBatchResultDto,
        operationEventIds: Map<String, String>,
    ) {
        for (result in batch.results) {
            val eventId = result.event?.id ?: operationEventIds[result.operationId]
            val old = eventId?.let { dao.get(it) }
            database.withTransaction {
                val canonical = result.event
                if (canonical == null || canonical.deletedAt != null) {
                    old?.let { dao.delete(it.id) }
                } else {
                    dao.upsert(canonical.toEntity(old?.playbackAttemptedAt))
                }
                pending.delete(result.operationId)
            }
            old?.let(scheduler::cancel)
            result.event?.takeIf { it.deletedAt == null }?.let {
                scheduler.schedule(it.toEntity(old?.playbackAttemptedAt))
            }
        }
    }

    private suspend fun pull(date: String): Boolean {
        val snapshot = planBody(authorized { token -> api.dailyPlan("Bearer $token", date) })
        val plan = snapshot.plan ?: return false
        val previous = dao.getDate(date)
        val pendingIds = pending.eventIds().toSet()
        val attempted = previous.associate { it.id to it.playbackAttemptedAt }
        val incoming = snapshot.events.filterNot { it.id in pendingIds }
            .map { it.toEntity(attempted[it.id]) }
        database.withTransaction {
            database.dailyPlanDao().upsert(
                DailyPlanEntity(plan.id, plan.planDate, plan.status, plan.updatedAt)
            )
            dao.deleteServerDateExceptPending(date)
            dao.upsertAll(incoming)
        }
        scheduler.reconcile(previous, dao.getDate(date))
        return true
    }

    suspend fun clear() = syncMutex.withLock {
        dao.pendingSpokenEvents().forEach(scheduler::cancel)
        database.withTransaction {
            pending.deleteAll()
            dao.deleteAll()
            database.dailyPlanDao().deleteAll()
        }
    }

    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): Response<T> {
        var token = auth.accessToken()
        var response = call(token)
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call(token)
        }
        return response
    }

    private fun planBody(response: Response<DailyPlanSnapshotDto>): DailyPlanSnapshotDto {
        if (!response.isSuccessful) throw ApiException("Plan sync failed (${response.code()})")
        return response.body() ?: throw ApiException("Plan sync returned an empty response")
    }

    private fun syncBody(response: Response<SyncBatchResultDto>): SyncBatchResultDto {
        if (!response.isSuccessful) throw ApiException("Event sync failed (${response.code()})")
        return response.body() ?: throw ApiException("Event sync returned an empty response")
    }
}

private fun DailyEventEntity.toMutation() = EventMutationDto(
    id, eventDate, title, description, eventType, status, startTime, endTime,
    durationMinutes, scheduledPrecision, content, reminderMinutesBefore, speakAloud, sortOrder,
)

private fun SyncEventDto.toEntity(playbackAttemptedAt: String?) = DailyEventEntity(
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
    playbackAttemptedAt = playbackAttemptedAt,
)
