package com.mishiranu.dashchan.text.style

import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import com.mishiranu.dashchan.graphics.ColorScheme

class UnderlyingSpoilerSpan :
    CharacterStyle(),
    UpdateAppearance,
    ColorScheme.Span {
    private var backgroundColor = 0

    override fun applyColorScheme(colorScheme: ColorScheme?) {
        if (colorScheme != null) {
            backgroundColor = colorScheme.spoilerBackgroundColor
        }
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.bgColor = backgroundColor
    }
}
