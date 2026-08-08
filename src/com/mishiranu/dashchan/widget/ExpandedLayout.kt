package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.util.ViewUtils

@SuppressLint("ViewConstructor")
class ExpandedLayout(
    context: Context,
    private val self: Boolean,
) : FrameLayout(context),
    ExpandedScreen.Layout {
    private var top = 0
    private var bottom = 0
    private var useGesture29 = false
    private var extraTop = 0
    private var extraBottom = 0
    private var recyclerViewField: RecyclerView? = null
    private var insetsTarget: View? = null

    fun setRecyclerView(recyclerView: RecyclerView?) {
        this.recyclerViewField = recyclerView
    }

    override fun getRecyclerView(): RecyclerView? = recyclerViewField

    /**
     * The view a direct child's vertical insets are handed down to, when the child is a container
     * rather than the content itself — a list under a bar pinned above it. The insets belong to what
     * scrolls: taken by the container they would only shorten it, so the last item would stop above
     * the gesture bar instead of scrolling through it, and the bar above the list would grow by a
     * navigation bar it is nowhere near.
     */
    fun setInsetsTarget(insetsTarget: View?) {
        if (this.insetsTarget !== insetsTarget) {
            this.insetsTarget = insetsTarget
            applyPadding()
        }
    }

    override fun setVerticalInsets(
        top: Int,
        bottom: Int,
        useGesture29: Boolean,
    ) {
        if (this.top != top || this.bottom != bottom || this.useGesture29 != useGesture29) {
            this.top = top
            this.bottom = bottom
            this.useGesture29 = useGesture29
            applyPadding()
        }
    }

    fun setExtraTop(extraTop: Int) {
        if (this.extraTop != extraTop) {
            this.extraTop = extraTop
            applyPadding()
        }
    }

    /**
     * Room kept below the end of the [recycler view][setRecyclerView]'s content, for whatever floats
     * over it — today the [FloatingToolbar], which would otherwise cover the last item of a list
     * scrolled to its bottom. It is the list's padding rather than the layout's because the point is
     * that content still *scrolls* through that strip; and it is the list's alone, since a view that
     * floats must not be pushed up by the room it is being given.
     */
    fun setExtraBottom(extraBottom: Int) {
        if (this.extraBottom != extraBottom) {
            this.extraBottom = extraBottom
            applyPadding()
        }
    }

    private fun applyPadding() {
        val childTop: Int
        val childBottom: Int
        if (!self) {
            childTop = top
            childBottom = bottom
        } else if (useGesture29) {
            childTop = 0
            childBottom = bottom
        } else {
            childTop = 0
            childBottom = 0
        }
        ViewUtils.setNewPadding(this, null, top + extraTop - childTop, null, bottom - childBottom)
        val insetsTarget = this.insetsTarget
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (insetsTarget != null && insetsTarget.parent === child) {
                ViewUtils.setNewPadding(insetsTarget, null, childTop, null, childBottom)
            } else {
                val extra = if (child === recyclerViewField) extraBottom else 0
                ViewUtils.setNewPadding(child, null, childTop, null, childBottom + extra)
            }
        }
    }
}
