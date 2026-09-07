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

@Serializable
data class EveningPrepareRequestDto(
    @SerialName("summary_date") val summaryDate: String? = null,
)

@Serializable
data class EveningPreparationResultDto(
    @SerialName("summary_date") val summaryDate: String,
    @SerialName("tomorrow_date") val tomorrowDate: String,
    @SerialName("good_night_summary") val goodNightSummary: String,
    @SerialName("tomorrow_plan_id") val tomorrowPlanId: String,
)

interface PlanningApi {
    @POST("api/planning/prepare")
    suspend fun prepare(
        @Header("Authorization") authorization: String,
        @Body request: PrepareDayRequestDto,
    ): Response<ResponseBody>

    @POST("api/planning/evening-prepare")
    suspend fun prepareEvening(
        @Header("Authorization") authorization: String,
        @Body request: EveningPrepareRequestDto,
    ): Response<EveningPreparationResultDto>
}
