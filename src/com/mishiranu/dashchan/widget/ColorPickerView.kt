package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.mishiranu.dashchan.graphics.ColorSwatchDrawable
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.roundToInt

/**
 * A saturation/value square with a hue bar and an alpha bar below it — the picker behind every
 * colour slot of the theme editor.
 *
 * The colour is held as HSV plus alpha rather than as a packed int: an ARGB round trip through
 * [Color.colorToHSV] loses the hue of any fully desaturated colour (black, white, grey), which
 * would make the hue bar jump back to red as soon as the user dragged the square to an edge.
 */
class ColorPickerView(
    context: Context,
) : View(context) {
    fun interface OnColorChangedListener {
        fun onColorChanged(color: Int)
    }

    private enum class Target { SQUARE, HUE, ALPHA }

    private val density = ResourceUtils.obtainDensity(context)
    private val barHeight = 28f * density
    private val gap = 16f * density
    private val squareHeight = 180f * density
    private val squareRadius = 8f * density
    private val thumbRadius = 9f * density
    private val thumbStroke = 2f * density

    private val hsv = floatArrayOf(0f, 0f, 1f)
    private var colorAlpha = 0xff

    /**
     * The colour as it was handed in, kept until the user touches the view: an ARGB → HSV → ARGB
     * round trip is not exact, so without this, opening a picker and confirming it unchanged could
     * shift a theme colour by a bit or two per channel.
     */
    private var exactColor: Int? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val squareRect = RectF()
    private val hueRect = RectF()
    private val alphaRect = RectF()

    private var squareShader: Shader? = null
    private var hueShader: Shader? = null
    private var alphaShader: Shader? = null
    private var checkerShader: Shader? = null

    private var target: Target? = null

    var onColorChangedListener: OnColorChangedListener? = null

    var color: Int
        get() = exactColor ?: Color.HSVToColor(colorAlpha, hsv)
        set(value) {
            val newHsv = FloatArray(3)
            Color.colorToHSV(value, newHsv)
            // A grey has no hue of its own; keep the one the user was working with.
            hsv[0] = if (newHsv[1] > 0f) newHsv[0] else hsv[0]
            hsv[1] = newHsv[1]
            hsv[2] = newHsv[2]
            colorAlpha = Color.alpha(value)
            exactColor = value
            squareShader = null
            alphaShader = null
            invalidate()
        }

    private fun pureHue(): Int = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = squareHeight + 2 * (gap + barHeight) + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(height.roundToInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        // The thumbs are drawn centred on the value they mark, so both ends of every bar and both
        // edges of the square are inset by the thumb radius to keep them inside the view.
        val left = paddingLeft + thumbRadius
        val right = w - paddingRight - thumbRadius
        var top = paddingTop.toFloat()
        squareRect.set(left, top, right, top + squareHeight)
        top = squareRect.bottom + gap
        hueRect.set(left, top, right, top + barHeight)
        top = hueRect.bottom + gap
        alphaRect.set(left, top, right, top + barHeight)
        squareShader = null
        hueShader =
            LinearGradient(
                hueRect.left,
                hueRect.top,
                hueRect.right,
                hueRect.top,
                HUE_COLORS,
                null,
                Shader.TileMode.CLAMP,
            )
        alphaShader = null
        checkerShader = ColorSwatchDrawable.createCheckerShader(maxOf(2, (barHeight / 4f).toInt()))
    }

    private fun ensureShaders() {
        if (squareShader == null) {
            val saturation =
                LinearGradient(
                    squareRect.left,
                    squareRect.top,
                    squareRect.right,
                    squareRect.top,
                    Color.WHITE,
                    pureHue(),
                    Shader.TileMode.CLAMP,
                )
            val value =
                LinearGradient(
                    squareRect.left,
                    squareRect.top,
                    squareRect.left,
                    squareRect.bottom,
                    Color.TRANSPARENT,
                    Color.BLACK,
                    Shader.TileMode.CLAMP,
                )
            squareShader = ComposeShader(saturation, value, PorterDuff.Mode.SRC_OVER)
        }
        if (alphaShader == null) {
            val opaque = Color.HSVToColor(0xff, hsv)
            alphaShader =
                LinearGradient(
                    alphaRect.left,
                    alphaRect.top,
                    alphaRect.right,
                    alphaRect.top,
                    opaque and 0x00ffffff,
                    opaque,
                    Shader.TileMode.CLAMP,
                )
        }
    }

    override fun onDraw(canvas: Canvas) {
        ensureShaders()
        val paint = this.paint
        paint.style = Paint.Style.FILL
        paint.shader = squareShader
        canvas.drawRoundRect(squareRect, squareRadius, squareRadius, paint)
        paint.shader = hueShader
        canvas.drawRoundRect(hueRect, barHeight / 2f, barHeight / 2f, paint)
        paint.shader = checkerShader
        canvas.drawRoundRect(alphaRect, barHeight / 2f, barHeight / 2f, paint)
        paint.shader = alphaShader
        canvas.drawRoundRect(alphaRect, barHeight / 2f, barHeight / 2f, paint)
        paint.shader = null
        drawThumbs(canvas)
    }

    private fun drawThumbs(canvas: Canvas) {
        val opaque = Color.HSVToColor(0xff, hsv)
        drawThumb(
            canvas,
            squareRect.left + hsv[1] * squareRect.width(),
            squareRect.top + (1f - hsv[2]) * squareRect.height(),
            opaque,
        )
        drawThumb(canvas, hueRect.left + hsv[0] / 360f * hueRect.width(), hueRect.centerY(), pureHue())
        drawThumb(
            canvas,
            alphaRect.left + colorAlpha / 255f * alphaRect.width(),
            alphaRect.centerY(),
            Color.HSVToColor(colorAlpha, hsv),
        )
    }

    private fun drawThumb(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        color: Int,
    ) {
        val paint = this.paint
        if (Color.alpha(color) < 0xff) {
            paint.shader = checkerShader
            canvas.drawCircle(cx, cy, thumbRadius, paint)
            paint.shader = null
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thumbStroke
        // Two rings: white reads against a dark colour, the black hairline against a light one.
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, thumbRadius - thumbStroke / 2f, paint)
        paint.strokeWidth = thumbStroke / 2f
        paint.color = 0x60000000
        canvas.drawCircle(cx, cy, thumbRadius + thumbStroke / 4f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = hitTest(event.y) ?: return false
                this.target = target
                // Inside a dialog's ScrollView a vertical drag on the square would otherwise be
                // stolen by the scroller halfway through.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> if (target == null) return false

            MotionEvent.ACTION_UP -> {
                if (target == null) {
                    return false
                }
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                target = null
                return false
            }

            else -> return false
        }
        updateFromTouch(event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            target = null
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(y: Float): Target? =
        when {
            y < squareRect.bottom + gap / 2f -> Target.SQUARE
            y < hueRect.bottom + gap / 2f -> Target.HUE
            else -> Target.ALPHA
        }

    private fun updateFromTouch(
        x: Float,
        y: Float,
    ) {
        exactColor = null
        when (target) {
            Target.SQUARE -> {
                hsv[1] = fraction(x, squareRect.left, squareRect.right)
                hsv[2] = 1f - fraction(y, squareRect.top, squareRect.bottom)
                alphaShader = null
            }

            Target.HUE -> {
                hsv[0] = 360f * fraction(x, hueRect.left, hueRect.right)
                squareShader = null
                alphaShader = null
            }

            Target.ALPHA -> colorAlpha = (255f * fraction(x, alphaRect.left, alphaRect.right)).roundToInt()

            null -> return
        }
        invalidate()
        onColorChangedListener?.onColorChanged(color)
    }

    private fun fraction(
        value: Float,
        from: Float,
        to: Float,
    ): Float = ((value - from) / (to - from)).coerceIn(0f, 1f)

    companion object {
        private val HUE_COLORS =
            intArrayOf(
                0xffff0000.toInt(),
                0xffffff00.toInt(),
                0xff00ff00.toInt(),
                0xff00ffff.toInt(),
                0xff0000ff.toInt(),
                0xffff00ff.toInt(),
                0xffff0000.toInt(),
            )
    }
}
