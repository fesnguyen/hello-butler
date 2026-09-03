package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyEventDao {
    @Query("SELECT * FROM daily_events WHERE eventDate = :date ORDER BY startTime IS NULL, startTime, sortOrder")
    fun observeDate(date: String): Flow<List<DailyEventEntity>>

    @Upsert suspend fun upsert(event: DailyEventEntity)
    @Upsert suspend fun upsertAll(events: List<DailyEventEntity>)

    @Query("DELETE FROM daily_events WHERE id = :id")
    suspend fun delete(id: String)
}
