package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.PUT

@Serializable
data class PushDeviceRequestDto(
    @SerialName("registration_token") val registrationToken: String,
)

interface PushApi {
    @HTTP(method = "DELETE", path = "api/push/device", hasBody = true)
    suspend fun unregister(
        @Header("Authorization") authorization: String,
        @Body request: PushDeviceRequestDto,
    ): Response<Unit>

    @PUT("api/push/device")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Body request: PushDeviceRequestDto,
    ): Response<Unit>
}
