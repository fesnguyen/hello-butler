package com.hellobutler.app.core

import android.content.Context
import com.hellobutler.app.BuildConfig
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.remote.AuthApi
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.remote.PlanningApi
import com.hellobutler.app.data.remote.SyncApi
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.execution.DailyEventScheduler
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS) // Temporary while planning remains a long-running synchronous request.
        .build()
    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BACKEND_BASE_URL)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    private val sessionStore = SecureSessionStore(context)
    val database = ButlerDatabase.get(context)
    val eventScheduler = DailyEventScheduler(context.applicationContext)

    val authRepository = AuthRepository(retrofit.create(AuthApi::class.java), sessionStore)
    val butlerRepository = ButlerRepository(retrofit.create(ButlerApi::class.java), authRepository)
    val dailyEventRepository = DailyEventRepository(
        database,
        retrofit.create(SyncApi::class.java),
        retrofit.create(PlanningApi::class.java),
        authRepository,
        eventScheduler,
    )
}
