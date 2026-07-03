package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.TextView
import com.mishiranu.dashchan.text.style.OverlineSpan

// Allows to cut lines that don't fit to view's height.
class CutTextView(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {
	init {
		ThemeEngine.applyStyle(this)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)

		var removeHeight = measuredHeight - paddingTop - paddingBottom
		val layout = layout
		val count = layout.lineCount
		for (i in 0 until count) {
			val lineHeight = layout.getLineTop(i + 1) - layout.getLineTop(i)
			if (removeHeight >= lineHeight) {
				removeHeight -= lineHeight
			} else {
				break
			}
		}
		if (removeHeight > 0) {
			setMeasuredDimension(measuredWidth, measuredHeight - removeHeight)
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		OverlineSpan.draw(this, canvas)
	}
}
