package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import com.mishiranu.dashchan.R

/**
 * Material components validate their attributes against a Material3 theme, but this app is themed on
 * the framework [android.R.style.Theme_Material] through [ThemeEngine], which is not one. Creating a
 * Material widget with a plain app context throws at inflation time for the missing attrs.
 *
 * [wrap] returns an isolated Material3 theme overlay over the given context so Material widgets can be
 * constructed. The light/dark variant is chosen from the active [ThemeEngine] theme's window colour
 * (not the system night setting), so the widget matches whatever user theme is currently on screen.
 * The overlay only supplies M3 shapes/sizes/attrs — tint the widget with the ThemeEngine accent
 * afterwards (e.g. via [ThemeEngine.applyStyle]) so its colours track the user's theme rather than
 * the stock Material3 palette.
 */
object MaterialContext {
    @JvmStatic
    fun wrap(context: Context): Context {
        val window = ThemeEngine.getTheme(context).window
        val dark =
            0.299 * Color.red(window) +
                0.587 * Color.green(window) +
                0.114 * Color.blue(window) < 128
        return ContextThemeWrapper(
            context,
            if (dark) {
                com.google.android.material.R.style.Theme_Material3_Dark
            } else {
                com.google.android.material.R.style.Theme_Material3_Light
            },
        )
    }

    /**
     * [wrap] with `colorSurface` blanked out, for building [com.google.android.material.chip.Chip].
     *
     * A Material3 chip composites its fill from a `?colorSurface` layer and the chip background on
     * top of it. [ThemeEngine.applyStyle] leaves an unchecked chip's background transparent so the
     * real background shows through, which with the stock surface layer in place means the
     * Material3 baseline surface colour instead. Blanking `colorSurface` removes that layer; a
     * checked chip is unaffected, its opaque accent covers it either way.
     */
    @JvmStatic
    fun wrapChips(context: Context): Context = ContextThemeWrapper(wrap(context), R.style.ThemeOverlay_Chip)
}
