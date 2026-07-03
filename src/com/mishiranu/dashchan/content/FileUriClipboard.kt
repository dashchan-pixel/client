package com.mishiranu.dashchan.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.File

object FileUriClipboard {
	@JvmStatic
	fun copyFileUriToClipboard(file: File, originalFileName: String) {
		val fileUri = CacheManager.getInstance().prepareFileForClipboard(file, originalFileName)
		if (fileUri == null) {
			ClickableToast.show(R.string.cache_is_unavailable)
			return
		}
		val context = MainApplication.getInstance()
		val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		val clip = ClipData.newUri(context.contentResolver, originalFileName, fileUri)
		clipboard.setPrimaryClip(clip)
	}
}
