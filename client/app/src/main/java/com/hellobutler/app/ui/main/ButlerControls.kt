package com.hellobutler.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ButlerControlBar(enabled: Boolean, activeMode: CaptureMode?, capturing: Boolean, onTap: (CaptureMode) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 2.dp) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton("Order", "Delegate", Icons.Outlined.MicNone, CaptureMode.ORDER, enabled, activeMode, capturing, Modifier.weight(1f), onTap)
            ControlButton("Talk", "Ask now", Icons.Outlined.ChatBubbleOutline, CaptureMode.TALK, enabled, activeMode, capturing, Modifier.weight(1f), onTap)
            ControlButton("Text", "Review", Icons.Outlined.EditNote, CaptureMode.TEXT, enabled, activeMode, capturing, Modifier.weight(1f), onTap)
        }
    }
}

@Composable
private fun ControlButton(label: String, hint: String, icon: ImageVector, mode: CaptureMode, enabled: Boolean, activeMode: CaptureMode?, capturing: Boolean, modifier: Modifier, onTap: (CaptureMode) -> Unit) {
    val active = capturing && activeMode == mode
    Surface(
        modifier.clickable(enabled = enabled) { onTap(mode) }, shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(21.dp))
            Spacer(Modifier.height(3.dp))
            Text(if (active) "Listening…" else label, style = MaterialTheme.typography.labelLarge)
            Text(if (active) "Speak naturally" else hint, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .66f))
        }
    }
}
