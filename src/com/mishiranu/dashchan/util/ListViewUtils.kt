package com.mishiranu.dashchan.util

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.AdapterView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.widget.ThemeEngine

object ListViewUtils {
	fun interface DataCallback<T> {
		fun getData(position: Int): T
	}

	fun interface ClickCallback<T, VH> {
		fun onItemClick(holder: VH, position: Int, item: T?, longClick: Boolean): Boolean
	}

	interface SimpleCallback<T> : ClickCallback<T, RecyclerView.ViewHolder> {
		fun onItemClick(item: T?)
		fun onItemLongClick(item: T?): Boolean

		override fun onItemClick(holder: RecyclerView.ViewHolder, position: Int,
				item: T?, longClick: Boolean): Boolean {
			return if (longClick) {
				onItemLongClick(item)
			} else {
				onItemClick(item)
				true
			}
		}
	}

	private fun <T, VH : RecyclerView.ViewHolder> handleClick(holder: VH,
			longClick: Boolean, dataCallback: DataCallback<T>?, callback: ClickCallback<T, VH>): Boolean {
		val position = holder.bindingAdapterPosition
		// position can be NO_POSITION if click event is fired after notifyDataSetChanged
		return position >= 0 && callback.onItemClick(holder, position,
				dataCallback?.getData(position), longClick)
	}

	@JvmStatic
	fun <T, VH : RecyclerView.ViewHolder> bind(holder: VH, view: View,
			longClick: Boolean, dataCallback: DataCallback<T>?, clickCallback: ClickCallback<T, VH>): VH {
		view.setOnClickListener { handleClick(holder, false, dataCallback, clickCallback) }
		if (longClick) {
			view.setOnLongClickListener { handleClick(holder, true, dataCallback, clickCallback) }
		}
		return holder
	}

	@JvmStatic
	fun <T, VH : RecyclerView.ViewHolder> bind(holder: VH,
			longClick: Boolean, dataCallback: DataCallback<T>?, clickCallback: ClickCallback<T, VH>): VH {
		return bind(holder, holder.itemView, longClick, dataCallback, clickCallback)
	}

	@JvmStatic
	fun getRootViewInList(view: View?): View? {
		var current = view
		while (current != null) {
			val parent = current.parent
			if (parent == null || parent is AdapterView<*> || parent is RecyclerView) {
				break
			}
			current = parent as? View
		}
		return current
	}

	@JvmStatic
	fun <T> getViewHolder(view: View, clazz: Class<T>): T? {
		val rootView = getRootViewInList(view)!!
		val parent = rootView.parent as View
		val holder: Any? = if (parent is RecyclerView) {
			parent.getChildViewHolder(rootView)
		} else {
			rootView.tag
		}
		@Suppress("UNCHECKED_CAST")
		return if (holder != null && clazz.isAssignableFrom(holder.javaClass)) holder as T else null
	}

	// Unlimited pool size for immediate scrolls to improve performance
	class UnlimitedRecycledViewPool : RecyclerView.RecycledViewPool() {
		override fun putRecycledView(scrap: RecyclerView.ViewHolder) {
			setMaxRecycledViews(scrap.itemViewType, Int.MAX_VALUE)
			super.putRecycledView(scrap)
		}
	}

	private class TopLinearSmoothScroller(context: Context, targetPosition: Int) :
			LinearSmoothScroller(context) {
		init {
			setTargetPosition(targetPosition)
		}

		override fun getVerticalSnapPreference(): Int = SNAP_TO_START
	}

	@JvmStatic
	fun getScrollJumpThreshold(context: Context): Int {
		return context.resources.configuration.screenHeightDp / 40
	}

	@JvmStatic
	fun smoothScrollToPosition(recyclerView: RecyclerView, position: Int) {
		val layoutManager = recyclerView.layoutManager as LinearLayoutManager
		if (AnimationUtils.areAnimatorsEnabled()) {
			val first = layoutManager.findFirstVisibleItemPosition()
			if (first >= 0) {
				val jumpThreshold = getScrollJumpThreshold(recyclerView.context)
				if (position > first + jumpThreshold) {
					layoutManager.scrollToPositionWithOffset(position - jumpThreshold, 0)
				} else if (position < first - jumpThreshold) {
					layoutManager.scrollToPositionWithOffset(position + jumpThreshold, 0)
				}
			}
			layoutManager.startSmoothScroll(TopLinearSmoothScroller(recyclerView.context, position))
		} else {
			layoutManager.scrollToPositionWithOffset(position, 0)
		}
	}

	@JvmStatic
	fun colorizeListThumbDrawable4(context: Context, drawable: Drawable): Drawable {
		val colorDefault = ThemeEngine.getTheme(context)!!.accent
		val colorPressed = GraphicsUtils.modifyColorGain(colorDefault, 4f / 3f)
		if (colorDefault != 0 && colorPressed != 0) {
			val pressedState = intArrayOf(android.R.attr.state_pressed)
			val defaultState = intArrayOf()
			drawable.state = pressedState
			val pressedDrawable = drawable.current
			drawable.state = defaultState
			val defaultDrawable = drawable.current
			if (defaultDrawable !== pressedDrawable) {
				val stateListDrawable = object : StateListDrawable() {
					override fun onStateChange(stateSet: IntArray): Boolean {
						val result = super.onStateChange(stateSet)
						if (result) {
							colorFilter = PorterDuffColorFilter(if (current === pressedDrawable) colorPressed
							else colorDefault, PorterDuff.Mode.SRC_IN)
						}
						return result
					}
				}
				stateListDrawable.addState(pressedState, pressedDrawable)
				stateListDrawable.addState(defaultState, defaultDrawable)
				return stateListDrawable
			}
		}
		return drawable
	}
}
