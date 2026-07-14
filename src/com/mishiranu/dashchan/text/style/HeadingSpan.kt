package com.mishiranu.dashchan.text.style

import android.annotation.SuppressLint
import android.text.TextPaint
import android.text.style.TypefaceSpan

// TypefaceSpan("sans-serif-light") + RelativeSizeSpan(SCALE)
@SuppressLint("ParcelCreator")
class HeadingSpan : TypefaceSpan("sans-serif-light") {
    override fun updateDrawState(paint: TextPaint) {
        super.updateDrawState(paint)
        applyScale(paint)
    }

    override fun updateMeasureState(paint: TextPaint) {
        super.updateMeasureState(paint)
        applyScale(paint)
    }

    private fun applyScale(paint: TextPaint) {
        paint.textSize = (paint.textSize * SCALE + 0.5f).toInt().toFloat()
    }

    companion object {
        private const val SCALE = 5f / 4f
    }
}
