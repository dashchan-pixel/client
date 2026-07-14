package com.mishiranu.dashchan.util

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

object AnimationUtils {
    @JvmField val ACCELERATE_DECELERATE_INTERPOLATOR: Interpolator = AccelerateDecelerateInterpolator()

    @JvmField val ACCELERATE_INTERPOLATOR: Interpolator = AccelerateInterpolator()

    @JvmField val DECELERATE_INTERPOLATOR: Interpolator = DecelerateInterpolator()

    @JvmStatic
    fun measureDynamicHeight(view: View) {
        var width = view.width
        if (width <= 0) {
            val parent = view.parent as View
            width = parent.width - parent.paddingLeft - parent.paddingRight
        }
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthMeasureSpec, heightMeasureSpec)
    }

    @JvmStatic
    fun ofHeight(
        view: View,
        from: Int,
        to: Int,
        needMeasure: Boolean,
    ): Animator {
        var realFrom = from
        var realTo = to
        val fromWC = realFrom == ViewGroup.LayoutParams.WRAP_CONTENT
        val toWC = realTo == ViewGroup.LayoutParams.WRAP_CONTENT
        if (fromWC || toWC) {
            if (needMeasure) {
                measureDynamicHeight(view)
            }
            val height = view.measuredHeight
            if (fromWC) {
                realFrom = height
            }
            if (toWC) {
                realTo = height
            }
        }
        val animator = ValueAnimator.ofInt(realFrom, realTo)
        val listener = HeightAnimatorListener(view, to)
        animator.addListener(listener)
        animator.addUpdateListener(listener)
        return animator
    }

    private class HeightAnimatorListener(
        private val view: View,
        private val resultingHeight: Int,
    ) : Animator.AnimatorListener,
        ValueAnimator.AnimatorUpdateListener {
        private fun applyHeight(height: Int) {
            view.layoutParams.height = height
            view.requestLayout()
        }

        override fun onAnimationUpdate(animation: ValueAnimator) {
            applyHeight(animation.animatedValue as Int)
        }

        override fun onAnimationStart(animation: Animator) {}

        override fun onAnimationEnd(animation: Animator) {
            applyHeight(resultingHeight)
        }

        override fun onAnimationCancel(animation: Animator) {}

        override fun onAnimationRepeat(animation: Animator) {}
    }

    class VisibilityListener(
        private val view: View,
        private val visibility: Int,
    ) : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) {}

        override fun onAnimationEnd(animation: Animator) {
            view.visibility = visibility
        }

        override fun onAnimationCancel(animation: Animator) {}

        override fun onAnimationRepeat(animation: Animator) {}
    }

    @JvmStatic
    fun lerp(
        a: Float,
        b: Float,
        t: Float,
    ): Float = a + (b - a) * t

    @JvmStatic
    fun areAnimatorsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
}
