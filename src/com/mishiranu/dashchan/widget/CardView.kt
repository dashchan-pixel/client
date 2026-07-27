package com.mishiranu.dashchan.widget

import android.content.Context
import com.google.android.material.card.MaterialCardView
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.ResourceUtils

/**
 * The container for thread/post grid cells. Formerly a hand-rolled clone of the support-library
 * CardView (a ~1dp-radius rounded rect drawn by a custom drawable); now a thin wrapper over
 * [MaterialCardView] so the cells get real Material 3 corners and elevation.
 *
 * Kept as its own type (and name) so the existing `itemView as CardView` call sites and the
 * [setBackgroundColor] / [getBackgroundColor] colour API in ViewUnit stay unchanged. MaterialCardView
 * validates against a Material3 theme, so the view is built in a [MaterialContext] overlay; the fill
 * colour is still driven by the active ThemeEngine theme (`theme.card`) through [setBackgroundColor],
 * so cards keep tracking user themes rather than the stock Material palette.
 */
class CardView(
    context: Context,
) : MaterialCardView(MaterialContext.wrap(context)) {
    init {
        val density = ResourceUtils.obtainDensity(this.context)
        radius = Preferences.uiCornerRadius * density
        cardElevation = ELEVATION_DP * density
        // No stroke: this is a filled+elevated card, coloured from the theme, not an outlined one.
        strokeWidth = 0
        // No compat padding: since API 21 the framework draws the elevation shadow outside the view
        // bounds (clipChildren does not clip it), so the card needs no reserved room for it. Enabling it
        // would inset the visible rounded rect inside the item bounds by an asymmetric, corner-radius
        // dependent amount (vertical > horizontal), which stacks on top of the list's own item spacing
        // and makes the gap between two cards visibly larger than the gap to the screen edges.
        setUseCompatPadding(false)
        // The content child carries its own selectable/ripple background; keep the card itself inert so
        // there is no second ripple or checkable overlay on top of it.
        isClickable = false
        isCheckable = false
    }

    override fun setBackgroundColor(color: Int) {
        setCardBackgroundColor(color)
    }

    fun getBackgroundColor(): Int = cardBackgroundColor.defaultColor

    private companion object {
        const val ELEVATION_DP = 1f
    }
}
