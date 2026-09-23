package com.hellobutler.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hellobutler.app.data.remote.SavedPreferenceDto
import com.hellobutler.app.data.remote.UserSettingsDto
import com.hellobutler.app.data.remote.UserSettingsUpdateDto
import com.hellobutler.app.data.repository.UserSettingsDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TtsMethod { OPEN_SOURCE, OPENAI }

data class UserSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val displayName: String = "",
    val email: String = "",
    val credits: Int = 0,
    val ttsMethod: TtsMethod = TtsMethod.OPEN_SOURCE,
    val preferences: List<SavedPreferenceDto> = emptyList(),
    val knownPreferenceIds: List<String> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
)

class UserSettingsViewModel(private val repository: UserSettingsDataSource) : ViewModel() {
    private val _state = MutableStateFlow(UserSettingsUiState())
    val state: StateFlow<UserSettingsUiState> = _state.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val settings = repository.get()
            setCanonical(settings)
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = error.message,
                )
            }
        }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, saved = false) }
    fun setTtsMethod(value: TtsMethod) = _state.update { it.copy(ttsMethod = value, saved = false) }
    fun removePreference(id: String) = _state.update {
        it.copy(preferences = it.preferences.filterNot { preference -> preference.id == id }, saved = false)
    }

    fun save() = viewModelScope.launch {
        val draft = _state.value
        if (draft.saving) return@launch
        _state.update { it.copy(saving = true, error = null, saved = false) }
        runCatching {
            repository.save(
                UserSettingsUpdateDto(
                    displayName = draft.displayName.trim().ifEmpty { null },
                    ttsMethod = draft.ttsMethod.name,
                    knownPreferenceIds = draft.knownPreferenceIds,
                    preferenceIds = draft.preferences.map(SavedPreferenceDto::id),
                )
            )
        }.fold({ canonical -> setCanonical(canonical, saved = true) }) { error ->
            _state.update { it.copy(saving = false, error = error.message) }
        }
    }

    private fun setCanonical(value: UserSettingsDto, saved: Boolean = false) {
        _state.value = UserSettingsUiState(
            loading = false,
            displayName = value.displayName.orEmpty(),
            email = value.email.orEmpty(),
            credits = value.credits,
            ttsMethod = runCatching { TtsMethod.valueOf(value.ttsMethod) }
                .getOrDefault(TtsMethod.OPEN_SOURCE),
            preferences = value.preferences,
            knownPreferenceIds = value.preferences.map(SavedPreferenceDto::id),
            saved = saved,
        )
    }

    companion object {
        fun factory(repository: UserSettingsDataSource): ViewModelProvider.Factory = viewModelFactory {
            initializer { UserSettingsViewModel(repository) }
        }
    }
}
