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
import com.hellobutler.app.sync.PushRegistrationRepository
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
    private lateinit var server: FakeSync
    private var authWakeups = 0
    private val calls = mutableListOf<String>()
    private val today = LocalDate.now().toString()

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecureSessionStore(context).also { it.clear() }
        context.getSharedPreferences("butler_push", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, ButlerDatabase::class.java).build()
        auth = AuthRepository(FakeAuth(), store, { authWakeups++ }, {})
        auth.login("test@example.test", "password")
        server = FakeSync()
        events = DailyEventRepository(
            context, database, server, UnusedPlanning(), auth, DailyEventScheduler(context), Json,
            enqueueSync = {}, // Real Room and repositories; no unrelated network workers in tests.
        )
    }

    @After fun cleanup() {
        database.close()
        store.clear()
        context.getSharedPreferences("butler_push", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun local() = DailyEventEntity(
        "event", "plan", today, "Exercise", null, "exercise", "planned", "19:00", null,
        null, "exact", null, null, false, 0, 0, "user", false, null,
    )

    @Test fun editImmediatelyThenReason_pushesFirstAndReconcilesRoom() = runBlocking {
        events.update(local())
        val butler = ButlerRepository(object : ButlerApi {
            override suspend fun talk(authorization: String, request: ButlerRequestDto): Response<ButlerResponseDto> {
                calls += "reason"
                assertEquals("19:00", server.canonical?.startTime)
                assertTrue(database.pendingSyncOperationDao().next().isEmpty())
                server.canonical = server.canonical!!.copy(title = "Rearranged exercise", version = 2)
                return Response.success(ButlerResponseDto("Done", listOf(ChangedEntityDto("daily_event", "event", listOf(today)))))
            }
        }, auth, events)
        butler.send("talk", "Rearrange my evening around exercise")
        assertEquals(listOf("push", "reason"), calls.take(2))
        assertEquals("Rearranged exercise", database.dailyEventDao().get("event")?.title)
        assertEquals(2, database.dailyEventDao().get("event")?.version)
    }

    @Test fun failedPushPreventsReasoningAndKeepsOutbox() = runBlocking {
        events.update(local())
        server.failPush = true
        val butler = ButlerRepository(object : ButlerApi {
            override suspend fun talk(authorization: String, request: ButlerRequestDto): Response<ButlerResponseDto> {
                fail("Reasoning must not run after a failed push")
                error("unreachable")
            }
        }, auth, events)
        try { butler.send("talk", "Move it"); fail("Expected upload failure") } catch (_: IOException) { }
        assertEquals(1, database.pendingSyncOperationDao().next().size)
        assertEquals("19:00", database.dailyEventDao().get("event")?.startTime)
    }

    @Test fun directEditDoesNotWaitForButlerReasoning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val butler = ButlerRepository(object : ButlerApi {
            override suspend fun talk(authorization: String, request: ButlerRequestDto): Response<ButlerResponseDto> {
                entered.complete(Unit)
                release.await()
                return Response.success(ButlerResponseDto("Answer"))
            }
        }, auth, events)
        val request = async { butler.send("talk", "Think") }
        entered.await()
        try {
            withTimeout(2_000) { events.update(local()) }
            assertEquals("19:00", database.dailyEventDao().get("event")?.startTime)
        } finally { release.complete(Unit) }
        request.await()
        Unit
    }

    @Test fun conflictConsumesOutboxAndAcceptsCanonical() = runBlocking {
        events.update(local())
        events.flushPending()
        events.update(local().copy(startTime = "20:00", version = 1))
        server.conflict = true
        events.flushPending()
        assertEquals("19:00", database.dailyEventDao().get("event")?.startTime)
        assertTrue(database.pendingSyncOperationDao().next().isEmpty())
    }

    @Test fun failedPullDoesNotReportSuccessfulCommandAsFailed() = runBlocking {
        val butler = ButlerRepository(object : ButlerApi {
            override suspend fun talk(authorization: String, request: ButlerRequestDto) = Response.success(
                ButlerResponseDto("Created", listOf(ChangedEntityDto("daily_event", "event", listOf(today))))
            )
        }, auth, events)
        server.failPull = true
        val result = butler.send("order", "Create event")
        assertEquals("Created", result.response)
        assertTrue(result.syncPending)
    }

    @Test fun existingTokenRegistersAfterLoginAndRestorationAndUnregistersBeforeLogout() = runBlocking {
        val registered = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val api = object : PushApi {
            override suspend fun register(authorization: String, request: PushDeviceRequestDto): Response<Unit> {
                registered += request.registrationToken
                return Response.success(Unit)
            }
            override suspend fun unregister(authorization: String, request: PushDeviceRequestDto): Response<Unit> {
                assertTrue(auth.hasSession())
                removed += request.registrationToken
                return Response.success(Unit)
            }
        }
        lateinit var push: PushRegistrationRepository
        auth = AuthRepository(FakeAuth(), store, { authWakeups++ }, { push.unregisterCurrent() })
        push = PushRegistrationRepository(context, api, auth, currentToken = { "already-existing-fcm-token" })
        val previousWakeups = authWakeups
        auth.login("test@example.test", "password")
        assertEquals(previousWakeups + 1, authWakeups)
        push.registerCurrent() // Execute the work requested by onAuthenticated; no onNewToken event.
        assertTrue(auth.restoreSession())
        assertEquals(previousWakeups + 2, authWakeups)
        push.registerCurrent()
        assertEquals(listOf("already-existing-fcm-token", "already-existing-fcm-token"), registered)
        auth.logout()
        assertEquals(listOf("already-existing-fcm-token"), removed)
        assertFalse(auth.hasSession())
        push.registerCurrent()
        assertEquals(2, registered.size)
    }

    @Test fun playbackClaimRejectsSkippedAndChangedContentAndDeduplicates() = runBlocking {
        val dao = database.dailyEventDao()
        val event = local().copy(speakAloud = true, content = "Prepared offline speech", status = "skipped")
        dao.upsert(event)
        assertEquals(0, dao.claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
        dao.upsert(event.copy(status = "planned"))
        assertEquals(0, dao.claimPlayback(event.id, "now", today, "19:00", "old content", 0))
        assertEquals(1, dao.claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
        assertEquals(0, dao.claimPlayback(event.id, "now", today, "19:00", event.content!!, 0))
    }

    private inner class FakeSync : SyncApi {
        var canonical: SyncEventDto? = null
        var failPush = false
        var failPull = false
        var conflict = false
        override suspend fun syncEvents(authorization: String, request: SyncBatchRequestDto): Response<SyncBatchResultDto> {
            if (failPush) throw IOException("offline")
            calls += "push"
            return Response.success(SyncBatchResultDto(request.operations.map { operation ->
                if (!conflict) {
                    val value = operation.event!!
                    canonical = SyncEventDto(
                        value.id, "plan", value.eventDate, value.title, value.description, value.eventType,
                        value.status, value.startTime, value.endTime, value.durationMinutes,
                        value.scheduledPrecision, value.content, value.reminderMinutesBefore,
                        value.speakAloud, value.sortOrder, operation.baseVersion + 1, "user", "now",
                    )
                }
                EventSyncResultDto(operation.operationId, if (conflict) "conflict" else "applied", canonical)
            }))
        }
        override suspend fun dailyPlan(authorization: String, date: String): Response<DailyPlanSnapshotDto> {
            if (failPull) throw IOException("offline")
            calls += "pull"
            return Response.success(DailyPlanSnapshotDto(
                SyncPlanDto("plan-$date", date, "planned", "now"), listOfNotNull(canonical?.takeIf { it.eventDate == date }),
            ))
        }
    }

    private class FakeAuth : AuthApi {
        private fun tokens() = Response.success(AuthTokensDto("access", "refresh", "bearer", 900))
        override suspend fun login(request: EmailAuthRequest) = tokens()
        override suspend fun register(request: EmailAuthRequest) = tokens()
        override suspend fun google(request: GoogleAuthRequest) = tokens()
        override suspend fun refresh(request: RefreshRequest) = tokens()
        override suspend fun logout(request: LogoutRequest): Response<Unit> = Response.success(Unit)
    }

    private class UnusedPlanning : PlanningApi {
        override suspend fun prepare(authorization: String, request: PrepareDayRequestDto): Response<ResponseBody> = error("unused")
        override suspend fun prepareEvening(authorization: String, request: EveningPrepareRequestDto): Response<EveningPreparationResultDto> = error("unused")
    }
}
