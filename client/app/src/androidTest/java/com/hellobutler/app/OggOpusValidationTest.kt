package com.hellobutler.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.speech.isOggOpusContainer
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OggOpusValidationTest {
    private val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun acceptsOggOpusContainer() {
        val file = File.createTempFile("valid-opus", ".ogg", directory)
        file.writeBytes("OggS".encodeToByteArray() + ByteArray(24) + "OpusHead".encodeToByteArray())
        try {
            assertTrue(file.isOggOpusContainer())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsCorruptOgg() {
        val file = File.createTempFile("corrupt-opus", ".ogg", directory)
        file.writeBytes("not an ogg file".encodeToByteArray())
        try {
            assertFalse(file.isOggOpusContainer())
        } finally {
            file.delete()
        }
    }
}
