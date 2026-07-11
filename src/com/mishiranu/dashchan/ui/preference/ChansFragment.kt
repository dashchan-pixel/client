package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.view.View
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.SharedPreferences

class ChansFragment : PreferenceFragment(), FragmentHandler.Callback {
	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		updateList()

		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.forums), null)
	}

	override fun onResume() {
		super.onResume()

		if (!ChanManager.getInstance().availableChans.iterator().hasNext()) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	override fun onChansChanged(changed: Collection<String>, removed: Collection<String>) {
		removeAllPreferences()
		if (!updateList()) {
			(requireActivity() as FragmentHandler).removeFragment()
		}
	}

	private fun updateList(): Boolean {
		var hasChans = false
		val manager = ChanManager.getInstance()
		for (chan in manager.availableChans) {
			val preference = addCategory(chan.configuration.getTitle(), manager.getIcon(chan))
			preference.setOnClickListener {
				(requireActivity() as FragmentHandler).pushFragment(ChanFragment(chan.name))
			}
			hasChans = true
		}
		return hasChans
	}
}
