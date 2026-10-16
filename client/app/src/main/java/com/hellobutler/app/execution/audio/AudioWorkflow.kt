
package com.hellobutler.app.execution.audio


// ------------------------------------------------------------
// Models - Audio Workflow Steps
// ------------------------------------------------------------

/**
 * Represents an individual operation in an audio workflow.
 *
 * Restricts workflow instructions to supported operations,
 * allowing the audio sequence engine to process them safely.
 */
sealed interface AudioStep {

    /**
     * Represents an instruction to play an audio resource.
     *
     * The resource can identify a bundled recording or the
     * special "<speech>" placeholder for generated speech.
     */
    data class Play(val resource: String) : AudioStep

    /**
     * Represents a delay between audio operations.
     *
     * Stores the duration in seconds so the sequence engine
     * can schedule the next operation.
     */
    data class Delay(val seconds: Long) : AudioStep
}


// ------------------------------------------------------------
// Models - Audio Workflow Types
// ------------------------------------------------------------

/**
 * Identifies the supported Butler audio workflows.
 *
 * Each workflow has its own sequence definition and
 * automatic-speech preference behavior.
 */
enum class AudioWorkflow {
    MORNING_BRIEF,
    GOOD_NIGHT,
    REMINDER,
    BUTLER_RESPONSE,
}


// ------------------------------------------------------------
// Configuration - Audio Workflows
// ------------------------------------------------------------

/**
 * Centralizes audio workflow definitions and resource groups.
 *
 * Provides the configuration required to construct playback
 * sequences without hardcoding their steps inside the player.
 */
object AudioWorkflows {

    // --------------------------------------------------------
    // Workflow Definitions
    // --------------------------------------------------------

    // Define the playback sequence for each supported workflow.
    // PLAY executes an audio segment; DELAY pauses before continuing.
    val definitions = mapOf(
        // Morning briefing: opening, warm-up, delay, chime, then speech.
        AudioWorkflow.MORNING_BRIEF to
                "PLAY(long_opening);PLAY(morning_warmup);DELAY(300);PLAY(short_opening);PLAY(<speech>)",

        // Good-night summary: opening, evening warm-up, then speech.
        AudioWorkflow.GOOD_NIGHT to
                "PLAY(long_opening);PLAY(evening_warmup);PLAY(<speech>)",

        // Reminder: short opening followed by generated speech.
        AudioWorkflow.REMINDER to
                "PLAY(short_opening);PLAY(<speech>)",

        // Butler response: play generated speech without an opening.
        AudioWorkflow.BUTLER_RESPONSE to
                "PLAY(<speech>)",
    )

    // --------------------------------------------------------
    // Audio Resource Groups
    // --------------------------------------------------------

    // Group alternative recordings under a shared logical name.
    // The resource selector chooses a recording from each group.
    val groups = mapOf(
        "morning_warmup" to (0..4).map {
            "morning_warmup_$it"
        },

        "evening_warmup" to (0..4).map {
            "evening_warmup_$it"
        },
    )

    // Collect all named audio resources used by the workflow system.
    val resources = setOf(
        "long_opening",
        "short_opening",
        "volume_preview",
    ) + groups.values.flatten()

    // --------------------------------------------------------
    // Workflow Resolution
    // --------------------------------------------------------

    /**
     * Maps a daily-event type to its corresponding audio workflow.
     *
     * Uses dedicated workflows for morning and night events,
     * while treating all other event types as reminders.
     */
    fun forEvent(type: String) = when (type) {
        "morning_brief" -> AudioWorkflow.MORNING_BRIEF
        "good_night_summary" -> AudioWorkflow.GOOD_NIGHT
        else -> AudioWorkflow.REMINDER
    }
}


// ------------------------------------------------------------
// Parser - Audio Workflow Instructions
// ------------------------------------------------------------

/**
 * Converts workflow definition strings into executable audio steps.
 *
 * Validates supported commands and sequence constraints before
 * the audio engine attempts playback or scheduling.
 */
object AudioWorkflowParser {

    // Match PLAY commands containing a named audio resource
    // or the special generated-speech placeholder.
    private val play = Regex("PLAY\\((<speech>|[a-z][a-z0-9_]*)\\)")

    // Match DELAY commands containing a nonnegative integer.
    private val delay = Regex("DELAY\\(([0-9]+)\\)")

    /**
     * Parses and validates an audio workflow definition.
     *
     * Ensures the workflow contains supported operations,
     * respects safety limits, and includes exactly one speech step.
     */
    fun parse(definition: String): List<AudioStep> {

        // Reject excessively long definitions before parsing.
        require(definition.length <= 4096) {
            "Audio workflow is too long"
        }

        // Separate the definition into individual commands.
        val commands = definition.split(';')

        // Limit the number of operations to prevent oversized sequences.
        require(commands.size in 1..64) {
            "Invalid number of audio operations"
        }

        // ----------------------------------------------------
        // Parse Individual Commands
        // ----------------------------------------------------

        // Convert each command into a strongly typed AudioStep.
        val steps = commands.map { raw ->

            // Remove surrounding whitespace before validation.
            val command = raw.trim()

            // Recognize PLAY commands and extract their resource names.
            play.matchEntire(command)?.let {
                return@map AudioStep.Play(it.groupValues[1])
            }

            // Attempt to extract a numeric delay duration.
            // An unmatched PLAY command also reaches this validation.
            val seconds = delay.matchEntire(command)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()

            // Reject unknown commands and delays outside 0–86400 seconds.
            require(seconds != null && seconds in 0..86400) {
                "Invalid audio operation: $command"
            }

            // Represent a valid delay as an executable step.
            AudioStep.Delay(seconds)
        }

        // ----------------------------------------------------
        // Validate Complete Workflow
        // ----------------------------------------------------

        // Require exactly one generated-speech operation per workflow.
        require(
            steps.count {
                it == AudioStep.Play("<speech>")
            } == 1
        ) {
            "Audio workflow requires exactly one speech"
        }

        // Return the validated operations in their original order.
        return steps
    }
}


// ------------------------------------------------------------
// Utilities - Audio Resource Rotation
// ------------------------------------------------------------

/**
 * Selects the next recording from a resource group.
 *
 * Reuses a valid remaining rotation or creates a new shuffled
 * cycle when the previous one is exhausted or invalid.
 *
 * Returns the selected recording and the recordings still
 * available in the current cycle. Persistence is handled
 * by the caller so different groups retain independent rotations.
 */
fun nextRotation(
    resources: List<String>,
    remaining: List<String>,
    shuffle: (List<String>) -> List<String> = { it.shuffled() },
): Pair<String, List<String>> {

    // Require at least one recording to make selection possible.
    require(resources.isNotEmpty())

    // Accept an existing rotation only when it contains
    // unique recordings that belong to the requested group.
    val valid = remaining.isNotEmpty() &&
            remaining.distinct().size == remaining.size &&
            remaining.all { it in resources }

    // Continue a valid rotation or start a newly shuffled cycle.
    val cycle = if (valid) {
        remaining
    } else {
        shuffle(resources)
    }

    // Select the first recording and retain the rest for later use.
    return cycle.first() to cycle.drop(1)
}


// ------------------------------------------------------------
// Utilities - Volume Conversion
// ------------------------------------------------------------

/**
 * Converts a percentage into a normalized playback volume.
 *
 * Clamps input to 0–100 and produces a Float between 0.0
 * and 1.0 for the audio playback component.
 */
fun butlerVolume(percent: Int): Float {

    // Normalize the configured percentage into an audio gain value.
    return percent.coerceIn(0, 100) / 100f
}


// ------------------------------------------------------------
// Utilities - Automatic Speech Policy
// ------------------------------------------------------------

/**
 * Determines whether a workflow is allowed to speak automatically.
 *
 * Maps each workflow to the corresponding user preference,
 * keeping automatic-speech decisions consistent across callers.
 */
fun automaticSpeechEnabled(
    workflow: AudioWorkflow,
    reminders: Boolean,
    responses: Boolean,
    briefings: Boolean,
) = when (workflow) {

    // Morning and night announcements share the briefing preference.
    AudioWorkflow.MORNING_BRIEF,
    AudioWorkflow.GOOD_NIGHT -> briefings

    // Reminders use their own automatic-speech preference.
    AudioWorkflow.REMINDER -> reminders

    // Butler responses use the response speech preference.
    AudioWorkflow.BUTLER_RESPONSE -> responses
}
