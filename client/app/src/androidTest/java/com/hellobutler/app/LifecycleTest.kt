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
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import com.hellobutler.app.execution.DailyEventScheduler
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class LifecycleTest {
    private lateinit var context: Context
    private lateinit var database: ButlerDatabase
    private lateinit var store: SecureSessionStore
    private lateinit var auth: AuthRepository
    private lateinit var events: DailyEventRepository
    private val today = LocalDate.now().toString()

    @Before
    fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecureSessionStore(context).also { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, ButlerDatabase::class.java).build()
        auth = AuthRepository(FakeAuth(), store, {}, {})
        auth.login("test@example.test", "password")
        events = DailyEventRepository(context, database, UnusedSync(), UnusedPlanning(), auth, DailyEventScheduler(context), Json, enqueueSync = {})
    }

    @After fun cleanup() { database.close(); store.clear() }

    @Test
    fun typedTextIsPersistedExactlyAndAcceptedAsynchronously() = runBlocking {
        val submitted = mutableListOf<String>()
        val api = object : EmptyButlerApi() {
            override suspend fun text(authorization: String, request: ButlerTextRequestDto): Response<ButlerAcceptedDto> {
                submitted += request.message
                return Response.success(202, ButlerAcceptedDto(request.requestId, "accepted"))
            }
        }
        val repository = ButlerRepository(context, database, api, auth, events)
        val exact = "  Move my meeting to 4 PM.  "
        val requestId = repository.queueText(exact)
        assertEquals(exact, database.butlerConversationDao().message(requestId, "user")?.text)
        repository.upload(requestId)
        assertEquals(listOf(exact), submitted)
        assertEquals("sent", database.butlerConversationDao().request(requestId)?.status)
    }

    @Test
    fun duplicateCompletedFetchUpsertsOneCanonicalPair() = runBlocking {
        val api = object : EmptyButlerApi() {
            override suspend fun result(authorization: String, id: String): Response<ButlerResultDto> = Response.success(
                ButlerResultDto(requestId = id, status = "completed", inputSource = "text", interactionMode = "talk",
                    createdAt = "2026-09-12T11:59:00Z", userMessageText = "Hello", responseText = "Got it.", completedAt = "2026-09-12T12:00:00Z")
            )
        }
        val repository = ButlerRepository(context, database, api, auth, events)
        val requestId = repository.queueText("Hello")
        assertTrue(repository.reconcile(requestId)); assertTrue(repository.reconcile(requestId))
        assertEquals("Hello", database.butlerConversationDao().message(requestId, "user")?.text)
        assertEquals("Got it.", database.butlerConversationDao().message(requestId, "butler")?.text)
    }

    @Test
    fun audioRequestMovesRecordingIntoDurableQueue() = runBlocking {
        val recording = File(context.cacheDir, "test-recording.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val repository = ButlerRepository(context, database, EmptyButlerApi(), auth, events)
        val requestId = repository.queueAudio("order", recording)
        val request = database.butlerConversationDao().request(requestId)
        assertNotNull(request); assertTrue(File(request!!.localAudioPath!!).isFile); assertFalse(recording.exists())
    }

    @Test
    fun directEventEditRemainsLocalFirst() = runBlocking {
        val event = local(); events.update(event)
        assertEquals("19:00", database.dailyEventDao().get(event.id)?.startTime)
        assertEquals(1, database.pendingSyncOperationDao().next().size)
    }

    @Test
    fun proactivePlaybackClaimRejectsSkippedAndDeduplicates() = runBlocking {
        val event = local().copy(speakAloud = true, content = "Prepared speech", status = "skipped")
        database.dailyEventDao().upsert(event)
        assertEquals(0, database.dailyEventDao().claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
        database.dailyEventDao().upsert(event.copy(status = "planned"))
        assertEquals(1, database.dailyEventDao().claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
        assertEquals(0, database.dailyEventDao().claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
    }

    private fun local() = DailyEventEntity("event", "plan", today, "Exercise", null, "exercise", "planned", "19:00", null, null, "exact", null, null, false, 0, 0, "user", false, null)

    private open class EmptyButlerApi : ButlerApi {
        override suspend fun recent(authorization: String): Response<List<ButlerResultDto>> = Response.success(emptyList())
        override suspend fun text(authorization: String, request: ButlerTextRequestDto): Response<ButlerAcceptedDto> = error("unused")
        override suspend fun audio(authorization: String, requestId: RequestBody, interactionMode: RequestBody, audio: MultipartBody.Part): Response<ButlerAcceptedDto> = error("unused")
        override suspend fun result(authorization: String, requestId: String): Response<ButlerResultDto> = error("unused")
        override suspend fun responseAudio(authorization: String, requestId: String): Response<ResponseBody> = error("unused")
    }

    private class FakeAuth : AuthApi {
        private fun tokens() = Response.success(AuthTokensDto("access", "refresh", "bearer", 900))
        override suspend fun login(request: EmailAuthRequest) = tokens()
        override suspend fun register(request: EmailAuthRequest) = tokens()
        override suspend fun google(request: GoogleAuthRequest) = tokens()
        override suspend fun refresh(request: RefreshRequest) = tokens()
        override suspend fun logout(request: LogoutRequest): Response<Unit> = Response.success(Unit)
    }
    private class UnusedSync : SyncApi {
        override suspend fun syncEvents(authorization: String, request: SyncBatchRequestDto): Response<SyncBatchResultDto> = error("unused")
        override suspend fun dailyPlan(authorization: String, date: String): Response<DailyPlanSnapshotDto> = error("unused")
        override suspend fun mutateUpcomingEvent(authorization: String, contextId: String, mutation: UpcomingEventMutationDto): Response<UpcomingMutationResultDto> = error("unused")
    }
    private class UnusedPlanning : PlanningApi {
        override suspend fun prepare(authorization: String, request: PrepareDayRequestDto): Response<ResponseBody> = error("unused")
        override suspend fun prepareEvening(authorization: String, request: EveningPrepareRequestDto): Response<EveningPreparationResultDto> = error("unused")
    }
}
