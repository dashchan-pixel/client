package com.mishiranu.dashchan.text.style

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import com.mishiranu.dashchan.graphics.ColorScheme

/**
 * Renders an AI ("@monkey") answer block as a quote: an accent-coloured bar down the
 * left edge with indented text. The block is set off by a blank line above and below,
 * emitted by ChanMarkup (the neuroslop tag is marked spaced), so the separation is real
 * layout space rather than anything painted over the neighbouring post text.
 */
class NeuroslopSpan :
    LineBackgroundSpan,
    LeadingMarginSpan,
    ColorScheme.Span {
    private var barColor = 0

    override fun applyColorScheme(colorScheme: ColorScheme?) {
        if (colorScheme != null) {
            barColor = colorScheme.accentColor
        }
    }

    override fun getLeadingMargin(first: Boolean): Int = LEADING_MARGIN

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        // The bar is drawn in drawBackground, which is called for every line of the block.
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
        paint.color = barColor
        canvas.drawRect(left.toFloat(), top.toFloat(), left + BAR_WIDTH, bottom.toFloat(), paint)
        paint.color = paintColor
        paint.style = paintStyle
    }

    companion object {
        private val DENSITY = Resources.getSystem().displayMetrics.density
        private val BAR_WIDTH = 3f * DENSITY
        private val LEADING_MARGIN = (16f * DENSITY).toInt()
    }
}
