package com.mishiranu.dashchan.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class InstanceDialog : DialogFragment {
	interface Provider {
		val context: Context
		val activity: FragmentActivity
		val fragmentManager: FragmentManager
		val parentFragment: Fragment?
		val lifecycleOwner: LifecycleOwner
		fun <T : ViewModel> getViewModel(modelClass: Class<T>): T
		fun createDismissDialog(): Dialog
		fun dismiss()
	}

	fun interface Factory {
		fun createDialog(provider: Provider): Dialog
	}

	class InstanceViewModel : ViewModel() {
		var factory: Factory? = null

		override fun onCleared() {
			factory = null
		}
	}

	private var initFactory: Factory? = null

	constructor()

	constructor(fragmentManager: FragmentManager, tag: String?, factory: Factory) {
		if (!fragmentManager.isStateSaved) {
			this.initFactory = factory
			if (tag != null) {
				val oldDialog = fragmentManager.findFragmentByTag(tag) as InstanceDialog?
				oldDialog?.dismiss()
			}
			show(fragmentManager, tag)
		}
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val viewModel = ViewModelProvider(this).get(InstanceViewModel::class.java)
		if (initFactory != null) {
			viewModel.factory = initFactory
			initFactory = null
		}
		return viewModel.factory?.createDialog(provider) ?: DismissDialog(requireContext())
	}

	override fun onViewStateRestored(savedInstanceState: Bundle?) {
		super.onViewStateRestored(savedInstanceState)
		if (dialog is DismissDialog) {
			dismiss()
		}
	}

	private class DismissDialog(context: Context) : Dialog(context) {
		override fun show() {}
	}

	private val provider: Provider = object : Provider {
		override val context: Context
			get() = requireContext()

		override val activity: FragmentActivity
			get() = requireActivity()

		override val fragmentManager: FragmentManager
			get() = parentFragmentManager

		override val parentFragment: Fragment?
			get() = this@InstanceDialog.parentFragment

		override val lifecycleOwner: LifecycleOwner
			get() = this@InstanceDialog

		override fun <T : ViewModel> getViewModel(modelClass: Class<T>): T {
			return ViewModelProvider(this@InstanceDialog).get(modelClass)
		}

		override fun createDismissDialog(): Dialog {
			return DismissDialog(requireContext())
		}

		override fun dismiss() {
			this@InstanceDialog.dismiss()
		}
	}
}
