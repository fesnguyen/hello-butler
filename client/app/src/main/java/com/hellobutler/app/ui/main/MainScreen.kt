package com.hellobutler.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.speech.SpeechInputController

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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        capturing = true
        viewModel.beginCapture(mode)
        speech.start(viewModel::updateTranscript, viewModel::finishCapture, viewModel::captureError)
    }

    fun stopCapture() {
        if (!capturing) return
        capturing = false
        speech.stop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Today"); Text(java.time.LocalDate.now().toString(), style = MaterialTheme.typography.labelMedium) } },
                actions = { TextButton(onClick = onLogout) { Text("Logout") } },
            )
        },
        bottomBar = { ButlerControlBar(enabled = !state.processing, ::startCapture, ::stopCapture) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (events.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Your day is clear", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Hold Order or Talk and ask Butler to add something.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(events, key = { it.id }) { event -> EventRow(event) { selectedEvent = event } }
                }
            }

            if (state.overlayVisible) {
                ConversationOverlay(
                    state = state,
                    onDraftChanged = viewModel::editDraft,
                    onSendOrder = { viewModel.sendDraft(CaptureMode.ORDER) },
                    onSendTalk = { viewModel.sendDraft(CaptureMode.TALK) },
                    onClose = viewModel::dismissOverlay,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    selectedEvent?.let { event ->
        EventDetailDialog(event, onDismiss = { selectedEvent = null }) {
            viewModel.updateEvent(it)
            selectedEvent = null
        }
    }
}

@Composable
private fun EventRow(event: DailyEventEntity, onConfigure: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(event.startTime ?: "--:--", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text(event.status, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onConfigure) { Icon(Icons.Default.MoreVert, contentDescription = "Configure event") }
        }
    }
}

@Composable
private fun ConversationOverlay(
    state: MainUiState,
    onDraftChanged: (String) -> Unit,
    onSendOrder: () -> Unit,
    onSendTalk: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Butler", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            state.messages.takeLast(6).forEach { message ->
                Text(
                    (if (message.role == "user") "You: " else "Butler: ") + message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.transcript.isNotBlank()) Text("You: ${state.transcript}")
            state.textDraft?.let { draft ->
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Edit transcript") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSendOrder, enabled = !state.processing) { Text("Send as Order") }
                    OutlinedButton(onClick = onSendTalk, enabled = !state.processing) { Text("Send as Talk") }
                }
            }
            if (state.processing) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun EventDetailDialog(
    event: DailyEventEntity,
    onDismiss: () -> Unit,
    onSave: (DailyEventEntity) -> Unit,
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var startTime by remember(event.id) { mutableStateOf(event.startTime.orEmpty()) }
    var status by remember(event.id) { mutableStateOf(event.status) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startTime, { startTime = it }, label = { Text("Time (HH:mm)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("planned", "completed", "skipped").forEach { value ->
                        OutlinedButton(onClick = { status = value }, enabled = status != value) { Text(value) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(event.copy(title = title.trim(), startTime = startTime.trim().ifEmpty { null }, status = status, version = event.version + 1)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
