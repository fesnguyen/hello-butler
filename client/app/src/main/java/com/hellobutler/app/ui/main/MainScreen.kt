package com.hellobutler.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.speech.SpeechInputController
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.filled.Refresh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onLogout: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val context = LocalContext.current
    val speech = remember { SpeechInputController(context) }
    var capturing by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<DailyEventEntity?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.captureError("Microphone permission is required for voice input")
    }

    DisposableEffect(Unit) { onDispose { speech.destroy() } }

    fun startCapture(mode: CaptureMode) {
        if (capturing || state.processing) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        capturing = true
        viewModel.beginCapture(mode)
        speech.start(
            onPartial = viewModel::updateTranscript,
            onFinal = { text -> capturing = false; viewModel.finishCapture(text) },
            onError = { error -> capturing = false; viewModel.captureError(error) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TodayHeader(onLogout, viewModel::refreshPreparedDays) },
        bottomBar = {
            ButlerControlBar(!state.processing && !capturing, state.captureMode, capturing, ::startCapture)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyDay { startCapture(CaptureMode.ORDER) } }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Your day", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            val done = events.count { it.status.equals("completed", true) }
                            Text(done.toString() + " of " + events.size + " complete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(events, key = { it.id }) { event -> DailyEventCard(event) { selectedEvent = event } }
                }
            }
            if (state.overlayVisible) {
                ButlerConversationOverlay(
                    state, capturing, viewModel::editDraft,
                    { viewModel.sendDraft(CaptureMode.ORDER) }, { viewModel.sendDraft(CaptureMode.TALK) },
                    viewModel::dismissOverlay,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    selectedEvent?.let { event ->
        EventDetailDialog(event, { selectedEvent = null }) {
            viewModel.updateEvent(it)
            selectedEvent = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayHeader(onLogout: () -> Unit, onRefresh: () -> Unit) {
    val today = remember { LocalDate.now() }
    TopAppBar(
        title = {
            Column {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE")), style = MaterialTheme.typography.headlineMedium)
                Text(today.format(DateTimeFormatter.ofPattern("MMMM d")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            Surface(Modifier.padding(start = 14.dp, end = 6.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
        actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh prepared days") }
            IconButton(onClick = onLogout) { Icon(Icons.Outlined.AccountCircle, "Account and logout") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
