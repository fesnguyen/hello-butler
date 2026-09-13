package com.hellobutler.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun ButlerControlBar(enabled: Boolean, activeMode: CaptureMode?, recording: Boolean, onVoicePressed: (CaptureMode) -> Unit, onVoiceReleased: () -> Unit, onText: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 2.dp) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceControl("Order", Icons.Outlined.MicNone, CaptureMode.ORDER, enabled, activeMode, recording, Modifier.weight(1f), onVoicePressed, onVoiceReleased)
            VoiceControl("Talk", Icons.Outlined.ChatBubbleOutline, CaptureMode.TALK, enabled, activeMode, recording, Modifier.weight(1f), onVoicePressed, onVoiceReleased)
            ControlSurface("Text", "Type and send", Icons.Outlined.EditNote, Modifier.weight(1f).clickable(enabled = enabled, onClick = onText), activeMode == CaptureMode.TEXT && !recording)
        }
    }
}

@Composable
private fun VoiceControl(label: String, icon: ImageVector, mode: CaptureMode, enabled: Boolean, activeMode: CaptureMode?, recording: Boolean, modifier: Modifier, onPressed: (CaptureMode) -> Unit, onReleased: () -> Unit) {
    val active = recording && activeMode == mode
    ControlSurface(label, if (active) "Recording…" else "Hold to speak", icon,
        modifier.pointerInput(mode) {
            detectTapGestures(onPress = {
                if (enabled) {
                    onPressed(mode)
                    tryAwaitRelease()
                    onReleased()
                }
            })
        }, active)
}

@Composable
private fun ControlSurface(label: String, hint: String, icon: ImageVector, modifier: Modifier, active: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(21.dp)); Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .66f))
        }
    }
}
