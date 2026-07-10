package com.mishiranu.dashchan.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.FrameLayout
import chan.content.ChanLocator
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.util.ResourceUtils

interface FragmentHandler {
	interface Callback {
		fun onChansChanged(changed: Collection<String>, removed: Collection<String>) {}
		fun onStorageRequestResult() {}
	}

	fun setTitleSubtitle(title: CharSequence?, subtitle: CharSequence?)
	fun getToolbarView(): ViewGroup
	fun getToolbarExtra(): FrameLayout
	fun getToolbarContext(): Context

	fun getActionBarIcon(attr: Int): Drawable? {
		return ResourceUtils.getActionBarIcon(getToolbarContext(), attr)
	}

	fun pushFragment(fragment: ContentFragment)
	fun removeFragment()

	// Re-evaluates whether the system back gesture should be intercepted (predictive back:
	// the back-to-home animation only plays when nothing in the app claims the gesture).
	fun updateBackHandling() {}

	fun getDownloadBinder(): DownloadService.Binder?
	fun requestStorage(): Boolean

	fun navigateTargetAllowReturn(chanName: String?, navigationData: ChanLocator.NavigationData)
	// navigateIfNeeded: when the target thread is not the current page (e.g. a gallery reopened
	// from picture-in-picture after browsing away), open that thread instead of doing nothing.
	// Pass false for implicit calls (scroll thread along with gallery) which must never navigate.
	fun scrollToPost(chanName: String?, boardName: String?, threadNumber: String?, postNumber: PostNumber?,
			navigateIfNeeded: Boolean)
	fun obtainDrawerPages(): Collection<DrawerForm.Page>

	fun setActionBarLocked(locker: String, locked: Boolean)
	fun setNavigationAreaLocked(locker: String, locked: Boolean)
}
