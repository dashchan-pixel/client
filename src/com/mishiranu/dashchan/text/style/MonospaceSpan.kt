package com.mishiranu.dashchan.text.style

import android.annotation.SuppressLint
import android.text.style.TypefaceSpan

@SuppressLint("ParcelCreator")
class MonospaceSpan(
    val isAsciiArt: Boolean,
) : TypefaceSpan("monospace")
