package com.mishiranu.dashchan.ui.preference

import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.SharedPreferences

class CategoriesFragment : PreferenceFragment() {
	private var compatibilityPreference: Preference<Void>? = null

	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val chans = ChanManager.getInstance().getAvailableChans().iterator()
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
		val compatibilityPreference = addCategory(R.string.compatibility, R.drawable.ic_verified)
		this.compatibilityPreference = compatibilityPreference
		compatibilityPreference.setOnClickListener {
			(requireActivity() as FragmentHandler).pushFragment(CompatibilityFragment())
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
	}

	override fun onDestroyView() {
		super.onDestroyView()
		compatibilityPreference = null
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.preferences), null)
	}

	override fun onResume() {
		super.onResume()

		val hasIssues = !Settings.canDrawOverlays(requireContext())
		setCategoryTint(compatibilityPreference!!, if (hasIssues) ColorStateList.valueOf(ResourceUtils
				.getColor(requireContext(), R.attr.colorTextError)) else null)
	}
}
