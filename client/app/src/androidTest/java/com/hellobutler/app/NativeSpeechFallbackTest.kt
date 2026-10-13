package com.hellobutler.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.execution.LocalTextToSpeech
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run with networking disabled and offline voice data installed. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechFallbackTest {
    @Test fun offlineInstalledVoiceProducesPlayableSizedFileAndCleansUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assumeTrue("Disable networking for offline acceptance", connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true)
        val initialized = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var available = false
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(context) { status ->
                Handler(Looper.getMainLooper()).post {
                    available = status == TextToSpeech.SUCCESS && tts?.voices.orEmpty().any {
                        !it.isNetworkConnectionRequired && it.locale.language in setOf(Locale.getDefault().language, "en") &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }
                    initialized.countDown()
                }
            }
        }
        try { assertTrue(initialized.await(15, TimeUnit.SECONDS)) }
        finally { tts?.shutdown() }
        assumeTrue("Install an offline voice in the device locale or English", available)
        val target = File(context.cacheDir, "offline-native-test.wav")
        try {
            assertTrue(LocalTextToSpeech(context).renderOffline("Your reminder is ready.", target))
            assertTrue(target.length() > 44)
            val decoder = android.media.MediaPlayer()
            try { decoder.setDataSource(target.path); decoder.prepare(); assertTrue(decoder.duration > 0) }
            finally { decoder.release() }
        } finally { target.delete() }
    }
}
