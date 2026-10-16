
package com.hellobutler.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.hellobutler.app.data.local.SavedContextEntity


// ------------------------------------------------------------
// Entry Point - Saved Context Editor
// ------------------------------------------------------------

/**
 * Provides a dialog for creating, editing, and deleting Butler's
 * saved notes and preferences.
 *
 * Supports both the standard editor and a simplified note-only mode.
 * Coordinates local draft state with ViewModel operations while
 * preventing dismissal or modification during persistence.
 */
@Composable
fun SavedContextEditor(
    viewModel: UserSettingsViewModel,
    item: SavedContextEntity,
    noteOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    // Observe saving status and errors to update dialog controls.
    val state by viewModel.state.collectAsState()

    // Scope editor state to the selected item so switching items
    // initializes a separate draft rather than reusing previous values.
    key(item.id) {

        // Preserve the edited description across recomposition and recreation.
        var description by rememberSaveable {
            mutableStateOf(item.content)
        }

        // Preserve the preference classification until the user saves.
        var isPreference by rememberSaveable {
            mutableStateOf(item.isPreference)
        }

        // Track whether the secondary delete-confirmation dialog is visible.
        var confirmDelete by remember {
            mutableStateOf(false)
        }

        // ----------------------------------------------------
        // Main Editor Dialog
        // ----------------------------------------------------

        // Present editable context fields and available actions.
        AlertDialog(
            // Prevent accidental dismissal while an operation is running.
            onDismissRequest = {
                if (!state.savingItem) {
                    onDismiss()
                }
            },

            // Use a translucent surface for quick note-taking mode.
            containerColor = if (noteOnly) {
                Color(0xE6EEE6F5)
            } else {
                MaterialTheme.colorScheme.surface
            },

            // Identify the editing mode and whether this is a new item.
            title = {
                Text(
                    if (noteOnly) {
                        "Take Note"
                    } else if (item.version == 0) {
                        "Add note or preference"
                    } else {
                        "Edit saved item"
                    }
                )
            },

            // ------------------------------------------------
            // Editor Content
            // ------------------------------------------------

            text = {
                // Reduce background dimming in quick note-taking mode.
                if (noteOnly) {
                    val view = LocalView.current

                    // Apply the dim level to the hosting dialog window.
                    SideEffect {
                        (view.parent as? DialogWindowProvider)
                            ?.window
                            ?.setDimAmount(0.12f)
                    }
                }

                // Arrange editing controls vertically with consistent spacing.
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    // --------------------------------------------
                    // Description Input
                    // --------------------------------------------

                    // Edit the saved content using a multiline text field.
                    OutlinedTextField(
                        // Display the current unsaved draft.
                        value = description,

                        // Update local draft state without persisting immediately.
                        onValueChange = {
                            description = it
                        },

                        label = {
                            Text("Description")
                        },

                        // Occupy available width and support longer notes.
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 7,

                        // Lock the input while saving or deleting.
                        enabled = !state.savingItem,
                    )

                    // --------------------------------------------
                    // Preference Classification
                    // --------------------------------------------

                    // Hide preference classification in note-only mode.
                    if (!noteOnly) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {

                            // Describe the switch and reserve remaining row space.
                            Text(
                                text = "Is preference",
                                modifier = Modifier.weight(1f),
                            )

                            // Let users classify this entry as a preference.
                            Switch(
                                checked = isPreference,

                                // Update the draft classification locally.
                                onCheckedChange = {
                                    isPreference = it
                                },

                                // Prevent classification changes during persistence.
                                enabled = !state.savingItem,
                            )
                        }
                    }

                    // --------------------------------------------
                    // Error Feedback
                    // --------------------------------------------

                    // Show the latest ViewModel error when an operation fails.
                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // --------------------------------------------
                    // Delete Action
                    // --------------------------------------------

                    // Allow deletion only for previously saved entries.
                    if (item.version > 0) {
                        TextButton(
                            // Open a separate confirmation before deleting.
                            onClick = {
                                confirmDelete = true
                            },

                            // Block deletion requests during active operations.
                            enabled = !state.savingItem,
                        ) {
                            // Highlight deletion as a destructive action.
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },

            // ------------------------------------------------
            // Save Action
            // ------------------------------------------------

            confirmButton = {
                TextButton(
                    // Require nonblank content and no active item operation.
                    enabled = description.isNotBlank() && !state.savingItem,

                    onClick = {
                        // Persist the draft using the original item's ID/version.
                        viewModel.saveItem(
                            item.id,
                            description,
                            isPreference,
                            item.version,
                        ) {
                            // Close the editor only after a successful save.
                            onDismiss()
                        }
                    },
                ) {
                    // Reflect the current save operation in the button label.
                    Text(
                        if (state.savingItem) "Saving…" else "Save"
                    )
                }
            },

            // ------------------------------------------------
            // Cancel / Exit Action
            // ------------------------------------------------

            dismissButton = {
                TextButton(
                    // Avoid dismissing the editor during persistence.
                    enabled = !state.savingItem,

                    // Discard the local draft and close the dialog.
                    onClick = {
                        onDismiss()
                    },
                ) {
                    // Use a context-appropriate label for the editing mode.
                    Text(
                        if (noteOnly) "Exit" else "Cancel"
                    )
                }
            },
        )

        // ----------------------------------------------------
        // Delete Confirmation Dialog
        // ----------------------------------------------------

        // Require explicit confirmation before removing saved context.
        if (confirmDelete) {
            AlertDialog(
                // Allow closing confirmation only when no mutation is active.
                onDismissRequest = {
                    if (!state.savingItem) {
                        confirmDelete = false
                    }
                },

                // Make the destructive operation explicit.
                title = {
                    Text("Delete saved item?")
                },

                text = {
                    Column {
                        // Explain the consequence of deleting this entry.
                        Text("This removes it from Butler's saved context.")

                        // Display any error returned by the deletion operation.
                        state.error?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },

                // --------------------------------------------
                // Confirm Deletion
                // --------------------------------------------

                confirmButton = {
                    TextButton(
                        // Prevent duplicate deletion requests.
                        enabled = !state.savingItem,

                        onClick = {
                            // Delete the selected entry through the ViewModel.
                            viewModel.deleteItem(item) {
                                // Close the editor after successful deletion.
                                onDismiss()
                            }
                        },
                    ) {
                        Text("Delete")
                    }
                },

                // --------------------------------------------
                // Cancel Deletion
                // --------------------------------------------

                dismissButton = {
                    TextButton(
                        // Prevent changes while the delete operation is active.
                        enabled = !state.savingItem,

                        onClick = {
                            // Return to the editor without deleting the entry.
                            confirmDelete = false
                        },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
