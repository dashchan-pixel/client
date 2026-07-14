package com.mishiranu.dashchan.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mishiranu.dashchan.util.ResourceUtils

class SelectorBorderDrawable(
    context: Context,
) : BaseDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val density = ResourceUtils.obtainDensity(context)

    private var selected = false

    fun setSelected(selected: Boolean) {
        this.selected = selected
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (selected) {
            canvas.drawColor(0x44ffffff)
            val bounds = bounds
            val thickness = (THICKNESS_DP * density).toInt()
            canvas.drawRect(
                bounds.top.toFloat(),
                bounds.left.toFloat(),
                bounds.right.toFloat(),
                (bounds.top + thickness).toFloat(),
                paint,
            )
            canvas.drawRect(
                (bounds.bottom - thickness).toFloat(),
                bounds.left.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                paint,
            )
            canvas.drawRect(
                bounds.top.toFloat(),
                bounds.left.toFloat(),
                (bounds.left + thickness).toFloat(),
                bounds.bottom.toFloat(),
                paint,
            )
            canvas.drawRect(
                bounds.top.toFloat(),
                (bounds.right - thickness).toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                paint,
            )
        }
    }

    companion object {
        private const val THICKNESS_DP = 2
    }
}
