package com.hellobutler.app.widget

// Small, non-interactive rows keep even a single long paragraph scrollable on older launchers.
// Retain every character, including whitespace, and never split a surrogate pair.
internal fun responseRows(text: String): List<String> {
    val rows = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + 240).coerceAtMost(text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start + 120).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        rows += text.substring(start, end)
        start = end
    }
    return rows
}

