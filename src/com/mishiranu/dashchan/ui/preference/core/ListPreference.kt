package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import chan.util.CommonUtils
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences

class ListPreference(context: Context, key: String, defaultValue: String?, title: CharSequence?,
		@JvmField val entries: List<CharSequence>, @JvmField val values: List<String>) :
		DialogPreference<String?>(context, key, defaultValue, title,
				SummaryProvider { p -> entries[getIndex(p as ListPreference)] }) {
	init {
		require(entries.size == values.size)
	}

	override fun extract(preferences: SharedPreferences) {
		var value = preferences.getString(key, defaultValue)
		if (!values.contains(value)) {
			value = defaultValue
		}
		setValue(value)
	}

	override fun persist(preferences: SharedPreferences) {
		preferences.edit().put(key, value).close()
	}

	override fun configureDialog(savedInstanceState: Bundle?,
			builder: AlertDialog.Builder): AlertDialog.Builder {
		return super.configureDialog(savedInstanceState, builder)
				.setSingleChoiceItems(CommonUtils.toArray(entries, CharSequence::class.java),
						getIndex(this)) { d, which ->
					d.dismiss()
					ConcurrentUtils.HANDLER.post { setValue(values[which]) }
				}
	}

	companion object {
		private fun getIndex(preference: ListPreference): Int {
			var index = preference.values.indexOf(preference.value)
			if (index < 0) {
				index = preference.values.indexOf(preference.defaultValue)
			}
			return index
		}
	}
}
