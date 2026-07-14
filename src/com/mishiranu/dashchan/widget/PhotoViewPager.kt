package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.OverScroller
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@SuppressLint("ViewConstructor")
class PhotoViewPager(
    context: Context,
    adapter: Adapter,
) : ViewGroup(context) {
    private val flingDistance: Int
    private val minimumVelocity: Int
    private val maximumVelocity: Int
    private val touchSlop: Int

    private val scroller: OverScroller
    private val edgeEffect: EdgeEffect

    private val adapter: Adapter
    private val photoViews = ArrayList<PhotoView>(3)

    private var active = true
    private var innerPadding = 0

    interface Adapter {
        fun onCreateView(parent: ViewGroup?): View

        fun getPhotoView(view: View): PhotoView?

        fun onPositionChange(
            view: PhotoViewPager?,
            index: Int,
            centerView: View,
            leftView: View?,
            rightView: View?,
            manually: Boolean,
        )

        fun onSwipingStateChange(
            view: PhotoViewPager?,
            swiping: Boolean,
        )
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun addView(
        child: View?,
        index: Int,
        params: LayoutParams?,
    ): Unit = throw UnsupportedOperationException()

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val childWidthMeasureSpec =
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY,
            )
        val childHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec),
                MeasureSpec.EXACTLY,
            )
        for (i in 0..<getChildCount()) {
            getChildAt(i).measure(childWidthMeasureSpec, childHeightMeasureSpec)
        }
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val width = r - l
        val height = b - t
        val start = currentIndex * (width + innerPadding)
        scrollTo(start, 0)
        val current = ((start + width / 2f) / (width + innerPadding)).toInt()
        var left = (current - 1) * (width + innerPadding)
        for (i in current - 1..<current + 2) {
            getChildAt((i + 3) % 3).layout(left, 0, left + width, height)
            left += width + innerPadding
        }
    }

    fun setActive(active: Boolean) {
        this.active = active
    }

    fun setInnerPadding(padding: Int) {
        innerPadding = padding
        requestLayout()
    }

    fun setCount(count: Int) {
        if (count > 0) {
            this.count = count
            if (currentIndex >= count) {
                currentIndex = count - 1
            }
            requestLayout()
        }
    }

    fun getCount(): Int = count

    fun setCurrentIndex(index: Int) {
        if (index >= 0 && index < count) {
            currentIndex = index
            updateCurrentScrollIndex(true)
        }
    }

    fun getCurrentIndex(): Int = currentIndex

    val currentView: View?
        get() = getChildAt(currentIndex % 3)

    private fun updateCurrentScrollIndex(manually: Boolean) {
        queueScrollFinish = false
        scroller.abortAnimation()
        onScrollFinish(manually)
    }

    private fun onScrollFinish(manually: Boolean) {
        requestLayout()
        notifySwiping(false)
        if (previousIndex != currentIndex || manually) {
            val centerIndex = currentIndex % 3
            val leftIndex = (currentIndex + 2) % 3
            val rightIndex = (currentIndex + 1) % 3
            val hasLeft = currentIndex > 0
            val hasRight = currentIndex < count - 1
            val centerView = getChildAt(centerIndex)
            val leftView = if (hasLeft) getChildAt(leftIndex) else null
            val rightView = if (hasRight) getChildAt(rightIndex) else null
            adapter.onPositionChange(this, currentIndex, centerView, leftView, rightView, manually)
            previousIndex = currentIndex
        }
    }

    private var count = 1
    private var currentIndex = 0
    private var previousIndex = -1

    private var allowMove = false
    private var lastEventToPhotoView = false

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var startScrollX = 0
    private var longTapConfirmed = false

    private var velocityTracker: VelocityTracker? = null

    private val longTapRunnable =
        Runnable {
            longTapConfirmed = true
            val photoView = photoViews[currentIndex % 3]
            photoView.dispatchSimpleClick(true, startX, startY)
        }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) {
            return false
        }
        val photoView = photoViews[currentIndex % 3]
        val action = event.getActionMasked()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                photoView.dispatchSpecialTouchEvent(event)
                allowMove = false
                lastEventToPhotoView = true
                updateCurrentScrollIndex(false)
                lastX = event.getX()
                startX = lastX
                startY = event.getY()
                startScrollX = getScrollX()
                longTapConfirmed = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker!!.addMovement(event)
                postDelayed(longTapRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.getX()
                val y = event.getY()
                var previousX = x
                var previousY = y
                var previousValid = false
                val singlePointer = event.getPointerCount() == 1
                if (event.getHistorySize() > 0) {
                    previousX = event.getHistoricalX(0)
                    previousY = event.getHistoricalY(0)
                    // Don't track repeating movements
                    if (previousX == x && previousY == y && singlePointer) {
                        return true
                    }
                    previousValid = true
                }
                if (!allowMove) {
                    if (abs(event.getX() - startX) <= touchSlop &&
                        abs(event.getY() - startY) <= touchSlop
                    ) {
                        // Too short movement, skip it
                        return true
                    }
                    removeCallbacks(longTapRunnable)
                    allowMove = true
                }
                var sendToPhotoView = count == 1 || photoView.isScaling
                val currentScrollX = getScrollX()
                // Scrolling right means scroll to right view (so finger moves left)
                val scrollingRight = x < lastX
                val canScrollLeft = photoView.canScrollLeft()
                val canScrollRight = photoView.canScrollRight()
                val canScrollPhotoView =
                    canScrollLeft && !scrollingRight || canScrollRight && scrollingRight
                val canScalePhotoView = !singlePointer
                if (startScrollX == currentScrollX) {
                    if (!sendToPhotoView) {
                        if (canScrollPhotoView || canScalePhotoView) {
                            sendToPhotoView = true
                        } else if (previousValid) {
                            val vX = x - previousX
                            val vY = y - previousY
                            val length = sqrt((vX * vX + vY * vY).toDouble()).toFloat()
                            val angle =
                                (acos(abs(vX / length).toDouble()) * 180f / Math.PI).toFloat()
                            // angle >= 30 means vertical movement (angle is from 0 to 90)
                            if (angle >= 30f) {
                                sendToPhotoView = true
                            }
                        }
                    }
                }
                if (sendToPhotoView) {
                    startX += x - lastX
                    photoView.dispatchSpecialTouchEvent(event)
                    notifySwiping(false)
                } else {
                    val width = getWidth()
                    val deltaX = (startX - x).toInt()
                    val desiredScroll = startScrollX + deltaX
                    var actualScroll =
                        max(0, min((count - 1) * (width + innerPadding), desiredScroll))
                    val canFocusPhotoView =
                        currentScrollX < startScrollX &&
                            actualScroll >= startScrollX ||
                            currentScrollX > startScrollX &&
                            actualScroll <= startScrollX
                    if (canFocusPhotoView && canScrollPhotoView) {
                        // Fix scrolling to make PhotoView fill PhotoViewPager
                        // to ensure sendToPhotoView = true on next touch event
                        startX -= (actualScroll - startScrollX).toFloat()
                        actualScroll = startScrollX
                    }
                    if (desiredScroll > actualScroll) {
                        if (!scrollingRight) {
                            startX = x
                        }
                        edgeEffect.onPull((desiredScroll - actualScroll).toFloat() / width)
                        invalidate()
                    } else if (desiredScroll < actualScroll) {
                        if (scrollingRight) {
                            startX = x
                        }
                        edgeEffect.onPull((actualScroll - desiredScroll).toFloat() / width)
                        invalidate()
                    } else {
                        notifySwiping(true)
                    }
                    scrollTo(actualScroll, 0)
                    velocityTracker!!.addMovement(event)
                    photoView.dispatchIdleMoveTouchEvent(event)
                }
                lastEventToPhotoView = sendToPhotoView
                lastX = x
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (lastEventToPhotoView || action == MotionEvent.ACTION_CANCEL) {
                    photoView.dispatchSpecialTouchEvent(event)
                } else {
                    val fakeEvent = MotionEvent.obtain(event)
                    fakeEvent.setAction(MotionEvent.ACTION_CANCEL)
                    photoView.dispatchSpecialTouchEvent(fakeEvent)
                    fakeEvent.recycle()
                }
                var index = currentIndex
                var velocity = 0
                if (action == MotionEvent.ACTION_UP) {
                    val deltaX = (startX - event.getX()).toInt()
                    velocityTracker!!.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                    velocity = velocityTracker!!.getXVelocity(0).toInt()
                    index = determineTargetIndex(velocity, deltaX)
                    if (!allowMove && !longTapConfirmed) {
                        photoView.dispatchSimpleClick(false, event.getX(), event.getY())
                    }
                }
                velocityTracker!!.recycle()
                velocityTracker = null
                removeCallbacks(longTapRunnable)
                smoothScrollTo(index, velocity)
                currentIndex = index
                edgeEffect.onRelease()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                run {
                    // Replace active pointer
                    if (event.getActionIndex() == 0) {
                        val deltaX = event.getX(1) - event.getX(0)
                        val deltaY = event.getY(1) - event.getY(0)
                        startX += deltaX
                        startY += deltaY
                        lastX += deltaX
                    }
                }
                run {
                    photoView.dispatchSpecialTouchEvent(event)
                    return true
                }
            }

            else -> {
                photoView.dispatchSpecialTouchEvent(event)
                return true
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!edgeEffect.isFinished() && (currentIndex == 0 || currentIndex == count - 1)) {
            val width = getWidth()
            val height = getHeight()
            canvas.save()
            if (currentIndex == 0) {
                canvas.rotate(270f)
                canvas.translate(-height.toFloat(), 0f)
            } else {
                canvas.rotate(90f)
                canvas.translate(0f, -((count - 1) * (width + innerPadding) + width).toFloat())
            }
            edgeEffect.setSize(height, width)
            val invalidate = edgeEffect.draw(canvas)
            canvas.restore()
            if (invalidate) {
                invalidate()
            }
        }
    }

    private fun determineTargetIndex(
        velocity: Int,
        deltaX: Int,
    ): Int {
        val index = currentIndex
        val targetIndex: Int
        if (abs(deltaX) > flingDistance && abs(velocity) > minimumVelocity) {
            // First condition to ensure not scrolling through 2 pages (from 4.8 to 3, for example)
            targetIndex =
                if (deltaX * velocity > 0) {
                    index
                } else if (velocity > 0) {
                    index - 1
                } else {
                    index + 1
                }
        } else {
            targetIndex = (index + deltaX.toFloat() / getWidth() + 0.5f).toInt()
        }
        return max(0, min(count - 1, targetIndex))
    }

    private fun smoothScrollTo(
        index: Int,
        velocity: Int,
    ) {
        val startX = getScrollX()
        val endX = index * (getWidth() + innerPadding)
        val deltaX = endX - startX
        if (startX != endX) {
            var duration: Int
            if (abs(velocity) > minimumVelocity) {
                duration = 4 * Math.round(1000 * abs(deltaX.toFloat() / velocity))
            } else {
                val pageDelta = abs(deltaX).toFloat() / getWidth()
                duration = ((pageDelta + 1) * 200).toInt()
            }
            duration = min(duration, MAX_SETTLE_DURATION)
            queueScrollFinish = true
            notifySwiping(true)
            scroller.startScroll(startX, 0, deltaX, 0, duration)
            invalidate()
        } else {
            onScrollFinish(false)
        }
    }

    private var swiping = false

    private fun notifySwiping(swiping: Boolean) {
        if (this.swiping != swiping) {
            this.swiping = swiping
            post(if (swiping) swipingStateRunnableTrue else swipingStateRunnableFalse)
        }
    }

    private val swipingStateRunnableTrue: Runnable = SwipingStateRunnable(true)
    private val swipingStateRunnableFalse: Runnable = SwipingStateRunnable(false)

    private inner class SwipingStateRunnable(
        private val swiping: Boolean,
    ) : Runnable {
        override fun run() {
            adapter.onSwipingStateChange(this@PhotoViewPager, swiping)
        }
    }

    private var queueScrollFinish = false

    init {
        setWillNotDraw(false)
        val density = obtainDensity(context)
        flingDistance = (24 * density).toInt()
        val configuration = ViewConfiguration.get(context)
        minimumVelocity = (MIN_FLING_VELOCITY * density).toInt()
        maximumVelocity = configuration.getScaledMaximumFlingVelocity()
        touchSlop = configuration.getScaledTouchSlop()
        scroller = OverScroller(context)
        edgeEffect = EdgeEffect(context)
        this.adapter = adapter
        for (i in 0..2) {
            val view = adapter.onCreateView(this)
            super.addView(view, -1, generateDefaultLayoutParams())
            photoViews.add(adapter.getPhotoView(view)!!)
        }
    }

    override fun computeScroll() {
        if (scroller.isFinished()) {
            if (queueScrollFinish) {
                queueScrollFinish = false
                onScrollFinish(false)
            }
        } else if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0)
            invalidate()
        }
    }

    companion object {
        private const val MAX_SETTLE_DURATION = 600
        private const val MIN_FLING_VELOCITY = 400
    }
}
