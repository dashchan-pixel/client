package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CommandLibraries
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.CodeEditText
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.SortableHelper
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory

/**
 * The Libraries screen: the JavaScript a command can pull into its scope, either kept here as a
 * snippet or downloaded from an address (see [CommandsStorage.LibraryItem] and
 * [CommandLibraries]). Reached from the Commands screen, which is also where a command picks the
 * libraries it loads.
 *
 * The list order is the load order — a library may use what one above it declared — so rows can be
 * dragged by long-pressing them, exactly like the drawer's favourites.
 */
class LibrariesFragment :
    BaseListFragment(),
    SortableHelper.Callback<LibrariesFragment.LibraryViewHolder> {
    private val items = ArrayList<CommandsStorage.LibraryItem>()

    private var sortableHelper: SortableHelper<LibraryViewHolder>? = null
    private val dragState = SortableHelper.DragState()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.libraries), null)
        items.addAll(CommandsStorage.getInstance().getLibraryItems())
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_libraries_defined))
        }
        val recyclerView = getRecyclerView()!!
        recyclerView.adapter = Adapter()
        sortableHelper = SortableHelper(recyclerView, this)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_library, 0, R.string.new_library)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_library -> {
                editLibrary(null, -1)
                return true
            }

            R.id.menu_refresh -> {
                // Downloaded scripts are kept for the life of the process, so this is how the user
                // picks up a change made to one on the other end.
                CommandLibraries.clearCache()
                ClickableToast.show(R.string.completed)
                return true
            }
        }
        return super.onMenuItemSelected(item)
    }

    private fun editLibrary(
        libraryItem: CommandsStorage.LibraryItem?,
        index: Int,
    ) {
        LibraryDialog(libraryItem, index).show(childFragmentManager, LibraryDialog::class.java.name)
    }

    internal fun onEditComplete(
        libraryItem: CommandsStorage.LibraryItem,
        index: Int,
    ) {
        // Commands reference a library by name, so two libraries sharing one would leave the user
        // unable to say which they meant.
        libraryItem.name = uniqueName(libraryItem.name, index)
        val adapter = getRecyclerView()!!.adapter as Adapter
        if (index == -1) {
            CommandsStorage.getInstance().addLibrary(libraryItem)
            items.add(libraryItem)
            setErrorText(null)
        } else if (index >= 0) {
            CommandsStorage.getInstance().updateLibrary(index, libraryItem)
            items[index] = libraryItem
        }
        adapter.notifyDataSetChanged()
    }

    /** [name] itself when no other row holds it, else the first free `name (n)`. */
    private fun uniqueName(
        name: String,
        index: Int,
    ): String {
        val base = name.ifEmpty { getString(R.string.library) }
        val taken = HashSet<String>()
        for (i in items.indices) {
            if (i != index) {
                taken.add(items[i].name)
            }
        }
        if (base !in taken) {
            return base
        }
        var number = 2
        while ("$base ($number)" in taken) {
            number++
        }
        return "$base ($number)"
    }

    internal fun onDelete(index: Int) {
        CommandsStorage.getInstance().deleteLibrary(index)
        items.removeAt(index)
        getRecyclerView()!!.adapter!!.notifyDataSetChanged()
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_libraries_defined))
        }
    }

    override fun onDragStart(holder: LibraryViewHolder) {
        dragState.reset()
        holder.setDragging(true, ThemeEngine.getTheme(requireContext()).accent)
    }

    override fun onDragFinish(
        holder: LibraryViewHolder?,
        cancelled: Boolean,
    ) {
        if (!cancelled && dragState.getMovedTo() >= 0) {
            CommandsStorage.getInstance().replaceAllLibraries(items)
        }
        holder?.setDragging(false, 0)
    }

    override fun onDragCanMove(
        fromHolder: LibraryViewHolder,
        toHolder: LibraryViewHolder,
    ): Boolean = true

    override fun onDragMove(
        fromHolder: LibraryViewHolder,
        toHolder: LibraryViewHolder,
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
        RecyclerView.Adapter<LibraryViewHolder>(),
        ListViewUtils.ClickCallback<Unit, LibraryViewHolder> {
        override fun getItemCount(): Int = items.size

        override fun onItemClick(
            holder: LibraryViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            if (longClick) {
                // Nothing else hangs off a long press here, so it can arm the drag on its own — the
                // Commands screen needs a second finger only because it has a context menu too.
                sortableHelper?.start(holder)
            } else {
                editLibrary(items[position], position)
            }
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): LibraryViewHolder =
            ListViewUtils.bind<Unit, LibraryViewHolder>(
                LibraryViewHolder(ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_SINGLE_LINE)),
                true,
                null,
                this,
            )

        override fun onBindViewHolder(
            holder: LibraryViewHolder,
            position: Int,
        ) {
            val libraryItem = items[position]
            holder.twoLines.text1.text = libraryItem.name
            holder.twoLines.text2.text =
                when (libraryItem.kind) {
                    CommandsStorage.LibraryItem.Kind.SNIPPET -> getString(R.string.code_snippet)
                    CommandsStorage.LibraryItem.Kind.URL -> libraryItem.content
                }
        }
    }

    /** Row holder; [setDragging] tints the title while the row is being dragged. */
    class LibraryViewHolder(
        val twoLines: ViewFactory.TwoLinesViewHolder,
    ) : RecyclerView.ViewHolder(twoLines.view) {
        private var originalTextColors: ColorStateList? = null

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
    }

    class LibraryDialog : DialogFragment {
        private lateinit var scrollView: ScrollView
        private lateinit var nameEdit: EditText
        private lateinit var kindView: DropdownView
        private lateinit var urlEdit: EditText
        private lateinit var codeEdit: CodeEditText

        // Identity of the library being edited, carried into readDialogView() so an edit keeps the
        // same id (and a new library keeps one stable id for the life of the dialog).
        private var editItemId: Long = 0

        // The kind the dialog is currently showing fields for. Tracked because the spinner resolves
        // its own selection only at layout time, too late for readDialogView().
        private var selectedKind = CommandsStorage.LibraryItem.Kind.SNIPPET

        constructor()

        constructor(libraryItem: CommandsStorage.LibraryItem?, index: Int) {
            val args = Bundle()
            args.putParcelable(EXTRA_ITEM, libraryItem)
            args.putInt(EXTRA_INDEX, index)
            arguments = args
        }

        @SuppressLint("InflateParams")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val view =
                LayoutInflater
                    .from(requireContext())
                    .inflate(R.layout.dialog_library, null) as ScrollView
            scrollView = view
            nameEdit = view.findViewById(R.id.name)
            kindView = view.findViewById(R.id.kind)
            urlEdit = view.findViewById(R.id.url)
            codeEdit = view.findViewById(R.id.code)

            kindView.setItems(KIND_ORDER.map { getString(it.titleRes) })
            kindView.setOnItemSelectedListener { position -> applyKind(KIND_ORDER[position]) }

            var libraryItem: CommandsStorage.LibraryItem? = null
            if (savedInstanceState != null) {
                libraryItem = BundleCompat.getParcelable(savedInstanceState, EXTRA_ITEM, CommandsStorage.LibraryItem::class.java)
            }
            if (libraryItem == null) {
                libraryItem = BundleCompat.getParcelable(requireArguments(), EXTRA_ITEM, CommandsStorage.LibraryItem::class.java)
            }
            // The spinner delivers its initial selection on layout, i.e. after the dialog is built, so
            // each branch applies its own kind here rather than waiting for the listener.
            if (libraryItem != null) {
                editItemId = libraryItem.id
                nameEdit.setText(libraryItem.name)
                kindView.setSelection(KIND_ORDER.indexOf(libraryItem.kind).coerceAtLeast(0))
                when (libraryItem.kind) {
                    CommandsStorage.LibraryItem.Kind.SNIPPET -> codeEdit.setText(libraryItem.content)
                    CommandsStorage.LibraryItem.Kind.URL -> urlEdit.setText(libraryItem.content)
                }
                applyKind(libraryItem.kind)
            } else {
                editItemId = CommandsStorage.LibraryItem.generateId()
                applyKind(KIND_ORDER[0])
            }
        }

        /** Shows the field the selected kind is edited in and hides the other one. */
        private fun applyKind(kind: CommandsStorage.LibraryItem.Kind) {
            selectedKind = kind
            val url = kind == CommandsStorage.LibraryItem.Kind.URL
            urlEdit.visibility = if (url) View.VISIBLE else View.GONE
            codeEdit.visibility = if (url) View.GONE else View.VISIBLE
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
                        (parentFragment as LibrariesFragment).onEditComplete(readDialogView(), index)
                    }
            if (index >= 0) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    (parentFragment as LibrariesFragment).onDelete(index)
                }
            }
            val dialog = wrapWithRibbon(requireContext(), scrollView, codeEdit) { view -> builder.setView(view).create() }
            @Suppress("DEPRECATION")
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            return dialog
        }

        private fun readDialogView(): CommandsStorage.LibraryItem {
            val kind = KIND_ORDER[kindView.getSelectedItemPosition().coerceIn(KIND_ORDER.indices)]
            val content =
                when (kind) {
                    CommandsStorage.LibraryItem.Kind.SNIPPET -> codeEdit.text.toString()
                    CommandsStorage.LibraryItem.Kind.URL -> urlEdit.text.toString().trim()
                }
            return CommandsStorage.LibraryItem(editItemId, nameEdit.text.toString().trim(), kind, content)
        }

        companion object {
            private const val EXTRA_ITEM = "item"
            private const val EXTRA_INDEX = "index"

            // The dropdown's fixed order; the selected index maps back to a Kind on save.
            private val KIND_ORDER =
                listOf(CommandsStorage.LibraryItem.Kind.SNIPPET, CommandsStorage.LibraryItem.Kind.URL)

            private val CommandsStorage.LibraryItem.Kind.titleRes: Int
                get() =
                    when (this) {
                        CommandsStorage.LibraryItem.Kind.SNIPPET -> R.string.code_snippet
                        CommandsStorage.LibraryItem.Kind.URL -> R.string.external_script
                    }
        }
    }
}
