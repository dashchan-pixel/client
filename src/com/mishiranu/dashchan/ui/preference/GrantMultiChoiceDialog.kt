package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.CommandsStorage

/**
 * Picks what a command may reach beyond its own input — its [grants][CommandsStorage.Grant] — the way
 * [LibraryMultiChoiceDialog] picks its libraries. The order is the order of the enum, which runs from
 * the one a new command already has to the ones it has to be given.
 *
 * The selection travels as the stored keys rather than as ordinals, so a saved instance state still
 * means the same thing if the list ever grows.
 */
class GrantMultiChoiceDialog :
    DialogFragment,
    DialogInterface.OnMultiChoiceClickListener {
    interface Callback {
        fun onGrantsSelected(grants: Collection<CommandsStorage.Grant>)
    }

    constructor()

    constructor(selected: Collection<CommandsStorage.Grant>) {
        val args = Bundle()
        args.putStringArrayList(EXTRA_SELECTED, ArrayList(selected.map { it.key }))
        arguments = args
    }

    fun show(fragment: Fragment) {
        show(fragment.childFragmentManager, GrantMultiChoiceDialog::class.java.name)
    }

    private lateinit var checkedItems: BooleanArray

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val grants = CommandsStorage.Grant.entries
        val checkedItems = BooleanArray(grants.size)
        this.checkedItems = checkedItems
        val selected =
            if (savedInstanceState != null) {
                savedInstanceState.getStringArrayList(EXTRA_CHECKED)
            } else {
                requireArguments().getStringArrayList(EXTRA_SELECTED)
            }
        val selectedSet = CommandsStorage.Grant.fromKeys(selected.orEmpty())
        for (i in grants.indices) {
            checkedItems[i] = grants[i] in selectedSet
        }
        return AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.access)
            .setMultiChoiceItems(grants.map { getString(it.titleRes) }.toTypedArray(), checkedItems, this)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (parentFragment as Callback).onGrantsSelected(collectSelected())
            }.create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXTRA_CHECKED, ArrayList(collectSelected().map { it.key }))
    }

    override fun onClick(
        dialog: DialogInterface,
        which: Int,
        isChecked: Boolean,
    ) {
        checkedItems[which] = isChecked
    }

    private fun collectSelected(): List<CommandsStorage.Grant> = CommandsStorage.Grant.entries.filterIndexed { index, _ -> checkedItems[index] }

    companion object {
        private const val EXTRA_SELECTED = "selected"
        private const val EXTRA_CHECKED = "checked"
    }
}

/**
 * What each grant is called on screen, in the words the app already uses for the same thing — the
 * environment editor's title, the settings screens' and the cookies a forum keeps. Kept at file level
 * so the command editor, which lists the same grants on a row, names them the same way.
 */
val CommandsStorage.Grant.titleRes: Int
    get() =
        when (this) {
            CommandsStorage.Grant.ENVIRONMENT -> R.string.environment
            CommandsStorage.Grant.SETTINGS -> R.string.preferences
            CommandsStorage.Grant.COOKIES -> R.string.cookies
        }
