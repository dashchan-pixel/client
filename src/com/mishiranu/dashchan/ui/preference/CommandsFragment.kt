package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.SortableHelper
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class CommandsFragment :
    BaseListFragment,
    SortableHelper.Callback<CommandsFragment.CommandViewHolder> {
    private val items = ArrayList<CommandsStorage.CommandItem>()

    // Two-finger tap-and-drag reorder, mirroring the drawer's favourites/forums sorting: the gesture
    // arms on a long press held with a second finger down, then ItemTouchHelper drives the drag.
    private var sortableHelper: SortableHelper<CommandViewHolder>? = null
    private val dragState = SortableHelper.DragState()

    constructor() : super()

    /** Opens the fragment with the command of [editCommandId] shown in the edit dialog (e.g. from ⌘). */
    constructor(editCommandId: Long) : this() {
        arguments = Bundle().apply { putLong(EXTRA_EDIT_ID, editCommandId) }
    }

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
        val recyclerView = getRecyclerView()!!
        recyclerView.adapter = Adapter()
        sortableHelper = SortableHelper(recyclerView, this)

        if (savedInstanceState == null) {
            val editId = arguments?.getLong(EXTRA_EDIT_ID, 0) ?: 0
            if (editId != 0L) {
                val index = items.indexOfFirst { it.id == editId }
                if (index >= 0) {
                    editCommand(items[index], index)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_command, 0, R.string.new_command)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, R.id.menu_add_command, 0, R.string.add_command)
        menu.add(0, R.id.menu_environment, 0, R.string.environment)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_command -> {
                editCommand(null, -1)
                return true
            }
            R.id.menu_add_command -> {
                launchAddCommand()
                return true
            }
            R.id.menu_environment -> {
                EnvironmentDialog().show(childFragmentManager, EnvironmentDialog::class.java.name)
                return true
            }
        }
        return super.onMenuItemSelected(item)
    }

    private fun launchAddCommand() {
        // Mirror ThemesFragment's "add theme": pick a JSON file and import command(s) from it.
        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("json")
        if (StringUtils.isEmpty(mimeType) || "application/octet-stream" == mimeType) {
            mimeType = "*/*"
        }
        val intent =
            Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
        addCommandLauncher.launch(intent)
    }

    private val addCommandLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val uri = data.data
                val fileHolder = if (uri != null) FileHolder.obtain(uri) else null
                if (fileHolder != null) {
                    val output = ByteArrayOutputStream()
                    val success =
                        try {
                            fileHolder.openInputStream().use { input -> IOUtils.copyStream(input, output) }
                            true
                        } catch (e: IOException) {
                            e.printStackTrace()
                            false
                        }
                    val array = output.toByteArray()
                    if (success && array.isNotEmpty()) {
                        val jsonObject =
                            try {
                                JSONObject(String(array))
                            } catch (e: JSONException) {
                                e.printStackTrace()
                                null
                            }
                        val commands =
                            if (jsonObject != null) CommandsStorage.parseCommands(jsonObject) else emptyList()
                        if (commands.isNotEmpty()) {
                            addCommands(commands)
                        } else {
                            ClickableToast.show(R.string.invalid_data_format)
                        }
                    }
                }
            }
        }

    private fun addCommands(commands: List<CommandsStorage.CommandItem>) {
        val storage = CommandsStorage.getInstance()
        for (command in commands) {
            storage.add(command)
            items.add(command)
        }
        setErrorText(null)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
    }

    // Command awaiting a destination Uri from the create-document picker (Save from the context menu).
    private var pendingSaveCommand: CommandsStorage.CommandItem? = null

    private val saveCommandLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val command = pendingSaveCommand
            pendingSaveCommand = null
            if (uri != null && command != null) {
                val saved =
                    try {
                        val json = CommandsStorage.getInstance().commandToJson(command).toString()
                        requireContext().contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray())
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                ClickableToast.show(if (saved) R.string.completed else R.string.unknown_error)
            }
        }

    private fun showCommandContextMenu(command: CommandsStorage.CommandItem) {
        val title = if (command.name.isNullOrEmpty()) getString(R.string.command) else command.name
        DialogMenu(requireContext())
            .setTitle(title)
            .add(R.string.duplicate) { duplicateCommand(command) }
            .add(R.string.save) {
                pendingSaveCommand = command
                val baseName = command.name?.takeIf { it.isNotEmpty() } ?: "command"
                saveCommandLauncher.launch("$baseName.json")
            }.add(R.string.copy) { copyCommandJson(command) }
            .create()
            .show()
    }

    /**
     * Duplicates a command: the copy gets its own identity and a name of its own, and opens in the
     * edit dialog as a new command (index -1), so it's stored only once the user confirms it.
     */
    private fun duplicateCommand(command: CommandsStorage.CommandItem) {
        val name = command.name
        val copy =
            CommandsStorage.CommandItem(
                CommandsStorage.CommandItem.generateId(),
                command.chanNames?.let { HashSet(it) },
                command.boardName,
                if (name.isNullOrEmpty()) name else uniqueName(name),
                command.code,
                command.useIn,
                command.autoRun,
                command.perPost,
            )
        editCommand(copy, -1)
    }

    /** Keeps the duplicate distinguishable in the list, where names are free-form and may repeat. */
    private fun uniqueName(name: String): String {
        val existing = items.mapTo(HashSet()) { it.name }
        var index = 2
        while ("$name ($index)" in existing) {
            index++
        }
        return "$name ($index)"
    }

    private fun copyCommandJson(command: CommandsStorage.CommandItem) {
        try {
            val json = CommandsStorage.getInstance().commandToJson(command).toString()
            requireContext()
                .getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.command), json))
            ClickableToast.show(R.string.copied_to_clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            ClickableToast.show(R.string.unknown_error)
        }
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

    override fun onDragStart(holder: CommandViewHolder) {
        dragState.reset()
        holder.setDragging(true, ThemeEngine.getTheme(requireContext()).accent)
    }

    override fun onDragFinish(
        holder: CommandViewHolder?,
        cancelled: Boolean,
    ) {
        if (!cancelled && dragState.getMovedTo() >= 0) {
            CommandsStorage.getInstance().replaceAll(items)
        }
        holder?.setDragging(false, 0)
    }

    // Single flat list of commands, so any item may drop over any other.
    override fun onDragCanMove(
        fromHolder: CommandViewHolder,
        toHolder: CommandViewHolder,
    ): Boolean = true

    override fun onDragMove(
        fromHolder: CommandViewHolder,
        toHolder: CommandViewHolder,
    ): Boolean {
        val from = fromHolder.bindingAdapterPosition
        val to = toHolder.bindingAdapterPosition
        if (from < 0 || to < 0) {
            return false
        }
        items.add(to, items.removeAt(from))
        getRecyclerView()!!.adapter!!.notifyItemMoved(from, to)
        dragState.set(from, to)
        return true
    }

    private inner class Adapter :
        RecyclerView.Adapter<CommandViewHolder>(),
        ListViewUtils.ClickCallback<Unit, CommandViewHolder> {
        override fun getItemCount(): Int = items.size

        override fun onItemClick(
            holder: CommandViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            if (longClick) {
                if (holder.isMultipleFingers) {
                    sortableHelper?.start(holder)
                } else {
                    showCommandContextMenu(items[position])
                }
            } else {
                editCommand(items[position], position)
            }
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): CommandViewHolder =
            ListViewUtils.bind<Unit, CommandViewHolder>(
                CommandViewHolder(ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_SINGLE_LINE)),
                true,
                null,
                this,
            )

        override fun onBindViewHolder(
            holder: CommandViewHolder,
            position: Int,
        ) {
            val commandItem = items[position]
            holder.twoLines.text1.text =
                if (StringUtils.isEmpty(commandItem.name)) getString(R.string.command) else commandItem.name
            holder.twoLines.text2.text = describeScope(commandItem)
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
            val useInRes =
                when (commandItem.useIn) {
                    CommandsStorage.UseIn.COMMENT -> R.string.comment
                    CommandsStorage.UseIn.THREAD -> R.string.thread
                }
            builder.append(" · ").append(getString(useInRes))
            if (commandItem.perPost && commandItem.useIn == CommandsStorage.UseIn.THREAD) {
                builder.append(" · ").append(getString(R.string.per_post))
            }
            if (commandItem.autoRun) {
                builder.append(" · ").append(getString(R.string.auto_run))
            }
            return builder
        }
    }

    /**
     * Row holder that also tracks the two-finger gesture (like the drawer's sortable rows): a second
     * finger held down during a long press arms the drag, distinguishing it from a plain long-press
     * (which opens the context menu). [setDragging] tints the title while the row is being dragged.
     */
    class CommandViewHolder(
        val twoLines: ViewFactory.TwoLinesViewHolder,
    ) : RecyclerView.ViewHolder(twoLines.view),
        View.OnTouchListener {
        private var originalTextColors: ColorStateList? = null

        private var multipleFingersCountingTime = false
        private var multipleFingersTime = 0L
        private var multipleFingersStartTime = 0L

        init {
            twoLines.view.setOnTouchListener(this)
        }

        fun setDragging(
            dragging: Boolean,
            activeColor: Int,
        ) {
            if (dragging) {
                if (originalTextColors == null) {
                    originalTextColors = twoLines.text1.textColors
                }
                twoLines.text1.setTextColor(activeColor)
            } else {
                originalTextColors?.let { twoLines.text1.setTextColor(it) }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            v: View?,
            event: MotionEvent,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multipleFingersCountingTime = false
                    multipleFingersStartTime = 0L
                    multipleFingersTime = 0L
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!multipleFingersCountingTime) {
                        multipleFingersCountingTime = true
                        multipleFingersStartTime = SystemClock.elapsedRealtime()
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // A two-finger gesture only ever has the second finger to lift, so we stop timing on
                    // the first pointer-up. (The drawer's sortable rows also guard on pointerCount <= 2,
                    // but that only matters for 3+ fingers, which this gesture doesn't care about.)
                    if (multipleFingersCountingTime) {
                        multipleFingersCountingTime = false
                        multipleFingersTime += SystemClock.elapsedRealtime() - multipleFingersStartTime
                    }
                }
            }
            return false
        }

        val isMultipleFingers: Boolean
            get() {
                var time = multipleFingersTime
                if (multipleFingersCountingTime) {
                    time += SystemClock.elapsedRealtime() - multipleFingersStartTime
                }
                return time >= ViewConfiguration.getLongPressTimeout() / 10
            }
    }

    companion object {
        private const val EXTRA_EDIT_ID = "editId"
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
        private lateinit var autoRunCheckBox: CheckBox
        private lateinit var perPostCheckBox: CheckBox
        private lateinit var codeEdit: EditText

        // Identity of the command being edited, carried into readDialogView() so an edit keeps the same
        // id (and a new command keeps one stable id for the life of the dialog).
        private var editItemId: Long = 0

        // The target the dialog is currently showing options for. Tracked because the spinner resolves
        // its own selection only at layout time, too late for the code placeholder.
        private var selectedUseIn = CommandsStorage.UseIn.COMMENT

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
            autoRunCheckBox = view.findViewById(R.id.auto_run)
            perPostCheckBox = view.findViewById(R.id.per_post)
            codeEdit = view.findViewById(R.id.code)
            chanNameSelector.setOnClickListener { ChanMultiChoiceDialog(selectedChanNames).show(this) }
            chanNameSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM

            useInView.setItems(USE_IN_ORDER.map { getString(it.titleRes) })
            useInView.setOnItemSelectedListener { position -> applyUseIn(USE_IN_ORDER[position]) }
            // The sample depends on both, so the checkbox has to refresh it too.
            perPostCheckBox.setOnCheckedChangeListener { _, _ -> updateCodeHint() }

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
            // The spinner delivers its initial selection on layout, i.e. after the dialog is built, so
            // each branch applies its own target here rather than waiting for the listener.
            if (commandItem != null) {
                editItemId = commandItem.id
                commandItem.chanNames?.let { selectedChanNames.addAll(it) }
                boardNameEdit.setText(commandItem.boardName)
                nameEdit.setText(commandItem.name)
                useInView.setSelection(USE_IN_ORDER.indexOf(commandItem.useIn).coerceAtLeast(0))
                autoRunCheckBox.isChecked = commandItem.autoRun
                perPostCheckBox.isChecked = commandItem.perPost
                codeEdit.setText(commandItem.code)
                applyUseIn(commandItem.useIn)
            } else {
                editItemId = CommandsStorage.CommandItem.generateId()
                chanNameSelector.setText(R.string.all_forums)
                applyUseIn(USE_IN_ORDER[0])
            }
            updateSelectedText()
        }

        /**
         * Relabels and shows the target-dependent options: the auto-run checkbox means different things
         * per target (run before sending vs. run when the thread opens), and per-post processing only
         * exists for a thread command — a comment command is handed its single input already.
         */
        private fun applyUseIn(useIn: CommandsStorage.UseIn) {
            selectedUseIn = useIn
            autoRunCheckBox.setText(useIn.autoRunRes)
            perPostCheckBox.visibility =
                if (useIn == CommandsStorage.UseIn.THREAD) View.VISIBLE else View.GONE
            updateCodeHint()
        }

        /**
         * Shows the identity command for the current target as the empty code field's placeholder: the
         * shortest body that hands the data back unchanged. It spells out what the body is given and
         * what it has to return — which differs per target — without the user having to run anything.
         */
        private fun updateCodeHint() {
            codeEdit.hint =
                when {
                    selectedUseIn == CommandsStorage.UseIn.COMMENT -> SAMPLE_COMMENT
                    perPostCheckBox.isChecked -> SAMPLE_PER_POST
                    else -> SAMPLE_THREAD
                }
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

        private fun readDialogView(): CommandsStorage.CommandItem {
            val useIn = USE_IN_ORDER[useInView.getSelectedItemPosition().coerceIn(USE_IN_ORDER.indices)]
            return CommandsStorage.CommandItem(
                editItemId,
                if (selectedChanNames.isNotEmpty()) HashSet(selectedChanNames) else null,
                boardNameEdit.text.toString(),
                nameEdit.text.toString(),
                codeEdit.text.toString(),
                useIn,
                autoRunCheckBox.isChecked,
                // The checkbox is hidden for a comment command, so don't save what it happens to be
                // left on from a target the user switched away from.
                useIn == CommandsStorage.UseIn.THREAD && perPostCheckBox.isChecked,
            )
        }

        override fun onChansSelected(chanNames: Collection<String>) {
            selectedChanNames.clear()
            selectedChanNames.addAll(chanNames)
            updateSelectedText()
        }

        companion object {
            private const val EXTRA_ITEM = "item"
            private const val EXTRA_INDEX = "index"

            // The dropdown's fixed order; the selected index maps back to a UseIn on save.
            private val USE_IN_ORDER = listOf(CommandsStorage.UseIn.COMMENT, CommandsStorage.UseIn.THREAD)

            // Placeholders for the code field: the do-nothing command for each target, i.e. the least
            // code that returns the input unchanged. Not translated — it's JavaScript.
            private const val SAMPLE_COMMENT = "return comment;"

            private const val SAMPLE_THREAD =
                "return posts.reduce((acc, post) => {\n" +
                    "  acc[post.number] = post.comment;\n" +
                    "  return acc;\n" +
                    "}, {});"

            private const val SAMPLE_PER_POST = "return post.comment;"

            private val CommandsStorage.UseIn.titleRes: Int
                get() =
                    when (this) {
                        CommandsStorage.UseIn.COMMENT -> R.string.comment
                        CommandsStorage.UseIn.THREAD -> R.string.thread
                    }

            private val CommandsStorage.UseIn.autoRunRes: Int
                get() =
                    when (this) {
                        CommandsStorage.UseIn.COMMENT -> R.string.run_before_sending
                        CommandsStorage.UseIn.THREAD -> R.string.run_when_thread_opens
                    }
        }
    }

    /**
     * Editor for the shared command [environment][CommandsStorage.getEnv] — a plain key→value store
     * that every command can read as `env.NAME`. Presented as free text, one `NAME=value` per line,
     * which is the simplest thing to type and re-edit. Lines without a valid identifier key (or with
     * no `=`) are ignored on save.
     */
    class EnvironmentDialog : DialogFragment() {
        private lateinit var envEdit: EditText

        @SuppressLint("InflateParams")
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_environment, null)
            envEdit = view.findViewById(R.id.env)
            if (savedInstanceState == null) {
                // On recreation the EditText restores its own text from the saved view state, so only
                // seed it on first creation.
                envEdit.setText(formatEnv(CommandsStorage.getInstance().getEnv()))
            }
            val dialog =
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.environment)
                    .setView(view)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        CommandsStorage.getInstance().setEnv(parseEnv(envEdit.text.toString()))
                    }.create()
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            return dialog
        }

        companion object {
            // Keys must be usable as `env.NAME`, i.e. valid JS identifiers.
            private val KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

            private fun formatEnv(env: Map<String, String>): String = env.entries.joinToString("\n") { "${it.key}=${it.value}" }

            private fun parseEnv(text: String): Map<String, String> {
                val result = LinkedHashMap<String, String>()
                for (rawLine in text.split('\n')) {
                    val line = rawLine.trim()
                    val eq = line.indexOf('=')
                    if (eq <= 0) {
                        continue
                    }
                    val key = line.substring(0, eq).trim()
                    if (KEY_REGEX.matches(key)) {
                        result[key] = line.substring(eq + 1)
                    }
                }
                return result
            }
        }
    }
}
