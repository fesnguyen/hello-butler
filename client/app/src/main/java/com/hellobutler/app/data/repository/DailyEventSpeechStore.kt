package com.hellobutler.app.data.repository

import android.content.SharedPreferences
import com.hellobutler.app.data.local.DailyEventEntity
import com.hellobutler.app.execution.canPrepareSpeech
import com.hellobutler.app.execution.spokenText
import com.hellobutler.app.speech.isOggOpusContainer
import java.io.File
import java.util.UUID

/** File and voice-invalidation storage; the repository owns all Room checks and synchronization. */
internal class DailyEventSpeechStore(
    private val filesDir: File,
    private val cacheDir: File,
    private val preferences: SharedPreferences,
) {
    private val directory get() = File(filesDir, "event_speech")

    /** Reads validated local speech without authentication refresh or network access. */
    fun preparedSpeech(event: DailyEventEntity): File? =
        speechFile(event.id, event.version).takeIf {
            event.syncedFromServer && !invalidatedByVoiceChange(event) && it.isOggOpusContainer()
        }

    /** Returns the protected snapshot identity shared by executions of this event version. */
    fun workflowSpeechFile(event: DailyEventEntity) =
        File(File(filesDir, "workflow_audio"), "${event.id}-${event.version}.ogg")

    /** Copies validated bytes into protected storage while the caller holds its audio-state lock. */
    fun snapshot(event: DailyEventEntity): File? {
        val target = workflowSpeechFile(event)
        if (target.isOggOpusContainer()) return target
        val source = preparedSpeech(event) ?: return null
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".${UUID.randomUUID()}.part")
        try {
            source.copyTo(partial)
            if (!partial.isOggOpusContainer() || !partial.renameTo(target)) throw java.io.IOException("Could not publish playback snapshot")
        } finally { partial.delete() }
        return target
    }

    /** Allocates an unpublished transfer file beside its final target for atomic rename. */
    fun partial(eventId: String, version: Int): File {
        directory.mkdirs()
        return File(speechFile(eventId, version).path + ".${UUID.randomUUID()}.part")
    }

    /** Publishes a validated download after the repository rechecks canonical event state. */
    fun publish(partial: File, eventId: String, version: Int): File {
        val target = speechFile(eventId, version)
        if (!partial.renameTo(target)) throw java.io.IOException("Could not publish event speech audio")
        return target
    }

    /** Discards unusable prepared bytes and their protected copy before source fallback. */
    fun discard(event: DailyEventEntity): Boolean {
        speechFile(event.id, event.version).delete()
        return workflowSpeechFile(event).delete()
    }

    /** Reuses bytes only for an exact canonical echo of a compatible local edit. */
    fun rebind(previous: DailyEventEntity, current: DailyEventEntity?) {
        // Remote versions alone cannot prove that voice/provider settings stayed the same.
        if (current != null && current.syncedFromServer && !previous.syncedFromServer &&
            current.version == previous.version + 1 && !invalidatedByVoiceChange(previous) &&
            current.eventDate == previous.eventDate && current.startTime == previous.startTime &&
            previous.spokenText() == current.spokenText() && previous.eventType == current.eventType &&
            previous.speakAloud == current.speakAloud && current.status == "planned") {
            val old = speechFile(previous.id, previous.version)
            val target = speechFile(current.id, current.version)
            if (old != target && old.isOggOpusContainer()) {
                val partial = File(target.path + ".${UUID.randomUUID()}.part")
                try {
                    old.copyTo(partial)
                    if (!partial.renameTo(target)) throw java.io.IOException("Could not rebind speech")
                } finally { partial.delete() }
            }
        }
    }

    /** Removes obsolete prepared files without touching active workflow snapshots. */
    fun reconcile(previous: DailyEventEntity?, current: DailyEventEntity?): String? {
        val id = current?.id ?: previous?.id ?: return null
        val keep = current?.takeIf {
            it.canPrepareSpeech() || (!it.syncedFromServer && previous != null &&
                previous.spokenText() == it.spokenText() && previous.eventType == it.eventType && previous.speakAloud == it.speakAloud)
        }?.let { speechFile(it.id, it.version).name }
        directory.listFiles()?.filter {
            it.name.startsWith("$id-") && it.name != keep && !it.name.endsWith(".part")
        }?.forEach(File::delete)
        return keep
    }

    /** Cleans abandoned files at restoration without erasing eligible durable speech. */
    fun recover(events: List<DailyEventEntity>) {
        val keep = events.filter { it.canPrepareSpeech() }.mapTo(mutableSetOf()) { speechFile(it.id, it.version).name }
        directory.listFiles()?.filter { it.name.endsWith(".ogg") && it.name !in keep }?.forEach(File::delete)
        directory.listFiles()?.filter {
            it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 86400_000L
        }?.forEach(File::delete)
    }

    /** Detects event versions whose protected and prepared speech uses an obsolete voice. */
    fun invalidatedByVoiceChange(event: DailyEventEntity) =
        "${event.id}:${event.version}" in preferences.getStringSet("invalidated_versions", emptySet()).orEmpty()

    /** Reads the last reconciled TTS method without changing its invalidation history. */
    fun ttsMethod(): String? = preferences.getString("tts_method", null)

    /** Persists invalidation before playback cancellation and prepared-file cleanup. */
    fun invalidate(events: List<DailyEventEntity>) {
        preferences.edit().putStringSet("invalidated_versions", events.map { "${it.id}:${it.version}" }.toSet()).commit()
    }

    /** Records the reconciled method after its existing cleanup and sync effects. */
    fun saveTtsMethod(method: String): Boolean = preferences.edit().putString("tts_method", method).commit()

    /** Removes the legacy cache before querying canonical events during restoration. */
    fun clearLegacyCache() { File(cacheDir, "event_speech").deleteRecursively() }

    /** Deletes prepared speech while preserving protected workflow snapshots. */
    fun deletePrepared() { directory.deleteRecursively() }

    /** Clears prepared files and voice state during the repository's locked logout cleanup. */
    fun clear() {
        preferences.edit().clear().commit()
        deletePrepared()
        File(cacheDir, "event_speech").deleteRecursively()
    }

    /** Keeps the existing event/version filename contract for downloads and reconciliation. */
    private fun speechFile(eventId: String, version: Int) = File(directory, "$eventId-$version.ogg")
}
