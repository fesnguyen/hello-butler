package com.hellobutler.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_sync_operations",
    indices = [Index(value = ["eventId"], unique = true)],
)
data class PendingSyncOperationEntity(
    @PrimaryKey val operationId: String,
    val eventId: String,
    val operationType: String,
    val baseVersion: Int,
    val payload: String?,
    val createdAt: String,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
