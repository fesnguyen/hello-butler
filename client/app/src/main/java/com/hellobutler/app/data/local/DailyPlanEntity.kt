package com.hellobutler.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_plans")
data class DailyPlanEntity(
    @PrimaryKey val id: String,
    val planDate: String,
    val status: String,
    val updatedAt: String,
)
