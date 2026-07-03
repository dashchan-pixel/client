package com.mishiranu.dashchan.ui.posting.text

import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ErrorEditTextSetter
import com.mishiranu.dashchan.widget.ThemeEngine

class NameEditWatcher(private val watchTripcodeWarning: Boolean, private val nameView: EditText,
		private val tripcodeWarning: TextView, private val layoutCallback: Runnable) : TextWatcher {
	private var error = false

	private var tripcodeSpan: ForegroundColorSpan? = null
	private var errorSetter: ErrorEditTextSetter? = null

	override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

	override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

	override fun afterTextChanged(s: Editable) {
		val index = s.toString().indexOf('#')
		if (watchTripcodeWarning) {
			val error = index >= 0
			if (this.error != error) {
				val errorSetter = errorSetter ?: ErrorEditTextSetter(nameView).also { errorSetter = it }
				errorSetter.setError(error)

				tripcodeWarning.visibility = if (error) View.VISIBLE else View.GONE
				layoutCallback.run()
				this.error = error
			}
		}
		tripcodeSpan?.let { s.removeSpan(it) }
		if (index >= 0) {
			val tripcodeSpan = tripcodeSpan ?: ForegroundColorSpan(if (watchTripcodeWarning)
				ResourceUtils.getColor(nameView.context, R.attr.colorTextError)
			else ThemeEngine.getTheme(nameView.context).tripcode).also { tripcodeSpan = it }
			s.setSpan(tripcodeSpan, index, s.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}
}
