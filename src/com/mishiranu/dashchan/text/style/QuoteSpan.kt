package com.mishiranu.dashchan.text.style

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import com.mishiranu.dashchan.graphics.ColorScheme

class QuoteSpan : CharacterStyle(), UpdateAppearance, ColorScheme.Span {
	private var foregroundColor = 0

	override fun applyColorScheme(colorScheme: ColorScheme?) {
		if (colorScheme != null) {
			foregroundColor = colorScheme.quoteColor
		}
	}

	override fun updateDrawState(paint: TextPaint) {
		if (paint.color != Color.TRANSPARENT) {
			paint.color = foregroundColor
		}
	}
}
