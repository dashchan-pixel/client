package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.view.View
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.SharedPreferences

class CategoriesFragment : PreferenceFragment() {
	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES!!

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val chans = ChanManager.getInstance().availableChans.iterator()
		val hasChan = chans.hasNext()
		val singleChanName = if (hasChan) chans.next().name else null
		val hasMultipleChans = hasChan && chans.hasNext()
		addCategory(R.string.general, R.drawable.ic_map)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(GeneralFragment()) }
		if (hasMultipleChans) {
			addCategory(R.string.forums, R.drawable.ic_public)
					.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(ChansFragment()) }
		} else if (hasChan) {
			addCategory(R.string.forum, R.drawable.ic_public)
					.setOnClickListener {
						(requireActivity() as FragmentHandler).pushFragment(ChanFragment(singleChanName))
					}
		}
		addCategory(R.string.user_interface, R.drawable.ic_color_lens)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(InterfaceFragment()) }
		addCategory(R.string.contents, R.drawable.ic_local_library)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(ContentsFragment()) }
		addCategory(R.string.media, R.drawable.ic_save)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(MediaFragment()) }
		addCategory(R.string.autohide, R.drawable.ic_custom_fork)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(AutohideFragment()) }
		addCategory(R.string.about, R.drawable.ic_info)
				.setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(AboutFragment()) }

		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.preferences), null)
	}

}
