package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.min

open class CodeEditText : SafePasteEditText {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    private val requestRect = Rect()

    private val visibleTextHeight: Int
        get() = height - extendedPaddingTop - extendedPaddingBottom

    private val contextHeight: Int
        get() = CONTEXT_LINES * lineHeight

    private val isTextScrollable: Boolean
        get() = (layout?.height ?: 0) > visibleTextHeight

    private var isKeyboardOpen = false
    private var isExpanded = false

    private val layoutListener =
        android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (isExpanded) {
                val parentGroup = parent as? ViewGroup ?: return@OnGlobalLayoutListener
                val scrollView = parentGroup.parent as? ScrollView ?: return@OnGlobalLayoutListener
                val availableHeight = scrollView.height - parentGroup.paddingTop - parentGroup.paddingBottom

                if (availableHeight > 0 && layoutParams.height != availableHeight) {
                    if (layoutParams.height == resources.displayMetrics.heightPixels) {
                        layoutParams.height = availableHeight
                        requestLayout()
                    }
                }
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnApplyWindowInsetsListener { _, insets ->
            isKeyboardOpen = insets.isVisible(WindowInsets.Type.ime())
            updateExpansionState()

            if (isExpanded) {
                layoutParams.height = resources.displayMetrics.heightPixels
                requestLayout()
            }

            insets
        }
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        updateExpansionState()
    }

    var onExpandedStateChanged: ((Boolean) -> Unit)? = null

    private fun updateExpansionState() {
        val shouldExpand = isKeyboardOpen && isFocused
        if (isExpanded == shouldExpand) return
        isExpanded = shouldExpand

        val parentGroup = parent as? ViewGroup ?: return
        for (i in 0 until parentGroup.childCount) {
            val child = parentGroup.getChildAt(i)
            if (child != this) {
                child.visibility = if (shouldExpand) View.GONE else View.VISIBLE
            }
        }

        val lp = layoutParams
        if (shouldExpand) {
            lp.height = resources.displayMetrics.heightPixels
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        layoutParams = lp

        onExpandedStateChanged?.invoke(shouldExpand)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!isExpanded && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val limit = (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()
            if (measuredHeight > limit) {
                setMeasuredDimension(measuredWidthAndState, limit)
            }
        }
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        val changed = super.bringPointIntoView(offset)
        return keepCaretContextVisible(offset) || changed
    }

    override fun bringPointIntoView(
        offset: Int,
        requestRectangleVisible: Boolean,
    ): Boolean {
        val changed = super.bringPointIntoView(offset, requestRectangleVisible)
        return keepCaretContextVisible(offset) || changed
    }

    override fun getFocusedRect(r: Rect) {
        super.getFocusedRect(r)
        addContextBelow(r)
    }

    override fun requestRectangleOnScreen(
        rectangle: Rect,
        immediate: Boolean,
    ): Boolean {
        requestRect.set(rectangle)
        addContextBelow(requestRect)
        return super.requestRectangleOnScreen(requestRect, immediate)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTextScrollable) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun addContextBelow(rect: Rect) {
        val room = height - rect.bottom
        if (room > 0) {
            rect.bottom += min(room, contextHeight)
        }
    }

    private fun keepCaretContextVisible(offset: Int): Boolean {
        val layout = layout ?: return false
        val visibleHeight = visibleTextHeight
        if (visibleHeight <= 0) {
            return false
        }
        val line = layout.getLineForOffset(offset)
        val contextBottom = layout.getLineBottom(min(line + CONTEXT_LINES, layout.lineCount - 1))
        val contextTop = layout.getLineTop(max(line - CONTEXT_LINES, 0))
        var scroll = scrollY
        if (contextBottom - scroll > visibleHeight) {
            scroll = contextBottom - visibleHeight
        }
        if (contextTop < scroll) {
            scroll = contextTop
        }
        val caretTop = layout.getLineTop(line)
        val caretMinScroll = layout.getLineBottom(line) - visibleHeight
        scroll = if (caretMinScroll <= caretTop) scroll.coerceIn(caretMinScroll, caretTop) else caretTop
        scroll = scroll.coerceIn(0, max(layout.height - visibleHeight, 0))
        if (scroll == scrollY) {
            return false
        }
        scrollTo(scrollX, scroll)
        return true
    }

    companion object {
        private const val CONTEXT_LINES = 3
        private const val MAX_HEIGHT_FRACTION = 0.3f
    }
}
