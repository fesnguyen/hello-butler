package com.hellobutler.app.ui.main

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun ButlerControlBar(
    enabled: Boolean,
    onHoldStart: (CaptureMode) -> Unit,
    onHoldEnd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CaptureMode.entries.forEach { mode ->
            Surface(
                modifier = Modifier.weight(1f).pointerInput(mode, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        onHoldStart(mode)
                        if (tryAwaitRelease()) {
                            onHoldEnd()
                        }
                    })
                },
            ) {
                Text(
                    text = mode.name.lowercase().replaceFirstChar(Char::uppercase),
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                )
            }
        }
    }
}
