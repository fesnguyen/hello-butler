
package com.hellobutler.app.execution.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


// ------------------------------------------------------------
// Data Model - Sound & Voice Preferences
// ------------------------------------------------------------

/**
 * Represents the user's audio playback and speech preferences.
 *
 * Centralizes volume and workflow-specific speech permissions
 * so audio components can consistently determine whether
 * automatic speech is enabled.
 */
data class SoundVoicePreferences(
    // Default Butler playback volume to 70%.
    val volume: Int = 70,

    // Control automatic speech for each supported workflow.
    val reminders: Boolean = false,
    val responses: Boolean = false,
    val briefings: Boolean = true,
) {

    /**
     * Determines whether automatic speech is allowed for a workflow.
     *
     * Delegates the decision to the shared audio policy so every
     * workflow follows the same preference rules.
     */
    fun allows(workflow: AudioWorkflow) = automaticSpeechEnabled(
        workflow,
        reminders,
        responses,
        briefings,
    )
}


// ------------------------------------------------------------
// Settings Manager - Sound & Voice
// ------------------------------------------------------------

/**
 * Manages persistent Butler audio preferences.
 *
 * Uses SharedPreferences for local storage and StateFlow for
 * reactive updates, keeping audio settings synchronized across
 * the application whenever a preference changes.
 */
class SoundVoiceSettings(context: Context) {

    // --------------------------------------------------------
    // Initialization - Persistent Storage
    // --------------------------------------------------------

    // Open Butler's private preference storage.
    private val prefs = context.getSharedPreferences(
        "butler_sound_voice",
        Context.MODE_PRIVATE,
    )

    // --------------------------------------------------------
    // Observable State
    // --------------------------------------------------------

    // Initialize observable state from previously saved preferences.
    private val mutableState = MutableStateFlow(read())

    // Expose read-only state to prevent external components
    // from modifying preferences without going through this manager.
    val state = mutableState.asStateFlow()

    // --------------------------------------------------------
    // Preference Change Listener
    // --------------------------------------------------------

    // Keep a strong reference because SharedPreferences stores
    // listeners weakly and could otherwise lose this listener.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->

        // Reload preferences whenever a stored value changes.
        mutableState.value = read()
    }

    init {
        // Register the listener so external preference changes
        // are reflected in the observable state.
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // --------------------------------------------------------
    // Public Operations - Volume Control
    // --------------------------------------------------------

    /**
     * Persists the user's preferred Butler playback volume.
     *
     * Restricts values to 0–100% to keep stored settings within
     * the supported audio gain range.
     */
    fun volume(value: Int) {

        // Clamp the requested volume before saving it asynchronously.
        prefs.edit()
            .putInt("volume", value.coerceIn(0, 100))
            .apply()
    }

    // --------------------------------------------------------
    // Public Operations - Speech Preferences
    // --------------------------------------------------------

    /**
     * Enables or disables automatic speech for a supported workflow.
     *
     * Validates the preference key and commits the change
     * synchronously to ensure persistence succeeds.
     */
    fun toggle(key: String, value: Boolean) {

        // Reject unknown keys to avoid creating invalid preferences.
        require(key in setOf("reminders", "responses", "briefings"))

        // Save the requested toggle and fail if the commit is unsuccessful.
        check(
            prefs.edit()
                .putBoolean(key, value)
                .commit()
        )
    }

    // --------------------------------------------------------
    // Internal Utilities - Preference Loading
    // --------------------------------------------------------

    /**
     * Reconstructs the current audio preferences from local storage.
     *
     * Supplies defaults for missing values and normalizes volume
     * so consumers always receive a valid preference snapshot.
     */
    private fun read(): SoundVoicePreferences {

        // Read the saved volume and constrain it to the supported range.
        val volume = prefs.getInt("volume", 70).coerceIn(0, 100)

        // Load each workflow's speech preference using its default.
        val reminders = prefs.getBoolean("reminders", false)
        val responses = prefs.getBoolean("responses", false)
        val briefings = prefs.getBoolean("briefings", true)

        // Combine stored values into one immutable preference snapshot.
        return SoundVoicePreferences(
            volume = volume,
            reminders = reminders,
            responses = responses,
            briefings = briefings,
        )
    }
}
