package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils

/**
 * A mock thread — toolbar, three post cards and an accent chip — painted with the colours of a
 * [ThemeEngine.Theme]. The theme editor keeps one at the top of its list so an edit is visible
 * immediately and in context, not just as a swatch.
 *
 * Every label is the name of the colour it is drawn in, so the preview doubles as a legend and
 * needs no sample post text of its own.
 */
class ThemePreviewView(
    context: Context,
) : LinearLayout(context) {
    private val density = ResourceUtils.obtainDensity(context)
    private val padding = (12f * density).toInt()

    private val toolbar = TextView(context)
    private val body = LinearLayout(context)

    private val tripcodeView = label(R.string.theme_color_tripcode)
    private val capcodeView = label(R.string.theme_color_capcode)
    private val metaView = label(R.string.theme_color_meta)
    private val quoteView = label(R.string.theme_color_quote)
    private val linkView = label(R.string.theme_color_link)
    private val spoilerView = label(R.string.theme_color_spoiler)
    private val postView = label(R.string.theme_color_post)
    private val highlightView = label(R.string.theme_color_highlight)
    private val neuroslopQuoteView = label(R.string.theme_color_neuroslop_quote)
    private val neuroslopView = label(R.string.theme_color_neuroslop)
    private val accentView = label(R.string.theme_color_accent)

    private val postCard = card(row(tripcodeView, capcodeView, metaView), row(quoteView, linkView, spoilerView), postView)
    private val highlightCard = card(highlightView)
    private val neuroslopCard = card(row(neuroslopQuoteView, neuroslopView))

    init {
        orientation = VERTICAL
        toolbar.setPadding(padding, padding, padding, padding)
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.isSingleLine = true
        toolbar.typeface = ResourceUtils.TYPEFACE_MEDIUM
        ViewUtils.setTextSizeScaled(toolbar, 16)
        addView(toolbar, LayoutParams.MATCH_PARENT, (56f * density).toInt())

        body.orientation = VERTICAL
        body.setPadding(padding, padding, padding, padding)
        addView(body, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addToBody(postCard)
        addToBody(highlightCard)
        addToBody(neuroslopCard)

        spoilerView.setPadding((6f * density).toInt(), 0, (6f * density).toInt(), 0)
        accentView.setPadding(padding, (6f * density).toInt(), padding, (6f * density).toInt())
        accentView.background = roundedShape()
        body.addView(accentView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private fun label(textResId: Int): TextView {
        val textView = TextView(context)
        textView.setText(textResId)
        textView.isSingleLine = true
        ViewUtils.setTextSizeScaled(textView, 14)
        return textView
    }

    /** One line of the mock post: labels laid out left to right, each one a different colour. */
    private fun row(vararg views: TextView): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = HORIZONTAL
        for (view in views) {
            layout.addView(view, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            (view.layoutParams as MarginLayoutParams).marginEnd = (8f * density).toInt()
        }
        return layout
    }

    private fun card(vararg children: View): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = VERTICAL
        layout.setPadding(padding, padding, padding, padding)
        layout.background = roundedShape()
        for (child in children) {
            layout.addView(child, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        return layout
    }

    private fun addToBody(card: LinearLayout) {
        body.addView(card, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        (card.layoutParams as MarginLayoutParams).bottomMargin = (8f * density).toInt()
    }

    /** Cards follow the same corner radius as the real post cards. */
    private fun roundedShape(): MaterialShapeDrawable =
        MaterialShapeDrawable(
            ShapeAppearanceModel
                .builder()
                .setAllCornerSizes(Preferences.uiCornerRadius * density)
                .build(),
        )

    private fun fill(
        view: View,
        color: Int,
    ) {
        (view.background as MaterialShapeDrawable).fillColor = ColorStateList.valueOf(color)
    }

    fun setTheme(theme: ThemeEngine.Theme) {
        toolbar.text = theme.name
        toolbar.setBackgroundColor(theme.primary or Color.BLACK)
        toolbar.setTextColor(contrast(theme.primary))
        body.setBackgroundColor(theme.window)
        fill(postCard, theme.card)
        fill(neuroslopCard, theme.card)
        // The real highlight is drawn as a 10% wash of the highlight colour over the card, so the
        // preview mixes the same wash rather than filling the card with the raw colour.
        fill(highlightCard, GraphicsUtils.mixColors(theme.card, GraphicsUtils.applyAlpha(theme.highlight, 0.1f)))
        fill(accentView, theme.accent)
        accentView.setTextColor(contrast(theme.accent))
        tripcodeView.setTextColor(theme.tripcode)
        capcodeView.setTextColor(theme.capcode)
        metaView.setTextColor(theme.meta)
        quoteView.setTextColor(theme.quote)
        linkView.setTextColor(theme.link)
        spoilerView.setTextColor(theme.post)
        spoilerView.setBackgroundColor(theme.spoiler)
        postView.setTextColor(theme.post)
        highlightView.setTextColor(theme.post)
        neuroslopQuoteView.setTextColor(theme.neuroslopQuote)
        neuroslopView.setTextColor(theme.neuroslop)
    }

    private fun contrast(background: Int): Int = if (GraphicsUtils.isLight(background)) Color.BLACK else Color.WHITE
}
