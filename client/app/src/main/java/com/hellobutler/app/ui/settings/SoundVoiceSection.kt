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

@Composable
internal fun SoundVoiceSection(settings: SoundVoiceSettings) {
    val value by settings.state.collectAsState()
    val context = LocalContext.current
    var adjusting by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        onDispose { if (adjusting) ButlerAudioPlaybackService.stopPreview(context) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sound & Voice", style = MaterialTheme.typography.titleMedium)
        Text("Butler volume · ${value.volume}%")
        Slider(
            value = value.volume.toFloat(), valueRange = 0f..100f,
            onValueChange = { volume ->
                settings.volume(volume.toInt())
                if (!adjusting) {
                    adjusting = true
                    // The shared service keeps one sample running; further changes update its gain.
                    runCatching { ButlerAudioPlaybackService.preview(context) }
                }
            },
            onValueChangeFinished = {
                adjusting = false
                ButlerAudioPlaybackService.stopPreview(context)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Controls Butler audio only. Does not change Android volume.", style = MaterialTheme.typography.bodySmall)
        SoundToggle("Speak reminders aloud", value.reminders) { settings.toggle("reminders", it) }
        SoundToggle("Speak Butler responses aloud", value.responses) { settings.toggle("responses", it) }
        SoundToggle("Speak daily briefings aloud", value.briefings) { settings.toggle("briefings", it) }
        Text("Notifications, text, and manual listening stay available.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SoundToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
