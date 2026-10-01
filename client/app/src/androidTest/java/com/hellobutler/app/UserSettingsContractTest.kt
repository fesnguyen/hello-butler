package com.hellobutler.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.local.ButlerDatabase
import com.hellobutler.app.data.remote.*
import com.hellobutler.app.data.repository.UserSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class UserSettingsContractTest {
    private lateinit var store: SecureSessionStore
    private lateinit var database: ButlerDatabase
    private lateinit var repository: UserSettingsRepository
    private val api = FakeUserSettingsApi()

    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecureSessionStore(context).also { it.clear() }
        database = Room.inMemoryDatabaseBuilder(context, ButlerDatabase::class.java).build()
        val auth = AuthRepository(FakeAuth(), store, {}, {})
        auth.login("user@example.test", "password")
        repository = UserSettingsRepository(api, auth, database.savedContextDao())
    }

    @After fun cleanup() { database.close(); store.clear() }

    @Test fun accountSaveCannotWriteCreditsOrDeleteSavedItems() = runBlocking {
        assertEquals(5, repository.get().credits)
        val update = UserSettingsUpdateDto("New Name", "OPENAI")
        repository.save(update)
        assertEquals(update, api.saved)
        val encoded = Json.encodeToString(update)
        assertFalse(encoded.contains("credits"))
        assertFalse(encoded.contains("preference_ids"))
    }

    @Test fun conversationProjectionReconcilesWithoutDuplicatesAndRemovesDeletedRows() = runBlocking {
        api.items = listOf(SavedContextDto("context-1", "I love the beach", true, 1))
        repository.synchronize()
        repository.synchronize()
        assertEquals(1, repository.observeContext().first().size)
        assertEquals("context-1", repository.observeContext().first().single().id)
        api.items = emptyList()
        repository.synchronize()
        assertTrue(repository.observeContext().first().isEmpty())
    }

    @Test fun addEditSwitchAndDeleteUseStableIdentity() = runBlocking {
        repository.saveContext("context-1", SavedContextUpdateDto("A note", false, 0))
        var item = repository.observeContext().first().single()
        assertFalse(item.isPreference)
        repository.saveContext(item.id, SavedContextUpdateDto("A preference", true, item.version))
        item = repository.observeContext().first().single()
        assertTrue(item.isPreference)
        assertEquals("context-1", item.id)
        assertEquals("A preference", item.content)
        repository.deleteContext(item)
        assertTrue(repository.observeContext().first().isEmpty())
        repository.synchronize()
        assertTrue(repository.observeContext().first().isEmpty())
    }

    @Test fun logoutClearsCachedContext() = runBlocking {
        repository.saveContext("context-1", SavedContextUpdateDto("Private note", false, 0))
        repository.clear()
        assertTrue(repository.observeContext().first().isEmpty())
    }

    @Test fun migrationFromVersionFourPreservesConversationCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "saved-context-migration-test.db"
        context.deleteDatabase(name)
        val old = Room.databaseBuilder(context, ButlerDatabase::class.java, name).build()
        val sqlite = old.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO conversation_messages (id, requestId, role, text, createdAt, deliveryState, inputSource, audioCacheState) VALUES ('message', 'request', 'butler', 'Keep this text', 'now', 'completed', 'text', 'unavailable')")
        sqlite.execSQL("DROP TABLE saved_context")
        sqlite.version = 4
        old.close()
        val migrated = Room.databaseBuilder(context, ButlerDatabase::class.java, name)
            .addMigrations(ButlerDatabase.MIGRATION_4_5).build()
        try {
            assertEquals("Keep this text", migrated.butlerConversationDao().message("request", "butler")?.text)
            assertTrue(migrated.savedContextDao().observe().first().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    private class FakeAuth : AuthApi {
        private fun tokens() = Response.success(AuthTokensDto("access", "refresh", "bearer", 900))
        override suspend fun login(request: EmailAuthRequest) = tokens()
        override suspend fun register(request: EmailAuthRequest) = tokens()
        override suspend fun google(request: GoogleAuthRequest) = tokens()
        override suspend fun refresh(request: RefreshRequest) = tokens()
        override suspend fun logout(request: LogoutRequest): Response<Unit> = Response.success(Unit)
    }
}

internal class FakeUserSettingsApi : UserSettingsApi {
    var items = emptyList<SavedContextDto>()
    var saved: UserSettingsUpdateDto? = null
    override suspend fun get(authorization: String) = Response.success(UserSettingsDto("Name", "user@example.test", 5, "OPEN_SOURCE", emptyList()))
    override suspend fun update(authorization: String, update: UserSettingsUpdateDto): Response<UserSettingsDto> {
        saved = update
        return Response.success(UserSettingsDto(update.displayName, "user@example.test", 5, update.ttsMethod, emptyList()))
    }
    override suspend fun listContext(authorization: String) = Response.success(items)
    override suspend fun saveContext(authorization: String, id: String, update: SavedContextUpdateDto): Response<SavedContextDto> {
        val item = SavedContextDto(id, update.content, update.isPreference, update.baseVersion + 1)
        items = items.filterNot { it.id == id } + item
        return Response.success(item)
    }
    override suspend fun deleteContext(authorization: String, id: String, version: Int): Response<Unit> {
        items = items.filterNot { it.id == id }
        return Response.success(Unit)
    }
}
