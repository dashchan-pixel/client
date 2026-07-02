package com.mishiranu.dashchan.text.style

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import com.mishiranu.dashchan.content.AdvancedPreferences

class TabulationSpan : ReplacementSpan() {
	override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
			fm: Paint.FontMetricsInt?): Int {
		var from = 0
		for (i in start - 1 downTo 0) {
			if (text[i] == '\n') {
				from = i + 1
				break
			}
		}
		var length = 0
		for (i in from until start) {
			length = if (text[i] == '\t') {
				length + TAB_SIZE - length % TAB_SIZE
			} else {
				length + 1
			}
		}
		val count = TAB_SIZE - length % TAB_SIZE
		return paint.measureText("        ", 0, count).toInt()
	}

	override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
			x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}

	companion object {
		private val TAB_SIZE = AdvancedPreferences.getTabSize().let { if (it < 1 || it > 8) 8 else it }
	}
}
