package com.hellobutler.app.execution.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Pure contract checks can also run with the Kotlin compiler when Android tooling is unavailable. */
object AudioWorkflowChecks {
    fun parser() {
        AudioWorkflows.definitions.values.forEach { check(AudioWorkflowParser.parse(it).isNotEmpty()) }
        listOf("", "PLAY(foo)", "PLAY(<speech>);", "DELAY(-1);PLAY(<speech>)",
            "DELAY(86401);PLAY(<speech>)", "DELAY(999999999999999999999);PLAY(<speech>)",
            "EXEC(foo);PLAY(<speech>)", "PLAY(../../x);PLAY(<speech>)",
            "PLAY(<speech>);PLAY(<speech>)", "PLAY(<speech>)garbage").forEach {
            check(runCatching { AudioWorkflowParser.parse(it) }.isFailure) { "Accepted invalid workflow $it" }
        }
        check(AudioWorkflowParser.parse(" PLAY(short_opening) ; PLAY(<speech>) ").size == 2)
    }
    fun rotation() {
        val groups = AudioWorkflows.groups
        check(groups.size == 2 && groups.values.all { it.size == 5 })
        val remaining = mutableMapOf<String, List<String>>()
        val used = mutableMapOf<String, MutableList<String>>()
        repeat(10) { index ->
            val group = if (index % 2 == 0) "morning_warmup" else "evening_warmup"
            val (next, rest) = nextRotation(groups.getValue(group), remaining[group].orEmpty()) { it.reversed() }
            remaining[group] = rest
            used.getOrPut(group) { mutableListOf() }.add(next)
        }
        used.forEach { (group, cycle) -> check(cycle.toSet() == groups.getValue(group).toSet()) }
        val recovered = nextRotation(groups.getValue("morning_warmup"), listOf("morning_warmup_2", "morning_warmup_1"))
        check(recovered.first == "morning_warmup_2" && recovered.second == listOf("morning_warmup_1"))
        check(nextRotation(listOf("a", "b"), listOf("missing")) { it }.first == "a")
    }
    fun preferences() {
        check(butlerVolume(70) == .7f && butlerVolume(-10) == 0f && butlerVolume(200) == 1f)
        AudioWorkflow.entries.forEach { workflow ->
            check(!automaticSpeechEnabled(workflow, false, false, false))
            check(automaticSpeechEnabled(workflow, true, true, true))
        }
        check(automaticSpeechEnabled(AudioWorkflow.MORNING_BRIEF, false, false, true))
        check(!automaticSpeechEnabled(AudioWorkflow.REMINDER, false, true, true))
        check(!automaticSpeechEnabled(AudioWorkflow.BUTLER_RESPONSE, true, false, true))
    }
    fun sequences() = runBlocking {
        for (workflow in listOf(AudioWorkflow.GOOD_NIGHT, AudioWorkflow.REMINDER, AudioWorkflow.BUTLER_RESPONSE)) {
            val heard = mutableListOf<String>()
            var journal: AudioExecution? = null
            AudioSequence({ journal = it }, { heard.add(it); true }, { error("Unexpected delay") })
                .run(AudioExecution("id", workflow, "speech"))
            val expected = when (workflow) {
                AudioWorkflow.GOOD_NIGHT -> listOf("long_opening", "evening_warmup", "<speech>")
                AudioWorkflow.REMINDER -> listOf("short_opening", "<speech>")
                else -> listOf("<speech>")
            }
            check(heard == expected && journal?.status == "complete")
        }
    }
    fun persistentDelay() = runBlocking {
        var now = 1000L
        val heard = mutableListOf<String>()
        var journal: AudioExecution? = null
        var scheduled: AudioExecution? = null
        val sequence = AudioSequence({ journal = it }, { resource ->
            heard.add(resource); now += 20_000; true
        }, { check(journal == it); scheduled = it }, { now })
        sequence.run(AudioExecution("morning", AudioWorkflow.MORNING_BRIEF, "speech"))
        check(heard == listOf("long_opening", "morning_warmup"))
        check(scheduled?.dueAt == 341_000L) // Starts AFTER both recordings finish.
        check(scheduled?.next == 3 && scheduled?.status == "waiting")
        val persisted = scheduled!!
        now = persisted.dueAt
        sequence.run(persisted) // A new runner needs only the journal, never a live delay coroutine.
        check(heard == listOf("long_opening", "morning_warmup", "short_opening", "<speech>"))
        check(journal?.status == "complete")
    }
    fun failuresAndCancellation() = runBlocking {
        val heard = mutableListOf<String>()
        var journal: AudioExecution? = null
        AudioSequence({ journal = it }, { heard.add(it); false }, { error("Unexpected schedule") })
            .run(AudioExecution("reminder", AudioWorkflow.REMINDER, "speech"))
        check(heard == listOf("short_opening", "<speech>") && journal?.status == "cancelled")
        heard.clear()
        try {
            AudioSequence({ journal = it }, { heard.add(it); throw CancellationException("Stop") }, { error("Unexpected schedule") })
                .run(AudioExecution("night", AudioWorkflow.GOOD_NIGHT, "speech"))
            error("Cancellation swallowed")
        } catch (_: CancellationException) { }
        check(heard == listOf("long_opening") && journal?.status == "playing")
    }
    @JvmStatic fun main(args: Array<String>) {
        parser(); rotation(); preferences(); sequences(); persistentDelay(); failuresAndCancellation()
        println("Audio workflow contract checks: 6 groups passed")
    }
}
