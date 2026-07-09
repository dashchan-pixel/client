package com.mishiranu.dashchan.util

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.widget.EdgeEffect
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.mishiranu.dashchan.R

object ViewUtils {
	const val STATUS_OVERLAY_TRANSPARENT = 0x4d000000

	@JvmField
	val ALERT_DIALOG_LONGER_TITLE = DialogInterface.OnShowListener { dialog ->
		if (dialog is AlertDialog) {
			val view = dialog.window!!.decorView
			@Suppress("DiscouragedApi")
			var id = view.resources.getIdentifier("alertTitle", "id", "android")
			if (id == 0) {
				id = android.R.id.title
			}
			val titleView = view.findViewById<View>(id)
			if (titleView is TextView) {
				val maxLines = titleView.maxLines
				if (maxLines in 1 until 4) {
					titleView.maxLines = 4
				}
			}
		}
	}

	@JvmStatic
	fun removeFromParent(view: View) {
		val viewParent = view.parent
		if (viewParent is ViewGroup) {
			viewParent.removeView(view)
		}
	}

	@JvmStatic
	fun isDrawerLockable(configuration: Configuration): Boolean {
		// Should always result "true" for tablets in landscape mode (+ in portrait mode on large screens).
		// Sometimes it will result "true" for screens with low DPI configuration, which is intentional.
		return configuration.screenWidthDp >= 720
	}

	@JvmStatic
	fun isGestureNavigationOverlap(view: View, checkLeft: Boolean, checkRight: Boolean): Boolean {
		val windowInsets = view.rootWindowInsets
		val insets = windowInsets.getInsets(WindowInsets.Type.systemGestures())
		if (checkLeft && insets.left > 0 || checkRight && insets.right > 0) {
			var left = view.left
			var parentView = view.parent as View
			while (true) {
				left += parentView.left
				val parent = parentView.parent
				if (parent is View) {
					parentView = parent
				} else {
					break
				}
			}
			val right = parentView.width - left - view.width
			return checkLeft && insets.left > left || checkRight && insets.right > right
		}
		return false
	}

	@JvmStatic
	fun setTextSizeScaled(textView: TextView, sizeSp: Int) {
		// Avoid fractional sizes (the same logic is used for sizes specified in XML)
		val sizePx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(),
				textView.resources.displayMetrics) + 0.5f).toInt()
		textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx.toFloat())
	}

	@JvmStatic
	fun applyScaleSize(scale: Float, vararg views: View?) {
		for (view in views) {
			if (view != null) {
				if (view is TextView) {
					val size = (view.textSize * scale + 0.5f).toInt()
					view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
				}
				val params = view.layoutParams
				if (params != null) {
					if (params.width > 0) {
						params.width = (params.width * scale).toInt()
					}
					if (params.height > 0) {
						params.height = (params.height * scale).toInt()
					}
				}
			}
		}
	}

	@JvmStatic
	fun applyScaleMarginLR(scale: Float, vararg views: View?) {
		for (view in views) {
			if (view != null) {
				val params = view.layoutParams
				if (params is ViewGroup.MarginLayoutParams) {
					params.leftMargin = (params.leftMargin * scale).toInt()
					params.rightMargin = (params.rightMargin * scale).toInt()
				}
			}
		}
	}

	@JvmStatic
	fun makeRoundedCorners(view: View, radius: Int, withPaddings: Boolean) {
		view.clipToOutline = true
		view.outlineProvider = object : ViewOutlineProvider() {
			private val rect = Rect()

			override fun getOutline(view: View, outline: Outline) {
				val rect = this.rect
				if (withPaddings) {
					rect.set(view.paddingLeft, view.paddingTop, view.width - view.paddingRight,
							view.height - view.paddingBottom)
				} else {
					rect.set(0, 0, view.width, view.height)
				}
				outline.setRoundRect(rect, radius.toFloat())
			}
		}
	}

	@JvmStatic
	fun setEdgeEffectColor(scrollView: ScrollView, color: Int) {
		scrollView.setEdgeEffectColor(color)
	}

	@JvmStatic
	fun setEdgeEffectColor(edgeEffect: EdgeEffect, color: Int) {
		edgeEffect.color = color
	}

	@JvmStatic
	fun setSelectableItemBackground(view: View) {
		setBackgroundPreservePadding(view, ResourceUtils
				.getDrawable(view.context, android.R.attr.selectableItemBackground, 0))
	}

	@JvmStatic
	fun setBackgroundPreservePadding(view: View, drawable: Drawable?) {
		// Setting background drawable may reset padding
		val left = view.paddingLeft
		val top = view.paddingTop
		val right = view.paddingRight
		val bottom = view.paddingBottom
		view.background = drawable
		view.setPadding(left, top, right, bottom)
	}

	@JvmStatic
	fun setNewPadding(view: View, left: Int?, top: Int?, right: Int?, bottom: Int?) {
		val oldLeft = view.paddingLeft
		val oldTop = view.paddingTop
		val oldRight = view.paddingRight
		val oldBottom = view.paddingBottom
		val newLeft = left ?: oldLeft
		val newTop = top ?: oldTop
		val newRight = right ?: oldRight
		val newBottom = bottom ?: oldBottom
		if (oldLeft != newLeft || oldTop != newTop || oldRight != newRight || oldBottom != newBottom) {
			view.setPadding(newLeft, newTop, newRight, newBottom)
		}
	}

	@JvmStatic
	fun setNewMargin(view: View, left: Int?, top: Int?, right: Int?, bottom: Int?) {
		val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
		var changed = false
		if (left != null && layoutParams.leftMargin != left) {
			layoutParams.leftMargin = left
			changed = true
		}
		if (top != null && layoutParams.topMargin != top) {
			layoutParams.topMargin = top
			changed = true
		}
		if (right != null && layoutParams.rightMargin != right) {
			layoutParams.rightMargin = right
			changed = true
		}
		if (bottom != null && layoutParams.bottomMargin != bottom) {
			layoutParams.bottomMargin = bottom
			changed = true
		}
		if (changed) {
			view.requestLayout()
		}
	}

	@JvmStatic
	fun setNewMarginRelative(view: View, start: Int?, top: Int?, end: Int?, bottom: Int?) {
		if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
			setNewMargin(view, end, top, start, bottom)
		} else {
			setNewMargin(view, start, top, end, bottom)
		}
	}

	@JvmStatic
	fun applyMonospaceTypeface(editText: EditText) {
		val initialTypeface = editText.typeface
		val monospaceTypeface = Typeface.MONOSPACE
		val empty = booleanArrayOf(true)
		val textWatcher = object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

			override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

			override fun afterTextChanged(s: Editable) {
				val newEmpty = s.isEmpty()
				if (newEmpty != empty[0]) {
					empty[0] = newEmpty
					editText.typeface = if (newEmpty) initialTypeface else monospaceTypeface
				}
			}
		}
		textWatcher.afterTextChanged(editText.text)
		editText.addTextChangedListener(textWatcher)
	}

	@JvmStatic
	fun setWindowLayoutFullscreen(window: Window) {
		WindowCompat.setDecorFitsSystemWindows(window, false)
	}

	@JvmStatic
	fun drawSystemInsetsOver(view: View, canvas: Canvas, gestureNavigation: Boolean) {
		var paint = view.getTag(R.id.tag_insets_draw_data) as Paint?
		if (paint == null) {
			paint = Paint(Paint.ANTI_ALIAS_FLAG)
			paint.color = STATUS_OVERLAY_TRANSPARENT
			paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
			view.setTag(R.id.tag_insets_draw_data, paint)
		}
		val x = view.scrollX
		val y = view.scrollY
		val translate = x != 0 || y != 0
		if (translate) {
			canvas.save()
			canvas.translate(x.toFloat(), y.toFloat())
		}
		val left = view.paddingLeft
		val top = view.paddingTop
		val right = view.paddingRight
		val bottom = if (gestureNavigation) 0 else view.paddingBottom
		val width = view.width
		val height = view.height
		// Draw system insets over dialogs
		canvas.drawRect(0f, 0f, width.toFloat(), top.toFloat(), paint)
		canvas.drawRect(0f, (height - bottom).toFloat(), width.toFloat(), height.toFloat(), paint)
		canvas.drawRect(0f, top.toFloat(), left.toFloat(), (height - bottom).toFloat(), paint)
		canvas.drawRect((width - right).toFloat(), top.toFloat(), width.toFloat(),
				(height - bottom).toFloat(), paint)
		if (translate) {
			canvas.restore()
		}
	}

	@JvmStatic
	fun getDecorView(view: View): View {
		var decorView = view
		while (true) {
			val viewParent = decorView.parent
			if (viewParent is View) {
				decorView = viewParent
			} else {
				break
			}
		}
		return decorView
	}

	private class WindowFocusListenerView(context: Context) : View(context) {
		val listeners = ArrayList<OnFocusChangeListener>()

		override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
			super.onWindowFocusChanged(hasWindowFocus)
			for (listener in listeners) {
				listener.onFocusChange(parent as View, hasWindowFocus)
			}
		}

		companion object {
			fun get(view: View, create: Boolean): WindowFocusListenerView? {
				val decorView = getDecorView(view) as ViewGroup
				val childCount = decorView.childCount
				for (i in 0 until childCount) {
					val child = decorView.getChildAt(i)
					if (child is WindowFocusListenerView) {
						return child
					}
				}
				if (create) {
					val listenerView = WindowFocusListenerView(decorView.context)
					decorView.addView(listenerView, 0, 0)
					return listenerView
				}
				return null
			}
		}
	}

	@JvmStatic
	fun addWindowFocusListener(view: View, listener: View.OnFocusChangeListener) {
		val listenerView = WindowFocusListenerView.get(view, true)!!
		listenerView.listeners.add(listener)
	}

	@JvmStatic
	fun removeWindowFocusListener(view: View, listener: View.OnFocusChangeListener) {
		val listenerView = WindowFocusListenerView.get(view, false)
		listenerView?.listeners?.remove(listener)
	}

	@JvmStatic
	fun getOutlineRect(outline: Outline, outRect: Rect): Boolean = outline.getRect(outRect)

	@JvmStatic
	fun getOutlineRadius(outline: Outline): Float = outline.radius
}
