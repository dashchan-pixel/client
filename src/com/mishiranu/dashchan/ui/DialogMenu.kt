package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.graphics.BaseDrawable
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.ThemeEngine

class DialogMenu(private val context: Context) {
	private val listItems = ArrayList<ListItem>()
	private var title: CharSequence? = null

	private enum class ViewType { ITEM, MORE, CHECK }

	private class ListItem(
			val viewType: ViewType,
			val title: String,
			val checked: Boolean,
			val runnable: Runnable)

	fun setTitle(title: CharSequence?): DialogMenu {
		this.title = title
		return this
	}

	private fun add(viewType: ViewType, title: String, checked: Boolean, callback: Runnable): DialogMenu {
		listItems.add(ListItem(viewType, title, checked, callback))
		return this
	}

	fun add(titleRes: Int, runnable: Runnable): DialogMenu {
		return add(ViewType.ITEM, context.getString(titleRes), false, runnable)
	}

	fun add(title: String, runnable: Runnable): DialogMenu {
		return add(ViewType.ITEM, title, false, runnable)
	}

	fun addMore(titleRes: Int, runnable: Runnable): DialogMenu {
		return add(ViewType.MORE, context.getString(titleRes), false, runnable)
	}

	fun addCheck(titleRes: Int, checked: Boolean, runnable: Runnable): DialogMenu {
		return add(ViewType.CHECK, context.getString(titleRes), checked, runnable)
	}

	private fun getRecyclerView(dialog: AlertDialog): RecyclerView {
		val custom = dialog.findViewById<FrameLayout>(android.R.id.custom)
		return custom.getChildAt(0) as RecyclerView
	}

	private fun setAdapter(dialog: AlertDialog, recyclerView: RecyclerView) {
		recyclerView.adapter = Adapter(dialog.context, { dialog.dismiss() }, ArrayList(listItems))
	}

	private fun updateInternal(dialog: AlertDialog, recyclerView: RecyclerView?) {
		dialog.setTitle(title)
		if (dialog.isShowing) {
			setAdapter(dialog, recyclerView ?: getRecyclerView(dialog))
		} else if (recyclerView != null) {
			dialog.setOnShowListener(ViewUtils.ALERT_DIALOG_LONGER_TITLE)
			setAdapter(dialog, recyclerView)
		} else {
			dialog.setOnShowListener { d ->
				ViewUtils.ALERT_DIALOG_LONGER_TITLE.onShow(d)
				setAdapter(dialog, getRecyclerView(dialog))
			}
		}
	}

	fun update(dialog: AlertDialog) {
		updateInternal(dialog, null)
	}

	fun create(): AlertDialog {
		val builder = AlertDialog.Builder(context)
		val recyclerView: RecyclerView = PaddedRecyclerView(builder.context)
		recyclerView.isMotionEventSplittingEnabled = false
		recyclerView.isVerticalScrollBarEnabled = true
		recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
		val density = ResourceUtils.obtainDensity(recyclerView)
		recyclerView.clipToPadding = false
		recyclerView.setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
		val dialog = builder.setView(recyclerView).create()
		updateInternal(dialog, recyclerView)
		return dialog
	}

	private class Adapter(context: Context, private val dismiss: Runnable, private val listItems: List<ListItem>) :
			RecyclerView.Adapter<ViewHolder>(), ListViewUtils.ClickCallback<ListItem, ViewHolder> {
		private val layoutResId = ResourceUtils.obtainAlertDialogLayoutResId(context,
				ResourceUtils.DialogLayout.SIMPLE)

		override fun getItemViewType(position: Int): Int {
			return listItems[position].viewType.ordinal
		}

		override fun getItemCount(): Int {
			return listItems.size
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val holder = ViewHolder(parent, layoutResId, ViewType.values()[viewType])
			ListViewUtils.bind(holder, false, ListViewUtils.DataCallback { listItems[it] }, this)
			return holder
		}

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val listItem = listItems[position]
			holder.textView.text = listItem.title
			holder.checkBox?.isChecked = listItem.checked
		}

		override fun onItemClick(holder: ViewHolder, position: Int, item: ListItem?, longClick: Boolean): Boolean {
			dismiss.run()
			ConcurrentUtils.HANDLER.post(item!!.runnable)
			return true
		}
	}

	private class ViewHolder(parent: ViewGroup, layoutResId: Int, viewType: ViewType) :
			RecyclerView.ViewHolder(if (viewType != ViewType.ITEM) LinearLayout(parent.context)
					else LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)) {
		val textView: TextView
		val checkBox: CheckBox?

		init {
			if (viewType != ViewType.ITEM) {
				val linearLayout = itemView as LinearLayout
				linearLayout.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
						RecyclerView.LayoutParams.WRAP_CONTENT)
				linearLayout.orientation = LinearLayout.HORIZONTAL
				linearLayout.gravity = Gravity.CENTER_VERTICAL
				val view = LayoutInflater.from(parent.context).inflate(layoutResId, linearLayout, false)
				linearLayout.addView(view, LinearLayout.LayoutParams(0,
						LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
				val density = ResourceUtils.obtainDensity(view)
				// Align to checkbox inner padding
				val padding = view.paddingEnd - (2f * density).toInt()
				view.setPaddingRelative(view.paddingStart,
						view.paddingTop, 0, view.paddingBottom)
				val contentSize = (24f * density).toInt()
				val contentLayout = FrameLayout(parent.context)
				linearLayout.addView(contentLayout, padding + contentSize + padding,
						LinearLayout.LayoutParams.MATCH_PARENT)
				if (viewType == ViewType.MORE) {
					var drawable: Drawable? = null
					val attrs = intArrayOf(android.R.attr.subMenuArrow)
					val typedArray = parent.context.obtainStyledAttributes(null,
							attrs, android.R.attr.listMenuViewStyle, 0)
					try {
						drawable = typedArray.getDrawable(0)
					} finally {
						typedArray.recycle()
					}
					if (drawable == null) {
						drawable = SubMenuArrowDrawable(parent)
					}
					val imageView = ImageView(parent.context)
					imageView.scaleType = ImageView.ScaleType.CENTER
					imageView.setImageDrawable(drawable)
					contentLayout.addView(imageView, FrameLayout.LayoutParams.WRAP_CONTENT,
							FrameLayout.LayoutParams.WRAP_CONTENT)
					(imageView.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
				}
				if (viewType == ViewType.CHECK) {
					val checkBox = CheckBox(parent.context)
					ThemeEngine.applyStyle(checkBox)
					checkBox.isClickable = false
					checkBox.isFocusable = false
					contentLayout.addView(checkBox, LinearLayout.LayoutParams.WRAP_CONTENT,
							LinearLayout.LayoutParams.WRAP_CONTENT)
					(checkBox.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
					this.checkBox = checkBox
				} else {
					this.checkBox = null
				}
			} else {
				this.checkBox = null
			}
			textView = itemView.findViewById(android.R.id.text1)
			ViewUtils.setSelectableItemBackground(itemView)
		}
	}

	private class SubMenuArrowDrawable(view: View) : BaseDrawable() {
		private val paint = Paint()
		private val path = Path()
		private val color: ColorStateList
		private val rtl: Boolean
		private val size: Int

		init {
			val density = ResourceUtils.obtainDensity(view)
			color = ResourceUtils.getColorStateList(view.context, android.R.attr.textColorSecondary)!!
			rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
			size = (SIZE_DP * density).toInt()
		}

		override fun getIntrinsicWidth(): Int {
			return size
		}

		override fun getIntrinsicHeight(): Int {
			return size
		}

		override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
			super.setBounds(left, top, right, bottom)
			val size = Math.min(right - left, bottom - top)
			val scale = size.toFloat() / SIZE_DP
			path.rewind()
			if (rtl) {
				path.moveTo(14 * scale, 7 * scale)
				path.rLineTo(-5 * scale, 5 * scale)
				path.rLineTo(5 * scale, 5 * scale)
			} else {
				path.moveTo(10 * scale, 7 * scale)
				path.rLineTo(5 * scale, 5 * scale)
				path.rLineTo(-5 * scale, 5 * scale)
			}
			path.close()
		}

		override fun isStateful(): Boolean {
			return true
		}

		override fun onStateChange(state: IntArray): Boolean {
			invalidateSelf()
			return true
		}

		override fun draw(canvas: Canvas) {
			canvas.save()
			val left: Int
			val top: Int
			val bounds = getBounds()
			val width = bounds.width()
			val height = bounds.height()
			if (width > height) {
				left = bounds.left + (width - height) / 2
				top = bounds.top
			} else {
				left = bounds.left
				top = bounds.top + (height - width) / 2
			}
			canvas.translate(left.toFloat(), top.toFloat())
			paint.color = color.getColorForState(state, color.defaultColor)
			canvas.drawPath(path, paint)
			canvas.restore()
		}

		companion object {
			private const val SIZE_DP = 24
		}
	}
}
