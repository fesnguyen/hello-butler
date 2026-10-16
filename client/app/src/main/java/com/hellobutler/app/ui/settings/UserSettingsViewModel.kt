
package com.hellobutler.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hellobutler.app.data.local.SavedContextEntity
import com.hellobutler.app.data.remote.SavedContextUpdateDto
import com.hellobutler.app.data.remote.UserSettingsDto
import com.hellobutler.app.data.remote.UserSettingsUpdateDto
import com.hellobutler.app.data.repository.UserSettingsDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


// ------------------------------------------------------------
// Models - Settings State
// ------------------------------------------------------------

/**
 * Defines the supported text-to-speech providers.
 *
 * Provides a type-safe representation of the user's
 * preferred speech generation method.
 */
enum class TtsMethod {
    OPEN_SOURCE,
    OPENAI,
}

/**
 * Represents all state required by the User Settings screen.
 *
 * Keeps account information, preferences, saved context,
 * and operation statuses together for reactive UI updates.
 */
data class UserSettingsUiState(
    // Track initial data loading and settings persistence.
    val loading: Boolean = true,
    val saving: Boolean = false,

    // Track note/preference mutations independently of settings saves.
    val savingItem: Boolean = false,

    // Hold editable account information and available credits.
    val displayName: String = "",
    val email: String = "",
    val credits: Int = 0,

    // Default to open-source speech until settings are loaded.
    val ttsMethod: TtsMethod = TtsMethod.OPEN_SOURCE,

    // Store locally observed notes and preferences.
    val items: List<SavedContextEntity> = emptyList(),

    // Report operation failures and successful settings saves.
    val error: String? = null,
    val saved: Boolean = false,
)


// ------------------------------------------------------------
// ViewModel - User Settings Orchestration
// ------------------------------------------------------------

/**
 * Coordinates account settings and saved-context operations.
 *
 * Connects the UI to the repository, manages asynchronous
 * operations, and exposes a consistent observable state.
 */
class UserSettingsViewModel(
    private val repository: UserSettingsDataSource,
    val soundVoice: com.hellobutler.app.execution.audio.SoundVoiceSettings? = null,
) : ViewModel() {

    // Hold mutable UI state privately to control state updates.
    private val _state = MutableStateFlow(UserSettingsUiState())

    // Expose read-only state so UI components can observe changes.
    val state = _state.asStateFlow()

    // --------------------------------------------------------
    // Initialization - Observe Saved Context
    // --------------------------------------------------------

    init {
        // Keep notes/preferences synchronized with repository changes.
        viewModelScope.launch {
            repository.observeContext().collect { items ->

                // Update only saved items without replacing other UI state.
                _state.update {
                    it.copy(items = items)
                }
            }
        }
    }

    // --------------------------------------------------------
    // Entry Point - Refresh Settings
    // --------------------------------------------------------

    /**
     * Loads the current account settings and synchronizes saved context.
     *
     * Establishes the canonical settings state when the screen opens
     * while requesting repository synchronization.
     */
    fun refresh() = viewModelScope.launch {
        try {
            // Retrieve authoritative settings and apply them to the UI.
            setCanonical(repository.get())

            // Synchronize locally stored context with its data source.
            repository.synchronize()

        } catch (cancelled: CancellationException) {
            // Preserve coroutine cancellation instead of treating it as an error.
            throw cancelled

        } catch (error: Exception) {
            // Stop loading and expose the failure to the UI.
            _state.update {
                it.copy(
                    loading = false,
                    error = error.message,
                )
            }
        }
    }

    // --------------------------------------------------------
    // Entry Points - Edit Settings
    // --------------------------------------------------------

    /**
     * Updates the editable display name in local UI state.
     *
     * Allows immediate field updates without persisting every keystroke
     * and invalidates the previous save confirmation.
     */
    fun setDisplayName(value: String) = _state.update {
        it.copy(
            displayName = value,
            saved = false,
        )
    }

    /**
     * Updates the selected text-to-speech provider.
     *
     * Keeps provider selection reactive while deferring
     * persistence until the user saves their settings.
     */
    fun setTtsMethod(value: TtsMethod) = _state.update {
        it.copy(
            ttsMethod = value,
            saved = false,
        )
    }

    // --------------------------------------------------------
    // Entry Point - Save Account Settings
    // --------------------------------------------------------

    /**
     * Persists the user's account settings and TTS preference.
     *
     * Prevents duplicate saves, normalizes the submitted name,
     * and replaces local state with the canonical saved response.
     */
    fun save() = viewModelScope.launch {

        // Capture current settings as a consistent save request.
        val draft = _state.value

        // Ignore another save request while one is already in progress.
        if (draft.saving) return@launch

        // Mark saving active and clear previous operation feedback.
        _state.update {
            it.copy(
                saving = true,
                error = null,
                saved = false,
            )
        }

        try {
            // Persist editable settings and apply the server response.
            setCanonical(
                repository.save(
                    UserSettingsUpdateDto(
                        // Trim whitespace; submit null for an empty name.
                        displayName = draft.displayName
                            .trim()
                            .ifEmpty { null },

                        // Convert the selected enum into its API representation.
                        ttsMethod = draft.ttsMethod.name,
                    )
                ),
                saved = true,
            )

        } catch (cancelled: CancellationException) {
            // Let structured concurrency handle coroutine cancellation.
            throw cancelled

        } catch (error: Exception) {
            // Restore the save button and display the failure.
            _state.update {
                it.copy(
                    saving = false,
                    error = error.message,
                )
            }
        }
    }

    // --------------------------------------------------------
    // Entry Points - Saved Notes & Preferences
    // --------------------------------------------------------

    /**
     * Creates or updates a saved note or preference.
     *
     * Normalizes the content before persistence and delegates
     * operation tracking and completion handling to mutateItem.
     */
    fun saveItem(
        id: String,
        content: String,
        isPreference: Boolean,
        version: Int,
        onSaved: () -> Unit,
    ) = mutateItem(onSaved) {

        // Persist the entry using its ID and current version.
        repository.saveContext(
            id,
            SavedContextUpdateDto(
                content.trim(),
                isPreference,
                version,
            ),
        )
    }

    /**
     * Deletes an existing saved note or preference.
     *
     * Reuses the shared mutation lifecycle so deletion has
     * the same loading, error, and completion behavior as saving.
     */
    fun deleteItem(
        item: SavedContextEntity,
        onDeleted: () -> Unit,
    ) = mutateItem(onDeleted) {

        // Request deletion of the selected saved context entry.
        repository.deleteContext(item)
    }

    // --------------------------------------------------------
    // Internal Logic - Saved Context Mutations
    // --------------------------------------------------------

    /**
     * Executes a saved-context mutation with shared state management.
     *
     * Prevents overlapping item operations, reports failures,
     * invokes completion only on success, and clears busy state.
     */
    private fun mutateItem(
        onSuccess: () -> Unit,
        mutation: suspend () -> Unit,
    ) = viewModelScope.launch {

        // Prevent concurrent note/preference mutations.
        if (_state.value.savingItem) return@launch

        // Mark item operation active and clear previous errors.
        _state.update {
            it.copy(
                savingItem = true,
                error = null,
            )
        }

        try {
            // Execute the supplied save or delete operation.
            mutation()

            // Notify the caller only after successful completion.
            onSuccess()

        } catch (cancelled: CancellationException) {
            // Propagate cancellation rather than reporting it as a failure.
            throw cancelled

        } catch (error: Exception) {
            // Expose mutation failures without discarding existing state.
            _state.update {
                it.copy(error = error.message)
            }

        } finally {
            // Always clear the busy flag, including on failure or cancellation.
            _state.update {
                it.copy(savingItem = false)
            }
        }
    }

    // --------------------------------------------------------
    // Internal Logic - Canonical State Mapping
    // --------------------------------------------------------

    /**
     * Applies authoritative account settings to the UI state.
     *
     * Centralizes DTO-to-UI conversion and ensures loading,
     * saving, and operation feedback are updated consistently.
     */
    private fun setCanonical(
        value: UserSettingsDto,
        saved: Boolean = false,
    ) {
        // Replace account fields while preserving unrelated state.
        _state.update {
            it.copy(
                // Complete active loading and settings-saving operations.
                loading = false,
                saving = false,

                // Convert nullable API fields into UI-safe strings.
                displayName = value.displayName.orEmpty(),
                email = value.email.orEmpty(),

                // Display the authoritative available credit balance.
                credits = value.credits,

                // Parse the API provider name; default on unknown values.
                ttsMethod = runCatching {
                    TtsMethod.valueOf(value.ttsMethod)
                }.getOrDefault(TtsMethod.OPEN_SOURCE),

                // Reflect save success and clear previous errors.
                saved = saved,
                error = null,
            )
        }
    }

    // --------------------------------------------------------
    // Factory - ViewModel Construction
    // --------------------------------------------------------

    companion object {

        /**
         * Creates the ViewModel factory with its required dependencies.
         *
         * Keeps repository and optional audio-settings injection
         * outside the UI and centralizes ViewModel construction.
         */
        fun factory(
            repository: UserSettingsDataSource,
            soundVoice: com.hellobutler.app.execution.audio.SoundVoiceSettings? = null,
        ): ViewModelProvider.Factory = viewModelFactory {

            // Construct the ViewModel using the supplied dependencies.
            initializer {
                UserSettingsViewModel(repository, soundVoice)
            }
        }
    }
}
