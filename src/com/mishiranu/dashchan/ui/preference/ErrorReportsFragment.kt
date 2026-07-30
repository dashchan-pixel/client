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
import com.mishiranu.dashchan.content.ErrorReports
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ViewFactory
import java.io.File

/**
 * Lists the crash reports of [ErrorReports]. Nothing else ever reads them, so this is also the
 * only way to get rid of them from inside the app.
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
        val text = ErrorReports.readReport(reportItem.file)
        if (text != null) {
            NavigationUtils.shareText(requireContext(), getString(R.string.error_report), text, null)
        } else {
            ClickableToast.show(R.string.unknown_error)
        }
    }

    private fun copyReport(reportItem: ReportItem) {
        val text = ErrorReports.readReport(reportItem.file)
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
        private fun collectItems(context: Context): List<ReportItem> {
            val dateFormatter = PostDateFormatter(context)
            return ErrorReports.getFiles(context).map { file ->
                ReportItem(
                    file,
                    dateFormatter.formatDateTime(ErrorReports.getTime(file)),
                    ErrorReports.readSummary(file) ?: StringUtils.formatFileSize(file.length(), false),
                )
            }
        }
    }
}
