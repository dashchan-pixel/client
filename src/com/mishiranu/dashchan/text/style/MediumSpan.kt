package com.mishiranu.dashchan.text.style

import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import com.mishiranu.dashchan.util.ResourceUtils

class MediumSpan : MetricAffectingSpan() {
	override fun updateDrawState(paint: TextPaint) {
		paint.typeface = ResourceUtils.TYPEFACE_MEDIUM
	}

	override fun updateMeasureState(paint: TextPaint) {
		updateDrawState(paint)
	}
}
