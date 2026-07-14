package com.mishiranu.dashchan.graphics

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils

class TransparentTileDrawable(
    context: Context,
    large: Boolean,
) : BaseDrawable() {
    private val paint = Paint()

    init {
        val density = ResourceUtils.obtainDensity(context)
        val bitmap =
            GraphicsUtils.generateNoise(
                if (large) 80 else 40,
                density.toInt(),
                COLOR_MIN shl 24 or 0x00ffffff,
                COLOR_MAX shl 24 or 0x00ffffff,
            )
        paint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    companion object {
        private const val COLOR_MIN = 0xe0
        private const val COLOR_MAX = 0xf0
    }
}
