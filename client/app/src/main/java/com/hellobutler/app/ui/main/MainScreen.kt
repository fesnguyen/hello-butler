package com.hellobutler.app.ui.main

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hellobutler.app.execution.ScheduleRestoreWorker
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
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
import com.hellobutler.app.execution.SpeechForegroundService
import com.hellobutler.app.execution.SpeechPlaybackState
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import com.hellobutler.app.core.ButlerNavigation
import com.hellobutler.app.speech.ButlerAudioRecorder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onLogout: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    val speaking by SpeechPlaybackState.speaking.collectAsState()
    var exactAlarmsAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ScheduleRestoreWorker.enqueue(context)
        onPauseOrDispose { }
    }
    val recorder = remember { ButlerAudioRecorder(context) }
    var captureStartedAtMillis by remember { mutableLongStateOf(0L) }
    var selectedEvent by remember { mutableStateOf<DailyEventEntity?>(null) }
    var creatingEvent by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.captureError("Microphone permission is required for voice input")
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val openConversation by ButlerNavigation.openConversation.collectAsState()
    LaunchedEffect(openConversation) {
        if (openConversation) {
            viewModel.openConversation()
            ButlerNavigation.consumed()
        }
    }
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startCapture(mode: CaptureMode) {
        if (state.recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        runCatching { recorder.start() }.fold(
            onSuccess = {
                captureStartedAtMillis = SystemClock.elapsedRealtime()
                viewModel.beginRecording(mode)
            },
            onFailure = { viewModel.captureError(it.message ?: "Recording could not start") },
        )
    }

    fun finishCapture() {
        val elapsedMillis = SystemClock.elapsedRealtime() - captureStartedAtMillis
        captureStartedAtMillis = 0L
        if (shouldDiscardVoiceCapture(elapsedMillis)) {
            recorder.cancel()
            viewModel.cancelRecording()
            return
        }
        viewModel.finishRecording(recorder.stop())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TodayHeader(
                onRefresh = viewModel::refreshPreparedDays,
                onAddEvent = {
                    creatingEvent = true
                    selectedEvent = DailyEventEntity(
                        id = UUID.randomUUID().toString(), dailyPlanId = null,
                        eventDate = LocalDate.now().toString(), title = "", description = null,
                        eventType = "custom", status = "planned", startTime = null, endTime = null,
                        durationMinutes = null, scheduledPrecision = null, content = null,
                        reminderMinutesBefore = null, speakAloud = false, sortOrder = events.size,
                        version = 0, origin = "user", syncedFromServer = false,
                        playbackAttemptedAt = null,
                    )
                },
                onRecreateToday = viewModel::recreateTodayPlan,
                recreatingToday = state.recreatingToday,
                onLogout = onLogout,
            )
        },
        bottomBar = {
            ButlerControlBar(
                enabled = !state.recording,
                activeMode = state.captureMode,
                recording = state.recording,
                onVoicePressed = ::startCapture,
                onVoiceReleased = ::finishCapture,
                onText = viewModel::openTextComposer,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!exactAlarmsAllowed) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Automatic speech needs alarm access. Otherwise, allow notifications and tap Listen when a reminder arrives.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            context.startActivity(Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            ))
                        }) { Text("Enable") }
                    }
                }
            }
            if (speaking) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Butler is speaking", Modifier.weight(1f))
                        TextButton(onClick = { SpeechForegroundService.stop(context) }) { Text("Stop") }
                    }
                }
            }
            if (!state.overlayVisible) {
                state.error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (events.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyDay(viewModel::openConversation) }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        state = state,
                        messages = messages,
                        onDraftChanged = viewModel::editDraft,
                        onSendText = viewModel::sendText,
                        onPlay = { requestId, private -> ButlerAudioPlaybackService.play(context, requestId, private) },
                        onClose = viewModel::dismissOverlay,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    selectedEvent?.let { event ->
        EventDetailDialog(
            event = event,
            onDismiss = { selectedEvent = null; creatingEvent = false },
            onDelete = {
                if (!creatingEvent) viewModel.deleteEvent(event)
                selectedEvent = null
                creatingEvent = false
            },
            onSave = {
                viewModel.updateEvent(it)
                selectedEvent = null
                creatingEvent = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayHeader(
    onRefresh: () -> Unit,
    onAddEvent: () -> Unit,
    onRecreateToday: () -> Unit,
    recreatingToday: Boolean,
    onLogout: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var accountMenuOpen by remember { mutableStateOf(false) }
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
            IconButton(onClick = onAddEvent) { Icon(Icons.Default.Add, "Add event") }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh prepared days") }
            Box {
                IconButton(onClick = { accountMenuOpen = true }) { Icon(Icons.Outlined.AccountCircle, "Account menu") }
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Profile") }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {}, enabled = false)
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (recreatingToday) "Recreating today's plan…" else "Recreate today's plan") },
                        onClick = { accountMenuOpen = false; onRecreateToday() },
                        enabled = !recreatingToday,
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Log out") }, onClick = { accountMenuOpen = false; onLogout() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
