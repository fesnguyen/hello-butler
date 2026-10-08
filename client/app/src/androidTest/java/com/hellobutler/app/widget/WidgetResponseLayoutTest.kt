package com.hellobutler.app.widget

import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hellobutler.app.R
import com.hellobutler.app.data.local.ConversationMessageEntity
import com.hellobutler.app.execution.ButlerPlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetResponseLayoutTest {
    @Test fun heightGrowsWithContentAndCapsOnlyLongResponses() {
        assertEquals(ResponsePresentation(false, 40), responsePresentation(40, 180, 216))
        assertEquals(ResponsePresentation(false, 120), responsePresentation(120, 180, 216))
        assertEquals(ResponsePresentation(true, 180), responsePresentation(600, 180, 216))
        assertEquals(ResponsePresentation(true, 216), responsePresentation(600, 800, 216))
    }

    @Test fun nativeMeasurementRespectsWidthAndAvailableHeight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val short = WidgetResponseLayout(context, SizeF(320f, 400f), "Hello Butler")
        val medium = WidgetResponseLayout(context, SizeF(320f, 400f), "Here is your message. ".repeat(8))
        val long = WidgetResponseLayout(context, SizeF(320f, 400f), "Here is your message. ".repeat(100))
        assertFalse(short.presentation.scrolls)
        assertTrue(medium.presentation.heightPx > short.presentation.heightPx)
        assertTrue(long.presentation.scrolls)
        val small = WidgetResponseLayout(context, SizeF(250f, 250f), "Long message. ".repeat(100))
        assertTrue(small.presentation.heightPx < long.presentation.heightPx)
        assertTrue(small.presentation.scrolls)
    }

    @Test fun compactRemoteViewsInflateWithoutNavigationOnText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val message = ConversationMessageEntity("test:butler", "test", "butler", "Hello Butler",
                "2026-10-08T00:00:00Z", "completed", "text", audioCacheState = "cached")
            val view = ButlerWidgetProvider.createViews(context, 1, SizeF(320f, 400f), message, ButlerPlaybackState())
                .apply(context, FrameLayout(context))
            val text = view.findViewById<TextView>(R.id.widget_response_compact)
            assertEquals(message.text, text.text.toString())
            assertFalse(text.isClickable)
            assertFalse(text.hasOnClickListeners())
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_response_collection).visibility)
            assertNotNull(view.findViewById<View>(R.id.widget_home))
            assertNotNull(view.findViewById<View>(R.id.widget_open))
        }
    }
}
