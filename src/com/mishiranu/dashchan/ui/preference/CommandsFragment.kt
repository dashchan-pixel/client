package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import com.mishiranu.dashchan.widget.CodeEditText
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.SortableHelper
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

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
        menu.add(0, R.id.menu_storage, 0, R.string.storage)
        menu.add(0, R.id.menu_libraries, 0, R.string.libraries)
    }

    override fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        // Nothing has stored anything yet: a row that opens an empty dialog is one more thing between
        // the user and the commands.
        menu.findItem(R.id.menu_storage)?.isVisible = CommandsStorage.getInstance().getStoreSize() > 0
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

            R.id.menu_storage -> {
                StorageDialog().show(childFragmentManager, StorageDialog::class.java.name)
                return true
            }

            R.id.menu_libraries -> {
                (requireActivity() as FragmentHandler).pushFragment(LibrariesFragment())
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
                        val import =
                            if (jsonObject != null) CommandsStorage.parseImport(jsonObject) else CommandsStorage.Import.EMPTY
                        if (!import.isEmpty) {
                            addCommands(import)
                        } else {
                            ClickableToast.show(R.string.invalid_data_format)
                        }
                    }
                }
            }
        }

    private fun addCommands(import: CommandsStorage.Import) {
        val storage = CommandsStorage.getInstance()
        // The libraries first: a command references them by name, so they have to exist before it
        // runs. One the user already has is kept as it is.
        storage.addMissingLibraries(import.libraries)
        for (command in import.commands) {
            storage.add(command)
            items.add(command)
        }
        if (import.commands.isNotEmpty()) {
            setErrorText(null)
            getRecyclerView()!!.adapter!!.notifyDataSetChanged()
        } else {
            ClickableToast.show(R.string.completed)
        }
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
                command.libraries?.let { LinkedHashSet(it) },
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
                chanNames.isNullOrEmpty() -> {
                    builder.append(getString(R.string.all_forums))
                }

                chanNames.size == 1 -> {
                    val chanName = chanNames.iterator().next()
                    val chan = Chan.get(chanName)
                    val title = if (chan.name != null) chan.configuration.getTitle() else chanName
                    builder.append(getString(R.string.forum_only__format, title))
                }

                else -> {
                    builder.append(getString(R.string.multiple_forums))
                }
            }
            if (!StringUtils.isEmpty(commandItem.boardName)) {
                builder.append(" & [").append(commandItem.boardName).append(']')
            }
            val useInRes =
                when (commandItem.useIn) {
                    CommandsStorage.UseIn.COMMENT -> R.string.draft
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
        ChanMultiChoiceDialog.Callback,
        GrantMultiChoiceDialog.Callback,
        LibraryMultiChoiceDialog.Callback {
        private val selectedChanNames = HashSet<String>()
        private val selectedLibraries = LinkedHashSet<String>()
        private val selectedGrants = LinkedHashSet<CommandsStorage.Grant>()

        private lateinit var scrollView: ScrollView
        private lateinit var chanNameSelector: TextView
        private lateinit var boardNameEdit: EditText
        private lateinit var nameEdit: EditText
        private lateinit var useInView: DropdownView
        private lateinit var autoRunCheckBox: CheckBox
        private lateinit var perPostCheckBox: CheckBox
        private lateinit var grantsSelector: TextView
        private lateinit var librariesSelector: TextView
        private lateinit var codeEdit: CodeEditText

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
            grantsSelector = view.findViewById(R.id.grants)
            librariesSelector = view.findViewById(R.id.libraries)
            codeEdit = view.findViewById(R.id.code)
            chanNameSelector.setOnClickListener { ChanMultiChoiceDialog(selectedChanNames).show(this) }
            chanNameSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM
            grantsSelector.setOnClickListener { GrantMultiChoiceDialog(selectedGrants).show(this) }
            grantsSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM
            librariesSelector.setOnClickListener { LibraryMultiChoiceDialog(selectedLibraries).show(this) }
            librariesSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM

            useInView.setItems(USE_IN_ORDER.map { getString(it.titleRes) })
            useInView.setOnItemSelectedListener { position -> applyUseIn(USE_IN_ORDER[position]) }
            // The sample depends on both, so the checkbox has to refresh it too.
            perPostCheckBox.setOnCheckedChangeListener { _, _ -> updateCodeHint() }

            if (!ChanManager.getInstance().hasMultipleAvailableChans()) {
                chanNameSelector.visibility = View.GONE
            }
            // Nothing to pick from until the user has written a library, and a row that only ever says
            // "no libraries" is one more thing between them and the code field.
            if (CommandsStorage.getInstance().getLibraryItems().isEmpty()) {
                librariesSelector.visibility = View.GONE
            }
            // What the dialog was showing when it went away, else the command it was opened for.
            val restored =
                savedInstanceState?.let {
                    BundleCompat.getParcelable(it, EXTRA_ITEM, CommandsStorage.CommandItem::class.java)
                }
            fillDialogView(
                restored ?: BundleCompat.getParcelable(requireArguments(), EXTRA_ITEM, CommandsStorage.CommandItem::class.java),
            )
        }

        /**
         * Shows [commandItem], or the defaults a new command starts from. The spinner delivers its
         * initial selection on layout, i.e. after the dialog is built, so each branch applies its own
         * target here rather than waiting for the listener.
         */
        private fun fillDialogView(commandItem: CommandsStorage.CommandItem?) {
            if (commandItem != null) {
                editItemId = commandItem.id
                commandItem.chanNames?.let { selectedChanNames.addAll(it) }
                boardNameEdit.setText(commandItem.boardName)
                nameEdit.setText(commandItem.name)
                useInView.setSelection(USE_IN_ORDER.indexOf(commandItem.useIn).coerceAtLeast(0))
                autoRunCheckBox.isChecked = commandItem.autoRun
                perPostCheckBox.isChecked = commandItem.perPost
                selectedGrants.addAll(commandItem.grants)
                commandItem.libraries?.let { selectedLibraries.addAll(it) }
                codeEdit.setText(commandItem.code)
                applyUseIn(commandItem.useIn)
            } else {
                editItemId = CommandsStorage.CommandItem.generateId()
                chanNameSelector.setText(R.string.all_forums)
                selectedGrants.addAll(CommandsStorage.Grant.DEFAULT)
                applyUseIn(USE_IN_ORDER[0])
            }
            updateSelectedText()
            updateGrantsText()
            updateLibrariesText()
        }

        /**
         * Relabels and shows the target-dependent options: the auto-run checkbox means different things
         * per target (run before sending vs. run when the thread opens), and per-post processing only
         * exists for a thread command — a draft command is handed its single input already.
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
                    selectedUseIn == CommandsStorage.UseIn.COMMENT -> SAMPLE_DRAFT
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
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        (parentFragment as CommandsFragment).onEditComplete(readDialogView(), index)
                    }
            if (index >= 0) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    (parentFragment as CommandsFragment).onDelete(index)
                }
            }

            val dialog =
                wrapWithRibbon(requireContext(), scrollView, codeEdit) { view ->
                    builder.setView(view).create()
                }

            @Suppress("DEPRECATION")
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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

        /**
         * Lists what the command is allowed to reach, or says it is allowed nothing. Always shown,
         * unlike the libraries row: a command that was granted the settings or a forum's cookies is
         * worth seeing on the way past, and one that was granted nothing is worth being told about
         * before its script says so.
         */
        private fun updateGrantsText() {
            grantsSelector.text =
                if (selectedGrants.isEmpty()) {
                    getString(R.string.nothing_granted)
                } else {
                    val titles =
                        CommandsStorage.Grant.entries
                            .filter { it in selectedGrants }
                            .map { getString(it.titleRes) }
                    getString(R.string.access__format, titles.joinToString(", "))
                }
        }

        override fun onGrantsSelected(grants: Collection<CommandsStorage.Grant>) {
            selectedGrants.clear()
            selectedGrants.addAll(grants)
            updateGrantsText()
        }

        /** Lists the selected libraries, or says there are none — the row is hidden when none exist. */
        private fun updateLibrariesText() {
            librariesSelector.text =
                if (selectedLibraries.isEmpty()) {
                    getString(R.string.no_libraries)
                } else {
                    getString(R.string.libraries__format, orderedLibraries().joinToString(", "))
                }
        }

        /**
         * The selection in load order, which is the order the libraries run in — so the row reads the
         * way the command will run, and that is also the order the command is saved with.
         */
        private fun orderedLibraries(): List<String> =
            CommandsStorage
                .getInstance()
                .librariesFor(selectedLibraries)
                .map { it.name }

        override fun onLibrariesSelected(names: Collection<String>) {
            selectedLibraries.clear()
            selectedLibraries.addAll(names)
            updateLibrariesText()
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
                // Kept in load order, so an export reads in the order the libraries run in.
                LinkedHashSet(orderedLibraries()).ifEmpty { null },
                // Kept in the enum's order rather than the order they were ticked, so the same set of
                // grants always saves, and reads back, the same way.
                CommandsStorage.Grant.entries.filterTo(LinkedHashSet()) { it in selectedGrants },
            )
        }

        override fun onChansSelected(
            chanNames: Collection<String>,
            target: String?,
        ) {
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
            private const val SAMPLE_DRAFT = "return { comment, attachments };"

            private const val SAMPLE_THREAD =
                "return posts.reduce((acc, post) => {\n" +
                    "  acc[post.number] = { comment: post.comment,\n" +
                    "    attachments: post.attachments };\n" +
                    "  return acc;\n" +
                    "}, {});"

            private const val SAMPLE_PER_POST = "return { comment: post.comment, attachments: post.attachments };"

            private val CommandsStorage.UseIn.titleRes: Int
                get() =
                    when (this) {
                        CommandsStorage.UseIn.COMMENT -> R.string.draft
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
     * Editor for the shared command [environment][CommandsStorage.getEnvText] — a plain key→value
     * store that every command can read as `env.NAME`. Edited as free text, one `NAME=value` per
     * line, which is the simplest thing to type and re-edit; the text is also what gets stored, so
     * comments and blank lines are kept. The accepted syntax is described on [EnvText]. This is not
     * JavaScript, so it is highlighted as an env file rather than as a command body.
     */
    class EnvironmentDialog : DialogFragment() {
        private lateinit var envEdit: CodeEditText

        @SuppressLint("InflateParams")
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_environment, null)
            envEdit = view.findViewById(R.id.env)
            envEdit.syntax = CodeEditText.Syntax.ENVIRONMENT
            if (savedInstanceState == null) {
                // On recreation the EditText restores its own text from the saved view state, so only
                // seed it on first creation.
                envEdit.setText(CommandsStorage.getInstance().getEnvText())
            }
            val builder =
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.environment)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        CommandsStorage.getInstance().setEnvText(envEdit.text.toString())
                    }
            val dialog =
                wrapWithRibbon(requireContext(), view, envEdit) { newView ->
                    builder.setView(newView).create()
                }
            @Suppress("DEPRECATION")
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            return dialog
        }
    }

    /**
     * Shows what the commands have kept in their [store][CommandsStorage.getStore], and lets the user
     * empty it. Read-only: the values are a script's own state, not something to hand-edit into a
     * shape its author never expected — but they are the user's to see and to throw away, which is the
     * difference between this and a setting a script would otherwise have squatted in.
     *
     * A long value is shown cut short. The point of the dialog is to say what is being kept and how
     * much of it, not to be a viewer for a payload a script parked there.
     */
    class StorageDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
            AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.storage)
                .setMessage(
                    CommandsStorage
                        .getInstance()
                        .getStore()
                        .entries
                        .joinToString("\n\n") { (key, value) ->
                            key + " = " + StringUtils.cutIfLongerToLine(value, MAX_VALUE_LENGTH, true)
                        },
                ).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.clear) { _, _ ->
                    CommandsStorage.getInstance().clearStore()
                    (parentFragment as CommandsFragment).invalidateOptionsMenu()
                }.setPositiveButton(android.R.string.ok, null)
                .create()

        companion object {
            private const val MAX_VALUE_LENGTH = 120
        }
    }
}

private fun createSymbolButton(
    context: Context,
    symbol: String,
    codeEdit: CodeEditText,
): TextView =
    TextView(context).apply {
        text = symbol
        textSize = 16f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val start = codeEdit.selectionStart
            val end = codeEdit.selectionEnd
            if (start >= 0 && end >= 0) {
                codeEdit.text?.replace(min(start, end), max(start, end), symbol)
            }
        }
    }

private fun createOkButton(
    context: Context,
    codeEdit: CodeEditText,
): TextView =
    TextView(context).apply {
        setText(android.R.string.ok)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val pX = (16 * resources.displayMetrics.density).toInt()
        val pY = (12 * resources.displayMetrics.density).toInt()
        setPadding(pX, pY, pX, pY)
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            codeEdit.clearFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(codeEdit.windowToken, 0)
        }
    }

private fun createBottomBar(
    context: Context,
    codeEdit: CodeEditText,
): LinearLayout {
    val symbolsContainer =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
    val symbols = listOf("{", "}", "[", "]", "/", "\\", "$", "`", "=", "\"", ":", ";", "(", ")", "<", ">", "&", "|")
    for (symbol in symbols) {
        val button = createSymbolButton(context, symbol, codeEdit)
        symbolsContainer.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    val ribbonScroll =
        HorizontalScrollView(context).apply {
            addView(symbolsContainer)
            isHorizontalScrollBarEnabled = false
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val px = (8 * resources.displayMetrics.density).toInt()
        val py = (4 * resources.displayMetrics.density).toInt()
        setPadding(px, py, px, py)
        visibility = View.GONE
        addView(ribbonScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val okButton = createOkButton(context, codeEdit)
        addView(
            okButton,
            LinearLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = (8 * context.resources.displayMetrics.density).toInt()
                },
        )
    }
}

/**
 * Turns the dialog around an expanded code field into a fullscreen editor, so that the screen holds
 * nothing but the status bar, the code, the symbol ribbon and the keyboard — and turns it back into
 * a dialog when the field collapses, because the same dialog is also the form with the other fields
 * on it.
 *
 * What is in the way, from the outside in: the floating window is only as large as the form and its
 * background is an inset rounded rectangle whose inset the decor takes as padding; the dialog's own
 * title and button bar sit above and below the custom view (the ribbon replaces the buttons, and the
 * only title is the environment editor's); and each panel between the window and the field pads it.
 * All of it is measured or hidden here and restored on the way out.
 */
private class FullscreenEditor(
    private val dialog: AlertDialog,
    private val content: View,
) {
    private var enabled = false
    private val hiddenViews = ArrayList<View>()
    private val paddings = ArrayList<Pair<View, Rect>>()
    private val heights = ArrayList<Pair<View, Int>>()

    private var windowWidth = WindowManager.LayoutParams.WRAP_CONTENT
    private var windowHeight = WindowManager.LayoutParams.WRAP_CONTENT
    private var windowGravity = Gravity.CENTER
    private var windowBackground: Drawable? = null

    fun setEnabled(enable: Boolean) {
        if (enabled == enable) {
            return
        }
        val window = dialog.window ?: return
        if (enable) {
            // Where the panels the dialog builds around the custom view end is the one thing that has
            // to be known before anything is touched, because the panels above it hold the window's
            // own insets and must be left alone.
            val contentParent = window.findViewById<View>(android.R.id.content) ?: return
            enabled = true
            enter(window, contentParent)
        } else {
            enabled = false
            leave(window)
        }
    }

    private fun enter(
        window: Window,
        contentParent: View,
    ) {
        val decorView = window.decorView
        var view = content
        while (true) {
            val parent = view.parent as? ViewGroup ?: break
            // Everything the dialog puts beside the code field — its title, its buttons, the message
            // panel — is either replaced by the ribbon or empty, so none of it is laid out. Only the
            // views actually hidden here are remembered, so leaving restores exactly them.
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child !== view && child.visibility == View.VISIBLE) {
                    child.visibility = View.GONE
                    hiddenViews.add(child)
                }
            }
            dropPadding(parent)
            // Every panel on the way down has to be told to fill, or the field is measured against
            // the text it holds instead of against the screen.
            fillParent(view)
            if (parent === contentParent) {
                break
            }
            view = parent
        }

        window.attributes.let {
            windowWidth = it.width
            windowHeight = it.height
            windowGravity = it.gravity
        }
        windowBackground = decorView.background
        window.setBackgroundDrawable(ColorDrawable(ThemeEngine.getTheme(dialog.context).card))
        dropPadding(decorView)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        // A fullscreen window is laid out behind the system bars and the keyboard where the app draws
        // edge to edge, and is inset by the framework where it doesn't — in which case the insets
        // arrive here already spent and the padding stays zero.
        content.setOnApplyWindowInsetsListener { paddedView, insets ->
            val bars =
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            paddedView.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
            insets
        }
        content.requestApplyInsets()
    }

    private fun leave(window: Window) {
        content.setOnApplyWindowInsetsListener(null)
        content.setPadding(0, 0, 0, 0)
        for (view in hiddenViews) {
            view.visibility = View.VISIBLE
        }
        hiddenViews.clear()
        for ((view, height) in heights) {
            view.layoutParams = view.layoutParams.also { it.height = height }
        }
        heights.clear()
        // The background goes back before the paddings: it carries the inset the decor's padding came
        // from, and assigning it applies that inset again.
        window.setBackgroundDrawable(windowBackground)
        windowBackground = null
        for ((view, padding) in paddings) {
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
        paddings.clear()
        window.setGravity(windowGravity)
        window.setLayout(windowWidth, windowHeight)
    }

    private fun dropPadding(view: View) {
        val padding = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        if (padding.left != 0 || padding.top != 0 || padding.right != 0 || padding.bottom != 0) {
            paddings.add(view to padding)
            view.setPadding(0, 0, 0, 0)
        }
    }

    private fun fillParent(view: View) {
        val layoutParams = view.layoutParams ?: return
        if (layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            heights.add(view to layoutParams.height)
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = layoutParams
        }
    }
}

/** Also used by the library editor, which has the same code field under the same keyboard. */
internal fun wrapWithRibbon(
    context: Context,
    scrollView: View,
    codeEdit: CodeEditText,
    dialogCreator: (View) -> AlertDialog,
): AlertDialog {
    val bottomBar = createBottomBar(context, codeEdit)

    val root =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                bottomBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    val dialog = dialogCreator(root)
    val fullscreenEditor = FullscreenEditor(dialog, root)

    codeEdit.onExpandedStateChanged = { expanded ->
        bottomBar.visibility = if (expanded) View.VISIBLE else View.GONE
        fullscreenEditor.setEnabled(expanded)
    }

    return dialog
}
