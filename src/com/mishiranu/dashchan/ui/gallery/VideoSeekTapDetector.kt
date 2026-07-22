package com.mishiranu.dashchan.ui.gallery

import android.os.SystemClock

/**
 * Counts rapid taps on a video surface into an escalating fast-forward / rewind gesture,
 * YouTube/TikTok-style. The first tap of a burst does nothing (so a lone tap keeps its normal
 * meaning); the double tap seeks 5s, the third quick tap 10s, the fourth and later 20s. Taps on
 * the left half of the surface rewind, the right half fast-forwards; switching sides restarts the
 * burst so the double tap is required again in the new direction.
 *
 * The host feeds every qualifying tap-up (single pointer, no scroll/long-press) to [onTap] and,
 * when it returns a non-null [Seek], applies the delta and suppresses the tap's normal action.
 */
class VideoSeekTapDetector {
    class Seek(
        /** Signed seek delta in milliseconds (negative rewinds). */
        val deltaMs: Long,
        /** Signed accumulated delta of the whole burst so far, for on-screen feedback. */
        val burstMs: Long,
        val forward: Boolean,
    )

    private var lastTapTime = 0L
    private var lastForward = false
    private var count = 0
    private var burstMs = 0L

    fun onTap(
        x: Float,
        viewWidth: Int,
    ): Seek? {
        val now = SystemClock.uptimeMillis()
        val forward = x >= viewWidth / 2f
        val consecutive = now - lastTapTime <= WINDOW_MS
        lastTapTime = now
        if (!consecutive || forward != lastForward) {
            // Start (or restart, on a direction flip) a burst; the first tap never seeks.
            count = 1
            burstMs = 0L
            lastForward = forward
            return null
        }
        count++
        val delta =
            when (count) {
                2 -> STEP_DOUBLE_TAP
                3 -> STEP_THIRD_TAP
                else -> STEP_FURTHER_TAP
            }
        val signed = if (forward) delta else -delta
        burstMs += signed
        return Seek(signed, burstMs, forward)
    }

    fun reset() {
        lastTapTime = 0L
        count = 0
        burstMs = 0L
    }

    companion object {
        // A tap this soon after the previous one continues the burst; otherwise it starts fresh.
        private const val WINDOW_MS = 450L
        private const val STEP_DOUBLE_TAP = 5000L
        private const val STEP_THIRD_TAP = 10000L
        private const val STEP_FURTHER_TAP = 20000L
    }
}
