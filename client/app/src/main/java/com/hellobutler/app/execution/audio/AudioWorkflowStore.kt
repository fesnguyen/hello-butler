
package com.hellobutler.app.execution.audio

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


// ------------------------------------------------------------
// Storage - Audio Workflow Execution Journal
// ------------------------------------------------------------

/**
 * Maintains a lightweight, device-local journal of audio executions.
 *
 * Stores execution progress and resource rotation state using
 * SharedPreferences, separately from authoritative Room/server data.
 *
 * Also provides guarded state transitions and cleanup of old
 * execution records and eligible local speech files.
 */
class AudioWorkflowStore(
    private val context: Context,
) {

    // --------------------------------------------------------
    // Initialization - Local Storage
    // --------------------------------------------------------

    // Open private storage for workflow progress and resource rotations.
    private val prefs = context.getSharedPreferences(
        "butler_audio_workflows",
        Context.MODE_PRIVATE,
    )

    // Configure JSON decoding to tolerate fields added by newer versions.
    private val json = Json {
        ignoreUnknownKeys = true
    }

    // --------------------------------------------------------
    // Entry Point - Read Execution
    // --------------------------------------------------------

    /**
     * Retrieves a persisted audio execution by its ID.
     *
     * Returns null when the record is missing or its stored
     * JSON cannot be decoded into an AudioExecution.
     */
    fun get(id: String): AudioExecution? {

        // Retrieve the serialized execution, or return null if absent.
        val stored = prefs.getString("execution:$id", null)
            ?: return null

        // Decode the stored JSON without propagating decoding failures.
        return runCatching {
            json.decodeFromString<AudioExecution>(stored)
        }.getOrNull()
    }

    // --------------------------------------------------------
    // Entry Point - Read All Executions
    // --------------------------------------------------------

    /**
     * Retrieves all readable audio executions from local storage.
     *
     * Filters preference entries by their execution prefix
     * and skips records that are missing or cannot be decoded.
     */
    fun all(): List<AudioExecution> {

        // Find preference keys belonging to execution records.
        return prefs.all.keys
            .filter { it.startsWith("execution:") }

            // Extract the execution ID and load its stored record.
            .mapNotNull { key ->
                get(key.removePrefix("execution:"))
            }
    }

    // --------------------------------------------------------
    // Entry Point - Save Execution
    // --------------------------------------------------------

    /**
     * Persists an audio execution and refreshes its update timestamp.
     *
     * Uses a shared execution lock and synchronous commit to
     * serialize writes and detect persistence failures.
     */
    fun save(value: AudioExecution) = synchronized(executionLock) {

        // Record the current time without modifying the caller's object.
        val updated = value.copy(
            updatedAt = System.currentTimeMillis()
        )

        // Serialize the execution and save it under its unique ID.
        // Fail explicitly if SharedPreferences cannot commit the change.
        check(
            prefs.edit()
                .putString(
                    "execution:${value.id}",
                    json.encodeToString(updated),
                )
                .commit()
        )
    }

    // --------------------------------------------------------
    // Entry Point - Conditional State Transition
    // --------------------------------------------------------

    /**
     * Performs an execution action only when the expected state matches.
     *
     * Prevents stale workflow callbacks from advancing an execution
     * whose step, due time, or status has already changed.
     *
     * The supplied action runs before the updated state is saved,
     * preserving the existing transition order.
     */
    fun transition(
        expected: AudioExecution,
        updated: AudioExecution,
        action: () -> Unit,
    ): Boolean = synchronized(executionLock) {

        // Load the latest execution while holding the shared lock.
        val current = get(expected.id)
            ?: return@synchronized false

        // Reject the transition if another operation has changed
        // the execution's current step, scheduled time, or status.
        if (
            !current.matchesCheckpoint(expected)
        ) {
            return@synchronized false
        }

        // Perform the caller's side effect only for a matching state.
        action()

        // Scheduling can guard effects without rewriting an unchanged checkpoint.
        if (updated != expected) save(updated)

        // Confirm that the transition completed.
        true
    }

    /** Reserve an automatic identity once, including terminal suppression markers. */
    fun reserve(execution: AudioExecution): Boolean = synchronized(executionLock) {
        if (get(execution.id) != null) return@synchronized false
        save(execution)
        true
    }

    /** Invalidate first, then remove effects under the same lock used by schedule/transition. */
    fun cancel(id: String, expected: AudioExecution? = null, removeEffects: () -> Unit) = synchronized(executionLock) {
        val current = get(id)
        if (expected != null && (current == null || !current.matchesCheckpoint(expected))) return@synchronized
        if (current != null && current.status !in setOf("complete", "cancelled")) save(current.copy(status = "cancelled"))
        removeEffects()
    }

    // --------------------------------------------------------
    // Entry Point - Select Rotating Audio Resource
    // --------------------------------------------------------

    /**
     * Selects the next recording from a named audio resource group.
     *
     * Maintains an independent rotation for each group so recordings
     * are used through a cycle before a new shuffled cycle begins.
     */
    fun select(group: String): String = synchronized(rotationLock) {

        // Retrieve the configured recordings for this group.
        // Unknown group names cause getValue() to fail.
        val resources = AudioWorkflows.groups.getValue(group)

        // Restore the remaining recording names from local storage.
        // Empty entries are discarded to produce a clean list.
        val remaining = prefs.getString("rotation:$group", "")
            .orEmpty()
            .split(',')
            .filter { it.isNotEmpty() }

        // Select the next recording and obtain the remaining cycle.
        val (selected, rest) = nextRotation(
            resources,
            remaining,
        )

        // Persist the updated rotation before returning the selection.
        // Fail if the rotation state cannot be committed.
        check(
            prefs.edit()
                .putString(
                    "rotation:$group",
                    rest.joinToString(","),
                )
                .commit()
        )

        // Return the recording chosen for this playback.
        selected
    }

    // --------------------------------------------------------
    // Entry Point - Clear Local Workflow State
    // --------------------------------------------------------

    /**
     * Removes all stored audio executions and rotation state.
     *
     * Clears the SharedPreferences journal synchronously so
     * callers can detect a failed storage operation.
     */
    fun clear() {

        // Remove all keys belonging to this preference file.
        check(
            prefs.edit()
                .clear()
                .commit()
        )
    }

    // --------------------------------------------------------
    // Entry Point - Prune Old Executions
    // --------------------------------------------------------

    /**
     * Removes completed or cancelled executions older than seven days.
     *
     * Also deletes eligible event-associated speech files when
     * no active execution references their paths and the files
     * belong directly to Butler's workflow audio directory.
     *
     * Active executions are preserved regardless of their age.
     */
    fun prune(now: Long = System.currentTimeMillis()) {

        // Collect preference removals into one editor operation.
        val editor = prefs.edit()

        // Load the currently readable execution records.
        val executions = all()

        // ----------------------------------------------------
        // Identify Protected Speech Files
        // ----------------------------------------------------

        // Collect speech paths referenced by unfinished executions.
        // These files must not be deleted during cleanup.
        val activePaths = executions
            .filter {
                it.status !in setOf("complete", "cancelled")
            }
            .map {
                it.speechPath
            }
            .toSet()

        // ----------------------------------------------------
        // Remove Expired Execution Records
        // ----------------------------------------------------

        // Find terminal executions whose last update was over
        // seven days ago.
        executions
            .filter {
                it.status in setOf("complete", "cancelled") &&
                        now - it.updatedAt > 7 * 86400_000L
            }
            .forEach { execution ->

                // Remove the expired execution from the journal.
                editor.remove("execution:${execution.id}")

                // ------------------------------------------------
                // Delete Eligible Local Speech File
                // ------------------------------------------------

                // Only event-associated executions participate
                // in this speech-file cleanup path.
                if (execution.eventId != null) {

                    // Resolve the speech file recorded by the execution.
                    val file = java.io.File(execution.speechPath)

                    // Delete only when the file is not used by any
                    // active execution and belongs directly to the
                    // application's workflow audio directory.
                    if (
                        execution.speechPath !in activePaths &&
                        file.parentFile == java.io.File(
                            context.filesDir,
                            "workflow_audio",
                        )
                    ) {
                        file.delete()
                    }
                }
            }

        // Apply accumulated journal removals asynchronously.
        editor.apply()
    }

    // --------------------------------------------------------
    // Internal Synchronization - Shared Locks
    // --------------------------------------------------------

    companion object {

        // Serialize resource rotation selections across instances.
        private val rotationLock = Any()

        // Serialize execution writes and conditional transitions
        // across instances of AudioWorkflowStore.
        private val executionLock = Any()
    }
}
