package com.hellobutler.app.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.room.withTransaction
import com.hellobutler.app.auth.ApiException
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.local.ButlerRequestEntity
import com.hellobutler.app.data.local.ConversationMessageEntity
import com.hellobutler.app.data.remote.ButlerApi
import com.hellobutler.app.data.remote.ButlerResultDto
import com.hellobutler.app.data.remote.ButlerTextRequestDto
import com.hellobutler.app.sync.ButlerAudioWorker
import com.hellobutler.app.sync.ButlerNotification
import com.hellobutler.app.sync.ButlerRequestWorker
import com.hellobutler.app.sync.ButlerResultWorker
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

class ButlerRepository(
    private val context: Context,
    private val database: ButlerDatabase,
    private val api: ButlerApi,
    private val auth: AuthRepository,
    private val events: DailyEventRepository,
) {
    private val dao = database.butlerConversationDao()
    private val audioLocks = ConcurrentHashMap<String, Mutex>()

    fun observeMessages(): Flow<List<ConversationMessageEntity>> = dao.observeMessages()

    suspend fun queueText(message: String): String {
        require(message.isNotBlank())
        val requestId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        database.withTransaction {
            dao.upsertRequest(ButlerRequestEntity(requestId, "text", "talk", message, null, "sending", now))
            dao.upsertMessage(ConversationMessageEntity("$requestId:user", requestId, "user", message, now, "sending", "text"))
        }
        ButlerRequestWorker.enqueue(context, requestId)
        return requestId
    }

    suspend fun queueAudio(mode: String, recording: File): String {
        require(mode == "order" || mode == "talk")
        val requestId = UUID.randomUUID().toString()
        val durable = File(File(context.filesDir, "butler_pending").apply { mkdirs() }, "$requestId.m4a")
        if (!recording.renameTo(durable)) {
            recording.copyTo(durable, overwrite = true)
            recording.delete()
        }
        val now = Instant.now().toString()
        database.withTransaction {
            dao.upsertRequest(ButlerRequestEntity(requestId, "audio", mode, null, durable.absolutePath, "sending", now))
            dao.upsertMessage(ConversationMessageEntity("$requestId:user", requestId, "user", "Voice message", now, "sending", "audio"))
        }
        ButlerRequestWorker.enqueue(context, requestId)
        return requestId
    }

    suspend fun upload(requestId: String) {
        val request = dao.request(requestId) ?: return
        if (request.status !in setOf("sending", "failed")) return
        events.flushPending()
        try {
            val accepted = if (request.inputSource == "audio") {
                val file = request.localAudioPath?.let(::File)?.takeIf(File::isFile)
                    ?: throw ApiException("Voice recording is unavailable")
                authorized { token ->
                    api.audio(
                        token,
                        requestId.toRequestBody("text/plain".toMediaType()),
                        request.interactionMode.toRequestBody("text/plain".toMediaType()),
                        MultipartBody.Part.createFormData("audio", file.name, file.asRequestBody("audio/mp4".toMediaType())),
                    )
                }
            } else {
                authorized { token -> api.text(token, ButlerTextRequestDto(requestId, request.submittedText.orEmpty())) }
            }
            dao.updateRequest(requestId, "sent", Instant.now().toString(), null)
            dao.updateDelivery(requestId, "sent")
            ButlerResultWorker.enqueue(context, accepted.requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            dao.updateRequest(requestId, "failed", null, error.message)
            dao.updateDelivery(requestId, "failed")
            throw error
        }
    }

    suspend fun reconcile(requestId: String): Boolean {
        val request = dao.request(requestId) ?: return true
        val result = authorized { token -> api.result(token, requestId) }
        if (result.status in setOf("accepted", "processing")) {
            dao.updateRequest(requestId, result.status, request.acceptedAt, null)
            dao.updateDelivery(requestId, "sent")
            return false
        }
        if (result.status == "failed") {
            dao.updateRequest(requestId, "failed", request.acceptedAt, result.failureReason)
            dao.updateDelivery(requestId, "failed")
            return true
        }
        persistCompleted(request, result)
        ButlerNotification.showCompleted(context, result)
        request.localAudioPath?.let { File(it).delete() }
        if (result.responseAudioUrl != null) {
            runCatching { ensureAudio(requestId) }.onFailure { ButlerAudioWorker.enqueue(context, requestId) }
        }
        synchronizeChanges(result)
        return true
    }

    private suspend fun persistCompleted(request: ButlerRequestEntity, result: ButlerResultDto) {
        val completedAt = result.completedAt ?: Instant.now().toString()
        database.withTransaction {
            val existingUser = dao.message(request.requestId, "user")
            dao.upsertMessage(
                ConversationMessageEntity(
                    id = "${request.requestId}:user", requestId = request.requestId, role = "user",
                    text = if (request.inputSource == "audio") result.userMessageText ?: "Voice message" else request.submittedText.orEmpty(),
                    createdAt = existingUser?.createdAt ?: request.createdAt, deliveryState = "completed", inputSource = request.inputSource,
                )
            )
            dao.upsertMessage(
                ConversationMessageEntity(
                    id = "${request.requestId}:butler", requestId = request.requestId, role = "butler",
                    text = result.responseText.orEmpty(), createdAt = completedAt, deliveryState = "completed", inputSource = request.inputSource,
                    responseAudioUrl = result.responseAudioUrl, responseAudioMimeType = result.responseAudioMimeType,
                    responseAudioDurationMs = result.responseAudioDurationMs,
                    audioCacheState = if (result.responseAudioUrl == null) "unavailable" else "pending",
                )
            )
            dao.upsertRequest(request.copy(status = "completed", localAudioPath = null, lastError = null))
        }
    }

    suspend fun ensureAudio(requestId: String): File? = audioLocks.getOrPut(requestId) { Mutex() }.withLock {
        val message = dao.message(requestId, "butler") ?: return@withLock null
        message.localAudioPath?.let(::File)?.takeIf(File::isFile)?.let { return@withLock it }
        if (message.responseAudioUrl == null) return@withLock null
        dao.updateAudio(requestId, "downloading", null)
        try {
            val body = authorized { token -> api.responseAudio(token, requestId) }
            val directory = File(context.filesDir, "butler_audio").apply { mkdirs() }
            val temporary = File(directory, "$requestId.part")
            val target = File(directory, "$requestId.mp3")
            body.byteStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            dao.updateAudio(requestId, "cached", target.absolutePath)
            val metadata = MediaMetadataRetriever()
            runCatching {
                metadata.setDataSource(target.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                    ?.let { dao.updateAudioDuration(requestId, it) }
            }
            metadata.release()
            target
        } catch (error: Exception) {
            dao.updateAudio(requestId, "failed", null)
            throw error
        }
    }

    suspend fun recover() {
        dao.recoverableRequests().forEach { request ->
            if (request.status in setOf("sending", "failed")) ButlerRequestWorker.enqueue(context, request.requestId)
            else ButlerResultWorker.enqueue(context, request.requestId)
        }
        runCatching { refreshHistory() }
    }

    private suspend fun refreshHistory() {
        val results = authorized { token -> api.recent(token) }
        results.forEach { result ->
            val local = dao.request(result.requestId) ?: ButlerRequestEntity(
                result.requestId, result.inputSource, result.interactionMode,
                result.userMessageText.takeIf { result.inputSource == "text" }, null,
                "completed", result.createdAt,
            )
            persistCompleted(local, result)
        }
        results.takeLast(5).filter { it.responseAudioUrl != null }.forEach { ButlerAudioWorker.enqueue(context, it.requestId) }
    }

    suspend fun clear() {
        dao.recoverableRequests().mapNotNull { it.localAudioPath }.forEach { File(it).delete() }
        File(context.filesDir, "butler_audio").deleteRecursively()
        File(context.filesDir, "butler_pending").deleteRecursively()
        database.withTransaction { dao.clearMessages(); dao.clearRequests() }
    }

    private suspend fun synchronizeChanges(result: ButlerResultDto) {
        if (result.changedEntities.isEmpty()) return
        val today = LocalDate.now()
        val dates = listOf(today.toString(), today.plusDays(1).toString()) + result.changedEntities.flatMap { it.planDates }
        events.queueSynchronization(dates)
        try {
            events.synchronize(dates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable DailySyncWorker queue will reconcile this completed command.
        }
    }

    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): T {
        var token = auth.accessToken()
        var response = call("Bearer $token")
        if (response.code() == 401) {
            token = auth.refreshAfterUnauthorized(token)
            response = call("Bearer $token")
        }
        if (!response.isSuccessful) throw ApiException("Butler request failed (${response.code()})", response.code())
        return response.body() ?: throw ApiException("Butler returned an empty response")
    }
}
