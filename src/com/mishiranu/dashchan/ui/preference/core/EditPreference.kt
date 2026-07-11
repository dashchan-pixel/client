package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.FlagUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.SafePasteEditText
import java.util.ArrayList

class EditPreference(context: Context, key: String, defaultValue: String?,
		title: CharSequence?, summaryProvider: SummaryProvider<String>?,
		@JvmField val hint: CharSequence?, @JvmField val inputType: Int) :
		DialogPreference<String>(context, key, defaultValue ?: "", title, summaryProvider) {
	@JvmField
	val customFilters = ArrayList<InputFilter>()

	override fun extract(preferences: SharedPreferences) {
		setValue(preferences.getString(key, defaultValue))
	}

	override fun persist(preferences: SharedPreferences) {
		preferences.edit().put(key, value).close()
	}

	override fun createDialog(savedInstanceState: Bundle?): AlertDialog {
		val alertDialog = super.createDialog(savedInstanceState)
		alertDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
		return alertDialog
	}

	override fun configureDialog(savedInstanceState: Bundle?, builder: AlertDialog.Builder): AlertDialog.Builder {
		val pair = createDialogLayout(builder.context)
		val editText = SafePasteEditText(pair.second.context)
		editText.id = android.R.id.edit
		configureEdit(editText, hint, inputType, value, customFilters)
		editText.requestFocus()
		pair.second.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
		return super.configureDialog(savedInstanceState, builder).setView(pair.first)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					ConcurrentUtils.HANDLER.post { setValue(editText.text.toString()) }
				}
	}

	fun addFilter(filter: InputFilter?): EditPreference {
		if (filter != null) {
			customFilters.add(filter)
		}
		return this
	}

	companion object {
		@JvmStatic
		fun configureEdit(editText: EditText, hint: CharSequence?, inputType: Int, text: CharSequence?) {
			configureEdit(editText, hint, inputType, text, null)
		}

		@JvmStatic
		fun configureEdit(editText: EditText, hint: CharSequence?, inputType: Int, text: CharSequence?,
				filters: ArrayList<InputFilter>?) {
			editText.hint = hint
			val visiblePassword = FlagUtils.get(inputType, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
			val type = FlagUtils.set(inputType, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false)
			editText.inputType = type
			if (visiblePassword) {
				ViewUtils.applyMonospaceTypeface(editText)
			}
			if (FlagUtils.get(type, InputType.TYPE_CLASS_NUMBER)) {
				editText.filters = arrayOf(InputFilter.LengthFilter(Int.MAX_VALUE.toString().length - 1))
			} else if (filters != null && filters.isNotEmpty()) {
				editText.filters = filters.toTypedArray()
			}
			editText.setText(text)
			editText.setSelection(editText.text.length)
		}
	}
}
