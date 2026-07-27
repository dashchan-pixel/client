package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.DropdownPopup

/**
 * The row the Filter action drops over the top of the gallery grid: one dropdown narrows the files
 * to a media type, the other picks their order.
 *
 * Both list only what this gallery has. A media type no file matches gets no row — so a pick can
 * never empty the grid — and neither does a criterion the chan reports nothing for. With a single
 * media type there is nothing to narrow, and the first dropdown goes away altogether.
 *
 * Styled like the gallery's other overlay controls (white on a translucent bar) rather than with
 * the user's theme, because it sits over the grid's dark background whatever the theme is; the
 * dropdowns themselves are app-themed, see [DropdownPopup].
 */
class GalleryFilterBar(
    context: Context,
    private val instance: GalleryInstance,
    private val callback: Callback,
) : LinearLayout(context) {
    fun interface Callback {
        /** A media type or an order has been picked, and the gallery list already reflects it. */
        fun onGalleryFilterChanged()
    }

    private val typeButton = dropdownButton { showTypePopup() }
    private val sortButton = dropdownButton { showSortPopup() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BACKGROUND_COLOR)
        val density = ResourceUtils.obtainDensity(context)
        val padding = (8f * density).toInt()
        setPadding(padding, 0, padding, 0)
        // Even halves, so a long label ellipsizes instead of pushing the other dropdown off the bar
        addView(typeButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(sortButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        update()
    }

    /** Re-label both dropdowns from the gallery's current state. */
    fun update() {
        typeButton.visibility = if (instance.mediaTypeCounts.size > 1) VISIBLE else GONE
        typeButton.text = typeLabel(instance.mediaType)
        sortButton.text = context.getString(instance.sort.titleResId)
    }

    private fun typeLabel(mediaType: GalleryInstance.MediaType?): String {
        val title =
            mediaType?.title(context) ?: context.getString(R.string.all_files)
        val count = mediaType?.let { instance.mediaTypeCounts[it] } ?: instance.totalCount
        return "$title ($count)"
    }

    private fun showTypePopup() {
        val mediaTypes = listOf<GalleryInstance.MediaType?>(null) + instance.mediaTypeCounts.keys
        DropdownPopup.show(
            typeButton,
            GalleryInstance.menuContext(context),
            mediaTypes.map { typeLabel(it) },
            mediaTypes.indexOf(instance.mediaType),
        ) { position ->
            if (instance.setMediaType(mediaTypes[position])) {
                update()
                callback.onGalleryFilterChanged()
            }
        }
    }

    private fun showSortPopup() {
        val sorts = instance.sortOptions
        DropdownPopup.show(
            sortButton,
            GalleryInstance.menuContext(context),
            sorts.map { context.getString(it.titleResId) },
            sorts.indexOf(instance.sort),
        ) { position ->
            // setSort remembers the choice even when this gallery is already in that order
            val changed = instance.setSort(sorts[position])
            update()
            if (changed) {
                callback.onGalleryFilterChanged()
            }
        }
    }

    private fun dropdownButton(onClick: () -> Unit): TextView {
        val button = TextView(context, null, android.R.attr.borderlessButtonStyle)
        button.gravity = Gravity.CENTER_VERTICAL
        button.setTextColor(Color.WHITE)
        button.typeface = ResourceUtils.TYPEFACE_MEDIUM
        button.setSingleLine(true)
        button.ellipsize = TextUtils.TruncateAt.END
        ViewUtils.setTextSizeScaled(button, 14)
        val caret = ResourceUtils.getDrawable(context, R.attr.iconButtonDropDown, 0)?.mutate()
        caret?.setTint(Color.WHITE)
        button.setCompoundDrawablesWithIntrinsicBounds(null, null, caret, null)
        button.setOnClickListener { onClick() }
        return button
    }

    companion object {
        /** Height of the bar, matching the action bar row it hangs under. */
        const val HEIGHT_DP = 48f

        /** The action bar's translucent dark, so the bar reads as a second row of it. */
        private const val BACKGROUND_COLOR = 0xa0202020.toInt()
    }
}
