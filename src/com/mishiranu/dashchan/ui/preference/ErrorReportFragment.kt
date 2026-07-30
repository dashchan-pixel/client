package com.mishiranu.dashchan.ui.preference

import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.Logger
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import java.io.File

/** A single crash report from [ErrorReportsFragment], shown as selectable monospaced text. */
class ErrorReportFragment : BaseListFragment {
    private var text: String? = null

    constructor()

    constructor(fileName: String) {
        arguments = Bundle().apply { putString(EXTRA_FILE_NAME, fileName) }
    }

    private fun getFile(): File? {
        val directory = Logger.getErrorsDirectory(requireContext()) ?: return null
        return File(directory, requireArguments().getString(EXTRA_FILE_NAME)!!)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val file = getFile()
        val text = if (file != null) ErrorReportsFragment.readReport(file) else null
        this.text = text
        (requireActivity() as FragmentHandler).setTitleSubtitle(
            getString(R.string.error_report),
            if (file != null) {
                PostDateFormatter(requireContext()).formatDateTime(ErrorReportsFragment.getTime(file))
            } else {
                null
            },
        )
        getRecyclerView()!!.adapter = Adapter()
        if (text == null) {
            setErrorText(getString(R.string.unknown_error))
        }
    }

    override fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(false)

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_delete, 0, R.string.delete)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionDelete))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, R.id.menu_share, 0, R.string.share)
        menu.add(0, R.id.menu_copy, 0, R.string.copy_text)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_delete -> {
                getFile()?.delete()
                (requireActivity() as FragmentHandler).removeFragment()
                return true
            }

            R.id.menu_share -> {
                val text = this.text
                if (text != null) {
                    NavigationUtils.shareText(requireContext(), getString(R.string.error_report), text, null)
                } else {
                    ClickableToast.show(R.string.unknown_error)
                }
                return true
            }

            R.id.menu_copy -> {
                val text = this.text
                if (text != null) {
                    StringUtils.copyToClipboard(requireContext(), text)
                    ClickableToast.show(R.string.copied_to_clipboard)
                } else {
                    ClickableToast.show(R.string.unknown_error)
                }
                return true
            }
        }
        return super.onMenuItemSelected(item)
    }

    private inner class Adapter : RecyclerView.Adapter<TextViewHolder>() {
        override fun getItemCount(): Int = if (text != null) 1 else 0

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): TextViewHolder {
            val textView = TextView(parent.context)
            val density = ResourceUtils.obtainDensity(parent)
            val padding = (16f * density + 0.5f).toInt()
            textView.setPadding(padding, padding, padding, padding)
            textView.typeface = Typeface.MONOSPACE
            ViewUtils.setTextSizeScaled(textView, 12)
            // A stack trace is worth nothing if it can't leave the phone: allow selection so a part
            // of it can be copied out, next to the whole-report actions in the menu.
            textView.setTextIsSelectable(true)
            textView.layoutParams =
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            return TextViewHolder(textView)
        }

        override fun onBindViewHolder(
            holder: TextViewHolder,
            position: Int,
        ) {
            holder.textView.text = text
        }
    }

    private class TextViewHolder(
        val textView: TextView,
    ) : RecyclerView.ViewHolder(textView)

    companion object {
        private const val EXTRA_FILE_NAME = "fileName"
    }
}
