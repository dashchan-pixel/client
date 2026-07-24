package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.service.WatcherService
import com.mishiranu.dashchan.content.service.WatcherService.Counter.State
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class WatcherView(
    context: Context,
    private val colorSet: ColorSet,
) : FrameLayout(context) {
    class ColorSet(
        @JvmField val enabledColor: Int,
        @JvmField val unavailableColor: Int,
        @JvmField val disabledColor: Int,
    )

    private val progressBar: ProgressBar

    private var text = ""
    private var hasNew = false
    private var color = 0

    init {
        setBackgroundResource(ResourceUtils.getResourceId(context, android.R.attr.selectableItemBackground, 0))
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        progressBar.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)

        addView(progressBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        update(WatcherService.Counter.INITIAL)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val rectF = RectF()
    private val rect = Rect()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        val density = ResourceUtils.obtainDensity(this)
        val paddingHorizontal = (8f * density).toInt()
        val paddingVertical = (12f * density).toInt()
        rectF.set(
            paddingHorizontal.toFloat(),
            paddingVertical.toFloat(),
            (width - paddingHorizontal).toFloat(),
            (height - paddingVertical).toFloat(),
        )

        // Follow the app-wide corner radius; drawRoundRect caps it at half the badge height, so a
        // large radius simply yields a pill.
        val cornerRadius = Preferences.uiCornerRadius * density
        paint.color = color
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        canvas.save()
        // Clip to the rounded badge shape (not a plain rect) so the tap ripple matches the badge
        // instead of showing a square shade.
        clipPath.rewind()
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        super.draw(canvas)

        // Keep the number/dot legible on bright badge colours by flipping to black.
        val onColor = if (GraphicsUtils.isLight(color)) Color.BLACK else Color.WHITE
        if (progressBar.visibility != View.VISIBLE) {
            val fontSize = 12
            paint.color = onColor
            paint.typeface = Typeface.DEFAULT_BOLD
            if (!hasNew) {
                paint.alpha = 0x99
            }
            paint.textSize =
                (
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        fontSize.toFloat(),
                        resources.displayMetrics,
                    ) + 0.5f
                ).toInt().toFloat()
            paint.textAlign = Paint.Align.CENTER
            paint.getTextBounds(text, 0, text.length, rect)
            canvas.drawText(text, width / 2f, (height + rect.height()) / 2f, paint)
        } else if (hasNew) {
            paint.color = onColor
            canvas.drawCircle(width / 2f, height / 2f, (4f * density).toInt().toFloat(), paint)
        }
        canvas.restore()
    }

    fun update(counter: WatcherService.Counter) {
        progressBar.visibility = if (counter.running) View.VISIBLE else View.GONE
        color =
            when (counter.state) {
                State.ENABLED -> colorSet.enabledColor
                State.UNAVAILABLE -> colorSet.unavailableColor
                State.DISABLED -> colorSet.disabledColor
                else -> throw IllegalStateException()
            }
        val text: String
        if (counter.newCount <= 0 && counter.deleted) {
            text = "X"
        } else {
            var value =
                if (abs(counter.newCount) >= 1000) {
                    (counter.newCount / 1000).toString() + "K+"
                } else {
                    counter.newCount.toString()
                }
            if (counter.deleted) {
                value += "X"
            } else if (counter.error) {
                value += "?"
            }
            text = value
        }
        this.text = text
        this.hasNew = counter.newCount > 0
        invalidate()
    }
}
