package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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
    @SerialName("known_preference_ids") val knownPreferenceIds: List<String>? = null,
    @SerialName("preference_ids") val preferenceIds: List<String>? = null,
)

@Serializable
data class SavedContextDto(
    val id: String,
    val content: String,
    @SerialName("is_preference") val isPreference: Boolean,
    val version: Int,
)

@Serializable
data class SavedContextUpdateDto(
    val content: String,
    @SerialName("is_preference") val isPreference: Boolean,
    @SerialName("base_version") val baseVersion: Int,
)

interface UserSettingsApi {
    @GET("api/user-settings/saved-context")
    suspend fun listContext(@Header("Authorization") authorization: String): Response<List<SavedContextDto>>

    @PUT("api/user-settings/saved-context/{id}")
    suspend fun saveContext(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body update: SavedContextUpdateDto,
    ): Response<SavedContextDto>

    @DELETE("api/user-settings/saved-context/{id}")
    suspend fun deleteContext(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Query("base_version") version: Int,
    ): Response<Unit>

    @GET("api/user-settings")
    suspend fun get(@Header("Authorization") authorization: String): Response<UserSettingsDto>

    @PUT("api/user-settings")
    suspend fun update(
        @Header("Authorization") authorization: String,
        @Body update: UserSettingsUpdateDto,
    ): Response<UserSettingsDto>
}
