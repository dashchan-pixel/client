package com.mishiranu.dashchan.text.style

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.widget.TextView

class OverlineSpan {
    companion object {
        private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG)

        @JvmStatic
        fun draw(
            textView: TextView,
            canvas: Canvas,
        ) {
            val layout = textView.layout ?: return
            val text = textView.text as? Spanned ?: return
            val spans = text.getSpans(0, text.length, OverlineSpan::class.java)
            if (spans != null && spans.isNotEmpty()) {
                val paddingTop = textView.totalPaddingTop
                val paddingLeft = textView.paddingLeft
                val shift = (textView.textSize * 8f / 9f).toInt()
                val thickness = textView.textSize / 15f - 0.25f
                val color = textView.currentTextColor
                PAINT.color = color
                PAINT.strokeWidth = thickness
                for (span in spans) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    val lineStart = layout.getLineForOffset(start)
                    val lineEnd = layout.getLineForOffset(end)
                    for (i in lineStart..lineEnd) {
                        val left =
                            if (i == lineStart) {
                                layout.getPrimaryHorizontal(start)
                            } else {
                                layout.getLineLeft(i)
                            }
                        val right =
                            if (i == lineEnd) {
                                layout.getPrimaryHorizontal(end)
                            } else {
                                layout.getLineRight(i)
                            }
                        val top = layout.getLineBaseline(i) - shift + 0.5f
                        canvas.drawLine(
                            paddingLeft + left,
                            paddingTop + top,
                            paddingLeft + right,
                            paddingTop + top,
                            PAINT,
                        )
                    }
                }
            }
        }
    }
}
