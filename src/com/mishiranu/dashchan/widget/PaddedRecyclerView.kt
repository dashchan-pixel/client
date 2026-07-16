package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Xml
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ListViewUtils.UnlimitedRecycledViewPool
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.getDrawable
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.isGestureNavigationOverlap
import com.mishiranu.dashchan.widget.EdgeEffectHandler.Companion.bind
import com.mishiranu.dashchan.widget.EdgeEffectHandler.Shift
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

open class PaddedRecyclerView :
    RecyclerView,
    Shift,
    PullableWrapper.Wrapped {
    private val edgeEffectHandlerField = bind(this, this)
    private var shift: Shift? = null
    private var pullableWrapper: PullableWrapper? = null

    private val thumbDrawable: Drawable
    private val trackDrawable: Drawable
    private val touchSlop: Int
    private val minTrackSize: Int

    private var fastScrollerEnabled = false
    private var fastScrollerAllowed = false
    private var regularScrolling = false
    private var fastScrolling = false

    private var fastScrollingDown = false
    private var fastScrollingStartOffset: Float? = null
    private var fastScrollingStartY = 0f
    private var fastScrollingCurrentY = 0f

    private var showFastScrollingStart: Long = 0
    private var showFastScrolling = false

    private var minRealThumbSize = 0
    private lateinit var realThumbDrawable: Drawable
    private var importantPostsMarksFastScrollBarDecoration: ImportantPostsMarksFastScrollBarDecoration? =
        null

    constructor(context: Context) : this(context, createDefaultAttributeSet(context)) {
        setVerticalScrollBarEnabled(false)
        setHorizontalScrollBarEnabled(false)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr,
    )

    // init
    init {
        val theme: ThemeEngine.Theme = ThemeEngine.Companion.getTheme(getContext())
        edgeEffectHandlerField.setColor(theme.accent)

        val density = obtainDensity(this)
        val thumbDrawable = getDrawable(getContext(), android.R.attr.fastScrollThumbDrawable, 0)!!
        this.thumbDrawable = thumbDrawable
        val states =
            arrayOf<IntArray?>(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_enabled),
            )
        val colors = intArrayOf(theme.accent, theme.controlNormal21)
        thumbDrawable.setTintList(ColorStateList(states, colors))

        trackDrawable = getDrawable(getContext(), android.R.attr.fastScrollTrackDrawable, 0)!!
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop()
        minTrackSize = (16f * density).toInt()

        setRecycledViewPool(UnlimitedRecycledViewPool())
        addOnScrollListener(
            object : OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    val regularScrolling = newState != SCROLL_STATE_IDLE
                    updateFastScroller(
                        false,
                        fastScrollerEnabled,
                        fastScrollerAllowed,
                        regularScrolling,
                        fastScrolling,
                    )
                }
            },
        )
        addOnItemTouchListener(
            object : OnItemTouchListener {
                override fun onInterceptTouchEvent(
                    rv: RecyclerView,
                    e: MotionEvent,
                ): Boolean = handleTouchEvent(e)

                override fun onTouchEvent(
                    rv: RecyclerView,
                    e: MotionEvent,
                ) {
                    handleTouchEvent(e)
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                    if (disallowIntercept) {
                        fastScrollingDown = false
                        if (fastScrolling) {
                            updateFastScroller(
                                true,
                                fastScrollerEnabled,
                                fastScrollerAllowed,
                                regularScrolling,
                                false,
                            )
                        }
                    }
                }
            },
        )
        addItemDecoration(
            object : ItemDecoration() {
                override fun onDrawOver(
                    c: Canvas,
                    parent: RecyclerView,
                    state: State,
                ) {
                    onDrawFastScroller(c)
                }
            },
        )
    }

    private fun initializeRealThumbDrawable() {
        val realThumbDrawableColor = getColor(getContext(), android.R.attr.textColorPrimaryInverse)
        val realThumbColorAlpha = Math.round(255 * 0.2f)
        realThumbDrawable =
            ColorDrawable(ColorUtils.setAlphaComponent(realThumbDrawableColor, realThumbColorAlpha))
    }

    fun setImportantPostsMarksFastScrollBarDecoration(
        importantPostsMarksFastScrollBarDecoration: ImportantPostsMarksFastScrollBarDecoration?,
    ) {
        this.importantPostsMarksFastScrollBarDecoration = importantPostsMarksFastScrollBarDecoration
        minRealThumbSize = Math.round(obtainDensity(this))
        initializeRealThumbDrawable()
    }

    fun setFastScrollerEnabled(fastScrollerEnabled: Boolean) {
        if (this.fastScrollerEnabled != fastScrollerEnabled) {
            fastScrollingDown = false
            updateFastScroller(
                true,
                fastScrollerEnabled,
                fastScrollerAllowed,
                regularScrolling,
                false,
            )
        }
    }

    private val isFastScrollerAvailable: Boolean
        get() = fastScrollerEnabled && fastScrollerAllowed

    @Suppress("unused") // Overrides hidden Android API protected method
    protected open fun onDrawVerticalScrollBar(
        canvas: Canvas,
        scrollBar: Drawable,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        var top = t
        var bottom = b
        if (!this.isFastScrollerAvailable) {
            if (bottom - top == getHeight()) {
                top += getEdgeEffectShift(EdgeEffectHandler.Side.TOP)
                bottom -= getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
            }
            scrollBar.setBounds(l, top, r, bottom)
            scrollBar.draw(canvas)
        }
    }

    fun setEdgeEffectShift(shift: Shift?) {
        this.shift = shift
    }

    override fun getEdgeEffectHandler(): EdgeEffectHandler = edgeEffectHandlerField

    override fun getEdgeEffectShift(side: EdgeEffectHandler.Side): Int = shift?.getEdgeEffectShift(side) ?: obtainEdgeEffectShift(side)

    fun obtainEdgeEffectShift(side: EdgeEffectHandler.Side?): Int =
        if (getClipToPadding()) {
            0
        } else if (side == EdgeEffectHandler.Side.TOP) {
            getPaddingTop()
        } else {
            getPaddingBottom()
        }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)

        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        val allowFastScrolling = extent > 0 && range >= 2 * extent
        updateFastScroller(
            false,
            fastScrollerEnabled,
            allowFastScrolling,
            regularScrolling,
            fastScrolling,
        )
        // OVER_SCROLL_IF_CONTENT_SCROLLS it not supported, see https://issuetracker.google.com/issues/37076456
        setOverScrollMode(if (range > extent) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (!hasWindowFocus) {
            fastScrollingDown = false
            if (fastScrolling) {
                updateFastScroller(
                    true,
                    fastScrollerEnabled,
                    fastScrollerAllowed,
                    regularScrolling,
                    false,
                )
            }
        }
    }

    private val invalidateRunnable = Runnable { this.invalidate() }

    private fun updateFastScroller(
        immediately: Boolean,
        fastScrollerEnabled: Boolean,
        fastScrollerAllowed: Boolean,
        regularScrolling: Boolean,
        fastScrolling: Boolean,
    ) {
        val oldShow =
            this.fastScrollerAllowed &&
                this.fastScrollerEnabled &&
                (this.regularScrolling || this.fastScrolling)
        val newShow =
            fastScrollerAllowed &&
                fastScrollerEnabled &&
                (regularScrolling || fastScrolling)
        this.fastScrollerEnabled = fastScrollerEnabled
        this.fastScrollerAllowed = fastScrollerAllowed
        this.regularScrolling = regularScrolling
        this.fastScrolling = fastScrolling
        val time = SystemClock.elapsedRealtime()
        val passed = time - showFastScrollingStart
        if (oldShow != newShow) {
            removeCallbacks(invalidateRunnable)
            val start: Long
            if (newShow && passed < FAST_SCROLLER_TRANSITION_OUT + FAST_SCROLLER_TRANSITION_OUT_DELAY) {
                start =
                    if (passed <= FAST_SCROLLER_TRANSITION_OUT_DELAY) {
                        0L
                    } else {
                        time -
                            (
                                (
                                    FAST_SCROLLER_TRANSITION_OUT_DELAY +
                                        FAST_SCROLLER_TRANSITION_OUT - passed
                                ).toFloat() /
                                    FAST_SCROLLER_TRANSITION_OUT * FAST_SCROLLER_TRANSITION_IN
                            ).toLong()
                    }
            } else if (!newShow && passed < FAST_SCROLLER_TRANSITION_IN) {
                if (immediately) {
                    start = time -
                        (
                            (FAST_SCROLLER_TRANSITION_IN - passed).toFloat() /
                                FAST_SCROLLER_TRANSITION_IN * FAST_SCROLLER_TRANSITION_OUT
                        ).toLong() -
                        FAST_SCROLLER_TRANSITION_IN - FAST_SCROLLER_TRANSITION_OUT_DELAY
                } else {
                    start = time - passed
                    postDelayed(
                        invalidateRunnable,
                        FAST_SCROLLER_TRANSITION_IN - passed +
                            FAST_SCROLLER_TRANSITION_OUT_DELAY,
                    )
                }
            } else {
                if (!newShow) {
                    postDelayed(invalidateRunnable, FAST_SCROLLER_TRANSITION_OUT_DELAY)
                }
                start = if (newShow) time else time - FAST_SCROLLER_TRANSITION_IN
            }
            showFastScrollingStart = start
            showFastScrolling = newShow
            invalidate()
        } else if (!this.isFastScrollerAvailable && passed < FAST_SCROLLER_TRANSITION_OUT_DELAY) {
            removeCallbacks(invalidateRunnable)
            showFastScrollingStart =
                time - FAST_SCROLLER_TRANSITION_IN - FAST_SCROLLER_TRANSITION_OUT_DELAY
            invalidate()
        }
    }

    private fun calculateOffset(): Float {
        val result: Float
        val height =
            getHeight() - getEdgeEffectShift(EdgeEffectHandler.Side.TOP) -
                getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
        val fastScrollingStartOffset = this.fastScrollingStartOffset
        if (fastScrollingStartOffset != null) {
            result = fastScrollingStartOffset + (fastScrollingCurrentY - fastScrollingStartY) /
                (height - thumbDrawable.getIntrinsicHeight())
        } else {
            result = (fastScrollingCurrentY - thumbDrawable.getIntrinsicHeight() / 2f) /
                (height - thumbDrawable.getIntrinsicHeight())
        }
        return max(0f, min(result, 1f))
    }

    private val currentOffset: Float
        get() {
            val offset = computeVerticalScrollOffset()
            val range = computeVerticalScrollRange() - computeVerticalScrollExtent()
            return max(
                0f,
                min(if (range > 0) offset.toFloat() / range else 0f, 1f),
            )
        }

    private fun scroll(offset: Float) {
        val layoutManager = getLayoutManager() as LinearLayoutManager?
        val count = layoutManager!!.getItemCount()
        if (count > 0) {
            if (offset < 1f) {
                val first = layoutManager.findFirstCompletelyVisibleItemPosition()
                val last = layoutManager.findLastCompletelyVisibleItemPosition()
                val childCount: Int
                if (first >= 0 && last >= first) {
                    childCount = last - first + 1
                } else {
                    childCount = getChildCount()
                }
                val position = ((count - childCount) * offset + 0.5f).toInt()
                layoutManager.scrollToPositionWithOffset(position, 0)
            } else {
                scrollToPosition(count - 1)
            }
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val action = event.getActionMasked()
        val top = getEdgeEffectShift(EdgeEffectHandler.Side.TOP)
        val currentY = event.getY() - top
        val fastScrollerAvailable = this.isFastScrollerAvailable
        if (action == MotionEvent.ACTION_DOWN) {
            fastScrollingDown = false
            if (!fastScrollerAvailable) {
                return false
            }
            val rtl = this.getLayoutDirection() == LAYOUT_DIRECTION_RTL
            val trackWidth =
                max(
                    minTrackSize,
                    max(
                        thumbDrawable.getIntrinsicWidth(),
                        trackDrawable.getIntrinsicWidth(),
                    ),
                )
            val atThumbVertical =
                if (rtl) event.getX() <= trackWidth else event.getX() >= getWidth() - trackWidth
            if (atThumbVertical) {
                val height = getHeight() - top - getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
                val offset = this.currentOffset
                val thumbHeight = thumbDrawable.getIntrinsicHeight()
                val thumbY = ((height - thumbHeight) * offset).toInt()
                val atThumb =
                    event.getY() >= top + thumbY && event.getY() <= top + thumbY + thumbHeight
                fastScrollingDown = true
                fastScrollingStartOffset = if (atThumb) offset else null
                fastScrollingStartY = currentY
                if (!isGestureNavigationOverlap(this, rtl, !rtl)) {
                    fastScrollingCurrentY = currentY
                    getParent().requestDisallowInterceptTouchEvent(true)
                    updateFastScroller(
                        false,
                        fastScrollerEnabled,
                        fastScrollerAllowed,
                        regularScrolling,
                        true,
                    )
                    if (!atThumb) {
                        scroll(calculateOffset())
                    }
                    return true
                }
            }
        } else if (fastScrollingDown) {
            val finish = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            if (!fastScrolling && fastScrollerAvailable) {
                var fastScrolling = false
                if (finish) {
                    fastScrolling = action != MotionEvent.ACTION_CANCEL
                } else if (abs(currentY - fastScrollingStartY) > touchSlop) {
                    getParent().requestDisallowInterceptTouchEvent(true)
                    fastScrolling = true
                }
                if (fastScrolling) {
                    updateFastScroller(
                        false,
                        fastScrollerEnabled,
                        fastScrollerAllowed,
                        regularScrolling,
                        true,
                    )
                    pullableWrapper?.onTouchEventOrNull(null)
                }
            }
            if (fastScrolling) {
                val cancel = !fastScrollerAvailable || finish
                if (fastScrollerAvailable) {
                    fastScrollingCurrentY = currentY
                    scroll(calculateOffset())
                }
                if (cancel) {
                    updateFastScroller(
                        false,
                        fastScrollerEnabled,
                        fastScrollerAllowed,
                        regularScrolling,
                        false,
                    )
                }
                return true
            }
        }
        return false
    }

    private fun onDrawFastScroller(canvas: Canvas) {
        val time = SystemClock.elapsedRealtime()
        var passed = time - showFastScrollingStart
        val shouldInvalidate: Boolean
        var stateValue: Float
        if (!showFastScrolling && passed >= FAST_SCROLLER_TRANSITION_IN) {
            passed -= FAST_SCROLLER_TRANSITION_IN
            shouldInvalidate = passed >= FAST_SCROLLER_TRANSITION_OUT_DELAY &&
                passed < FAST_SCROLLER_TRANSITION_OUT_DELAY + FAST_SCROLLER_TRANSITION_OUT
            stateValue =
                1f - (passed - FAST_SCROLLER_TRANSITION_OUT_DELAY).toFloat() / FAST_SCROLLER_TRANSITION_OUT
        } else {
            shouldInvalidate = true
            stateValue = passed.toFloat() / FAST_SCROLLER_TRANSITION_IN
        }
        stateValue = max(0f, min(stateValue, 1f))

        if (stateValue > 0f) {
            val rtl = this.getLayoutDirection() == LAYOUT_DIRECTION_RTL
            val maxWidth =
                max(thumbDrawable.getIntrinsicWidth(), trackDrawable.getIntrinsicHeight())
            val translateX = (maxWidth * (1f - stateValue) + 0.5f).toInt()
            val top = getEdgeEffectShift(EdgeEffectHandler.Side.TOP)
            val height = getHeight() - top - getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
            val offset = if (fastScrolling) calculateOffset() else this.currentOffset
            val thumbHeight = thumbDrawable.getIntrinsicHeight()
            var realThumbHeight = 0
            val verticalScrollRange = computeVerticalScrollRange()
            if (verticalScrollRange != 0) {
                realThumbHeight = max((height * height) / verticalScrollRange, minRealThumbSize)
            }
            val thumbBitmap = thumbDrawable.getCurrent() is BitmapDrawable
            val thumbY: Int

            val alignThumbCenterWithRealThumbCenter =
                !thumbBitmap && height != 0 && realThumbHeight != 0 && realThumbHeight <= thumbHeight
            if (alignThumbCenterWithRealThumbCenter) {
                val scrollPositionOnTrack = Math.round(height * offset)
                val scrollPercentToMid = scrollPositionOnTrack / (height / 2f)
                val realThumbCenter = realThumbHeight / 2
                val realThumbCenterOffset =
                    Math.round(realThumbCenter - (realThumbCenter * scrollPercentToMid))
                val thumbYMin = 0
                val thumbYMax = height - thumbHeight
                thumbY =
                    max(
                        thumbYMin,
                        min(
                            thumbYMax,
                            scrollPositionOnTrack - (thumbHeight / 2) + realThumbCenterOffset,
                        ),
                    )
            } else {
                thumbY = ((height - thumbHeight) * offset).toInt()
            }

            val trackExtra = (maxWidth - trackDrawable.getIntrinsicWidth()) / 2
            val trackLeft: Int
            val trackTop = top + (if (thumbBitmap) thumbHeight / 2 else 0)
            val trackRight: Int
            val trackBottom = top + height - (if (thumbBitmap) thumbHeight / 2 else 0)
            if (rtl) {
                trackLeft = trackExtra - translateX
                trackRight = trackExtra + trackDrawable.getIntrinsicWidth() - translateX
            } else {
                trackLeft =
                    getWidth() - trackExtra - trackDrawable.getIntrinsicWidth() + translateX
                trackRight = getWidth() - trackExtra + translateX
            }
            trackDrawable.setState(if (fastScrolling) STATE_PRESSED else STATE_NORMAL)
            trackDrawable.setBounds(trackLeft, trackTop, trackRight, trackBottom)
            trackDrawable.draw(canvas)

            val thumbExtra = (maxWidth - thumbDrawable.getIntrinsicWidth()) / 2
            thumbDrawable.setState(if (fastScrolling) STATE_PRESSED else STATE_NORMAL)
            if (rtl) {
                thumbDrawable.setBounds(
                    thumbExtra - translateX,
                    top + thumbY,
                    thumbExtra + thumbDrawable.getIntrinsicWidth() - translateX,
                    top + thumbY + thumbHeight,
                )
            } else {
                thumbDrawable.setBounds(
                    getWidth() - thumbExtra - thumbDrawable.getIntrinsicWidth() + translateX,
                    top + thumbY,
                    getWidth() - thumbExtra + translateX,
                    top + thumbY + thumbHeight,
                )
            }
            thumbDrawable.draw(canvas)

            val importantPostsMarksFastScrollBarDecoration = this.importantPostsMarksFastScrollBarDecoration
            if (importantPostsMarksFastScrollBarDecoration != null && importantPostsMarksFastScrollBarDecoration.hasMarks()) {
                importantPostsMarksFastScrollBarDecoration.draw(
                    trackLeft,
                    trackTop,
                    trackRight,
                    trackBottom,
                    canvas,
                )

                val drawRealThumb = realThumbHeight <= thumbHeight * 0.2
                if (drawRealThumb) {
                    val realThumbTop: Int
                    if (!thumbBitmap) {
                        realThumbTop = Math.round((height - realThumbHeight) * offset) + top
                    } else {
                        realThumbTop =
                            (thumbY + thumbDrawable.getIntrinsicHeight() / 2) - realThumbHeight / 2
                    }
                    val realThumbBottom = realThumbTop + realThumbHeight
                    realThumbDrawable.setBounds(
                        trackLeft,
                        realThumbTop,
                        trackRight,
                        realThumbBottom,
                    )
                    realThumbDrawable.draw(canvas)
                }
            }
        }

        if (shouldInvalidate) {
            invalidate()
        }
    }

    val pullable: PullableWrapper
        get() {
            pullableWrapper?.let { return it }
            val wrapper = PullableWrapper(this)
            this.pullableWrapper = wrapper
            addOnItemTouchListener(
                object : OnItemTouchListener {
                    private var intercepted = false
                    private var downY = 0f

                    override fun onInterceptTouchEvent(
                        rv: RecyclerView,
                        e: MotionEvent,
                    ): Boolean {
                        val y = e.getY()
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            intercepted = false
                            downY = y
                        }
                        if (wrapper.onTouchEventOrNull(e)) {
                            if (!intercepted && abs(downY - y) > touchSlop) {
                                intercepted = true
                            }
                            return intercepted
                        }
                        return false
                    }

                    override fun onTouchEvent(
                        rv: RecyclerView,
                        e: MotionEvent,
                    ) {
                        val result = wrapper.onTouchEventOrNull(e)
                        if (intercepted && !result) {
                            intercepted = false
                            // Reset intercepted state
                            removeOnItemTouchListener(this)
                            addOnItemTouchListener(this)
                        }
                    }

                    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                        if (disallowIntercept && intercepted) {
                            intercepted = false
                            wrapper.onTouchEventOrNull(null)
                        }
                    }
                },
            )
            return wrapper
        }

    override fun draw(canvas: Canvas) {
        val pullableWrapper = this.pullableWrapper
        if (pullableWrapper != null) {
            pullableWrapper.drawBefore(canvas)
            try {
                super.draw(canvas)
            } finally {
                pullableWrapper.drawAfter(canvas)
            }
        } else {
            super.draw(canvas)
        }
    }

    private val bounds = Rect()

    override fun isScrolledToTop(): Boolean {
        val bounds = this.bounds
        val view = if (getChildCount() > 0) getChildAt(0) else null
        if (view == null) {
            return true
        } else if (getChildLayoutPosition(view) == 0) {
            getDecoratedBoundsWithMargins(view, bounds)
            return bounds.top >= getPaddingTop()
        } else {
            return false
        }
    }

    override fun isScrolledToBottom(): Boolean {
        val bounds = this.bounds
        val childCount = getChildCount()
        val view = if (childCount > 0) getChildAt(childCount - 1) else null
        if (view == null) {
            return true
        } else if (getChildLayoutPosition(view) == getLayoutManager()!!.getItemCount() - 1) {
            getDecoratedBoundsWithMargins(view, bounds)
            return bounds.bottom <= getHeight() - getPaddingBottom()
        } else {
            return false
        }
    }

    companion object {
        private const val FAST_SCROLLER_TRANSITION_IN: Long = 100
        private const val FAST_SCROLLER_TRANSITION_OUT: Long = 200
        private const val FAST_SCROLLER_TRANSITION_OUT_DELAY: Long = 1000

        private fun createDefaultAttributeSet(context: Context): AttributeSet? {
            try {
                val parser: XmlPullParser = context.getResources().getXml(R.xml.scrollbars)
                parser.next()
                parser.nextTag()
                return Xml.asAttributeSet(parser)
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        private val STATE_PRESSED =
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
        private val STATE_NORMAL = intArrayOf(android.R.attr.state_enabled)
    }
}
