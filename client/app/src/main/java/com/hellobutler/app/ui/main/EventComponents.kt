package com.hellobutler.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hellobutler.app.data.local.DailyEventEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (phase == EventPhase.CURRENT) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .52f) else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (phase == EventPhase.CURRENT) 3.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 82.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).height(82.dp).background(accent))
            Column(Modifier.width(78.dp).padding(start = 14.dp)) {
                Text(formatTime(event.startTime), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                event.endTime?.let { Text(formatTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(eventIcon(event.eventType), null, Modifier.size(22.dp), tint = accent)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (phase == EventPhase.CURRENT) StatusPill("Now", accent)
                    if (phase == EventPhase.COMPLETED) Icon(Icons.Outlined.CheckCircle, "Completed", tint = accent, modifier = Modifier.size(19.dp))
                }
                Text(
                    event.description?.takeIf(String::isNotBlank) ?: event.eventType.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            IconButton(onClick = onConfigure) { Icon(Icons.Outlined.MoreVert, "Configure event") }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
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
private fun formatTime(value: String?) = value.toLocalTime()?.format(DateTimeFormatter.ofPattern("h:mm")) ?: "Anytime"
private fun eventIcon(type: String): ImageVector = when (type.lowercase()) {
    "morning_brief" -> Icons.Outlined.WbSunny
    "work", "meeting" -> Icons.Outlined.WorkOutline
    "exercise" -> Icons.Outlined.FitnessCenter
    "reminder" -> Icons.Outlined.NotificationsNone
    "goodnight_summary" -> Icons.Outlined.Bedtime
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
fun EventDetailDialog(event: DailyEventEntity, onDismiss: () -> Unit, onSave: (DailyEventEntity) -> Unit) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var startTime by remember(event.id) { mutableStateOf(event.startTime.orEmpty()) }
    var status by remember(event.id) { mutableStateOf(event.status) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp),
        icon = { Icon(eventIcon(event.eventType), null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Event details")
                Text(event.eventType.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp))
                OutlinedTextField(startTime, { startTime = it }, label = { Text("Start time") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp))
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
            Button(onClick = { onSave(event.copy(title = title.trim(), startTime = startTime.trim().ifEmpty { null }, status = status, version = event.version + 1)) }) { Text("Save changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
