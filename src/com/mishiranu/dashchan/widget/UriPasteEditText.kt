package com.mishiranu.dashchan.widget

import android.annotation.TargetApi
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.ContentInfo
import androidx.core.content.MimeTypeFilter
import androidx.core.view.ContentInfoCompat
import com.mishiranu.dashchan.R

class UriPasteEditText : SafePasteEditText {
	fun interface Callback {
		fun onUriWithAllowedMimeTypePasted(uri: Uri)
	}

	constructor(context: Context) : super(context)

	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
			super(context, attrs, defStyleAttr, defStyleRes)

	fun setCallback(callback: Callback, allowedUriMimeTypes: List<String>) {
		val mimeTypes = allowedUriMimeTypes.toTypedArray()
		setOnReceiveContentListener(mimeTypes) { _, payload ->
			val partition = ContentInfoCompat.partition(payload) { item ->
				item.uri != null && uriMimeTypeAllowed(item.uri, mimeTypes)
			}
			val accepted = partition.first
			if (accepted != null) {
				val clip = accepted.clip
				for (i in 0 until clip.itemCount) {
					callback.onUriWithAllowedMimeTypePasted(clip.getItemAt(i).uri)
				}
				if (accepted.source == ContentInfo.SOURCE_INPUT_METHOD) {
					ClickableToast.show(R.string.pasted)
				}
			}
			partition.second
		}
	}

	private fun uriMimeTypeAllowed(uri: Uri, allowedMimeTypes: Array<String>): Boolean {
		val uriMimeType = context.contentResolver.getType(uri)
		return MimeTypeFilter.matches(uriMimeType, allowedMimeTypes) != null
	}
}
