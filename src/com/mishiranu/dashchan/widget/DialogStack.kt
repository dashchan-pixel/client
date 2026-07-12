package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.Pair
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.GraphicsUtils.getCornerRadius
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.drawSystemInsetsOver
import com.mishiranu.dashchan.util.ViewUtils.removeFromParent
import com.mishiranu.dashchan.widget.DialogStack.DialogView.PopSelf
import com.mishiranu.dashchan.widget.InsetsLayout.Companion.isTargetGesture29
import com.mishiranu.dashchan.widget.ThemeEngine.OnOverlayFocusListener
import com.mishiranu.dashchan.widget.ThemeEngine.OnOverlayFocusListener.MutableItem
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

class DialogStack<T : DialogStack.ViewFactory<T?>?>(private val context: Context) :
    Iterable<Pair<T?, View?>?> {
    private val contentView: View
    private val rootView: DragLayout
    private val dialogAnimations: Int
    private val dialogDimAmount: Float
    private val dialogBackgroundResId: Int
    private val dialogElevation: Float

    private var currentActionMode: WeakReference<ActionMode?>? = null

    private val keyBackHandler: KeyBackHandler = RegularKeyBackHandler()

    private interface KeyBackHandler {
        fun onBackKey(event: KeyEvent, allowPop: Boolean): Boolean
    }

    private inner class RegularKeyBackHandler : KeyBackHandler {
        override fun onBackKey(event: KeyEvent, allowPop: Boolean): Boolean {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!event.isLongPress() && allowPop) {
                    popInternal()
                }
                return true
            } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.isLongPress()) {
                    clear()
                }
                return true
            }
            return false
        }
    }

    private val hiddenViews = LinkedList<T?>()
    private val visibleViews: LinkedList<Pair<T?, DialogView?>> =
        LinkedList<Pair<T?, DialogView?>>()
    private var dialog: Dialog? = null

    private val overlayFocusListener = OnOverlayFocusListener { stack: Iterable<MutableItem>? ->
        val decorView = if (dialog != null) dialog!!.getWindow()!!.getDecorView() else null
        var background = false
        if (decorView != null) {
            var foundSelf = false
            var isDimmedByOtherWindow = false
            for (mutableItem in stack!!) {
                if (!foundSelf) {
                    if (mutableItem.decorView === decorView) {
                        foundSelf = true
                    }
                } else {
                    if (mutableItem.indirect) {
                        // Ignore next windows with dim behind, e.g. gallery -> gallery dialog
                        break
                    } else {
                        isDimmedByOtherWindow = true
                    }
                }
            }
            background = isDimmedByOtherWindow
        }
        switchBackground(background)
    }

    init {
        val styledContext: Context = ContextThemeWrapper(
            context, getResourceId(
                context,
                android.R.attr.dialogTheme, 0
            )
        )
        ThemeEngine.Companion.addWeakOnOverlayFocusListener(context, overlayFocusListener)
        val contentView = InsetsLayout(context)
        this.contentView = contentView
        rootView = ContentView(context, DragLayout.Side.TOP, object : DragLayout.Callback {
            private val lastVisibleDialog: DialogView?
                get() = if (visibleViews.isEmpty()) null else visibleViews.getLast().second

            override val isScrolled: Boolean
                get() {
                    val dialogView = this.lastVisibleDialog
                    return dialogView != null && dialogView.isScrolledToTop
                }

            override fun onProposeShift(shift: Int, acceleration: Float): Boolean {
                val dialogView = this.lastVisibleDialog
                return dialogView != null && dialogView.handleShift(shift, acceleration)
            }

            override fun onFinished() {
                clear()
            }
        })
        contentView.addView(
            rootView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        contentView.setOnApplyInsetsTarget(rootView)
        rootView.setClipToPadding(false)
        rootView.setClipChildren(false)
        rootView.setOnClickListener(View.OnClickListener { v: View? ->
            if (!visibleViews.isEmpty()) {
                popInternal()
            }
        })
        val attrs = intArrayOf(
            android.R.attr.windowAnimationStyle, android.R.attr.backgroundDimAmount,
            android.R.attr.windowBackground, android.R.attr.windowElevation
        )
        val typedArray = styledContext.obtainStyledAttributes(attrs)
        try {
            dialogAnimations = typedArray.getResourceId(0, 0)
            dialogDimAmount = typedArray.getFloat(1, 0.6f)
            dialogBackgroundResId = typedArray.getResourceId(2, 0)
            dialogElevation = typedArray.getDimension(3, 0f)
        } finally {
            typedArray.recycle()
        }

        // Apply elevation to visible children only so their shadows didn't overlap each other too much
        rootView.addOnLayoutChangeListener(OnLayoutChangeListener { v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int ->
            var maxHeight = 0
            val iterator: MutableListIterator<Pair<T?, DialogView?>> =
                visibleViews.listIterator(visibleViews.size)
            while (iterator.hasPrevious()) {
                val pair = iterator.previous()
                val height = pair.second!!.getHeight()
                val taller = height > maxHeight
                if (taller) {
                    maxHeight = height
                }
                pair.second!!.setElevated(taller)
            }
        })
    }

    fun push(viewFactory: T?) {
        if (dialog == null) {
            val dialog: Dialog =
                object : Dialog(context, getResourceId(context, R.attr.overlayTheme, 0)) {
                    override fun onActionModeStarted(mode: ActionMode?) {
                        currentActionMode = WeakReference<ActionMode?>(mode)
                        super.onActionModeStarted(mode)
                    }

                    override fun onActionModeFinished(mode: ActionMode?) {
                        if (currentActionMode != null) {
                            if (currentActionMode!!.get() === mode) {
                                currentActionMode = null
                            }
                        }
                        super.onActionModeFinished(mode)
                    }

                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (getWindow()!!.superDispatchKeyEvent(event)) {
                            return true
                        }
                        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                            return keyBackHandler.onBackKey(event, !visibleViews.isEmpty())
                        }
                        return false
                    }

                    // With predictive back enabled, back gestures arrive here instead of
                    // dispatchKeyEvent. Pops a single dialog like the KeyEvent handler
                    // (the long-press-back "clear all" shortcut has no gesture equivalent).
                    private val backInvokedCallback = OnBackInvokedCallback {
                        if (!visibleViews.isEmpty()) {
                            popInternal()
                        }
                    }

                    override fun onStart() {
                        super.onStart()
                        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback
                        )
                    }

                    override fun onStop() {
                        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                            backInvokedCallback
                        )
                        super.onStop()
                    }
                }
            dialog.setContentView(contentView)
            dialog.setCancelable(false)
            rootView.resetDrag()
            val window = dialog.getWindow()
            val layoutParams = window!!.getAttributes()
            layoutParams.windowAnimations = dialogAnimations
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            layoutParams.dimAmount = dialogDimAmount
            // For hierarchy view (layout inspector)
            layoutParams.setTitle(context.getPackageName() + "/" + javaClass.getName())
            ViewUtils.setWindowLayoutFullscreen(window)

            dialog.show()
            this.dialog = dialog
        }
        if (currentActionMode != null) {
            val mode = currentActionMode!!.get()
            currentActionMode = null
            if (mode != null) {
                mode.finish()
            }
        }
        if (!visibleViews.isEmpty()) {
            visibleViews.getLast().second!!.setActive(false)
            if (visibleViews.size == VISIBLE_COUNT) {
                val first: Pair<T?, DialogView?> = visibleViews.removeFirst()
                first.first!!.destroyView(first.second!!.content, false)
                hiddenViews.add(first.first)
                rootView.removeView(first.second!!.container)
            }
        }
        val dialogView = addDialogView(viewFactory, rootView.getChildCount())
        dialogView.setAlpha(0f)
        dialogView.animate().alpha(1f).setDuration(100).start()
        visibleViews.add(Pair<T?, DialogView?>(viewFactory, dialogView))
        switchBackground(false)
    }

    fun addAll(viewFactories: MutableList<T?>) {
        if (!viewFactories.isEmpty()) {
            val hiddenTo: Int = viewFactories.size - VISIBLE_COUNT
            if (hiddenTo > 0) {
                for (pair in visibleViews) {
                    pair.first!!.destroyView(pair.second!!.content, false)
                    hiddenViews.add(pair.first)
                    rootView.removeView(pair.second!!.container)
                }
                hiddenViews.addAll(viewFactories.subList(0, hiddenTo))
            }
            for (viewFactory in viewFactories.subList(max(0, hiddenTo), viewFactories.size)) {
                push(viewFactory)
            }
        }
    }

    fun pop(): T? {
        return popInternal()
    }

    fun clear() {
        while (!visibleViews.isEmpty()) {
            popInternal()
        }
    }

    private fun switchBackground(background: Boolean) {
        for (pair in visibleViews) {
            pair.second!!.setBackground(background)
        }
    }

    private fun popInternal(): T? {
        if (hiddenViews.size > 0) {
            val index = rootView.indexOfChild(visibleViews.getFirst().second!!.container)
            val last = hiddenViews.removeLast()
            val dialogView = addDialogView(last, index)
            dialogView.setActive(false)
            visibleViews.addFirst(Pair<T?, DialogView?>(last, dialogView))
        }
        val last: Pair<T?, DialogView?> = visibleViews.removeLast()
        rootView.removeView(last.second!!.container)
        if (visibleViews.isEmpty()) {
            dialog!!.dismiss()
            dialog = null
            currentActionMode = null
            removeFromParent(contentView)
        } else {
            visibleViews.getLast().second!!.setActive(true)
        }
        last.first!!.destroyView(last.second!!.content, true)
        return last.first
    }

    private fun addDialogView(viewFactory: T?, index: Int): DialogView {
        val dialogView = DialogView(
            context,
            dialogBackgroundResId,
            dialogElevation,
            dialogDimAmount,
            viewFactory!!.createView(this),
            viewFactory,
            PopSelf { dialogView: DialogView? -> this.handlePopSelf(dialogView) })
        rootView.addView(
            dialogView.container,
            index,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return dialogView
    }

    private fun handlePopSelf(dialogView: DialogView?) {
        if (!visibleViews.isEmpty() && visibleViews.getLast().second === dialogView) {
            popInternal()
        }
    }

    private open class SimpleLayout(context: Context?) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val horizontal = getPaddingLeft() + getPaddingRight()
            val vertical = getPaddingTop() + getPaddingBottom()
            val childCount = getChildCount()
            var width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) max(
                0,
                MeasureSpec.getSize(widthMeasureSpec) - horizontal
            ) else
                0
            var height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) max(
                0,
                MeasureSpec.getSize(heightMeasureSpec) - vertical
            ) else
                0
            for (i in 0..<childCount) {
                val child = getChildAt(i)
                val layoutParams = child.getLayoutParams()
                child.measure(
                    getChildMeasureSpec(widthMeasureSpec, horizontal, layoutParams.width),
                    getChildMeasureSpec(heightMeasureSpec, vertical, layoutParams.height)
                )
                width = max(width, child.getMeasuredWidth())
                height = max(height, child.getMeasuredHeight())
            }
            setMeasuredDimension(width + horizontal, height + vertical)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val left = getPaddingLeft()
            val top = getPaddingTop()
            val width = r - l - getPaddingRight() - left
            val height = b - t - getPaddingBottom() - top
            val childCount = getChildCount()
            for (i in 0..<childCount) {
                val child = getChildAt(i)
                val childWidth = child.getMeasuredWidth()
                val childHeight = child.getMeasuredHeight()
                val shiftX = (width - childWidth) / 2
                val shiftY = (height - childHeight) / 2
                child.layout(
                    left + shiftX,
                    top + shiftY,
                    left + shiftX + childWidth,
                    top + shiftY + childHeight
                )
            }
        }
    }

    private open class DragLayout(context: Context?, side: Side, private val callback: Callback) :
        SimpleLayout(context), Runnable {
        enum class Side {
            TOP, BOTTOM
        }

        interface Callback {
            val isScrolled: Boolean
            fun onProposeShift(shift: Int, acceleration: Float): Boolean
            fun onFinished()
        }

        private class LayoutData {
            var top: Int = 0
            var multiplier: Float = 0f
            var dy: Int = 0
        }

        private val helper: ViewDragHelper

        private var hasWindowFocus = true
        private var intercepted = false
        private var accept = false

        init {
            helper = ViewDragHelper.create(this, object : ViewDragHelper.Callback() {
                override fun tryCaptureView(child: View, pointerId: Int): Boolean {
                    return hasWindowFocus
                }

                override fun getViewVerticalDragRange(child: View): Int {
                    return getHeight()
                }

                override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
                    return child.getLeft()
                }

                override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
                    val layoutTop = getChildInitialTop(child)
                    val scrolled = intercepted || callback.isScrolled
                    when (side) {
                        Side.TOP -> return if (scrolled) max(layoutTop, top) else layoutTop
                        Side.BOTTOM -> return if (scrolled) min(layoutTop, top) else layoutTop
                        else -> throw IllegalStateException()
                    }
                }

                override fun onViewDragStateChanged(state: Int) {
                    if (state == ViewDragHelper.STATE_DRAGGING) {
                        accept = false
                    }
                }

                override fun onViewPositionChanged(
                    changedView: View,
                    left: Int,
                    top: Int,
                    dx: Int,
                    dy: Int
                ) {
                    val layoutTop = getChildInitialTop(changedView)
                    val shift = top - layoutTop
                    val childCount = getChildCount()
                    for (i in 0..<childCount) {
                        val child = getChildAt(i)
                        if (child !== changedView) {
                            val layoutData = getLayoutData(child, false)
                            if (layoutData != null) {
                                val shareShift = (shift * layoutData.multiplier + 0.5f).toInt()
                                val childTop = child.getTop()
                                val childDy = layoutData.top + shareShift - childTop
                                ViewCompat.offsetTopAndBottom(child, childDy)
                            }
                        }
                    }
                    callback.onProposeShift(abs(shift), 0f)
                }

                override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
                    val top = releasedChild.getTop()
                    val layoutTop = getChildInitialTop(releasedChild)
                    val shift = abs(top - layoutTop)
                    val velocity =
                        if (top > layoutTop && yvel > 0) yvel else if (top < layoutTop && yvel < 0) -yvel else 0f
                    val acceleration = 1f + sqrt((velocity / getHeight()).toDouble()).toFloat()
                    if (hasWindowFocus && callback.onProposeShift(shift, acceleration)) {
                        accept = true
                        val targetTop = 2 * top - layoutTop
                        val maxTop: Int
                        val minTop: Int
                        when (side) {
                            Side.TOP -> {
                                maxTop = getHeight()
                                minTop = min(targetTop, maxTop)
                            }

                            Side.BOTTOM -> {
                                maxTop = -getHeight()
                                minTop = max(targetTop, maxTop)
                            }

                            else -> {
                                throw IllegalStateException()
                            }
                        }
                        if (velocity > 0) {
                            helper.flingCapturedView(
                                releasedChild.getLeft(), min(minTop, maxTop),
                                releasedChild.getLeft(), max(minTop, maxTop)
                            )
                        } else {
                            helper.settleCapturedViewAt(releasedChild.getLeft(), minTop)
                        }
                        removeCallbacks(this@DragLayout)
                        postDelayed(this@DragLayout, 150)
                    } else {
                        helper.settleCapturedViewAt(releasedChild.getLeft(), layoutTop)
                    }
                    invalidate()
                }
            })
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            this.hasWindowFocus = hasWindowFocus
            if (!hasWindowFocus) {
                if (helper.getViewDragState() == ViewDragHelper.STATE_DRAGGING) {
                    val ev = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                    try {
                        helper.processTouchEvent(ev)
                    } finally {
                        ev.recycle()
                    }
                }
                helper.cancel()
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                intercepted = false
            }
            val result = helper.shouldInterceptTouchEvent(ev)
            if (result) {
                if (!intercepted) {
                    getParent().requestDisallowInterceptTouchEvent(true)
                }
                intercepted = true
            }
            if (result || intercepted) {
                return result
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (intercepted) {
                helper.processTouchEvent(ev)
                return true
            }
            return super.onTouchEvent(ev)
        }

        override fun computeScroll() {
            super.computeScroll()
            if (helper.continueSettling(true)) {
                postInvalidateOnAnimation()
            } else {
                run()
            }
        }

        override fun run() {
            if (accept) {
                accept = false
                callback.onFinished()
            }
        }

        fun resetDrag() {
            helper.abort()
        }

        fun getChildInitialTop(child: View): Int {
            val layoutData = getLayoutData(child, false)
            return if (layoutData != null) layoutData.top else 0
        }

        protected override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val childCount = getChildCount()
            for (i in 0..<childCount) {
                val child = getChildAt(i)
                val layoutData = getLayoutData(child, false)
                if (layoutData != null) {
                    layoutData.dy = child.getTop() - layoutData.top
                }
            }

            super.onLayout(changed, l, t, r, b)
            for (i in 0..<childCount) {
                val child = getChildAt(i)
                val layoutData = getLayoutData(child, true)
                layoutData!!.top = child.getTop()
                layoutData.multiplier = (i + 1).toFloat() / childCount
                if (layoutData.dy != 0) {
                    ViewCompat.offsetTopAndBottom(child, layoutData.dy)
                }
            }

            val capturedView = helper.getCapturedView()
            if (capturedView != null && indexOfChild(capturedView) < 0) {
                if (childCount > 0) {
                    val child = getChildAt(childCount - 1)
                    val layoutData = getLayoutData(child, false)
                    helper.captureChildView(child, helper.getActivePointerId())
                    if (layoutData != null) {
                        callback.onProposeShift(abs(layoutData.top - child.getTop()), 0f)
                    }
                } else {
                    helper.abort()
                }
            }
        }

        companion object {
            private fun getLayoutData(child: View, create: Boolean): LayoutData? {
                var layoutData: LayoutData? = child.getTag(R.id.tag_drag_layout_data) as LayoutData?
                if (layoutData == null && create) {
                    layoutData = LayoutData()
                    child.setTag(R.id.tag_drag_layout_data, layoutData)
                }
                return layoutData
            }
        }
    }

    private class ContentView(context: Context?, side: Side, callback: Callback) :
        DragLayout(context, side, callback) {
        init {
            setWillNotDraw(false)
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            drawSystemInsetsOver(this, canvas, isTargetGesture29(this))
        }
    }

    private class DialogView(
        context: Context?, backgroundResId: Int, elevation: Float, dimAmount: Float,
        content: View?, private val viewFactory: ViewFactory<*>, popSelf: PopSelf
    ) : SimpleLayout(context) {
        fun interface PopSelf {
            fun onRequestPopSelf(dialogView: DialogView?)
        }

        private class ArrowData(
            val paint: Paint,
            val size: Float,
            topText: String,
            bottomText: String?
        ) {
            val path: Path = Path()

            val bottomTextShift: Float
            val topText: String
            val bottomText: String?
            val topTextWidth: Float
            val bottomTextWidth: Float

            var shift: Float = 0f
            var vertical: Float = 0f

            init {
                val metrics = Paint.FontMetrics()
                paint.getFontMetrics(metrics)
                bottomTextShift =
                    (metrics.descent + metrics.ascent + metrics.bottom + metrics.top) / -2f
                this.topText = topText
                this.bottomText = bottomText
                topTextWidth = paint.measureText(topText)
                bottomTextWidth = paint.measureText(bottomText)
            }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val elevation: Float
        private var arrowData: ArrowData? = null

        private var active = false
        private var background = false
        private var elevated = false

        init {
            val container =
                DragLayout(context, DragLayout.Side.BOTTOM, object : DragLayout.Callback {
                    override val isScrolled: Boolean
                        get() = this@DialogView.isScrolledToBottom

                    override fun onProposeShift(shift: Int, acceleration: Float): Boolean {
                        return handleShift(-shift, acceleration)
                    }

                    override fun onFinished() {
                        popSelf.onRequestPopSelf(this@DialogView)
                    }
                })
            container.setClipToPadding(false)
            container.setClipChildren(false)
            container.addView(
                this, LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            addView(
                content, LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setClickable(true)

            setBackgroundResource(backgroundResId)
            this.elevation = elevation
            setBackgroundTintList(ColorStateList.valueOf(ThemeEngine.Companion.getTheme(context)!!.card))

            paint.setColor((dimAmount * 0xff).toInt() shl 24)
            setActive(true)
        }

        val isScrolledToTop: Boolean
            get() = viewFactory.isScrolledToTop(this.content)

        val isScrolledToBottom: Boolean
            get() = viewFactory.isScrolledToBottom(this.content)

        fun handleShift(shift: Int, acceleration: Float): Boolean {
            val height = this.container!!.getHeight()
            var relativeShift = shift / (height / 8f)
            relativeShift = max(-1f, min(relativeShift, 1f))
            updatePath(relativeShift)
            val largeHeight = height * 2f / 3f
            val smallHeight = height / 3f
            // Make small views easier to close: denominator is in [2 .. 4] depending on the view height
            var denominator = (getHeight() - largeHeight) / (smallHeight - largeHeight)
            denominator = 2f * (max(0f, min(denominator, 1f)) + 1f)
            return acceleration * abs(shift) >= height / denominator
        }

        val container: View?
            get() = getParent() as View?

        val content: View
            get() = getChildAt(0)

        fun setActive(active: Boolean) {
            if (this.active != active) {
                this.active = active
                invalidate()
            }
        }

        fun setBackground(background: Boolean) {
            if (this.background != background) {
                this.background = background
                invalidate()
            }
        }

        fun setElevated(elevated: Boolean) {
            if (this.elevated != elevated) {
                this.elevated = elevated
                setElevation(if (elevated) elevation else 0f)
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            return active && super.dispatchTouchEvent(ev)
        }

        fun updatePath(shift: Float) {
            if (shift == 0f && arrowData == null) {
                return
            }
            val arrow: ArrowData?
            if (arrowData == null) {
                val density = obtainDensity(this)
                val arrowSize = (40f * density + 0.5f).toInt()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.setColor(-0x1)
                paint.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
                paint.setTextSize((14f * density + 0.5f).toInt().toFloat())
                paint.setStrokeWidth(2f * density)
                paint.setStrokeCap(Paint.Cap.ROUND)
                paint.setStrokeJoin(Paint.Join.ROUND)
                val topText =
                    getContext().getString(R.string.close_all).uppercase(Locale.getDefault())
                val bottomText =
                    getContext().getString(R.string.close).uppercase(Locale.getDefault())
                arrow = ArrowData(paint, arrowSize.toFloat(), topText, bottomText)
                arrowData = arrow
            } else {
                arrow = arrowData
                if (arrow!!.shift == shift) {
                    return
                }
                arrow.path.rewind()
            }
            arrow.shift = shift
            val absShift = abs(shift)
            arrow.vertical = sign(shift) * max(0f, absShift - 2f / 3f) * 3f
            arrow.path.moveTo(
                -absShift * arrow.size / 2f,
                -arrow.vertical * arrow.size / 4f
            )
            arrow.path.lineTo(0f, 0f)
            arrow.path.lineTo(absShift * arrow.size / 2f, -arrow.vertical * arrow.size / 4f)
            invalidate()
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)

            if (!active && !background) {
                var background = getBackground()
                while (background is InsetDrawable) {
                    background = background.getDrawable()
                }
                var radius = 0f
                if (background is GradientDrawable) {
                    val roundRectDrawable = background
                    radius = getCornerRadius(roundRectDrawable)
                }
                if (radius > 0f) {
                    canvas.drawRoundRect(
                        getPaddingLeft().toFloat(),
                        getPaddingTop().toFloat(),
                        (getWidth() - getPaddingRight()).toFloat(),
                        (getHeight() - getPaddingBottom()).toFloat(),
                        radius,
                        radius,
                        paint
                    )
                } else {
                    canvas.drawRect(
                        getPaddingLeft().toFloat(),
                        getPaddingTop().toFloat(),
                        (getWidth() - getPaddingRight()).toFloat(),
                        (getHeight() - getPaddingBottom()).toFloat(),
                        paint
                    )
                }
            }

            val arrow = this.arrowData
            if (arrow != null && arrow.shift != 0f) {
                canvas.save()
                canvas.translate(
                    getWidth() / 2f,
                    (if (arrow.shift >= 0) 0 else getHeight()).toFloat()
                )
                arrow.paint.setStyle(Paint.Style.STROKE)
                canvas.drawPath(arrow.path, arrow.paint)
                val absVertical = abs(arrow.vertical)
                if (absVertical > 0) {
                    arrow.paint.setStyle(Paint.Style.FILL)
                    val text = (if (arrow.shift >= 0) arrow.topText else arrow.bottomText)!!
                    val textWidth =
                        if (arrow.shift >= 0) arrow.topTextWidth else arrow.bottomTextWidth
                    val arrowDy = -arrow.vertical * arrow.size / 4f
                    val paddingDy =
                        (if (arrow.shift >= 0) -getPaddingTop() else getPaddingBottom()).toFloat()
                    val textDy = if (arrow.shift >= 0) 0f else arrow.bottomTextShift
                    canvas.translate(-textWidth / 2f, arrowDy + paddingDy + textDy)
                    val oldAlpha = arrow.paint.getAlpha()
                    arrow.paint.setAlpha((0xff * absVertical).toInt())
                    canvas.drawText(text, 0f, 0f, arrow.paint)
                    arrow.paint.setAlpha(oldAlpha)
                }
                canvas.restore()
            }
        }
    }

    interface ViewFactory<T : ViewFactory<T?>?> {
        fun createView(dialogStack: DialogStack<T>): View
        fun destroyView(view: View, remove: Boolean) {}

        fun isScrolledToTop(view: View): Boolean {
            return true
        }

        fun isScrolledToBottom(view: View): Boolean {
            return true
        }
    }

    fun getVisibleViews(): Iterable<View?> {
        return Iterable {
            val iterator: MutableIterator<Pair<T?, DialogView?>> = visibleViews.iterator()
            object : Iterator<View?> {
                override fun hasNext(): Boolean {
                    return iterator.hasNext()
                }

                override fun next(): View {
                    val pair = iterator.next()
                    return pair.second!!.content
                }
            }
        }
    }

    override fun iterator(): Iterator<Pair<T?, View?>?> {
        val hidden = hiddenViews.iterator()
        val visible: MutableIterator<Pair<T?, DialogView?>> = visibleViews.iterator()
        return object : Iterator<Pair<T?, View?>?> {
            override fun hasNext(): Boolean {
                return hidden.hasNext() || visible.hasNext()
            }

            override fun next(): Pair<T?, View?>? {
                if (hidden.hasNext()) {
                    return Pair<T?, View?>(hidden.next(), null)
                } else if (visible.hasNext()) {
                    val pair = visible.next()
                    return Pair<T?, View?>(
                        pair.first,
                        pair.second!!.content
                    )
                } else {
                    return null
                }
            }
        }
    }

    companion object {
        private const val VISIBLE_COUNT = 10
    }
}
