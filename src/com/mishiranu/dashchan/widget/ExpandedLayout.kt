package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
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

    fun setRecyclerView(recyclerView: RecyclerView?) {
        this.recyclerViewField = recyclerView
    }

    override fun getRecyclerView(): RecyclerView? = recyclerViewField

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
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val extra = if (child === recyclerViewField) extraBottom else 0
            ViewUtils.setNewPadding(child, null, childTop, null, childBottom + extra)
        }
    }
}
