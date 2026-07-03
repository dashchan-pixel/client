package com.mishiranu.dashchan.widget

import android.view.MenuItem

class MenuExpandListener(private val callback: Callback) : MenuItem.OnActionExpandListener {
	fun interface Callback {
		fun onChange(menuItem: MenuItem, expand: Boolean): Boolean
	}

	override fun onMenuItemActionExpand(menuItem: MenuItem): Boolean {
		return callback.onChange(menuItem, true)
	}

	override fun onMenuItemActionCollapse(menuItem: MenuItem): Boolean {
		return callback.onChange(menuItem, false)
	}
}
