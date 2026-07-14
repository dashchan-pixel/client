package com.mishiranu.dashchan.graphics

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable

class ChanIconDrawable(
    private val drawable: Drawable,
) : BaseDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1 }

    private var colorFilter: PorterDuffColorFilter? = null
    private var tint: ColorStateList? = null
    private var tintMode: PorterDuff.Mode? = null

    fun newInstance(): ChanIconDrawable = ChanIconDrawable(drawable)

    override fun setBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.setBounds(left, top, right, bottom)
        drawable.setBounds(left, top, right, bottom)
    }

    override fun setTintList(tint: ColorStateList?) {
        super.setTintList(tint)

        colorFilter = null
        this.tint = tint
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        super.setTintMode(tintMode)

        colorFilter = null
        this.tintMode = tintMode
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = drawable.intrinsicWidth

    override fun getIntrinsicHeight(): Int = drawable.intrinsicHeight

    override fun getMinimumWidth(): Int = drawable.minimumWidth

    override fun getMinimumHeight(): Int = drawable.minimumHeight

    override fun draw(canvas: Canvas) {
        val tint = this.tint
        if (colorFilter == null && tint != null) {
            colorFilter =
                PorterDuffColorFilter(
                    tint.getColorForState(state, tint.defaultColor),
                    tintMode ?: PorterDuff.Mode.SRC_IN,
                )
        }
        val colorFilter = this.colorFilter
        if (colorFilter == null) {
            drawable.draw(canvas)
        } else {
            val bounds = bounds
            paint.colorFilter = colorFilter
            canvas.saveLayer(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                paint,
            )
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = drawable.opacity
}
