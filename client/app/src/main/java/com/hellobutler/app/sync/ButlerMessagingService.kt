package com.hellobutler.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.data.remote.PushDeviceRequestDto
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ButlerMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "daily_plan_changed") DailySyncWorker.enqueue(this)
    }

    override fun onNewToken(token: String) {
        PushRegistrationWorker.enqueue(this)
    }
}

class PushRegistrationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        return runCatching {
            val registrationToken = firebaseToken() ?: return Result.retry()
            var accessToken = container.authRepository.accessToken()
            var response = container.pushApi.register(
                "Bearer $accessToken", PushDeviceRequestDto(registrationToken)
            )
            if (response.code() == 401) {
                accessToken = container.authRepository.refreshAfterUnauthorized(accessToken)
                response = container.pushApi.register(
                    "Bearer $accessToken", PushDeviceRequestDto(registrationToken)
                )
            }
            if (response.isSuccessful) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }

    private suspend fun firebaseToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (continuation.isActive) {
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "push-device-registration", ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
