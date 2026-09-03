package com.hellobutler.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.data.repository.ButlerRepository
import com.hellobutler.app.data.repository.DailyEventRepository
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptureMode { ORDER, TALK, TEXT }
data class ConversationMessage(val role: String, val text: String)

data class MainUiState(
    val overlayVisible: Boolean = false,
    val captureMode: CaptureMode? = null,
    val transcript: String = "",
    val textDraft: String? = null,
    val processing: Boolean = false,
    val messages: List<ConversationMessage> = emptyList(),
    val error: String? = null,
)

class MainViewModel(
    private val butler: ButlerRepository,
    private val eventsRepository: DailyEventRepository,
) : ViewModel() {
    private val today = LocalDate.now().toString()
    val events: StateFlow<List<DailyEventEntity>> = eventsRepository.observeDate(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    fun beginCapture(mode: CaptureMode) {
        _state.update { it.copy(overlayVisible = true, captureMode = mode, transcript = "", textDraft = null, error = null) }
    }

    fun updateTranscript(text: String) = _state.update { it.copy(transcript = text) }

    fun finishCapture(text: String) {
        val mode = _state.value.captureMode ?: return
        val message = text.trim()
        if (message.isEmpty()) {
            _state.update { it.copy(error = "I didn't catch that. Try again.") }
            return
        }
        if (mode == CaptureMode.TEXT) {
            _state.update { it.copy(transcript = message, textDraft = message) }
        } else {
            send(message, mode)
        }
    }

    fun captureError(message: String) = _state.update { it.copy(error = message) }
    fun editDraft(text: String) = _state.update { it.copy(textDraft = text) }
    fun dismissOverlay() = _state.update { it.copy(overlayVisible = false, captureMode = null, transcript = "", textDraft = null) }

    fun sendDraft(mode: CaptureMode) {
        val message = _state.value.textDraft?.trim().orEmpty()
        if (message.isNotEmpty()) send(message, mode)
    }

    fun updateEvent(event: DailyEventEntity) {
        viewModelScope.launch { eventsRepository.update(event) }
    }

    private fun send(message: String, mode: CaptureMode) {
        if (_state.value.processing) return
        val backendMode = if (mode == CaptureMode.ORDER) "order" else "talk"
        _state.update {
            it.copy(
                overlayVisible = true,
                captureMode = mode,
                textDraft = null,
                transcript = "",
                processing = true,
                error = null,
                messages = it.messages + ConversationMessage("user", message),
            )
        }
        viewModelScope.launch {
            runCatching { butler.send(backendMode, message) }.fold(
                onSuccess = { response ->
                    _state.update {
                        it.copy(
                            processing = false,
                            messages = it.messages + ConversationMessage("butler", response.response),
                        )
                    }
                    if (mode == CaptureMode.ORDER && !response.requiresFollowUp) {
                        delay(5_000)
                        _state.update { it.copy(overlayVisible = false, captureMode = null) }
                    }
                },
                onFailure = { error -> _state.update { it.copy(processing = false, error = error.message ?: "Butler request failed") } },
            )
        }
    }

    companion object {
        fun factory(
            butler: ButlerRepository,
            events: DailyEventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(butler, events) }
        }
    }
}
