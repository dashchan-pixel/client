package com.mishiranu.dashchan.text.style

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.LineBackgroundSpan
import com.mishiranu.dashchan.graphics.ColorScheme

/**
 * Renders an AI ("@monkey") answer block as a quote: a subtle full-width background
 * tint with an accent-coloured bar down the left edge. The block's left indent is
 * provided separately by the [android.text.style.LeadingMarginSpan] attached alongside
 * this span in [chan.content.ChanMarkup], so the bar sits in that margin, clear of the text.
 */
class NeuroslopSpan :
    LineBackgroundSpan,
    ColorScheme.Span {
    private var backgroundColor = 0
    private var barColor = 0

    override fun applyColorScheme(colorScheme: ColorScheme?) {
        if (colorScheme != null) {
            backgroundColor = colorScheme.neuroslopColor
            barColor = colorScheme.accentColor
        }
    }

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val paintColor = paint.color
        val paintStyle = paint.style
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = barColor
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + BAR_WIDTH).toFloat(), bottom.toFloat(), paint)
        paint.color = paintColor
        paint.style = paintStyle
    }

    companion object {
        private val BAR_WIDTH = (3f * Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
