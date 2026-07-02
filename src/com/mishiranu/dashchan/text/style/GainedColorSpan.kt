package com.mishiranu.dashchan.text.style

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.util.GraphicsUtils

class GainedColorSpan(val foregroundColor: Int) : CharacterStyle(), UpdateAppearance, ColorScheme.Span {
	private var colorGainFactor = 0f

	override fun applyColorScheme(colorScheme: ColorScheme?) {
		if (colorScheme != null) {
			colorGainFactor = colorScheme.colorGainFactor
		}
	}

	override fun updateDrawState(paint: TextPaint) {
		if (paint.color != Color.TRANSPARENT) {
			paint.color = GraphicsUtils.modifyColorGain(foregroundColor, colorGainFactor)
		}
	}
}
