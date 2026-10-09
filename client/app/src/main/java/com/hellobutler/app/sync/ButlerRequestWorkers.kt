package com.hellobutler.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hellobutler.app.ButlerApplication
import java.util.concurrent.TimeUnit

private const val REQUEST_ID = "request_id"

class ButlerRequestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(REQUEST_ID) ?: return Result.failure()
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        return runCatching { container.butlerRepository.upload(requestId); Result.success() }.getOrElse { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, requestId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("butler-upload-$requestId", ExistingWorkPolicy.KEEP, request<ButlerRequestWorker>(requestId))
        }
    }
}

class ButlerResultWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(REQUEST_ID) ?: return Result.failure()
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        return runCatching { if (container.butlerRepository.reconcile(requestId)) Result.success() else Result.retry() }.getOrElse { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, requestId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("butler-result-$requestId", ExistingWorkPolicy.REPLACE, request<ButlerResultWorker>(requestId))
        }
    }
}

class ButlerAudioWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(REQUEST_ID) ?: return Result.failure()
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        return runCatching {
            val audio = container.butlerRepository.ensureAudio(requestId)
            if (audio != null && container.soundVoice.state.value.responses) {
                val id = "response:$requestId"
                val message = container.database.butlerConversationDao().message(requestId, "butler")
                val recent = message?.createdAt?.let { runCatching {
                    java.time.Instant.parse(it).isAfter(java.time.Instant.now().minusSeconds(600))
                }.getOrDefault(false) } ?: false
                if (recent && container.audioWorkflowStore.get(id) == null) {
                    val execution = com.hellobutler.app.execution.audio.AudioExecution(id,
                        com.hellobutler.app.execution.audio.AudioWorkflow.BUTLER_RESPONSE,
                        audio.absolutePath, requestId = requestId)
                    container.audioWorkflowStore.save(execution)
                    if (com.hellobutler.app.core.AppVisibility.isForeground) {
                        try { com.hellobutler.app.execution.ButlerAudioPlaybackService.resume(applicationContext, id) }
                        catch (_: IllegalStateException) { container.audioWorkflowScheduler.offer(execution) }
                        catch (_: SecurityException) { container.audioWorkflowScheduler.offer(execution) }
                    } else container.audioWorkflowScheduler.offer(execution)
                }
            }
            if (audio != null || container.butlerRepository.audioUnavailable(requestId)) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, requestId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("butler-audio-$requestId", ExistingWorkPolicy.KEEP, request<ButlerAudioWorker>(requestId))
        }
    }
}

private inline fun <reified T : androidx.work.ListenableWorker> request(requestId: String) =
    OneTimeWorkRequestBuilder<T>()
        .setInputData(Data.Builder().putString(REQUEST_ID, requestId).build())
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
