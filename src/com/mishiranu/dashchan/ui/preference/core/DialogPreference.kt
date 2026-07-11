package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ThemeEngine

abstract class DialogPreference<T>(context: Context, key: String, defaultValue: T,
		title: CharSequence?, summaryProvider: SummaryProvider<T>?) :
		Preference<T>(context, key, defaultValue, title, summaryProvider) {
	private var neutralButtonText: CharSequence? = null
	private var neutralButtonListener: Runnable? = null
	private var description: CharSequence? = null

	internal open fun createDialog(savedInstanceState: Bundle?): AlertDialog {
		val dialog = configureDialog(savedInstanceState, AlertDialog.Builder(context)).create()
		if (neutralButtonText != null) {
			dialog.setButton(AlertDialog.BUTTON_NEUTRAL, neutralButtonText,
					null as DialogInterface.OnClickListener?)
			dialog.setOnShowListener {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
						.setOnClickListener { neutralButtonListener!!.run() }
			}
		}
		return dialog
	}

	protected fun createDialogLayout(context: Context): Pair<View, LinearLayout> {
		val density = ResourceUtils.obtainDensity(context)
		val padding = (20f * density).toInt()
		val scrollView = ScrollView(context)
		scrollView.overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
		ThemeEngine.applyStyle(scrollView)
		val linearLayout = LinearLayout(scrollView.context)
		linearLayout.orientation = LinearLayout.VERTICAL
		linearLayout.setPadding(padding, padding, padding, padding)
		scrollView.addView(linearLayout, ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT)
		val description = description
		if (!description.isNullOrEmpty()) {
			val testEditText = EditText(linearLayout.context)
			val descriptionView = TextView(linearLayout.context, null,
					android.R.attr.textAppearanceListItemSmall)
			descriptionView.setPadding(testEditText.paddingLeft, 0,
					testEditText.paddingRight, (8f * density).toInt())
			descriptionView.text = description
			linearLayout.addView(descriptionView, 0, LinearLayout
					.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
							LinearLayout.LayoutParams.WRAP_CONTENT))
		}
		linearLayout.id = android.R.id.widget_frame
		return Pair(scrollView, linearLayout)
	}

	protected fun getDialogLayout(dialog: AlertDialog): LinearLayout {
		return dialog.findViewById(android.R.id.widget_frame)
	}

	internal open fun configureDialog(savedInstanceState: Bundle?,
			builder: AlertDialog.Builder): AlertDialog.Builder {
		return builder.setTitle(title)
				.setNegativeButton(android.R.string.cancel, null)
	}

	internal open fun startDialog(dialog: AlertDialog) {}
	internal open fun stopDialog(dialog: AlertDialog) {}
	internal open fun saveState(dialog: AlertDialog, outState: Bundle) {}

	fun setNeutralButton(text: CharSequence?, listener: Runnable?) {
		neutralButtonText = text
		neutralButtonListener = listener
	}

	fun setDescription(description: CharSequence?) {
		this.description = description
	}
}
