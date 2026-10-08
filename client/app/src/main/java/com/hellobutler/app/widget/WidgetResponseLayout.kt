package com.hellobutler.app.widget

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SizeF
import android.graphics.Paint
import com.hellobutler.app.R
import kotlin.math.ceil

internal data class ResponsePresentation(val scrolls: Boolean, val heightPx: Int)

// Leave all controls visible; extra launcher height stays transparent once the useful cap is reached.
internal fun responsePresentation(contentHeightPx: Int, availableHeightPx: Int, maximumHeightPx: Int): ResponsePresentation {
    val cap = availableHeightPx.coerceAtLeast(1).coerceAtMost(maximumHeightPx.coerceAtLeast(1))
    return ResponsePresentation(contentHeightPx > cap, contentHeightPx.coerceAtMost(cap))
}

internal class WidgetResponseLayout(context: Context, size: SizeF, text: String) {
    private val resources = context.resources
    private val density = resources.displayMetrics.density
    val compact = size.height < 300f
    val rootPadding = if (compact) 6 else 8
    val cardPadding = if (compact) 4 else 6
    val readingPadding = if (compact) 8 else 10
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.widget_action_text_size)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val labelWidth = dp((size.width - rootPadding * 2 - 16) / 3).coerceAtLeast(1)
    private val labelHeight = listOf(R.string.widget_open, R.string.widget_talk, R.string.widget_note).maxOf { id ->
        val label = resources.getString(id)
        StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, labelWidth).setIncludePad(false).build().height
    }
    val actionHeight = maxOf(if (compact) 64 else 76, ceil(labelHeight / density + 51).toInt())
    // Header 48 + playback 48 + playback gap 4 + action gap 8.
    private val chrome = 108 + actionHeight + 2 * (rootPadding + cardPadding + readingPadding)
    private val availablePx = dp(size.height - chrome)
    private val textWidth = dp(size.width - 2 * (rootPadding + cardPadding + readingPadding)).coerceAtLeast(1)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.widget_response_text_size)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val measured = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
        .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().height + dp(2f)
    val presentation = responsePresentation(measured, availablePx, dp(216f))
    // Before API 31 RemoteViews cannot set LayoutParams.height. Fixed viewports cap ListView reliably.
    val legacyViewport = listOf(32 to R.layout.widget_response_32, 48 to R.layout.widget_response_48,
        64 to R.layout.widget_response_64, 96 to R.layout.widget_response_96, 128 to R.layout.widget_response_128,
        160 to R.layout.widget_response_160, 208 to R.layout.widget_response_208)
        .lastOrNull { dp(it.first.toFloat()) <= presentation.heightPx }?.second ?: R.layout.widget_response_32

    fun dp(value: Float) = ceil(value * density).toInt()
}
