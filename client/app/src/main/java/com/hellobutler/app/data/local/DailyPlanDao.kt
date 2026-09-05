package com.hellobutler.app.data.local

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Query

@Dao
interface DailyPlanDao {
    @Upsert suspend fun upsert(plan: DailyPlanEntity)

    @Query("DELETE FROM daily_plans")
    suspend fun deleteAll()
}
