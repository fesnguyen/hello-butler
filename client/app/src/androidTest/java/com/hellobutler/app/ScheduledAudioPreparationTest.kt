package com.hellobutler.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.remote.*
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.execution.DailyEventScheduler
import com.hellobutler.app.execution.DeferredSpeechNotification
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import retrofit2.Retrofit

@RunWith(AndroidJUnit4::class)
class ScheduledAudioPreparationTest {
    private lateinit var context: Context
    private lateinit var database: ButlerDatabase
    private lateinit var session: SecureSessionStore
    private lateinit var auth: AuthRepository
    private lateinit var repository: DailyEventRepository
    private lateinit var event: DailyEventEntity
    private lateinit var api: FakeSync
    private val enqueued = mutableListOf<DailyEventEntity>()
    private val ogg = "OggS".encodeToByteArray() + ByteArray(24) + "OpusHead".encodeToByteArray()

    private fun repository() = DailyEventRepository(context, database, api,
        Retrofit.Builder().baseUrl("http://127.0.0.1/").build().create(PlanningApi::class.java),
        auth, DailyEventScheduler(context), Json, enqueueSync = {}, enqueueAudio = { enqueued.add(it) })

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().context
        database = Room.inMemoryDatabaseBuilder(context, ButlerDatabase::class.java).build()
        session = SecureSessionStore(context).also { it.save(AuthTokensDto("test-access", "test-refresh", "bearer", 3600)) }
        auth = AuthRepository(Retrofit.Builder().baseUrl("http://127.0.0.1/").build().create(AuthApi::class.java), session, {}, {})
        event = DailyEventEntity(id = UUID.randomUUID().toString(), dailyPlanId = UUID.randomUUID().toString(),
            eventDate = LocalDate.now().toString(), title = "Reminder", description = "Drink water", eventType = "reminder",
            status = "planned", startTime = "00:00", endTime = null, durationMinutes = null, scheduledPrecision = "exact",
            content = null, reminderMinutesBefore = null, speakAloud = false, sortOrder = 0, version = 1,
            origin = "user", syncedFromServer = true, playbackAttemptedAt = null)
        api = FakeSync()
        repository = repository()
        repository.synchronize(listOf(event.eventDate))
    }

    @After fun cleanup() = runBlocking {
        repository.clear()
        File(context.filesDir, "workflow_audio").deleteRecursively()
        database.close()
        session.clear()
    }

    @Test fun canonicalPersistenceEnqueuesAndConcurrentPreparationDownloadsOnce() = runBlocking {
        assertEquals(event.id, enqueued.single().id)
        coroutineScope {
            val first = async { repository.speechAudio(event.id, event.version) }
            val second = async { repository.speechAudio(event.id, event.version) }
            assertEquals(first.await(), second.await())
        }
        assertEquals(1, api.preparations)
        assertEquals(1, api.downloads)
        assertTrue(repository.preparedSpeech(event)!!.path.startsWith(context.filesDir.path))
        repository.synchronize(listOf(event.eventDate))
        assertEquals(1, enqueued.size)
    }

    @Test fun pendingAndNetworkFailureLeaveOccurrenceUnclaimedAndRetryable() = runBlocking {
        api.status = "pending"
        assertNull(repository.speechAudio(event.id, 1))
        assertEquals(0, api.downloads)
        api.status = "ready"
        api.beforeDownload = { throw java.io.IOException("offline") }
        try { repository.speechAudio(event.id, 1); fail("Expected network failure") } catch (_: java.io.IOException) { }
        assertNull(repository.preparedSpeech(event))
        assertNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
        api.beforeDownload = {}
        assertNotNull(repository.speechAudio(event.id, 1))
    }

    @Test fun obsoleteTransferIsDiscardedAndCanonicalNewVersionIsPrepared() = runBlocking {
        api.beforeDownload = { database.dailyEventDao().upsert(event.copy(version = 2, description = "New speech")) }
        assertNull(repository.speechAudio(event.id, 1))
        assertNull(repository.preparedSpeech(event))
        event = event.copy(version = 2, description = "New speech")
        api.beforeDownload = {}
        repository.synchronize(listOf(event.eventDate))
        assertNotNull(repository.speechAudio(event.id, 2))
        assertEquals(listOf("${event.id}-2.ogg"), File(context.filesDir, "event_speech").listFiles()!!.filter { it.name.startsWith(event.id) }.map { it.name })
    }

    @Test fun restartRecoveryRepairsMissingEnqueueAndOfflineClaimUsesDurableSnapshot() = runBlocking {
        enqueued.clear()
        repository = repository()
        repository.recoverAudioPreparation()
        assertEquals(event.id, enqueued.single().id)
        repository.speechAudio(event.id, 1)
        File(context.cacheDir, "event_speech").deleteRecursively()
        repository = repository()
        api.beforeDownload = { error("Offline playback contacted backend") }
        val calls = api.preparations to api.downloads
        val snapshot = repository.claimPreparedSpeech(event)!!
        assertTrue(snapshot.isFile)
        assertEquals(calls, api.preparations to api.downloads)
        repository.update(event.copy(description = "Edited locally"))
        assertNull(repository.preparedSpeech(event))
        assertTrue(snapshot.isFile) // Cleanup cannot remove an active/delayed workflow snapshot.
        assertNull(repository.speechAudio(event.id, 1))
        assertEquals(calls, api.preparations to api.downloads)
    }

    @Test fun longNetworkSyncDoesNotBlockOfflinePlaybackClaim() = runBlocking {
        repository.speechAudio(event.id, 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforePlan = { entered.complete(Unit); release.await() }
        coroutineScope {
            val sync = async { repository.synchronize(listOf(event.eventDate)) }
            entered.await()
            try { assertNotNull(withTimeout(1000) { repository.claimPreparedSpeech(event) }) }
            finally { release.complete(Unit) }
            sync.await()
        }
    }

    @Test fun missingAudioFallbackDoesNotClaimSpeechOrRequestBackend() = runBlocking {
        val calls = api.preparations to api.downloads
        assertNull(repository.claimPreparedSpeech(event))
        DeferredSpeechNotification.show(context, event, audioReady = false)
        assertNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
        assertEquals(calls, api.preparations to api.downloads)
    }

    @Test fun deletedAndLogoutAudioIsCleaned() = runBlocking {
        val file = repository.speechAudio(event.id, 1)!!
        repository.delete(event)
        assertFalse(file.exists())
        assertNull(database.dailyEventDao().get(event.id))
        repository.clear()
        assertFalse(File(context.filesDir, "event_speech").exists())
    }

    private inner class FakeSync : SyncApi {
        var status = "ready"
        var preparations = 0
        var downloads = 0
        var beforeDownload: suspend () -> Unit = {}
        var beforePlan: suspend () -> Unit = {}
        override suspend fun prepareEventSpeech(authorization: String, eventId: String, version: Int): Response<EventSpeechPreparationDto> {
            preparations++
            return Response.success(EventSpeechPreparationDto(status))
        }
        override suspend fun eventAudio(authorization: String, eventId: String, version: Int): Response<ResponseBody> {
            downloads++
            beforeDownload()
            val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://127.0.0.1/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Version", version.toString()).build()
            return Response.success(ogg.toResponseBody("audio/ogg".toMediaType()), raw)
        }
        override suspend fun dailyPlan(authorization: String, date: String) = run {
            beforePlan()
            Response.success(DailyPlanSnapshotDto(
            SyncPlanDto(event.dailyPlanId!!, date, "planned", "2026-10-12T00:00:00Z"),
            listOf(SyncEventDto(event.id, event.dailyPlanId!!, date, event.title, event.description, event.eventType,
                event.status, event.startTime, event.endTime, event.durationMinutes, event.scheduledPrecision, event.content,
                event.reminderMinutesBefore, event.speakAloud, event.sortOrder, event.version, event.origin, "2026-10-12T00:00:00Z"))))
        }
        override suspend fun syncEvents(authorization: String, request: SyncBatchRequestDto): Response<SyncBatchResultDto> = error("Unexpected sync")
        override suspend fun mutateUpcomingEvent(authorization: String, contextId: String, mutation: UpcomingEventMutationDto): Response<UpcomingMutationResultDto> = error("Unexpected mutation")
    }
}
