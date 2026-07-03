package com.mishiranu.dashchan.content

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.ui.MainActivity
import java.util.regex.Pattern

class PostingShareActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val draftsStorage = DraftsStorage.getInstance()
		var uris: ArrayList<Uri>? = null
		var contentUri: Uri? = null
		val intent = intent

		if (Intent.ACTION_SEND == intent.action) {
			val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
			if (uri != null) {
				uris = ArrayList(1)
				uris.add(uri)
			} else {
				val text = StringUtils.emptyIfNull(intent.getStringExtra(Intent.EXTRA_SUBJECT)) + '\n' +
						StringUtils.emptyIfNull(intent.getStringExtra(Intent.EXTRA_TEXT))
				val matcher = PATTERN_HREF.matcher(StringUtils.linkify(text))
				if (matcher.find()) {
					contentUri = Uri.parse(matcher.group(2))
				}
			}
		} else if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
			uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
		}

		var success = 0
		if (!uris.isNullOrEmpty()) {
			for (uri in uris) {
				val fileHolder = FileHolder.obtain(uri)
				if (fileHolder != null && draftsStorage.storeFuture(fileHolder)) {
					success++
				}
			}
		}

		if (success > 0) {
			Toast.makeText(this, R.string.draft_saved, Toast.LENGTH_SHORT).show()
		} else if (contentUri != null) {
			startActivity(Intent(this, MainActivity::class.java).setData(contentUri))
		} else {
			Toast.makeText(this, R.string.unknown_address, Toast.LENGTH_SHORT).show()
		}
		finish()
	}

	companion object {
		private val PATTERN_HREF = Pattern.compile("<a .*href=([\"'])(.*?)\\1.*>")
	}
}
