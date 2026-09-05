package com.hellobutler.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ButlerConversationOverlay(state: MainUiState, capturing: Boolean, onDraftChanged: (String) -> Unit, onSendOrder: () -> Unit, onSendTalk: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().heightIn(max = 480.dp), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(9.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Butler", style = MaterialTheme.typography.titleMedium)
                    Text(when { capturing -> "Listening to you"; state.processing -> "Considering your request"; else -> "Here with your day" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close conversation") }
            }
            if (capturing) ListeningIndicator(state.transcript)
            if (state.messages.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.messages.takeLast(6)) { ConversationBubble(it) }
                }
            }
            state.textDraft?.let { draft ->
                OutlinedTextField(
                    draft, onDraftChanged, Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                    label = { Text("Review transcript") }, shape = RoundedCornerShape(17.dp),
                    supportingText = { Text("Edit anything speech recognition missed.") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSendOrder, enabled = !state.processing && draft.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send as Order")
                    }
                    OutlinedButton(onClick = onSendTalk, enabled = !state.processing && draft.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Send as Talk") }
                }
            }
            if (state.processing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Butler is working on it…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(9.dp))
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningIndicator(transcript: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Listening…", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(transcript.ifBlank { "Go ahead. I’m listening." }, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ConversationBubble(message: ConversationMessage) {
    val fromUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 290.dp), shape = RoundedCornerShape(18.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(if (fromUser) "You" else "Butler", style = MaterialTheme.typography.labelSmall, color = if (fromUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(message.text, color = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
