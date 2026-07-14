package com.mishiranu.dashchan.widget

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

class SortableHelper<VH : RecyclerView.ViewHolder>(
    recyclerView: RecyclerView,
    private val callback: Callback<VH>,
) : ItemTouchHelper.Callback() {
    interface Callback<VH : RecyclerView.ViewHolder> {
        fun onDragStart(holder: VH)

        fun onDragFinish(
            holder: VH?,
            cancelled: Boolean,
        )

        fun onDragCanMove(
            fromHolder: VH,
            toHolder: VH,
        ): Boolean

        fun onDragMove(
            fromHolder: VH,
            toHolder: VH,
        ): Boolean
    }

    class DragState {
        private var from = 0
        private var to = 0

        fun set(
            from: Int,
            to: Int,
        ) {
            if (this.from == -1) {
                this.from = from
            }
            this.to = to
        }

        fun reset() {
            from = -1
            to = -1
        }

        fun getMovedTo(): Int = if (from >= 0 && to >= 0 && to != from) to else -1
    }

    private val helper = ItemTouchHelper(this)

    private var isDragging = false

    init {
        helper.attachToRecyclerView(recyclerView)
    }

    fun start(holder: VH) {
        helper.startDrag(holder)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(viewHolder: RecyclerView.ViewHolder): VH = viewHolder as VH

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = callback.onDragCanMove(cast(current), cast(target))

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = callback.onDragMove(cast(viewHolder), cast(target))

    private var startViewHolder: WeakReference<VH>? = null

    override fun onSelectedChanged(
        viewHolder: RecyclerView.ViewHolder?,
        actionState: Int,
    ) {
        super.onSelectedChanged(viewHolder, actionState)

        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            isDragging = true
            val holder = cast(viewHolder!!)
            startViewHolder = WeakReference(holder)
            callback.onDragStart(holder)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            val cancelled = !isDragging
            callback.onDragFinish(if (viewHolder != null) cast(viewHolder) else startViewHolder?.get(), cancelled)
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        var dy = dY
        val position = viewHolder.bindingAdapterPosition
        val count = recyclerView.layoutManager!!.itemCount
        if (position == 0) {
            dy = max(0f, dy)
        }
        if (position + 1 == count) {
            dy = min(dy, 0f)
        }
        if (position > 0 && dy < 0f) {
            val index = recyclerView.indexOfChild(viewHolder.itemView)
            if (index > 0) {
                val before = recyclerView.getChildViewHolder(recyclerView.getChildAt(index - 1))
                if (!canDropOver(recyclerView, viewHolder, before)) {
                    dy = 0f
                }
            } else {
                dy = 0f
            }
        }
        if (position + 1 < count && dy > 0f) {
            val index = recyclerView.indexOfChild(viewHolder.itemView)
            if (index >= 0 && index + 1 < recyclerView.childCount) {
                val after = recyclerView.getChildViewHolder(recyclerView.getChildAt(index + 1))
                if (!canDropOver(recyclerView, viewHolder, after)) {
                    dy = 0f
                }
            } else {
                dy = 0f
            }
        }
        val itemHeight = viewHolder.itemView.height
        dy = max(-itemHeight.toFloat(), min(dy, itemHeight.toFloat()))
        super.onChildDraw(c, recyclerView, viewHolder, dX, dy, actionState, isCurrentlyActive)
    }

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int,
    ) {}
}
