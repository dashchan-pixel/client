package com.mishiranu.dashchan.widget

import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ListPosition(
    @JvmField val position: Int,
    @JvmField val offset: Int,
) : Parcelable {
    fun interface PositionTest {
        fun isPositionAllowed(position: Int): Boolean
    }

    fun apply(recyclerView: RecyclerView) {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        layoutManager.scrollToPositionWithOffset(position, offset)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeInt(position)
        dest.writeInt(offset)
    }

    companion object {
        @JvmStatic
        fun obtain(
            recyclerView: RecyclerView,
            positionTest: PositionTest?,
        ): ListPosition? {
            val rect = Rect()
            val paddingTop = recyclerView.paddingTop
            for (i in 0 until recyclerView.childCount) {
                val view = recyclerView.getChildAt(i)
                recyclerView.getDecoratedBoundsWithMargins(view, rect)
                if (rect.bottom > paddingTop) {
                    val position = recyclerView.getChildLayoutPosition(view)
                    if (position >= 0 && (positionTest == null || positionTest.isPositionAllowed(position))) {
                        val offset = rect.top - paddingTop
                        return ListPosition(position, offset)
                    }
                }
            }
            return null
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ListPosition> =
            object : Parcelable.Creator<ListPosition> {
                override fun createFromParcel(source: Parcel): ListPosition {
                    val position = source.readInt()
                    val offset = source.readInt()
                    return ListPosition(position, offset)
                }

                override fun newArray(size: Int): Array<ListPosition?> = arrayOfNulls(size)
            }
    }
}
