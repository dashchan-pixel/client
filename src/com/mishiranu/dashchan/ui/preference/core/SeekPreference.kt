package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Pair
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ViewFactory

class SeekPreference(
    context: Context,
    key: String,
    defaultValue: Int,
    title: CharSequence?,
    valueFormat: String?,
    specialValue: Pair<Int, String>?,
    private val minValue: Int,
    private val maxValue: Int,
    private val step: Int,
) : DialogPreference<Int>(
        context,
        key,
        defaultValue,
        title,
        SummaryProvider { p ->
            if (specialValue != null && specialValue.first == p.value) {
                specialValue.second
            } else if (valueFormat != null) {
                String.format(valueFormat, p.value)
            } else {
                null
            }
        },
    ) {
    private val valueFormat: String?
    private val specialValue: Int?
    private val specialValueText: String?

    init {
        require(
            !(
                specialValue != null &&
                    specialValue.first >= minValue &&
                    specialValue.first <= maxValue
            ),
        )
        this.valueFormat = valueFormat
        this.specialValue = specialValue?.first
        this.specialValueText = specialValue?.second
    }

    override fun extract(preferences: SharedPreferences) {
        value = (preferences.getInt(key!!, defaultValue!!))
    }

    override fun persist(preferences: SharedPreferences) {
        preferences.edit().put(key!!, value!!).close()
    }

    override fun configureDialog(
        savedInstanceState: Bundle?,
        builder: AlertDialog.Builder,
    ): AlertDialog.Builder {
        val holder =
            ViewFactory.createSeekLayout(
                builder.context,
                specialValue != null,
                minValue,
                maxValue,
                step,
                valueFormat,
            )
        // The switch off stands for the special value, so the row says what it stands for — the same
        // word the preference summary shows for it — instead of a number the setting is not at.
        holder.disabledText = specialValueText
        if (savedInstanceState != null) {
            holder.isEnabled = savedInstanceState.getBoolean(STATE_ENABLED)
            holder.value = savedInstanceState.getInt(STATE_VALUE)
        } else {
            val value = value
            holder.isEnabled = specialValue == null || specialValue != value
            holder.value = if (specialValue != null && specialValue == value) defaultValue!! else value!!
        }
        return super
            .configureDialog(savedInstanceState, builder)
            .setView(holder.layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ConcurrentUtils.HANDLER.post {
                    value = (
                        if (specialValue != null && !holder.isEnabled) {
                            specialValue
                        } else {
                            holder.value
                        }
                    )
                }
            }
    }

    override fun saveState(
        dialog: AlertDialog,
        outState: Bundle,
    ) {
        super.saveState(dialog, outState)

        val holder =
            dialog
                .findViewById<android.view.View>(R.id.seek_layout)
                .tag as ViewFactory.SeekLayoutHolder
        outState.putBoolean(STATE_ENABLED, holder.isEnabled)
        outState.putInt(STATE_VALUE, holder.value)
    }

    companion object {
        private const val STATE_ENABLED = "enabled"
        private const val STATE_VALUE = "value"
    }
}
