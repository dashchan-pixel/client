package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Canvas
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.util.ViewUtils

class EdgeEffectHandler private constructor(
    context: Context,
    shift: Shift?,
) {
    enum class Side { TOP, BOTTOM }

    fun interface Shift {
        fun getEdgeEffectShift(side: Side): Int
    }

    private class ControlledEdgeEffect(
        context: Context,
        private val shift: Shift,
        private val side: Side,
    ) : EdgeEffect(context) {
        internal var pullable = true

        override fun onPull(deltaDistance: Float) {
            if (pullable) {
                super.onPull(deltaDistance)
            }
        }

        override fun onPull(
            deltaDistance: Float,
            displacement: Float,
        ) {
            if (pullable) {
                super.onPull(deltaDistance, displacement)
            }
        }

        override fun onAbsorb(velocity: Int) {
            if (pullable) {
                super.onAbsorb(velocity)
            }
        }

        override fun getMaxHeight(): Int = super.getMaxHeight() + shift.getEdgeEffectShift(side)

        override fun draw(canvas: Canvas): Boolean =
            if (pullable) {
                val shift = this.shift.getEdgeEffectShift(side)
                val needShift = shift != 0
                if (needShift) {
                    canvas.save()
                    canvas.translate(0f, shift.toFloat())
                }
                val result = super.draw(canvas)
                if (needShift) {
                    canvas.restore()
                }
                result
            } else {
                false
            }
    }

    private val topEdgeEffect: ControlledEdgeEffect
    private val bottomEdgeEffect: ControlledEdgeEffect

    init {
        val edgeShift = shift ?: Shift { 0 }
        topEdgeEffect = ControlledEdgeEffect(context, edgeShift, Side.TOP)
        bottomEdgeEffect = ControlledEdgeEffect(context, edgeShift, Side.BOTTOM)
    }

    fun setColor(color: Int) {
        ViewUtils.setEdgeEffectColor(topEdgeEffect, color)
        ViewUtils.setEdgeEffectColor(bottomEdgeEffect, color)
    }

    private fun getEdgeEffect(side: Side): ControlledEdgeEffect =
        when (side) {
            Side.TOP -> topEdgeEffect
            Side.BOTTOM -> bottomEdgeEffect
        }

    fun setPullable(
        side: Side,
        pullable: Boolean,
    ) {
        getEdgeEffect(side).pullable = pullable
    }

    fun finish(side: Side) {
        getEdgeEffect(side).finish()
    }

    companion object {
        @JvmStatic
        fun bind(
            recyclerView: RecyclerView,
            shift: Shift?,
        ): EdgeEffectHandler {
            val handler = EdgeEffectHandler(recyclerView.context, shift)
            recyclerView.edgeEffectFactory =
                object : RecyclerView.EdgeEffectFactory() {
                    override fun createEdgeEffect(
                        view: RecyclerView,
                        direction: Int,
                    ): EdgeEffect =
                        when (direction) {
                            DIRECTION_TOP -> handler.topEdgeEffect
                            DIRECTION_BOTTOM -> handler.bottomEdgeEffect
                            else -> super.createEdgeEffect(view, direction)
                        }
                }
            return handler
        }
    }
}
