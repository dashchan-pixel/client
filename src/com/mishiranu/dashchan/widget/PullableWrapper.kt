package com.mishiranu.dashchan.widget

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.isTablet
import com.mishiranu.dashchan.widget.EdgeEffectHandler.Shift
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

class PullableWrapper(
    private val listView: Wrapped,
) {
    private val topView: PullView
    private val bottomView: PullView

    private val pullDeltaGain: Float

    enum class Side {
        NONE,
        BOTH,
        TOP,
        BOTTOM,
    }

    fun setColor(color: Int) {
        topView.setColor(color)
        bottomView.setColor(color)
    }

    interface PullCallback {
        fun onListPulled(
            wrapper: PullableWrapper,
            side: Side,
        )
    }

    fun interface PullStateListener {
        fun onPullStateChanged(
            wrapper: PullableWrapper?,
            busy: Boolean,
        )
    }

    private lateinit var pullCallback: PullCallback
    private var pullStateListener: PullStateListener? = null

    fun setOnPullListener(callback: PullCallback) {
        this.pullCallback = callback
    }

    fun setPullStateListener(listener: PullStateListener?) {
        this.pullStateListener = listener
    }

    private var pullSides: Side? = Side.NONE
    private var busySide: Side? = Side.NONE

    fun setPullSides(sides: Side?) {
        pullSides = sides ?: Side.NONE
    }

    fun startBusyState(side: Side) {
        startBusyState(side, false)
    }

    private fun getSidePullView(side: Side?): PullView? =
        if (side == Side.TOP) {
            topView
        } else if (side == Side.BOTTOM) {
            bottomView
        } else {
            null
        }

    private fun startBusyState(
        side: Side?,
        useCallback: Boolean,
    ): Boolean {
        if (side == null || side == Side.NONE) {
            return false
        }
        if (busySide != Side.NONE || side != pullSides && pullSides != Side.BOTH) {
            if (side == Side.BOTH && (busySide == Side.TOP || busySide == Side.BOTTOM)) {
                val pullView = getSidePullView(busySide)
                pullView!!.setState(
                    PullView.State.IDLE,
                    listView.getEdgeEffectShift(
                        if (side == Side.TOP) {
                            EdgeEffectHandler.Side.TOP
                        } else {
                            EdgeEffectHandler.Side.BOTTOM
                        },
                    ),
                )
                busySide = Side.BOTH
            }
            return false
        }
        busySide = side
        val pullView = getSidePullView(side)
        if (pullView != null) {
            pullView.setState(
                PullView.State.LOADING,
                listView.getEdgeEffectShift(
                    if (side == Side.TOP) {
                        EdgeEffectHandler.Side.TOP
                    } else {
                        EdgeEffectHandler.Side.BOTTOM
                    },
                ),
            )
        }
        if (useCallback) {
            pullCallback.onListPulled(this, side)
        }
        notifyPullStateChanged(true)
        return true
    }

    fun cancelBusyState() {
        if (busySide != Side.NONE) {
            busySide = Side.NONE
            topView.setState(
                PullView.State.IDLE,
                listView.getEdgeEffectShift(EdgeEffectHandler.Side.TOP),
            )
            bottomView.setState(
                PullView.State.IDLE,
                listView.getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM),
            )
            notifyPullStateChanged(false)
            updateStartY = true
        }
    }

    private fun notifyPullStateChanged(busy: Boolean) {
        pullStateListener?.onPullStateChanged(this, busy)
    }

    private var updateStartY = true
    private var startY = 0f

    private fun deltaToPullStrain(delta: Float): Int = (pullDeltaGain * delta / listView.getHeight() * PullView.MAX_STRAIN).toInt()

    private fun pullStrainToDelta(pullStrain: Int): Int = (pullStrain * listView.getHeight() / (pullDeltaGain * PullView.MAX_STRAIN)).toInt()

    // Used to calculate list transition animation.
    private var topJumpStartTime: Long = 0
    private var bottomJumpStartTime: Long = 0

    fun onTouchEventOrNull(ev: MotionEvent?): Boolean {
        var pull = false
        val action = if (ev != null) ev.getAction() else MotionEvent.ACTION_CANCEL
        if (action == MotionEvent.ACTION_DOWN || !listView.isScrolledToTop() && !listView.isScrolledToBottom()) {
            startY = if (ev != null) ev.getY() else 0f
        } else if (updateStartY) {
            val hsize = if (ev != null) ev.getHistorySize() else 0
            startY = if (hsize > 0) ev!!.getHistoricalY(hsize - 1) else ev!!.getY()
        }
        updateStartY = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (busySide == Side.NONE) {
            val edgeEffectHandler = listView.getEdgeEffectHandler()
            if (action == MotionEvent.ACTION_DOWN) {
                if (edgeEffectHandler != null) {
                    edgeEffectHandler.setPullable(EdgeEffectHandler.Side.TOP, true)
                    edgeEffectHandler.setPullable(EdgeEffectHandler.Side.BOTTOM, true)
                }
            } else {
                var dy = if (ev != null) ev.getY() - startY else 0f
                var resetTop = true
                var resetBottom = true
                if (action == MotionEvent.ACTION_MOVE) {
                    // Call getIdlePullStrain to get previous transient value
                    if (dy > 0 &&
                        listView.isScrolledToTop() &&
                        (pullSides == Side.BOTH || pullSides == Side.TOP)
                    ) {
                        pull = true
                        resetTop = false
                        val pullStrain = topView.getAndResetIdlePullStrain()
                        if (pullStrain > 0) {
                            startY -= pullStrainToDelta(pullStrain).toFloat()
                            dy = ev!!.getY() - startY
                        }
                        val padding = listView.getEdgeEffectShift(EdgeEffectHandler.Side.TOP)
                        topView.setState(PullView.State.PULL, padding)
                        topView.setPullStrain(deltaToPullStrain(dy), padding)
                        if (edgeEffectHandler != null) {
                            edgeEffectHandler.finish(EdgeEffectHandler.Side.TOP)
                            edgeEffectHandler.setPullable(EdgeEffectHandler.Side.TOP, false)
                        }
                    } else if (dy < 0 &&
                        listView.isScrolledToBottom() &&
                        (pullSides == Side.BOTH || pullSides == Side.BOTTOM)
                    ) {
                        pull = true
                        resetBottom = false
                        val pullStrain = bottomView.getAndResetIdlePullStrain()
                        if (pullStrain > 0) {
                            startY += pullStrainToDelta(pullStrain).toFloat()
                            dy = ev!!.getY() - startY
                        }
                        val padding = listView.getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
                        bottomView.setState(PullView.State.PULL, padding)
                        bottomView.setPullStrain(-deltaToPullStrain(dy), padding)
                        if (edgeEffectHandler != null) {
                            edgeEffectHandler.finish(EdgeEffectHandler.Side.BOTTOM)
                            edgeEffectHandler.setPullable(EdgeEffectHandler.Side.BOTTOM, false)
                        }
                    }
                }
                val topPullStrain = topView.getPullStrain()
                val bottomPullStrain = bottomView.getPullStrain()
                if (resetTop && resetBottom && (topPullStrain > 0 || bottomPullStrain > 0)) {
                    if (topPullStrain > bottomPullStrain) {
                        topJumpStartTime = topView.calculateJumpStartTime()
                    } else {
                        bottomJumpStartTime = bottomView.calculateJumpStartTime()
                    }
                }
                if (action == MotionEvent.ACTION_UP) {
                    if (topPullStrain >= PullView.MAX_STRAIN) {
                        topJumpStartTime = SystemClock.elapsedRealtime()
                        val success = startBusyState(Side.TOP, true)
                        resetTop = resetTop and !success
                    }
                    if (bottomPullStrain >= PullView.MAX_STRAIN) {
                        bottomJumpStartTime = SystemClock.elapsedRealtime()
                        val success = startBusyState(Side.BOTTOM, true)
                        resetBottom = resetBottom and !success
                    }
                }
                if (resetTop) {
                    topView.setState(
                        PullView.State.IDLE,
                        listView.getEdgeEffectShift(EdgeEffectHandler.Side.TOP),
                    )
                }
                if (resetBottom) {
                    bottomView.setState(
                        PullView.State.IDLE,
                        listView.getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM),
                    )
                }
            }
        }
        return pull
    }

    private var lastShiftValue = 0
    private var beforeShiftValue = 0
    private var beforeRestoreCanvas = false

    init {
        val context = listView.getContext()
        topView = LollipopView(listView, true)
        bottomView = LollipopView(listView, false)
        pullDeltaGain = if (isTablet(context.getResources().getConfiguration())) 6f else 4f
        setColor(ThemeEngine.Companion.getTheme(listView.getContext()).accent)
    }

    fun drawBefore(canvas: Canvas) {
        val top = topView.calculateJumpValue(topJumpStartTime)
        val bottom = bottomView.calculateJumpValue(bottomJumpStartTime)
        val shift = if (top > bottom) top else -bottom
        if (shift != 0) {
            canvas.save()
            val height = listView.getHeight().toFloat()
            val dy =
                (
                    (abs(pullStrainToDelta(shift)) / height)
                        .toDouble()
                        .pow(2.5)
                ).toFloat() * height * sign(shift.toFloat())
            canvas.translate(0f, dy)
            beforeRestoreCanvas = true
        }
        beforeShiftValue = shift
    }

    fun drawAfter(canvas: Canvas) {
        if (beforeRestoreCanvas) {
            beforeRestoreCanvas = false
            canvas.restore()
        }
        val shift = beforeShiftValue
        topView.draw(canvas, listView.getEdgeEffectShift(EdgeEffectHandler.Side.TOP))
        bottomView.draw(canvas, listView.getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM))
        if (lastShiftValue != shift) {
            lastShiftValue = shift
            listView.invalidate()
        }
    }

    private interface PullView {
        enum class State {
            IDLE,
            PULL,
            LOADING,
        }

        fun setColor(color: Int)

        fun setState(
            state: State,
            padding: Int,
        )

        fun setPullStrain(
            pullStrain: Int,
            padding: Int,
        )

        fun getPullStrain(): Int

        fun getAndResetIdlePullStrain(): Int

        fun draw(
            canvas: Canvas,
            padding: Int,
        )

        fun calculateJumpStartTime(): Long

        fun calculateJumpValue(jumpStartTime: Long): Int

        companion object {
            const val MAX_STRAIN: Int = 1000
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private class LollipopView(
        wrapped: Wrapped,
        private val top: Boolean,
    ) : PullView {
        private val wrapped: WeakReference<Wrapped?>
        private val radius: Int
        private val commonShift: Int
        private val shadowSize: Int
        private val shadowShift: Int
        private val ringRadius: Float
        private val strokeWidth: Float

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rectF = RectF()
        private val shadow: Bitmap

        private val startInterpolator: Interpolator
        private val endInterpolator: Interpolator

        private var previousState: PullView.State? = PullView.State.IDLE
        private var state = PullView.State.IDLE

        private var startFoldingPullStrain = 0
        private var timeStateStart = 0L
        private var timeSpinStart = 0L
        private var spinOffset = 0f

        private var pullStrain = 0

        init {
            this.wrapped = WeakReference<Wrapped?>(wrapped)
            val density =
                ResourceUtils.obtainDensity(
                    wrapped.getContext(),
                )
            radius = (CIRCLE_RADIUS * density).toInt()
            commonShift = (DEFAULT_CIRCLE_TARGET * density).toInt()
            shadowSize = (SHADOW_SIZE * density).toInt()
            shadowShift = (SHADOW_SHIFT * density).toInt()
            ringRadius = CENTER_RADIUS * density
            strokeWidth = STROKE_WIDTH * density
            val startPath = Path()
            startPath.lineTo(0.5f, 0f)
            startPath.cubicTo(0.7f, 0f, 0.6f, 1f, 1f, 1f)
            startInterpolator = PathInterpolator(startPath)
            val endPath = Path()
            endPath.cubicTo(0.2f, 0f, 0.1f, 1f, 0.5f, 1f)
            endPath.lineTo(1f, 1f)
            endInterpolator = PathInterpolator(endPath)
            ringPaint.setStyle(Paint.Style.STROKE)
            ringPaint.setStrokeCap(Paint.Cap.SQUARE)
            ringPaint.setStrokeJoin(Paint.Join.MITER)
            val bitmapSize = 2 * radius + shadowSize + shadowShift
            shadow = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(shadow)
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            shadowPaint.setColor(0x7f000000)
            shadowPaint.setMaskFilter(
                BlurMaskFilter(
                    shadowSize.toFloat(),
                    BlurMaskFilter.Blur.NORMAL,
                ),
            )
            canvas.drawCircle(
                bitmapSize / 2f,
                bitmapSize / 2f,
                (radius - shadowSize).toFloat(),
                shadowPaint,
            )
        }

        override fun setColor(color: Int) {
            circlePaint.setColor(color)
        }

        fun invalidate(padding: Int) {
            val wrapped = this.wrapped.get()
            if (wrapped != null) {
                invalidate(wrapped, wrapped.getWidth(), padding)
            }
        }

        fun invalidate(
            wrapped: Wrapped,
            width: Int,
            padding: Int,
        ) {
            val hw = width / 2
            val radius = this.radius
            val shadowSize = this.shadowSize
            val shadowShift = this.shadowShift
            val l = hw - radius - shadowSize - 1
            val r = hw + radius + shadowSize + 1
            var t = padding - 2 * radius - shadowSize + shadowShift - 1
            var b = padding + 2 * commonShift + shadowSize + shadowShift + 1
            if (!top) {
                val height = wrapped.getHeight()
                val nt = height - t
                val nb = height - b
                t = nb
                b = nt
            }
            wrapped.invalidate(l, t, r, b)
        }

        override fun setState(
            state: PullView.State,
            padding: Int,
        ) {
            if (this.state != state) {
                val prePreviousState = previousState
                previousState = this.state
                this.state = state
                val time = SystemClock.elapsedRealtime()
                when (this.state) {
                    PullView.State.IDLE -> {
                        if (previousState == PullView.State.LOADING) {
                            val pullStrain =
                                (
                                    PullView.MAX_STRAIN *
                                        getIdleTransientPullStrainValue(IDLE_FOLD_TIME, time)
                                ).toInt()
                            startFoldingPullStrain = max(PullView.MAX_STRAIN, pullStrain)
                        } else {
                            startFoldingPullStrain = pullStrain
                        }
                        timeStateStart = time
                        pullStrain = 0
                    }

                    PullView.State.PULL -> {}

                    PullView.State.LOADING -> {
                        // May continue use old animation until it over
                        val loadingToLoading =
                            prePreviousState == PullView.State.LOADING && previousState == PullView.State.IDLE && time - timeStateStart < 50
                        if (!loadingToLoading) {
                            timeStateStart = time
                            startFoldingPullStrain = pullStrain
                            timeSpinStart =
                                if (previousState == PullView.State.IDLE) time else time - FULL_CYCLE_TIME / 10
                            if (previousState == PullView.State.IDLE) {
                                spinOffset = 0f
                            }
                        }
                    }
                }
                invalidate(padding)
            }
        }

        override fun setPullStrain(
            pullStrain: Int,
            padding: Int,
        ) {
            this.pullStrain = pullStrain
            if (this.pullStrain > 2 * PullView.MAX_STRAIN) {
                this.pullStrain = 2 * PullView.MAX_STRAIN
            } else if (this.pullStrain < 0) {
                this.pullStrain = 0
            }
            if (state == PullView.State.PULL) {
                invalidate(padding)
            }
        }

        override fun getPullStrain(): Int = min(pullStrain, PullView.MAX_STRAIN)

        override fun getAndResetIdlePullStrain(): Int {
            if (startFoldingPullStrain == 0) {
                return 0
            }
            try {
                return (
                    PullView.MAX_STRAIN *
                        getIdleTransientPullStrainValue(
                            IDLE_FOLD_TIME,
                            SystemClock.elapsedRealtime(),
                        )
                ).toInt()
            } finally {
                startFoldingPullStrain = 0
            }
        }

        fun getIdleTransientPullStrainValue(
            maxFoldTime: Int,
            time: Long,
        ): Float {
            val foldTime = maxFoldTime * startFoldingPullStrain / PullView.MAX_STRAIN
            if (foldTime <= 0) {
                return 0f
            }
            val value = min((time - timeStateStart).toFloat() / foldTime, 1f)
            return (1f - value) * startFoldingPullStrain / PullView.MAX_STRAIN
        }

        override fun draw(
            canvas: Canvas,
            padding: Int,
        ) {
            val wrapped = this.wrapped.get()
            if (wrapped == null) {
                return
            }
            val circlePaint = this.circlePaint
            val ringPaint = this.ringPaint
            val time = SystemClock.elapsedRealtime()
            val width = wrapped.getWidth()
            val height = wrapped.getHeight()
            val state = this.state
            val previousState = this.previousState
            var needInvalidate = false

            var value = 0f
            var scale = 1f
            var spin = false

            if (state == PullView.State.PULL) {
                value = pullStrain.toFloat() / PullView.MAX_STRAIN
            }

            if (state == PullView.State.IDLE) {
                if (previousState == PullView.State.LOADING) {
                    value = getIdleTransientPullStrainValue(LOADING_FOLD_TIME, time)
                    if (value > 0f) {
                        scale = min(1f, value)
                        value = max(1f, value)
                        spin = true
                        needInvalidate = true
                    }
                } else {
                    value = getIdleTransientPullStrainValue(IDLE_FOLD_TIME, time)
                    if (value != 0f) {
                        needInvalidate = true
                    }
                }
            }

            if (state == PullView.State.LOADING) {
                value = getIdleTransientPullStrainValue(IDLE_FOLD_TIME, time)
                if (value <= 1f) {
                    value = 1f
                }
                spin = true
                if (previousState == PullView.State.IDLE) {
                    scale = min((time - timeStateStart).toFloat() / LOADING_FOLD_TIME, 1f)
                }
                needInvalidate = true
            }

            if (value != 0f) {
                var shift = (commonShift * value).toInt() + padding
                if (!top) {
                    shift = height - shift
                }
                val centerX = width / 2f
                val centerY = (if (top) shift - radius else shift + radius).toFloat()
                var needRestore = false
                if (scale != 1f && scale != 0f) {
                    canvas.save()
                    canvas.scale(scale, scale, centerX, centerY)
                    needRestore = true
                }
                ringPaint.setStrokeWidth(strokeWidth)
                canvas.drawBitmap(
                    shadow,
                    centerX - shadow.getWidth() / 2f,
                    centerY - shadow.getHeight() / 2f + shadowShift,
                    circlePaint,
                )
                canvas.drawCircle(centerX, centerY, radius.toFloat(), circlePaint)

                var arcStart: Float
                val arcLength: Float
                var ringAlpha = 0xff
                if (spin) {
                    val rotationValue =
                        ((time - timeSpinStart) % FULL_CYCLE_TIME).toFloat() / FULL_CYCLE_TIME
                    val animationValue = rotationValue * 5f % 1f
                    val trimOffset = 0.25f * animationValue
                    val trimStart =
                        0.75f * startInterpolator.getInterpolation(animationValue) + trimOffset
                    val trimEnd =
                        0.75f * endInterpolator.getInterpolation(animationValue) + trimOffset
                    val rotation = 2f * rotationValue
                    arcStart = trimStart
                    arcLength = trimEnd - arcStart
                    arcStart += rotation + spinOffset
                } else {
                    val alphaThreshold = 0.95f
                    ringAlpha =
                        lerp(
                            0x7f.toFloat(),
                            0xff.toFloat(),
                            (
                                min(
                                    1f,
                                    max(value, alphaThreshold),
                                ) - alphaThreshold
                            ) / (1f - alphaThreshold),
                        ).toInt()
                    arcStart = if (value > 1f) 0.25f + (value - 1f) * 0.5f else 0.25f * value
                    arcLength = 0.75f * min(value, 1f).toDouble().pow(0.75).toFloat()
                    if (value >= 1f) {
                        spinOffset = (value - 1f) * 0.5f
                    }
                }
                ringPaint.setColor(0xffffff or (ringAlpha shl 24))
                val size = rectF
                size.set(
                    centerX - ringRadius,
                    centerY - ringRadius,
                    centerX + ringRadius,
                    centerY + ringRadius,
                )
                drawArc(canvas, ringPaint, size, arcStart, arcLength)
                if (needRestore) {
                    canvas.restore()
                }
            }

            if (needInvalidate) {
                invalidate(wrapped, width, padding)
            }
        }

        fun drawArc(
            canvas: Canvas,
            paint: Paint,
            size: RectF,
            start: Float,
            length: Float,
        ) {
            val arcLength = if (length < 0.001f) 0.001f else length
            val path = this.path
            path.reset()
            if (arcLength >= 1f) {
                path.arcTo(size, 0f, 180f, false)
                path.arcTo(size, 180f, 180f, false)
            } else {
                path.arcTo(size, start * 360f - 90f, arcLength * 360f, false)
            }
            canvas.drawPath(path, paint)
        }

        override fun calculateJumpStartTime(): Long = 0L

        override fun calculateJumpValue(jumpStartTime: Long): Int = 0

        companion object {
            private const val IDLE_FOLD_TIME = 100
            private const val LOADING_FOLD_TIME = 150
            private const val FULL_CYCLE_TIME = 6665

            private const val CIRCLE_RADIUS = 20
            private const val DEFAULT_CIRCLE_TARGET = 64
            private const val SHADOW_SIZE = 4
            private const val SHADOW_SHIFT = 2
            private const val CENTER_RADIUS = 8.75f
            private const val STROKE_WIDTH = 2.5f
        }
    }

    interface Wrapped : Shift {
        fun getContext(): Context

        fun getResources(): Resources

        fun getEdgeEffectHandler(): EdgeEffectHandler?

        fun isScrolledToTop(): Boolean

        fun isScrolledToBottom(): Boolean

        fun invalidate(
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        )

        fun invalidate()

        fun getWidth(): Int

        fun getHeight(): Int
    }

    companion object {
        private const val BUSY_JUMP_TIME = 200
    }
}
