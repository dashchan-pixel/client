package com.mishiranu.dashchan.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Checkable
import android.widget.FrameLayout
import androidx.appcompat.widget.ListPopupWindow
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.max

/**
 * A single-choice dropdown anchored to a view: the current row carries a radio mark (unless
 * [showRadio] turns it off, for a chooser whose anchor already shows the current value) and the
 * list hugs its widest label instead of the anchor's width.
 *
 * A [ListPopupWindow] rather than a framework `PopupMenu`, for the same reason as [CommandsPopup]:
 * a PopupMenu draws the square background of its own popup style, which no amount of theming rounds
 * off. Here the background is ours ([ThemeEngine.roundedPopupBackground]). A popup window also
 * attaches to its anchor's window, so it works from a token-less base context — the gallery's, for
 * one — where an [android.app.AlertDialog] would throw a BadTokenException.
 *
 * [menuContext] themes the rows, and is not necessarily the anchor's own context: overlay controls
 * are themed for drawing over media (white text), which the app-themed popup background behind the
 * rows need not be dark enough for.
 */
object DropdownPopup {
    /**
     * Minimum width for a radio (menu-style) dropdown, so a list of short labels still reads as a
     * list rather than a tooltip. A no-radio chooser hugs its content down to [TAP_WIDTH_DP], which
     * keeps a speed picker of "1x"-width labels from floating in a needlessly wide popup.
     */
    private const val MIN_WIDTH_DP = 112f
    private const val TAP_WIDTH_DP = 48f

    fun show(
        anchor: View,
        menuContext: Context,
        labels: List<CharSequence>,
        checkedIndex: Int,
        showRadio: Boolean = true,
        onSelect: (Int) -> Unit,
    ) {
        val density = ResourceUtils.obtainDensity(menuContext)
        val paddingHorizontal = (16f * density).toInt()
        val paddingVertical = (12f * density).toInt()
        val adapter =
            object : ArrayAdapter<CharSequence>(
                menuContext,
                if (showRadio) {
                    android.R.layout.simple_list_item_single_choice
                } else {
                    android.R.layout.simple_list_item_1
                },
                android.R.id.text1,
                labels,
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    view.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                    // With the radio layout the row is a CheckedTextView, so the mark rides along
                    // without the list needing a choice mode of its own; the plain layout is not
                    // Checkable and this is a no-op.
                    (view as? Checkable)?.isChecked = position == checkedIndex
                    return view
                }
            }
        val popup = ListPopupWindow(menuContext)
        popup.anchorView = anchor
        popup.isModal = true
        popup.setAdapter(adapter)
        // Tap feedback in a ListView comes from the list selector, not item backgrounds.
        popup.setListSelector(
            menuContext.getDrawable(
                ResourceUtils.getResourceId(menuContext, android.R.attr.selectableItemBackground, 0),
            ),
        )
        popup.width = measureWidth(adapter, menuContext, density, showRadio)
        popup.setBackgroundDrawable(ThemeEngine.roundedPopupBackground(menuContext))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelect(position)
        }
        popup.show()
    }

    private fun measureWidth(
        adapter: ArrayAdapter<CharSequence>,
        menuContext: Context,
        density: Float,
        showRadio: Boolean,
    ): Int {
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val fakeParent = FrameLayout(menuContext)
        var contentWidth = 0
        var itemView: View? = null
        for (i in 0 until adapter.count) {
            itemView = adapter.getView(i, itemView, fakeParent)
            itemView.measure(measureSpec, measureSpec)
            contentWidth = max(contentWidth, itemView.measuredWidth)
        }
        val minWidthDp = if (showRadio) MIN_WIDTH_DP else TAP_WIDTH_DP
        return contentWidth.coerceAtLeast((minWidthDp * density).toInt())
    }
}
