package com.hellobutler.app.execution.audio

/** Playback waits for completion; delay scheduling returns immediately with no live player. */
class AudioSequence(
    private val checkpoint: (AudioExecution) -> Unit,
    private val play: suspend (String) -> Boolean,
    private val schedule: (AudioExecution) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(execution: AudioExecution) {
        val steps = AudioWorkflowParser.parse(execution.definition)
        var current = execution
        for (index in execution.next until steps.size) {
            when (val step = steps[index]) {
                is AudioStep.Play -> {
                    // A crash during audible output is ambiguous. Never replay that segment automatically.
                    checkpoint(current.copy(next = index, status = "playing"))
                    val successful = play(step.resource)
                    if (!successful && step.resource == "<speech>") {
                        checkpoint(current.copy(status = "cancelled"))
                        return
                    }
                    current = current.copy(next = index + 1, status = "ready", dueAt = 0)
                    checkpoint(current)
                }
                is AudioStep.Delay -> {
                    if (step.seconds == 0L) continue
                    current = current.copy(next = index + 1, dueAt = clock() + step.seconds * 1000, status = "waiting")
                    checkpoint(current) // Commit before enqueue; startup recovery repairs the enqueue gap.
                    schedule(current)
                    return
                }
            }
        }
        checkpoint(current.copy(status = "complete"))
    }
}
