package com.mishiranu.dashchan.text.style

import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import com.mishiranu.dashchan.graphics.ColorScheme

class NameColorSpan(
    private val type: Int,
) : CharacterStyle(),
    UpdateAppearance,
    ColorScheme.Span {
    private var foregroundColor = 0

    override fun applyColorScheme(colorScheme: ColorScheme?) {
        if (colorScheme != null) {
            when (type) {
                TYPE_TRIPCODE -> foregroundColor = colorScheme.tripcodeColor
                TYPE_CAPCODE -> foregroundColor = colorScheme.capcodeColor
            }
        }
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.color = foregroundColor
    }

    companion object {
        const val TYPE_TRIPCODE = 1
        const val TYPE_CAPCODE = 2
    }
}
