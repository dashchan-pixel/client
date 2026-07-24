package com.mishiranu.dashchan.ui

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import chan.content.Chan
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
        val uri = BundleCompat.getParcelable(requireArguments(), EXTRA_URI, Uri::class.java)
        val locator = Chan.getFallback().locator
        val imageUriString =
            Chan
                .get(chanName)
                .locator
                .convert(uri)
                .toString()
        // Plain context: Theme_Gallery is dark whatever the user theme is, and being fullscreen
        // rather than floating it also skips the theme engine's rounded window background.
        return DialogMenu(context)
            .add("Google") {
                searchImageUri(
                    locator.buildQueryWithHost(
                        "lens.google.com",
                        "uploadbyurl",
                        "url",
                        imageUriString,
                    ),
                )
            }.add("Yandex") {
                searchImageUri(
                    locator.buildQueryWithHost(
                        "www.yandex.ru",
                        "images/search",
                        "rpt",
                        "imageview",
                        "url",
                        imageUriString,
                    ),
                )
            }.create()
    }

    private fun searchImageUri(searchUri: Uri?) {
        NavigationUtils.handleUri(requireContext(), null, searchUri!!, NavigationUtils.BrowserType.EXTERNAL)
    }

    companion object {
        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_URI = "uri"
    }
}
