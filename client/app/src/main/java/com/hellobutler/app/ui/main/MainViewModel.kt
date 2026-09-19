package com.hellobutler.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptureMode { ORDER, TALK, TEXT }

internal const val MINIMUM_VOICE_CAPTURE_MILLIS = 1_000L

internal fun shouldDiscardVoiceCapture(elapsedMillis: Long): Boolean =
    elapsedMillis < MINIMUM_VOICE_CAPTURE_MILLIS

data class MainUiState(
    val overlayVisible: Boolean = false,
    val captureMode: CaptureMode? = null,
    val textDraft: String? = null,
    val recording: Boolean = false,
    val recreatingToday: Boolean = false,
    val error: String? = null,
)

class MainViewModel(private val butler: ButlerRepository, private val eventsRepository: DailyEventRepository) : ViewModel() {
    private val today = LocalDate.now().toString()
    val events: StateFlow<List<DailyEventEntity>> = eventsRepository.observeDate(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages = butler.observeMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init { refreshPreparedDays(); viewModelScope.launch { butler.recover() } }
    fun openConversation() = _state.update { it.copy(overlayVisible = true) }

    fun beginRecording(mode: CaptureMode) {
        if (mode == CaptureMode.TEXT || _state.value.recording) return
        _state.update { it.copy(overlayVisible = true, captureMode = mode, textDraft = null, recording = true, error = null) }
    }

    fun finishRecording(file: File?) {
        val mode = _state.value.captureMode
        _state.update { it.copy(recording = false) }
        if (mode !in setOf(CaptureMode.ORDER, CaptureMode.TALK)) return
        if (file == null) { captureError("I couldn't record that. Please try again."); return }
        viewModelScope.launch {
            runCatching { butler.queueAudio(if (mode == CaptureMode.ORDER) "order" else "talk", file) }
                .onFailure { captureError(it.message ?: "Voice message could not be queued") }
        }
    }

    fun cancelRecording() {
        _state.update {
            it.copy(
                overlayVisible = false,
                captureMode = null,
                recording = false,
                error = null,
            )
        }
    }

    fun openTextComposer() = _state.update { it.copy(overlayVisible = true, captureMode = CaptureMode.TEXT, textDraft = it.textDraft ?: "", error = null) }
    fun editDraft(text: String) = _state.update { it.copy(textDraft = text) }
    fun sendText() {
        val message = _state.value.textDraft.orEmpty()
        if (message.isBlank()) return
        _state.update { it.copy(textDraft = "", error = null) }
        viewModelScope.launch { runCatching { butler.queueText(message) }.onFailure { captureError(it.message ?: "Message could not be queued") } }
    }
    fun captureError(message: String) = _state.update { it.copy(recording = false, error = message) }
    fun dismissOverlay() = _state.update { it.copy(overlayVisible = false, captureMode = null, textDraft = null, recording = false) }
    fun updateEvent(event: DailyEventEntity) { viewModelScope.launch { eventsRepository.update(event) } }
    fun deleteEvent(event: DailyEventEntity) { viewModelScope.launch { eventsRepository.delete(event) } }
    fun recreateTodayPlan() {
        if (_state.value.recreatingToday) return
        _state.update { it.copy(recreatingToday = true, error = null) }
        viewModelScope.launch {
            runCatching { eventsRepository.recreatePlan(today) }.fold(
                onSuccess = { _state.update { it.copy(recreatingToday = false) } },
                onFailure = { error -> _state.update { it.copy(recreatingToday = false, error = error.message ?: "Today's plan could not be recreated") } },
            )
        }
    }
    fun refreshPreparedDays() {
        viewModelScope.launch {
            val dates = listOf(LocalDate.now(), LocalDate.now().plusDays(1))
            runCatching { eventsRepository.synchronize(dates.map(LocalDate::toString)) }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Plan sync failed") } }
        }
    }
    fun logout(onCleared: () -> Unit) = onCleared()
    companion object {
        fun factory(butler: ButlerRepository, events: DailyEventRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(butler, events) }
        }
    }
}
