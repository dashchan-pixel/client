package com.mishiranu.dashchan.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.BackEventCompat
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.ResourceUtils

/**
 * Applies the predictive back treatment to a single view for the duration of a back gesture: the
 * view scales down about its far edge, drifts in the direction of the swipe and pivots vertically
 * around the touch point, rounding its corners off as it shrinks.
 *
 * The app has no Material Components dependency, so this is hand-rolled and the constants below
 * approximate the published Material predictive back spec rather than sharing values with a library.
 *
 * Only [progress] follows the finger. [settle] springs the view back to rest and serves both
 * outcomes: an abandoned gesture unwinds, and a committed one lets the destination grow into place
 * from under the transform. [reset] is the immediate, un-animated version.
 *
 * Every entry point is a no-op unless a gesture is being tracked. The back callbacks are not
 * guaranteed to arrive in order or in pairs -- a cancel can arrive for a gesture that never
 * started -- and [start] itself declines to track anything while animations are disabled.
 */
class PredictiveBackTransform(
    private val view: View,
) {
    private val density = ResourceUtils.obtainDensity(view)

    private var settleAnimator: ValueAnimator? = null
    private var originalOutlineProvider: ViewOutlineProvider? = null
    private var originalClipToOutline = false
    private var active = false

    /** Tracks progress, so the outline is recomputed per frame rather than baked into the provider. */
    private var cornerRadius = 0f

    private val outlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }

    fun start(backEvent: BackEventCompat) {
        if (!AnimationUtils.areAnimatorsEnabled()) {
            return
        }
        stopSettling()
        if (!active) {
            active = true
            originalOutlineProvider = view.outlineProvider
            originalClipToOutline = view.clipToOutline
            view.outlineProvider = outlineProvider
            view.clipToOutline = true
        }
        apply(backEvent, 0f)
    }

    fun progress(backEvent: BackEventCompat) {
        if (!active) {
            return
        }
        apply(backEvent, INTERPOLATOR.getInterpolation(backEvent.progress))
    }

    /** Springs the view back to rest, whether the gesture was abandoned or committed. */
    fun settle() {
        if (!active) {
            return
        }
        val fromScale = view.scaleX
        val fromTranslationX = view.translationX
        val fromCornerRadius = cornerRadius
        val animator = ValueAnimator.ofFloat(1f, 0f)
        animator.duration = SETTLE_DURATION
        animator.interpolator = AnimationUtils.DECELERATE_INTERPOLATOR
        animator.addUpdateListener {
            val fraction = it.animatedValue as Float
            view.scaleX = AnimationUtils.lerp(1f, fromScale, fraction)
            view.scaleY = view.scaleX
            view.translationX = fromTranslationX * fraction
            cornerRadius = fromCornerRadius * fraction
            view.invalidateOutline()
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Also fires when the animation is cancelled, by which point the field has
                    // already been cleared and the canceller owns the reset.
                    if (settleAnimator === animator) {
                        settleAnimator = null
                        reset()
                    }
                }
            },
        )
        settleAnimator = animator
        animator.start()
    }

    /** Snaps the view back to rest and restores everything [start] took over. */
    fun reset() {
        stopSettling()
        if (!active) {
            return
        }
        active = false
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        cornerRadius = 0f
        view.outlineProvider = originalOutlineProvider
        view.clipToOutline = originalClipToOutline
        originalOutlineProvider = null
    }

    private fun stopSettling() {
        val animator = settleAnimator
        if (animator != null) {
            // Cleared first: the end listener keys off this field to tell a natural end from a
            // cancel, and must not recurse back into reset() here.
            settleAnimator = null
            animator.cancel()
        }
    }

    private fun apply(
        backEvent: BackEventCompat,
        progress: Float,
    ) {
        val fromLeft = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
        // Scale about the edge opposite the swipe, so the far edge stays put and the gap opens up
        // under the finger.
        view.pivotX = if (fromLeft) view.width.toFloat() else 0f
        // Follow the finger vertically, but keep the pivot inside the view: the gesture area
        // overlaps the navigation bar, and a touch below the view would flip the shrink upward.
        view.pivotY = backEvent.touchY.coerceIn(0f, view.height.toFloat())
        view.scaleX = AnimationUtils.lerp(1f, MIN_SCALE, progress)
        view.scaleY = view.scaleX
        view.translationX = (if (fromLeft) 1f else -1f) * MAX_TRANSLATION_X_DP * density * progress
        cornerRadius = MAX_CORNER_RADIUS_DP * density * progress
        view.invalidateOutline()
    }

    private companion object {
        /** How far the view shrinks at full progress. */
        const val MIN_SCALE = 0.9f

        /** Drift in the direction of the swipe at full progress. */
        const val MAX_TRANSLATION_X_DP = 8f

        const val MAX_CORNER_RADIUS_DP = 28f

        const val SETTLE_DURATION = 200L

        val INTERPOLATOR = AnimationUtils.DECELERATE_INTERPOLATOR
    }
}
