package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
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
    @SerialName("upcoming_events") val upcomingEvents: List<UpcomingEventDto> = emptyList(),
)

@Serializable
data class UpcomingEventDto(
    val id: String,
    @SerialName("source_context_id") val sourceContextId: String,
    @SerialName("source_context_version") val sourceContextVersion: Int,
    @SerialName("occurrence_date") val occurrenceDate: String?,
    val title: String,
    val description: String,
    @SerialName("starts_on") val startsOn: String,
    @SerialName("ends_on") val endsOn: String,
    @SerialName("start_time") val startTime: String?,
    @SerialName("end_time") val endTime: String?,
    val recurring: Boolean,
)

@Serializable
data class UpcomingEventMutationDto(
    val action: String,
    val scope: String,
    @SerialName("base_version") val baseVersion: Int,
    @SerialName("occurrence_date") val occurrenceDate: String? = null,
    val title: String? = null,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
)

@Serializable
data class UpcomingMutationResultDto(
    @SerialName("source_context_id") val sourceContextId: String,
    @SerialName("source_context_version") val sourceContextVersion: Int,
    @SerialName("upcoming_events") val upcomingEvents: List<UpcomingEventDto>,
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

    @PUT("api/sync/upcoming-events/{contextId}")
    suspend fun mutateUpcomingEvent(
        @Header("Authorization") authorization: String,
        @Path("contextId") contextId: String,
        @Body mutation: UpcomingEventMutationDto,
    ): Response<UpcomingMutationResultDto>
}
