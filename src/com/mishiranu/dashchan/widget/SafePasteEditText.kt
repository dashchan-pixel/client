package com.mishiranu.dashchan.widget

import android.content.Context
import android.text.InputFilter
import android.text.Spanned
import android.util.AttributeSet
import android.widget.EditText

// Removes spans on paste event.
open class SafePasteEditText : EditText {
	constructor(context: Context) : super(context)

	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
			super(context, attrs, defStyleAttr)

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
			super(context, attrs, defStyleAttr, defStyleRes)

	init {
		ThemeEngine.applyStyle(this)
	}

	override fun onTextContextMenuItem(id: Int): Boolean {
		if (id == android.R.id.paste) {
			val editable = editableText
			val filters: Array<InputFilter>? = editable.filters
			val tempFilters = arrayOfNulls<InputFilter>(if (filters != null) filters.size + 1 else 1)
			if (filters != null) {
				System.arraycopy(filters, 0, tempFilters, 1, filters.size)
			}
			tempFilters[0] = SPAN_FILTER
			editable.filters = tempFilters
			return try {
				super.onTextContextMenuItem(id)
			} finally {
				editable.filters = filters
			}
		} else {
			return super.onTextContextMenuItem(id)
		}
	}

	companion object {
		private val SPAN_FILTER = InputFilter { source, _, _, _, _, _ ->
			if (source is Spanned) source.toString() else source
		}
	}
}
