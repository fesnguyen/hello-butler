package com.hellobutler.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DailyEventEntity::class], version = 1, exportSchema = false)
abstract class ButlerDatabase : RoomDatabase() {
    abstract fun dailyEventDao(): DailyEventDao

    companion object {
        @Volatile private var instance: ButlerDatabase? = null

        fun get(context: Context): ButlerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ButlerDatabase::class.java,
                "hello_butler.db",
            ).build().also { instance = it }
        }
    }
}
