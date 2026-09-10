package com.hellobutler.app.auth

import com.hellobutler.app.data.remote.AuthApi
import com.hellobutler.app.data.remote.AuthTokensDto
import com.hellobutler.app.data.remote.EmailAuthRequest
import com.hellobutler.app.data.remote.GoogleAuthRequest
import com.hellobutler.app.data.remote.LogoutRequest
import com.hellobutler.app.data.remote.RefreshRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

class AuthRepository(
    private val api: AuthApi,
    private val session: SecureSessionStore,
    private val onAuthenticated: () -> Unit,
    private val beforeLogout: suspend () -> Unit,
    private val afterLogout: suspend () -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val sessionMutex = Mutex()
    @Volatile private var sessionGeneration = 0L

    suspend fun <T> withAuthenticatedSession(block: suspend () -> T): T? = sessionMutex.withLock {
        if (hasSession()) block() else null
    }

    fun hasSession(): Boolean = session.refreshToken() != null

    suspend fun restoreSession(): Boolean = sessionMutex.withLock {
        if (!hasSession()) return@withLock false
        try {
            refreshMutex.withLock { refreshNow() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is ApiException && error.statusCode in listOf(400, 401, 403)) {
                sessionGeneration++
                session.clear()
                return@withLock false
            }
            // Offline restoration retains prepared days and lets registration retry on connectivity.
        }
        onAuthenticated()
        true
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

    suspend fun logout() = sessionMutex.withLock {
        try {
            beforeLogout() // Serialized with registration while credentials are still valid.
            refreshMutex.withLock {
                session.refreshToken()?.let { api.logout(LogoutRequest(it)) }
            }
        } finally {
            sessionGeneration++
            session.clear()
            // Finish any in-flight sync, then clear Room after network credentials are invalidated.
            withContext(NonCancellable) { afterLogout() }
        }
    }

    private suspend fun refreshNow(): String {
        val generation = sessionGeneration
        val refresh = session.refreshToken() ?: throw ApiException("Session expired")
        val tokens = body(api.refresh(RefreshRequest(refresh)))
        if (generation != sessionGeneration) throw ApiException("Session changed")
        session.save(tokens)
        return tokens.accessToken
    }

    private suspend fun accept(response: Response<AuthTokensDto>) = sessionMutex.withLock {
        val tokens = body(response)
        sessionGeneration++
        session.save(tokens)
        onAuthenticated()
    }

    private fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) throw ApiException("Request failed (${response.code()})", response.code())
        return response.body() ?: throw ApiException("Server returned an empty response")
    }
}

class ApiException(message: String, val statusCode: Int? = null) : RuntimeException(message)
