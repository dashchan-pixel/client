package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils
import java.util.Collections

class ImportantPostsMarksFastScrollBarDecoration(
    context: Context,
) {
    private val userPostMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val replyMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val postMarkMinSize: Float = ResourceUtils.obtainDensity(context)
    private var data: Data? = null

    init {
        userPostMarkPaint.color = ResourceUtils.getColor(context, R.attr.colorPostMarkUserPost)
        replyMarkPaint.color = ResourceUtils.getColor(context, R.attr.colorPostMarkReply)
    }

    fun setData(data: Data?) {
        this.data = data
    }

    fun hasMarks(): Boolean {
        val data = data ?: return false
        return data.totalPostsCount > 0 &&
            !(data.userPostsPositions.isEmpty() && data.repliesPositions.isEmpty())
    }

    fun draw(
        scrollBarLeft: Int,
        scrollBarTop: Int,
        scrollBarRight: Int,
        scrollBarBottom: Int,
        canvas: Canvas,
    ) {
        val data = data
        if (data != null && data.totalPostsCount > 0) {
            val scrollBarHeight = scrollBarBottom - scrollBarTop
            val postMarkRealHeight = scrollBarHeight / data.totalPostsCount.toFloat()
            drawPostMarks(
                data.userPostsPositions,
                userPostMarkPaint,
                postMarkRealHeight,
                scrollBarLeft,
                scrollBarTop,
                scrollBarRight,
                canvas,
            )
            drawPostMarks(
                data.repliesPositions,
                replyMarkPaint,
                postMarkRealHeight,
                scrollBarLeft,
                scrollBarTop,
                scrollBarRight,
                canvas,
            )
        }
    }

    private fun drawPostMarks(
        postPositions: List<Int>,
        postMarkPaint: Paint,
        postMarkRealHeight: Float,
        scrollBarLeft: Int,
        scrollBarTop: Int,
        scrollBarRight: Int,
        canvas: Canvas,
    ) {
        val lastPostPositionIndex = postPositions.size - 1
        var postPositionIndex = 0
        while (postPositionIndex <= lastPostPositionIndex) {
            val postPosition = postPositions[postPositionIndex++]
            var postMarkTop = scrollBarTop + postMarkRealHeight * postPosition
            var postMarkBottom = postMarkTop + postMarkRealHeight

            var contiguousPostMarks = 1
            while (postPositionIndex < lastPostPositionIndex - 1) {
                val nextPostPosition = postPositions[postPositionIndex]
                val postPositionsAreContiguous = postPosition + 1 == nextPostPosition
                if (postPositionsAreContiguous) {
                    postMarkBottom += postMarkRealHeight
                    contiguousPostMarks++
                    postPositionIndex++
                } else {
                    break
                }
            }

            // real post mark can be too thin in very long threads, in this case increase its
            // height to postMarkMinHeight for better visibility
            val postMarkHeight = postMarkRealHeight * contiguousPostMarks
            val postMarkMinHeight = postMarkMinSize * contiguousPostMarks
            if (postMarkHeight < postMarkMinHeight) {
                val heightDifference = postMarkMinHeight - postMarkHeight
                postMarkTop -= heightDifference / 2
                postMarkBottom += heightDifference / 2
            }

            canvas.drawRect(
                scrollBarLeft.toFloat(),
                postMarkTop,
                scrollBarRight.toFloat(),
                postMarkBottom,
                postMarkPaint,
            )
        }
    }

    class Data(
        userPostsPositions: Set<Int>,
        repliesPositions: Set<Int>,
        @JvmField val totalPostsCount: Int,
    ) {
        @JvmField val userPostsPositions = ArrayList<Int>()

        @JvmField val repliesPositions = ArrayList<Int>()

        init {
            this.userPostsPositions.addAll(userPostsPositions)
            Collections.sort(this.userPostsPositions)

            for (replyPosition in repliesPositions) {
                val userPostAtPosition = Collections.binarySearch(this.userPostsPositions, replyPosition) >= 0
                if (!userPostAtPosition) {
                    this.repliesPositions.add(replyPosition)
                }
            }
            Collections.sort(this.repliesPositions)
        }
    }
}
