package com.mishiranu.dashchan.graphics

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import com.mishiranu.dashchan.util.GraphicsUtils

/**
 * What an [android.widget.ImageView] is given for a bitmap the image loader loaded, in place of the
 * [BitmapDrawable] that `setImageBitmap` would have made of it.
 *
 * The loader hands out bitmaps that live in GPU memory ([Bitmap.Config.HARDWARE]), and a software
 * canvas cannot draw one at all -- it throws. So the plain drawable is fine until something renders
 * the view offscreen, which a threadshot does to every post view it captures: the country flags and
 * the decorator's icons in those posts are ordinary image views, and each was enough to take the
 * whole threadshot down. Drawing through [GraphicsUtils.drawBitmap] falls back to a software copy
 * there, and leaves the on-screen path -- the one that has to be cheap, and the reason the bitmap is
 * in GPU memory in the first place -- exactly as [BitmapDrawable] draws it.
 *
 * The bitmap belongs to the loader's cache, so this never recycles it.
 */
class SoftwareSafeBitmapDrawable(
    resources: Resources,
    bitmap: Bitmap,
) : BitmapDrawable(resources, bitmap) {
    override fun draw(canvas: Canvas) {
        val bitmap = bitmap
        if (bitmap != null && !canvas.isHardwareAccelerated && bitmap.config == Bitmap.Config.HARDWARE) {
            // Bounds are the whole of what super would work out: gravity, tiling and mirroring are
            // all left at the defaults by the loader, and an image view scales through the canvas.
            GraphicsUtils.drawBitmap(canvas, bitmap, null, bounds, paint)
        } else {
            super.draw(canvas)
        }
    }
}
