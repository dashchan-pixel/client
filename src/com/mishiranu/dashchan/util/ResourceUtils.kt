package com.mishiranu.dashchan.util

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.fragment.app.Fragment
import com.mishiranu.dashchan.R

object ResourceUtils {
	@JvmField val TYPEFACE_MEDIUM: Typeface
	@JvmField val TYPEFACE_LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

	init {
		val size = 20
		val regularTypeface = Typeface.DEFAULT
		val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
		val regularBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val mediumBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		paint.textSize = size.toFloat()
		paint.typeface = regularTypeface
		Canvas(regularBitmap).drawText("A", 0f, size.toFloat(), paint)
		paint.typeface = mediumTypeface
		Canvas(mediumBitmap).drawText("A", 0f, size.toFloat(), paint)
		val regularPixels = IntArray(size * size)
		val mediumPixels = IntArray(size * size)
		regularBitmap.getPixels(regularPixels, 0, size, 0, 0, size, size)
		mediumBitmap.getPixels(mediumPixels, 0, size, 0, 0, size, size)
		TYPEFACE_MEDIUM = if (regularPixels.contentEquals(mediumPixels)) Typeface.DEFAULT_BOLD
		else mediumTypeface
	}

	@JvmStatic
	fun obtainDensity(view: View): Float = obtainDensity(view.resources)

	@JvmStatic
	fun obtainDensity(fragment: Fragment): Float = obtainDensity(fragment.resources)

	@JvmStatic
	fun obtainDensity(context: Context): Float = obtainDensity(context.resources)

	@JvmStatic
	fun obtainDensity(resources: Resources): Float = resources.displayMetrics.density

	@JvmStatic
	fun isTablet(configuration: Configuration): Boolean = configuration.smallestScreenWidthDp >= 600

	@JvmStatic
	fun isTabletLarge(configuration: Configuration): Boolean = configuration.smallestScreenWidthDp >= 720

	@JvmStatic
	fun isTabletOrLandscape(configuration: Configuration): Boolean {
		return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || isTablet(configuration)
	}

	@JvmStatic
	fun getColor(context: Context, attr: Int): Int {
		val typedArray = context.obtainStyledAttributes(intArrayOf(attr))
		try {
			return typedArray.getColor(0, 0)
		} finally {
			typedArray.recycle()
		}
	}

	@JvmStatic
	fun getColorStateList(context: Context, attr: Int): ColorStateList? {
		val typedArray = context.obtainStyledAttributes(intArrayOf(attr))
		try {
			return typedArray.getColorStateList(0)
		} finally {
			typedArray.recycle()
		}
	}

	@JvmStatic
	fun getResourceId(context: Context, attr: Int, notFound: Int): Int {
		try {
			val typedArray = context.obtainStyledAttributes(intArrayOf(attr))
			val resId = typedArray.getResourceId(0, 0)
			typedArray.recycle()
			if (resId != 0) {
				return resId
			}
		} catch (e: Exception) {
			// Ignore exception
		}
		return notFound
	}

	@JvmStatic
	fun getResourceId(context: Context, defStyleAttr: Int, attr: Int, notFound: Int): Int {
		try {
			val typedArray = context.obtainStyledAttributes(null, intArrayOf(attr), defStyleAttr, 0)
			val resId = typedArray.getResourceId(0, 0)
			typedArray.recycle()
			if (resId != 0) {
				return resId
			}
		} catch (e: Exception) {
			// Ignore exception
		}
		return notFound
	}

	@JvmStatic
	fun getDrawable(context: Context, resId: Int): Drawable? = context.getDrawable(resId)

	@JvmStatic
	fun getDrawable(context: Context, attr: Int, notFound: Int): Drawable? {
		val resId = getResourceId(context, attr, notFound)
		return if (resId != 0) getDrawable(context, resId) else null
	}

	@JvmStatic
	fun getActionBarIcon(context: Context, attr: Int): Drawable {
		val drawable = getDrawable(context, attr, 0)!!
		drawable.mutate()
		drawable.setTint(getColor(context, android.R.attr.textColorPrimary))
		return drawable
	}

	@JvmStatic
	fun getColonString(resources: Resources, resId: Int, formatArg: Any?): String {
		return resources.getString(R.string.__colon_format, resources.getString(resId), formatArg)
	}

	@JvmField
	val PRESSED_STATE = intArrayOf(android.R.attr.state_window_focused,
			android.R.attr.state_enabled, android.R.attr.state_pressed)

	@JvmStatic
	fun getSystemSelectorColor(context: Context): Int {
		return getColor(context, android.R.attr.colorControlHighlight)
	}

	@JvmStatic
	fun getDialogBackground(context: Context): Int {
		val themedContext = ContextThemeWrapper(context,
				getResourceId(context, android.R.attr.dialogTheme, 0))
		val typedArray = themedContext.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
		var drawable = typedArray.getDrawable(0)
		typedArray.recycle()
		if (drawable is InsetDrawable) {
			drawable = drawable.drawable
		}
		return if (drawable != null) {
			GraphicsUtils.getDrawableColor(themedContext, drawable, Gravity.CENTER)
		} else {
			0
		}
	}

	enum class DialogLayout { SIMPLE, SINGLE_CHOICE, MULTI_CHOICE }

	@JvmStatic
	fun obtainAlertDialogLayoutResId(context: Context, dialogLayout: DialogLayout): Int {
		var resId: Int
		val layoutName: String
		when (dialogLayout) {
			DialogLayout.SIMPLE -> {
				resId = android.R.layout.select_dialog_item
				layoutName = "listItemLayout"
			}
			DialogLayout.SINGLE_CHOICE -> {
				resId = android.R.layout.select_dialog_singlechoice
				layoutName = "singleChoiceItemLayout"
			}
			DialogLayout.MULTI_CHOICE -> {
				resId = android.R.layout.select_dialog_multichoice
				layoutName = "multiChoiceItemLayout"
			}
		}
		@Suppress("DiscouragedApi")
		val layoutAttr = context.resources.getIdentifier(layoutName, "attr", "android")
		if (layoutAttr != 0) {
			val typedArray = context.obtainStyledAttributes(null, intArrayOf(layoutAttr),
					android.R.attr.alertDialogStyle, 0)
			resId = typedArray.getResourceId(0, resId)
			typedArray.recycle()
		}
		return resId
	}
}
