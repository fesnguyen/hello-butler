package com.hellobutler.app.data.repository

import androidx.room.withTransaction
import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.local.DailyEventDao
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.local.DailyPlanEntity
import com.hellobutler.app.data.remote.DailyPlanSnapshotDto
import com.hellobutler.app.data.remote.SyncApi
import com.hellobutler.app.data.remote.SyncEventDto
import com.hellobutler.app.execution.DailyEventScheduler
import kotlinx.coroutines.flow.Flow
import retrofit2.Response

class DailyEventRepository(
    private val database: ButlerDatabase,
    private val api: SyncApi,
    private val auth: AuthRepository,
    private val scheduler: DailyEventScheduler,
) {
    private val dao: DailyEventDao = database.dailyEventDao()

    fun observeDate(date: String): Flow<List<DailyEventEntity>> = dao.observeDate(date)

    suspend fun update(event: DailyEventEntity) {
        dao.upsert(event) // Server sync attaches here once the sync contract exists.
        scheduler.schedule(event)
    }

    suspend fun refresh(date: String): Boolean {
        var token = auth.accessToken()
        var response = api.dailyPlan("Bearer $token", date)
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = api.dailyPlan("Bearer $token", date)
        }
        val snapshot = body(response)
        val plan = snapshot.plan ?: return false // Absence never erases a previously cached day.
        val previous = dao.getDate(date)
        val attempted = previous.associate { it.id to it.playbackAttemptedAt }
        val current = snapshot.events.map { it.toEntity(attempted[it.id]) }
        database.withTransaction {
            database.dailyPlanDao().upsert(
                DailyPlanEntity(plan.id, plan.planDate, plan.status, plan.updatedAt)
            )
            dao.deleteServerDate(date)
            dao.upsertAll(current)
        }
        scheduler.reconcile(previous, current)
        return true
    }

    suspend fun clear() {
        dao.pendingSpokenEvents().forEach(scheduler::cancel)
        database.withTransaction {
            dao.deleteAll()
            database.dailyPlanDao().deleteAll()
        }
    }

    private fun body(response: Response<DailyPlanSnapshotDto>): DailyPlanSnapshotDto {
        if (!response.isSuccessful) throw ApiException("Plan sync failed (${response.code()})")
        return response.body() ?: throw ApiException("Plan sync returned an empty response")
    }
}

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
