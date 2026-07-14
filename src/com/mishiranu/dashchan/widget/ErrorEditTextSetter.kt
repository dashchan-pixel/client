package com.mishiranu.dashchan.widget

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.EditText
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils

class ErrorEditTextSetter(
    private val editText: EditText,
) {
    private var error = false

    private var backgroundNormal: Drawable? = null
    private var backgroundError: Drawable? = null
    private var colorFilter: PorterDuffColorFilter? = null

    fun setError(error: Boolean) {
        if (this.error != error) {
            this.error = error
            if (backgroundNormal == null) {
                backgroundNormal =
                    ResourceUtils.getDrawable(
                        editText.context,
                        android.R.attr.editTextBackground,
                        0,
                    )
                val backgroundError =
                    ResourceUtils.getDrawable(
                        editText.context,
                        android.R.attr.editTextBackground,
                        0,
                    )
                this.backgroundError = backgroundError
                if (colorFilter == null) {
                    colorFilter =
                        PorterDuffColorFilter(
                            ResourceUtils.getColor(
                                editText.context,
                                R.attr.colorTextError,
                            ),
                            PorterDuff.Mode.SRC_IN,
                        )
                }
                backgroundError!!.mutate().colorFilter = colorFilter
            }
            editText.background = if (error) backgroundError else backgroundNormal
        }
    }
}
