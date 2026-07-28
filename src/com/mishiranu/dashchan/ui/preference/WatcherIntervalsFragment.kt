package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory

/**
 * The boards that the favorites watcher refreshes at their own pace instead of at
 * [Preferences.watcherRefreshInterval], edited as a list of rules the way [AutohideFragment] and
 * [CommandsFragment] edit theirs. The narrowest rule matching a thread's board decides its interval,
 * so the order of this list only settles ties between equally narrow rules.
 */
class WatcherIntervalsFragment : BaseListFragment() {
    private val items = ArrayList<Preferences.WatcherRefreshOverride>()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.board_intervals), null)
        items.addAll(Preferences.watcherRefreshOverrides)
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_intervals_defined))
        }
        getRecyclerView()!!.adapter = Adapter()
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_interval, 0, R.string.new_interval)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_new_interval) {
            editInterval(null, -1)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun editInterval(
        override: Preferences.WatcherRefreshOverride?,
        index: Int,
    ) {
        WatcherIntervalDialog(override, index).show(childFragmentManager, WatcherIntervalDialog::class.java.name)
    }

    internal fun onEditComplete(
        override: Preferences.WatcherRefreshOverride,
        index: Int,
    ) {
        if (index == -1) {
            items.add(override)
            setErrorText(null)
        } else if (index >= 0) {
            items[index] = override
        }
        Preferences.setWatcherRefreshOverrides(items)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
    }

    internal fun onDelete(index: Int) {
        items.removeAt(index)
        Preferences.setWatcherRefreshOverrides(items)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_intervals_defined))
        }
    }

    private inner class Adapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ListViewUtils.ClickCallback<Unit, RecyclerView.ViewHolder> {
        override fun getItemCount(): Int = items.size

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            editInterval(items[position], position)
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                SimpleViewHolder(ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_SINGLE_LINE).view),
                false,
                null,
                this,
            )

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            val viewHolder = holder.itemView.tag as ViewFactory.TwoLinesViewHolder
            val override = items[position]
            viewHolder.text1.text = describeScope(override)
            viewHolder.text2.text = describeInterval(requireContext(), override.interval)
        }

        private fun describeScope(override: Preferences.WatcherRefreshOverride): CharSequence {
            val context = requireContext()
            val builder = StringBuilder(describeChans(context, override.chanNames))
            val boardName = override.boardName
            if (!boardName.isNullOrEmpty()) {
                builder.append(" & [").append(boardName).append(']')
            } else {
                builder.append(" & ").append(context.getString(R.string.all_boards))
            }
            return builder
        }
    }

    class WatcherIntervalDialog :
        DialogFragment,
        ChanMultiChoiceDialog.Callback {
        private val selectedChanNames = HashSet<String>()

        private lateinit var scrollView: ScrollView
        private lateinit var chanNameSelector: TextView
        private lateinit var boardNameEdit: EditText
        private lateinit var intervalHolder: ViewFactory.SeekLayoutHolder

        constructor()

        /** A new rule has no override yet, and then the bundle holds nothing but the index. */
        constructor(override: Preferences.WatcherRefreshOverride?, index: Int) {
            val args = Bundle()
            if (override != null) {
                putOverride(args, override)
            }
            args.putInt(EXTRA_INDEX, index)
            arguments = args
        }

        @SuppressLint("InflateParams")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_watcher_interval, null) as ScrollView
            scrollView = view
            chanNameSelector = view.findViewById(R.id.chan_name)
            boardNameEdit = view.findViewById(R.id.board_name)
            chanNameSelector.setOnClickListener { ChanMultiChoiceDialog(selectedChanNames).show(this) }
            chanNameSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM
            if (!ChanManager.getInstance().hasMultipleAvailableChans()) {
                chanNameSelector.visibility = View.GONE
            }
            // The switch off is what "Disabled" means here too, which is why the value stored for it
            // is outside the slider's range (see SeekPreference).
            val intervalHolder =
                ViewFactory.createSeekLayout(
                    context,
                    true,
                    Preferences.MIN_WATCHER_REFRESH_INTERVAL,
                    Preferences.MAX_WATCHER_REFRESH_INTERVAL,
                    Preferences.STEP_WATCHER_REFRESH_INTERVAL,
                    getString(R.string.every_number_sec__format),
                )
            this.intervalHolder = intervalHolder
            view.findViewById<FrameLayout>(R.id.interval_container).addView(
                intervalHolder.layout,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            val source = savedInstanceState ?: requireArguments()
            val chanNames = source.getStringArrayList(EXTRA_CHAN_NAMES)
            if (chanNames != null) {
                selectedChanNames.addAll(chanNames)
            }
            boardNameEdit.setText(source.getString(EXTRA_BOARD_NAME))
            val interval = source.getInt(EXTRA_INTERVAL, Preferences.DEFAULT_WATCHER_REFRESH_INTERVAL)
            val enabled = interval != Preferences.DISABLED_WATCHER_REFRESH_INTERVAL
            intervalHolder.isEnabled = enabled
            intervalHolder.value = if (enabled) interval else Preferences.DEFAULT_WATCHER_REFRESH_INTERVAL
            updateSelectedText()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            putOverride(outState, readDialogView())
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val index = requireArguments().getInt(EXTRA_INDEX)
            val builder =
                AlertDialog
                    .Builder(requireContext())
                    .setView(scrollView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        (parentFragment as WatcherIntervalsFragment).onEditComplete(readDialogView(), index)
                    }
            if (index >= 0) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    (parentFragment as WatcherIntervalsFragment).onDelete(index)
                }
            }
            val dialog = builder.create()
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            return dialog
        }

        private fun updateSelectedText() {
            chanNameSelector.text = describeChans(requireContext(), selectedChanNames)
        }

        override fun onChansSelected(chanNames: Collection<String>) {
            selectedChanNames.clear()
            selectedChanNames.addAll(chanNames)
            updateSelectedText()
        }

        private fun readDialogView(): Preferences.WatcherRefreshOverride =
            Preferences.WatcherRefreshOverride(
                if (selectedChanNames.isNotEmpty()) HashSet(selectedChanNames) else null,
                boardNameEdit.text.toString(),
                if (intervalHolder.isEnabled) {
                    intervalHolder.value
                } else {
                    Preferences.DISABLED_WATCHER_REFRESH_INTERVAL
                },
            )

        companion object {
            private const val EXTRA_CHAN_NAMES = "chanNames"
            private const val EXTRA_BOARD_NAME = "boardName"
            private const val EXTRA_INTERVAL = "interval"
            private const val EXTRA_INDEX = "index"

            private fun putOverride(
                bundle: Bundle,
                override: Preferences.WatcherRefreshOverride,
            ) {
                override.chanNames?.let { bundle.putStringArrayList(EXTRA_CHAN_NAMES, ArrayList(it)) }
                bundle.putString(EXTRA_BOARD_NAME, override.boardName)
                bundle.putInt(EXTRA_INTERVAL, override.interval)
            }
        }
    }
}

/**
 * The scope's forums, worded the way the autohide and command rules word theirs, so that a rule
 * reads the same wherever the app shows one.
 */
private fun describeChans(
    context: Context,
    chanNames: Set<String>?,
): CharSequence =
    when {
        chanNames.isNullOrEmpty() -> {
            context.getString(R.string.all_forums)
        }

        chanNames.size > 1 -> {
            context.getString(R.string.multiple_forums)
        }

        else -> {
            val chanName = chanNames.iterator().next()
            val chan = Chan.get(chanName)
            val title = if (chan.name != null) chan.configuration.getTitle() else chanName
            context.getString(R.string.forum_only__format, title)
        }
    }

private fun describeInterval(
    context: Context,
    interval: Int,
): CharSequence =
    if (interval == Preferences.DISABLED_WATCHER_REFRESH_INTERVAL) {
        context.getString(R.string.disabled)
    } else {
        context.getString(R.string.every_number_sec__format, interval)
    }
