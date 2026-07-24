package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Window
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelStoreOwner
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.widget.ThemeEngine

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

        /**
         * A context for the gallery's own popups (context menus, dialogs). The gallery window is themed
         * with [com.mishiranu.dashchan.R.style.Theme_Gallery], which is fullscreen — not a floating
         * theme — so the [ThemeEngine] layout inflater it produces is not "direct". Dialogs inflated
         * from it therefore skip [ThemeEngine]'s rounded-corner window background (it only applies to
         * direct app surfaces), leaving gallery menus square while every other menu is rounded.
         *
         * This keeps the gallery Dialog's window token (so the popup stays attached to the immersive
         * gallery window) but re-themes it with the normal app theme and re-attaches it to the
         * [ThemeEngine], yielding a direct inflater. The popup then matches the rest of the app: the
         * user's theme colours and the global corner radius.
         */
        @JvmStatic
        fun menuContext(windowContext: Context): Context {
            val base = ThemeEngine.getTheme(windowContext).base
            val themed =
                if (base != null) ContextThemeWrapper(windowContext, base.resId) else windowContext
            return ThemeEngine.attach(themed)
        }
    }
}
