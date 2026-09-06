package com.hellobutler.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hellobutler.app.auth.AuthScreen
import com.hellobutler.app.auth.AuthViewModel
import com.hellobutler.app.core.AppContainer
import com.hellobutler.app.ui.main.MainScreen
import com.hellobutler.app.ui.main.MainViewModel

@Composable
fun HelloButlerApp(container: AppContainer) {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container.authRepository))
    val authState by authViewModel.state.collectAsState()

    when {
        authState.checkingSession -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !authState.authenticated -> AuthScreen(authViewModel)
        else -> {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(container.butlerRepository, container.dailyEventRepository)
            )
            MainScreen(mainViewModel) { mainViewModel.logout(authViewModel::logout) }
        }
    }
}
