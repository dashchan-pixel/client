package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R

class ChanMultiChoiceDialog : DialogFragment, DialogInterface.OnMultiChoiceClickListener {
	interface Callback {
		fun onChansSelected(chanNames: Collection<String>)
	}

	constructor()

	constructor(selected: Collection<String>) {
		val args = Bundle()
		args.putStringArrayList(EXTRA_SELECTED, ArrayList(selected))
		arguments = args
	}

	fun show(fragment: Fragment) {
		show(fragment.childFragmentManager, ChanMultiChoiceDialog::class.java.name)
	}

	private lateinit var chanNames: Array<String?>
	private lateinit var checkedItems: BooleanArray

	override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
		val chans = ArrayList<Chan>()
		for (chan in ChanManager.getInstance().getAvailableChans()) {
			chans.add(chan)
		}
		val chanNames = arrayOfNulls<String>(chans.size)
		val items = arrayOfNulls<String>(chans.size)
		for (i in chans.indices) {
			val chan = chans[i]
			chanNames[i] = chan.name
			items[i] = chan.configuration.getTitle()
		}
		this.chanNames = chanNames
		val checkedItems = BooleanArray(chans.size)
		this.checkedItems = checkedItems
		val selected: Collection<String>? = if (savedInstanceState != null) {
			savedInstanceState.getStringArrayList(EXTRA_CHECKED)
		} else {
			requireArguments().getStringArrayList(EXTRA_SELECTED)
		}
		val selectedSet: Set<String> = if (selected != null) HashSet(selected) else emptySet()
		for (i in chans.indices) {
			checkedItems[i] = selectedSet.contains(chanNames[i])
		}
		val dialog = AlertDialog.Builder(requireContext())
				.setMultiChoiceItems(items, checkedItems, this)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					(parentFragment as Callback).onChansSelected(collectSelected())
				}
				.create()
		updateTitle(dialog)
		return dialog
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putStringArrayList(EXTRA_CHECKED, collectSelected())
	}

	override fun onClick(dialog: DialogInterface, which: Int, isChecked: Boolean) {
		checkedItems[which] = isChecked
		updateTitle(dialog as AlertDialog)
	}

	private fun updateTitle(dialog: AlertDialog) {
		var singleChanName: String? = null
		var checkedCount = 0
		for (i in chanNames.indices) {
			if (checkedItems[i]) {
				singleChanName = chanNames[i]
				checkedCount++
			}
		}
		dialog.setTitle(when {
			checkedCount >= 2 -> getString(R.string.multiple_forums)
			checkedCount == 1 -> getString(R.string.forum_only__format,
					StringUtils.emptyIfNull(Chan.get(singleChanName).configuration.getTitle()))
			else -> getString(R.string.all_forums)
		})
	}

	private fun collectSelected(): ArrayList<String> {
		val result = ArrayList<String>()
		for (i in chanNames.indices) {
			if (checkedItems[i]) {
				chanNames[i]?.let { result.add(it) }
			}
		}
		return result
	}

	companion object {
		private const val EXTRA_SELECTED = "selected"
		private const val EXTRA_CHECKED = "checked"
	}
}
