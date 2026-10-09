package com.hellobutler.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.core.AppVisibility
import kotlinx.coroutines.launch

class ButlerMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "daily_plan_changed" -> DailySyncWorker.enqueue(this)
            "butler_request_completed", "butler_request_handling" -> {
                val requestId = message.data["request_id"] ?: return
                val app = application as ButlerApplication
                if (message.data["type"] == "butler_request_completed" &&
                    app.container.soundVoice.state.value.responses &&
                    (AppVisibility.isForeground || message.priority == RemoteMessage.PRIORITY_HIGH)) {
                    // Delivered high-priority FCM is a background FGS exemption. Downgraded pushes use the worker/Listen fallback.
                    runCatching { com.hellobutler.app.execution.ButlerAudioPlaybackService.play(this, requestId, false, automatic = true) }
                }
                if (AppVisibility.isForeground) {
                    app.applicationScope.launch {
                        runCatching {
                            if (!app.container.butlerRepository.reconcile(requestId)) {
                                ButlerResultWorker.enqueue(this@ButlerMessagingService, requestId)
                            }
                        }.onFailure { ButlerResultWorker.enqueue(this@ButlerMessagingService, requestId) }
                    }
                } else {
                    ButlerResultWorker.enqueue(this, requestId)
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        PushRegistrationWorker.enqueue(this)
    }
}

class PushRegistrationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ButlerApplication).container
        return try {
            container.pushRegistration.registerCurrent()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.retry()
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
