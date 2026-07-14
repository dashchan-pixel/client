package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.ResourceUtils

class CircularProgressBar(
    context: Context,
    attrs: AttributeSet?,
) : View(context, attrs) {
    private enum class Transient { NONE, INDETERMINATE_PROGRESS, PROGRESS_INDETERMINATE }

    private val paint: Paint
    private val path = Path()
    private val rectF = RectF()

    private val indeterminateDrawable: Drawable?
    private val indeterminateDuration: Int

    private val lollipopStartInterpolator: Interpolator
    private val lollipopEndInterpolator: Interpolator

    private val startTime = SystemClock.elapsedRealtime()

    private var transientState = Transient.NONE
    private val circularData = FloatArray(2)
    private val transientData = FloatArray(2)
    private var timeTransientStart: Long = 0

    private var indeterminate = true
    private var visible = false
    private var timeVisibilitySet: Long = 0

    private var progress = 0f
    private var transientProgress = 0f
    private var timeProgressChange: Long = 0

    constructor(context: Context) : this(context, null)

    init {
        paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.SQUARE
        paint.strokeJoin = Paint.Join.MITER
        val startPath = Path()
        startPath.lineTo(0.5f, 0f)
        startPath.cubicTo(0.7f, 0f, 0.6f, 1f, 1f, 1f)
        lollipopStartInterpolator = PathInterpolator(startPath)
        val endPath = Path()
        endPath.cubicTo(0.2f, 0f, 0.1f, 1f, 0.5f, 1f)
        endPath.lineTo(1f, 1f)
        lollipopEndInterpolator = PathInterpolator(endPath)
        indeterminateDrawable = null
        indeterminateDuration = 0
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val density = ResourceUtils.obtainDensity(this)
        val size = (72f * density + 0.5f).toInt()
        val width = size + paddingLeft + paddingRight
        val height = size + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSizeAndState(width, widthMeasureSpec, 0),
            resolveSizeAndState(height, heightMeasureSpec, 0),
        )
    }

    private fun getValue(
        currentTime: Long,
        fromTime: Long,
        max: Float,
    ): Float = ((currentTime - fromTime) % max) / max

    private var queuedVisible = false

    private val setVisibleRunnable =
        Runnable {
            visible = queuedVisible
            timeVisibilitySet = SystemClock.elapsedRealtime()
            invalidate()
        }

    fun setVisible(
        visible: Boolean,
        forced: Boolean,
    ) {
        removeCallbacks(setVisibleRunnable)
        if (this.visible != visible) {
            queuedVisible = visible
            val delta = SystemClock.elapsedRealtime() - timeVisibilitySet
            if (delta < 10) {
                this.visible = visible
                timeVisibilitySet = 0L
                invalidate()
            } else if (!visible && !forced && delta < VISIBILITY_TRANSIENT_TIME) {
                postDelayed(setVisibleRunnable, VISIBILITY_TRANSIENT_TIME - delta)
            } else {
                setVisibleRunnable.run()
            }
        }
    }

    fun cancelVisibilityTransient() {
        removeCallbacks(setVisibleRunnable)
        timeVisibilitySet = 0L
        visible = queuedVisible
        invalidate()
    }

    private fun calculateLollipopProgress(): Boolean {
        val progress = calculateTransientProgress()
        val arcStart = (progress - 1f) / 4f
        val arcLength = progress - arcStart
        circularData[0] = arcStart
        circularData[1] = arcLength
        return progress != this.progress
    }

    private fun calculateLollipopIndeterminate(currentTime: Long) {
        val rotationValue = getValue(currentTime, startTime, INDETERMINATE_LOLLIPOP_TIME.toFloat())
        val animationValue = rotationValue * 5f % 1f
        val trimOffset = 0.25f * animationValue
        val trimStart = 0.75f * lollipopStartInterpolator.getInterpolation(animationValue) + trimOffset
        val trimEnd = 0.75f * lollipopEndInterpolator.getInterpolation(animationValue) + trimOffset
        val rotation = 2f * rotationValue
        var arcStart = trimStart
        val arcLength = trimEnd - arcStart
        arcStart += rotation
        circularData[0] = arcStart % 1f
        circularData[1] = arcLength
    }

    private fun calculateLollipopTransient(
        arcStart: Float,
        arcLength: Float,
        desiredStart: Float,
        desiredLength: Float,
        interval: Long,
    ): Boolean {
        var currentArcStart = arcStart
        var currentArcLength = arcLength
        var finished = false
        var timeDelta = SystemClock.elapsedRealtime() - timeTransientStart
        if (timeDelta >= interval) {
            timeDelta = interval
            finished = true
        }
        val value = AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR.getInterpolation(timeDelta.toFloat() / interval)
        currentArcStart += (desiredStart - currentArcStart) * value
        currentArcLength += (desiredLength - currentArcLength) * value
        circularData[0] = currentArcStart % 1f
        circularData[1] = currentArcLength
        return finished
    }

    private fun calculateLollipopTransientIndeterminateProgress(): Boolean {
        var arcStart = transientData[0]
        val arcLength = transientData[1]
        val desiredStart = 0.75f
        val desiredLength = 0.25f
        if (arcStart >= desiredStart - 0.15f || arcLength >= desiredLength) {
            arcStart -= 1f
        }
        val interval = (800f * (desiredStart - arcStart)).toInt()
        return calculateLollipopTransient(arcStart, arcLength, desiredStart, desiredLength, interval.toLong())
    }

    private fun calculateLollipopTransientProgressIndeterminate(): Boolean {
        var arcStart = transientData[0]
        val arcLength = transientData[1]
        calculateLollipopIndeterminate(timeTransientStart + 1000L)
        val desiredStart = circularData[0]
        val desiredLength = circularData[1]
        if (arcStart >= desiredStart || arcLength >= desiredLength) {
            arcStart -= 1f
        }
        return calculateLollipopTransient(arcStart, arcLength, desiredStart, desiredLength, 1000L)
    }

    fun setIndeterminate(indeterminate: Boolean) {
        if (this.indeterminate != indeterminate) {
            val time = SystemClock.elapsedRealtime()
            val visible = this.visible && time - timeVisibilitySet > 50
            if (indeterminate) {
                if (transientState == Transient.INDETERMINATE_PROGRESS) {
                    calculateLollipopTransientIndeterminateProgress()
                } else {
                    calculateLollipopProgress()
                }
                if (visible) {
                    transientState = Transient.PROGRESS_INDETERMINATE
                }
            } else {
                if (transientState == Transient.PROGRESS_INDETERMINATE) {
                    calculateLollipopTransientProgressIndeterminate()
                } else {
                    calculateLollipopIndeterminate(time)
                }
                if (visible) {
                    transientState = Transient.INDETERMINATE_PROGRESS
                }
                timeProgressChange = time
            }
            transientData[0] = circularData[0]
            transientData[1] = circularData[1]

            if (!indeterminate) {
                transientProgress = 0f
                progress = 0f
            }
            timeTransientStart = time
            invalidate()
            this.indeterminate = indeterminate
        }
    }

    private fun calculateTransientProgress(): Float {
        val time = SystemClock.elapsedRealtime() - timeProgressChange
        val end = progress
        if (time > PROGRESS_TRANSIENT_TIME) {
            return end
        }
        val start = transientProgress
        return start + (end - start) * time / PROGRESS_TRANSIENT_TIME
    }

    fun setProgress(
        progress: Int,
        max: Int,
        ignoreTransient: Boolean,
    ) {
        var value = progress.toFloat() / max
        transientProgress = if (ignoreTransient) value else calculateTransientProgress()
        timeProgressChange = SystemClock.elapsedRealtime()
        if (value < 0f) {
            value = 0f
        } else if (value > 1f) {
            value = 1f
        }
        this.progress = value
        invalidate()
    }

    private fun drawArc(
        canvas: Canvas,
        paint: Paint,
        start: Float,
        length: Float,
    ) {
        var arcLength = length
        if (arcLength < 0.001f) {
            arcLength = 0.001f
        }
        val path = this.path
        path.reset()
        if (arcLength >= 1f) {
            path.arcTo(rectF, 0f, 180f, false)
            path.arcTo(rectF, 180f, 180f, false)
        } else {
            path.arcTo(rectF, start * 360f - 90f, arcLength * 360f, false)
        }
        canvas.drawPath(path, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width
        val height = height
        val time = SystemClock.elapsedRealtime()
        val size = Math.min(width, height)
        var invalidate = false

        val transientVisibility = time - timeVisibilitySet < VISIBILITY_TRANSIENT_TIME
        var visibilityValue =
            if (transientVisibility) {
                (time - timeVisibilitySet).toFloat() /
                    VISIBILITY_TRANSIENT_TIME
            } else {
                1f
            }
        visibilityValue = AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR.getInterpolation(visibilityValue)
        val alpha = (if (visible) 0xff * visibilityValue else 0xff * (1f - visibilityValue)).toInt()
        if (transientVisibility) {
            invalidate = true
        }

        var arcStart: Float
        var arcLength: Float
        if (transientState != Transient.NONE) {
            var finished = true
            if (transientState == Transient.INDETERMINATE_PROGRESS) {
                finished = calculateLollipopTransientIndeterminateProgress()
            } else if (transientState == Transient.PROGRESS_INDETERMINATE) {
                finished = calculateLollipopTransientProgressIndeterminate()
            }
            arcStart = circularData[0]
            arcLength = circularData[1]
            if (finished) {
                transientState = Transient.NONE
                transientProgress = 0f
                timeProgressChange = time
            }
            invalidate = true
        } else if (indeterminate) {
            calculateLollipopIndeterminate(time)
            arcStart = circularData[0]
            arcLength = circularData[1]
            invalidate = true
        } else {
            invalidate = invalidate or calculateLollipopProgress()
            arcStart = circularData[0]
            arcLength = circularData[1]
        }
        var useAlpha = true
        if (visible) {
            arcStart -= 0.25f * (1f - visibilityValue)
            arcLength = arcLength * alpha / 0xff
            useAlpha = false
        } else if (indeterminate || progress < 1f || transientProgress < 0.75f) {
            // Note, that visibilityValue always changes from 0 to 1, instead of alpha
            val newArcLength = arcLength * (1f - visibilityValue)
            arcStart += arcLength - newArcLength
            arcLength = newArcLength
            if (!indeterminate) {
                arcStart += 0.25f * visibilityValue
            }
            useAlpha = false
        }
        if (alpha > 0x00) {
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            val radius = (size * 38f / 48f / 2f + 0.5f).toInt()
            rectF.set(-radius.toFloat(), -radius.toFloat(), radius.toFloat(), radius.toFloat())
            val paint = this.paint
            paint.strokeWidth = size / 48f * 4f
            paint.color = Color.argb(if (useAlpha) alpha else 0xff, 0xff, 0xff, 0xff)
            drawArc(canvas, paint, arcStart, arcLength)
            canvas.restore()
        }

        if (invalidate && (alpha > 0x00 || visible)) {
            invalidate()
        }
    }

    companion object {
        private const val INDETERMINATE_LOLLIPOP_TIME = 6665
        private const val PROGRESS_TRANSIENT_TIME = 500
        private const val VISIBILITY_TRANSIENT_TIME = 500
    }
}
