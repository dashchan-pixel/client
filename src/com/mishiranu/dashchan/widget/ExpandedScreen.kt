package com.mishiranu.dashchan.widget

import android.animation.Animator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.Window
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.graphics.BaseDrawable
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.FlagUtils.set
import com.mishiranu.dashchan.util.GraphicsUtils.mixColors
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.removeFromParent
import com.mishiranu.dashchan.util.ViewUtils.setNewMargin
import com.mishiranu.dashchan.util.ViewUtils.setNewPadding
import com.mishiranu.dashchan.util.ViewUtils.setWindowLayoutFullscreen
import com.mishiranu.dashchan.widget.InsetsLayout.Apply
import com.mishiranu.dashchan.widget.InsetsLayout.OnApplyInsetsListener
import java.util.Arrays
import kotlin.math.max

class ExpandedScreen(
    init: Init,
    rootView: View?,
    toolbarView: View?,
    drawerInterlayer: FrameLayout?,
    drawerParent: FrameLayout?,
    drawerContent: View?,
    drawerHeader: View?,
) : RecyclerScrollTracker.OnScrollListener {
    internal val expandingEnabled: Boolean
    internal val fullScreenLayoutEnabled: Boolean

    internal val activity: Activity
    private var windowInsets = InsetsLayout.Insets.DEFAULT
    private var useGesture29 = false
    private var imeBottom29 = 0

    private val rootView: View?
    private val toolbarView: View?
    private val drawerInterlayer: FrameLayout?
    private val drawerContent: View?
    private val drawerHeader: View?

    private var drawerOverToolbarEnabled = false
    private val contentViews = LinkedHashMap<View, RecyclerView?>()

    private val foregroundDrawables: MutableList<ForegroundDrawable>
    private var foregroundAnimator: ValueAnimator? = null
    private var foregroundAnimatorShow = false

    private enum class State {
        SHOW,
        ACTION_MODE,
        LOCKED,
        ;

        fun flag(): Int = 1 shl ordinal
    }

    private val lockers = HashSet<String?>()
    private var stateFlags = State.SHOW.flag()

    private val lastItemLimit: Int
    private val minItemsCount: Int

    interface Layout {
        fun getRecyclerView(): RecyclerView? = null

        fun setVerticalInsets(
            top: Int,
            bottom: Int,
            useGesture29: Boolean,
        )
    }

    class PreThemeInit(
        internal val activity: Activity,
        internal val expandingEnabled: Boolean,
    ) {
        internal val fullScreenLayoutEnabled = true

        init {
            val window = activity.getWindow()
            if (!fullScreenLayoutEnabled && expandingEnabled) {
                window.requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY)
            }
        }

        fun initAfterTheme(): Init {
            if (fullScreenLayoutEnabled) {
                setWindowLayoutFullscreen(activity.getWindow())
            }
            return ExpandedScreen.Init(activity, expandingEnabled, fullScreenLayoutEnabled)
        }
    }

    class Init internal constructor(
        internal val activity: Activity,
        internal val expandingEnabled: Boolean,
        internal val fullScreenLayoutEnabled: Boolean,
    )

    private open class ForegroundDrawable : BaseDrawable() {
        open fun applyAlpha(alpha: Int) {}

        open fun applyStatusGuardColor(color: Int) {}
    }

    private open class AlphaForegroundDrawable : ForegroundDrawable() {
        protected var alphaValue: Int = 0xff

        override fun applyAlpha(alpha: Int) {
            this.alphaValue = alpha
            invalidateSelf()
        }
    }

    private inner class LollipopContentForeground(
        private val statusBarColor: Int,
        private val navigationBarColor: Int,
    ) : AlphaForegroundDrawable() {
        private val paint = Paint()

        public override fun draw(canvas: Canvas) {
            val width = getBounds().width()
            val height = getBounds().height()
            val paint = this.paint
            if (toolbarView == null) {
                val statusBarHeight = windowInsets.top
                if (statusBarHeight > 0) {
                    paint.setColor(ViewUtils.STATUS_OVERLAY_TRANSPARENT)
                    canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                    if (alphaValue > 0) {
                        paint.setColor(statusBarColor)
                        paint.setAlpha(alphaValue)
                        canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                    }
                }
            }
            val navigationBarLeft = windowInsets.left
            val navigationBarRight = windowInsets.right
            val navigationBarBottom = windowInsets.bottom
            if (navigationBarLeft > 0) {
                paint.setColor(navigationBarColor)
                canvas.drawRect(0f, 0f, navigationBarLeft.toFloat(), height.toFloat(), paint)
            }
            if (navigationBarRight > 0) {
                paint.setColor(navigationBarColor)
                canvas.drawRect(
                    (width - navigationBarRight).toFloat(),
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    paint,
                )
            }
            if (navigationBarBottom > 0 && !useGesture29) {
                paint.setColor(ViewUtils.STATUS_OVERLAY_TRANSPARENT)
                canvas.drawRect(
                    0f,
                    (height - navigationBarBottom).toFloat(),
                    width.toFloat(),
                    height.toFloat(),
                    paint,
                )
                if (alphaValue > 0) {
                    paint.setColor(navigationBarColor)
                    paint.setAlpha(alphaValue)
                    canvas.drawRect(
                        0f,
                        (height - navigationBarBottom).toFloat(),
                        width.toFloat(),
                        height.toFloat(),
                        paint,
                    )
                }
            }
        }
    }

    private inner class LollipopStatusBarForeground(
        private val statusBarColor: Int,
    ) : AlphaForegroundDrawable() {
        private val paint = Paint()

        private var statusGuardColor = 0

        override fun applyStatusGuardColor(color: Int) {
            statusGuardColor = color
            invalidateSelf()
        }

        public override fun draw(canvas: Canvas) {
            val width = getBounds().width()
            val paint = this.paint
            val statusBarHeight = windowInsets.top
            if (statusBarHeight > 0) {
                paint.setColor(ViewUtils.STATUS_OVERLAY_TRANSPARENT)
                canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                if (alphaValue > 0) {
                    paint.setColor(statusBarColor)
                    paint.setAlpha(alphaValue)
                    canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                }
                if (Color.alpha(statusBarColor) > 0) {
                    paint.setColor(statusGuardColor)
                    canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                }
            }
        }
    }

    private inner class LollipopDrawerForeground : ForegroundDrawable() {
        private val paint = Paint()

        public override fun draw(canvas: Canvas) {
            if (drawerOverToolbarEnabled && toolbarView != null) {
                val width = getBounds().width()
                val statusBarHeight = windowInsets.top
                if (statusBarHeight > 0) {
                    paint.setColor(ViewUtils.STATUS_OVERLAY_TRANSPARENT)
                    canvas.drawRect(0f, 0f, width.toFloat(), statusBarHeight.toFloat(), paint)
                }
            }
        }
    }

    private inner class ForegroundAnimatorListener(
        private val show: Boolean,
    ) : Animator.AnimatorListener,
        AnimatorUpdateListener {
        override fun onAnimationUpdate(animation: ValueAnimator) {
            val value = animation.getAnimatedValue() as Float
            val alpha = (0xff * value).toInt()
            for (foregroundDrawable in foregroundDrawables) {
                foregroundDrawable.applyAlpha(alpha)
            }
            if (toolbarView != null) {
                toolbarView.setAlpha(value)
            }
        }

        override fun onAnimationStart(animation: Animator) {}

        override fun onAnimationEnd(animation: Animator) {
            if (toolbarView != null && !show) {
                activity.getActionBar()!!.hide()
            }
            foregroundAnimator = null
        }

        override fun onAnimationCancel(animation: Animator) {}

        override fun onAnimationRepeat(animation: Animator) {}
    }

    private fun setState(
        state: State,
        value: Boolean,
    ) {
        if (expandingEnabled) {
            stateFlags = set(stateFlags, state.flag(), value)
        }
    }

    private fun checkState(state: State): Boolean = get(stateFlags, state.flag())

    private fun applyShowActionBar(show: Boolean) {
        val actionBar = activity.getActionBar()
        if (fullScreenLayoutEnabled) {
            val showing = this.isActionBarShowing
            var foregroundAnimator = this@ExpandedScreen.foregroundAnimator
            if (foregroundAnimator != null) {
                foregroundAnimator.cancel()
            }
            if (showing != show) {
                if (toolbarView != null) {
                    actionBar!!.show()
                }
                foregroundAnimator =
                    ValueAnimator.ofFloat(if (show) 0f else 1f, if (show) 1f else 0f)
                val listener = ForegroundAnimatorListener(show)
                foregroundAnimator.setInterpolator(AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR)
                foregroundAnimator.setDuration(ACTION_BAR_ANIMATION_TIME.toLong())
                foregroundAnimator.addListener(listener)
                foregroundAnimator.addUpdateListener(listener)
                foregroundAnimator.start()
                this@ExpandedScreen.foregroundAnimator = foregroundAnimator
                foregroundAnimatorShow = show
            }
        }
        if (toolbarView == null) {
            if (show) {
                actionBar!!.show()
            } else {
                actionBar!!.hide()
            }
        }
    }

    private val isActionBarShowing: Boolean
        get() {
            if (!activity.getActionBar()!!.isShowing()) {
                return false
            }
            if (toolbarView != null && foregroundAnimator != null) {
                return foregroundAnimatorShow
            }
            return true
        }

    private var enqueuedShowState = true
    private var lastShowStateChanged: Long = 0

    private val showStateRunnable =
        Runnable {
            if (enqueuedShowState != this.isActionBarShowing) {
                val show = enqueuedShowState
                setState(State.SHOW, show)
                applyShowActionBar(show)
                lastShowStateChanged = SystemClock.elapsedRealtime()
                updatePaddings()
            }
        }

    fun addLocker(name: String?) {
        lockers.add(name)
        if (!checkState(State.LOCKED)) {
            setLocked(true)
        }
    }

    fun removeLocker(name: String?) {
        lockers.remove(name)
        if (lockers.size == 0 && checkState(State.LOCKED)) {
            setLocked(false)
        }
    }

    private fun setLocked(locked: Boolean) {
        setState(State.LOCKED, locked)
        if (locked) {
            setShowActionBar(true, false)
        }
    }

    private fun setShowActionBar(
        show: Boolean,
        delayed: Boolean,
    ) {
        var showActionBar = show
        if (!showActionBar) {
            showActionBar = checkState(State.LOCKED) ||
                checkState(State.ACTION_MODE) &&
                !activity.getWindow().hasFeature(Window.FEATURE_ACTION_MODE_OVERLAY)
        }
        if (enqueuedShowState != showActionBar) {
            enqueuedShowState = showActionBar
            ConcurrentUtils.HANDLER.removeCallbacks(showStateRunnable)
            val t = SystemClock.elapsedRealtime() - lastShowStateChanged
            if (showActionBar != this.isActionBarShowing) {
                if (!delayed) {
                    showStateRunnable.run()
                } else if (t >= ACTION_BAR_ANIMATION_TIME + 200) {
                    ConcurrentUtils.HANDLER.post(showStateRunnable)
                } else {
                    ConcurrentUtils.HANDLER.postDelayed(showStateRunnable, t)
                }
            }
        }
    }

    private val recyclerScrollTracker = RecyclerScrollTracker(this)

    fun addContentView(view: View?) {
        var recyclerView: RecyclerView? = null
        if (view is Layout) {
            recyclerView =
                (view as Layout).getRecyclerView()
        }
        contentViews.put(view!!, recyclerView)
        if (recyclerView != null && expandingEnabled) {
            recyclerScrollTracker.attach(recyclerView)
        }
        updatePaddings()
        setShowActionBar(true, true)
    }

    fun removeContentView(view: View?) {
        val recyclerView = contentViews.remove(view)
        if (recyclerView != null && expandingEnabled) {
            recyclerScrollTracker.detach(recyclerView)
        }
    }

    fun setDrawerOverToolbarEnabled(drawerOverToolbarEnabled: Boolean) {
        this.drawerOverToolbarEnabled = drawerOverToolbarEnabled
        updatePaddings()
    }

    fun updatePaddings() {
        if (expandingEnabled || fullScreenLayoutEnabled) {
            val actionBarHeight: Int = obtainActionBarHeight(activity)
            val statusBarHeight = windowInsets.top
            val leftNavigationBarHeight = windowInsets.left
            val rightNavigationBarHeight = windowInsets.right
            val bottomNavigationBarHeight = windowInsets.bottom
            val useGesture29 = this.useGesture29
            val bottomImeHeight = imeBottom29
            if (rootView != null) {
                setNewMargin(
                    rootView,
                    leftNavigationBarHeight,
                    0,
                    rightNavigationBarHeight,
                    bottomImeHeight,
                )
            }
            if (drawerInterlayer != null) {
                setNewPadding(
                    drawerInterlayer,
                    null,
                    statusBarHeight,
                    null,
                    bottomNavigationBarHeight,
                )
            }
            for (view in contentViews.keys) {
                if (view is Layout) {
                    (view as Layout).setVerticalInsets(
                        statusBarHeight + actionBarHeight,
                        bottomNavigationBarHeight,
                        useGesture29,
                    )
                } else {
                    setNewMargin(
                        view,
                        null,
                        statusBarHeight + actionBarHeight,
                        null,
                        bottomNavigationBarHeight,
                    )
                }
            }
            if (drawerContent != null) {
                val paddingTop =
                    if (drawerOverToolbarEnabled && toolbarView != null) {
                        statusBarHeight
                    } else {
                        statusBarHeight + actionBarHeight
                    }
                if (drawerHeader != null) {
                    setNewPadding(drawerHeader, null, paddingTop, null, null)
                    setNewPadding(drawerContent, null, 0, null, bottomNavigationBarHeight)
                } else {
                    setNewPadding(drawerContent, null, paddingTop, null, bottomNavigationBarHeight)
                }
            }
            for (foregroundDrawable in foregroundDrawables) {
                foregroundDrawable.invalidateSelf()
            }
        }
    }

    override fun onScroll(
        view: ViewGroup,
        scrollingDown: Boolean,
        totalItemCount: Int,
        first: Boolean,
        last: Boolean,
    ) {
        var show = true
        if (scrollingDown) {
            val childCount = view.getChildCount()
            if (childCount > 0 && totalItemCount > minItemsCount) {
                if (!first || view.getChildAt(0).getTop() <= 0) {
                    // List is scrolled above the top edge of the screen
                    if (last) {
                        val lastView = view.getChildAt(childCount - 1)
                        if (view.getHeight() - view.getPaddingBottom() - lastView.getBottom() + lastItemLimit < 0) {
                            show = false
                        }
                    } else {
                        show = false
                    }
                }
            }
        }
        setShowActionBar(show, true)
    }

    private var actionModeViewInitialized = false

    init {
        expandingEnabled = init.expandingEnabled
        fullScreenLayoutEnabled = init.fullScreenLayoutEnabled
        activity = init.activity
        val contentForeground: ForegroundDrawable?
        val statusBarContentForeground: ForegroundDrawable?
        val statusBarDrawerForeground: ForegroundDrawable?
        val foregroundDrawables: MutableList<ForegroundDrawable>
        if (fullScreenLayoutEnabled) {
            // Edge-to-edge is enforced: system bars are transparent and the scrims below
            // draw the opaque bar backgrounds the window colors used to provide.
            val statusBarColor = Color.BLACK
            val navigationBarColor = Color.BLACK
            contentForeground = LollipopContentForeground(statusBarColor, navigationBarColor)
            statusBarContentForeground = LollipopStatusBarForeground(statusBarColor)
            statusBarDrawerForeground = LollipopDrawerForeground()
            foregroundDrawables =
                Arrays.asList<ForegroundDrawable>(
                    contentForeground,
                    statusBarContentForeground,
                    statusBarDrawerForeground,
                )
        } else {
            contentForeground = null
            statusBarContentForeground = null
            statusBarDrawerForeground = null
            foregroundDrawables = mutableListOf<ForegroundDrawable>()
        }

        val resources = activity.getResources()
        val density = obtainDensity(resources)
        lastItemLimit = (72f * density).toInt()
        minItemsCount = resources.getConfiguration().screenHeightDp / 48
        this.rootView = rootView
        this.toolbarView = toolbarView
        this.drawerInterlayer = drawerInterlayer
        if (drawerInterlayer != null) {
            drawerInterlayer.setForeground(statusBarContentForeground)
        }
        this.drawerContent = drawerContent
        this.drawerHeader = drawerHeader
        this.foregroundDrawables = foregroundDrawables
        if (fullScreenLayoutEnabled) {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val insetsLayout = InsetsLayout(activity)
            content.addView(
                insetsLayout,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            val actionBarHeight: Int = obtainActionBarHeight(activity)
            insetsLayout.setOnApplyInsetsListener(
                OnApplyInsetsListener { applyData: Apply? ->
                    val windowInsets: InsetsLayout.Insets
                    windowInsets = applyData!!.window
                    if (!this.windowInsets.equals(windowInsets) ||
                        useGesture29 != applyData.useGesture29 ||
                        imeBottom29 != applyData.imeBottom29
                    ) {
                        this.windowInsets = windowInsets
                        useGesture29 = applyData.useGesture29
                        imeBottom29 = applyData.imeBottom29
                        updatePaddings()
                    }
                },
            )
            insetsLayout.setBackground(contentForeground)
            if (statusBarDrawerForeground != null && drawerParent != null) {
                drawerParent.setForeground(statusBarDrawerForeground)
            }
        }
        updatePaddings()
    }

    fun setActionModeState(actionMode: Boolean) {
        if (actionMode && !actionModeViewInitialized) {
            if (drawerInterlayer != null) {
                // ActionModeBar view has lazy initialization
                val actionModeBarId =
                    activity.getResources().getIdentifier("action_mode_bar", "id", "android")
                val actionModeView =
                    if (actionModeBarId != 0) activity.findViewById<View?>(actionModeBarId) else null
                if (actionModeView != null) {
                    actionModeViewInitialized = true
                    removeFromParent(actionModeView)
                    var maxZ = 0f
                    val childCount = drawerInterlayer.getChildCount()
                    for (i in 0..<childCount) {
                        val child = drawerInterlayer.getChildAt(i)
                        maxZ = max(maxZ, child.getElevation() + child.getTranslationZ())
                    }
                    // Use simple ViewGroup without MarginLayoutParams to avoid StatusGuardView in DecorView
                    val box = ActionModeBoxView(actionModeView, maxZ)
                    drawerInterlayer.addView(
                        box,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            }
            if (fullScreenLayoutEnabled) {
                updatePaddings()
            }
        }
        setState(State.ACTION_MODE, actionMode)
        if (!actionMode && checkState(State.LOCKED) && !this.isActionBarShowing) {
            // Restore action bar
            enqueuedShowState = false
            setShowActionBar(true, true)
        }
    }

    private inner class ActionModeBoxView(
        actionModeView: View,
        maxZ: Float,
    ) : ViewGroup(actionModeView.getContext()),
        OnPreDrawListener {
        private val backgroundColor: Int

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val child = getChildAt(0)
            child.measure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(child.getMeasuredWidth(), child.getMeasuredHeight())
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val child = getChildAt(0)
            child.layout(0, 0, r - l, b - t)
        }

        private var lastAlpha = -1f

        init {
            setTranslationZ(maxZ)
            addView(actionModeView, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            actionModeView.getViewTreeObserver().addOnPreDrawListener(this)
            backgroundColor = getColor(getContext(), android.R.attr.colorBackground)
        }

        override fun onPreDraw(): Boolean {
            val child = getChildAt(0)
            var value = child.getAlpha()
            if (child.getVisibility() != VISIBLE) {
                value = 0f
            }
            if (lastAlpha != value) {
                lastAlpha = value
                val alpha = (0xff * value).toInt()
                val color =
                    mixColors(
                        -0x1000000 or backgroundColor,
                        ViewUtils.STATUS_OVERLAY_TRANSPARENT,
                    )
                val alphaColor = (alpha shl 24) or (0x00ffffff and color)
                for (foregroundDrawable in foregroundDrawables) {
                    foregroundDrawable.applyStatusGuardColor(alphaColor)
                }
            }
            return true
        }
    }

    companion object {
        // The same value is hardcoded in ActionBarImpl.
        private const val ACTION_BAR_ANIMATION_TIME = 250

        private val ATTRS_ACTION_BAR_SIZE = intArrayOf(android.R.attr.actionBarSize)

        private fun obtainActionBarHeight(context: Context): Int {
            val typedArray = context.obtainStyledAttributes(ATTRS_ACTION_BAR_SIZE)
            val actionHeight = typedArray.getDimensionPixelSize(0, 0)
            typedArray.recycle()
            return actionHeight
        }
    }
}
