package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Body

@Serializable
data class SyncPlanDto(
    val id: String,
    @SerialName("plan_date") val planDate: String,
    val status: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SyncEventDto(
    val id: String,
    @SerialName("daily_plan_id") val dailyPlanId: String,
    @SerialName("event_date") val eventDate: String,
    val title: String,
    val description: String?,
    @SerialName("event_type") val eventType: String,
    val status: String,
    @SerialName("start_time") val startTime: String?,
    @SerialName("end_time") val endTime: String?,
    @SerialName("duration_minutes") val durationMinutes: Int?,
    @SerialName("scheduled_precision") val scheduledPrecision: String?,
    val content: String?,
    @SerialName("reminder_minutes_before") val reminderMinutesBefore: Int?,
    @SerialName("speak_aloud") val speakAloud: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
    val version: Int,
    val origin: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class EventMutationDto(
    val id: String,
    @SerialName("event_date") val eventDate: String,
    val title: String,
    val description: String?,
    @SerialName("event_type") val eventType: String,
    val status: String,
    @SerialName("start_time") val startTime: String?,
    @SerialName("end_time") val endTime: String?,
    @SerialName("duration_minutes") val durationMinutes: Int?,
    @SerialName("scheduled_precision") val scheduledPrecision: String?,
    val content: String?,
    @SerialName("reminder_minutes_before") val reminderMinutesBefore: Int?,
    @SerialName("speak_aloud") val speakAloud: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class EventSyncOperationDto(
    @SerialName("operation_id") val operationId: String,
    val action: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("base_version") val baseVersion: Int,
    val event: EventMutationDto? = null,
)

@Serializable data class SyncBatchRequestDto(val operations: List<EventSyncOperationDto>)

@Serializable
data class EventSyncResultDto(
    @SerialName("operation_id") val operationId: String,
    val status: String,
    val event: SyncEventDto?,
)

@Serializable data class SyncBatchResultDto(val results: List<EventSyncResultDto>)

@Serializable
data class DailyPlanSnapshotDto(
    val plan: SyncPlanDto?,
    val events: List<SyncEventDto>,
)

interface SyncApi {
    @POST("api/sync/events")
    suspend fun syncEvents(
        @Header("Authorization") authorization: String,
        @Body request: SyncBatchRequestDto,
    ): Response<SyncBatchResultDto>

    @GET("api/sync/daily-plan/{date}")
    suspend fun dailyPlan(
        @Header("Authorization") authorization: String,
        @Path("date") date: String,
    ): Response<DailyPlanSnapshotDto>
}
