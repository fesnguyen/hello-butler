package com.hellobutler.app.execution

import android.content.Context
import android.util.Log
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
import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.data.local.DailyEventEntity
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal fun DailyEventEntity.canPrepareSpeech(today: LocalDate = LocalDate.now()): Boolean =
    syncedFromServer && version > 0 && status == "planned" && playbackAttemptedAt == null &&
        (speakAloud || eventType == "reminder") && triggerAtMillis() != null &&
        !listOf(content, description, title).firstOrNull { !it.isNullOrBlank() }.isNullOrBlank() &&
        runCatching { !LocalDate.parse(eventDate).isBefore(today) }.getOrDefault(false)

internal fun eventAudioRetryable(status: Int?): Boolean =
    status == null || status in setOf(202, 408, 424, 429) || status >= 500

/** Prepares a file only. Alarm delivery and playback never depend on this worker. */
class DailyEventAudioWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(EXTRA_EVENT_ID) ?: return Result.failure()
        val version = inputData.getInt("event_version", -1)
        val container = (applicationContext as ButlerApplication).container
        if (!container.authRepository.hasSession()) return Result.success()
        return try {
            val event = container.database.dailyEventDao().get(id)
            if (event == null || event.version != version || !event.canPrepareSpeech()) return Result.success()
            if (container.dailyEventRepository.speechAudio(id, version) == null) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (error.statusCode in setOf(404, 409, 410, 422)) {
                val date = container.database.dailyEventDao().get(id)?.eventDate
                container.dailyEventRepository.queueSynchronization(listOfNotNull(date))
            }
            if (eventAudioRetryable(error.statusCode)) Result.retry()
            else Result.failure(Data.Builder().putString("error", error.message).build())
        } catch (error: java.io.IOException) {
            Log.w("ButlerAudio", "Event audio preparation will retry", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "daily-event-audio"
        internal fun workName(id: String, version: Int) = "$TAG:$id:$version"
        private fun eventTag(id: String) = "$TAG:$id"
        fun enqueue(context: Context, event: DailyEventEntity) {
            if (!event.canPrepareSpeech()) return
            val request = OneTimeWorkRequestBuilder<DailyEventAudioWorker>()
                .setInputData(Data.Builder().putString(EXTRA_EVENT_ID, event.id).putInt("event_version", event.version).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG).addTag(eventTag(event.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(event.id, event.version), ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelAllWorkByTag(eventTag(id)) }
        fun clear(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    }
}
