package com.mishiranu.dashchan.graphics

import android.animation.ValueAnimator
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.sqrt

class GalleryBackgroundDrawable(view: View, imageViewPosition: IntArray?, color: Int) : BaseDrawable() {
	private val view: View?
	private val centerX: Float
	private val centerY: Float

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val location = IntArray(2)

	private var animator: ValueAnimator? = null
	private var alpha = 0xff

	init {
		if (imageViewPosition != null) {
			this.view = view
			centerX = imageViewPosition[0] + imageViewPosition[2] / 2f
			centerY = imageViewPosition[1] + imageViewPosition[3] / 2f
		} else {
			this.view = null
			centerX = -1f
			centerY = -1f
		}
		val density = ResourceUtils.obtainDensity(view)
		val colorFrom = maxOf(Color.alpha(color) - 5, 0x00) shl 24 or (0x00ffffff and color)
		val colorTo = minOf(Color.alpha(color) + 5, 0xff) shl 24 or (0x00ffffff and color)
		val bitmap = GraphicsUtils.generateNoise(80, density.toInt(), colorFrom, colorTo)
		paint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
	}

	override fun draw(canvas: Canvas) {
		val paint = this.paint
		val bounds = bounds
		val t: Float
		if (view != null) {
			var animator = this.animator
			if (animator == null) {
				animator = ValueAnimator.ofFloat(0f, 1f)
				animator.interpolator = AnimationUtils.ACCELERATE_INTERPOLATOR
				animator.duration = 300
				animator.start()
				this.animator = animator
				view.getLocationOnScreen(location)
			}
			t = animator.animatedValue as Float
		} else {
			t = 1f
		}
		if (t >= 1f) {
			paint.alpha = alpha
			canvas.drawRect(bounds, paint)
		} else {
			val width = bounds.width()
			val height = bounds.height()
			val cx = AnimationUtils.lerp(centerX - location[0], bounds.left + width / 2f, t / 2f)
			val cy = AnimationUtils.lerp(centerY - location[1], bounds.top + height / 2f, t / 2f)
			val radius = AnimationUtils.lerp(0f, sqrt((width * width + height * height).toFloat()), t)
			paint.alpha = (0xff * t).toInt()
			canvas.drawRect(bounds, paint)
			paint.alpha = (0xff * (1f - t) / 2f).toInt()
			canvas.drawCircle(cx, cy, radius, paint)
			invalidateSelf()
		}
	}

	override fun getAlpha(): Int = alpha

	override fun setAlpha(alpha: Int) {
		if (this.alpha != alpha) {
			this.alpha = alpha
			invalidateSelf()
		}
	}
}
