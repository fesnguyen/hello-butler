package com.hellobutler.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_events")
data class DailyEventEntity(
    @PrimaryKey val id: String,
    val dailyPlanId: String?,
    val eventDate: String,
    val title: String,
    val description: String?,
    val eventType: String,
    val status: String,
    val startTime: String?,
    val endTime: String?,
    val reminderMinutesBefore: Int?,
    val speakAloud: Boolean,
    val sortOrder: Int,
    val version: Int,
)
