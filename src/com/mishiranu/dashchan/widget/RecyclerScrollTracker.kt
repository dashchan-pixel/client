package com.mishiranu.dashchan.widget

import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class RecyclerScrollTracker(
    private val listener: OnScrollListener,
) {
    fun attach(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutListener)
    }

    fun detach(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutListener)
    }

    private var scrollingDown = false
    private var firstPosition = -1
    private var firstTop = 0

    private var slopDelta = -1
    private var totalDelta = 0

    private fun onScrolled(
        recyclerView: RecyclerView,
        dx: Int,
        dy: Int,
    ) {
        if (slopDelta == -1) {
            slopDelta = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        }
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val totalItemCount = layoutManager.itemCount
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        val lastPosition = layoutManager.findLastVisibleItemPosition()
        val first: Boolean
        val last: Boolean
        if (firstPosition < 0 || lastPosition < 0 || recyclerView.childCount == 0) {
            first = false
            last = false
            scrollingDown = false
            this.firstPosition = -1
            firstTop = 0
        } else {
            first = firstPosition == 0
            last = lastPosition == totalItemCount - 1
            val firstTop = recyclerView.getChildAt(0).top
            if (dx == 0 && dy == 0) {
                // Layout changed
                if (this.firstPosition != firstPosition || this.firstTop != firstTop) {
                    totalDelta = 0
                    scrollingDown = this.firstPosition >= 0 &&
                        (
                            firstPosition > this.firstPosition ||
                                (firstPosition == this.firstPosition && firstTop > this.firstTop)
                        )
                }
            } else if (dy != 0) {
                totalDelta += dy
                if (abs(totalDelta) > slopDelta) {
                    scrollingDown = totalDelta > 0
                    totalDelta = 0
                }
            }
            this.firstPosition = firstPosition
            this.firstTop = firstTop
        }
        listener.onScroll(recyclerView, scrollingDown, totalItemCount, first, last)
    }

    private var scrollHandled = false

    private val scrollListener =
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: RecyclerView,
                dx: Int,
                dy: Int,
            ) {
                // May call with (dx, dy) == (0, 0) during layout (e.g. after scrollToPosition)
                this@RecyclerScrollTracker.onScrolled(recyclerView, dx, dy)
                scrollHandled = true
            }

            override fun onScrollStateChanged(
                recyclerView: RecyclerView,
                newState: Int,
            ) {}
        }

    // RecyclerView won't call onScroll after scrollToPosition if first and last items weren't changed
    private val layoutListener =
        View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (scrollHandled) {
                scrollHandled = false
            } else {
                onScrolled(v as RecyclerView, 0, 0)
            }
        }

    interface OnScrollListener {
        fun onScroll(
            view: ViewGroup,
            scrollingDown: Boolean,
            totalItemCount: Int,
            first: Boolean,
            last: Boolean,
        )
    }
}
