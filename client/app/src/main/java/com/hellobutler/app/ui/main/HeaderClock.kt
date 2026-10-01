package com.hellobutler.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

internal fun millisUntilNextMinute(nowMillis: Long): Long = 60_000L - Math.floorMod(nowMillis, 60_000L)

@Composable
internal fun rememberHeaderTime(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(millisUntilNextMinute(value))
            }
        }
    }
    return now
}
