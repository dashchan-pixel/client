package com.mishiranu.dashchan.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF

class ThemeChoiceDrawable(
    private val background: Int,
    private val primary: Int,
    private val accent: Int,
) : BaseDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val radius = minOf(bounds.width(), bounds.height()) / 2
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val paint = this.paint
        if (accent != primary && accent != Color.TRANSPARENT) {
            val rectF = this.rectF
            rectF.set(
                (cx - radius).toFloat(),
                (cy - radius).toFloat(),
                (cx + radius).toFloat(),
                (cy + radius).toFloat(),
            )
            paint.color = primary
            canvas.drawArc(rectF, 135f, 180f, true, paint)
            paint.color = accent
            canvas.drawArc(rectF, -45f, 180f, true, paint)
        } else {
            paint.color = primary
            canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius * 1f, paint)
        }
        paint.color = background
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius * 0.5f, paint)
    }

    override fun getOutline(outline: Outline) {
        val bounds = bounds
        val radius = (minOf(bounds.width(), bounds.height()) / 2 * 0.95f).toInt()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        outline.setOval(cx - radius, cy - radius, cx + radius, cy + radius)
    }
}
