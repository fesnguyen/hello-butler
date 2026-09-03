package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable data class EmailAuthRequest(val email: String, val password: String)
@Serializable data class GoogleAuthRequest(@SerialName("id_token") val idToken: String)
@Serializable data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)
@Serializable data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthTokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
)

interface AuthApi {
    @POST("api/auth/register") suspend fun register(@Body request: EmailAuthRequest): Response<AuthTokensDto>
    @POST("api/auth/login") suspend fun login(@Body request: EmailAuthRequest): Response<AuthTokensDto>
    @POST("api/auth/google") suspend fun google(@Body request: GoogleAuthRequest): Response<AuthTokensDto>
    @POST("api/auth/refresh") suspend fun refresh(@Body request: RefreshRequest): Response<AuthTokensDto>
    @POST("api/auth/logout") suspend fun logout(@Body request: LogoutRequest): Response<Unit>
}
