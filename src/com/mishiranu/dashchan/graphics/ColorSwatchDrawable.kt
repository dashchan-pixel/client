package com.mishiranu.dashchan.graphics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import com.mishiranu.dashchan.util.GraphicsUtils

/**
 * A circular swatch of one ARGB colour, used by the theme editor to show what a colour slot
 * currently resolves to. Drawn over a grey checkerboard so a translucent colour reads as
 * translucent, and ringed with a hairline outline so a swatch that matches the surface behind it
 * is still visible.
 */
class ColorSwatchDrawable(
    private val color: Int,
) : BaseDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var checkerShader: Shader? = null
    private var checkerCell = 0

    private fun radius(): Float = minOf(bounds.width(), bounds.height()) / 2f

    override fun draw(canvas: Canvas) {
        val radius = radius()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val paint = this.paint
        if (Color.alpha(color) < 0xff) {
            val cell = maxOf(2, (radius / 3f).toInt())
            if (checkerShader == null || checkerCell != cell) {
                checkerCell = cell
                checkerShader = createCheckerShader(cell)
            }
            paint.style = Paint.Style.FILL
            paint.shader = checkerShader
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius / 12f)
        paint.color = if (GraphicsUtils.isLight(color)) OUTLINE_DARK else OUTLINE_LIGHT
        canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
    }

    override fun getOutline(outline: Outline) {
        val radius = (radius() * 0.95f).toInt()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        outline.setOval(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    companion object {
        private const val OUTLINE_LIGHT = 0x40ffffff
        private const val OUTLINE_DARK = 0x40000000

        private const val CHECKER_LIGHT = 0xffcccccc.toInt()
        private const val CHECKER_DARK = 0xff999999.toInt()

        /** A two-tone tile of [cell]-sized squares, repeated to fill whatever it is drawn into. */
        fun createCheckerShader(cell: Int): Shader {
            val size = 2 * cell
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(CHECKER_LIGHT)
            val paint = Paint()
            paint.color = CHECKER_DARK
            val cellFloat = cell.toFloat()
            val sizeFloat = size.toFloat()
            canvas.drawRect(0f, 0f, cellFloat, cellFloat, paint)
            canvas.drawRect(cellFloat, cellFloat, sizeFloat, sizeFloat, paint)
            return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}
