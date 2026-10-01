package com.hellobutler.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hellobutler.app.execution.ButlerPlayback
import com.hellobutler.app.execution.PlaybackPhase
import com.hellobutler.app.execution.playbackPhase
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hellobutler.app.data.local.ConversationMessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ButlerConversationOverlay(
    state: MainUiState,
    messages: List<ConversationMessageEntity>,
    onDraftChanged: (String) -> Unit,
    onSendText: () -> Unit,
    onPlay: (requestId: String, private: Boolean) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val visibleMessages = messages.takeLast(30)

    LaunchedEffect(visibleMessages.size, state.recording) {
        val itemCount = visibleMessages.size + if (state.recording) 1 else 0

        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Card(modifier.fillMaxWidth().fillMaxHeight(.90f), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)) {
        Column(Modifier.fillMaxHeight().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(9.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Butler", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.recording) "Recording" else "Here with your day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose, enabled = !state.recording) { Icon(Icons.Outlined.Close, "Close conversation") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(visibleMessages, key = { it.id }) {
                    ConversationBubble(it, onPlay, onStop)
                }

                if (state.recording) {
                    item { RecordingBubble() }
                }
            }
            state.textDraft?.let { draft ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, onDraftChanged, Modifier.weight(1f), minLines = 1, maxLines = 4, placeholder = { Text("Message Butler") }, shape = RoundedCornerShape(17.dp))
                    Button(onClick = onSendText, enabled = draft.isNotBlank(), contentPadding = PaddingValues(12.dp)) { Icon(Icons.Outlined.Send, "Send text") }
                }
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(Modifier.widthIn(max = 260.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Recording…", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConversationBubble(message: ConversationMessageEntity, onPlay: (requestId: String, private: Boolean) -> Unit, onStop: () -> Unit) {
    val playback by ButlerPlayback.state.collectAsState()
    val fromUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = 290.dp), shape = RoundedCornerShape(18.dp), color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (fromUser) "You" else "Butler", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    if (!fromUser && message.responseAudioDurationMs != null) Text(" · ${duration(message.responseAudioDurationMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(message.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (fromUser && message.deliveryState != "completed") Text(
                    when (message.deliveryState) { "sending" -> "Sending…"; "sent" -> "Sent • ${messageTime(message.createdAt)}"; "failed" -> "Waiting for connection"; else -> message.deliveryState },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                )
                if (!fromUser && message.audioCacheState !in setOf("none", "unavailable")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(false, true).forEach { private ->
                            val phase = playbackPhase(message.requestId, private, message.audioCacheState, playback)
                            TextButton(
                                onClick = { if (phase == PlaybackPhase.SPEAKING) onStop() else onPlay(message.requestId, private) },
                                enabled = phase != PlaybackPhase.LOADING,
                                modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                if (phase == PlaybackPhase.LOADING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(when {
                                    phase == PlaybackPhase.SPEAKING -> Icons.Outlined.Stop
                                    private -> Icons.Outlined.Call
                                    else -> Icons.Outlined.VolumeUp
                                }, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(when (phase) {
                                    PlaybackPhase.LOADING -> "Preparing audio…"
                                    PlaybackPhase.SPEAKING -> "Stop"
                                    else -> if (private) "Phone Listen" else "Listen Aloud"
                                }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }

            }
        }
    }
}

private fun duration(milliseconds: Int): String { val seconds = (milliseconds / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
private fun messageTime(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault("")
