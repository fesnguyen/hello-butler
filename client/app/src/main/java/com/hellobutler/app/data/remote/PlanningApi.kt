package com.hellobutler.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class PrepareDayRequestDto(
    @SerialName("target_date") val targetDate: String,
)

interface PlanningApi {
    @POST("api/planning/prepare")
    suspend fun prepare(
        @Header("Authorization") authorization: String,
        @Body request: PrepareDayRequestDto,
    ): Response<ResponseBody>
}
