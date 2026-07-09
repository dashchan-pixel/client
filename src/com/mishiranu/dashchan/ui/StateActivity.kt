package com.mishiranu.dashchan.ui

import androidx.fragment.app.FragmentActivity

abstract class StateActivity : FragmentActivity() {
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
		// Force: onFinish must run whenever this instance goes away, including configuration changes.
		callOnFinish(true)
	}

	private fun callOnFinish(force: Boolean) {
		if (!onFinishCalled && (isFinishing || force)) {
			onFinish()
			onFinishCalled = true
		}
	}

	protected open fun onFinish() {}
}
