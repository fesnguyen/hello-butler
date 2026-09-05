package com.hellobutler.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ButlerControlBar(
    enabled: Boolean,
    onTap: (CaptureMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CaptureMode.entries.forEach { mode ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onTap(mode) },
            ) {
                Text(
                    text = mode.name.lowercase().replaceFirstChar(Char::uppercase),
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                )
            }
        }
    }
}
