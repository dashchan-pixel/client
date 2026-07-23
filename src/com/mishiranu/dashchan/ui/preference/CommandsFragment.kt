package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory

class CommandsFragment : BaseListFragment() {
    private val items = ArrayList<CommandsStorage.CommandItem>()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.commands), null)
        items.addAll(CommandsStorage.getInstance().getItems())
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_commands_defined))
        }
        getRecyclerView()!!.adapter = Adapter()
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_command, 0, R.string.new_command)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_command -> {
                editCommand(null, -1)
                return true
            }
        }
        return super.onMenuItemSelected(item)
    }

    private fun editCommand(
        commandItem: CommandsStorage.CommandItem?,
        index: Int,
    ) {
        val dialog = CommandDialog(commandItem, index)
        dialog.show(childFragmentManager, CommandDialog::class.java.name)
    }

    internal fun onEditComplete(
        commandItem: CommandsStorage.CommandItem,
        index: Int,
    ) {
        val adapter = getRecyclerView()!!.adapter as Adapter
        if (index == -1) {
            CommandsStorage.getInstance().add(commandItem)
            items.add(commandItem)
            setErrorText(null)
        } else if (index >= 0) {
            CommandsStorage.getInstance().update(index, commandItem)
            items[index] = commandItem
        }
        adapter.notifyDataSetChanged()
    }

    internal fun onDelete(index: Int) {
        CommandsStorage.getInstance().delete(index)
        items.removeAt(index)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_commands_defined))
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
            editCommand(items[position], position)
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
            val commandItem = items[position]
            viewHolder.text1.text =
                if (StringUtils.isEmpty(commandItem.name)) getString(R.string.command) else commandItem.name
            viewHolder.text2.text = describeScope(commandItem)
        }

        private fun describeScope(commandItem: CommandsStorage.CommandItem): CharSequence {
            val builder = StringBuilder()
            val chanNames = commandItem.chanNames
            when {
                chanNames.isNullOrEmpty() -> builder.append(getString(R.string.all_forums))
                chanNames.size == 1 -> {
                    val chanName = chanNames.iterator().next()
                    val chan = Chan.get(chanName)
                    val title = if (chan.name != null) chan.configuration.getTitle() else chanName
                    builder.append(getString(R.string.forum_only__format, title))
                }
                else -> builder.append(getString(R.string.multiple_forums))
            }
            if (!StringUtils.isEmpty(commandItem.boardName)) {
                builder.append(" & [").append(commandItem.boardName).append(']')
            }
            builder.append(" · ").append(getString(R.string.comment))
            if (commandItem.runOnSend) {
                builder.append(" · ").append(getString(R.string.run_on_send))
            }
            return builder
        }
    }

    class CommandDialog :
        DialogFragment,
        ChanMultiChoiceDialog.Callback {
        private val selectedChanNames = HashSet<String>()

        private lateinit var scrollView: ScrollView
        private lateinit var chanNameSelector: TextView
        private lateinit var boardNameEdit: EditText
        private lateinit var nameEdit: EditText
        private lateinit var useInView: DropdownView
        private lateinit var runOnSendCheckBox: CheckBox
        private lateinit var codeEdit: EditText

        constructor()

        constructor(commandItem: CommandsStorage.CommandItem?, index: Int) {
            val args = Bundle()
            args.putParcelable(EXTRA_ITEM, commandItem)
            args.putInt(EXTRA_INDEX, index)
            arguments = args
        }

        @SuppressLint("InflateParams")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val view =
                LayoutInflater
                    .from(requireContext())
                    .inflate(R.layout.dialog_command, null) as ScrollView
            scrollView = view
            chanNameSelector = view.findViewById(R.id.chan_name)
            boardNameEdit = view.findViewById(R.id.board_name)
            nameEdit = view.findViewById(R.id.name)
            useInView = view.findViewById(R.id.use_in)
            runOnSendCheckBox = view.findViewById(R.id.run_on_send)
            codeEdit = view.findViewById(R.id.code)
            chanNameSelector.setOnClickListener { ChanMultiChoiceDialog(selectedChanNames).show(this) }
            chanNameSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM

            // Only comment is supported for now; the dropdown is a single fixed item.
            useInView.setItems(listOf(getString(R.string.comment)))
            useInView.setSelection(0)

            if (!ChanManager.getInstance().hasMultipleAvailableChans()) {
                chanNameSelector.visibility = View.GONE
            }
            var commandItem: CommandsStorage.CommandItem? = null
            if (savedInstanceState != null) {
                commandItem = BundleCompat.getParcelable(savedInstanceState, EXTRA_ITEM, CommandsStorage.CommandItem::class.java)
            }
            if (commandItem == null) {
                commandItem = BundleCompat.getParcelable(requireArguments(), EXTRA_ITEM, CommandsStorage.CommandItem::class.java)
            }
            if (commandItem != null) {
                commandItem.chanNames?.let { selectedChanNames.addAll(it) }
                boardNameEdit.setText(commandItem.boardName)
                nameEdit.setText(commandItem.name)
                runOnSendCheckBox.isChecked = commandItem.runOnSend
                codeEdit.setText(commandItem.code)
            } else {
                chanNameSelector.setText(R.string.all_forums)
            }
            updateSelectedText()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putParcelable(EXTRA_ITEM, readDialogView())
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val index = requireArguments().getInt(EXTRA_INDEX)
            val builder =
                AlertDialog
                    .Builder(requireContext())
                    .setView(scrollView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        (parentFragment as CommandsFragment).onEditComplete(readDialogView(), index)
                    }
            if (index >= 0) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    (parentFragment as CommandsFragment).onDelete(index)
                }
            }
            val dialog = builder.create()
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            return dialog
        }

        private fun updateSelectedText() {
            val chanNameText: String
            val size = selectedChanNames.size
            if (size == 0) {
                chanNameText = getString(R.string.all_forums)
            } else if (size > 1) {
                chanNameText = getString(R.string.multiple_forums)
            } else {
                val chanName = selectedChanNames.iterator().next()
                val chan = Chan.get(chanName)
                val title = if (chan.name != null) chan.configuration.getTitle() else chanName
                chanNameText = getString(R.string.forum_only__format, title)
            }
            chanNameSelector.text = chanNameText
        }

        private fun readDialogView(): CommandsStorage.CommandItem =
            CommandsStorage.CommandItem(
                if (selectedChanNames.isNotEmpty()) HashSet(selectedChanNames) else null,
                boardNameEdit.text.toString(),
                nameEdit.text.toString(),
                codeEdit.text.toString(),
                CommandsStorage.UseIn.COMMENT,
                runOnSendCheckBox.isChecked,
            )

        override fun onChansSelected(chanNames: Collection<String>) {
            selectedChanNames.clear()
            selectedChanNames.addAll(chanNames)
            updateSelectedText()
        }

        companion object {
            private const val EXTRA_ITEM = "item"
            private const val EXTRA_INDEX = "index"
        }
    }
}
