package com.hellobutler.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hellobutler.app.data.local.SavedContextEntity
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(viewModel: UserSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var selectedItem by remember { mutableStateOf<SavedContextEntity?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { SectionTitle("Account") }
            item {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::setDisplayName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = {},
                    enabled = false,
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SectionTitle("Credits") }
            item { Text(state.credits.toString(), style = MaterialTheme.typography.headlineSmall) }
            item { SectionTitle("TTS Method") }
            item {
                TtsChoice(
                    label = "Open Source",
                    description = "Kokoro speech with no additional TTS credit cost",
                    selected = state.ttsMethod == TtsMethod.OPEN_SOURCE,
                ) { viewModel.setTtsMethod(TtsMethod.OPEN_SOURCE) }
            }
            item {
                TtsChoice(
                    label = "OpenAI",
                    description = "Uses additional credits; Kokoro is used when credits run out",
                    selected = state.ttsMethod == TtsMethod.OPENAI,
                ) { viewModel.setTtsMethod(TtsMethod.OPENAI) }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Notes & Preferences", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selectedItem = SavedContextEntity(UUID.randomUUID().toString(), "", false, 0) }) { Text("+ Add") }
                }
            }
            if (state.items.isEmpty()) item { Text("No saved notes or preferences") }
            items(state.items, key = SavedContextEntity::id) { item ->
                Column(Modifier.fillMaxWidth().clickable { selectedItem = item }.padding(vertical = 8.dp)) {
                    Text(if (item.isPreference) "Preference" else "Note", style = MaterialTheme.typography.labelSmall)
                    Text(item.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (state.saved) item { Text("User Settings saved", color = MaterialTheme.colorScheme.primary) }
            item {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.saving) "Saving…" else "Save") }
            }
        }
    }
    selectedItem?.let { item ->
        key(item.id) {
            var description by remember { mutableStateOf(item.content) }
            var isPreference by remember { mutableStateOf(item.isPreference) }
            var confirmDelete by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!state.savingItem) selectedItem = null },
                title = { Text(if (item.version == 0) "Add note or preference" else "Edit saved item") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(description, { description = it }, label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, enabled = !state.savingItem)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Is preference", Modifier.weight(1f))
                            Switch(isPreference, { isPreference = it }, enabled = !state.savingItem)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (item.version > 0) TextButton(onClick = { confirmDelete = true }, enabled = !state.savingItem) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = description.isNotBlank() && !state.savingItem, onClick = {
                        viewModel.saveItem(item.id, description, isPreference, item.version) { selectedItem = null }
                    }) { Text(if (state.savingItem) "Saving…" else "Save") }
                },
                dismissButton = { TextButton(enabled = !state.savingItem, onClick = { selectedItem = null }) { Text("Cancel") } },
            )
            if (confirmDelete) AlertDialog(
                onDismissRequest = { if (!state.savingItem) confirmDelete = false },
                title = { Text("Delete saved item?") },
                text = {
                    Column {
                        Text("This removes it from Butler's saved context.")
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(enabled = !state.savingItem, onClick = {
                    viewModel.deleteItem(item) { selectedItem = null }
                }) { Text("Delete") } },
                dismissButton = { TextButton(enabled = !state.savingItem, onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun TtsChoice(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
