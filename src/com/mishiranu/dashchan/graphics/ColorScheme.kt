package com.mishiranu.dashchan.graphics

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import androidx.core.graphics.ColorUtils
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ThemeEngine

class ColorScheme(
    context: Context,
    theme: ThemeEngine.Theme,
) {
    @JvmField val windowBackgroundColor: Int = theme.window

    @JvmField val dialogBackgroundColor: Int

    @JvmField val spoilerBackgroundColor: Int = theme.spoiler

    @JvmField val spoilerTopBackgroundColor: Int

    @JvmField val linkColor: Int = theme.link

    @JvmField val quoteColor: Int = theme.quote

    @JvmField val clickedColor: Int

    @JvmField val tripcodeColor: Int = theme.tripcode

    @JvmField val capcodeColor: Int = theme.capcode

    @JvmField val neuroslopColor: Int = theme.neuroslop

    @JvmField val highlightTextColor: Int

    @JvmField val highlightBackgroundColor: Int

    @JvmField val highlightUserPostBackgroundColor: Int

    @JvmField val colorGainFactor: Float = theme.colorGainFactor

    init {
        spoilerTopBackgroundColor = minOf((spoilerBackgroundColor ushr 24) * 2, 0xff) shl 24 or
            (spoilerBackgroundColor and 0x00ffffff)
        dialogBackgroundColor = ResourceUtils.getDialogBackground(context)
        clickedColor = ResourceUtils.getSystemSelectorColor(context)
        highlightTextColor = (Color.BLACK or linkColor) and 0x80ffffff.toInt()
        highlightBackgroundColor = if (GraphicsUtils.isLight(windowBackgroundColor)) 0x1e000000 else 0x1effffff
        highlightUserPostBackgroundColor = ColorUtils.setAlphaComponent(theme.highlight, (255 * 0.1).toInt())
    }

    interface Span {
        fun applyColorScheme(colorScheme: ColorScheme?)
    }

    fun apply(text: CharSequence?) {
        apply(getSpans(text))
    }

    fun apply(spans: Array<Span>?) {
        spans?.forEach { it.applyColorScheme(this) }
    }

    companion object {
        @JvmStatic
        fun getSpans(text: CharSequence?): Array<Span>? = if (text is Spanned) text.getSpans(0, text.length, Span::class.java) else null
    }
}
