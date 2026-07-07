package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.mishiranu.dashchan.graphics.BaseDrawable
import com.mishiranu.dashchan.util.ResourceUtils

class CardView : FrameLayout {
	private val initialized: Boolean

	private var bgColor = 0

	constructor(context: Context) : this(context, null)

	constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
		val density = ResourceUtils.obtainDensity(context)
		val size = 1f * density + 0.5f
		IMPLEMENTATION.initialize(this, context, bgColor, size)
		initialized = true
	}

	private val measureSpecs = IntArray(2)

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		measureSpecs[0] = widthMeasureSpec
		measureSpecs[1] = heightMeasureSpec
		IMPLEMENTATION.measure(this, measureSpecs)
		super.onMeasure(measureSpecs[0], measureSpecs[1])
	}

	private fun setBackgroundColorInternal(color: Int) {
		bgColor = color
		if (initialized) {
			IMPLEMENTATION.setBackgroundColor(this, color)
		}
	}

	@Deprecated("Deprecated in Java")
	@Suppress("DEPRECATION")
	override fun setBackgroundDrawable(background: Drawable?) {
		if (background is ColorDrawable) {
			val color = background.color
			setBackgroundColorInternal(color)
			return
		}
		super.setBackgroundDrawable(background)
	}

	override fun setBackgroundColor(color: Int) {
		setBackgroundColorInternal(color)
	}

	fun getBackgroundColor(): Int {
		return bgColor
	}

	private interface Implementation {
		fun initialize(cardView: CardView, context: Context, backgroundColor: Int, size: Float)
		fun measure(cardView: CardView, measureSpecs: IntArray)
		fun setBackgroundColor(cardView: CardView, color: Int)
	}

	private class CardViewJellyBean : Implementation {
		override fun initialize(cardView: CardView, context: Context, backgroundColor: Int, size: Float) {
			val background = RoundRectDrawableWithShadow(context.resources, backgroundColor, size)
			cardView.background = background
			val shadowPadding = Rect()
			background.getMaxShadowAndCornerPadding(shadowPadding)
			cardView.minimumHeight = Math.ceil(background.getMinHeight().toDouble()).toInt()
			cardView.minimumWidth = Math.ceil(background.getMinWidth().toDouble()).toInt()
			cardView.setPadding(shadowPadding.left, shadowPadding.top, shadowPadding.right, shadowPadding.bottom)
		}

		override fun measure(cardView: CardView, measureSpecs: IntArray) {
			val background = cardView.background as RoundRectDrawableWithShadow
			val widthMode = View.MeasureSpec.getMode(measureSpecs[0])
			when (widthMode) {
				View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> {
					val minWidth = Math.ceil(background.getMinWidth().toDouble()).toInt()
					measureSpecs[0] = View.MeasureSpec.makeMeasureSpec(Math.max(minWidth,
							View.MeasureSpec.getSize(measureSpecs[0])), widthMode)
				}
			}
			val heightMode = View.MeasureSpec.getMode(measureSpecs[1])
			when (heightMode) {
				View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> {
					val minHeight = Math.ceil(background.getMinHeight().toDouble()).toInt()
					measureSpecs[1] = View.MeasureSpec.makeMeasureSpec(Math.max(minHeight,
							View.MeasureSpec.getSize(measureSpecs[1])), heightMode)
				}
			}
		}

		override fun setBackgroundColor(cardView: CardView, color: Int) {
			(cardView.background as RoundRectDrawableWithShadow).setColor(color)
		}
	}

	private class CardViewLollipop : Implementation {
		override fun initialize(cardView: CardView, context: Context, backgroundColor: Int, size: Float) {
			val backgroundDrawable = RoundRectDrawable(backgroundColor, size)
			cardView.background = backgroundDrawable
			cardView.clipToOutline = true
			cardView.elevation = size
			backgroundDrawable.setPadding(size)
			val elevation = backgroundDrawable.getPadding()
			val radius = backgroundDrawable.radius
			val hPadding = Math.ceil(calculateHorizontalPadding(elevation, radius).toDouble()).toInt()
			val vPadding = Math.ceil(calculateVerticalPadding(elevation, radius).toDouble()).toInt()
			cardView.setPadding(hPadding, vPadding, hPadding, vPadding)
		}

		override fun measure(cardView: CardView, measureSpecs: IntArray) {}

		override fun setBackgroundColor(cardView: CardView, color: Int) {
			(cardView.background as RoundRectDrawable).setColor(color)
		}
	}

	private class RoundRectDrawable(backgroundColor: Int, val radius: Float) : BaseDrawable() {
		private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
		private val boundsF = RectF()
		private val boundsI = Rect()
		private var currentPadding = 0f

		init {
			paint.color = backgroundColor
		}

		fun setPadding(padding: Float) {
			if (padding == this.currentPadding) {
				return
			}
			this.currentPadding = padding
			updateBounds(null)
			invalidateSelf()
		}

		fun getPadding(): Float {
			return currentPadding
		}

		override fun draw(canvas: Canvas) {
			canvas.drawRoundRect(boundsF, radius, radius, paint)
		}

		private fun updateBounds(bounds: Rect?) {
			val bounds = bounds ?: getBounds()
			boundsF.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
			boundsI.set(bounds)
			val vInset = calculateVerticalPadding(currentPadding, radius)
			val hInset = calculateHorizontalPadding(currentPadding, radius)
			boundsI.inset(Math.ceil(hInset.toDouble()).toInt(), Math.ceil(vInset.toDouble()).toInt())
			boundsF.set(boundsI)
		}

		override fun onBoundsChange(bounds: Rect) {
			super.onBoundsChange(bounds)
			updateBounds(bounds)
		}

		override fun getOutline(outline: Outline) {
			outline.setRoundRect(boundsI, radius)
		}

		fun setColor(color: Int) {
			paint.color = color
			invalidateSelf()
		}
	}

	private class RoundRectDrawableWithShadow(resources: Resources, backgroundColor: Int, size: Float) : BaseDrawable() {
		private val insetShadow: Int

		private val paint: Paint
		private val cornerShadowPaint: Paint
		private val edgeShadowPaint: Paint

		private val cardBounds: RectF
		private val cornerRadius: Float
		private val cornerShadowPath = Path()

		private var rawMaxShadowSize = 0f
		private var shadowSize = 0f
		private var rawShadowSize = 0f

		private var dirty = true
		private var printedShadowClipWarning = false

		init {
			val density = ResourceUtils.obtainDensity(resources)
			insetShadow = (1f * density + 0.5f).toInt()
			paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
			paint.color = backgroundColor
			cornerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
			cornerShadowPaint.style = Paint.Style.FILL
			cornerRadius = (size + .5f).toInt().toFloat()
			cardBounds = RectF()
			edgeShadowPaint = Paint(cornerShadowPaint)
			edgeShadowPaint.isAntiAlias = false
			setShadowSize(size, size)
		}

		private fun toEven(value: Float): Int {
			val i = (value + .5f).toInt()
			return if (i % 2 == 1) {
				i - 1
			} else {
				i
			}
		}

		override fun setAlpha(alpha: Int) {
			paint.alpha = alpha
			cornerShadowPaint.alpha = alpha
			edgeShadowPaint.alpha = alpha
		}

		override fun onBoundsChange(bounds: Rect) {
			super.onBoundsChange(bounds)
			dirty = true
		}

		fun setShadowSize(shadowSize: Float, maxShadowSize: Float) {
			var shadowSize = toEven(shadowSize).toFloat()
			val maxShadowSize = toEven(maxShadowSize).toFloat()
			if (shadowSize > maxShadowSize) {
				shadowSize = maxShadowSize
				if (!printedShadowClipWarning) {
					printedShadowClipWarning = true
				}
			}
			if (rawShadowSize == shadowSize && rawMaxShadowSize == maxShadowSize) {
				return
			}
			rawShadowSize = shadowSize
			rawMaxShadowSize = maxShadowSize
			this.shadowSize = (shadowSize * SHADOW_MULTIPLIER + insetShadow + .5f).toInt().toFloat()
			dirty = true
			invalidateSelf()
		}

		override fun getPadding(padding: Rect): Boolean {
			val vOffset = Math.ceil(calculateVerticalPadding(rawMaxShadowSize, cornerRadius).toDouble()).toInt()
			val hOffset = Math.ceil(calculateHorizontalPadding(rawMaxShadowSize, cornerRadius).toDouble()).toInt()
			padding.set(hOffset, vOffset, hOffset, vOffset)
			return true
		}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			paint.colorFilter = colorFilter
			cornerShadowPaint.colorFilter = colorFilter
			edgeShadowPaint.colorFilter = colorFilter
		}

		override fun draw(canvas: Canvas) {
			if (dirty) {
				buildComponents(bounds)
				dirty = false
			}
			canvas.translate(0f, rawShadowSize / 2)
			drawShadow(canvas)
			canvas.translate(0f, -rawShadowSize / 2)
			canvas.drawRoundRect(cardBounds, cornerRadius, cornerRadius, paint)
		}

		private fun drawShadow(canvas: Canvas) {
			val edgeShadowTop = -cornerRadius - shadowSize
			val inset = cornerRadius + insetShadow + rawShadowSize / 2
			val drawHorizontalEdges = cardBounds.width() - 2 * inset > 0
			val drawVerticalEdges = cardBounds.height() - 2 * inset > 0
			var saved = canvas.save()
			canvas.translate(cardBounds.left + inset, cardBounds.top + inset)
			canvas.drawPath(cornerShadowPath, cornerShadowPaint)
			if (drawHorizontalEdges) {
				canvas.drawRect(0f, edgeShadowTop, cardBounds.width() - 2 * inset, -cornerRadius, edgeShadowPaint)
			}
			canvas.restoreToCount(saved)
			saved = canvas.save()
			canvas.translate(cardBounds.right - inset, cardBounds.bottom - inset)
			canvas.rotate(180f)
			canvas.drawPath(cornerShadowPath, cornerShadowPaint)
			if (drawHorizontalEdges) {
				canvas.drawRect(0f, edgeShadowTop, cardBounds.width() - 2 * inset, -cornerRadius + shadowSize,
						edgeShadowPaint)
			}
			canvas.restoreToCount(saved)
			saved = canvas.save()
			canvas.translate(cardBounds.left + inset, cardBounds.bottom - inset)
			canvas.rotate(270f)
			canvas.drawPath(cornerShadowPath, cornerShadowPaint)
			if (drawVerticalEdges) {
				canvas.drawRect(0f, edgeShadowTop, cardBounds.height() - 2 * inset, -cornerRadius, edgeShadowPaint)
			}
			canvas.restoreToCount(saved)
			saved = canvas.save()
			canvas.translate(cardBounds.right - inset, cardBounds.top + inset)
			canvas.rotate(90f)
			canvas.drawPath(cornerShadowPath, cornerShadowPaint)
			if (drawVerticalEdges) {
				canvas.drawRect(0f, edgeShadowTop, cardBounds.height() - 2 * inset, -cornerRadius, edgeShadowPaint)
			}
			canvas.restoreToCount(saved)
		}

		private fun buildShadowCorners() {
			val shadowStartColor = 0x37000000
			val shadowEndColor = 0x03000000
			val innerBounds = RectF(-cornerRadius, -cornerRadius, cornerRadius, cornerRadius)
			val outerBounds = RectF(innerBounds)
			outerBounds.inset(-shadowSize, -shadowSize)
			cornerShadowPath.reset()
			cornerShadowPath.fillType = Path.FillType.EVEN_ODD
			cornerShadowPath.moveTo(-cornerRadius, 0f)
			cornerShadowPath.rLineTo(-shadowSize, 0f)
			cornerShadowPath.arcTo(outerBounds, 180f, 90f, false)
			cornerShadowPath.arcTo(innerBounds, 270f, -90f, false)
			cornerShadowPath.close()
			val startRatio = cornerRadius / (cornerRadius + shadowSize)
			cornerShadowPaint.shader = RadialGradient(0f, 0f, cornerRadius + shadowSize,
					intArrayOf(shadowStartColor, shadowStartColor, shadowEndColor),
					floatArrayOf(0f, startRatio, 1f), Shader.TileMode.CLAMP)
			edgeShadowPaint.shader = LinearGradient(0f, -cornerRadius + shadowSize, 0f,
					-cornerRadius - shadowSize, intArrayOf(shadowStartColor, shadowStartColor, shadowEndColor),
					floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
			edgeShadowPaint.isAntiAlias = false
		}

		private fun buildComponents(bounds: Rect) {
			val verticalOffset = rawMaxShadowSize * SHADOW_MULTIPLIER
			cardBounds.set(bounds.left + rawMaxShadowSize, bounds.top + verticalOffset,
					bounds.right - rawMaxShadowSize, bounds.bottom - verticalOffset)
			buildShadowCorners()
		}

		fun getMaxShadowAndCornerPadding(into: Rect) {
			getPadding(into)
		}

		fun getMinWidth(): Float {
			val content = 2 * Math.max(rawMaxShadowSize, cornerRadius + insetShadow + rawMaxShadowSize / 2)
			return content + (rawMaxShadowSize + insetShadow) * 2
		}

		fun getMinHeight(): Float {
			val content = 2 * Math.max(rawMaxShadowSize, cornerRadius + insetShadow +
					rawMaxShadowSize * SHADOW_MULTIPLIER / 2)
			return content + (rawMaxShadowSize * SHADOW_MULTIPLIER + insetShadow) * 2
		}

		fun setColor(color: Int) {
			paint.color = color
			invalidateSelf()
		}
	}

	companion object {
		private val IMPLEMENTATION: Implementation = CardViewLollipop()

		private val COS_45 = Math.cos(Math.toRadians(45.0))
		private const val SHADOW_MULTIPLIER = 1.5f

		fun calculateVerticalPadding(maxShadowSize: Float, cornerRadius: Float): Float {
			return (maxShadowSize * SHADOW_MULTIPLIER + (1 - COS_45) * cornerRadius).toFloat()
		}

		fun calculateHorizontalPadding(maxShadowSize: Float, cornerRadius: Float): Float {
			return (maxShadowSize + (1 - COS_45) * cornerRadius).toFloat()
		}
	}
}
