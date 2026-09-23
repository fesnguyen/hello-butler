package com.hellobutler.app.data.repository

import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.remote.UserSettingsApi
import com.hellobutler.app.data.remote.UserSettingsDto
import com.hellobutler.app.data.remote.UserSettingsUpdateDto
import retrofit2.Response

interface UserSettingsDataSource {
    suspend fun get(): UserSettingsDto
    suspend fun save(update: UserSettingsUpdateDto): UserSettingsDto
}

class UserSettingsRepository(
    private val api: UserSettingsApi,
    private val auth: AuthRepository,
) : UserSettingsDataSource {
    override suspend fun get(): UserSettingsDto = body(authorized(api::get))

    override suspend fun save(update: UserSettingsUpdateDto): UserSettingsDto = body(
        authorized { authorization -> api.update(authorization, update) }
    )

    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): Response<T> {
        var token = auth.accessToken()
        var response = call("Bearer $token")
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call("Bearer $token")
        }
        return response
    }

    private fun body(response: Response<UserSettingsDto>): UserSettingsDto {
        if (!response.isSuccessful) {
            throw ApiException("User Settings request failed (${response.code()})", response.code())
        }
        return response.body() ?: throw ApiException("User Settings returned an empty response")
    }
}
