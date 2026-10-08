package com.hellobutler.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetResponseContentTest {
    @Test fun preservesCompleteCanonicalTextIncludingParagraphsAndUnicode() {
        val cases = listOf("", "Hello Butler", "A long paragraph. ".repeat(1000),
            "\nLine one\n\n  Line two\n", "x".repeat(239) + "\uD83D\uDD14" + "y".repeat(600), "字".repeat(1200))
        cases.forEach { text ->
            val rows = responseRows(text)
            assertEquals(text, rows.joinToString(""))
            assertTrue(rows.all { it.isNotEmpty() && it.length <= 240 })
            assertTrue(rows.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        }
    }
}
