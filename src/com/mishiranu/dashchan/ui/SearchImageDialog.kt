package com.mishiranu.dashchan.ui

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.fragment.app.DialogFragment
import chan.content.Chan
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.NavigationUtils

class SearchImageDialog() : DialogFragment() {
	constructor(chanName: String?, uri: Uri?) : this() {
		val args = Bundle()
		args.putString(EXTRA_CHAN_NAME, chanName)
		args.putParcelable(EXTRA_URI, uri)
		arguments = args
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val context = requireContext()
		val chanName = requireArguments().getString(EXTRA_CHAN_NAME)
		val uri = requireArguments().getParcelable<Uri>(EXTRA_URI)
		val locator = Chan.getFallback().locator
		val imageUriString = Chan.get(chanName).locator.convert(uri).toString()
		return DialogMenu(ContextThemeWrapper(context, R.style.Theme_Gallery))
				.add("Google") { searchImageUri(locator.buildQueryWithHost("www.google.com",
						"searchbyimage", "sbisrc", "is", "safe", "off", "image_url", imageUriString)) }
				.add("Yandex") { searchImageUri(locator.buildQueryWithHost("www.yandex.ru",
						"images/search", "rpt", "imageview", "url", imageUriString)) }
				.add("TinEye") { searchImageUri(locator.buildQueryWithHost("www.tineye.com",
						"search", "url", imageUriString)) }
				.add("SauceNAO") { searchImageUri(locator.buildQueryWithHost("saucenao.com",
						"search.php", "url", imageUriString)) }
				.add("iqdb.org") { searchImageUri(locator.buildQueryWithHost("iqdb.org",
						"/", "url", imageUriString)) }
				.add("trace.moe") { searchImageUri(locator.buildQueryWithHost("trace.moe",
						"/", "url", imageUriString)) }
				.create()
	}

	private fun searchImageUri(searchUri: Uri) {
		NavigationUtils.handleUri(requireContext(), null, searchUri, NavigationUtils.BrowserType.EXTERNAL)
	}

	companion object {
		private const val EXTRA_CHAN_NAME = "chanName"
		private const val EXTRA_URI = "uri"
	}
}
