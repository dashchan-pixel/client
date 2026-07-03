package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import androidx.drawerlayout.widget.DrawerLayout
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.abs

class CustomDrawerLayout(context: Context, attrs: AttributeSet?) : DrawerLayout(context, attrs) {
	private val touchSlop: Int
	private val edgeSize: Int

	private var expandableFromAnyPoint = true

	private var startX = 0f
	private var startY = 0f
	private var handle = false
	private var ignoreTouch = false

	init {
		val density = ResourceUtils.obtainDensity(context)
		touchSlop = ViewConfiguration.get(context).scaledTouchSlop
		edgeSize = try {
			val field = ViewDragHelper::class.java.getDeclaredField("EDGE_SIZE")
			field.isAccessible = true
			(field.getInt(null) * density).toInt()
		} catch (e: RuntimeException) {
			throw e
		} catch (e: Exception) {
			throw RuntimeException(e)
		}
	}

	fun setExpandableFromAnyPoint(expandableFromAnyPoint: Boolean) {
		this.expandableFromAnyPoint = expandableFromAnyPoint
	}

	override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
		val action = ev.actionMasked
		if (action == MotionEvent.ACTION_DOWN) {
			startX = ev.x
			startY = ev.y
			handle = !isDrawerOpen(GravityCompat.START) && !isDrawerOpen(GravityCompat.END)
			ignoreTouch = false
		} else if (action == MotionEvent.ACTION_MOVE && handle) {
			val dx = ev.x - startX
			val dy = ev.y - startY
			if (abs(dx) > touchSlop) {
				handle = false
				if (expandableFromAnyPoint && abs(dx) > abs(dy) &&
						getDrawerLockMode(GravityCompat.START) == LOCK_MODE_UNLOCKED) {
					val rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL
					val left = !rtl && dx > 0 && startX >= edgeSize
					val right = rtl && dx < 0 && startX <= width - edgeSize
					if (left || right) {
						startX = ev.x
						startY = ev.y
						ignoreTouch = true
						// ViewDragHelper tracks pointer IDs
						val properties = arrayOf(MotionEvent.PointerProperties())
						ev.getPointerProperties(0, properties[0])
						val coordinates = arrayOf(MotionEvent.PointerCoords())
						coordinates[0].x = if (right) width.toFloat() else 0f
						coordinates[0].y = ev.y
						// Zero event time to disallow initial high velocity
						val fakeDown = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN,
								1, properties, coordinates, 0, 0, 0f, 0f, 0, 0, 0, 0)
						try {
							super.onInterceptTouchEvent(fakeDown)
						} finally {
							fakeDown.recycle()
						}
					}
				}
			}
		}
		val result = super.onInterceptTouchEvent(ev)
		if (result && handle) {
			handle = false
		}
		return result
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(ev: MotionEvent): Boolean {
		val action = ev.actionMasked
		if (ignoreTouch && abs(ev.x - startX) > touchSlop) {
			ignoreTouch = false
		}
		if (action == MotionEvent.ACTION_UP && ignoreTouch) {
			val fakeCancel = MotionEvent.obtain(ev.downTime, ev.eventTime,
					MotionEvent.ACTION_CANCEL, ev.x, ev.y, ev.metaState)
			try {
				return super.onTouchEvent(fakeCancel)
			} finally {
				fakeCancel.recycle()
			}
		}
		return super.onTouchEvent(ev)
	}
}
