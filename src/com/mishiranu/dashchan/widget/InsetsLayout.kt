package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.mishiranu.dashchan.R

class InsetsLayout(
    context: Context,
) : FrameLayout(context) {
    class Insets(
        @JvmField val left: Int,
        @JvmField val top: Int,
        @JvmField val right: Int,
        @JvmField val bottom: Int,
    ) {
        constructor(insets: android.graphics.Insets) : this(insets.left, insets.top, insets.right, insets.bottom)

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is Insets) {
                return left == other.left &&
                    top == other.top &&
                    right == other.right &&
                    bottom == other.bottom
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + left
            result = prime * result + top
            result = prime * result + right
            result = prime * result + bottom
            return result
        }

        companion object {
            @JvmField
            val DEFAULT = Insets(0, 0, 0, 0)
        }
    }

    class Apply internal constructor(
        @JvmField val window: Insets,
        @JvmField val useGesture29: Boolean,
        @JvmField val imeBottom29: Int,
    ) {
        fun get(): Insets {
            val bottom = Math.max(window.bottom, imeBottom29)
            return if (bottom != window.bottom) {
                Insets(window.left, window.top, window.right, window.bottom)
            } else {
                window
            }
        }
    }

    fun interface OnApplyInsetsListener {
        fun onApplyInsets(apply: Apply)
    }

    private var onApplyInsetsListener: OnApplyInsetsListener? = null

    init {
        super.setFitsSystemWindows(true)
        super.setClipToPadding(false)
    }

    fun setOnApplyInsetsListener(listener: OnApplyInsetsListener?) {
        onApplyInsetsListener = listener
    }

    fun setOnApplyInsetsTarget(view: View?) {
        setOnApplyInsetsListener(
            if (view != null) {
                OnApplyInsetsListener { applyData ->
                    val insets = applyData.get()
                    view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                    view.setTag(R.id.tag_insets_gesture_navigation, applyData.useGesture29)
                }
            } else {
                null
            },
        )
    }

    override fun setFitsSystemWindows(fitSystemWindows: Boolean): Unit = throw UnsupportedOperationException()

    override fun setClipToPadding(clipToPadding: Boolean): Unit = throw UnsupportedOperationException()

    private var lastWindow = Insets.DEFAULT
    private var lastGesture29 = Insets.DEFAULT
    private var lastIme29 = Insets.DEFAULT

    private fun onInsetsChangedInternal(
        window: Insets,
        gesture29: Insets,
        ime29: Insets,
    ) {
        if (lastWindow != window || lastGesture29 != gesture29 || lastIme29 != ime29) {
            lastWindow = window
            lastGesture29 = gesture29
            lastIme29 = ime29
            onApplyInsetsListener?.let {
                val useGesture29 = gesture29.left > window.left || gesture29.right > window.right
                it.onApplyInsets(Apply(window, useGesture29, ime29.bottom))
            }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        try {
            return super.onApplyWindowInsets(insets)
        } finally {
            setPadding(0, 0, 0, 0)
            val window: Insets
            val realWindow =
                Insets(
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars()),
                )
            val gesture29 = Insets(insets.getInsets(WindowInsets.Type.systemGestures()))
            val ime29 = Insets(insets.getInsets(WindowInsets.Type.ime()))
            window =
                if (ime29.bottom > realWindow.bottom) {
                    // Assume keyboard can be at the bottom only
                    Insets(realWindow.left, realWindow.top, realWindow.right, 0)
                } else {
                    realWindow
                }
            onInsetsChangedInternal(window, gesture29, ime29)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun fitSystemWindows(insets: Rect): Boolean = super.fitSystemWindows(insets)

    companion object {
        @JvmStatic
        fun isTargetGesture29(view: View): Boolean {
            val gestureNavigation = view.getTag(R.id.tag_insets_gesture_navigation)
            return gestureNavigation != null && gestureNavigation as Boolean
        }
    }
}
