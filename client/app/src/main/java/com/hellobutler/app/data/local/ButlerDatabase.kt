package com.hellobutler.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailyPlanEntity::class, DailyEventEntity::class, PendingSyncOperationEntity::class, ButlerRequestEntity::class, ConversationMessageEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class ButlerDatabase : RoomDatabase() {
    abstract fun dailyEventDao(): DailyEventDao
    abstract fun dailyPlanDao(): DailyPlanDao
    abstract fun pendingSyncOperationDao(): PendingSyncOperationDao
    abstract fun butlerConversationDao(): ButlerConversationDao

    companion object {
        @Volatile private var instance: ButlerDatabase? = null

        fun get(context: Context): ButlerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ButlerDatabase::class.java,
                "hello_butler.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_plans (id TEXT NOT NULL PRIMARY KEY, planDate TEXT NOT NULL, status TEXT NOT NULL, updatedAt TEXT NOT NULL)")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN durationMinutes INTEGER")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN scheduledPrecision TEXT")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN content TEXT")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN syncedFromServer INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_events ADD COLUMN playbackAttemptedAt TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_sync_operations (operationId TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, operationType TEXT NOT NULL, baseVersion INTEGER NOT NULL, payload TEXT, createdAt TEXT NOT NULL, attemptCount INTEGER NOT NULL DEFAULT 0, lastError TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_sync_operations_eventId ON pending_sync_operations (eventId)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS butler_requests (requestId TEXT NOT NULL PRIMARY KEY, inputSource TEXT NOT NULL, interactionMode TEXT NOT NULL, submittedText TEXT, localAudioPath TEXT, status TEXT NOT NULL, createdAt TEXT NOT NULL, acceptedAt TEXT, lastError TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_messages (id TEXT NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAt TEXT NOT NULL, deliveryState TEXT NOT NULL, inputSource TEXT NOT NULL, responseAudioUrl TEXT, responseAudioMimeType TEXT, responseAudioDurationMs INTEGER, audioCacheState TEXT NOT NULL, localAudioPath TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_requestId ON conversation_messages (requestId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversation_messages_requestId_role ON conversation_messages (requestId, role)")
            }
        }
    }
}
