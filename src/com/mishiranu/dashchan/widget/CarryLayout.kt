/*
 * Copyright 2014-2016 Fukurou Mishiranu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mishiranu.dashchan.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class CarryLayout : ViewGroup {
    var horizontalSpacing = 0
        set(value) {
            field = value
            requestLayout()
        }
    var verticalSpacing = 0
        set(value) {
            field = value
            requestLayout()
        }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        val typedArray = context.obtainStyledAttributes(attrs, ATTRS, defStyleAttr, 0)
        horizontalSpacing = typedArray.getDimensionPixelSize(0, 0)
        verticalSpacing = typedArray.getDimensionPixelSize(1, 0)
        typedArray.recycle()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        var maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val widthUnspecified = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val vertialPaddings = paddingTop + paddingBottom
        val horizontalPaddings = paddingLeft + paddingRight
        maxWidth -= horizontalPaddings
        var minWidth = 0
        var minHeight = 0
        var lineWidth = 0
        var lineHeight = 0
        val horizontalSpacing = this.horizontalSpacing
        val verticalSpacing = this.verticalSpacing
        val count = childCount
        val childWidthMeasureSpec =
            if (widthUnspecified) {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            } else {
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            }
        for (i in 0 until count) {
            val child = getChildAt(i)
            val layoutParams = child.layoutParams
            val measure = child.visibility != View.GONE
            if (measure) {
                val childHeightMeasureSpec =
                    getChildMeasureSpec(
                        heightMeasureSpec,
                        vertialPaddings,
                        layoutParams.height,
                    )
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
                val childWidth = child.measuredWidth
                if (lineWidth > 0 && childWidth + lineWidth + horizontalSpacing > maxWidth) {
                    minWidth = Math.max(lineWidth, minWidth)
                    minHeight += lineHeight + verticalSpacing
                    lineHeight = child.measuredHeight
                    lineWidth = childWidth
                } else {
                    if (lineWidth > 0) {
                        lineWidth += horizontalSpacing
                    }
                    lineWidth += childWidth
                    lineHeight = Math.max(lineHeight, child.measuredHeight)
                }
            }
            if (i + 1 == count) {
                minWidth = Math.max(lineWidth, minWidth)
                minHeight += lineHeight
            }
        }
        minWidth += horizontalPaddings
        minHeight += vertialPaddings
        minWidth = Math.max(minWidth, suggestedMinimumWidth)
        minHeight = Math.max(minHeight, suggestedMinimumHeight)
        setMeasuredDimension(resolveSize(minWidth, widthMeasureSpec), resolveSize(minHeight, heightMeasureSpec))
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val left = paddingLeft
        val top = paddingTop
        val right = r - l - paddingRight
        var rleft = 0
        var rtop = 0
        var lineHeight = 0
        val horizontalSpacing = this.horizontalSpacing
        val verticalSpacing = this.verticalSpacing
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                val width = child.measuredWidth
                val height = child.measuredHeight
                if (rleft > 0 && rleft + width + horizontalSpacing > right - left) {
                    rtop += lineHeight + verticalSpacing
                    rleft = 0
                    lineHeight = 0
                }
                if (rleft > 0) {
                    rleft += horizontalSpacing
                }
                val cleft = left + rleft
                val ctop = top + rtop
                child.layout(cleft, ctop, Math.min(cleft + width, right), ctop + height)
                rleft += width
                lineHeight = Math.max(lineHeight, height)
            }
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    companion object {
        private val ATTRS = intArrayOf(android.R.attr.horizontalSpacing, android.R.attr.verticalSpacing)
    }
}
