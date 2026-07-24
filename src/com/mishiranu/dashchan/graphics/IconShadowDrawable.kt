package com.mishiranu.dashchan.graphics

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Wraps an icon [Drawable] and paints a soft dark halo behind it, so white overlay glyphs stay
 * legible over light content. The gallery/flow video controls sit directly on top of arbitrary
 * frames; a white icon on a white frame is otherwise invisible. This is the CSS
 * `box-shadow`/`text-shadow` analogue for an [android.widget.ImageView]'s drawable, which — unlike
 * a [android.widget.TextView] — has no shadow layer of its own.
 */
class IconShadowDrawable(
    private val icon: Drawable,
    private val blurRadius: Float,
    private val shadowColor: Int,
) : Drawable() {
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurPaint =
        Paint().apply {
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
    private val shadowOffset = IntArray(2)
    private var shadowBitmap: Bitmap? = null
    private var drawableAlpha = 0xff

    override fun onBoundsChange(bounds: Rect) {
        icon.bounds = bounds
        shadowBitmap?.recycle()
        shadowBitmap =
            if (bounds.isEmpty) {
                null
            } else {
                // Rasterise the icon's silhouette, then let extractAlpha bake the blur into an
                // (expanded) alpha bitmap — the halo is drawn once here, not on every frame.
                val mask = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ALPHA_8)
                icon.setBounds(0, 0, bounds.width(), bounds.height())
                icon.draw(Canvas(mask))
                icon.bounds = bounds
                val blurred = mask.extractAlpha(blurPaint, shadowOffset)
                mask.recycle()
                blurred
            }
    }

    override fun draw(canvas: Canvas) {
        val bitmap = shadowBitmap
        if (bitmap != null) {
            shadowPaint.color = shadowColor
            shadowPaint.alpha = Color.alpha(shadowColor) * drawableAlpha / 0xff
            canvas.drawBitmap(
                bitmap,
                (bounds.left + shadowOffset[0]).toFloat(),
                (bounds.top + shadowOffset[1]).toFloat(),
                shadowPaint,
            )
        }
        icon.draw(canvas)
    }

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
