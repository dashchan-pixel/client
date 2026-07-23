package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ScrollView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ThemeEngine

/**
 * A single settings row that opens a dialog of Material chips for choosing which optional video
 * playback speeds appear in the player's speed picker. The always-on chip (1×) is shown checked
 * and non-toggleable, in its natural numeric position among the others; the rest are filter chips.
 * The enabled optional speeds are persisted as a set of stable tokens
 * ([Preferences.videoSpeedToken][com.mishiranu.dashchan.content.Preferences]).
 */
class VideoSpeedsPreference(
    context: Context,
    key: String,
    title: CharSequence?,
    /** Every speed chip in display (numeric) order; the [Entry.alwaysOn] one is 1×. */
    private val entries: List<Entry>,
    private val defaultTokens: Set<String>,
    summaryProvider: SummaryProvider<Set<String>>,
) : DialogPreference<Set<String>>(context, key, defaultTokens, title, summaryProvider) {
    class Entry(
        val token: String,
        val label: CharSequence,
        /** The always-on speed (1×): shown checked and non-toggleable, never stored. */
        val alwaysOn: Boolean,
    )

    // Points at the chip group of the dialog currently on screen; used to read back the checked
    // chips on confirm and to preserve them across a configuration change.
    private var chipGroup: ChipGroup? = null

    override fun extract(preferences: SharedPreferences) {
        value = preferences.getStringSet(key!!, defaultTokens) ?: defaultTokens
    }

    override fun persist(preferences: SharedPreferences) {
        preferences.edit().put(key!!, value).close()
    }

    override fun configureDialog(
        savedInstanceState: Bundle?,
        builder: AlertDialog.Builder,
    ): AlertDialog.Builder {
        // Material chips validate against a Material3 theme; the app's own theme is not one, so wrap
        // the chip context in an isolated Material3 theme just for this dialog's chip group. Pick the
        // light/dark variant from the app theme's actual window colour (not the system night setting)
        // so the chips match the dialog this app-themed AlertDialog draws behind them.
        val windowColor = ThemeEngine.getTheme(builder.context).window
        val dark =
            0.299 * Color.red(windowColor) +
                0.587 * Color.green(windowColor) +
                0.114 * Color.blue(windowColor) < 128
        val chipContext =
            ContextThemeWrapper(
                builder.context,
                if (dark) {
                    com.google.android.material.R.style.Theme_Material3_Dark
                } else {
                    com.google.android.material.R.style.Theme_Material3_Light
                },
            )
        val density = ResourceUtils.obtainDensity(builder.context)
        val padding = (20f * density).toInt()
        val scrollView = ScrollView(builder.context)
        scrollView.overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
        val group = ChipGroup(chipContext)
        group.isSingleSelection = false
        group.setPadding(padding, padding, padding, padding)

        val enabled =
            savedInstanceState?.getStringArrayList(EXTRA_CHECKED)?.toSet()
                ?: value ?: defaultTokens
        for (entry in entries) {
            val chip = Chip(chipContext)
            chip.text = entry.label
            chip.isCheckable = true
            if (entry.alwaysOn) {
                // 1×: always checked and non-toggleable, so it can never be removed. No token tag,
                // so it is excluded from the persisted set.
                chip.isChecked = true
                chip.isClickable = false
            } else {
                chip.isChecked = entry.token in enabled
                chip.tag = entry.token
            }
            group.addView(chip)
        }
        scrollView.addView(
            group,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        chipGroup = group
        return super
            .configureDialog(savedInstanceState, builder)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val checked = collectCheckedTokens(group)
                ConcurrentUtils.HANDLER.post { value = checked }
            }
    }

    override fun saveState(
        dialog: AlertDialog,
        outState: Bundle,
    ) {
        super.saveState(dialog, outState)
        val group = chipGroup ?: return
        outState.putStringArrayList(EXTRA_CHECKED, ArrayList(collectCheckedTokens(group)))
    }

    private fun collectCheckedTokens(group: ChipGroup): Set<String> {
        val result = LinkedHashSet<String>()
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val token = chip.tag as? String ?: continue
            if (chip.isChecked) {
                result.add(token)
            }
        }
        return result
    }

    companion object {
        private const val EXTRA_CHECKED = "checkedSpeeds"
    }
}
