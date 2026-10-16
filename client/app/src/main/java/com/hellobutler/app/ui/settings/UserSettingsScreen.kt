
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


// ------------------------------------------------------------
// Entry Point - User Settings Screen
// ------------------------------------------------------------

/**
 * Displays the user's account settings, audio preferences,
 * TTS provider selection, and saved notes or preferences.
 *
 * Coordinates settings through the ViewModel so the screen
 * reflects current values and supports editing and saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    viewModel: UserSettingsViewModel,
    onBack: () -> Unit,
) {
    // Observe ViewModel state to automatically update the UI.
    val state by viewModel.state.collectAsState()

    // Track the note or preference currently being edited.
    // Null means the editor is closed.
    var selectedItem by remember {
        mutableStateOf<SavedContextEntity?>(null)
    }

    // Refresh settings when the screen first enters composition.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // --------------------------------------------------------
    // Screen Structure
    // --------------------------------------------------------

    // Provide the app bar and main content area.
    Scaffold(
        topBar = {
            TopAppBar(
                // Display the screen title.
                title = {
                    Text("User Settings")
                },

                // Return to the previous screen when Back is pressed.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->

        // Display settings in a vertically scrollable list.
        // Apply Scaffold padding to avoid overlapping the app bar.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ------------------------------------------------
            // Loading Indicator
            // ------------------------------------------------

            // Show progress while settings are being loaded.
            if (state.loading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ------------------------------------------------
            // Account Information
            // ------------------------------------------------

            // Identify the account settings section.
            item {
                SectionTitle("Account")
            }

            // Allow the user to edit their display name.
            item {
                OutlinedTextField(
                    // Show the current name from ViewModel state.
                    value = state.displayName,

                    // Forward name changes to the ViewModel.
                    onValueChange = viewModel::setDisplayName,

                    label = {
                        Text("Name")
                    },

                    // Keep the name field on a single line.
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Display the account email without allowing edits.
            item {
                OutlinedTextField(
                    value = state.email,

                    // Ignore edits because email is read-only.
                    onValueChange = {},

                    // Disable user interaction with this field.
                    enabled = false,

                    label = {
                        Text("Email")
                    },

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ------------------------------------------------
            // Available Credits
            // ------------------------------------------------

            // Identify the credits section.
            item {
                SectionTitle("Credits")
            }

            // Display the current available credit balance.
            item {
                Text(
                    text = state.credits.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            // ------------------------------------------------
            // Sound & Voice Settings
            // ------------------------------------------------

            // Display audio controls only when settings are available.
            viewModel.soundVoice?.let { soundVoice ->
                item {
                    // Delegate volume and speech controls to their own component.
                    SoundVoiceSection(soundVoice)
                }
            }

            // ------------------------------------------------
            // Text-to-Speech Provider
            // ------------------------------------------------

            // Identify the TTS provider selection section.
            item {
                SectionTitle("TTS Method")
            }

            // Offer Kokoro as the open-source speech provider.
            item {
                TtsChoice(
                    label = "Open Source",
                    description = "Kokoro speech with no additional TTS credit cost",

                    // Mark this option selected when Kokoro is configured.
                    selected = state.ttsMethod == TtsMethod.OPEN_SOURCE,
                ) {
                    // Request the ViewModel to select open-source TTS.
                    viewModel.setTtsMethod(TtsMethod.OPEN_SOURCE)
                }
            }

            // Offer OpenAI as the credit-consuming speech provider.
            item {
                TtsChoice(
                    label = "OpenAI",
                    description = "Uses additional credits; Kokoro is used when credits run out",

                    // Mark this option selected when OpenAI is configured.
                    selected = state.ttsMethod == TtsMethod.OPENAI,
                ) {
                    // Request the ViewModel to select OpenAI TTS.
                    viewModel.setTtsMethod(TtsMethod.OPENAI)
                }
            }

            // ------------------------------------------------
            // Saved Notes & Preferences
            // ------------------------------------------------

            // Display the section heading and action for adding entries.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    // Occupy remaining width so Add stays on the right.
                    Text(
                        text = "Notes & Preferences",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    // Create a temporary empty entry and open its editor.
                    TextButton(
                        onClick = {
                            selectedItem = SavedContextEntity(
                                UUID.randomUUID().toString(),
                                "",
                                false,
                                0,
                            )
                        },
                    ) {
                        Text("+ Add")
                    }
                }
            }

            // Explain when no notes or preferences have been saved.
            if (state.items.isEmpty()) {
                item {
                    Text("No saved notes or preferences")
                }
            }

            // Render each saved entry using its stable ID as the list key.
            items(
                items = state.items,
                key = SavedContextEntity::id,
            ) { item ->

                // Make the entire entry clickable to open its editor.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedItem = item
                        }
                        .padding(vertical = 8.dp),
                ) {
                    // Distinguish enduring preferences from ordinary notes.
                    Text(
                        text = if (item.isPreference) {
                            "Preference"
                        } else {
                            "Note"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )

                    // Show a one-line preview to keep the list compact.
                    Text(
                        text = item.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ------------------------------------------------
            // Operation Feedback
            // ------------------------------------------------

            // Show any error reported by the ViewModel.
            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Confirm when settings have been saved successfully.
            if (state.saved) {
                item {
                    Text(
                        text = "User Settings saved",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ------------------------------------------------
            // Save Action
            // ------------------------------------------------

            // Provide the main action for saving user settings.
            item {
                Button(
                    // Delegate persistence to the ViewModel.
                    onClick = viewModel::save,

                    // Prevent repeated saves or saving during loading.
                    enabled = !state.saving && !state.loading,

                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Indicate whether saving is currently in progress.
                    Text(
                        if (state.saving) "Saving…" else "Save"
                    )
                }
            }
        }
    }

    // --------------------------------------------------------
    // Saved Context Editor
    // --------------------------------------------------------

    // Show the editor only when an entry has been selected.
    selectedItem?.let { item ->

        // Delegate editing to the dedicated editor component.
        SavedContextEditor(viewModel, item) {

            // Clear the selection to close the editor.
            selectedItem = null
        }
    }
}


// ------------------------------------------------------------
// UI Components - TTS Provider Selection
// ------------------------------------------------------------

/**
 * Displays a selectable text-to-speech provider.
 *
 * Combines a radio button with provider details so users
 * can understand and select their preferred speech engine.
 */
@Composable
private fun TtsChoice(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Make the entire provider row selectable, not just the radio button.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        // Indicate which TTS provider is currently selected.
        RadioButton(
            selected = selected,
            onClick = onClick,
        )

        // Arrange provider name and description vertically.
        Column(
            modifier = Modifier.padding(start = 8.dp),
        ) {
            // Display the provider's name.
            Text(label)

            // Explain the provider's behavior and credit implications.
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


// ------------------------------------------------------------
// UI Components - Section Heading
// ------------------------------------------------------------

/**
 * Displays a consistently styled heading for settings sections.
 *
 * Avoids duplicating typography configuration throughout
 * the screen and keeps section headings visually uniform.
 */
@Composable
private fun SectionTitle(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
)
