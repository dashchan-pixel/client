package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.mishiranu.dashchan.util.ResourceUtils

class PostBorderView(
    context: Context,
    attrs: AttributeSet?,
) : View(context, attrs) {
    private var borderStyle: BorderStyle? = null
    private var borderConfiguration: BorderConfiguration? = null

    fun setBorderStyle(borderStyle: BorderStyle?) {
        if (this.borderStyle != borderStyle) {
            this.borderStyle = borderStyle
            initializeBorderConfigurationForStyle()
            requestLayout()
        }
    }

    private fun initializeBorderConfigurationForStyle() {
        borderConfiguration =
            when (borderStyle) {
                null -> null
                BorderStyle.USER_POST -> UserPostBorderConfiguration(context)
                BorderStyle.REPLY -> ReplyBorderConfiguration(context)
            }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        borderConfiguration?.let {
            val borderLeft = paddingLeft
            val borderTop = paddingTop
            val borderRight = measuredWidth - paddingRight
            val borderBottom = measuredHeight - paddingBottom
            it.configure(borderLeft, borderTop, borderRight, borderBottom)
        }
    }

    override fun onDraw(canvas: Canvas) {
        borderConfiguration?.draw(canvas)
    }

    enum class BorderStyle {
        USER_POST,
        REPLY,
    }

    private interface BorderConfiguration {
        fun configure(
            borderLeft: Int,
            borderTop: Int,
            borderRight: Int,
            borderBottom: Int,
        )

        fun draw(canvas: Canvas)
    }

    private class UserPostBorderConfiguration(
        context: Context,
    ) : BorderConfiguration {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var borderTop = 0f
        private var borderBottom = 0f
        private var borderMid = 0f
        private val density: Float

        init {
            paint.color = ThemeEngine.getTheme(context).accent
            paint.strokeCap = Paint.Cap.ROUND
            density = ResourceUtils.obtainDensity(context)
        }

        override fun configure(
            borderLeft: Int,
            borderTop: Int,
            borderRight: Int,
            borderBottom: Int,
        ) {
            val userPostBorderSize = borderRight - borderLeft - density
            if (userPostBorderSize > 0) {
                paint.strokeWidth = userPostBorderSize

                val borderCapSize = userPostBorderSize / 2
                this.borderTop = borderTop + borderCapSize
                this.borderBottom = borderBottom - borderCapSize
                this.borderMid = (borderLeft + borderRight) / 2f
            }
        }

        override fun draw(canvas: Canvas) {
            canvas.drawLine(borderMid, borderTop, borderMid, borderBottom, paint)
        }
    }

    private class ReplyBorderConfiguration(
        context: Context,
    ) : BorderConfiguration {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dotRadius = 0f
        private var dotsCount = 0
        private var spaceBetweenDots = 0f
        private var dotCenterX = 0f
        private var dotCenterY = 0f

        init {
            paint.color = ThemeEngine.getTheme(context).accent
        }

        override fun configure(
            borderLeft: Int,
            borderTop: Int,
            borderRight: Int,
            borderBottom: Int,
        ) {
            val dotDiameter = borderRight - borderLeft
            if (dotDiameter > 0) {
                dotRadius = dotDiameter / 2f

                val availableHeight = borderBottom - borderTop
                dotsCount = (availableHeight / 2) / dotDiameter

                spaceBetweenDots = 0f
                if (dotsCount >= 2) {
                    val remainingHeight = availableHeight - dotsCount * dotDiameter
                    spaceBetweenDots += dotDiameter
                    // the last dot does not need a space after it, so divide remaining
                    // height between all dots except last
                    spaceBetweenDots += remainingHeight.toFloat() / (dotsCount - 1)
                }

                dotCenterX = dotRadius
                dotCenterY = borderTop + dotRadius
            }
        }

        override fun draw(canvas: Canvas) {
            for (dotNumber in 0 until dotsCount) {
                canvas.drawCircle(dotCenterX, dotCenterY + spaceBetweenDots * dotNumber, dotRadius, paint)
            }
        }
    }
}
