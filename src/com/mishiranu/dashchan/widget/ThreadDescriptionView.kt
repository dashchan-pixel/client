package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import chan.util.StringUtils
import com.mishiranu.dashchan.util.ResourceUtils
import java.util.Locale

class ThreadDescriptionView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val fontMetrics = Paint.FontMetrics()

	private val description = ArrayList<String>()
	private val shortDescription = ArrayList<String>()

	private var toEnd = false
	private var spacing = 0

	constructor(context: Context) : this(context, null)

	init {
		paint.typeface = ResourceUtils.TYPEFACE_MEDIUM
	}

	fun setTextColor(color: Int) {
		paint.color = color
		invalidate()
	}

	fun setTextSizeSp(sizeSp: Float) {
		val size = (sizeSp * resources.displayMetrics.scaledDensity + 0.5f).toInt()
		paint.textSize = size.toFloat()
		requestLayout()
	}

	fun setToEnd(toEnd: Boolean) {
		this.toEnd = toEnd
		invalidate()
	}

	fun setSpacing(spacing: Int) {
		this.spacing = spacing
		invalidate()
	}

	fun clear() {
		description.clear()
		shortDescription.clear()
		invalidate()
	}

	fun append(value: String?) {
		if (!StringUtils.isEmpty(value)) {
			description.add(value!!.uppercase(Locale.getDefault()))
			shortDescription.clear()
		}
		invalidate()
	}

	private fun prepareShortDescription() {
		if (shortDescription.isEmpty()) {
			val builder = StringBuilder()
			for (value in description) {
				builder.setLength(0)
				val words = value.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
				for (word in words) {
					val length = builder.length
					try {
						word.toInt()
						if (length > 0) {
							builder.append(' ')
						}
						builder.append(word)
					} catch (e: NumberFormatException) {
						if (word.length >= 3) {
							if (length > 0 && builder[length - 1] != '.') {
								builder.append(' ')
							}
							builder.append(word[0]).append('.')
						} else {
							if (length > 0) {
								builder.append(' ')
							}
							builder.append(word)
						}
					}
				}
				shortDescription.add(builder.toString())
			}
		}
	}

	override fun getSuggestedMinimumHeight(): Int {
		paint.getFontMetrics(fontMetrics)
		val textHeight = Math.ceil(fontMetrics.bottom.toDouble()).toInt() - Math.floor(fontMetrics.top.toDouble()).toInt()
		val minHeight = textHeight + paddingTop + paddingBottom
		return Math.max(minHeight, super.getSuggestedMinimumHeight())
	}

	private var measurements: FloatArray? = null

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)

		val paint = this.paint
		val fontMetrics = this.fontMetrics
		paint.getFontMetrics(fontMetrics)
		val maxWidth = width - paddingLeft - paddingRight
		var totalWidth = 0f
		if (measurements == null || measurements!!.size != description.size) {
			measurements = FloatArray(description.size)
		}
		val measurements = this.measurements!!
		for (i in measurements.indices) {
			val width = paint.measureText(description[i])
			measurements[i] = width
			totalWidth += width
		}
		totalWidth += (spacing * (description.size - 1)).toFloat()
		val description: ArrayList<String>
		val measure: Boolean
		if (totalWidth > maxWidth) {
			prepareShortDescription()
			description = shortDescription
			measure = true
		} else {
			description = this.description
			measure = false
		}
		val baseline = height - paddingBottom - Math.ceil(fontMetrics.bottom.toDouble()).toInt()
		var left = paddingLeft.toFloat()
		var right = (width - paddingRight).toFloat()
		val rtl = this.layoutDirection == View.LAYOUT_DIRECTION_RTL
		if (rtl != toEnd) {
			for (i in measurements.indices.reversed()) {
				val text = description[i]
				val width = if (measure) paint.measureText(text) else measurements[i]
				if (right - width < left) {
					break
				}
				canvas.drawText(description[i], right - width, baseline.toFloat(), paint)
				right -= width + spacing
			}
		} else {
			for (i in measurements.indices) {
				val text = description[i]
				val width = if (measure) paint.measureText(text) else measurements[i]
				if (left + width > right) {
					break
				}
				canvas.drawText(description[i], left, baseline.toFloat(), paint)
				left += width + spacing
			}
		}
	}
}
