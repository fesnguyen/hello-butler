package com.hellobutler.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.execution.LocalTextToSpeech
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

private enum class EventPhase { COMPLETED, PAST, CURRENT, UPCOMING }

@Composable
fun DailyEventCard(event: DailyEventEntity, now: LocalTime = LocalTime.now(), onConfigure: () -> Unit) {
    val phase = event.phase(now)
    val accent = when (phase) {
        EventPhase.CURRENT -> MaterialTheme.colorScheme.tertiary
        EventPhase.COMPLETED, EventPhase.UPCOMING -> MaterialTheme.colorScheme.primary
        EventPhase.PAST -> MaterialTheme.colorScheme.outline
    }
    Card(
        Modifier.fillMaxWidth().alpha(if (phase == EventPhase.PAST || phase == EventPhase.COMPLETED) .68f else 1f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (phase == EventPhase.CURRENT) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .52f) else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (phase == EventPhase.CURRENT) 3.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(66.dp).background(accent))
            Column(Modifier.width(68.dp).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(formatTime(event.startTime), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                event.endTime?.let { Text(formatTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(eventIcon(event.eventType), null, Modifier.size(19.dp), tint = accent)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp, top = 9.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (phase == EventPhase.CURRENT) StatusPill("Now", accent)
                    if (phase == EventPhase.COMPLETED) Icon(Icons.Outlined.CheckCircle, "Completed", tint = accent, modifier = Modifier.size(17.dp))
                }
                Text(
                    event.description?.takeIf(String::isNotBlank) ?: event.eventType.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onConfigure, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "Configure event") }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
    }
}

private fun DailyEventEntity.phase(now: LocalTime): EventPhase {
    if (status.equals("completed", true)) return EventPhase.COMPLETED
    val start = startTime.toLocalTime() ?: return EventPhase.UPCOMING
    val end = endTime.toLocalTime() ?: start.plusMinutes(45)
    return when {
        now.isAfter(end) -> EventPhase.PAST
        !now.isBefore(start) -> EventPhase.CURRENT
        else -> EventPhase.UPCOMING
    }
}

private fun String?.toLocalTime() = try { this?.let(LocalTime::parse) } catch (_: DateTimeParseException) { null }
private fun formatTime(value: String?) = value.toLocalTime()?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "Anytime"
private fun eventIcon(type: String): ImageVector = when (type.lowercase()) {
    "morning_brief" -> Icons.Outlined.WbSunny
    "work", "meeting" -> Icons.Outlined.WorkOutline
    "exercise" -> Icons.Outlined.FitnessCenter
    "reminder" -> Icons.Outlined.NotificationsNone
    "good_night_summary", "goodnight_summary", "night_summary" -> Icons.Outlined.Bedtime
    else -> Icons.Outlined.Event
}

@Composable
fun EmptyDay(onOrder: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.Spa, null, Modifier.padding(20.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(18.dp))
        Text("A clear day", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Nothing is planned yet. Enjoy the space, or ask Butler to shape the day with you.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onOrder) {
            Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Plan with Butler")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailDialog(
    event: DailyEventEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (DailyEventEntity) -> Unit,
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description.orEmpty()) }
    var startTime by remember(event.id) { mutableStateOf(event.startTime) }
    var endTime by remember(event.id) { mutableStateOf(event.endTime) }
    var status by remember(event.id) { mutableStateOf(event.status) }
    var validationError by remember(event.id) { mutableStateOf<String?>(null) }
    var selectingStart by remember { mutableStateOf(false) }
    var selectingEnd by remember { mutableStateOf(false) }
    var showBrief by remember { mutableStateOf(false) }

    val isBrief = event.eventType.lowercase() in setOf("morning_brief", "good_night_summary", "goodnight_summary", "night_summary")

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(eventIcon(event.eventType), null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Event details")
                Text(event.eventType.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    minLines = 2,
                    maxLines = 4,
                )
                TimeField("Start time", startTime) { selectingStart = true }
                TimeField("End time", endTime) { selectingEnd = true }
                if (isBrief) {
                    OutlinedButton(onClick = { showBrief = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Article, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (event.eventType.equals("morning_brief", true)) "View Morning Brief" else "View Good Night Summary")
                    }
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Status", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("planned", "completed", "skipped").forEachIndexed { index, value ->
                        SegmentedButton(selected = status == value, onClick = { status = value }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                            Text(value.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val normalizedTitle = title.trim()
                validationError = if (normalizedTitle.isEmpty()) "Title is required" else null
                if (validationError == null) {
                    onSave(
                        event.copy(
                            title = normalizedTitle,
                            description = description.trim().ifEmpty { null },
                            startTime = startTime,
                            endTime = endTime,
                            status = status,
                        )
                    )
                }
            }) { Text("Save changes") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (selectingStart) {
        NativeTimePickerDialog(
            initialTime = startTime.toLocalTime(),
            title = "Select start time",
            onDismiss = { selectingStart = false },
            onConfirm = { startTime = it.toString(); selectingStart = false },
        )
    }
    if (selectingEnd) {
        NativeTimePickerDialog(
            initialTime = endTime.toLocalTime(),
            title = "Select end time",
            onDismiss = { selectingEnd = false },
            onConfirm = { endTime = it.toString(); selectingEnd = false },
        )
    }
    if (showBrief) {
        BriefContentDialog(event = event, onDismiss = { showBrief = false })
    }
}

@Composable
private fun TimeField(label: String, value: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(15.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(Icons.Outlined.ArrowDropDown, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeTimePickerDialog(
    initialTime: LocalTime?,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val fallback = initialTime ?: LocalTime.now().withSecond(0).withNano(0)
    val state = rememberTimePickerState(initialHour = fallback.hour, initialMinute = fallback.minute, is24Hour = false)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
    ) {
        TimePicker(state = state)
    }
}

@Composable
private fun BriefContentDialog(event: DailyEventEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tts = remember { LocalTextToSpeech(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var speaking by remember { mutableStateOf(false) }
    val content = event.content?.takeIf(String::isNotBlank)
        ?: event.description?.takeIf(String::isNotBlank)
        ?: "No brief content is available yet."
    val title = if (event.eventType.equals("morning_brief", true)) "Morning Brief" else "Good Night Summary"

    DisposableEffect(Unit) { onDispose { tts.stop() } }

    Dialog(onDismissRequest = {
        tts.stop()
        speaking = false
        onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.90f).fillMaxHeight(.78f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(eventIcon(event.eventType), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        tts.stop()
                        speaking = false
                        onDismiss()
                    }) { Icon(Icons.Outlined.Close, "Close") }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        content,
                        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (speaking) {
                            tts.stop()
                            speaking = false
                        } else {
                            speaking = true
                            scope.launch {
                                tts.speak(content)
                                speaking = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(if (speaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (speaking) "Stop" else "Listen aloud")
                }
            }
        }
    }
}
