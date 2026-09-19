package com.hellobutler.app.speech

import java.io.File

internal fun File.isOggOpusContainer(): Boolean {
    if (length() < 12) return false
    val header = ByteArray(256)
    val count = runCatching { inputStream().use { it.read(header) } }.getOrElse { return false }
    if (count < 12 || !header.copyOfRange(0, 4).contentEquals("OggS".encodeToByteArray())) {
        return false
    }
    val opusHead = "OpusHead".encodeToByteArray()
    return (0..count - opusHead.size).any { start ->
        opusHead.indices.all { offset -> header[start + offset] == opusHead[offset] }
    }
}
