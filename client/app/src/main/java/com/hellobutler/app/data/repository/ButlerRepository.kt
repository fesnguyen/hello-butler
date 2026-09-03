package com.hellobutler.app.data.repository

import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.remote.ButlerRequestDto
import com.hellobutler.app.data.remote.ButlerResponseDto
import retrofit2.Response

class ButlerRepository(
    private val api: ButlerApi,
    private val auth: AuthRepository,
) {
    suspend fun send(mode: String, message: String): ButlerResponseDto {
        var token = auth.accessToken()
        var response = api.talk("Bearer $token", ButlerRequestDto(mode, message))
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token) // One refresh wins if requests race.
            response = api.talk("Bearer $token", ButlerRequestDto(mode, message))
        }
        return body(response)
    }

    private fun body(response: Response<ButlerResponseDto>): ButlerResponseDto {
        if (!response.isSuccessful) throw ApiException("Butler request failed (${response.code()})")
        return response.body() ?: throw ApiException("Butler returned an empty response")
    }
}
