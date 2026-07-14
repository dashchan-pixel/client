package com.mishiranu.dashchan.graphics

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sqrt

class RoundedCornersDrawable(
    private val radius: Int,
) : BaseDrawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    constructor(radius: Int, color: Int) : this(radius) {
        setColor(color)
    }

    fun setColor(color: Int) {
        paint.color = color
    }

    override fun setBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val bounds = bounds
        if (bounds.left != left || bounds.top != top || bounds.right != right || bounds.bottom != bottom) {
            val path = this.path
            path.rewind()
            val radius = this.radius.toFloat()
            val shift = (sqrt(2f) - 1f) * radius * 4f / 3f
            path.moveTo(left.toFloat(), top.toFloat())
            path.rLineTo(radius, 0f)
            path.rCubicTo(-shift, 0f, -radius, radius - shift, -radius, radius)
            path.close()
            path.moveTo(right.toFloat(), top.toFloat())
            path.rLineTo(-radius, 0f)
            path.rCubicTo(shift, 0f, radius, radius - shift, radius, radius)
            path.close()
            path.moveTo(left.toFloat(), bottom.toFloat())
            path.rLineTo(radius, 0f)
            path.rCubicTo(-shift, 0f, -radius, shift - radius, -radius, -radius)
            path.close()
            path.moveTo(right.toFloat(), bottom.toFloat())
            path.rLineTo(-radius, 0f)
            path.rCubicTo(shift, 0f, radius, shift - radius, radius, -radius)
            path.close()
        }
        super.setBounds(left, top, right, bottom)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}
