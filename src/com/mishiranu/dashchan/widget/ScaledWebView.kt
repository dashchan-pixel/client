package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class ScaledWebView(
    context: Context,
    minScale: Float,
    extraInitialScale: Float,
) : WebView(context) {
    private var nativeScale: Float
    private var scale: Float

    init {
        val nativeScaleInt = max(25f, min(minScale * extraInitialScale * 100, 300f)).toInt()
        super.setInitialScale(nativeScaleInt)
        nativeScale = nativeScaleInt / 100f
        scale = minScale
    }

    private fun getRelativeScale(): Float = scale / nativeScale

    override fun setInitialScale(scaleInPercent: Int): Unit = throw UnsupportedOperationException()

    fun setScale(scale: Float) {
        if (this.scale != scale) {
            this.scale = scale
            invalidate()
        }
    }

    fun notifyClientScaleChanged(newScale: Float) {
        if ((1000 * nativeScale).toInt() != (1000 * newScale).toInt()) {
            nativeScale = newScale
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val scale = getRelativeScale()
        canvas.save()
        canvas.scale(scale, scale)
        super.onDraw(canvas)
        canvas.restore()
    }

    private fun createScaledMotionEvent(event: MotionEvent): MotionEvent {
        val scale = getRelativeScale()
        return MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            event.x / scale,
            event.y / scale,
            event.pressure,
            event.size,
            event.metaState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
        )
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val scaledEvent = createScaledMotionEvent(event)
        try {
            return super.onHoverEvent(scaledEvent)
        } finally {
            scaledEvent.recycle()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaledEvent = createScaledMotionEvent(event)
        try {
            return super.onTouchEvent(scaledEvent)
        } finally {
            scaledEvent.recycle()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val scaledEvent = createScaledMotionEvent(event)
        try {
            return super.onGenericMotionEvent(scaledEvent)
        } finally {
            scaledEvent.recycle()
        }
    }

    override fun onTrackballEvent(event: MotionEvent): Boolean {
        val scaledEvent = createScaledMotionEvent(event)
        try {
            return super.onTrackballEvent(scaledEvent)
        } finally {
            scaledEvent.recycle()
        }
    }
}
