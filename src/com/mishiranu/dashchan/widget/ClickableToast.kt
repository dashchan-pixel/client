package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.View.OnFocusChangeListener
import android.view.View.OnLayoutChangeListener
import android.view.View.OnTouchListener
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.graphics.BaseDrawable
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.isMain
import com.mishiranu.dashchan.util.ConcurrentUtils.mainGet
import com.mishiranu.dashchan.util.FlagUtils.set
import com.mishiranu.dashchan.util.GraphicsUtils.getDrawableColor
import com.mishiranu.dashchan.util.GraphicsUtils.isLight
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.addWindowFocusListener
import com.mishiranu.dashchan.util.ViewUtils.removeFromParent
import com.mishiranu.dashchan.util.ViewUtils.removeWindowFocusListener
import java.lang.ref.WeakReference
import java.util.Objects
import java.util.UUID
import java.util.concurrent.Callable
import kotlin.math.max
import kotlin.math.roundToInt

class ClickableToast private constructor(
    private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
    private val windowManager: WindowManager

    /**
     * The toasts on screen, oldest first, each in a window of its own. They stack upwards from the
     * usual toast position: the oldest keeps it, every later one rides above the ones already
     * there. A toast that arrives while another is still up therefore cannot hide it -- covering
     * one was how a toast's button became unreachable before it timed out.
     */
    private val items = ArrayList<Item>()

    private var resumed: Boolean

    // Everything below is measured or derived once and shared by every toast of this activity:
    // building a toast means laying out the platform toast layout, which is not worth repeating.
    private val density: Float
    private val innerPadding: Int
    private val stackGap: Int
    private val dividerWidth: Int
    private val dividerPadding: Int
    private val toastHorizontalPadding: Int
    private val paddingForElevation: Int
    private val toastElevation: Float
    private val cornerRadius: Float
    private val backgroundColor: Int
    private val dividerColor: Int
    private val clickedButtonBackgroundPaint: Paint
    private val contentPadding: Rect
    private val horizontalContentPadding: Int
    private val maxToastWidth: Int

    class Button(
        internal val titleResId: Int,
        internal val clickableOnlyWhenRoot: Boolean,
        internal val callback: Runnable?,
    )

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        updateAndApplyLayoutChecked()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        updateAndApplyLayoutChecked()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (currentActivity?.get() === owner) {
            currentActivity = null
        }
        cancelInternal()
        // Unbind toast from DecorView, which may be reused on configuration change
        val tagView: View = getTagView(owner as ComponentActivity)
        if (tagView.getTag(R.id.tag_clickable_toast) === this) {
            tagView.setTag(R.id.tag_clickable_toast, null)
        }
        removeWindowFocusListener(tagView, windowFocusListener)
    }

    private fun isForActivity(activity: ComponentActivity?): Boolean {
        // Toast is bound to DecorView, which may be reused on configuration change
        return this.activity === activity
    }

    private val windowFocusListener =
        OnFocusChangeListener { v: View?, hasFocus: Boolean -> updateAndApplyLayoutChecked() }

    /**
     * A toast whose window has just been laid out knows its real height, which is what the ones
     * above it are stacked on top of. The height predicted when it was shown is usually right, so
     * this normally changes nothing; the reposition is posted because it happens during a layout.
     */
    private val stackLayoutListener =
        OnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
            val item = items.firstOrNull { it.windowContainer === view }
            if (item != null && item.stackHeight != bottom - top) {
                item.stackHeight = bottom - top
                view.post { updateStackPositions() }
            }
        }

    private fun showInternal(
        message: CharSequence?,
        updateId: String?,
        button: Button?,
    ): String? {
        val updated = if (updateId != null) items.firstOrNull { it.id == updateId } else null
        if (updated != null) {
            ConcurrentUtils.HANDLER.removeCallbacks(updated.cancelRunnable)
            bind(updated, message, button)
            applyLayout(updated)
            ConcurrentUtils.HANDLER.postDelayed(updated.cancelRunnable, TIMEOUT.toLong())
            return updateId
        }
        // The stack is bounded: past a few toasts the oldest one has been readable for a while
        // already, and the newest would be climbing towards the action bar.
        while (items.size >= MAX_STACK_SIZE) {
            cancelItem(items[0])
        }
        val item = Item()
        bind(item, message, button)
        item.stackHeight = measureStackHeight(item)
        if (!addItemToWindowManager(item, stackTopOffset())) {
            return null
        }
        items.add(item)
        val id = UUID.randomUUID().toString()
        item.id = id
        ConcurrentUtils.HANDLER.postDelayed(item.cancelRunnable, TIMEOUT.toLong())
        return id
    }

    private fun bind(
        item: Item,
        message: CharSequence?,
        button: Button?,
    ) {
        item.clickable = button != null
        item.message.setText(message)
        if (button != null) {
            item.button.setText(button.titleResId)
        }
        item.onClickListener = if (button != null) button.callback else null
        item.partialClickDrawable.clicked = false
        item.partialClickDrawable.invalidateSelf()
        item.clickableOnlyWhenRoot = button == null || button.clickableOnlyWhenRoot
        updateLayout(item)
    }

    /** Where the next toast goes: above everything already stacked. */
    private fun stackTopOffset(): Int {
        var offset = Y_OFFSET
        for (item in items) {
            offset += item.stackHeight + stackGap
        }
        return offset
    }

    private fun measureStackHeight(item: Item): Int {
        item.container.measure(
            MeasureSpec.makeMeasureSpec(maxToastWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return item.container.getMeasuredHeight() + paddingForElevation
    }

    private fun updateStackPositions() {
        var offset = Y_OFFSET
        for (item in items) {
            val windowContainer = item.windowContainer
            if (windowContainer != null && windowContainer.getParent() != null) {
                val layoutParams = windowContainer.getLayoutParams() as WindowManager.LayoutParams
                if (layoutParams.y != offset) {
                    layoutParams.y = offset
                    windowManager.updateViewLayout(windowContainer, layoutParams)
                }
            }
            offset += item.stackHeight + stackGap
        }
    }

    private fun addItemToWindowManager(
        item: Item,
        y: Int,
    ): Boolean {
        var added = false
        // TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW permission
        if (Settings.canDrawOverlays(activity)) {
            added =
                addItemToWindowManager(item, y, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }

        if (!added) {
            // TYPE_APPLICATION can't even properly overlay dialogs, used as fallback option
            added = addItemToWindowManager(item, y, WindowManager.LayoutParams.TYPE_APPLICATION)
        }
        return added
    }

    private fun addItemToWindowManager(
        item: Item,
        y: Int,
        type: Int,
    ): Boolean {
        var success = false
        try {
            val windowContainer = FrameLayout(activity)
            item.windowContainer = windowContainer
            windowContainer.setPadding(
                toastHorizontalPadding,
                0,
                toastHorizontalPadding,
                paddingForElevation,
            )
            windowContainer.setClipToPadding(false)
            windowContainer.addView(
                item.container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            windowContainer.addOnLayoutChangeListener(stackLayoutListener)
            windowManager.addView(windowContainer, createLayoutParams(item, type, y))
            success = true
        } catch (e: BadTokenException) {
            val errorMessage = e.message
            if (errorMessage == null ||
                !(
                    errorMessage.contains("permission denied") ||
                        errorMessage.contains("has already been added")
                )
            ) {
                throw e
            }
        } finally {
            if (!success) {
                removeWindowContainer(item)
            }
        }
        return success
    }

    private fun updateLayoutParams(
        item: Item,
        layoutParams: WindowManager.LayoutParams,
    ): WindowManager.LayoutParams {
        layoutParams.flags =
            set(
                layoutParams.flags,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                !item.realClickable,
            )
        return layoutParams
    }

    private fun createLayoutParams(
        item: Item,
        type: Int,
        y: Int,
    ): WindowManager.LayoutParams {
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.type = type
        layoutParams.format = PixelFormat.TRANSLUCENT
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        // For hierarchy viewer (layout inspector)
        layoutParams.setTitle(activity.getPackageName() + "/" + javaClass.getName())
        layoutParams.windowAnimations = android.R.style.Animation_Toast
        layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        layoutParams.y = y
        try {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            // PRIVATE_FLAG_NO_MOVE_ANIMATION == 0x00000040
            field.set(layoutParams, field.getInt(layoutParams) or 0x00000040)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return updateLayoutParams(item, layoutParams)
    }

    private fun updateAndApplyLayoutChecked() {
        for (item in items) {
            if (item.clickable) {
                updateLayout(item)
                applyLayout(item)
            }
        }
    }

    private fun updateLayout(item: Item) {
        val focused = activity.getWindow().getDecorView().hasWindowFocus()
        item.realClickable = item.clickable && (focused || !item.clickableOnlyWhenRoot) && resumed
        item.button.setVisibility(if (item.realClickable) View.VISIBLE else View.GONE)
        item.message.setPadding(
            if (item.realClickable) item.button.getPaddingRight() else 0,
            0,
            if (item.realClickable) item.button.getPaddingLeft() else 0,
            0,
        )
    }

    private fun applyLayout(item: Item) {
        val windowContainer = item.windowContainer
        if (windowContainer != null && windowContainer.getParent() != null) {
            windowManager.updateViewLayout(
                windowContainer,
                updateLayoutParams(
                    item,
                    windowContainer.getLayoutParams() as WindowManager.LayoutParams,
                ),
            )
        }
    }

    private fun cancelInternal() {
        while (items.isNotEmpty()) {
            cancelItem(items[0])
        }
    }

    private fun cancelItem(item: Item) {
        ConcurrentUtils.HANDLER.removeCallbacks(item.cancelRunnable)
        if (!items.remove(item)) {
            return
        }
        item.onClickListener = null
        item.id = null
        item.clickable = false
        item.realClickable = false
        removeWindowContainer(item)
        // The toasts that were riding on this one drop down into the space it leaves
        updateStackPositions()
    }

    private fun removeWindowContainer(item: Item) {
        val windowContainer = item.windowContainer
        if (windowContainer != null) {
            windowContainer.removeOnLayoutChangeListener(stackLayoutListener)
            if (windowContainer.getParent() != null) {
                windowManager.removeViewImmediate(windowContainer)
            }
            windowContainer.removeView(item.container)
            item.windowContainer = null
        }
    }

    init {
        windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addWindowFocusListener(getTagView(activity), windowFocusListener)
        toastHorizontalPadding =
            activity
                .getResources()
                .getDimensionPixelSize(R.dimen.clickable_toast_horizontal_padding)

        activity.lifecycle.addObserver(this)
        resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED
        density = obtainDensity(activity)
        innerPadding = (8f * density).toInt()
        stackGap = (8f * density).toInt()
        dividerWidth = (density + 0.5f).toInt()
        dividerPadding = (4f * density).toInt()
        toastElevation = activity.getResources().getDimension(R.dimen.clickable_toast_elevation)
        paddingForElevation = 2 * toastElevation.roundToInt()
        cornerRadius = Preferences.uiCornerRadius * density
        backgroundColor = getColor(activity, R.attr.colorClickableToastBackground)

        // Measure a throwaway copy of the platform toast: the padding its frame leaves around the
        // text is the padding the replacement layout has to reproduce.
        val inflater = LayoutInflater.from(activity)
        val toast: View = inflater.inflate(LAYOUT_ID, null)
        val message = toast.findViewById<TextView>(android.R.id.message)
        TextViewCompat.setTextAppearance(message, R.style.ClickableToastTextAppearance)
        dividerColor = message.getTextColors().getDefaultColor()
        var backgroundView: View? = toast
        if (toast.getBackground() == null) {
            var view: View? = message
            while (view != null) {
                if (view.getBackground() != null) {
                    backgroundView = view
                    break
                }
                view = view.getParent() as View?
            }
        }
        // Make long text to avoid minimum widths
        val builder = StringBuilder()
        for (i in 0..99) {
            builder.append('W')
        }
        message.setText(builder)
        val measureSize =
            (activity.getResources().getConfiguration().screenWidthDp * density + 0.5f).toInt()
        maxToastWidth = measureSize - 2 * toastHorizontalPadding
        toast.measure(
            MeasureSpec.makeMeasureSpec(measureSize, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val lineCount = message.getLayout().getLineCount()
        if (lineCount >= 2) {
            builder.setLength(message.getLayout().getLineEnd(0))
            message.setText(builder)
            toast.measure(
                MeasureSpec.makeMeasureSpec(measureSize, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        toast.layout(0, 0, toast.getMeasuredWidth(), toast.getMeasuredHeight())
        val totalPadding =
            Rect(
                message.getPaddingLeft(),
                message.getPaddingTop(),
                message.getPaddingRight(),
                message.getPaddingBottom(),
            )
        val messageMeasuredHeight = message.getHeight()
        var measureView: View? = message
        while (true) {
            val parent = measureView!!.getParent() as View?
            if (parent == null || measureView === backgroundView) {
                break
            }
            totalPadding.left += measureView.getLeft()
            totalPadding.top += measureView.getTop()
            totalPadding.right += parent.getWidth() - measureView.getRight()
            totalPadding.bottom += parent.getHeight() - measureView.getBottom()
            measureView = parent
        }
        message.measure(
            MeasureSpec.makeMeasureSpec(message.getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val extraHeight = messageMeasuredHeight - message.getMeasuredHeight()
        totalPadding.top += extraHeight / 2
        totalPadding.bottom += extraHeight / 2
        contentPadding = totalPadding
        horizontalContentPadding = max(totalPadding.left, totalPadding.right)

        // The pressed-button tint is derived from the background colour, and getDrawableColor
        // renders the drawable to do it -- once here rather than once per toast.
        val color = getDrawableColor(activity, createBackgroundDrawable(), Gravity.CENTER)
        val isLight = isLight(color)
        val source = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3f / 0xff
        val target = source + (if (isLight) -0.15f else 0.2f)
        val multiplier = target / source
        val matrix = FloatArray(20)
        for (i in 0..2) {
            matrix[6 * i] = multiplier
        }
        matrix[18] = 1f
        val colorFilter: ColorFilter = ColorMatrixColorFilter(matrix)
        clickedButtonBackgroundPaint = Paint()
        clickedButtonBackgroundPaint.setColor(color)
        clickedButtonBackgroundPaint.setColorFilter(colorFilter)
        clickedButtonBackgroundPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
    }

    /**
     * Round the toast to the app-wide radius: replace the platform toast frame with a rounded shape
     * of the toast colour. The outline provider + clipToOutline of [Item] then clip the whole toast
     * to these corners.
     */
    private fun createBackgroundDrawable(): Drawable {
        val background = GradientDrawable()
        background.cornerRadius = cornerRadius
        background.setColor(backgroundColor)
        return background
    }

    /**
     * A text view of the platform toast, detached from the frame it came in: the message and the
     * button of a toast are two of these side by side.
     */
    private fun inflateToastTextView(): TextView {
        val view =
            LayoutInflater
                .from(activity)
                .inflate(LAYOUT_ID, null)
                .findViewById<TextView>(android.R.id.message)
        TextViewCompat.setTextAppearance(view, R.style.ClickableToastTextAppearance)
        // Some launchers (e.g. OneUI) add a shadow to toast text and it looks bad so remove it
        view.setShadowLayer(0f, 0f, 0f, 0)
        removeFromParent(view)
        view.setBackground(null)
        view.setEllipsize(TextUtils.TruncateAt.END)
        return view
    }

    /** One toast: its views, its window, and the state that decides whether its button works. */
    private inner class Item {
        val container: View
        val message: TextView
        val button: TextView
        val partialClickDrawable: PartialClickDrawable

        var windowContainer: FrameLayout? = null
        var onClickListener: Runnable? = null
        var id: String? = null
        var clickable = false
        var realClickable = false
        var clickableOnlyWhenRoot = false

        /** Height this toast occupies in the stack: predicted when shown, corrected once laid out. */
        var stackHeight = 0

        val cancelRunnable = Runnable { cancelItem(this) }

        init {
            val messageView = inflateToastTextView()
            val buttonView = inflateToastTextView()
            val linearLayout = LinearLayout(activity)
            linearLayout.setOrientation(LinearLayout.HORIZONTAL)
            linearLayout.setDividerDrawable(ToastDividerDrawable(dividerColor, dividerWidth))
            linearLayout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE)
            linearLayout.setDividerPadding(dividerPadding)
            linearLayout.setTag(this@ClickableToast)
            linearLayout.addView(
                messageView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            linearLayout.addView(
                buttonView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            (messageView.getLayoutParams() as LinearLayout.LayoutParams).weight = 1f
            (buttonView.getLayoutParams() as LinearLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL
            linearLayout.setPadding(
                horizontalContentPadding,
                contentPadding.top,
                horizontalContentPadding,
                contentPadding.bottom,
            )
            val backgroundDrawable = createBackgroundDrawable()
            linearLayout.setOutlineProvider(
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View?,
                        outline: Outline,
                    ) {
                        backgroundDrawable.getOutline(outline)
                    }
                },
            )
            linearLayout.setElevation(toastElevation)
            linearLayout.setClipToOutline(true)

            partialClickDrawable = PartialClickDrawable(this, backgroundDrawable)
            linearLayout.setBackground(partialClickDrawable)
            linearLayout.setOnTouchListener(partialClickDrawable)
            messageView.setPadding(0, 0, 0, 0)
            buttonView.setPaddingRelative(innerPadding, 0, 0, 0)
            messageView.setMaxLines(3)
            buttonView.setSingleLine(true)
            container = linearLayout
            message = messageView
            button = buttonView
        }
    }

    private inner class PartialClickDrawable(
        private val item: Item,
        private val drawable: Drawable,
    ) : BaseDrawable(),
        OnTouchListener,
        Drawable.Callback {
        private val buttonBounds = Rect()
        internal var clicked = false

        init {
            drawable.setCallback(this)
        }

        val view: View?
            get() = if (getCallback() is View) (getCallback() as View?) else null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            v: View?,
            event: MotionEvent,
        ): Boolean {
            if (!item.realClickable) {
                return false
            }
            val button = item.button
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getX() >= button.getLeft()) {
                    clicked = true
                    val view = this.view
                    if (view != null) {
                        view.invalidate()
                    }
                }
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (clicked) {
                    clicked = false
                    val view = this.view
                    if (view != null) {
                        view.invalidate()
                        if (event.getAction() == MotionEvent.ACTION_UP) {
                            val x = event.getX()
                            val y = event.getY()
                            if (x >= button.getLeft() && x <= view.getWidth() && y >= 0 && y <= view.getHeight()) {
                                val onClickListener = item.onClickListener
                                ConcurrentUtils.HANDLER.removeCallbacks(item.cancelRunnable)
                                ConcurrentUtils.HANDLER.post(item.cancelRunnable)
                                onClickListener?.run()
                            }
                        }
                    }
                    return true
                }
            }
            return clicked
        }

        override fun setBounds(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.setBounds(left, top, right, bottom)
            drawable.setBounds(left, top, right, bottom)
        }

        override fun getDirtyBounds(): Rect = drawable.getDirtyBounds()

        public override fun draw(canvas: Canvas) {
            drawable.draw(canvas)
            if (clicked) {
                val button = item.button
                val toastBounds = getBounds()
                if (button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                    val shift = button.getRight()
                    buttonBounds.set(
                        toastBounds.left + shift,
                        toastBounds.top,
                        toastBounds.left + shift,
                        toastBounds.bottom,
                    )
                } else {
                    val shift = button.getLeft()
                    buttonBounds.set(
                        toastBounds.left + shift,
                        toastBounds.top,
                        toastBounds.right,
                        toastBounds.bottom,
                    )
                }
                canvas.drawRect(buttonBounds, clickedButtonBackgroundPaint)
            }
        }

        @Suppress("deprecation")
        public override fun getOpacity(): Int = drawable.getOpacity()

        public override fun setAlpha(alpha: Int) {
            drawable.setAlpha(alpha)
        }

        override fun getIntrinsicWidth(): Int = drawable.getIntrinsicWidth()

        override fun getIntrinsicHeight(): Int = drawable.getIntrinsicHeight()

        override fun invalidateDrawable(who: Drawable) {
            invalidateSelf()
        }

        override fun scheduleDrawable(
            who: Drawable,
            what: Runnable,
            `when`: Long,
        ) {
            scheduleSelf(what, `when`)
        }

        override fun unscheduleDrawable(
            who: Drawable,
            what: Runnable,
        ) {
            unscheduleSelf(what)
        }
    }

    private class ToastDividerDrawable(
        color: Int,
        private val width: Int,
    ) : ColorDrawable(color) {
        override fun getIntrinsicWidth(): Int = width
    }

    companion object {
        private val Y_OFFSET: Int
        private val LAYOUT_ID: Int

        private const val TIMEOUT = 3500
        private const val MAX_STACK_SIZE = 3

        init {
            val resources = Resources.getSystem()
            Y_OFFSET =
                resources.getDimensionPixelSize(
                    resources.getIdentifier(
                        "toast_y_offset",
                        "dimen",
                        "android",
                    ),
                )
            LAYOUT_ID = resources.getIdentifier("transient_notification", "layout", "android")
        }

        private var currentActivity: WeakReference<ComponentActivity?>? = null

        private fun getTagView(activity: ComponentActivity): View = activity.getWindow().getDecorView()

        private fun getToast(activity: ComponentActivity): ClickableToast? {
            val toast = getTagView(activity).getTag(R.id.tag_clickable_toast) as ClickableToast?
            return if (toast != null && toast.isForActivity(activity)) toast else null
        }

        private val currentToast: ClickableToast?
            get() {
                val activity = currentActivity?.get() ?: return null
                return getToast(activity)
            }

        @JvmStatic
        fun register(activity: ComponentActivity) {
            Objects.requireNonNull<ComponentActivity?>(activity)
            val oldActivity: ComponentActivity? = currentActivity?.get()
            if (oldActivity === activity) {
                return
            }
            if (oldActivity != null) {
                getToast(oldActivity)!!.cancelInternal()
            }
            currentActivity = null
            val state: Lifecycle.State = activity.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.INITIALIZED)) {
                if (getToast(activity) == null) {
                    getTagView(activity).setTag(R.id.tag_clickable_toast, ClickableToast(activity))
                }
                currentActivity = WeakReference<ComponentActivity?>(activity)
            }
        }

        @JvmStatic
        fun show(message: Int): String? = show(ErrorItem(message))

        @JvmStatic
        fun show(errorItem: ErrorItem?): String? = show((if (errorItem != null) errorItem else ErrorItem(ErrorItem.Type.UNKNOWN)).toString())

        @JvmStatic
        @JvmOverloads
        fun show(
            message: CharSequence?,
            updateId: String? = null,
            button: Button? = null,
        ): String? {
            if (isMain()) {
                val toast: ClickableToast? = currentToast
                if (toast != null) {
                    return toast.showInternal(message, updateId, button)
                } else {
                    return null
                }
            } else {
                return mainGet<String?>(Callable { show(message, updateId, null) })
            }
        }

        @JvmStatic
        fun isShowing(id: String?): Boolean {
            val toast: ClickableToast? = currentToast
            return toast != null && id != null && toast.items.any { it.id == id }
        }

        @JvmStatic
        fun cancel() {
            val toast: ClickableToast? = currentToast
            if (toast != null) {
                toast.cancelInternal()
            }
        }
    }
}
