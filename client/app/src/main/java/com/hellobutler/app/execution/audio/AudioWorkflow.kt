package com.hellobutler.app.execution.audio

sealed interface AudioStep {
    data class Play(val resource: String) : AudioStep
    data class Delay(val seconds: Long) : AudioStep
}

enum class AudioWorkflow { MORNING_BRIEF, GOOD_NIGHT, REMINDER, BUTLER_RESPONSE }

object AudioWorkflows {
    val definitions = mapOf(
        AudioWorkflow.MORNING_BRIEF to "PLAY(long_opening);PLAY(morning_warmup);DELAY(300);PLAY(short_opening);PLAY(<speech>)",
        AudioWorkflow.GOOD_NIGHT to "PLAY(long_opening);PLAY(evening_warmup);PLAY(<speech>)",
        AudioWorkflow.REMINDER to "PLAY(short_opening);PLAY(<speech>)",
        AudioWorkflow.BUTLER_RESPONSE to "PLAY(<speech>)",
    )
    val groups = mapOf(
        "morning_warmup" to (0..4).map { "morning_warmup_$it" },
        "evening_warmup" to (0..4).map { "evening_warmup_$it" },
    )
    val resources = setOf("long_opening", "short_opening", "volume_preview") + groups.values.flatten()
    fun forEvent(type: String) = when (type) {
        "morning_brief" -> AudioWorkflow.MORNING_BRIEF
        "good_night_summary" -> AudioWorkflow.GOOD_NIGHT
        else -> AudioWorkflow.REMINDER
    }
}

object AudioWorkflowParser {
    private val play = Regex("PLAY\\((<speech>|[a-z][a-z0-9_]*)\\)")
    private val delay = Regex("DELAY\\(([0-9]+)\\)")
    fun parse(definition: String): List<AudioStep> {
        require(definition.length <= 4096) { "Audio workflow is too long" }
        val commands = definition.split(';')
        require(commands.size in 1..64) { "Invalid number of audio operations" }
        val steps = commands.map { raw ->
            val command = raw.trim()
            play.matchEntire(command)?.let { return@map AudioStep.Play(it.groupValues[1]) }
            val seconds = delay.matchEntire(command)?.groupValues?.get(1)?.toLongOrNull()
            require(seconds != null && seconds in 0..86400) { "Invalid audio operation: $command" }
            AudioStep.Delay(seconds)
        }
        require(steps.count { it == AudioStep.Play("<speech>") } == 1) { "Audio workflow requires exactly one speech" }
        return steps
    }
}

/** Remaining recordings are persisted by the caller; groups never share a rotation. */
fun nextRotation(resources: List<String>, remaining: List<String>, shuffle: (List<String>) -> List<String> = { it.shuffled() }): Pair<String, List<String>> {
    require(resources.isNotEmpty())
    val valid = remaining.isNotEmpty() && remaining.distinct().size == remaining.size && remaining.all { it in resources }
    val cycle = if (valid) remaining else shuffle(resources)
    return cycle.first() to cycle.drop(1)
}

fun butlerVolume(percent: Int): Float = percent.coerceIn(0, 100) / 100f

fun automaticSpeechEnabled(workflow: AudioWorkflow, reminders: Boolean, responses: Boolean, briefings: Boolean) = when (workflow) {
    AudioWorkflow.MORNING_BRIEF, AudioWorkflow.GOOD_NIGHT -> briefings
    AudioWorkflow.REMINDER -> reminders
    AudioWorkflow.BUTLER_RESPONSE -> responses
}
