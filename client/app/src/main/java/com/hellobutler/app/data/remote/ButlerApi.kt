package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class ButlerRequestDto(
    @SerialName("interaction_mode") val interactionMode: String,
    val message: String,
)

@Serializable data class ChangedEntityDto(
    val type: String,
    val id: String,
    @SerialName("plan_dates") val planDates: List<String> = emptyList(),
)

@Serializable
data class ButlerResponseDto(
    val response: String,
    @SerialName("changed_entities") val changedEntities: List<ChangedEntityDto> = emptyList(),
    @SerialName("requires_follow_up") val requiresFollowUp: Boolean = false,
    @Transient val syncPending: Boolean = false,
)

interface ButlerApi {
    @POST("api/butler/talk")
    suspend fun talk(
        @Header("Authorization") authorization: String,
        @Body request: ButlerRequestDto,
    ): Response<ButlerResponseDto>
}
