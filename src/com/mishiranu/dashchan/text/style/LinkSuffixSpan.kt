package com.mishiranu.dashchan.text.style

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.util.FlagUtils
import com.mishiranu.dashchan.util.ResourceUtils

class LinkSuffixSpan(private var suffix: Int, val postNumber: PostNumber?) :
		ReplacementSpan(), ColorScheme.Span {
	private var foregroundColor = 0

	fun isSuffixPresent(suffix: Int): Boolean = FlagUtils.get(this.suffix, suffix)

	fun setSuffix(suffix: Int, present: Boolean) {
		this.suffix = FlagUtils.set(this.suffix, suffix, present)
	}

	private val suffixText: String?
		get() = when {
			isSuffixPresent(SUFFIX_ORIGINAL_POSTER) -> "OP"
			isSuffixPresent(SUFFIX_DIFFERENT_THREAD) -> "DT"
			isSuffixPresent(SUFFIX_USER_POST) -> "Y"
			else -> null
		}

	override fun applyColorScheme(colorScheme: ColorScheme?) {
		if (colorScheme != null) {
			foregroundColor = colorScheme.linkColor
		}
	}

	override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
			fm: Paint.FontMetricsInt?): Int {
		val suffixText = suffixText ?: return 0
		val after = if (end >= text.length) '\n' else text[end]
		val addSpace = after > ' ' && after != '.' && after != ',' && after != '!' &&
				after != '?' && after != ')' && after != ']' && after != ':' && after != ';'
		paint.typeface = ResourceUtils.TYPEFACE_MEDIUM
		return paint.measureText(" " + suffixText + if (addSpace) " " else "").toInt()
	}

	override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
			x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
		val suffixText = suffixText
		if (suffixText != null) {
			paint.typeface = ResourceUtils.TYPEFACE_MEDIUM
			paint.color = foregroundColor
			canvas.drawText(" $suffixText", x, y.toFloat(), paint)
		}
	}

	companion object {
		const val SUFFIX_ORIGINAL_POSTER = 0x00000001
		const val SUFFIX_DIFFERENT_THREAD = 0x00000002
		const val SUFFIX_USER_POST = 0x00000004
	}
}
