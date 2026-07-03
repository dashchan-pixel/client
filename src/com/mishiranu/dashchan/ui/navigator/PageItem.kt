package com.mishiranu.dashchan.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import androidx.fragment.app.FragmentManager
import com.mishiranu.dashchan.ui.StackItem

class PageItem : Parcelable {
	@JvmField var createdRealtime = 0L
	@JvmField var threadTitle: String? = null
	@JvmField var allowReturn = false

	fun toSaved(fragmentManager: FragmentManager, fragment: PageFragment): SavedPageItem {
		return SavedPageItem(StackItem(fragmentManager, fragment, SAVE),
				createdRealtime, threadTitle, allowReturn)
	}

	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeLong(createdRealtime)
		dest.writeString(threadTitle)
		dest.writeByte(if (allowReturn) 1 else 0)
	}

	companion object {
		private val SAVE = StackItem.SaveFragment { fragmentManager, fragment ->
			val pageFragment = fragment as PageFragment
			try {
				pageFragment.setSaveToStack(true)
				fragmentManager.saveFragmentInstanceState(fragment)
			} finally {
				pageFragment.setSaveToStack(false)
			}
		}

		@JvmField
		val CREATOR: Parcelable.Creator<PageItem> = object : Parcelable.Creator<PageItem> {
			override fun createFromParcel(source: Parcel): PageItem {
				val pageItem = PageItem()
				pageItem.createdRealtime = source.readLong()
				pageItem.threadTitle = source.readString()
				pageItem.allowReturn = source.readByte().toInt() != 0
				return pageItem
			}

			override fun newArray(size: Int): Array<PageItem?> = arrayOfNulls(size)
		}
	}
}
