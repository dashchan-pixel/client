package com.mishiranu.dashchan.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

abstract class StateActivity : FragmentActivity() {
	class InstanceFragment : Fragment() {
		override fun onDetach() {
			(activity as StateActivity).callOnFinish(true)
			super.onDetach()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val tag = "instance"
		val fragmentManager = supportFragmentManager
		var fragment = fragmentManager.findFragmentByTag(tag) as InstanceFragment?
		if (fragment == null) {
			fragment = InstanceFragment()
			@Suppress("DEPRECATION")
			fragment.retainInstance = true
			fragmentManager.beginTransaction().add(fragment, tag).commit()
		}
	}

	private var onFinishCalled = false

	override fun recreate() {
		super.recreate()
		callOnFinish(true)
	}

	override fun onPause() {
		super.onPause()
		callOnFinish(false)
	}

	override fun onStop() {
		super.onStop()
		callOnFinish(false)
	}

	override fun onDestroy() {
		super.onDestroy()
		callOnFinish(false)
	}

	private fun callOnFinish(force: Boolean) {
		if (!onFinishCalled && (isFinishing || force)) {
			onFinish()
			onFinishCalled = true
		}
	}

	protected open fun onFinish() {}
}
