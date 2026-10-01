package com.hellobutler.app

import com.hellobutler.app.data.local.SavedContextEntity
import com.hellobutler.app.data.local.notes
import com.hellobutler.app.data.remote.*
import com.hellobutler.app.data.repository.UserSettingsDataSource
import com.hellobutler.app.ui.main.millisUntilNextMinute
import com.hellobutler.app.ui.settings.UserSettingsUiState
import com.hellobutler.app.ui.settings.UserSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import androidx.lifecycle.ViewModelStore

@OptIn(ExperimentalCoroutinesApi::class)
class UserSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = ContextSource()
    private lateinit var viewModel: UserSettingsViewModel
    private val store = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = UserSettingsViewModel(repository)
        store.put("settings", viewModel)
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun notesAndPreferencesAreSeparateAndQuickNotesExcludesPreferences() {
        val note = SavedContextEntity("note", "Long note\n".repeat(100), false, 2)
        val preference = SavedContextEntity("preference", "Beach days", true, 4)
        val state = UserSettingsUiState(items = listOf(preference, note))
        assertEquals(listOf(note), state.notes)
        assertEquals(listOf(preference), state.preferences)
        assertEquals(state.notes, state.items.notes()) // MainScreen uses this same projection.
        assertTrue(UserSettingsUiState().notes.isEmpty())
    }

    @Test fun addActionsUseNewStableUuidAndCorrectType() = runTest(dispatcher) {
        for (isPreference in listOf(false, true)) {
            val draft = viewModel.newItem(isPreference)
            assertEquals(draft.id, UUID.fromString(draft.id).toString())
            viewModel.saveItem(draft, "  Saved content  ") {}
            runCurrent()
            assertEquals(draft.id to SavedContextUpdateDto("Saved content", isPreference, 0), repository.lastSave)
            val items = viewModel.state.value.items
            assertEquals(isPreference, items.single { it.id == draft.id }.isPreference)
        }
        assertEquals(2, viewModel.state.value.items.size)
    }

    @Test fun editPreservesTypeIdAndVersionAndDeleteUpdatesObservedState() = runTest(dispatcher) {
        for (isPreference in listOf(false, true)) {
            val item = SavedContextEntity(UUID.randomUUID().toString(), "Original", isPreference, 7)
            repository.items.value = listOf(item)
            runCurrent()
            viewModel.saveItem(item, "Changed") {}
            runCurrent()
            assertEquals(item.id to SavedContextUpdateDto("Changed", isPreference, 7), repository.lastSave)
            val updated = viewModel.state.value.items.single()
            assertEquals(8, updated.version)
            viewModel.deleteItem(updated) {}
            runCurrent()
            assertEquals(updated, repository.lastDelete)
            assertTrue(viewModel.state.value.items.isEmpty())
        }
    }

    @Test fun failedSaveKeepsDraftIdentityForRetryAndDoesNotCloseEditor() = runTest(dispatcher) {
        val draft = viewModel.newItem(false)
        var closed = false
        repository.failSave = true
        viewModel.saveItem(draft, "Note") { closed = true }
        runCurrent()
        assertFalse(closed)
        assertFalse(viewModel.state.value.savingItem)
        assertNotNull(viewModel.state.value.error)
        repository.failSave = false
        viewModel.saveItem(draft, "Note") { closed = true }
        runCurrent()
        assertTrue(closed)
        assertEquals(draft.id, repository.items.value.single().id)
    }

    @Test fun externalButlerReconciliationUpdatesSettingsAndQuickNotesProjection() = runTest(dispatcher) {
        runCurrent()
        val note = SavedContextEntity("butler-note", "Remember this", false, 1)
        repository.items.value = listOf(note, SavedContextEntity("butler-preference", "Beach", true, 1))
        runCurrent()
        assertEquals(listOf(note), viewModel.state.value.notes)
        assertEquals(listOf(note), repository.items.value.notes())
        repository.items.value = emptyList()
        runCurrent()
        assertTrue(viewModel.state.value.notes.isEmpty())
    }

    @Test fun headerUpdatesAtMinuteBoundaryWithoutBusyLoop() {
        assertEquals(60_000L, millisUntilNextMinute(0))
        assertEquals(1L, millisUntilNextMinute(59_999))
        assertEquals(60_000L, millisUntilNextMinute(60_000))
        assertEquals(30_000L, millisUntilNextMinute(90_000))
    }

    private class ContextSource : UserSettingsDataSource {
        val items = MutableStateFlow(emptyList<SavedContextEntity>())
        var lastSave: Pair<String, SavedContextUpdateDto>? = null
        var lastDelete: SavedContextEntity? = null
        var failSave = false
        override fun observeContext() = items
        override suspend fun synchronize() {}
        override suspend fun saveContext(id: String, update: SavedContextUpdateDto) {
            lastSave = id to update
            if (failSave) error("Offline")
            items.value = items.value.filterNot { it.id == id } +
                SavedContextEntity(id, update.content, update.isPreference, update.baseVersion + 1)
        }
        override suspend fun deleteContext(item: SavedContextEntity) {
            lastDelete = item
            items.value = items.value.filterNot { it.id == item.id }
        }
        override suspend fun get() = UserSettingsDto("Name", "email", 5, "OPEN_SOURCE", emptyList())
        override suspend fun save(update: UserSettingsUpdateDto) = get()
    }
}
