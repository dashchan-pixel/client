package com.mishiranu.dashchan.widget

import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils

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

        val theme = ThemeEngine.getTheme(getContext())
        val colorControlDisabled = GraphicsUtils.applyAlpha(theme.controlNormal21, theme.disabledAlpha21)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        val colors = intArrayOf(colorControlDisabled, theme.accent)
        backgroundTintList = ColorStateList(states, colors)
        isSingleLine = true
        isAllCaps = true
    }

    override fun setTranslationZ(translationZ: Float) {
        val maxTranslationZ = (2f * density).toInt().toFloat()
        super.setTranslationZ(translationZ.coerceAtMost(maxTranslationZ))
    }
}
