package com.mishiranu.dashchan.widget

import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Scroller
import com.mishiranu.dashchan.graphics.TransparentTileDrawable
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class PhotoView(context: Context, attr: AttributeSet?) : View(context, attr),
    OnScaleGestureListener {
    private enum class ScrollEdge {
        NONE, START, END, BOTH
    }

    private enum class TouchMode {
        UNDEFINED, COMMON, CLOSING_START, CLOSING_END, CLOSING_BOTH
    }

    private val tile: TransparentTileDrawable
    private var drawable: Drawable? = null
    private var hasAlpha = false
    private var fitScreen = false
    private var drawDim = false
    private val previousDimensions = Point()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var initialScalingData: IntArray? = null
    private var initialScalingAnimator: ValueAnimator? = null
    private var initialScaleClipRect: Rect? = null

    private var minimumScale = 0f
    private var maximumScale = 0f
    private var doubleTapScale = 0f
    private var initialScale = 0f

    private val baseMatrix = Matrix()
    private val transformMatrix = Matrix()
    private val displayMatrix = Matrix()
    private val workRect = RectF()
    private val matrixValues = FloatArray(9)

    private var listener: Listener? = null

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector

    private var flingRunnable: FlingRunnable? = null
    private var scrollEdgeX = ScrollEdge.BOTH
    private var scrollEdgeY = ScrollEdge.BOTH

    private var touchMode = TouchMode.UNDEFINED
    private var animatedRestoreSwipeRunnable: AnimatedRestoreSwipeRunnable? = null

    private var activePointerId: Int = INVALID_POINTER_ID
    private var activePointerIndex = 0

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val touchSlop: Float
    private val minimumVelocity: Float

    private var isDoubleTapDown = false
    private var isQuickScale = false

    private var velocityTracker: VelocityTracker? = null
    private var isDragging = false
    private var isParentDragging = false

    private val lastLayout = Rect()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (top != lastLayout.top || bottom != lastLayout.bottom || left != lastLayout.left || right != lastLayout.right) {
            lastLayout.set(left, top, right, bottom)
            resetScale()
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    interface Listener {
        fun onClick(photoView: PhotoView?, image: Boolean, x: Float, y: Float)
        fun onLongClick(photoView: PhotoView?, x: Float, y: Float)
        fun onVerticalSwipe(photoView: PhotoView?, down: Boolean, value: Float)
        fun onClose(photoView: PhotoView?, down: Boolean): Boolean
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun recycle() {
        if (drawable != null) {
            drawable!!.setCallback(null)
            unscheduleDrawable(drawable)
            drawable = null
            invalidate()
            post(Runnable {
                // Check drawable wasn't set immediately after recycle call
                if (drawable == null) {
                    // Disallow scale keeping
                    previousDimensions.set(0, 0)
                    cancelRestoreVerticalSwipe(false)
                    notifyVerticalSwipe(0f, false)
                }
            })
        }
    }

    fun setImage(drawable: Drawable, hasAlpha: Boolean, fitScreen: Boolean, keepScale: Boolean) {
        var keepScale = keepScale
        recycle()
        this.drawable = drawable
        this.hasAlpha = hasAlpha
        this.fitScreen = fitScreen
        drawDim = false
        drawable.setCallback(this)
        val dimensions = this.dimensions
        keepScale = keepScale and (previousDimensions == dimensions)
        previousDimensions.set(dimensions!!.x, dimensions.y)
        initBaseMatrix(keepScale)
    }

    fun hasImage(): Boolean {
        return drawable != null
    }

    fun setDrawDimForCurrentImage(drawDim: Boolean) {
        if (this.drawDim != drawDim) {
            this.drawDim = drawDim
            invalidate()
        }
    }

    fun setInitialScaleAnimationData(imageViewPosition: IntArray, cropEnabled: Boolean) {
        initialScalingData = intArrayOf(
            imageViewPosition[0], imageViewPosition[1], imageViewPosition[2],
            imageViewPosition[3], if (cropEnabled) 1 else 0
        )
    }

    fun clearInitialScaleAnimationData() {
        initialScalingData = null
        if (initialScalingAnimator != null) {
            initialScalingAnimator!!.cancel()
            initialScalingAnimator = null
            initialScaleClipRect = null
            this.scale = initialScale
        }
    }

    private fun handleInitialScale() {
        val initialScalingData = this.initialScalingData
        if (initialScalingData != null) {
            clearInitialScaleAnimationData()
            val location = IntArray(2)
            getLocationOnScreen(location)
            val x = initialScalingData[0] - location[0]
            val y = initialScalingData[1] - location[1]
            val viewWidth = initialScalingData[2]
            val viewHeight = initialScalingData[3]
            val centerX = x + viewWidth / 2f
            val centerY = y + viewHeight / 2f
            val cropEnabled = initialScalingData[4] != 0
            initialScalingAnimator = ValueAnimator.ofFloat(0f, 1f)
            initialScalingAnimator!!.addUpdateListener(
                InitialScaleListener(
                    centerX, centerY,
                    viewWidth, viewHeight, cropEnabled
                )
            )
            initialScalingAnimator!!.setDuration(INITIAL_SCALE_TRANSITION_TIME.toLong())
            initialScalingAnimator!!.start()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        updateTextureSize(canvas)
        val dimensions = this.dimensions
        if (dimensions == null) {
            return
        }
        handleInitialScale()
        var restoreClip = false
        if (initialScaleClipRect != null) {
            restoreClip = true
            canvas.save()
            canvas.clipRect(initialScaleClipRect!!)
        }
        var rect = initDisplayMatrixAndRect()
        var workAlpha = 0xff
        if (this.isClosingTouchMode) {
            val value = min(abs(getClosingTouchModeShift(rect!!)) * 2f / getHeight(), 1f)
            workAlpha = (workAlpha * (1f - value)).toInt()
            val scale =
                (1f - AnimationUtils.ACCELERATE_INTERPOLATOR.getInterpolation(value)) * 0.4f + 0.6f
            displayMatrix.postScale(scale, scale, getWidth() / 2f, getHeight() / 2f)
            rect = initDisplayRect()
        }
        var restoreAlpha = false
        if (workAlpha != 0xff) {
            canvas.saveLayerAlpha(0f, 0f, getWidth().toFloat(), getHeight().toFloat(), workAlpha)

            restoreAlpha = true
        }
        if (drawable != null) {
            if (hasAlpha) {
                tile.setBounds(
                    (rect!!.left + 0.5f).toInt(),
                    (rect.top + 0.5f).toInt(),
                    (rect.right + 0.5f).toInt(),
                    (rect.bottom + 0.5f).toInt()
                )
                tile.draw(canvas)
            }
            canvas.save()
            canvas.clipRect(0, 0, getWidth(), getHeight())
            canvas.concat(displayMatrix)
            drawable!!.setBounds(
                0,
                0,
                drawable!!.getIntrinsicWidth(),
                drawable!!.getIntrinsicHeight()
            )
            drawable!!.draw(canvas)
            canvas.restore()
        }
        if (drawDim) {
            canvas.save()
            canvas.concat(displayMatrix)
            paint.setColor(0x44000000)
            canvas.drawRect(0f, 0f, dimensions.x.toFloat(), dimensions.y.toFloat(), paint)
            canvas.restore()
        }
        if (restoreAlpha) {
            canvas.restore()
        }
        if (restoreClip) {
            canvas.restore()
        }
    }

    override fun invalidateDrawable(drawable: Drawable) {
        // Override this method instead of verifyDrawable because I use own matrix to concatenate canvas
        if (drawable === this.drawable) {
            invalidate()
        } else {
            super.invalidateDrawable(drawable)
        }
    }

    private var maximumImageSize = 0
    private val maximumImageSizeLock = Any()

    private fun updateTextureSize(canvas: Canvas) {
        if (maximumImageSize == 0) {
            val maxSize = min(canvas.getMaximumBitmapWidth(), canvas.getMaximumBitmapHeight())
            maximumImageSize = min(maxSize, 2048)
            synchronized(maximumImageSizeLock) {
                (maximumImageSizeLock as Object).notifyAll()
            }
        }
    }

    @get:Throws(InterruptedException::class)
    val maximumImageSizeAsync: Int
        get() {
            if (maximumImageSize == 0) {
                synchronized(maximumImageSizeLock) {
                    while (maximumImageSize == 0) {
                        (maximumImageSizeLock as Object).wait()
                    }
                }
            }
            return maximumImageSize
        }

    private val point = Point()

    private val dimensions: Point?
        get() {
            if (drawable == null) {
                return null
            }
            point.set(drawable!!.getIntrinsicWidth(), drawable!!.getIntrinsicHeight())
            return point
        }

    override fun onDetachedFromWindow() {
        cleanup()
        super.onDetachedFromWindow()
    }

    fun cleanup() {
        cancelFling()
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val isQuickScale = this.isQuickScale
        if (!isQuickScale || detector.getPreviousSpan() > 2 * touchSlop) {
            var factor = detector.getScaleFactor()
            if (factor > 0f) {
                if (isQuickScale) {
                    factor = max(0.75f, min(factor, 1.25f))
                }
                onScale(factor, detector.getFocusX(), detector.getFocusY())
            }
        }
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    private var scale: Float
        get() {
            transformMatrix.getValues(matrixValues)
            return sqrt(
                matrixValues[Matrix.MSCALE_X].toDouble()
                    .pow(2.0) + matrixValues[Matrix.MSKEW_Y].toDouble().pow(2.0)
            ).toFloat()
        }
        set(scale) {
            setScale(scale, false)
        }

    private fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            isDoubleTapDown = true
        } else if (e.getAction() == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress()) {
            val scale = this.scale
            val x = e.getX()
            val y = e.getY()
            setScale(if (scale < doubleTapScale) doubleTapScale else minimumScale, x, y, true)
            return true
        }
        return false
    }

    private fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (listener != null) {
            val x = e.getX()
            val y = e.getY()
            val rect = checkMatrixBounds()
            if (rect != null && rect.contains(x, y)) {
                listener!!.onClick(this, true, x, y)
                return true
            }
            listener!!.onClick(this, false, x, y)
        }
        return false
    }

    private fun onLongPress(e: MotionEvent) {
        if (listener != null) {
            listener!!.onLongClick(this, e.getX(), e.getY())
        }
    }

    private fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
        var scaleFactor = scaleFactor
        if (checkTouchMode() && !fitScreen) {
            val scale = this.scale
            val maxFactor = maximumScale / scale
            scaleFactor = min(scaleFactor, maxFactor)
            if (scaleFactor == 1f) {
                return
            }
            if (scale <= minimumScale && scaleFactor < 1f) {
                scaleFactor = scaleFactor.toDouble().pow((1f / 4f).toDouble()).toFloat()
            }
            val minFactor = minimumScale / scale / 2f
            scaleFactor = max(scaleFactor, minFactor)
            if (scaleFactor == 1f) {
                return
            }
            transformMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
            checkMatrixBoundsAndInvalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }

    fun dispatchSimpleClick(longClick: Boolean, x: Float, y: Float) {
        if (listener != null && !hasImage() && this.isAttachedToWindow()) {
            if (longClick) {
                listener!!.onLongClick(this, x, y)
            } else {
                listener!!.onClick(this, true, x, y)
            }
        }
    }

    fun dispatchSpecialTouchEvent(event: MotionEvent) {
        if (hasImage()) {
            val action = event.getActionMasked()
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    isParentDragging = false
                    touchMode = TouchMode.UNDEFINED
                    cancelFling()
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    if (this.scale < minimumScale) {
                        val rect = checkMatrixBounds()
                        if (rect != null) {
                            post(
                                AnimatedScaleRunnable(
                                    minimumScale,
                                    rect.centerX(),
                                    rect.centerY()
                                )
                            )
                        }
                    }
                }
            }
            isDoubleTapDown = false
            gestureDetector.onTouchEvent(event)
            scaleGestureDetector.onTouchEvent(event)
            if (action == MotionEvent.ACTION_DOWN) {
                isQuickScale = isDoubleTapDown && scaleGestureDetector.isQuickScaleEnabled()
                if (isQuickScale) {
                    checkTouchMode()
                }
            }
            onCommonTouchEvent(event)
        }
    }

    fun dispatchIdleMoveTouchEvent(event: MotionEvent) {
        lastTouchY = getActiveY(event)
        isParentDragging = true
    }

    val isClosingTouchMode: Boolean
        get() = touchMode == TouchMode.CLOSING_START || touchMode == TouchMode.CLOSING_END || touchMode == TouchMode.CLOSING_BOTH

    private fun getClosingTouchModeShift(rect: RectF): Float {
        if (touchMode == TouchMode.CLOSING_START) {
            return rect.top
        } else if (touchMode == TouchMode.CLOSING_END) {
            return rect.bottom - getHeight()
        } else {
            return (rect.top + rect.bottom - getHeight()) / 2f
        }
    }

    fun canScrollLeft(): Boolean {
        return hasImage() && (scrollEdgeX != ScrollEdge.START && scrollEdgeX != ScrollEdge.BOTH
                || this.isClosingTouchMode)
    }

    fun canScrollRight(): Boolean {
        return hasImage() && (scrollEdgeX != ScrollEdge.END && scrollEdgeX != ScrollEdge.BOTH
                || this.isClosingTouchMode)
    }

    val isScaling: Boolean
        get() = hasImage() && scaleGestureDetector.isInProgress()

    fun setScale(scale: Float, animate: Boolean) {
        setScale(scale, getRight() / 2f, getBottom() / 2f, animate)
    }

    fun setScale(scale: Float, focalX: Float, focalY: Float, animate: Boolean) {
        if (scale < minimumScale || scale > maximumScale) {
            return
        }
        touchMode = TouchMode.UNDEFINED
        if (animate) {
            post(AnimatedScaleRunnable(scale, focalX, focalY))
        } else {
            transformMatrix.setScale(scale, scale, focalX, focalY)
            checkMatrixBoundsAndInvalidate()
        }
    }

    private fun cancelFling() {
        if (flingRunnable != null) {
            flingRunnable!!.cancelFling()
            flingRunnable = null
        }
    }

    private fun checkTouchMode(): Boolean {
        when (touchMode) {
            TouchMode.UNDEFINED -> {
                run {
                    touchMode = TouchMode.COMMON
                }
                run {
                    return true
                }
            }

            TouchMode.COMMON -> {
                return true
            }

            TouchMode.CLOSING_START, TouchMode.CLOSING_END, TouchMode.CLOSING_BOTH -> {
                return false
            }
        }
        throw RuntimeException()
    }

    private fun checkMatrixBoundsAndInvalidate() {
        checkMatrixBounds()
        invalidate()
    }

    private fun checkMatrixBounds(): RectF? {
        val rect = initDisplayMatrixAndRect()
        if (rect == null) {
            return null
        }
        val width = rect.width()
        val height = rect.height()
        val viewHeight = getHeight()
        var deltaX = 0f
        var deltaY = 0f
        if (height <= viewHeight + 0.5f) {
            deltaY = (viewHeight - height) / 2 - rect.top
            scrollEdgeY = ScrollEdge.BOTH
        } else if (rect.top + 0.5f >= 0) {
            deltaY = -rect.top
            scrollEdgeY = ScrollEdge.START
        } else if (rect.bottom - 0.5f <= viewHeight) {
            deltaY = viewHeight - rect.bottom
            scrollEdgeY = ScrollEdge.END
        } else {
            if (this.isClosingTouchMode) {
                notifyVerticalSwipe(0f, false)
                touchMode = TouchMode.COMMON // Reset touch mode
            }
            scrollEdgeY = ScrollEdge.NONE
        }
        if (this.isClosingTouchMode) {
            notifyVerticalSwipe(-deltaY, false)
            deltaY = 0f
        }
        val viewWidth = getWidth()
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left
            scrollEdgeX = ScrollEdge.BOTH
        } else if (rect.left >= 0) {
            scrollEdgeX = ScrollEdge.START
            deltaX = -rect.left
        } else if (rect.right <= viewWidth) {
            deltaX = viewWidth - rect.right
            scrollEdgeX = ScrollEdge.END
        } else {
            scrollEdgeX = ScrollEdge.NONE
        }
        transformMatrix.postTranslate(deltaX, deltaY)
        return rect
    }

    private fun initDisplayMatrixAndRect(): RectF? {
        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(transformMatrix)
        return initDisplayRect()
    }

    private fun initDisplayRect(): RectF? {
        val dimensions = this.dimensions
        if (dimensions != null) {
            workRect.set(0f, 0f, dimensions.x.toFloat(), dimensions.y.toFloat())
            displayMatrix.mapRect(workRect)
            return workRect
        }
        return null
    }

    fun resetScale() {
        initBaseMatrix(false)
    }

    private fun initBaseMatrix(keepScale: Boolean) {
        cancelRestoreVerticalSwipe(true)
        val dimensions = this.dimensions
        if (dimensions == null) {
            return
        }
        val viewWidth = getWidth().toFloat()
        val viewHeight = getHeight().toFloat()
        val imageWidth = dimensions.x
        val imageHeight = dimensions.y
        baseMatrix.reset()
        val widthScale = viewWidth / imageWidth
        val heightScale = viewHeight / imageHeight
        var scale = min(widthScale, heightScale)
        var postScale = 1f
        if (scale > 1f) {
            postScale = scale
            scale = 1f
        } else if (scale <= 0f) {
            scale = 1f
        }
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(
            (viewWidth - imageWidth * scale) / 2f,
            (viewHeight - imageHeight * scale) / 2f
        )
        if (!keepScale) {
            transformMatrix.reset()
            checkMatrixBounds()
        }
        if (fitScreen) {
            minimumScale = postScale
            maximumScale = postScale
            initialScale = postScale
            doubleTapScale = postScale
            this.scale = postScale
        } else {
            minimumScale = 1f
            maximumScale = 4f / scale
            initialScale = min(postScale, maximumScale)
            doubleTapScale =
                if (postScale > 1f) min(postScale, maximumScale) else min(1f / scale, 8f)
            if (!keepScale && postScale > 1f) {
                this.scale = doubleTapScale
            }
        }
        invalidate()
    }

    private var lastVerticalSwipeDeltaY = 0f

    private fun notifyVerticalSwipe(deltaY: Float, restore: Boolean) {
        if (lastVerticalSwipeDeltaY != deltaY) {
            var value = min(abs(deltaY / getHeight()) * 4f, 1f)
            if (value < 0.001f && value > -0.001f) {
                value = 0f
            }
            lastVerticalSwipeDeltaY = deltaY
            if (listener != null) {
                listener!!.onVerticalSwipe(this, deltaY >= 0 != restore, value)
            }
        }
    }

    private fun cancelRestoreVerticalSwipe(notify: Boolean) {
        if (animatedRestoreSwipeRunnable != null) {
            removeCallbacks(animatedRestoreSwipeRunnable)
            animatedRestoreSwipeRunnable = null
            if (notify) {
                notifyVerticalSwipe(0f, false)
            }
        }
    }

    private fun startRestoreVerticalSwipe(rect: RectF?, close: Boolean, velocity: Float) {
        var rect = rect
        cancelRestoreVerticalSwipe(false)
        if (rect == null) {
            rect = checkMatrixBounds()
        }
        animatedRestoreSwipeRunnable = AnimatedRestoreSwipeRunnable(rect!!, close, velocity)
        post(animatedRestoreSwipeRunnable)
    }

    private inner class AnimatedScaleRunnable(
        private val scaleEnd: Float,
        private val focalX: Float,
        private val focalY: Float
    ) : Runnable {
        private val startTime: Long
        private val startTransformMatrix: Matrix
        private val scaleStart: Float

        init {
            startTime = SystemClock.elapsedRealtime()
            startTransformMatrix = Matrix(transformMatrix)
            scaleStart = this@PhotoView.scale
        }

        override fun run() {
            var t = (SystemClock.elapsedRealtime() - startTime).toFloat() / ZOOM_DURATION
            var post = true
            if (t >= 1f) {
                t = 1f
                post = false
            }
            t = AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR.getInterpolation(t)
            val scale = lerp(scaleStart, scaleEnd, t)
            val deltaScale = scale / scaleStart
            transformMatrix.set(startTransformMatrix)
            transformMatrix.postScale(deltaScale, deltaScale, focalX, focalY)
            checkMatrixBoundsAndInvalidate()
            if (post) {
                postOnAnimation(this)
            }
        }

        private val ZOOM_DURATION = 200
    }

    private inner class AnimatedRestoreSwipeRunnable(
        rect: RectF,
        finish: Boolean,
        velocity: Float
    ) : Runnable {
        private val startTime: Long
        private val deltaY: Float
        private val finish: Boolean
        private val duration: Int

        private var lastDeltaY = 0f

        init {
            val height = rect.height()
            val viewHeight = getHeight()
            var deltaY = 0f
            if (height <= viewHeight) {
                deltaY = (viewHeight - height) / 2 - rect.top
            } else if (rect.top > 0) {
                deltaY = -rect.top
            } else if (rect.bottom < viewHeight) {
                deltaY = viewHeight - rect.bottom
            }
            if (finish) {
                // Fling image out of screen
                if (deltaY > 0f) {
                    deltaY = -rect.bottom
                } else {
                    deltaY = viewHeight - rect.top
                }
            }
            startTime = SystemClock.elapsedRealtime()
            this.deltaY = deltaY
            this.finish = finish
            if (finish) {
                val duration = abs(deltaY / velocity * 1000f).toInt()
                this.duration = max(min(duration, FINISH_DURATION_MAX), FINISH_DURATION_MIN)
            } else {
                duration = RESTORE_DURATION
            }
        }

        override fun run() {
            var t = (SystemClock.elapsedRealtime() - startTime).toFloat() / duration
            var post = true
            if (t >= 1f) {
                t = 1f
                post = false
            }
            if (!finish) {
                t = AnimationUtils.DECELERATE_INTERPOLATOR.getInterpolation(t)
            }
            val deltaY = lerp(0f, this.deltaY, t)
            val dy = deltaY - lastDeltaY
            lastDeltaY = deltaY
            transformMatrix.postTranslate(0f, dy)
            if (post) {
                invalidate()
                postOnAnimation(this)
                if (!finish) {
                    notifyVerticalSwipe(this.deltaY - deltaY, true)
                }
            } else {
                checkMatrixBoundsAndInvalidate()
                if (!finish) {
                    notifyVerticalSwipe(0f, true)
                }
            }
        }

        private val RESTORE_DURATION = 150
        private val FINISH_DURATION_MAX = 500
        private val FINISH_DURATION_MIN = RESTORE_DURATION
    }

    private inner class FlingRunnable(context: Context?) : Runnable {
        private val scroller: Scroller
        private var currentX = 0
        private var currentY = 0

        init {
            scroller = Scroller(context)
        }

        fun cancelFling() {
            scroller.forceFinished(true)
        }

        fun fling(viewWidth: Int, viewHeight: Int, velocityX: Int, velocityY: Int) {
            val rect = checkMatrixBounds()
            if (rect == null) {
                return
            }
            val startX = Math.round(-rect.left)
            val minX: Int
            val maxX: Int
            val minY: Int
            val maxY: Int
            if (viewWidth < rect.width()) {
                minX = 0
                maxX = Math.round(rect.width() - viewWidth)
            } else {
                maxX = startX
                minX = maxX
            }
            val startY = Math.round(-rect.top)
            if (viewHeight < rect.height()) {
                minY = 0
                maxY = Math.round(rect.height() - viewHeight)
            } else {
                maxY = startY
                minY = maxY
            }
            currentX = startX
            currentY = startY
            if (startX != maxX || startY != maxY) {
                scroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY)
            }
        }

        override fun run() {
            if (scroller.isFinished()) {
                return
            }
            if (scroller.computeScrollOffset()) {
                val newX = scroller.getCurrX()
                val newY = scroller.getCurrY()
                transformMatrix.postTranslate(
                    (currentX - newX).toFloat(),
                    (currentY - newY).toFloat()
                )
                invalidate()
                currentX = newX
                currentY = newY
                postOnAnimation(this)
            }
        }
    }

    init {
        val configuration = ViewConfiguration.get(context)
        minimumVelocity = configuration.getScaledMinimumFlingVelocity().toFloat()
        touchSlop = configuration.getScaledTouchSlop().toFloat()
        gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                return this@PhotoView.onDoubleTapEvent(e)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return this@PhotoView.isAttachedToWindow() && this@PhotoView.onSingleTapConfirmed(e)
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDragging && !isParentDragging && this@PhotoView.isAttachedToWindow()) {
                    this@PhotoView.onLongPress(e)
                }
            }
        })
        scaleGestureDetector = ScaleGestureDetector(getContext(), this)
        tile = TransparentTileDrawable(context, true)
        initBaseMatrix(false)
    }

    private inner class InitialScaleListener(
        private val centerX: Float,
        private val centerY: Float,
        private val viewWidth: Int,
        private val viewHeight: Int,
        private val cropEnabled: Boolean
    ) : AnimatorUpdateListener {
        override fun onAnimationUpdate(animation: ValueAnimator) {
            baseMatrix.getValues(matrixValues)
            val baseScale = matrixValues[Matrix.MSCALE_Y]
            val dimensions: Point? = this@PhotoView.dimensions
            if (dimensions == null) {
                return
            }
            var scale = if ((dimensions.x * viewHeight > dimensions.y * viewWidth) == cropEnabled)
                viewHeight.toFloat() / dimensions.y
            else
                viewWidth.toFloat() / dimensions.x
            scale /= baseScale

            var t = animation.getAnimatedValue() as Float
            var finished = false
            if (t >= 1f) {
                t = 1f
                finished = true
            }
            val wait: Float = WAIT_TIME.toFloat() / INITIAL_SCALE_TRANSITION_TIME
            t = if (t >= wait) AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR
                .getInterpolation((t - wait) / (1f - wait)) else 0f

            if (!finished) {
                val ct = t.toDouble().pow(TRANSFER_TIME_FACTOR.toDouble())
                    .toFloat() // Make XY transition faster
                val targetX = lerp(centerX, getWidth() / 2f, ct)
                val targetY = lerp(centerY, getHeight() / 2f, ct)
                val targetScale = lerp(scale, initialScale, t)
                val dx = -getWidth() / 2f + targetX
                val dy = -getHeight() / 2f + targetY
                transformMatrix.reset()
                transformMatrix.postTranslate(dx, dy)
                transformMatrix.postScale(targetScale, targetScale, targetX, targetY)
                if (cropEnabled) {
                    if (initialScaleClipRect == null) {
                        initialScaleClipRect = Rect()
                    }
                    val sizeXY = lerp(
                        min(dimensions.x, dimensions.y).toFloat(),
                        max(dimensions.x, dimensions.y).toFloat(), t
                    ).toInt()
                    val scaledHalfSize = targetScale * baseScale * sizeXY / 2f
                    initialScaleClipRect!!.set(
                        (targetX - scaledHalfSize - 0.5f).toInt(),
                        ((targetY - scaledHalfSize
                                - 0.5f)).toInt(),
                        (targetX + scaledHalfSize + 0.5f).toInt(),
                        (targetY + scaledHalfSize + 0.5f).toInt()
                    )
                }
                invalidate()
            } else {
                initialScalingAnimator = null
                initialScaleClipRect = null
                this@PhotoView.scale = initialScale
            }
        }

        private val WAIT_TIME = 100
        private val TRANSFER_TIME_FACTOR = 1.5f
    }

    private fun getActiveX(event: MotionEvent): Float {
        try {
            return event.getX(activePointerIndex)
        } catch (e: Exception) {
            return event.getX()
        }
    }

    private fun getActiveY(event: MotionEvent): Float {
        try {
            return event.getY(activePointerIndex)
        } catch (e: Exception) {
            return event.getY()
        }
    }

    private fun onCommonTouchEvent(event: MotionEvent): Boolean {
        when (event.getActionMasked()) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                activePointerId = INVALID_POINTER_ID
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = (event.getAction() and MotionEvent.ACTION_POINTER_INDEX_MASK) shr
                        MotionEvent.ACTION_POINTER_INDEX_SHIFT
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newPointerIndex)
                    lastTouchX = event.getX(newPointerIndex)
                    lastTouchY = event.getY(newPointerIndex)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                checkTouchMode()
            }
        }
        activePointerIndex =
            event.findPointerIndex(if (activePointerId != INVALID_POINTER_ID) activePointerId else 0)
        when (event.getAction()) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker = VelocityTracker.obtain()
                if (velocityTracker != null) {
                    velocityTracker!!.addMovement(event)
                }
                lastTouchX = getActiveX(event)
                lastTouchY = getActiveY(event)
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val x = getActiveX(event)
                val y = getActiveY(event)
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (!isDragging) {
                    isDragging = length >= touchSlop
                }
                if (isDragging) {
                    if (touchMode == TouchMode.UNDEFINED) {
                        val allowClosing =
                            scrollEdgeY == ScrollEdge.BOTH || scrollEdgeY == ScrollEdge.START && dy > 0 || scrollEdgeY == ScrollEdge.END && dy < 0
                        var closing = false
                        if (allowClosing) {
                            val angle =
                                (acos(abs(dx / length).toDouble()) * 180f / Math.PI).toFloat()
                            closing = angle >= 60
                        }
                        if (closing) {
                            when (scrollEdgeY) {
                                ScrollEdge.NONE -> {
                                    throw RuntimeException()
                                }

                                ScrollEdge.START -> {
                                    touchMode = TouchMode.CLOSING_START
                                }

                                ScrollEdge.END -> {
                                    touchMode = TouchMode.CLOSING_END
                                }

                                ScrollEdge.BOTH -> {
                                    touchMode = TouchMode.CLOSING_BOTH
                                }
                            }
                        } else {
                            touchMode = TouchMode.COMMON
                        }
                    }
                    if (!scaleGestureDetector.isInProgress()) {
                        if (this.isClosingTouchMode) {
                            transformMatrix.postTranslate(0f, dy * CLOSE_SWIPE_FACTOR)
                        } else {
                            transformMatrix.postTranslate(dx, dy)
                        }
                        checkMatrixBoundsAndInvalidate()
                    }
                    lastTouchX = x
                    lastTouchY = y
                    if (velocityTracker != null) {
                        velocityTracker!!.addMovement(event)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (velocityTracker != null) {
                    velocityTracker!!.recycle()
                    velocityTracker = null
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    if (this.isClosingTouchMode) {
                        val rect = checkMatrixBounds()
                        if (rect != null) {
                            val viewHeight = getHeight()
                            var threshold: Float = viewHeight * CLOSE_SWIPE_FACTOR / 2f
                            val shift = getClosingTouchModeShift(rect)
                            var velocity = 0f
                            if (velocityTracker != null) {
                                velocityTracker!!.addMovement(event)
                                velocityTracker!!.computeCurrentVelocity(1000)
                                velocity = velocityTracker!!.getYVelocity() * CLOSE_SWIPE_FACTOR
                                val increase = shift > 0 == velocity > 0
                                velocity = abs(velocity)
                                if (increase) {
                                    threshold *= 1f - min(velocity / 1000f, 0.9f)
                                } else {
                                    threshold *= max(velocity / 1000f, 1f)
                                }
                            }
                            var close = abs(shift) >= threshold
                            if (listener != null && close) {
                                close = listener!!.onClose(this, shift >= 0)
                            }
                            startRestoreVerticalSwipe(rect, close, velocity)
                        }
                    } else if (velocityTracker != null) {
                        lastTouchX = getActiveX(event)
                        lastTouchY = getActiveY(event)
                        velocityTracker!!.addMovement(event)
                        velocityTracker!!.computeCurrentVelocity(1000)
                        val vX = velocityTracker!!.getXVelocity()
                        val vY = velocityTracker!!.getYVelocity()
                        if (max(abs(vX), abs(vY)) >= minimumVelocity) {
                            flingRunnable = FlingRunnable(getContext())
                            flingRunnable!!.fling(getWidth(), getHeight(), -vX.toInt(), -vY.toInt())
                            post(flingRunnable)
                        }
                    }
                }
                if (velocityTracker != null) {
                    velocityTracker!!.recycle()
                    velocityTracker = null
                }
            }
        }
        return true
    }

    companion object {
        private const val CLOSE_SWIPE_FACTOR = 0.25f

        private val INVALID_POINTER_ID = -1

        const val INITIAL_SCALE_TRANSITION_TIME: Int = 400
    }
}
