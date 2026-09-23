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
import com.hellobutler.app.data.remote.SavedPreferenceDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(viewModel: UserSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var selectedPreference by remember { mutableStateOf<SavedPreferenceDto?>(null) }
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
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                item { SectionTitle("Preferences") }
                if (state.preferences.isEmpty()) item { Text("No saved preferences") }
                items(state.preferences, key = SavedPreferenceDto::id) { preference ->
                    Text(
                        preference.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { selectedPreference = preference }
                            .padding(vertical = 10.dp),
                    )
                }
                state.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                if (state.saved) item { Text("User Settings saved", color = MaterialTheme.colorScheme.primary) }
                item {
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.saving) "Saving…" else "Save") }
                }
            }
        }
    }
    selectedPreference?.let { preference ->
        AlertDialog(
            onDismissRequest = { selectedPreference = null },
            title = { Text("Saved preference") },
            text = { Text(preference.content) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removePreference(preference.id) // Draft only; Save owns server mutation.
                    selectedPreference = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { selectedPreference = null }) { Text("Cancel") } },
        )
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
