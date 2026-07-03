package com.mishiranu.dashchan.ui.posting.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback

class AttachmentWarningDialog() : DialogFragment() {
	constructor(attachmentIndex: Int) : this() {
		val args = Bundle()
		args.putInt(EXTRA_ATTACHMENT_INDEX, attachmentIndex)
		arguments = args
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val activity = requireActivity()
		val holder = (parentFragment as PostingDialogCallback)
				.getAttachmentHolder(requireArguments().getInt(EXTRA_ATTACHMENT_INDEX))
		val fileHolder = if (holder != null) DraftsStorage.getInstance()
				.getAttachmentDraftFileHolder(holder.hash) else null
		if (holder == null || fileHolder == null) {
			dismiss()
			return Dialog(activity)
		}
		val jpegData = fileHolder.jpegData
		val pngData = fileHolder.pngData
		val hasMetadata = pngData != null && pngData.hasMetadata
		val exifData = jpegData?.exifData
		val rotation = fileHolder.imageRotation
		val geolocation = exifData?.getGeolocation(false)
		var message = ""
		if (hasMetadata) {
			message = appendMessage(message, getString(R.string.metadata))
		}
		if (exifData != null) {
			message = appendMessage(message, getString(R.string.exif_metadata))
		}
		if (rotation != 0) {
			message = appendMessage(message, getString(R.string.orientation))
		}
		if (geolocation != null) {
			message = appendMessage(message, getString(R.string.geolocation))
		}
		return AlertDialog.Builder(activity).setTitle(R.string.warning)
				.setMessage(getString(R.string.file_contains_data__format_sentence, message))
				.setPositiveButton(android.R.string.ok, null).create()
	}

	private fun appendMessage(message: String, append: String): String {
		return if (message.isEmpty()) {
			append
		} else {
			getString(R.string.__enumeration_format, message, append)
		}
	}

	companion object {
		@JvmField val TAG: String = AttachmentWarningDialog::class.java.name

		private const val EXTRA_ATTACHMENT_INDEX = "attachmentIndex"
	}
}
