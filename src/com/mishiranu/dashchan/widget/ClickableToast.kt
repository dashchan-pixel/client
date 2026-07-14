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
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.View.OnFocusChangeListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mishiranu.dashchan.R
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

class ClickableToast private constructor(
    private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
    private val windowManager: WindowManager
    private val container: View

    private val partialClickDrawable: PartialClickDrawable
    private val message: TextView
    private val button: TextView

    private var currentContainer: ViewGroup? = null
    private var onClickListener: Runnable? = null
    private var showing: String? = null
    private var clickable = false
    private var realClickable = false
    internal var clickableOnlyWhenRoot = false

    private var resumed: Boolean

    private val toastHorizontalPadding: Int

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
        if (currentActivity != null && currentActivity!!.get() === owner) {
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

    private fun showInternal(
        message: CharSequence?,
        updateId: String?,
        button: Button?,
    ): String? {
        val update = updateId != null && updateId == showing
        if (update) {
            ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable)
        } else {
            cancelInternal()
        }
        clickable = button != null
        this.message.setText(message)
        if (button != null) {
            this.button.setText(button.titleResId)
        }
        onClickListener = if (button != null) button.callback else null
        partialClickDrawable.clicked = false
        partialClickDrawable.invalidateSelf()
        clickableOnlyWhenRoot = button == null || button.clickableOnlyWhenRoot
        updateLayout()
        if (update) {
            applyLayout()
            ConcurrentUtils.HANDLER.postDelayed(cancelRunnable, TIMEOUT.toLong())
            return updateId
        } else if (addContainerToWindowManager()) {
            val id = UUID.randomUUID().toString()
            showing = id
            ConcurrentUtils.HANDLER.postDelayed(cancelRunnable, TIMEOUT.toLong())
            return id
        } else {
            return null
        }
    }

    private fun addContainerToWindowManager(): Boolean {
        var added = false
        // TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW permission
        if (Settings.canDrawOverlays(activity)) {
            added = addContainerToWindowManager(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }

        if (!added) {
            // TYPE_APPLICATION can't even properly overlay dialogs, used as fallback option
            added = addContainerToWindowManager(WindowManager.LayoutParams.TYPE_APPLICATION)
        }
        return added
    }

    private fun addContainerToWindowManager(type: Int): Boolean {
        var success = false
        try {
            currentContainer = FrameLayout(activity)
            val paddingForElevation = 2 * Math.round(ViewCompat.getElevation(container))
            currentContainer!!.setPadding(
                toastHorizontalPadding,
                0,
                toastHorizontalPadding,
                paddingForElevation,
            )
            currentContainer!!.setClipToPadding(false)
            currentContainer!!.addView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            windowManager.addView(currentContainer, createLayoutParams(type))
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
                removeCurrentContainer()
            }
        }
        return success
    }

    private fun updateLayoutParams(layoutParams: WindowManager.LayoutParams): WindowManager.LayoutParams {
        layoutParams.flags =
            set(
                layoutParams.flags,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                !realClickable,
            )
        return layoutParams
    }

    private fun createLayoutParams(type: Int): WindowManager.LayoutParams {
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
        layoutParams.y = Y_OFFSET
        try {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            // PRIVATE_FLAG_NO_MOVE_ANIMATION == 0x00000040
            field.set(layoutParams, field.getInt(layoutParams) or 0x00000040)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return updateLayoutParams(layoutParams)
    }

    private fun updateAndApplyLayoutChecked() {
        if (showing != null && clickable) {
            updateLayout()
            applyLayout()
        }
    }

    private fun updateLayout() {
        val focused = activity.getWindow().getDecorView().hasWindowFocus()
        realClickable = clickable && (focused || !clickableOnlyWhenRoot) && resumed
        button.setVisibility(if (realClickable) View.VISIBLE else View.GONE)
        message.setPadding(
            if (realClickable) button.getPaddingRight() else 0,
            0,
            if (realClickable) button.getPaddingLeft() else 0,
            0,
        )
    }

    private fun applyLayout() {
        if (currentContainer != null) {
            windowManager.updateViewLayout(
                currentContainer,
                updateLayoutParams((currentContainer!!.getLayoutParams() as WindowManager.LayoutParams?)!!),
            )
        }
    }

    private fun cancelInternal() {
        ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable)
        if (showing == null) {
            return
        }
        onClickListener = null
        showing = null
        clickable = false
        realClickable = false
        removeCurrentContainer()
    }

    private fun removeCurrentContainer() {
        if (currentContainer != null) {
            if (currentContainer!!.getParent() != null) {
                windowManager.removeViewImmediate(currentContainer)
            }
            currentContainer!!.removeView(container)
            currentContainer = null
        }
    }

    private val cancelRunnable = Runnable { this.cancelInternal() }

    init {
        windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addWindowFocusListener(getTagView(activity), windowFocusListener)
        toastHorizontalPadding =
            activity
                .getResources()
                .getDimensionPixelSize(R.dimen.clickable_toast_horizontal_padding)

        activity.lifecycle.addObserver(this)
        resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED
        val density = obtainDensity(activity)
        val innerPadding = (8f * density).toInt()
        val inflater = LayoutInflater.from(activity)
        val toast1: View = inflater.inflate(LAYOUT_ID, null)
        val toast2: View = inflater.inflate(LAYOUT_ID, null)
        val message1 = toast1.findViewById<TextView>(android.R.id.message)
        val message2 = toast2.findViewById<TextView>(android.R.id.message)
        TextViewCompat.setTextAppearance(message1, R.style.ClickableToastTextAppearance)
        TextViewCompat.setTextAppearance(message2, R.style.ClickableToastTextAppearance)
        // Some launchers (e.g. OneUI) add a shadow to toast text and it looks bad so remove it
        message1.setShadowLayer(0f, 0f, 0f, 0)
        message2.setShadowLayer(0f, 0f, 0f, 0)
        var backgroundDrawable = toast1.getBackground()
        var backgroundView: View? = toast1
        if (backgroundDrawable == null) {
            var view: View? = message1
            while (view != null) {
                backgroundDrawable = view.getBackground()
                if (backgroundDrawable != null) {
                    backgroundView = view
                    break
                }
                view = view.getParent() as View?
            }
        }
        val clickableToastBackgroundColor =
            getColor(
                activity,
                R.attr.colorClickableToastBackground,
            )
        val clickableToastBackgroundColorFilter =
            BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                clickableToastBackgroundColor,
                BlendModeCompat.SRC_IN,
            )
        backgroundDrawable!!.setColorFilter(clickableToastBackgroundColorFilter)
        // Make long text to avoid minimum widths
        val builder = StringBuilder()
        for (i in 0..99) {
            builder.append('W')
        }
        message1.setText(builder)
        val measureSize =
            (activity.getResources().getConfiguration().screenWidthDp * density + 0.5f).toInt()
        toast1.measure(
            MeasureSpec.makeMeasureSpec(measureSize, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val lineCount = message1.getLayout().getLineCount()
        if (lineCount >= 2) {
            builder.setLength(message1.getLayout().getLineEnd(0))
            message1.setText(builder)
            toast1.measure(
                MeasureSpec.makeMeasureSpec(measureSize, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        toast1.layout(0, 0, toast1.getMeasuredWidth(), toast1.getMeasuredHeight())
        val totalPadding =
            Rect(
                message1.getPaddingLeft(),
                message1.getPaddingTop(),
                message1.getPaddingRight(),
                message1.getPaddingBottom(),
            )
        val messageMeasuredHeight = message1.getHeight()
        var measureView: View? = message1
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
        message1.measure(
            MeasureSpec.makeMeasureSpec(message1.getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val extraHeight = messageMeasuredHeight - message1.getMeasuredHeight()
        totalPadding.top += extraHeight / 2
        totalPadding.bottom += extraHeight / 2
        val horizontalPadding = max(totalPadding.left, totalPadding.right)

        removeFromParent(message1)
        removeFromParent(message2)
        val linearLayout = LinearLayout(activity)
        linearLayout.setOrientation(LinearLayout.HORIZONTAL)
        linearLayout.setDividerDrawable(
            ToastDividerDrawable(
                message1.getTextColors().getDefaultColor(),
                (density + 0.5f).toInt(),
            ),
        )
        linearLayout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE)
        linearLayout.setDividerPadding((4f * density).toInt())
        linearLayout.setTag(this)
        linearLayout.addView(
            message1,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        linearLayout.addView(
            message2,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        (message1.getLayoutParams() as LinearLayout.LayoutParams).weight = 1f
        (message2.getLayoutParams() as LinearLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL
        linearLayout.setPadding(
            horizontalPadding,
            totalPadding.top,
            horizontalPadding,
            totalPadding.bottom,
        )
        val finalBackgroundDrawable = backgroundDrawable
        linearLayout.setOutlineProvider(
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View?,
                    outline: Outline,
                ) {
                    finalBackgroundDrawable.getOutline(outline)
                }
            },
        )
        val toastElevation = activity.getResources().getDimension(R.dimen.clickable_toast_elevation)
        linearLayout.setElevation(toastElevation)
        linearLayout.setClipToOutline(true)

        partialClickDrawable = PartialClickDrawable(activity, backgroundDrawable)
        linearLayout.setBackground(partialClickDrawable)
        linearLayout.setOnTouchListener(partialClickDrawable)
        message1.setBackground(null)
        message2.setBackground(null)
        message1.setPadding(0, 0, 0, 0)
        message2.setPaddingRelative(innerPadding, 0, 0, 0)
        message1.setMaxLines(3)
        message2.setSingleLine(true)
        message1.setEllipsize(TextUtils.TruncateAt.END)
        message2.setEllipsize(TextUtils.TruncateAt.END)
        container = linearLayout
        message = message1
        button = message2
    }

    private inner class PartialClickDrawable(
        context: Context,
        private val drawable: Drawable,
    ) : BaseDrawable(),
        OnTouchListener,
        Drawable.Callback {
        private val clickedButtonBackgroundPaint = Paint()
        internal var clicked = false

        init {
            val color = getDrawableColor(context, drawable, Gravity.CENTER)
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
            clickedButtonBackgroundPaint.setColor(color)
            clickedButtonBackgroundPaint.setColorFilter(colorFilter)
            clickedButtonBackgroundPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
            drawable.setCallback(this)
        }

        val view: View?
            get() = if (getCallback() is View) (getCallback() as View?) else null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            v: View?,
            event: MotionEvent,
        ): Boolean {
            if (!realClickable) {
                return false
            }
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
                                ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable)
                                ConcurrentUtils.HANDLER.post(cancelRunnable)
                                if (onClickListener != null) {
                                    onClickListener!!.run()
                                }
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
                val toastBounds = getBounds()
                val buttonBounds: Rect?
                if (button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                    val shift = button.getRight()
                    buttonBounds =
                        Rect(
                            toastBounds.left + shift,
                            toastBounds.top,
                            toastBounds.left + shift,
                            toastBounds.bottom,
                        )
                } else {
                    val shift = button.getLeft()
                    buttonBounds =
                        Rect(
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
                if (currentActivity != null) {
                    val activity: ComponentActivity? =
                        currentActivity!!.get()
                    return if (activity != null) getToast(activity) else null
                }
                return null
            }

        @JvmStatic
        fun register(activity: ComponentActivity) {
            Objects.requireNonNull<ComponentActivity?>(activity)
            if (currentActivity != null) {
                val oldActivity: ComponentActivity? = currentActivity!!.get()
                if (oldActivity === activity) {
                    return
                }
                if (oldActivity != null) {
                    getToast(oldActivity)!!.cancelInternal()
                }
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
            return toast != null && id != null && id == toast.showing
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
