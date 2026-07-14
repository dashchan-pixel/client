package com.mishiranu.dashchan.ui.posting.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.ui.posting.AttachmentHolder
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback

class AttachmentRatingDialog() :
    DialogFragment(),
    DialogInterface.OnClickListener {
    constructor(attachmentIndex: Int) : this() {
        val args = Bundle()
        args.putInt(EXTRA_ATTACHMENT_INDEX, attachmentIndex)
        arguments = args
    }

    private lateinit var ratings: Array<String>

    private fun getAttachmentHolder(): AttachmentHolder? =
        (parentFragment as PostingDialogCallback)
            .getAttachmentHolder(requireArguments().getInt(EXTRA_ATTACHMENT_INDEX))

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val holder = getAttachmentHolder()
        val attachmentRatingItems = (parentFragment as PostingDialogCallback).getAttachmentRatingItems()
        if (holder == null || attachmentRatingItems == null) {
            dismiss()
            return Dialog(activity)
        }
        val items = Array(attachmentRatingItems.size) { attachmentRatingItems[it].second }
        ratings = Array(attachmentRatingItems.size) { attachmentRatingItems[it].first }
        var checkedItem = 0
        for (i in items.indices) {
            if (ratings[i] == holder.rating) {
                checkedItem = i
            }
        }
        return AlertDialog
            .Builder(activity)
            .setTitle(R.string.rating)
            .setSingleChoiceItems(
                items,
                checkedItem,
                this,
            ).setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onClick(
        dialog: DialogInterface,
        which: Int,
    ) {
        val holder = getAttachmentHolder()
        holder!!.rating = ratings[which]
        dismiss()
    }

    companion object {
        @JvmField val TAG: String = AttachmentRatingDialog::class.java.name

        private const val EXTRA_ATTACHMENT_INDEX = "attachmentIndex"
    }
}
