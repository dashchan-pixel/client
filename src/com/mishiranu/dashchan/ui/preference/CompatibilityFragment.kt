package com.mishiranu.dashchan.ui.preference

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.CheckPreference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ClickableToast

class CompatibilityFragment : PreferenceFragment() {
	private var drawOverOtherApplicationsPreference: CheckPreference? = null

	override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val preference = addCheck(false, "draw_over_other_applications", false,
				R.string.draw_over_other_applications, R.string.draw_over_other_applications__summary)
		drawOverOtherApplicationsPreference = preference
		preference.setOnClickListener {
			try {
				startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
						.setData(Uri.parse("package:" + requireContext().packageName)))
			} catch (e: ActivityNotFoundException) {
				ClickableToast.show(R.string.unknown_address)
			}
		}

		addHeader(R.string.additional)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		drawOverOtherApplicationsPreference = null
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)
		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.compatibility), null)
	}

	override fun onResume() {
		super.onResume()

		drawOverOtherApplicationsPreference?.setValue(Settings.canDrawOverlays(requireContext()))
	}
}
