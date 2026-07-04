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

class LinebreakLayout : ViewGroup {
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

	private val postMeasurements = ArrayList<View>()

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthUnspecified = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
		val vertialPaddings = paddingTop + paddingBottom
		val horizontalPaddings = paddingLeft + paddingRight
		val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - horizontalPaddings
		var minWidth = 0
		var minHeight = 0
		var lineWidth = 0
		var lineHeight = 0
		val postMeasurements = this.postMeasurements
		val horizontalSpacing = this.horizontalSpacing
		val verticalSpacing = this.verticalSpacing
		val count = childCount
		for (i in 0 until count) {
			var child = getChildAt(i)
			var layoutParams = child.layoutParams
			val measure = child.visibility != View.GONE
			val linebreak = measure && layoutParams.width == LayoutParams.MATCH_PARENT
			if (measure) {
				if (layoutParams.height == LayoutParams.MATCH_PARENT) {
					postMeasurements.add(child)
				} else {
					lineWidth += measureChild(child, layoutParams, widthUnspecified, linebreak, maxWidth, lineWidth,
							heightMeasureSpec, vertialPaddings, 0, horizontalSpacing)
					lineHeight = Math.max(lineHeight, child.measuredHeight)
				}
			}
			val last = i + 1 == count
			if (linebreak || last) {
				for (j in postMeasurements.indices) {
					child = postMeasurements[j]
					lineWidth += measureChild(child, layoutParams, widthUnspecified, linebreak, maxWidth, lineWidth,
							heightMeasureSpec, vertialPaddings, lineHeight, horizontalSpacing)
				}
				postMeasurements.clear()
				minWidth = Math.max(minWidth, lineWidth)
				minHeight += lineHeight
				lineWidth = 0
				lineHeight = 0
				if (!last) {
					minHeight += verticalSpacing
				}
			}
		}
		minWidth += horizontalPaddings
		minHeight += vertialPaddings
		minWidth = Math.max(minWidth, suggestedMinimumWidth)
		minHeight = Math.max(minHeight, suggestedMinimumHeight)
		setMeasuredDimension(resolveSize(minWidth, widthMeasureSpec), resolveSize(minHeight, heightMeasureSpec))
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		val left = paddingLeft
		val top = paddingTop
		val right = r - l - paddingRight
		var rleft = 0
		var rtop = 0
		var lineHeight = 0
		var newLine = true
		val horizontalSpacing = this.horizontalSpacing
		val verticalSpacing = this.verticalSpacing
		val count = childCount
		for (i in 0 until count) {
			if (newLine) {
				lineHeight = 0
				for (j in i until count) {
					val child = getChildAt(j)
					if (child.visibility != View.GONE) {
						val layoutParams = child.layoutParams
						lineHeight = Math.max(lineHeight, child.measuredHeight)
						// Line break
						if (layoutParams.width == LayoutParams.MATCH_PARENT) {
							break
						}
					}
				}
				newLine = false
			}
			val child = getChildAt(i)
			if (child.visibility != View.GONE) {
				val layoutParams = child.layoutParams
				val width = child.measuredWidth
				val height = child.measuredHeight
				// + 1 for ceil
				val dy = (lineHeight - height + 1) / 2
				val cleft = left + rleft
				val ctop = top + rtop + dy
				if (cleft < right) {
					child.layout(cleft, ctop, Math.min(cleft + width, right), ctop + height)
				}
				rleft += width + horizontalSpacing
				// Line break
				if (layoutParams.width == LayoutParams.MATCH_PARENT) {
					rleft = 0
					rtop += lineHeight + verticalSpacing
					newLine = true
				}
			}
		}
	}

	override fun generateDefaultLayoutParams(): LayoutParams {
		return LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
	}

	private fun measureChild(child: View, layoutParams: LayoutParams, widthUnspecified: Boolean,
			linebreak: Boolean, maxWidth: Int, lineWidth: Int, heightMeasureSpec: Int, vertialPaddings: Int,
			matchLineHeight: Int, horizontalSpacing: Int): Int {
		val childWidthMeasureSpec = if (layoutParams.width >= 0)
			MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY)
		else if (widthUnspecified) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
		else MeasureSpec.makeMeasureSpec(Math.max(maxWidth - (if (lineWidth > 0) lineWidth + horizontalSpacing else 0),
				0), if (linebreak) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)
		val childHeightMeasureSpec = if (matchLineHeight == 0)
			getChildMeasureSpec(heightMeasureSpec, vertialPaddings, layoutParams.height)
		else MeasureSpec.makeMeasureSpec(matchLineHeight, MeasureSpec.EXACTLY)
		child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
		var childWidth = child.measuredWidth
		if (lineWidth > 0) {
			childWidth += horizontalSpacing
		}
		return childWidth
	}

	companion object {
		private val ATTRS = intArrayOf(android.R.attr.horizontalSpacing, android.R.attr.verticalSpacing)
	}
}
