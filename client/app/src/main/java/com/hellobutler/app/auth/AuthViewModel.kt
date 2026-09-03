package com.hellobutler.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val checkingSession: Boolean = true,
    val loading: Boolean = false,
    val authenticated: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val restored = repository.restoreSession()
            _state.value = AuthUiState(checkingSession = false, authenticated = restored)
        }
    }

    fun login(email: String, password: String) = authenticate { repository.login(email, password) }
    fun register(email: String, password: String) = authenticate { repository.register(email, password) }
    fun google(idToken: String) = authenticate { repository.google(idToken) }
    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun logout() {
        viewModelScope.launch {
            runCatching { repository.logout() }
            _state.value = AuthUiState(checkingSession = false)
        }
    }

    private fun authenticate(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }.fold(
                onSuccess = { _state.value = AuthUiState(checkingSession = false, authenticated = true) },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "Authentication failed") }
                },
            )
        }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}
