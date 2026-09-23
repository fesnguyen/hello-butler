package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT

@Serializable
data class SavedPreferenceDto(val id: String, val content: String)

@Serializable
data class UserSettingsDto(
    @SerialName("display_name") val displayName: String?,
    val email: String?,
    val credits: Int,
    @SerialName("tts_method") val ttsMethod: String,
    val preferences: List<SavedPreferenceDto>,
)

@Serializable
data class UserSettingsUpdateDto(
    @SerialName("display_name") val displayName: String?,
    @SerialName("tts_method") val ttsMethod: String,
    @SerialName("known_preference_ids") val knownPreferenceIds: List<String>,
    @SerialName("preference_ids") val preferenceIds: List<String>,
)

interface UserSettingsApi {
    @GET("api/user-settings")
    suspend fun get(@Header("Authorization") authorization: String): Response<UserSettingsDto>

    @PUT("api/user-settings")
    suspend fun update(
        @Header("Authorization") authorization: String,
        @Body update: UserSettingsUpdateDto,
    ): Response<UserSettingsDto>
}
