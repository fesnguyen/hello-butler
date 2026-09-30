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

enum class TtsMethod { OPEN_SOURCE, OPENAI }

data class UserSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val savingItem: Boolean = false,
    val displayName: String = "",
    val email: String = "",
    val credits: Int = 0,
    val ttsMethod: TtsMethod = TtsMethod.OPEN_SOURCE,
    val items: List<SavedContextEntity> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
)

class UserSettingsViewModel(private val repository: UserSettingsDataSource) : ViewModel() {
    private val _state = MutableStateFlow(UserSettingsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.observeContext().collect { items ->
            _state.update { it.copy(items = items) }
        } }
    }

    fun refresh() = viewModelScope.launch {
        try {
            setCanonical(repository.get())
            repository.synchronize()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = error.message) }
        }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, saved = false) }
    fun setTtsMethod(value: TtsMethod) = _state.update { it.copy(ttsMethod = value, saved = false) }

    fun saveItem(id: String, content: String, isPreference: Boolean, version: Int, onSaved: () -> Unit) =
        mutateItem(onSaved) { repository.saveContext(id, SavedContextUpdateDto(content.trim(), isPreference, version)) }

    fun deleteItem(item: SavedContextEntity, onDeleted: () -> Unit) =
        mutateItem(onDeleted) { repository.deleteContext(item) }

    private fun mutateItem(onSuccess: () -> Unit, mutation: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.savingItem) return@launch
        _state.update { it.copy(savingItem = true, error = null) }
        try {
            mutation()
            onSuccess()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message) }
        } finally { _state.update { it.copy(savingItem = false) } }
    }

    fun save() = viewModelScope.launch {
        val draft = _state.value
        if (draft.saving) return@launch
        _state.update { it.copy(saving = true, error = null, saved = false) }
        try {
            setCanonical(repository.save(UserSettingsUpdateDto(
                displayName = draft.displayName.trim().ifEmpty { null }, ttsMethod = draft.ttsMethod.name,
            )), saved = true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(saving = false, error = error.message) }
        }
    }

    private fun setCanonical(value: UserSettingsDto, saved: Boolean = false) {
        _state.update { it.copy(
            loading = false, saving = false, displayName = value.displayName.orEmpty(),
            email = value.email.orEmpty(), credits = value.credits,
            ttsMethod = runCatching { TtsMethod.valueOf(value.ttsMethod) }.getOrDefault(TtsMethod.OPEN_SOURCE),
            saved = saved, error = null,
        ) }
    }

    companion object {
        fun factory(repository: UserSettingsDataSource): ViewModelProvider.Factory = viewModelFactory {
            initializer { UserSettingsViewModel(repository) }
        }
    }
}
