package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mishiranu.dashchan.util.ResourceUtils
import java.text.NumberFormat

class ProgressDialog @SuppressLint("RtlHardcoded") constructor(context: Context,
		private val progressFormat: String?) : AlertDialog(context) {
	private val numberFormat: NumberFormat?
	private val progressBar: ProgressBar
	private val message: TextView?
	private val percent: TextView?
	private val progress: TextView?

	init {
		val context = getContext()
		setCanceledOnTouchOutside(false)

		if (progressFormat != null) {
			numberFormat = NumberFormat.getPercentInstance()
			numberFormat.maximumFractionDigits = 0
			// Ensure message TextView is created
			setMessage("")
		} else {
			numberFormat = null
		}

		val layout = LinearLayout(context)
		val density = ResourceUtils.obtainDensity(context)
		val horizontalPadding = ((if (numberFormat != null) 24f else 20f) * density).toInt()
		val verticalPadding = (18f * density).toInt()
		layout.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
		setView(layout)

		if (progressFormat == null) {
			layout.orientation = LinearLayout.HORIZONTAL
			layout.gravity = Gravity.CENTER_VERTICAL
			val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyle)
			ThemeEngine.applyStyle(progressBar)
			layout.addView(progressBar, LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT)
			this.progressBar = progressBar
			val message = TextView(context)
			layout.addView(message, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT)
			if (message.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
				message.setPadding(0, 0, horizontalPadding, 0)
			} else {
				message.setPadding(horizontalPadding, 0, 0, 0)
			}
			this.message = message
			percent = null
			progress = null
		} else {
			layout.orientation = LinearLayout.VERTICAL
			val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
			ThemeEngine.applyStyle(progressBar)
			layout.addView(progressBar, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT)
			this.progressBar = progressBar
			message = null
			val inner = LinearLayout(context)
			layout.addView(inner, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT)
			inner.orientation = LinearLayout.HORIZONTAL
			val percent = TextView(context)
			inner.addView(percent, LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT)
			this.percent = percent
			val progress = TextView(context)
			inner.addView(progress, 0, LinearLayout.LayoutParams.WRAP_CONTENT)
			(progress.layoutParams as LinearLayout.LayoutParams).weight = 1f
			progress.gravity = Gravity.END
			this.progress = progress
		}
		updateProgress()
	}

	override fun setMessage(message: CharSequence?) {
		if (progressFormat == null) {
			this.message!!.text = message
		} else {
			super.setMessage(message)
		}
	}

	fun setIndeterminate(indeterminate: Boolean) {
		progressBar.isIndeterminate = indeterminate
	}

	fun setValue(value: Int) {
		progressBar.progress = value
		updateProgress()
	}

	fun setMax(max: Int) {
		progressBar.max = max
		updateProgress()
	}

	private fun updateProgress() {
		if (progressFormat != null) {
			val value = progressBar.progress
			val max = progressBar.max
			percent!!.text = numberFormat!!.format(value.toDouble() / max.toDouble())
			progress!!.text = String.format(progressFormat, value, max)
		}
	}
}
