package com.mishiranu.dashchan.ui.preference.core

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ThemeEngine

class CheckPreference(context: Context, key: String, defaultValue: Boolean,
		title: CharSequence?, summary: CharSequence?) :
		Preference<Boolean>(context, key, defaultValue, title, SummaryProvider { summary }) {
	override fun extract(preferences: SharedPreferences) {
		setValue(preferences.getBoolean(key, defaultValue))
	}

	override fun persist(preferences: SharedPreferences) {
		preferences.edit().put(key, getValue()).close()
	}

	override fun getViewType(): ViewType = ViewType.CHECK

	class CheckViewHolder(viewHolder: ViewHolder, @JvmField val check: CheckBox) : ViewHolder(viewHolder)

	override fun createViewHolder(parent: ViewGroup): CheckViewHolder {
		val viewHolder = super.createViewHolder(parent)
		viewHolder.widgetFrame.visibility = View.VISIBLE
		val check = CheckBox(viewHolder.widgetFrame.context)
		ThemeEngine.applyStyle(check)
		check.isClickable = false
		check.isFocusable = false
		viewHolder.widgetFrame.addView(check, ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT)
		return CheckViewHolder(viewHolder, check)
	}

	override fun bindViewHolder(viewHolder: ViewHolder) {
		super.bindViewHolder(viewHolder)

		if (viewHolder is CheckViewHolder) {
			viewHolder.check.isChecked = getValue()
			viewHolder.check.isEnabled = isEnabled()
		}
	}
}
