package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

@Serializable
data class ButlerTextRequestDto(@SerialName("request_id") val requestId: String, val message: String)

@Serializable
data class ButlerAcceptedDto(@SerialName("request_id") val requestId: String, val status: String)

@Serializable
data class ChangedEntityDto(
    val type: String,
    val id: String,
    @SerialName("plan_dates") val planDates: List<String> = emptyList(),
)

@Serializable
data class ButlerResultDto(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("input_source") val inputSource: String,
    @SerialName("interaction_mode") val interactionMode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("user_message_text") val userMessageText: String? = null,
    @SerialName("response_text") val responseText: String? = null,
    @SerialName("response_audio_url") val responseAudioUrl: String? = null,
    @SerialName("response_audio_mime_type") val responseAudioMimeType: String? = null,
    @SerialName("response_audio_duration_ms") val responseAudioDurationMs: Int? = null,
    @SerialName("changed_entities") val changedEntities: List<ChangedEntityDto> = emptyList(),
    @SerialName("requires_follow_up") val requiresFollowUp: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
    val warnings: List<String> = emptyList(),
)

interface ButlerApi {
    @GET("api/butler/requests")
    suspend fun recent(@Header("Authorization") authorization: String): Response<List<ButlerResultDto>>

    @POST("api/butler/requests/text")
    suspend fun text(@Header("Authorization") authorization: String, @Body request: ButlerTextRequestDto): Response<ButlerAcceptedDto>

    @Multipart
    @POST("api/butler/requests/audio")
    suspend fun audio(
        @Header("Authorization") authorization: String,
        @Part("request_id") requestId: RequestBody,
        @Part("interaction_mode") interactionMode: RequestBody,
        @Part audio: MultipartBody.Part,
    ): Response<ButlerAcceptedDto>

    @GET("api/butler/requests/{requestId}")
    suspend fun result(@Header("Authorization") authorization: String, @Path("requestId") requestId: String): Response<ButlerResultDto>

    @Streaming
    @GET("api/butler/requests/{requestId}/audio")
    suspend fun responseAudio(@Header("Authorization") authorization: String, @Path("requestId") requestId: String): Response<ResponseBody>
}
