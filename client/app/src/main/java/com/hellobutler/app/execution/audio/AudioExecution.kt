package com.hellobutler.app.execution.audio

import kotlinx.serialization.Serializable

@Serializable
data class AudioExecution(
    val id: String,
    val workflow: AudioWorkflow,
    val speechPath: String,
    val eventId: String? = null,
    val eventVersion: Int? = null,
    val requestId: String? = null,
    val automatic: Boolean = true,
    val privateRoute: Boolean = false,
    val definition: String = AudioWorkflows.definitions.getValue(workflow),
    val next: Int = 0,
    val dueAt: Long = 0,
    val status: String = "ready",
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)
