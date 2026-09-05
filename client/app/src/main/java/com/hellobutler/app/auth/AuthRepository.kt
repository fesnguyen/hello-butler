package com.hellobutler.app.auth

import com.hellobutler.app.data.remote.AuthApi
import com.hellobutler.app.data.remote.AuthTokensDto
import com.hellobutler.app.data.remote.EmailAuthRequest
import com.hellobutler.app.data.remote.GoogleAuthRequest
import com.hellobutler.app.data.remote.LogoutRequest
import com.hellobutler.app.data.remote.RefreshRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

class AuthRepository(
    private val api: AuthApi,
    private val session: SecureSessionStore,
) {
    private val refreshMutex = Mutex()

    fun hasSession(): Boolean = session.refreshToken() != null

    suspend fun restoreSession(): Boolean {
        if (session.refreshToken() == null) return false
        return runCatching { refreshMutex.withLock { refreshNow() } }.fold(
            onSuccess = { true },
            onFailure = { session.clear(); false },
        )
    }

    suspend fun login(email: String, password: String) =
        accept(api.login(EmailAuthRequest(email.trim(), password)))

    suspend fun register(email: String, password: String) =
        accept(api.register(EmailAuthRequest(email.trim(), password)))

    suspend fun google(idToken: String) = accept(api.google(GoogleAuthRequest(idToken)))

    suspend fun accessToken(): String = session.accessToken ?: refreshMutex.withLock {
        session.accessToken ?: refreshNow()
    }

    suspend fun refreshAfterUnauthorized(failedToken: String): String = refreshMutex.withLock {
        val current = session.accessToken
        if (current != null && current != failedToken) current else refreshNow()
    }

    suspend fun logout() {
        val refresh = session.refreshToken()
        try {
            if (refresh != null) api.logout(LogoutRequest(refresh))
        } finally {
            session.clear()
        }
    }

    private suspend fun refreshNow(): String {
        val refresh = session.refreshToken() ?: throw ApiException("Session expired")
        val tokens = body(api.refresh(RefreshRequest(refresh)))
        session.save(tokens)
        return tokens.accessToken
    }

    private fun accept(response: Response<AuthTokensDto>) {
        session.save(body(response))
    }

    private fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) throw ApiException("Request failed (${response.code()})")
        return response.body() ?: throw ApiException("Server returned an empty response")
    }
}

class ApiException(message: String) : RuntimeException(message)
