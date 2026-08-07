package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils

class MaterialButton(
    context: Context,
) : Button(context, null, 0, android.R.style.Widget_Material_Button_Colored) {
    private val density = ResourceUtils.obtainDensity(this)

    init {
        setTextColor(
            ResourceUtils.getColorStateList(
                getContext(),
                android.R.attr.textColorPrimaryInverse,
            ),
        )

        // Before the tint, which the view applies to whatever background is set at the time.
        roundCorners(Preferences.uiCornerRadius * density)
        val theme = ThemeEngine.getTheme(getContext())
        val colorControlDisabled = GraphicsUtils.applyAlpha(theme.controlNormal21, theme.disabledAlpha21)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        val colors = intArrayOf(colorControlDisabled, theme.accent)
        backgroundTintList = ColorStateList(states, colors)
        isSingleLine = true
        isAllCaps = true
    }

    /**
     * Rounds the button to [radius] instead of the 2dp `control_corner_material` the framework drawable
     * ships with, so it matches the app's other surfaces.
     *
     * `btn_default_material` is an [InsetDrawable] over a [RippleDrawable] whose single layer is the
     * shape that paints the button, and rounding only that shape would leave the *ripple* square: a
     * ripple with no mask layer is clipped to the plain rectangle of its bounds, so the corner would
     * come back square for as long as the button is held. A mask cannot be added to a ripple after the
     * fact either — [RippleDrawable] picks its mask up when its layers are inflated, not when one is
     * appended — so the ripple is built here instead, the rounded shape serving as both content and
     * mask. Only the ripple is replaced: the inset around it is the button's, holding the drawn button
     * clear of the touch target the view measures, and is kept as it was found.
     */
    private fun roundCorners(radius: Float) {
        val shape = GradientDrawable()
        // White because the view's backgroundTintList is what colours it, disabled state included.
        shape.setColor(Color.WHITE)
        shape.cornerRadius = radius
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = radius
        val highlight =
            ResourceUtils.getColorStateList(context, android.R.attr.colorControlHighlight)
                ?: ColorStateList.valueOf(Color.TRANSPARENT)
        val ripple = RippleDrawable(highlight, shape, mask)
        val inset = background?.mutate() as? InsetDrawable
        if (inset != null) {
            // The view has already taken its padding from the shape this replaces, and setting the
            // same drawable instance again is a no-op, so nothing here disturbs the layout.
            inset.drawable = ripple
            invalidate()
        } else {
            ViewUtils.setBackgroundPreservePadding(this, ripple)
        }
    }

    override fun setTranslationZ(translationZ: Float) {
        val maxTranslationZ = (2f * density).toInt().toFloat()
        super.setTranslationZ(translationZ.coerceAtMost(maxTranslationZ))
    }
}
