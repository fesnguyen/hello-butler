package com.hellobutler.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hellobutler.app.auth.AuthScreen
import com.hellobutler.app.auth.AuthViewModel
import com.hellobutler.app.core.AppContainer
import com.hellobutler.app.core.ButlerDestination
import com.hellobutler.app.core.ButlerNavigation
import com.hellobutler.app.data.local.SavedContextEntity
import com.hellobutler.app.ui.main.MainScreen
import com.hellobutler.app.ui.main.MainViewModel
import com.hellobutler.app.ui.settings.SavedContextEditor
import com.hellobutler.app.ui.settings.UserSettingsScreen
import com.hellobutler.app.ui.settings.UserSettingsViewModel
import java.util.UUID

@Composable
fun HelloButlerApp(container: AppContainer) {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container.authRepository))
    val authState by authViewModel.state.collectAsState()

    when {
        authState.checkingSession -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !authState.authenticated -> AuthScreen(authViewModel)
        else -> {
            var userSettingsOpen by remember { mutableStateOf(false) }
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(container.butlerRepository, container.dailyEventRepository)
            )
            val destination by ButlerNavigation.destination.collectAsState()
            var quickNote by remember { mutableStateOf<SavedContextEntity?>(null) }
            val userSettingsViewModel: UserSettingsViewModel = viewModel(
                factory = UserSettingsViewModel.factory(container.userSettingsRepository, container.soundVoice)
            )
            LaunchedEffect(destination) {
                destination?.let {
                    userSettingsOpen = false
                    when (it) {
                        ButlerDestination.HOME -> { quickNote = null; mainViewModel.dismissOverlay() }
                        ButlerDestination.CONVERSATION -> { quickNote = null; mainViewModel.openConversation() }
                        ButlerDestination.TALK -> { quickNote = null; mainViewModel.prepareTalk() }
                        ButlerDestination.NOTE -> quickNote = SavedContextEntity(UUID.randomUUID().toString(), "", false, 0)
                    }
                    ButlerNavigation.navigationConsumed()
                }
            }
            quickNote?.let { item -> SavedContextEditor(userSettingsViewModel, item) { quickNote = null } }
            if (userSettingsOpen) {
                UserSettingsScreen(userSettingsViewModel) { userSettingsOpen = false }
            } else {
                MainScreen(
                    mainViewModel,
                    onUserSettings = { userSettingsOpen = true },
                    onLogout = { mainViewModel.logout(authViewModel::logout) },
                )
            }
        }
    }
}
