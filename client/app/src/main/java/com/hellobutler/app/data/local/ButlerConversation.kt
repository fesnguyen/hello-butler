package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "butler_requests")
data class ButlerRequestEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val inputSource: String,
    val interactionMode: String,
    val submittedText: String?,
    val localAudioPath: String?,
    val status: String,
    val createdAt: String,
    val acceptedAt: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["requestId"]), Index(value = ["requestId", "role"], unique = true)],
)
data class ConversationMessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val requestId: String,
    val role: String,
    val text: String,
    val createdAt: String,
    val deliveryState: String,
    val inputSource: String,
    val responseAudioUrl: String? = null,
    val responseAudioMimeType: String? = null,
    val responseAudioDurationMs: Int? = null,
    val audioCacheState: String = "none",
    val localAudioPath: String? = null,
)

@Dao
interface ButlerConversationDao {
    @Query("SELECT * FROM conversation_messages ORDER BY createdAt ASC")
    fun observeMessages(): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE requestId = :requestId AND role = :role")
    suspend fun message(requestId: String, role: String): ConversationMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ConversationMessageEntity)

    @Query("UPDATE conversation_messages SET deliveryState = :state WHERE requestId = :requestId AND role = 'user'")
    suspend fun updateDelivery(requestId: String, state: String)

    @Query("UPDATE conversation_messages SET audioCacheState = :state, localAudioPath = :path WHERE requestId = :requestId AND role = 'butler'")
    suspend fun updateAudio(requestId: String, state: String, path: String?)

    @Query("UPDATE conversation_messages SET responseAudioDurationMs = :durationMs WHERE requestId = :requestId AND role = 'butler'")
    suspend fun updateAudioDuration(requestId: String, durationMs: Int)

    @Query("SELECT * FROM butler_requests WHERE requestId = :requestId")
    suspend fun request(requestId: String): ButlerRequestEntity?

    @Query("SELECT * FROM butler_requests WHERE status IN ('sending', 'sent', 'processing', 'failed')")
    suspend fun recoverableRequests(): List<ButlerRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(request: ButlerRequestEntity)

    @Query("UPDATE butler_requests SET status = :status, acceptedAt = :acceptedAt, lastError = :error WHERE requestId = :requestId")
    suspend fun updateRequest(requestId: String, status: String, acceptedAt: String?, error: String?)

    @Query("DELETE FROM conversation_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM butler_requests")
    suspend fun clearRequests()
}
