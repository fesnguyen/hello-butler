package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PendingSyncOperationDao {
    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAt LIMIT :limit")
    suspend fun next(limit: Int = 50): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE eventId = :eventId LIMIT 1")
    suspend fun forEvent(eventId: String): PendingSyncOperationEntity?

    @Query("SELECT eventId FROM pending_sync_operations")
    suspend fun eventIds(): List<String>

    @Upsert suspend fun upsert(operation: PendingSyncOperationEntity)

    @Query("DELETE FROM pending_sync_operations WHERE operationId = :operationId")
    suspend fun delete(operationId: String)

    @Query("UPDATE pending_sync_operations SET attemptCount = attemptCount + 1, lastError = :message WHERE operationId IN (:operationIds)")
    suspend fun recordFailure(operationIds: List<String>, message: String)

    @Query("DELETE FROM pending_sync_operations")
    suspend fun deleteAll()
}
