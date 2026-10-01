package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// A cache of the backend's manageable UserContext projection, never an authoritative note store.
@Entity(tableName = "saved_context")
data class SavedContextEntity(
    @PrimaryKey val id: String,
    val content: String,
    val isPreference: Boolean,
    val version: Int,
)

@Dao
abstract class SavedContextDao {
    @Query("SELECT * FROM saved_context ORDER BY id")
    abstract fun observe(): Flow<List<SavedContextEntity>>

    @Upsert
    abstract suspend fun upsert(items: List<SavedContextEntity>)

    @Query("DELETE FROM saved_context WHERE id NOT IN (:ids)")
    abstract suspend fun removeAbsent(ids: List<String>)

    @Query("DELETE FROM saved_context WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM saved_context")
    abstract suspend fun clear()

    @Transaction
    open suspend fun reconcile(items: List<SavedContextEntity>) {
        removeAbsent(items.map { it.id })
        upsert(items)
    }
}
