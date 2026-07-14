package com.mishiranu.dashchan.widget

import android.content.Context
import android.widget.FrameLayout

// Measures itself to the largest size with the given width/height aspect ratio that fits within the
// parent constraints (fit-center). Used to host the video SurfaceView so the surface is created at the
// correct shape from the first layout pass and the video is never rendered stretched.
class AspectRatioFrameLayout(
    context: Context,
) : FrameLayout(context) {
    private var aspectRatio = 0f

    fun setAspectRatio(ratio: Float) {
        if (aspectRatio != ratio) {
            aspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (aspectRatio <= 0f) {
            return
        }
        var width = measuredWidth
        var height = measuredHeight
        if (width <= 0 || height <= 0) {
            return
        }
        val viewRatio = width.toFloat() / height
        if (viewRatio > aspectRatio) {
            width = (height * aspectRatio).toInt()
        } else {
            height = (width / aspectRatio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
