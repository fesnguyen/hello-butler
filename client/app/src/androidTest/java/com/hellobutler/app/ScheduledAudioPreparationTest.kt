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
import com.hellobutler.app.execution.triggerAtMillis
import com.hellobutler.app.execution.audio.AudioExecutionPreparer
import com.hellobutler.app.execution.audio.AudioWorkflowScheduler
import com.hellobutler.app.execution.audio.AudioWorkflowStore
import com.hellobutler.app.execution.audio.SoundVoiceSettings
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
    private var eveningRequests = 0
    private var failEvening = false
    private val planning = object : PlanningApi {
        override suspend fun prepare(authorization: String, request: PrepareDayRequestDto): Response<ResponseBody> =
            error("Unexpected regeneration")
        override suspend fun prepareEvening(authorization: String, request: EveningPrepareRequestDto): Response<EveningPreparationResultDto> {
            eveningRequests++
            if (failEvening) throw java.io.IOException("Planning offline")
            val date = LocalDate.parse(request.summaryDate!!)
            return Response.success(EveningPreparationResultDto(date.toString(), date.plusDays(1).toString(), "Summary", "plan"))
        }
    }
    private val ogg = "OggS".encodeToByteArray() + ByteArray(24) + "OpusHead".encodeToByteArray()

    private fun repository() = DailyEventRepository(context, database, api,
        planning, auth, DailyEventScheduler(context), Json, enqueueSync = {}, enqueueAudio = { enqueued.add(it) })

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().context
        context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun missingAudioWorkflowClaimDoesNotRequestBackendOrRepeatAutomatically() = runBlocking {
        val calls = api.preparations to api.downloads
        assertNull(repository.claimPreparedSpeech(event))
        DeferredSpeechNotification.show(context, event, audioReady = false)
        assertNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
        assertEquals(calls, api.preparations to api.downloads)
        assertTrue(repository.claimEventPlayback(event))
        val claimed = database.dailyEventDao().get(event.id)!!
        assertNotNull(claimed.playbackAttemptedAt)
        assertFalse(repository.claimEventPlayback(claimed))
        assertEquals(calls, api.preparations to api.downloads)
    }

    @Test fun localTimingEditReusesConfirmedBytesButChangedSpeechInvalidates() = runBlocking {
        repository.speechAudio(event.id, 1)
        val calls = api.preparations to api.downloads
        repository.update(event.copy(startTime = "23:00"))
        event = event.copy(startTime = "23:00", version = 2)
        database.pendingSyncOperationDao().deleteAll()
        repository.synchronize(listOf(event.eventDate))
        assertNotNull(repository.preparedSpeech(event))
        assertNotNull(repository.snapshotPreparedSpeech(event))
        assertEquals(calls, api.preparations to api.downloads)
        event = event.copy(description = "Changed speech", version = 3)
        repository.synchronize(listOf(event.eventDate))
        assertNull(repository.preparedSpeech(event))
        assertNull(repository.snapshotPreparedSpeech(event))
    }

    @Test fun remoteVersionDoesNotRebindUnprovenVoiceBytes() = runBlocking {
        repository.speechAudio(event.id, 1)
        event = event.copy(version = 2)
        repository.synchronize(listOf(event.eventDate))
        assertNull(repository.preparedSpeech(event))
        assertNull(repository.snapshotPreparedSpeech(event))
        assertNotNull(repository.speechAudio(event.id, 2))
        assertEquals(2, api.downloads)
    }

    @Test fun voiceChangeRejectsProtectedSnapshotThroughRepositoryRestart() = runBlocking {
        repository.speechAudio(event.id, 1)
        val protected = repository.snapshotPreparedSpeech(event)!!
        repository.reconcileTtsMethod("OPEN_SOURCE")
        repository.reconcileTtsMethod("OPENAI")
        assertNull(repository.preparedSpeech(event))
        assertNull(repository.snapshotPreparedSpeech(event))
        assertTrue(protected.exists())
        repository = repository()
        assertNull(repository.snapshotPreparedSpeech(event))
    }

    @Test fun streamingRecoveryDeadlineStopsStalledBodyWithoutPublishing() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val release = java.util.concurrent.CountDownLatch(1)
        val thread = kotlin.concurrent.thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nContent-Type: audio/ogg\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(ogg); output.flush()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val client = okhttp3.OkHttpClient.Builder().readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/audio").build()
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.newCall(request).execute() }
        api.audioBody = response.body!!
        try {
            withTimeout(2_000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        repository.speechAudio(event.id, 1, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200))
                        fail("Stalled stream exceeded its deadline")
                    } catch (_: java.io.InterruptedIOException) { }
                }
            }
            assertNull(repository.preparedSpeech(event))
            assertFalse(File(context.filesDir, "event_speech").listFiles().orEmpty().any { it.name.startsWith(event.id) && it.name.endsWith(".part") })
        } finally {
            release.countDown(); response.close(); server.close(); thread.join(1_000)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun deletedAndLogoutAudioIsCleaned() = runBlocking {
        val file = repository.speechAudio(event.id, 1)!!
        repository.delete(event)
        assertFalse(file.exists())
        assertNull(database.dailyEventDao().get(event.id))
        repository.clear()
        assertFalse(File(context.filesDir, "event_speech").exists())
    }

    /** Extracted preparation must reject stale alarms and claim events without fetching audio. */
    @Test fun executionPreparationKeepsClaimsIndependentOfAudioReadiness() = runBlocking {
        val store = AudioWorkflowStore(context)
        val workflows = AudioWorkflowScheduler(context, store)
        val settings = SoundVoiceSettings(context)
        val reminders = settings.state.value.reminders
        settings.toggle("reminders", true)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ButlerApplication
        val preparer = AudioExecutionPreparer(
            database, repository, app.container.butlerRepository, store, workflows, settings,
            showDeferred = {}, dismissDeferred = {}, reserveActiveId = { error("Event reserved response identity") },
        )
        try {
            assertNull(preparer.prepare(null, event.id, true, false, "unused", event.version + 1, event.triggerAtMillis()!!))
            assertNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
            val automatic = preparer.prepare(null, event.id, true, false, "unused", event.version, event.triggerAtMillis()!!)!!
            assertEquals("event:${event.id}:${event.version}", automatic.id)
            assertNotNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
            assertNull(preparer.prepare(null, event.id, true, false, "unused"))
            val manual = preparer.prepare(null, event.id, false, false, "unused")!!
            assertTrue(manual.manualEvent)
            assertEquals("cancelled", store.get(automatic.id)!!.status)
            assertEquals(0, api.preparations)
            assertEquals(0, api.downloads)
        } finally {
            store.all().filter { it.eventId == event.id }.forEach { workflows.cancel(it.id) }
            settings.toggle("reminders", reminders)
        }
    }

    /** Failed upload must retain the original outbox identity and increment retry metadata. */
    @Test fun failedUploadRetainsPendingOperation() = runBlocking {
        repository.update(event.copy(description = "Edited locally"))
        val before = database.pendingSyncOperationDao().forEvent(event.id)!!
        api.onSync = { throw java.io.IOException("upload unavailable") }
        try {
            repository.flushPending()
            fail("Expected upload failure")
        } catch (_: java.io.IOException) { }
        val after = database.pendingSyncOperationDao().forEvent(event.id)!!
        assertEquals(before.operationId, after.operationId)
        assertEquals(before.baseVersion, after.baseVersion)
        assertEquals(before.payload, after.payload)
        assertEquals(before.attemptCount + 1, after.attemptCount)
        assertEquals("Edited locally", database.dailyEventDao().get(event.id)!!.description)
    }

    /** Per-result reconciliation must acknowledge both a canonical update and a deletion in one batch. */
    @Test fun multiResultBatchAppliesCanonicalUpdateAndDeletion() = runBlocking {
        repository.update(event.copy(description = "Edited locally"))
        val second = event.copy(id = UUID.randomUUID().toString())
        database.dailyEventDao().upsert(second)
        repository.update(second.copy(description = "Other local edit"))
        api.onSync = { request ->
            val canonical = api.dailyPlan("unused", event.eventDate).body()!!.events.single()
                .copy(description = "Canonical speech", version = event.version + 1)
            Response.success(SyncBatchResultDto(request.operations.map {
                EventSyncResultDto(it.operationId, "applied", if (it.eventId == event.id) canonical else null)
            }))
        }
        repository.flushPending()
        assertTrue(database.pendingSyncOperationDao().next().isEmpty())
        assertEquals("Canonical speech", database.dailyEventDao().get(event.id)!!.description)
        assertEquals(event.version + 1, database.dailyEventDao().get(event.id)!!.version)
        assertNull(database.dailyEventDao().get(second.id))
    }

    @Test fun notificationReadinessIsLocalVersionedAndDoesNotOutliveClaims() = runBlocking {
        val readiness = mutableListOf<Boolean>()
        assertTrue(repository.offerEventPlayback(event) { _, ready -> readiness.add(ready); true })
        assertEquals(listOf(false), readiness)
        assertEquals(0, api.preparations)
        repository.speechAudio(event.id, event.version)
        assertTrue(repository.offerEventPlayback(event) { _, ready -> readiness.add(ready); true })
        assertEquals(listOf(false, true), readiness)
        val calls = api.preparations to api.downloads
        database.dailyEventDao().upsert(event.copy(version = 2))
        repository.offerEventPlayback(event) { _, _ -> fail("Stale event notified"); true }
        repository.offerEventPlayback(event.copy(version = 2)) { _, ready -> assertFalse(ready); true }
        assertEquals(calls, api.preparations to api.downloads)
        database.dailyEventDao().upsert(event)
        assertTrue(repository.claimEventPlayback(event))
        val claimed = database.dailyEventDao().get(event.id)!!
        repository.offerEventPlayback(claimed) { _, _ -> fail("Claimed occurrence notified again"); true }
    }

    @Test fun voiceInvalidationAndDisabledNotificationsKeepManualRecoveryPossible() = runBlocking {
        repository.speechAudio(event.id, event.version)
        repository.reconcileTtsMethod("OPEN_SOURCE")
        repository.reconcileTtsMethod("OPENAI")
        assertFalse(repository.offerEventPlayback(event) { _, ready -> assertFalse(ready); false })
        assertNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
        assertTrue(repository.claimEventPlayback(event, manual = true))
    }

    @Test fun concurrentAutomaticClaimsAllowExactlyOneWinnerWithoutAudio() = runBlocking {
        val winners = coroutineScope {
            (1..10).map { async { repository.claimEventPlayback(event) } }.map { it.await() }
        }
        assertEquals(1, winners.count { it })
        assertEquals(0, api.downloads)
        assertNotNull(database.dailyEventDao().get(event.id)!!.playbackAttemptedAt)
    }

    @Test fun successfulEveningIsNotRegeneratedAfterFailedPullOrRepositoryRecreation() = runBlocking {
        val date = event.eventDate
        val tomorrow = LocalDate.parse(date).plusDays(1).toString()
        api.beforePlan = { throw java.io.IOException("Pull offline after planning succeeded") }
        try { repository.prepareEvening(date, tomorrow); fail("Expected failed pull") } catch (_: java.io.IOException) { }
        assertEquals(1, eveningRequests)
        repository = repository()
        api.beforePlan = {}
        repository.prepareEvening(date, tomorrow)
        repository.prepareEvening(date, tomorrow)
        assertEquals(1, eveningRequests)
    }

    @Test fun failedEveningRetriesAndLogoutClearsSuccessfulDate() = runBlocking {
        val date = event.eventDate
        val tomorrow = LocalDate.parse(date).plusDays(1).toString()
        failEvening = true
        try { repository.prepareEvening(date, tomorrow); fail("Expected planning failure") } catch (_: java.io.IOException) { }
        assertNull(context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE).getString("prepared_date", null))
        failEvening = false
        repository.prepareEvening(date, tomorrow)
        assertEquals(2, eveningRequests)
        repository.clear()
        assertNull(context.getSharedPreferences("butler_nightly_planning", Context.MODE_PRIVATE).getString("prepared_date", null))
    }

    private inner class FakeSync : SyncApi {
        var status = "ready"
        var audioBody: ResponseBody? = null
        var preparations = 0
        var downloads = 0
        var beforeDownload: suspend () -> Unit = {}
        var beforePlan: suspend () -> Unit = {}
        var onSync: suspend (SyncBatchRequestDto) -> Response<SyncBatchResultDto> = { error("Unexpected sync") }
        override suspend fun prepareEventSpeech(authorization: String, eventId: String, version: Int): Response<EventSpeechPreparationDto> {
            preparations++
            return Response.success(EventSpeechPreparationDto(status))
        }
        override suspend fun eventAudio(authorization: String, eventId: String, version: Int): Response<ResponseBody> {
            downloads++
            beforeDownload()
            val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://127.0.0.1/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Version", version.toString()).build()
            return Response.success(audioBody ?: ogg.toResponseBody("audio/ogg".toMediaType()), raw)
        }
        override suspend fun dailyPlan(authorization: String, date: String) = run {
            beforePlan()
            Response.success(DailyPlanSnapshotDto(
            SyncPlanDto(event.dailyPlanId!!, date, "planned", "2026-10-12T00:00:00Z"),
            listOf(SyncEventDto(event.id, event.dailyPlanId!!, date, event.title, event.description, event.eventType,
                event.status, event.startTime, event.endTime, event.durationMinutes, event.scheduledPrecision, event.content,
                event.reminderMinutesBefore, event.speakAloud, event.sortOrder, event.version, event.origin, "2026-10-12T00:00:00Z"))))
        }
        override suspend fun syncEvents(authorization: String, request: SyncBatchRequestDto): Response<SyncBatchResultDto> = onSync(request)
        override suspend fun mutateUpcomingEvent(authorization: String, contextId: String, mutation: UpcomingEventMutationDto): Response<UpcomingMutationResultDto> = error("Unexpected mutation")
    }
}
