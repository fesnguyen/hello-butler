package com.hellobutler.app.core

import android.content.Context
import com.hellobutler.app.BuildConfig
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.remote.AuthApi
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.remote.PlanningApi
import com.hellobutler.app.data.remote.PushApi
import com.hellobutler.app.data.remote.SyncApi
import com.hellobutler.app.data.remote.UserSettingsApi
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.data.repository.UserSettingsRepository
import com.hellobutler.app.execution.DailyEventScheduler
import com.hellobutler.app.sync.PushRegistrationRepository
import com.hellobutler.app.sync.PushRegistrationWorker
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

    val authRepository: AuthRepository = AuthRepository(
        retrofit.create(AuthApi::class.java), sessionStore,
        onAuthenticated = { PushRegistrationWorker.enqueue(context) },
        beforeLogout = { pushRegistration.unregisterCurrent() },
        afterLogout = {
            context.stopService(android.content.Intent(context, com.hellobutler.app.execution.SpeechForegroundService::class.java))
            context.stopService(android.content.Intent(context, com.hellobutler.app.execution.ButlerAudioPlaybackService::class.java))
            dailyEventRepository.clear()
            butlerRepository.clear()
            userSettingsRepository.clear()
        },
    )
    val pushApi: PushApi = retrofit.create(PushApi::class.java)
    val pushRegistration: PushRegistrationRepository = PushRegistrationRepository(context.applicationContext, pushApi, authRepository)
    val dailyEventRepository: DailyEventRepository = DailyEventRepository(
        context.applicationContext,
        database,
        retrofit.create(SyncApi::class.java),
        retrofit.create(PlanningApi::class.java),
        authRepository,
        eventScheduler,
        json,
    )
    val userSettingsRepository = UserSettingsRepository(
        retrofit.create(UserSettingsApi::class.java), authRepository, database.savedContextDao(),
    )
    val butlerRepository = ButlerRepository(
        context.applicationContext, database, retrofit.create(ButlerApi::class.java),
        authRepository, dailyEventRepository, userSettingsRepository,
    )
}
