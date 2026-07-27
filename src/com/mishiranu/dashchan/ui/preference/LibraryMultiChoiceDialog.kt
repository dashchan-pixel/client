package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.CommandsStorage

/**
 * Picks the [libraries][CommandsStorage.LibraryItem] a command loads, the way
 * [ChanMultiChoiceDialog] picks its forums. The names are listed in load order, which is the order
 * they will run in.
 */
class LibraryMultiChoiceDialog :
    DialogFragment,
    DialogInterface.OnMultiChoiceClickListener {
    interface Callback {
        fun onLibrariesSelected(names: Collection<String>)
    }

    constructor()

    constructor(selected: Collection<String>) {
        val args = Bundle()
        args.putStringArrayList(EXTRA_SELECTED, ArrayList(selected))
        arguments = args
    }

    fun show(fragment: Fragment) {
        show(fragment.childFragmentManager, LibraryMultiChoiceDialog::class.java.name)
    }

    private lateinit var names: Array<String>
    private lateinit var checkedItems: BooleanArray

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val names =
            CommandsStorage
                .getInstance()
                .getLibraryItems()
                .map { it.name }
                .toTypedArray()
        this.names = names
        val checkedItems = BooleanArray(names.size)
        this.checkedItems = checkedItems
        val selected =
            if (savedInstanceState != null) {
                savedInstanceState.getStringArrayList(EXTRA_CHECKED)
            } else {
                requireArguments().getStringArrayList(EXTRA_SELECTED)
            }
        val selectedSet: Set<String> = if (selected != null) HashSet(selected) else emptySet()
        for (i in names.indices) {
            checkedItems[i] = names[i] in selectedSet
        }
        return AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.libraries)
            .setMultiChoiceItems(names, checkedItems, this)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (parentFragment as Callback).onLibrariesSelected(collectSelected())
            }.create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXTRA_CHECKED, collectSelected())
    }

    override fun onClick(
        dialog: DialogInterface,
        which: Int,
        isChecked: Boolean,
    ) {
        checkedItems[which] = isChecked
    }

    private fun collectSelected(): ArrayList<String> {
        val result = ArrayList<String>()
        for (i in names.indices) {
            if (checkedItems[i]) {
                result.add(names[i])
            }
        }
        return result
    }

    companion object {
        private const val EXTRA_SELECTED = "selected"
        private const val EXTRA_CHECKED = "checked"
    }
}
