package com.hellobutler.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.auth.AuthRepository
import com.hellobutler.app.auth.SecureSessionStore
import com.hellobutler.app.data.remote.*
import com.hellobutler.app.data.repository.UserSettingsRepository
import com.hellobutler.app.data.repository.UserSettingsDataSource
import com.hellobutler.app.ui.settings.TtsMethod
import com.hellobutler.app.ui.settings.UserSettingsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class UserSettingsContractTest {
    private lateinit var store: SecureSessionStore
    private lateinit var auth: AuthRepository

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecureSessionStore(context).also { it.clear() }
        auth = AuthRepository(FakeAuth(), store, {}, {})
        auth.login("user@example.test", "password")
    }

    @After fun cleanup() = store.clear()

    @Test
    fun creditsAreReadOnlyAndCompleteDraftSavesTogether() = runBlocking {
        val api = FakeUserSettingsApi()
        val repository = UserSettingsRepository(api, auth)
        val loaded = repository.get()
        assertEquals(5, loaded.credits)

        val update = UserSettingsUpdateDto(
            displayName = "New Name",
            ttsMethod = "OPENAI",
            knownPreferenceIds = listOf("preference-1"),
            preferenceIds = listOf("preference-1"),
        )
        repository.save(update)
        assertEquals(update, api.saved)
        assertFalse(Json.encodeToString(update).contains("credits"))
    }

    @Test
    fun preferenceDeletionAndAllEditableFieldsRemainDraftUntilSave() = runBlocking {
        val source = FakeSettingsDataSource()
        val viewModel = UserSettingsViewModel(source)
        viewModel.refresh().join()
        viewModel.setDisplayName("New Name")
        viewModel.setTtsMethod(TtsMethod.OPENAI)
        viewModel.removePreference("preference-2")

        assertEquals(2, source.canonical.preferences.size) // Backend remains unchanged.
        assertEquals(listOf("preference-1"), viewModel.state.value.preferences.map { it.id })
        viewModel.save().join()
        assertEquals("New Name", source.saved?.displayName)
        assertEquals("OPENAI", source.saved?.ttsMethod)
        assertEquals(listOf("preference-1", "preference-2"), source.saved?.knownPreferenceIds)
        assertEquals(listOf("preference-1"), source.saved?.preferenceIds)
    }

    private class FakeSettingsDataSource : UserSettingsDataSource {
        val canonical = UserSettingsDto(
            displayName = "Name",
            email = "user@example.test",
            credits = 5,
            ttsMethod = "OPEN_SOURCE",
            preferences = listOf(
                SavedPreferenceDto("preference-1", "Keep briefs concise"),
                SavedPreferenceDto("preference-2", "Exercise after work"),
            ),
        )
        var saved: UserSettingsUpdateDto? = null
        override suspend fun get() = canonical
        override suspend fun save(update: UserSettingsUpdateDto): UserSettingsDto {
            saved = update
            return canonical.copy(
                displayName = update.displayName,
                ttsMethod = update.ttsMethod,
                preferences = canonical.preferences.filter { it.id in update.preferenceIds },
            )
        }
    }

    private class FakeUserSettingsApi : UserSettingsApi {
        var saved: UserSettingsUpdateDto? = null
        private val canonical = UserSettingsDto(
            displayName = "Name",
            email = "user@example.test",
            credits = 5,
            ttsMethod = "OPEN_SOURCE",
            preferences = listOf(SavedPreferenceDto("preference-1", "Keep briefs concise")),
        )

        override suspend fun get(authorization: String) = Response.success(canonical)
        override suspend fun update(authorization: String, update: UserSettingsUpdateDto): Response<UserSettingsDto> {
            saved = update
            return Response.success(canonical.copy(displayName = update.displayName, ttsMethod = update.ttsMethod))
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
}
