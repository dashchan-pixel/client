package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HeaderItemDecoration(private val configuration: Configuration?,
		private val provider: Provider) : RecyclerView.ItemDecoration() {
	fun interface Configuration {
		fun configureHeaderView(context: Context, headerView: TextView)
	}

	fun interface Provider {
		fun getHeader(context: Context, position: Int): String?
	}

	constructor(provider: Provider) : this(null, provider)

	private val rect = Rect()
	private var headerView: TextView? = null

	private fun prepareHeaderView(parent: RecyclerView, header: String?, layout: Boolean): View {
		val headerView = headerView ?: ViewFactory.makeListTextHeader(parent).also {
			headerView = it
			configuration?.configureHeaderView(parent.context, it)
		}
		headerView.text = header
		headerView.measure(View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
		if (layout) {
			headerView.layout(0, 0, headerView.measuredWidth, headerView.measuredHeight)
		}
		return headerView
	}

	override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		for (i in 0 until parent.childCount) {
			val view = parent.getChildAt(i)
			val position = parent.getChildAdapterPosition(view)
			if (position >= 0) {
				val header = provider.getHeader(view.context, position)
				if (header != null) {
					parent.getDecoratedBoundsWithMargins(view, rect)
					c.save()
					c.translate(rect.left.toFloat(), rect.top.toFloat())
					prepareHeaderView(parent, header, true).draw(c)
					c.restore()
				}
			}
		}
	}

	override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView,
			state: RecyclerView.State) {
		val position = parent.getChildAdapterPosition(view)
		val header = if (position >= 0) provider.getHeader(view.context, position) else null
		if (header != null) {
			outRect.set(0, prepareHeaderView(parent, header, false).measuredHeight, 0, 0)
		} else {
			outRect.set(0, 0, 0, 0)
		}
	}
}
