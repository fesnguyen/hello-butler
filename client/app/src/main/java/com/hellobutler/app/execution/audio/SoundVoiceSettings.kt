package com.hellobutler.app.execution.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SoundVoicePreferences(
    val volume: Int = 70,
    val reminders: Boolean = false,
    val responses: Boolean = false,
    val briefings: Boolean = true,
) {
    fun allows(workflow: AudioWorkflow) = automaticSpeechEnabled(workflow, reminders, responses, briefings)
}

class SoundVoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("butler_sound_voice", Context.MODE_PRIVATE)
    private fun read() = SoundVoicePreferences(
        prefs.getInt("volume", 70).coerceIn(0, 100), prefs.getBoolean("reminders", false),
        prefs.getBoolean("responses", false), prefs.getBoolean("briefings", true),
    )
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()
    // Keep a strong reference: SharedPreferences stores listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableState.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun volume(value: Int) { prefs.edit().putInt("volume", value.coerceIn(0, 100)).apply() }
    fun toggle(key: String, value: Boolean) {
        require(key in setOf("reminders", "responses", "briefings"))
        check(prefs.edit().putBoolean(key, value).commit())
    }
}
