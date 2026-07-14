package com.mishiranu.dashchan.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import android.util.Pair
import com.mishiranu.dashchan.ui.StackItem

class SavedPageItem(
    @JvmField val stackItem: StackItem?,
    @JvmField val createdRealtime: Long,
    @JvmField val threadTitle: String?,
    @JvmField val allowReturn: Boolean,
) : Parcelable {
    private fun createInternal(page: Page?): Pair<PageFragment, PageItem> {
        val fragment =
            stackItem!!.create(
                if (page == null) {
                    null
                } else {
                    StackItem.ReplaceFragment { newFragment ->
                        PageFragment(page, (newFragment as PageFragment).retainId)
                    }
                },
            ) as PageFragment
        val pageItem = PageItem()
        pageItem.createdRealtime = createdRealtime
        pageItem.threadTitle = threadTitle
        pageItem.allowReturn = allowReturn
        return Pair(fragment, pageItem)
    }

    fun create(): Pair<PageFragment, PageItem> = createInternal(null)

    fun createWithNewPage(page: Page): Pair<PageFragment, PageItem> = createInternal(page)

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeByte(if (stackItem != null) 1 else 0)
        stackItem?.writeToParcel(dest, flags)
        dest.writeLong(createdRealtime)
        dest.writeString(threadTitle)
        dest.writeByte(if (allowReturn) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SavedPageItem> =
            object : Parcelable.Creator<SavedPageItem> {
                override fun createFromParcel(source: Parcel): SavedPageItem {
                    val stackItem =
                        if (source.readByte().toInt() != 0) {
                            StackItem.CREATOR.createFromParcel(source)
                        } else {
                            null
                        }
                    val createdRealtime = source.readLong()
                    val threadTitle = source.readString()
                    val allowReturn = source.readByte().toInt() != 0
                    return SavedPageItem(stackItem, createdRealtime, threadTitle, allowReturn)
                }

                override fun newArray(size: Int): Array<SavedPageItem?> = arrayOfNulls(size)
            }
    }
}
