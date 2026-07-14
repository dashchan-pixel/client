package com.mishiranu.dashchan.util

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MenuItem
import android.view.View
import androidx.core.view.GravityCompat
import androidx.customview.widget.ViewDragHelper
import androidx.drawerlayout.widget.DrawerLayout
import com.mishiranu.dashchan.graphics.BaseDrawable
import kotlin.math.cos
import kotlin.math.sin

class DrawerToggle(
    private val activity: Activity,
    toolbarContext: Context?,
    private val drawerLayout: DrawerLayout,
) : DrawerLayout.DrawerListener {
    private val arrowDrawable = ArrowDrawable(toolbarContext ?: activity)

    enum class Mode { DISABLED, DRAWER, UP }

    private var mode = Mode.DISABLED

    fun setDrawerIndicatorMode(mode: Mode) {
        if (this.mode != mode) {
            this.mode = mode
            val actionBar = activity.actionBar!!
            if (mode == Mode.DISABLED) {
                actionBar.setHomeAsUpIndicator(null)
                actionBar.setDisplayHomeAsUpEnabled(false)
            } else {
                actionBar.setDisplayHomeAsUpEnabled(true)
                actionBar.setHomeAsUpIndicator(arrowDrawable)
                val open = drawerLayout.isDrawerOpen(GravityCompat.START) && arrowDrawable.position == 1f
                if (!open) {
                    val animator = ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = DRAWER_CLOSE_DURATION.toLong()
                    animator.addUpdateListener(StateArrowAnimatorListener(mode == Mode.DRAWER))
                    animator.start()
                }
            }
        }
    }

    fun syncState() {
        if (mode != Mode.DISABLED) {
            arrowDrawable.setPosition(
                if (mode == Mode.UP ||
                    drawerLayout.isDrawerOpen(GravityCompat.START)
                ) {
                    1f
                } else {
                    0f
                },
            )
            activity.actionBar!!.setHomeAsUpIndicator(arrowDrawable)
        }
    }

    fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item != null && item.itemId == android.R.id.home) {
            if (drawerLayout.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_UNLOCKED) {
                return false
            }
            if (mode == Mode.DRAWER) {
                if (drawerLayout.isDrawerVisible(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
                return true
            } else if (mode == Mode.UP) {
                if (drawerLayout.isDrawerVisible(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return true
                }
            }
        }
        return false
    }

    override fun onDrawerSlide(
        drawerView: View,
        slideOffset: Float,
    ) {
        if (mode == Mode.DRAWER) {
            arrowDrawable.setPosition(slideOffset)
        }
    }

    override fun onDrawerOpened(drawerView: View) {
        if (mode == Mode.DRAWER) {
            arrowDrawable.setPosition(1f)
        }
    }

    override fun onDrawerClosed(drawerView: View) {
        if (mode == Mode.DRAWER) {
            arrowDrawable.setPosition(0f)
        }
    }

    override fun onDrawerStateChanged(newState: Int) {}

    private inner class ArrowDrawable(
        context: Context,
    ) : BaseDrawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        private val barThickness: Float
        private val topBottomArrowSize: Float
        private val barSize: Float
        private val middleArrowSize: Float
        private val barGap: Float
        private val size: Int

        private var verticalMirror = false
        var position = 0f
            private set

        init {
            paint.isAntiAlias = true
            paint.color = ResourceUtils.getColor(context, android.R.attr.textColorPrimary)
            val density = ResourceUtils.obtainDensity(context)
            size = (24f * density).toInt()
            barSize = 16f * density
            topBottomArrowSize = 9.5f * density
            barThickness = 2f * density
            barGap = 3f * density
            middleArrowSize = 13.6f * density
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.SQUARE
            paint.strokeWidth = barThickness
        }

        fun setPosition(position: Float) {
            val clamped = position.coerceIn(0f, 1f)
            if (clamped == 1f) {
                verticalMirror = true
            } else if (clamped == 0f) {
                verticalMirror = false
            }
            this.position = clamped
            invalidateSelf()
        }

        override fun getIntrinsicWidth(): Int = size

        override fun getIntrinsicHeight(): Int = size

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val rtl = isLayoutRtl()
            val position = this.position
            val arrowSize = AnimationUtils.lerp(barSize, topBottomArrowSize, position)
            val middleBarSize = AnimationUtils.lerp(barSize, middleArrowSize, position)
            val middleBarCut = AnimationUtils.lerp(0f, barThickness / 2f, position)
            val rotation = AnimationUtils.lerp(0f, ARROW_HEAD_ANGLE, position)
            val canvasRotate = AnimationUtils.lerp(if (rtl) 0f else -180f, if (rtl) 180f else 0f, position)
            val topBottomBarOffset = AnimationUtils.lerp(barGap + barThickness, 0f, position)
            path.rewind()
            val arrowEdge = -middleBarSize / 2f + 0.5f
            path.moveTo(arrowEdge + middleBarCut, 0f)
            path.rLineTo(middleBarSize - middleBarCut, 0f)
            val arrowWidth = Math.round(arrowSize * cos(rotation)).toFloat()
            val arrowHeight = Math.round(arrowSize * sin(rotation)).toFloat()
            path.moveTo(arrowEdge, topBottomBarOffset)
            path.rLineTo(arrowWidth, arrowHeight)
            path.moveTo(arrowEdge, -topBottomBarOffset)
            path.rLineTo(arrowWidth, -arrowHeight)
            path.moveTo(0f, 0f)
            path.close()
            canvas.save()
            canvas.rotate(
                canvasRotate * (if (verticalMirror xor rtl) -1f else 1f),
                bounds.centerX().toFloat(),
                bounds.centerY().toFloat(),
            )
            canvas.translate(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private inner class StateArrowAnimatorListener(
        private val enable: Boolean,
    ) : ValueAnimator.AnimatorUpdateListener {
        override fun onAnimationUpdate(animation: ValueAnimator) {
            val value = animation.animatedValue as Float
            arrowDrawable.setPosition(if (enable) 1f - value else value)
        }
    }

    private fun isLayoutRtl(): Boolean = activity.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL

    companion object {
        private val DRAWER_CLOSE_DURATION: Int =
            try {
                val baseSettleDurationField = ViewDragHelper::class.java.getDeclaredField("BASE_SETTLE_DURATION")
                baseSettleDurationField.isAccessible = true
                baseSettleDurationField.get(null) as Int
            } catch (e: Exception) {
                // Library method, fix if needed
                throw RuntimeException(e)
            }

        private val ARROW_HEAD_ANGLE = Math.toRadians(45.0).toFloat()
    }
}
