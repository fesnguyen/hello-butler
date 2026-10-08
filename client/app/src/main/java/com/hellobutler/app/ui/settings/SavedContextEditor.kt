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

@Composable
fun SavedContextEditor(viewModel: UserSettingsViewModel, item: SavedContextEntity, noteOnly: Boolean = false, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    key(item.id) {
        var description by rememberSaveable { mutableStateOf(item.content) }
        var isPreference by rememberSaveable { mutableStateOf(item.isPreference) }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.savingItem) onDismiss() },
            containerColor = if (noteOnly) Color(0xE6EEE6F5) else MaterialTheme.colorScheme.surface,
            title = { Text(if (noteOnly) "Take Note" else if (item.version == 0) "Add note or preference" else "Edit saved item") },
            text = {
                if (noteOnly) {
                    val view = LocalView.current
                    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.12f) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(description, { description = it }, label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, enabled = !state.savingItem)
                    if (!noteOnly) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                    viewModel.saveItem(item.id, description, isPreference, item.version) { onDismiss() }
                }) { Text(if (state.savingItem) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !state.savingItem, onClick = { onDismiss() }) { Text(if (noteOnly) "Exit" else "Cancel") } },
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
                viewModel.deleteItem(item) { onDismiss() }
            }) { Text("Delete") } },
            dismissButton = { TextButton(enabled = !state.savingItem, onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
