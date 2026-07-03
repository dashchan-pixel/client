package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.util.ViewUtils

@SuppressLint("ViewConstructor")
class ExpandedLayout(context: Context, private val self: Boolean) :
		FrameLayout(context), ExpandedScreen.Layout {
	private var top = 0
	private var bottom = 0
	private var useGesture29 = false
	private var extraTop = 0
	private var recyclerView: RecyclerView? = null

	fun setRecyclerView(recyclerView: RecyclerView?) {
		this.recyclerView = recyclerView
	}

	override fun getRecyclerView(): RecyclerView? = recyclerView

	override fun setVerticalInsets(top: Int, bottom: Int, useGesture29: Boolean) {
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
			ViewUtils.setNewPadding(getChildAt(i), null, childTop, null, childBottom)
		}
	}
}
