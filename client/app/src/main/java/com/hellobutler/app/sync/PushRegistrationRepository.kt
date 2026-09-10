package com.hellobutler.app.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.remote.PushApi
import com.hellobutler.app.data.remote.PushDeviceRequestDto
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

class PushRegistrationRepository(
    private val context: Context,
    private val api: PushApi,
    private val auth: AuthRepository,
    private val currentToken: suspend () -> String? = {
        if (FirebaseApp.getApps(context).isEmpty()) null else firebaseToken()
    },
) {
    private val prefs = context.getSharedPreferences("butler_push", Context.MODE_PRIVATE)

    suspend fun registerCurrent() = auth.withAuthenticatedSession {
        val token = currentToken() ?: return@withAuthenticatedSession
        val previous = prefs.getString("registered_token", null)
        authorized { api.register(it, PushDeviceRequestDto(token)) }
        if (previous != null && previous != token) {
            authorized { api.unregister(it, PushDeviceRequestDto(previous)) }
        }
        prefs.edit().putString("registered_token", token).apply()
    }

    // Called under AuthRepository's session lock. A queued registration cannot race logout.
    suspend fun unregisterCurrent() {
        val token = prefs.getString("registered_token", null) ?: return
        try {
            val removed = withTimeoutOrNull(5_000) {
                authorized { api.unregister(it, PushDeviceRequestDto(token)) }
                true
            } ?: false
            if (!removed) Log.w("HelloButlerPush", "Device unregister timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("HelloButlerPush", "Device unregister failed; local logout continues")
        } finally {
            prefs.edit().remove("registered_token").apply()
        }
    }

    private suspend fun authorized(call: suspend (String) -> Response<Unit>) {
        var token = auth.accessToken()
        var response = call("Bearer $token")
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call("Bearer $token")
        }
        if (!response.isSuccessful) throw ApiException("Push registration failed (${response.code()})")
    }
}

private suspend fun firebaseToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: ApiException("FCM unavailable"))
        }
    }
}
