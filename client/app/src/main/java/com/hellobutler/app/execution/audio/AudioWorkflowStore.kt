package com.hellobutler.app.execution.audio

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small device execution journal, separate from authoritative Room/server content. */
class AudioWorkflowStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("butler_audio_workflows", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun get(id: String): AudioExecution? = prefs.getString("execution:$id", null)?.let {
        runCatching { json.decodeFromString<AudioExecution>(it) }.getOrNull()
    }
    fun all(): List<AudioExecution> = prefs.all.keys.filter { it.startsWith("execution:") }.mapNotNull { get(it.removePrefix("execution:")) }
    fun save(value: AudioExecution) = synchronized(executionLock) {
        check(prefs.edit().putString("execution:${value.id}", json.encodeToString(value.copy(updatedAt = System.currentTimeMillis()))).commit())
    }
    fun transition(expected: AudioExecution, updated: AudioExecution, action: () -> Unit): Boolean = synchronized(executionLock) {
        val current = get(expected.id) ?: return@synchronized false
        if (current.next != expected.next || current.dueAt != expected.dueAt || current.status != expected.status) return@synchronized false
        action()
        save(updated)
        true
    }
    fun select(group: String): String = synchronized(rotationLock) {
        val resources = AudioWorkflows.groups.getValue(group)
        val remaining = prefs.getString("rotation:$group", "").orEmpty().split(',').filter { it.isNotEmpty() }
        val (selected, rest) = nextRotation(resources, remaining)
        check(prefs.edit().putString("rotation:$group", rest.joinToString(",")).commit())
        selected
    }
    fun clear() { check(prefs.edit().clear().commit()) }
    fun prune(now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        all().filter { it.status in setOf("complete", "cancelled") && now - it.updatedAt > 7 * 86400_000L }
            .forEach {
                editor.remove("execution:${it.id}")
                if (it.eventId != null) {
                    val file = java.io.File(it.speechPath)
                    if (file.parentFile == java.io.File(context.filesDir, "workflow_audio")) file.delete()
                }
            }
        editor.apply()
    }
    companion object { private val rotationLock = Any(); private val executionLock = Any() }
}
