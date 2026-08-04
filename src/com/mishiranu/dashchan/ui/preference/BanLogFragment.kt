package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.SummaryLayout
import com.mishiranu.dashchan.widget.ViewFactory

/**
 * The bans of one forum, as [com.mishiranu.dashchan.content.BanLog] caught them while posting.
 * Nothing expires a row by itself: a ban stays in the log until it is deleted here, and its status
 * is computed against the current time every time the list is bound.
 */
class BanLogFragment :
    BaseListFragment,
    FragmentHandler.Callback {
    constructor()

    constructor(chanName: String) {
        val args = Bundle()
        args.putString(EXTRA_CHAN_NAME, chanName)
        arguments = args
    }

    private fun getChanName(): String = requireArguments().getString(EXTRA_CHAN_NAME)!!

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val chan = Chan.get(getChanName())
        (requireActivity() as FragmentHandler)
            .setTitleSubtitle(getString(R.string.ban_log), chan.configuration.getTitle())
        getRecyclerView()!!.adapter = Adapter(requireContext(), chan, this::onBanClick)
        updateCursor()
    }

    override fun onDestroyView() {
        (getRecyclerView()!!.adapter as Adapter).setCursor(null)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()

        if (!ChanManager.getInstance().isExistingChanName(getChanName())) {
            (requireActivity() as FragmentHandler).removeFragment()
        } else {
            // The address a ban was caught with is resolved in the background: pick it up when shown
            updateCursor()
        }
    }

    override fun onChansChanged(
        changed: Collection<String>,
        removed: Collection<String>,
    ) {
        if (removed.contains(getChanName())) {
            (requireActivity() as FragmentHandler).removeFragment()
        }
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
        menu.findItem(R.id.menu_clear)?.isVisible = (getRecyclerView()?.adapter?.itemCount ?: 0) > 0
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_clear) {
            ClearDialog().show(childFragmentManager, ClearDialog::class.java.name)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun onBanClick(
        banItem: ChanDatabase.BanItem,
        longClick: Boolean,
    ) {
        if (longClick) {
            val summary = formatSummary(requireContext(), Chan.get(getChanName()), banItem)
            DialogMenu(requireContext())
                .add(R.string.copy_text) {
                    StringUtils.copyToClipboard(requireContext(), summary)
                    ClickableToast.show(R.string.copied_to_clipboard)
                }.add(R.string.delete) { deleteBan(banItem.rowId) }
                .create()
                .show()
        } else {
            DetailsDialog(getChanName(), banItem).show(childFragmentManager, DetailsDialog::class.java.name)
        }
    }

    private fun deleteBan(rowId: Long) {
        ChanDatabase.getInstance().deleteBan(rowId)
        updateCursor()
    }

    internal fun deleteAllBans() {
        ChanDatabase.getInstance().deleteBans(getChanName())
        updateCursor()
    }

    private fun updateCursor() {
        val adapter = getRecyclerView()!!.adapter as Adapter
        adapter.setCursor(ChanDatabase.getInstance().getBans(getChanName()))
        setErrorText(if (adapter.itemCount > 0) null else getString(R.string.no_bans_recorded))
        invalidateOptionsMenu()
    }

    private class Adapter(
        context: Context,
        private val chan: Chan,
        private val callback: Callback,
    ) : CursorAdapter<ChanDatabase.BanCursor, Adapter.ViewHolder>(),
        ListViewUtils.ClickCallback<Unit, Adapter.ViewHolder> {
        fun interface Callback {
            fun onBanClick(
                banItem: ChanDatabase.BanItem,
                longClick: Boolean,
            )
        }

        private class ViewHolder(
            val twoLines: ViewFactory.TwoLinesViewHolder,
        ) : RecyclerView.ViewHolder(twoLines.view)

        private val resources = context.resources
        private val formatter = PostDateFormatter(context)
        private val banItem = ChanDatabase.BanItem()

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder = ListViewUtils.bind(ViewHolder(ViewFactory.makeTwoLinesListItem(parent, 0)), true, null, this)

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val banItem = this.banItem.update(moveTo(position))
            val expired = getStatus(banItem) == Status.EXPIRED
            holder.twoLines.text1.text = formatTitle(resources, formatter, chan, banItem)
            holder.twoLines.text2.text = formatSubtitle(resources, formatter, banItem)
            // An expired ban is kept for the record only, so it reads as inactive
            holder.twoLines.text1.isEnabled = !expired
            holder.twoLines.text2.isEnabled = !expired
        }

        override fun onItemClick(
            holder: ViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            callback.onBanClick(banItem.update(moveTo(position)).copy(), longClick)
            return true
        }
    }

    class DetailsDialog : DialogFragment {
        constructor()

        constructor(chanName: String, banItem: ChanDatabase.BanItem) {
            val args = Bundle()
            args.putString(EXTRA_CHAN_NAME, chanName)
            args.putString(EXTRA_BOARD_NAME, banItem.boardName)
            args.putString(EXTRA_BAN_ID, banItem.banId)
            args.putString(EXTRA_MESSAGE, banItem.message)
            args.putLong(EXTRA_START_DATE, banItem.startDate)
            args.putLong(EXTRA_EXPIRE_DATE, banItem.expireDate)
            args.putString(EXTRA_ADDRESS, banItem.address)
            args.putLong(EXTRA_CREATED, banItem.created)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val args = requireArguments()
            val banItem = ChanDatabase.BanItem()
            banItem.boardName = args.getString(EXTRA_BOARD_NAME)
            banItem.banId = args.getString(EXTRA_BAN_ID)
            banItem.message = args.getString(EXTRA_MESSAGE)
            banItem.startDate = args.getLong(EXTRA_START_DATE)
            banItem.expireDate = args.getLong(EXTRA_EXPIRE_DATE)
            banItem.address = args.getString(EXTRA_ADDRESS)
            banItem.created = args.getLong(EXTRA_CREATED)
            val dialog =
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.details)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            val layout = SummaryLayout(dialog)
            val context = requireContext()
            val chan = Chan.get(args.getString(EXTRA_CHAN_NAME))
            val formatter = PostDateFormatter(context)
            val banId = banItem.banId
            if (!banId.isNullOrEmpty()) {
                layout.add(getString(R.string.ban_id), banId)
            }
            val boardTitle = formatBoardTitle(chan, banItem.boardName)
            if (boardTitle != null) {
                layout.add(getString(R.string.board), boardTitle)
            }
            if (banItem.startDate > 0L) {
                layout.add(getString(R.string.filed_on), formatter.formatDateTime(banItem.startDate))
            }
            layout.add(getString(R.string.expires), formatExpires(context, formatter, banItem))
            layout.add(getString(R.string.recorded_on), formatter.formatDateTime(banItem.created))
            val address = banItem.address
            layout.add(
                getString(R.string.visible_ip),
                if (address.isNullOrEmpty()) getString(R.string.unavailable) else address,
            )
            val message = banItem.message
            if (!message.isNullOrEmpty()) {
                layout.add(getString(R.string.reason), message)
            }
            return dialog
        }

        companion object {
            private const val EXTRA_BOARD_NAME = "boardName"
            private const val EXTRA_BAN_ID = "banId"
            private const val EXTRA_MESSAGE = "message"
            private const val EXTRA_START_DATE = "startDate"
            private const val EXTRA_EXPIRE_DATE = "expireDate"
            private const val EXTRA_ADDRESS = "address"
            private const val EXTRA_CREATED = "created"
        }
    }

    class ClearDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
            AlertDialog
                .Builder(requireContext())
                .setMessage(R.string.delete_all_bans__sentence)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    (parentFragment as BanLogFragment).deleteAllBans()
                }.create()
    }

    private enum class Status { ACTIVE, PERMANENT, EXPIRED }

    companion object {
        private const val EXTRA_CHAN_NAME = "chanName"

        /**
         * A ban the forum gave no expiration date for is never known to be over, so it stays
         * [Status.ACTIVE] until it is deleted by hand.
         */
        private fun getStatus(banItem: ChanDatabase.BanItem): Status =
            when {
                banItem.expireDate == Long.MAX_VALUE -> Status.PERMANENT
                banItem.expireDate <= 0L -> Status.ACTIVE
                banItem.expireDate > System.currentTimeMillis() -> Status.ACTIVE
                else -> Status.EXPIRED
            }

        private fun formatBoardTitle(
            chan: Chan,
            boardName: String?,
        ): String? =
            if (boardName.isNullOrEmpty()) {
                null
            } else {
                StringUtils.formatBoardTitle(
                    chan.name.orEmpty(),
                    boardName,
                    chan.configuration.getBoardTitle(boardName),
                )
            }

        private fun formatExpires(
            context: Context,
            formatter: PostDateFormatter,
            banItem: ChanDatabase.BanItem,
        ): String =
            when {
                banItem.expireDate == Long.MAX_VALUE -> context.getString(R.string.never)
                banItem.expireDate <= 0L -> context.getString(R.string.unknown)
                else -> formatter.formatDateTime(banItem.expireDate)
            }

        /** The board it was caught on, if any, and how much of the ban is left. */
        private fun formatTitle(
            resources: Resources,
            formatter: PostDateFormatter,
            chan: Chan,
            banItem: ChanDatabase.BanItem,
        ): String {
            val status =
                when (getStatus(banItem)) {
                    Status.PERMANENT -> {
                        resources.getString(R.string.ban_permanent)
                    }

                    Status.EXPIRED -> {
                        resources.getString(R.string.ban_expired)
                    }

                    Status.ACTIVE -> {
                        if (banItem.expireDate > 0L) {
                            ResourceUtils.getColonString(
                                resources,
                                R.string.expires,
                                formatter.formatDateTime(banItem.expireDate),
                            )
                        } else {
                            resources.getString(R.string.ban_active)
                        }
                    }
                }
            val boardTitle = formatBoardTitle(chan, banItem.boardName)
            return if (boardTitle != null) {
                resources.getString(R.string.__enumeration_format, boardTitle, status)
            } else {
                status
            }
        }

        /** The reason the forum gave, falling back to when the ban was caught. */
        private fun formatSubtitle(
            resources: Resources,
            formatter: PostDateFormatter,
            banItem: ChanDatabase.BanItem,
        ): String {
            val message = banItem.message
            return if (!message.isNullOrEmpty()) {
                message
            } else {
                ResourceUtils.getColonString(
                    resources,
                    R.string.recorded_on,
                    formatter.formatDateTime(banItem.created),
                )
            }
        }

        internal fun formatSummary(
            context: Context,
            chan: Chan,
            banItem: ChanDatabase.BanItem,
        ): String {
            val resources = context.resources
            val formatter = PostDateFormatter(context)
            val builder = StringBuilder()
            val banId = banItem.banId
            if (!banId.isNullOrEmpty()) {
                builder.append(ResourceUtils.getColonString(resources, R.string.ban_id, banId)).append('\n')
            }
            val boardTitle = formatBoardTitle(chan, banItem.boardName)
            if (boardTitle != null) {
                builder.append(ResourceUtils.getColonString(resources, R.string.board, boardTitle)).append('\n')
            }
            if (banItem.startDate > 0L) {
                builder
                    .append(
                        ResourceUtils.getColonString(
                            resources,
                            R.string.filed_on,
                            formatter.formatDateTime(banItem.startDate),
                        ),
                    ).append('\n')
            }
            builder
                .append(
                    ResourceUtils.getColonString(
                        resources,
                        R.string.expires,
                        formatExpires(context, formatter, banItem),
                    ),
                ).append('\n')
            builder
                .append(
                    ResourceUtils.getColonString(
                        resources,
                        R.string.recorded_on,
                        formatter.formatDateTime(banItem.created),
                    ),
                ).append('\n')
            val address = banItem.address
            if (!address.isNullOrEmpty()) {
                builder
                    .append(ResourceUtils.getColonString(resources, R.string.visible_ip, address))
                    .append('\n')
            }
            val message = banItem.message
            if (!message.isNullOrEmpty()) {
                builder.append(ResourceUtils.getColonString(resources, R.string.reason, message)).append('\n')
            }
            return builder.toString().trim()
        }
    }
}
