package com.mishiranu.dashchan.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

class SimpleBitmapDrawable(
    private val bitmap: Bitmap,
    private val width: Int,
    private val height: Int,
    private val allowRecycle: Boolean,
) : BaseDrawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    constructor(bitmap: Bitmap, allowRecycle: Boolean) :
        this(bitmap, bitmap.width, bitmap.height, allowRecycle)

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

    fun recycle() {
        if (allowRecycle) {
            bitmap.recycle()
        }
    }
}
