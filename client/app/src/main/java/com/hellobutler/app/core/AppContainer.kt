package com.hellobutler.app.core

import android.content.Context
import com.hellobutler.app.BuildConfig
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.remote.AuthApi
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BACKEND_BASE_URL)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    private val sessionStore = SecureSessionStore(context)
    private val database = ButlerDatabase.get(context)

    val authRepository = AuthRepository(retrofit.create(AuthApi::class.java), sessionStore)
    val butlerRepository = ButlerRepository(retrofit.create(ButlerApi::class.java), authRepository)
    val dailyEventRepository = DailyEventRepository(database.dailyEventDao())
}
