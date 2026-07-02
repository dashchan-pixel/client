package com.mishiranu.dashchan.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.SystemClock
import com.mishiranu.dashchan.util.AnimationUtils

class SelectorCheckDrawable : BaseDrawable() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.SQUARE
		strokeJoin = Paint.Join.MITER
		color = Color.WHITE
	}
	private val path = Path()
	private val pathMeasure = PathMeasure()

	private var start = 0L
	private var selected = false

	override fun draw(canvas: Canvas) {
		val dt = SystemClock.elapsedRealtime() - start
		var value = if (start == 0L) 1f else if (dt < 0) 0f else minOf(dt.toFloat() / DURATION, 1f)
		value = AnimationUtils.DECELERATE_INTERPOLATOR.getInterpolation(value)
		val bounds = bounds
		canvas.save()
		canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
		canvas.drawColor(Color.argb(((if (selected) value else 1f - value) * 0x80).toInt(), 0, 0, 0))
		val width = bounds.width()
		val height = bounds.height()
		val size: Int
		if (width > height) {
			canvas.translate((width - height) / 2f, 0f)
			size = height
		} else if (height > width) {
			canvas.translate(0f, (height - width) / 2f)
			size = width
		} else {
			size = width
		}
		val strokeSize = 0.03f
		paint.strokeWidth = strokeSize * size

		path.moveTo(0.39f * size, 0.5f * size)
		path.rLineTo(0.08f * size, 0.08f * size)
		path.rLineTo(0.14f * size, -0.14f * size)
		pathMeasure.setPath(path, false)
		path.rewind()
		var length = pathMeasure.length
		if (selected) {
			pathMeasure.getSegment(0f, value * length, path, true)
		} else {
			pathMeasure.getSegment(value * length, length, path, true)
		}
		canvas.drawPath(path, paint)
		path.rewind()

		val append = if (selected) (1f - value) * 90f else value * -90f - 180f
		paint.strokeWidth = strokeSize * size
		path.arcTo(0.3f * size, 0.3f * size, 0.7f * size, 0.7f * size, 270f + append, -180f, true)
		path.arcTo(0.3f * size, 0.3f * size, 0.7f * size, 0.7f * size, 90f + append, -180f, false)
		pathMeasure.setPath(path, false)
		path.rewind()
		length = pathMeasure.length
		if (selected) {
			pathMeasure.getSegment(0f, value * length, path, true)
		} else {
			pathMeasure.getSegment(value * length, length, path, true)
		}
		canvas.drawPath(path, paint)
		path.rewind()

		canvas.restore()
		if (value < 1f) {
			invalidateSelf()
		}
	}

	fun isSelected(): Boolean = selected

	fun setSelected(selected: Boolean, animate: Boolean) {
		if (this.selected != selected) {
			start = if (animate) SystemClock.elapsedRealtime() else 0L
			this.selected = selected
			invalidateSelf()
		}
	}

	companion object {
		private const val DURATION = 200
	}
}
