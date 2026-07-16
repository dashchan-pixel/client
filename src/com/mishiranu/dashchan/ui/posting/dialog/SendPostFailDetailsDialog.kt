package com.mishiranu.dashchan.ui.posting.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import chan.content.ApiException
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.SummaryLayout

class SendPostFailDetailsDialog() : DialogFragment() {
    constructor(extra: ApiException.Extra?) : this() {
        val args = Bundle()
        args.putParcelable(EXTRA_EXTRA, extra)
        arguments = args
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val extra = BundleCompat.getParcelable(requireArguments(), EXTRA_EXTRA, ApiException.Extra::class.java)
        val dialog =
            AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.details)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        if (extra is ApiException.BanExtra) {
            val layout = SummaryLayout(dialog)
            val formatter = PostDateFormatter(requireContext())
            val id = extra.id
            if (!id.isNullOrEmpty()) {
                layout.add(getString(R.string.ban_id), id)
            }
            if (extra.startDate > 0L) {
                layout.add(getString(R.string.filed_on), formatter.formatDateTime(extra.startDate))
            }
            if (extra.expireDate > 0L) {
                layout.add(
                    getString(R.string.expires),
                    if (extra.expireDate == Long.MAX_VALUE) {
                        getString(R.string.never)
                    } else {
                        formatter.formatDateTime(extra.expireDate)
                    },
                )
            }
            val message = extra.message
            if (!message.isNullOrEmpty()) {
                layout.add(getString(R.string.reason), message)
            }
        } else if (extra is ApiException.WordsExtra) {
            var message = ""
            var first = true
            for (word in extra.words) {
                if (first) {
                    first = false
                    message = word!!
                } else {
                    message = getString(R.string.__enumeration_format, message, word)
                }
            }
            dialog.setMessage(ResourceUtils.getColonString(resources, R.string.rejected_words, message))
        }
        return dialog
    }

    companion object {
        @JvmField val TAG: String = SendPostFailDetailsDialog::class.java.name

        private const val EXTRA_EXTRA = "extra"
    }
}
