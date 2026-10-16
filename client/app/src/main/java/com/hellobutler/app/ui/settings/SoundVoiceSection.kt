
package com.hellobutler.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import com.hellobutler.app.execution.audio.SoundVoiceSettings


// ------------------------------------------------------------
// Entry Point - Sound & Voice Settings
// ------------------------------------------------------------

/**
 * Displays and manages Butler's audio preferences.
 *
 * Provides independent volume control with live audio preview,
 * plus switches for enabling speech in different scenarios.
 */
@Composable
internal fun SoundVoiceSection(settings: SoundVoiceSettings) {

    // Observe saved audio settings so UI updates when preferences change.
    val value by settings.state.collectAsState()

    // Obtain Android context to communicate with the audio service.
    val context = LocalContext.current

    // Track whether the user is adjusting volume to avoid restarting preview.
    var adjusting by remember { mutableStateOf(false) }

    // --------------------------------------------------------
    // Preview Lifecycle
    // --------------------------------------------------------

    // Register cleanup to prevent preview audio continuing after UI disposal.
    DisposableEffect(context) {
        onDispose {
            // Stop preview only if a volume adjustment is still active.
            if (adjusting) {
                ButlerAudioPlaybackService.stopPreview(context)
            }
        }
    }

    // --------------------------------------------------------
    // Settings Layout
    // --------------------------------------------------------

    // Arrange settings vertically with consistent 8dp spacing.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Display the section heading using the theme's title style.
        Text(
            text = "Sound & Voice",
            style = MaterialTheme.typography.titleMedium,
        )

        // ----------------------------------------------------
        // Butler Volume Control
        // ----------------------------------------------------

        // Show the current volume percentage from saved settings.
        Text("Butler volume · ${value.volume}%")

        // Allow users to adjust Butler volume and hear a live preview.
        Slider(
            // Convert the stored integer percentage into Slider's float value.
            value = value.volume.toFloat(),

            // Restrict Butler volume to the supported 0–100% range.
            valueRange = 0f..100f,

            // Handle every slider movement to update volume immediately.
            onValueChange = { volume ->

                // Persist the new volume percentage through settings.
                settings.volume(volume.toInt())

                // Start preview only once during the current adjustment.
                if (!adjusting) {

                    // Mark preview as active to avoid repeated startup calls.
                    adjusting = true

                    // Play the sample; suppress errors to keep UI responsive.
                    // Further slider movements update gain without restarting it.
                    runCatching {
                        ButlerAudioPlaybackService.preview(context)
                    }
                }
            },

            // Stop the sample when the user finishes moving the slider.
            onValueChangeFinished = {

                // Reset adjustment state for the next slider interaction.
                adjusting = false

                // Release the active audio preview.
                ButlerAudioPlaybackService.stopPreview(context)
            },

            // Expand the slider to the available horizontal space.
            modifier = Modifier.fillMaxWidth(),
        )

        // Explain that this control does not modify Android system volume.
        Text(
            text = "Controls Butler audio only. Does not change Android volume.",
            style = MaterialTheme.typography.bodySmall,
        )

        // ----------------------------------------------------
        // Speech Preferences
        // ----------------------------------------------------

        // Enable or disable automatic speech for scheduled reminders.
        SoundToggle(
            label = "Speak reminders aloud",
            checked = value.reminders,
            onChange = { settings.toggle("reminders", it) },
        )

        // Enable or disable automatic speech for Butler's responses.
        SoundToggle(
            label = "Speak Butler responses aloud",
            checked = value.responses,
            onChange = { settings.toggle("responses", it) },
        )

        // Enable or disable automatic speech for daily briefings.
        SoundToggle(
            label = "Speak daily briefings aloud",
            checked = value.briefings,
            onChange = { settings.toggle("briefings", it) },
        )

        // Clarify that disabling speech does not disable other features.
        Text(
            text = "Notifications, text, and manual listening stay available.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


// ------------------------------------------------------------
// UI Components - Reusable Controls
// ------------------------------------------------------------

/**
 * Displays a reusable labeled switch for an audio preference.
 *
 * Centralizes toggle layout to keep all speech settings visually
 * consistent while delegating state changes to the parent.
 */
@Composable
private fun SoundToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // Place the preference label and switch on the same horizontal row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        // Show the preference name and reserve remaining space for it.
        Text(
            text = label,
            modifier = Modifier.weight(1f),
        )

        // Display the current preference and forward changes to the parent.
        Switch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}
