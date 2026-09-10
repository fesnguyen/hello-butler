package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyEventDao {
    @Query("SELECT * FROM daily_events WHERE eventDate = :date ORDER BY startTime IS NULL, startTime, sortOrder")
    fun observeDate(date: String): Flow<List<DailyEventEntity>>

    @Query("SELECT * FROM daily_events WHERE eventDate = :date")
    suspend fun getDate(date: String): List<DailyEventEntity>

    @Query("SELECT * FROM daily_events WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DailyEventEntity?

    @Query("SELECT * FROM daily_events WHERE status = 'planned' AND speakAloud = 1 AND playbackAttemptedAt IS NULL AND content IS NOT NULL AND TRIM(content) != ''")
    suspend fun pendingSpokenEvents(): List<DailyEventEntity>

    @Upsert suspend fun upsert(event: DailyEventEntity)
    @Upsert suspend fun upsertAll(events: List<DailyEventEntity>)

    @Query("DELETE FROM daily_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM daily_events WHERE eventDate = :date AND syncedFromServer = 1 AND id NOT IN (SELECT eventId FROM pending_sync_operations)")
    suspend fun deleteServerDateExceptPending(date: String)

    @Query("UPDATE daily_events SET playbackAttemptedAt = :attemptedAt WHERE id = :id AND status = 'planned' AND eventDate = :eventDate AND startTime = :startTime AND content = :content AND version = :version AND playbackAttemptedAt IS NULL AND speakAloud = 1 AND content IS NOT NULL AND TRIM(content) != ''")
    suspend fun claimPlayback(
        id: String, attemptedAt: String, eventDate: String, startTime: String,
        content: String, version: Int,
    ): Int

    @Query("DELETE FROM daily_events")
    suspend fun deleteAll()
}
