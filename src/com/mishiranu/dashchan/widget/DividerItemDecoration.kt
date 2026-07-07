package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.max

class DividerItemDecoration(context: Context, private val callback: Callback) :
		RecyclerView.ItemDecoration() {
	class Configuration {
		internal var needed = false
		internal var start = 0
		internal var end = 0
		internal var top = 0
		internal var bottom = 0
		internal var heightValue = 0
		internal var translateValue = true

		fun need(need: Boolean): Configuration {
			this.needed = need
			return this
		}

		fun horizontal(start: Int, end: Int): Configuration {
			this.start = start
			this.end = end
			return this
		}

		fun vertical(top: Int, bottom: Int): Configuration {
			this.top = top
			this.bottom = bottom
			return this
		}

		fun height(height: Int): Configuration {
			this.heightValue = height
			return this
		}

		fun translate(translate: Boolean): Configuration {
			this.translateValue = translate
			return this
		}

		internal fun getHeight(drawable: Drawable): Int = max(drawable.intrinsicWidth, heightValue)

		internal fun getTotalHeight(drawable: Drawable): Int = top + getHeight(drawable) + bottom
	}

	fun interface Callback {
		fun configure(configuration: Configuration, position: Int): Configuration
	}

	fun interface AboveCallback {
		fun shouldPlaceAbove(position: Int): Boolean
	}

	fun interface SkipCallback {
		fun shouldSkipDivider(position: Int): Boolean
	}

	private val drawable: Drawable = ResourceUtils.getDrawable(context, android.R.attr.listDivider, 0)!!
	private val configuration = Configuration()
	private val rect = Rect()

	private var aboveCallback: AboveCallback? = null
	private var skipCallback: SkipCallback? = null

	fun setAboveCallback(aboveCallback: AboveCallback?) {
		this.aboveCallback = aboveCallback
	}

	fun setSkipCallback(skipCallback: SkipCallback?) {
		this.skipCallback = skipCallback
	}

	private fun drawDivider(canvas: Canvas, top: Int, left: Int, right: Int, height: Int,
			view: View, translate: Boolean) {
		var top = top
		if (translate) {
			top = (top + view.translationY).toInt()
		}
		drawable.setBounds(left, top, right, top + height)
		drawable.draw(canvas)
	}

	override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		val childCount = parent.childCount
		val left = parent.paddingLeft
		val right = parent.width - parent.paddingRight
		val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
		for (i in 0 until childCount) {
			val view = parent.getChildAt(i)
			val position = parent.getChildAdapterPosition(view)
			if (skipCallback?.shouldSkipDivider(position) == true) {
				continue
			}
			if (position >= 0) {
				callback.configure(configuration, position)
				if (configuration.needed) {
					val currentLeft = left + (if (rtl) configuration.end else configuration.start)
					val currentRight = right - (if (rtl) configuration.start else configuration.end)
					parent.getDecoratedBoundsWithMargins(view, rect)
					val height = configuration.getHeight(drawable)
					if (position == parent.adapter!!.itemCount - 1) {
						drawDivider(c, rect.bottom - configuration.bottom,
								currentLeft, currentRight, height, view, configuration.translateValue)
					} else {
						val toNext = aboveCallback?.shouldPlaceAbove(position + 1) == true
						val top = if (toNext) rect.bottom + configuration.top
								else rect.bottom - configuration.bottom - height
						drawDivider(c, top, currentLeft, currentRight, height, view, configuration.translateValue)
					}
				}
			}
		}
	}

	override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
		val position = parent.getChildAdapterPosition(view)
		if (position >= 0) {
			val last = position == parent.adapter!!.itemCount - 1
			var top = 0
			var bottom = 0
			if (position > 0 && aboveCallback?.shouldPlaceAbove(position) == true) {
				callback.configure(configuration, position - 1)
				top = if (configuration.needed) configuration.getTotalHeight(drawable) else 0
			}
			if (!last && (aboveCallback == null || aboveCallback?.shouldPlaceAbove(position + 1) != true)) {
				callback.configure(configuration, position)
				bottom = if (configuration.needed) configuration.getTotalHeight(drawable) else 0
			}
			outRect.set(0, top, 0, bottom)
		} else {
			outRect.set(0, 0, 0, 0)
		}
	}
}
