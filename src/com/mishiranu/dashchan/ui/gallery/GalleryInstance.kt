package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.view.Window
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelStoreOwner
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.ui.InstanceDialog

class GalleryInstance(
    @JvmField val context: Context,
    @JvmField var callback: Callback,
    @JvmField val actionBarColor: Int,
    @JvmField val chanName: String?,
    @JvmField val galleryItems: List<GalleryItem>,
) {
    interface Flags {
        companion object {
            const val LOCKED_USER = 0x00000001
            const val LOCKED_GRID = 0x00000002
            const val LOCKED_ERROR = 0x00000004
        }
    }

    interface Callback : ViewModelStoreOwner {
        fun getWindow(): Window?

        fun getChildFragmentManager(): FragmentManager

        fun downloadGalleryItem(galleryItem: GalleryItem)

        fun downloadGalleryItems(galleryItems: List<GalleryItem>)

        fun modifyVerticalSwipeState(
            ignoreIfGallery: Boolean,
            value: Float,
        )

        fun updateTitle()

        fun navigateGalleryOrFinish(enableGalleryMode: Boolean)

        fun navigatePageFromList(position: Int)

        fun navigatePost(
            galleryItem: GalleryItem,
            manually: Boolean,
            force: Boolean,
        )

        /** Switch to the video feed (flow) at the currently viewed attachment. */
        fun switchToFlow()

        /** Continue the currently viewed video in the floating picture-in-picture player. */
        fun switchToPip()

        fun isAllowNavigatePostManually(fromPager: Boolean): Boolean

        fun invalidateOptionsMenu()

        fun setScreenOnFixed(fixed: Boolean)

        fun isGalleryWindow(): Boolean

        fun isGalleryMode(): Boolean

        fun isSystemUiVisible(): Boolean

        fun modifySystemUiVisibility(
            flag: Int,
            value: Boolean,
        )

        fun toggleSystemUIVisibility(flag: Int)
    }

    companion object {
        @JvmStatic
        fun getCallback(provider: InstanceDialog.Provider): Callback = provider.parentFragment as Callback
    }
}
