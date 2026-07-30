package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.Logger
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ViewFactory
import java.io.File
import java.io.IOException

/**
 * Lists the crash reports the uncaught exception handler leaves in [Logger]'s errors directory,
 * one "error-<millis>.txt" per crash. Nothing else ever reads them, so this is also the only way
 * to get rid of them from inside the app.
 */
class ErrorReportsFragment : BaseListFragment() {
    private val items = ArrayList<ReportItem>()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.error_reports), null)
        getRecyclerView()!!.adapter = Adapter()
    }

    override fun onResume() {
        super.onResume()

        // Reload on every resume: a report may have been deleted in the viewer fragment.
        loadItems()
    }

    private fun loadItems() {
        val context = requireContext()
        items.clear()
        items.addAll(collectItems(context))
        setErrorText(if (items.isEmpty()) getString(R.string.no_error_reports) else null)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_clear, 0, R.string.clear)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionDelete))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu.findItem(R.id.menu_clear)?.isVisible = items.isNotEmpty()
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_clear) {
            ClearDialog().show(childFragmentManager, ClearDialog::class.java.name)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun showContextMenu(reportItem: ReportItem) {
        DialogMenu(requireContext())
            .setTitle(reportItem.title)
            .add(R.string.share) { shareReport(reportItem) }
            .add(R.string.copy_text) { copyReport(reportItem) }
            .add(R.string.delete) { deleteReport(reportItem) }
            .create()
            .show()
    }

    private fun shareReport(reportItem: ReportItem) {
        val text = readReport(reportItem.file)
        if (text != null) {
            NavigationUtils.shareText(requireContext(), getString(R.string.error_report), text, null)
        } else {
            ClickableToast.show(R.string.unknown_error)
        }
    }

    private fun copyReport(reportItem: ReportItem) {
        val text = readReport(reportItem.file)
        if (text != null) {
            StringUtils.copyToClipboard(requireContext(), text)
            ClickableToast.show(R.string.copied_to_clipboard)
        } else {
            ClickableToast.show(R.string.unknown_error)
        }
    }

    private fun deleteReport(reportItem: ReportItem) {
        reportItem.file.delete()
        loadItems()
    }

    internal fun deleteAllReports() {
        for (reportItem in items) {
            reportItem.file.delete()
        }
        loadItems()
    }

    private inner class Adapter :
        RecyclerView.Adapter<ReportViewHolder>(),
        ListViewUtils.ClickCallback<Unit, ReportViewHolder> {
        override fun getItemCount(): Int = items.size

        override fun onItemClick(
            holder: ReportViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            val reportItem = items[position]
            if (longClick) {
                showContextMenu(reportItem)
            } else {
                (requireActivity() as FragmentHandler).pushFragment(ErrorReportFragment(reportItem.file.name))
            }
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ReportViewHolder =
            ListViewUtils.bind(
                ReportViewHolder(ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_SINGLE_LINE)),
                true,
                null,
                this,
            )

        override fun onBindViewHolder(
            holder: ReportViewHolder,
            position: Int,
        ) {
            val reportItem = items[position]
            holder.twoLines.text1.text = reportItem.title
            holder.twoLines.text2.text = reportItem.summary
        }
    }

    private class ReportViewHolder(
        val twoLines: ViewFactory.TwoLinesViewHolder,
    ) : RecyclerView.ViewHolder(twoLines.view)

    class ClearDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
            AlertDialog
                .Builder(requireContext())
                .setMessage(R.string.delete_all_error_reports__sentence)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    (parentFragment as ErrorReportsFragment).deleteAllReports()
                }.create()
    }

    class ReportItem(
        val file: File,
        val title: String,
        val summary: String,
    )

    companion object {
        private const val NAME_PREFIX = "error-"
        private const val NAME_SUFFIX = ".txt"

        // Reports are stack traces of a few kilobytes; the cap only guards against a file that
        // somehow grew out of hand, since the whole text goes into a single TextView.
        private const val MAX_REPORT_SIZE = 1024 * 1024

        /** Newest first: the report the user came here for is the one that just crashed the app. */
        private fun collectItems(context: Context): List<ReportItem> {
            val files = Logger.getErrorsDirectory(context)?.listFiles() ?: return emptyList()
            val dateFormatter = PostDateFormatter(context)
            return files
                .filter { it.isFile && it.name.startsWith(NAME_PREFIX) }
                .sortedByDescending { getTime(it) }
                .map { file ->
                    ReportItem(
                        file,
                        dateFormatter.formatDateTime(getTime(file)),
                        readSummary(file) ?: StringUtils.formatFileSize(file.length(), false),
                    )
                }
        }

        fun getTime(file: File): Long {
            val name = file.name
            if (name.startsWith(NAME_PREFIX) && name.endsWith(NAME_SUFFIX)) {
                val time =
                    name
                        .substring(NAME_PREFIX.length, name.length - NAME_SUFFIX.length)
                        .toLongOrNull()
                if (time != null) {
                    return time
                }
            }
            return file.lastModified()
        }

        /**
         * The first line of the stack trace, i.e. the exception that killed the app: the report
         * starts with the technical data block, which is the same for every report of a build.
         */
        private fun readSummary(file: File): String? {
            try {
                file.bufferedReader().use { reader ->
                    var afterDivider = false
                    var line = reader.readLine()
                    while (line != null) {
                        if (afterDivider) {
                            if (line.isNotBlank()) {
                                return line.trim()
                            }
                        } else if (line.length >= 3 && line.all { it == '-' }) {
                            afterDivider = true
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return null
        }

        fun readReport(file: File): String? =
            try {
                val bytes = file.inputStream().use { it.readNBytes(MAX_REPORT_SIZE) }
                String(bytes) + if (file.length() > MAX_REPORT_SIZE) "\n…" else ""
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
    }
}
