package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Pair
import android.util.SparseArray
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.content.Preferences.unpackOrCastMultipleValues
import com.mishiranu.dashchan.ui.preference.core.EditPreference.Companion.configureEdit
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.SafePasteEditText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max

class MultipleEditPreference<T>(
    context: Context,
    key: String,
    title: CharSequence?,
    summaryProvider: SummaryProvider<T>?,
    val hints: List<CharSequence?>?,
    val inputTypes: List<Int>?,
    private val valueCodec: ValueCodec<T>
) : DialogPreference<T>(context, key, null, title, summaryProvider) {
    private val values = SparseArray<Pair<List<CharSequence?>, List<String?>>?>()
    private var lastFocusIndex = 0

    override fun extract(preferences: SharedPreferences) {
        value = valueCodec.fromString(preferences.getString(key!!, null))
    }

    override fun persist(preferences: SharedPreferences) {
        preferences.edit().put(key!!, valueCodec.toString(value)).close()
    }

    fun setValues(index: Int, entries: List<CharSequence?>?, values: List<String?>?) {
        if (entries == null || values == null) {
            this.values.remove(index)
        } else {
            require(entries.size == values.size)
            this.values.put(index, Pair(entries, values))
        }
    }

    override fun createDialog(savedInstanceState: Bundle?): AlertDialog {
        val alertDialog = super.createDialog(savedInstanceState)
        alertDialog.getWindow()!!
            .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return alertDialog
    }

    override fun configureDialog(
        savedInstanceState: Bundle?,
        builder: AlertDialog.Builder
    ): AlertDialog.Builder {
        val pair = createDialogLayout(builder.getContext())
        val viewHolders = ArrayList<ViewHolder>()
        for (i in 0..<valueCodec.count) {
            val values = this.values.get(i)
            val value = valueCodec.getValueAt(value, i)
            val viewHolder: ViewHolder?
            if (values != null) {
                viewHolder =
                    DropdownViewHolder(builder.getContext(), values.first, values.second, value)
            } else {
                val hint = if (hints != null && hints.size > i) hints.get(i) else null
                val inputType = (if (inputTypes != null && inputTypes.size > i)
                    inputTypes.get(i)
                else
                    android.text.InputType.TYPE_CLASS_TEXT)!!
                viewHolder = EditTextViewHolder(builder.getContext(), hint, inputType, value)
            }
            viewHolders.add(viewHolder)
            pair.second!!.addView(viewHolder.view)
            viewHolder.view.setTag(viewHolder)
            if (savedInstanceState != null) {
                viewHolder.restoreState(savedInstanceState, i)
            }
        }
        lastFocusIndex = -1
        var focusIndex = 0
        if (savedInstanceState != null) {
            focusIndex = savedInstanceState.getInt(EXTRA_FOCUS, -1)
        }
        if (focusIndex >= 0) {
            restoreFocusIndex(pair.second!!, focusIndex)
        }
        return super.configureDialog(savedInstanceState, builder).setView(pair.first)
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    ConcurrentUtils.HANDLER.post(
                        Runnable {
                            val values: MutableList<String?> = ArrayList<String?>(viewHolders.size)
                            for (viewHolder in viewHolders) {
                                values.add(nullIfEmpty(viewHolder.value))
                            }
                            this.value = valueCodec.createValue(values)
                        })
                })
    }

    private fun restoreFocusIndex(layout: LinearLayout, focusIndex: Int) {
        var index = 0
        val childCount = layout.getChildCount()
        for (i in 0..<childCount) {
            val viewHolder = layout.getChildAt(i).getTag() as ViewHolder?
            if (viewHolder != null) {
                if (index++ == focusIndex) {
                    viewHolder.view.requestFocus()
                    return
                }
            }
        }
    }

    private fun getFocusIndex(layout: LinearLayout): Int {
        var index = 0
        val childCount = layout.getChildCount()
        for (i in 0..<childCount) {
            val viewHolder = layout.getChildAt(i).getTag() as ViewHolder?
            if (viewHolder != null) {
                if (viewHolder.view.hasFocus()) {
                    return index
                }
                index++
            }
        }
        return -1
    }

    override fun startDialog(dialog: AlertDialog) {
        super.startDialog(dialog)

        if (lastFocusIndex >= 0) {
            restoreFocusIndex(getDialogLayout(dialog), lastFocusIndex)
            lastFocusIndex = -1
        }
    }

    override fun stopDialog(dialog: AlertDialog) {
        super.stopDialog(dialog)
        lastFocusIndex = getFocusIndex(getDialogLayout(dialog))
    }

    override fun saveState(dialog: AlertDialog, outState: Bundle) {
        super.saveState(dialog, outState)

        var index = 0
        val layout = getDialogLayout(dialog)
        val childCount = layout.getChildCount()
        for (i in 0..<childCount) {
            val viewHolder = layout.getChildAt(i).getTag() as ViewHolder?
            if (viewHolder != null) {
                viewHolder.saveState(outState, index++)
            }
        }
        var focusIndex = lastFocusIndex
        if (focusIndex == -1) {
            focusIndex = getFocusIndex(layout)
        }
        outState.putInt(EXTRA_FOCUS, focusIndex)
    }

    private interface ViewHolder {
        val value: String?
        val view: View
        fun restoreState(bundle: Bundle, index: Int)
        fun saveState(bundle: Bundle, index: Int)
    }

    private class EditTextViewHolder(
        context: Context,
        hint: CharSequence?,
        inputType: Int,
        value: String?
    ) : ViewHolder {
        private val editText: SafePasteEditText

        init {
            editText = SafePasteEditText(context)
            configureEdit(editText, hint, inputType, value)
        }

        override val value: String
            get() = editText.getText().toString()

        override val view: View
            get() = editText

        override fun restoreState(bundle: Bundle, index: Int) {
            editText.setText(bundle.getCharSequence("text" + index))
            editText.setSelection(
                bundle.getInt("selectionStart" + index),
                bundle.getInt("selectionEnd" + index)
            )
        }

        override fun saveState(bundle: Bundle, index: Int) {
            bundle.putCharSequence("text" + index, editText.getText())
            bundle.putInt("selectionStart" + index, editText.getSelectionStart())
            bundle.putInt("selectionEnd" + index, editText.getSelectionEnd())
        }
    }

    private class DropdownViewHolder(
        context: Context,
        entries: List<CharSequence?>,
        values: List<String?>,
        value: String?
    ) : ViewHolder {
        private val dropdownView: DropdownView
        private val values: List<String?>

        init {
            dropdownView = DropdownView(context)
            dropdownView.setItems(entries.map { it ?: "" })
            this.values = values
            dropdownView.setSelection(max(0, values.indexOf(value)))
        }

        override val value: String?
            get() = values.get(dropdownView.getSelectedItemPosition())

        override val view: View
            get() = dropdownView

        override fun restoreState(bundle: Bundle, index: Int) {
            dropdownView.setSelection(bundle.getInt("value" + index))
        }

        override fun saveState(bundle: Bundle, index: Int) {
            bundle.putInt("value" + index, dropdownView.getSelectedItemPosition())
        }
    }

    interface ValueCodec<T> {
        val count: Int
        fun fromString(value: String?): T?
        fun toString(value: T?): String?
        fun getValueAt(value: T?, index: Int): String?
        fun createValue(values: MutableList<String?>?): T?
    }

    class ListValueCodec(override val count: Int) : ValueCodec<List<String>> {
        override fun fromString(value: String?): List<String>? {
            @Suppress("UNCHECKED_CAST")
            return unpackOrCastMultipleValues(value, count) as List<String>
        }

        override fun toString(value: List<String>?): String {
            return JSONArray(value).toString()
        }

        override fun getValueAt(value: List<String>?, index: Int): String? {
            return value!!.get(index)
        }

        override fun createValue(values: MutableList<String?>?): List<String>? {
            @Suppress("UNCHECKED_CAST")
            return values as List<String>?
        }
    }

    class MapValueCodec(private val keys: List<String>) :
        ValueCodec<Map<String, String>> {
        override val count: Int
            get() = keys.size

        override fun fromString(value: String?): Map<String, String>? {
            @Suppress("UNCHECKED_CAST")
            return unpackOrCastMultipleValues(value, keys) as Map<String, String>
        }

        override fun toString(value: Map<String, String>?): String {
            val jsonObject = JSONObject()
            for (entry in value!!.entries) {
                try {
                    jsonObject.put(entry.key, entry.value)
                } catch (e: JSONException) {
                    throw RuntimeException(e)
                }
            }
            return jsonObject.toString()
        }

        override fun getValueAt(value: Map<String, String>?, index: Int): String? {
            return value!!.get(keys.get(index))
        }

        override fun createValue(values: MutableList<String?>?): Map<String, String> {
            val map = HashMap<String, String>()
            for (i in keys.indices) {
                val value = values!!.get(i)
                if (!isEmpty(value)) {
                    map.put(keys.get(i), value!!)
                }
            }
            return map
        }
    }

    companion object {
        private const val EXTRA_FOCUS = "focus"

        fun <T> formatValues(valueCodec: ValueCodec<T>, format: String?, value: T?): String? {
            val builder = StringBuilder(format!!)
            var index = 0
            var i = 0
            while (i < valueCodec.count && (builder.indexOf("%s").also { index = it }) >= 0) {
                val stringValue = valueCodec.getValueAt(value, i)
                if (stringValue == null) {
                    return null
                }
                builder.replace(index, index + 2, stringValue)
                i++
            }
            return builder.toString()
        }
    }
}
