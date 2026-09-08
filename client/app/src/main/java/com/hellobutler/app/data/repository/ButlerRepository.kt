package com.hellobutler.app.data.repository

import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.remote.ButlerRequestDto
import com.hellobutler.app.data.remote.ButlerResponseDto
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import retrofit2.Response

class ButlerRepository(
    private val api: ButlerApi,
    private val auth: AuthRepository,
    private val events: DailyEventRepository,
) {
    suspend fun send(mode: String, message: String): ButlerResponseDto {
        events.flushPending() // Failure aborts reasoning; the outbox remains retryable.
        var token = auth.accessToken()
        var response = api.talk("Bearer $token", ButlerRequestDto(mode, message))
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token) // One refresh wins if requests race.
            response = api.talk("Bearer $token", ButlerRequestDto(mode, message))
        }
        val result = body(response)
        if (result.changedEntities.isNotEmpty()) {
            val today = LocalDate.now()
            val dates = listOf(today.toString(), today.plusDays(1).toString()) +
                result.changedEntities.flatMap { it.planDates }
            events.queueSynchronization(dates) // Persist affected dates even if the UI disappears.
            try {
                events.synchronize(dates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The command succeeded. Do not invite the user to repeat it because PULL failed.
                return result.copy(syncPending = true)
            }
        }
        return result
    }

    private fun body(response: Response<ButlerResponseDto>): ButlerResponseDto {
        if (!response.isSuccessful) throw ApiException("Butler request failed (${response.code()})")
        return response.body() ?: throw ApiException("Butler returned an empty response")
    }
}
