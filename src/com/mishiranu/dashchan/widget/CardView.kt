package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
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

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
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

    fun getBackgroundColor(): Int = bgColor

    private interface Implementation {
        fun initialize(
            cardView: CardView,
            context: Context,
            backgroundColor: Int,
            size: Float,
        )

        fun measure(
            cardView: CardView,
            measureSpecs: IntArray,
        )

        fun setBackgroundColor(
            cardView: CardView,
            color: Int,
        )
    }

    private class CardViewLollipop : Implementation {
        override fun initialize(
            cardView: CardView,
            context: Context,
            backgroundColor: Int,
            size: Float,
        ) {
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

        override fun measure(
            cardView: CardView,
            measureSpecs: IntArray,
        ) {}

        override fun setBackgroundColor(
            cardView: CardView,
            color: Int,
        ) {
            (cardView.background as RoundRectDrawable).setColor(color)
        }
    }

    private class RoundRectDrawable(
        backgroundColor: Int,
        val radius: Float,
    ) : BaseDrawable() {
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

        fun getPadding(): Float = currentPadding

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(boundsF, radius, radius, paint)
        }

        private fun updateBounds(bounds: Rect?) {
            val currentBounds = bounds ?: getBounds()
            boundsF.set(
                currentBounds.left.toFloat(),
                currentBounds.top.toFloat(),
                currentBounds.right.toFloat(),
                currentBounds.bottom.toFloat(),
            )
            boundsI.set(currentBounds)
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

    companion object {
        private val IMPLEMENTATION: Implementation = CardViewLollipop()

        private val COS_45 = Math.cos(Math.toRadians(45.0))
        private const val SHADOW_MULTIPLIER = 1.5f

        fun calculateVerticalPadding(
            maxShadowSize: Float,
            cornerRadius: Float,
        ): Float = (maxShadowSize * SHADOW_MULTIPLIER + (1 - COS_45) * cornerRadius).toFloat()

        fun calculateHorizontalPadding(
            maxShadowSize: Float,
            cornerRadius: Float,
        ): Float = (maxShadowSize + (1 - COS_45) * cornerRadius).toFloat()
    }
}
