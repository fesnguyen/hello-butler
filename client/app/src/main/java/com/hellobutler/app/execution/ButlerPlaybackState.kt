package com.hellobutler.app.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackPhase { LOADING, READY, SPEAKING }

data class ButlerPlaybackState(
    val requestId: String? = null,
    val privateRoute: Boolean = false,
    val phase: PlaybackPhase = PlaybackPhase.READY,
)

object ButlerPlayback {
    private val mutableState = MutableStateFlow(ButlerPlaybackState())
    val state = mutableState.asStateFlow()
    internal fun update(value: ButlerPlaybackState) { mutableState.value = value }
}

internal fun playbackPhase(requestId: String, privateRoute: Boolean, cacheState: String,
                           playback: ButlerPlaybackState): PlaybackPhase = when {
    playback.requestId == requestId && playback.phase == PlaybackPhase.LOADING -> PlaybackPhase.LOADING
    playback.requestId == requestId && playback.privateRoute == privateRoute -> playback.phase
    cacheState in setOf("pending", "downloading") -> PlaybackPhase.LOADING
    else -> PlaybackPhase.READY
}
