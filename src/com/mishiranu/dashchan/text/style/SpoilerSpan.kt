package com.mishiranu.dashchan.text.style

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.widget.CommentTextView

class SpoilerSpan :
    CharacterStyle(),
    UpdateAppearance,
    CommentTextView.ClickableSpan,
    ColorScheme.Span {
    private var backgroundColor = 0
    private var clickedColor = 0
    private var clicked = false
    private var enabled = false

    var isVisible = false

    override fun applyColorScheme(colorScheme: ColorScheme?) {
        if (colorScheme != null) {
            backgroundColor = colorScheme.spoilerTopBackgroundColor
            clickedColor = colorScheme.clickedColor
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun updateDrawState(paint: TextPaint) {
        if (!enabled || isVisible) {
            if (enabled && clicked) {
                paint.bgColor = GraphicsUtils.mixColors(clickedColor, paint.bgColor)
            }
        } else {
            paint.bgColor =
                if (clicked) {
                    GraphicsUtils.mixColors(backgroundColor, clickedColor)
                } else {
                    backgroundColor
                }
            paint.color = Color.TRANSPARENT
        }
    }

    override fun setClicked(clicked: Boolean) {
        this.clicked = clicked
    }
}
