package com.mishiranu.dashchan.text.style

import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.UpdateAppearance

class ScriptSpan(val isSuperscript: Boolean) : CharacterStyle(), UpdateAppearance, ParagraphStyle {
	override fun updateDrawState(paint: TextPaint) {
		val oldSize = paint.textSize
		val newSize = oldSize * 3f / 4f
		paint.textSize = (newSize + 0.5f).toInt().toFloat()
		val shift = (oldSize - newSize).toInt()
		if (isSuperscript) {
			paint.baselineShift -= shift
		}
	}
}
