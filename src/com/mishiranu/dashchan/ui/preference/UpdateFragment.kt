package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.UpdaterActivity
import com.mishiranu.dashchan.content.async.ReadUpdateTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.preference.core.CheckPreference
import com.mishiranu.dashchan.util.AndroidUtils
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory

class UpdateFragment : BaseListFragment {
	private var updateDataMap: ReadUpdateTask.UpdateDataMap? = null
	private var updateErrorItem: ErrorItem? = null

	private var progressView: View? = null

	internal class ListItem(
			@JvmField val extensionName: String,
			@JvmField val title: String?,
			@JvmField val enabled: Boolean,
			@JvmField val installed: Boolean
	) {
		@JvmField var target: String? = null
		@JvmField var targetIndex = 0
		@JvmField var warning: String? = null

		fun isHeader(): Boolean = StringUtils.isEmpty(extensionName)

		fun willBeInstalled(): Boolean =
				!isHeader() && (installed && targetIndex > 0 || !installed && targetIndex >= 0)

		fun setTarget(context: Context?, applicationItem: ReadUpdateTask.ApplicationItem, targetIndex: Int) {
			this.targetIndex = targetIndex
			if (installed && targetIndex > 0 || !installed && targetIndex >= 0) {
				val packageItem = applicationItem.packageItems[targetIndex]
				var target = packageItem.title
				if (context != null) {
					target = context.getString(R.string.__enumeration_format, target, packageItem.versionName)
					if (packageItem.length > 0) {
						target = context.getString(R.string.__enumeration_format, target,
								StringUtils.formatFileSize(packageItem.length, false))
					}
				}
				this.target = target
			} else if (targetIndex == 0) {
				target = if (context != null) context.getString(R.string.keep_current_version) else null
			} else {
				target = if (context != null) context.getString(R.string.dont_install) else null
			}
		}

		companion object {
			fun create(extensionName: String, extensionTitle: String?,
					enabled: Boolean, installed: Boolean): ListItem {
				var title = Chan.get(extensionName).configuration.getTitle()
				if (title == null) {
					title = extensionTitle
				}
				return ListItem(extensionName, title, enabled, installed)
			}
		}
	}

	constructor()

	constructor(updateDataMap: ReadUpdateTask.UpdateDataMap) {
		val args = Bundle()
		args.putParcelable(EXTRA_UPDATE_DATA_MAP, updateDataMap)
		arguments = args
	}

	private fun updateTitle() {
		var count = 0
		if (updateDataMap != null) {
			val adapter = getRecyclerView()!!.adapter as Adapter
			for (listItem in adapter.listItems) {
				if (listItem.willBeInstalled()) {
					count++
				}
			}
		}
		(requireActivity() as FragmentHandler).setTitleSubtitle(if (count <= 0) getString(R.string.updates)
				else ResourceUtils.getColonString(resources, R.string.updates, count), null)
	}

	private fun isUpdateDataProvided(): Boolean {
		val args = arguments
		return args != null && args.containsKey(EXTRA_UPDATE_DATA_MAP)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
			savedInstanceState: Bundle?): View {
		val layout = super.onCreateView(inflater, container, savedInstanceState) as ExpandedLayout
		progressView = ViewFactory.createProgressLayout(layout)
		return layout
	}

	override fun onDestroyView() {
		super.onDestroyView()
		progressView = null
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (isUpdateDataProvided()) {
			updateDataMap = BundleCompat.getParcelable(requireArguments(), EXTRA_UPDATE_DATA_MAP, ReadUpdateTask.UpdateDataMap::class.java)
		} else {
			updateDataMap = if (savedInstanceState != null)
					BundleCompat.getParcelable(savedInstanceState, EXTRA_UPDATE_DATA_MAP, ReadUpdateTask.UpdateDataMap::class.java) else null
			updateErrorItem = if (savedInstanceState != null)
					BundleCompat.getParcelable(savedInstanceState, EXTRA_UPDATE_ERROR_ITEM, ErrorItem::class.java) else null
			val updateErrorItem = this.updateErrorItem
			if (updateErrorItem != null) {
				setErrorText(updateErrorItem.toString())
			} else if (updateDataMap == null) {
				progressView!!.visibility = View.VISIBLE
				val viewModel = ViewModelProvider(this).get(UpdateViewModel::class.java)
				if (!viewModel.hasTaskOrValue()) {
					val task = ReadUpdateTask(requireContext(), viewModel.callback)
					task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
					viewModel.attach(task)
				}
				viewModel.observe(viewLifecycleOwner) { updateDataMap, errorItem ->
					progressView!!.visibility = View.GONE
					if (updateDataMap != null) {
						this.updateDataMap = updateDataMap
						val adapter = getRecyclerView()!!.adapter as Adapter
						adapter.listItems = buildData(requireContext(), updateDataMap, null)
						adapter.notifyDataSetChanged()
						updateTitle()
					} else {
						this@UpdateFragment.updateErrorItem = errorItem
						setErrorText(errorItem.toString())
					}
				}
			}
		}
		val adapter = Adapter(getRecyclerView()!!.context, this::onItemClick)
		getRecyclerView()!!.adapter = adapter
		if (updateDataMap != null) {
			adapter.listItems = buildData(requireContext(), updateDataMap!!, savedInstanceState)
		}
		updateTitle()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)

		val recyclerView = getRecyclerView()
		if (recyclerView != null) {
			val adapter = recyclerView.adapter as Adapter
			for (listItem in adapter.listItems) {
				outState.putInt(EXTRA_TARGET_PREFIX + listItem.extensionName, listItem.targetIndex)
			}
		}
		if (!isUpdateDataProvided()) {
			outState.putParcelable(EXTRA_UPDATE_DATA_MAP, updateDataMap)
			outState.putParcelable(EXTRA_UPDATE_ERROR_ITEM, updateErrorItem)
		}
	}

	override fun configureDivider(configuration: DividerItemDecoration.Configuration,
			position: Int): DividerItemDecoration.Configuration {
		return (getRecyclerView()!!.adapter as Adapter).configureDivider(configuration, position)
	}

	private fun onItemClick(listItem: ListItem) {
		val targets = ArrayList<String>()
		val repositories = ArrayList<String?>()
		val targetIndex: Int
		val applicationItem = updateDataMap!!.get(listItem.extensionName, listItem.installed)
		if (listItem.installed) {
			targets.add(getString(R.string.keep_current_version))
			repositories.add(null)
			for (packageItem in applicationItem.packageItems.subList(1, applicationItem.packageItems.size)) {
				targets.add(packageItem.title)
				repositories.add(packageItem.repository)
			}
			targetIndex = listItem.targetIndex
		} else {
			targets.add(getString(R.string.dont_install))
			repositories.add(null)
			for (packageItem in applicationItem.packageItems) {
				targets.add(packageItem.title)
				repositories.add(packageItem.repository)
			}
			targetIndex = listItem.targetIndex + 1
		}
		val dialog = TargetDialog(listItem.extensionName, listItem.title,
				targets, repositories, targetIndex)
		dialog.show(childFragmentManager, TargetDialog.TAG)
	}

	override fun onCreateOptionsMenu(menu: Menu, primary: Boolean) {
		menu.add(0, R.id.menu_download, 0, R.string.download_files)
				.setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionDownload))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.add(0, R.id.menu_check_on_start, 0, R.string.check_on_startup).setCheckable(true)
	}

	override fun onPrepareOptionsMenu(menu: Menu, primary: Boolean) {
		var length = 0L
		val recyclerView = getRecyclerView()
		if (updateDataMap != null && recyclerView != null) {
			val adapter = recyclerView.adapter as Adapter
			for (listItem in adapter.listItems) {
				if (listItem.willBeInstalled()) {
					length += updateDataMap!!.get(listItem.extensionName, listItem.installed)
							.packageItems[listItem.targetIndex].length
				}
			}
		}
		var downloadTitle = getString(R.string.download_files)
		if (length > 0) {
			downloadTitle = getString(R.string.__enumeration_format, downloadTitle,
					StringUtils.formatFileSize(length, false))
		}
		menu.findItem(R.id.menu_download).setTitle(downloadTitle)
		menu.findItem(R.id.menu_check_on_start).setChecked(Preferences.isCheckUpdatesOnStart())
	}

	override fun onMenuItemSelected(item: MenuItem): Boolean {
		val itemId = item.itemId
		if (itemId == R.id.menu_download) {
			val requests = ArrayList<UpdaterActivity.Request>()
			if (updateDataMap != null) {
				val adapter = getRecyclerView()!!.adapter as Adapter
				for (listItem in adapter.listItems) {
					if (listItem.willBeInstalled()) {
						val packageItem = updateDataMap!!.get(listItem.extensionName, listItem.installed)
								.packageItems[listItem.targetIndex]
						if (packageItem.source != null) {
							requests.add(UpdaterActivity.Request(listItem.extensionName,
									packageItem.versionName, packageItem.source,
									packageItem.sha256sum, packageItem.fingerprints))
						}
					}
				}
			}
			if (!requests.isEmpty()) {
				displayUpdateReminderDialog(childFragmentManager)
				UpdaterActivity.startUpdater(requests)
			} else {
				ClickableToast.show(R.string.no_available_updates)
			}
			return true
		} else if (itemId == R.id.menu_check_on_start) {
			Preferences.setCheckUpdatesOnStart(!item.isChecked)
		}
		return false
	}

	private fun onTargetSelected(extensionName: String, selectedIndex: Int) {
		var targetIndex = selectedIndex
		val adapter = getRecyclerView()!!.adapter as Adapter
		for (i in adapter.listItems.indices) {
			val listItem = adapter.listItems[i]
			if (extensionName == listItem.extensionName) {
				val applicationItem = updateDataMap!!.get(extensionName, listItem.installed)
				if (!listItem.installed) {
					targetIndex--
				}
				if (listItem.targetIndex != targetIndex) {
					listItem.setTarget(requireContext(), applicationItem, targetIndex)
					onTargetChanged(requireContext(), adapter, updateDataMap!!, listItem)
					adapter.notifyDataSetChanged()
					invalidateOptionsMenu()
					updateTitle()
				}
				break
			}
		}
	}

	class UpdateViewModel : TaskViewModel.Proxy<ReadUpdateTask, ReadUpdateTask.Callback>()

	private class Adapter(context: Context, private val callback: Callback) :
			RecyclerView.Adapter<RecyclerView.ViewHolder>() {
		private enum class ViewType { ITEM, HEADER }

		fun interface Callback : ListViewUtils.ClickCallback<ListItem, RecyclerView.ViewHolder> {
			fun onItemClick(listItem: ListItem)

			override fun onItemClick(holder: RecyclerView.ViewHolder, position: Int,
					listItem: ListItem?, longClick: Boolean): Boolean {
				onItemClick(listItem!!)
				return true
			}
		}

		private val checkPreference = CheckPreference(context, "", false, "title", "summary")

		internal var listItems: List<ListItem> = emptyList()

		fun configureDivider(configuration: DividerItemDecoration.Configuration,
				position: Int): DividerItemDecoration.Configuration {
			val current = listItems[position]
			val next = if (listItems.size > position + 1) listItems[position + 1] else null
			return configuration.need(!current.isHeader() && (next == null || !next.isHeader() || true))
		}

		override fun getItemCount(): Int = listItems.size

		override fun getItemViewType(position: Int): Int =
				(if (listItems[position].isHeader()) ViewType.HEADER else ViewType.ITEM).ordinal

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			return when (ViewType.values()[viewType]) {
				ViewType.ITEM -> {
					val viewHolder = checkPreference.createViewHolder(parent)
					ViewUtils.setSelectableItemBackground(viewHolder.view)
					viewHolder.view.tag = viewHolder
					ListViewUtils.bind<ListItem, RecyclerView.ViewHolder>(
							SimpleViewHolder(viewHolder.view), false, listItems::get, callback)
				}
				ViewType.HEADER -> SimpleViewHolder(ViewFactory.makeListTextHeader(parent))
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val listItem = listItems[position]
			when (ViewType.values()[holder.itemViewType]) {
				ViewType.ITEM -> {
					val viewHolder = holder.itemView.tag as CheckPreference.CheckViewHolder
					checkPreference.setValue(listItem.willBeInstalled())
					checkPreference.setEnabled(listItem.enabled)
					checkPreference.bindViewHolder(viewHolder)
					viewHolder.title.text = listItem.title
					if (listItem.warning != null) {
						val spannable = SpannableString(listItem.target + "\n" + listItem.warning)
						val length = spannable.length
						spannable.setSpan(ForegroundColorSpan(ResourceUtils.getColor(holder.itemView.context,
								R.attr.colorTextError)), length - listItem.warning!!.length, length,
								Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
						viewHolder.summary.text = spannable
					} else {
						viewHolder.summary.text = listItem.target
					}
				}
				ViewType.HEADER -> (holder.itemView as TextView).text = listItem.title
			}
		}
	}

	class TargetDialog : DialogFragment, DialogInterface.OnClickListener {
		constructor()

		constructor(extensionName: String, title: String?, targets: ArrayList<String>,
				repositories: ArrayList<String?>, index: Int) {
			val args = Bundle()
			args.putString(EXTRA_EXTENSION_NAME, extensionName)
			args.putString(EXTRA_TITLE, title)
			args.putStringArrayList(EXTRA_TARGETS, targets)
			args.putStringArrayList(EXTRA_REPOSITORIES, repositories)
			args.putInt(EXTRA_INDEX, index)
			arguments = args
		}

		override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
			val index = requireArguments().getInt(EXTRA_INDEX)
			val targets = requireArguments().getStringArrayList(EXTRA_TARGETS)!!
			val repositories = requireArguments().getStringArrayList(EXTRA_REPOSITORIES)!!
			val titles = arrayOfNulls<CharSequence>(targets.size)
			val referenceParent = FrameLayout(requireContext())
			val referenceSubtitle = ViewFactory.makeTwoLinesListItem(referenceParent, 0).text2
			for (i in titles.indices) {
				val target = targets[i]
				val repository = repositories[i]
				if (StringUtils.isEmpty(repository)) {
					titles[i] = target
				} else {
					val builder = SpannableStringBuilder(target)
					builder.append('\n')
					builder.append(repository)
					val from = builder.length - repository!!.length
					val to = builder.length
					builder.setSpan(ForegroundColorSpan(referenceSubtitle.textColors.defaultColor),
							from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
					builder.setSpan(AbsoluteSizeSpan((referenceSubtitle.textSize + 0.5f).toInt()),
							from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
					titles[i] = builder
				}
			}
			return AlertDialog.Builder(requireContext())
					.setTitle(requireArguments().getString(EXTRA_TITLE))
					.setSingleChoiceItems(titles, index, this)
					.setNegativeButton(android.R.string.cancel, null).create()
		}

		override fun onClick(dialog: DialogInterface, which: Int) {
			dismiss()
			(parentFragment as UpdateFragment)
					.onTargetSelected(requireArguments().getString(EXTRA_EXTENSION_NAME)!!, which)
		}

		companion object {
			val TAG: String = TargetDialog::class.java.name

			private const val EXTRA_EXTENSION_NAME = "extensionName"
			private const val EXTRA_TITLE = "title"
			private const val EXTRA_TARGETS = "targets"
			private const val EXTRA_REPOSITORIES = "repositories"
			private const val EXTRA_INDEX = "index"
		}
	}

	companion object {
		private const val VERSION_TITLE_RELEASE = "Release"

		private const val EXTRA_UPDATE_DATA_MAP = "updateDataMap"
		private const val EXTRA_UPDATE_ERROR_ITEM = "updateErrorItem"
		private const val EXTRA_TARGET_PREFIX = "target_"

		private fun checkVersionValid(applicationItem: ReadUpdateTask.ApplicationItem,
				packageItem: ReadUpdateTask.PackageItem, minApiVersion: Int, maxApiVersion: Int): Boolean {
			return applicationItem.type != ReadUpdateTask.ApplicationItem.Type.CHAN ||
					packageItem.apiVersion in minApiVersion..maxApiVersion
		}

		private fun compareForUpdates(installed: ReadUpdateTask.PackageItem,
				update: ReadUpdateTask.PackageItem): Boolean {
			return update.versionCode > installed.versionCode ||
					!CommonUtils.equals(installed.versionName, update.versionName)
		}

		private fun handleAddListItem(context: Context?, applicationItem: ReadUpdateTask.ApplicationItem,
				savedInstanceState: Bundle?, minApiVersion: Int, maxApiVersion: Int,
				installed: Boolean, warningUnsupported: String?): ListItem {
			val listItem = ListItem.create(applicationItem.name, applicationItem.title,
					applicationItem.packageItems.size >= (if (installed) 2 else 1), installed)
			var targetIndex = if (savedInstanceState != null)
					savedInstanceState.getInt(EXTRA_TARGET_PREFIX + applicationItem.name, -1) else -1
			if (targetIndex < 0) {
				if (installed) {
					val installedExtensionData = applicationItem.packageItems[0]
					if (checkVersionValid(applicationItem, installedExtensionData, minApiVersion, maxApiVersion)) {
						targetIndex = 0
					}
					for (i in 1 until applicationItem.packageItems.size) {
						val updatePackageItem = applicationItem.packageItems[i]
						if (checkVersionValid(applicationItem, updatePackageItem, minApiVersion, maxApiVersion)) {
							// targetIndex < 0 - means installed version is not supported
							if (targetIndex < 0 || VERSION_TITLE_RELEASE == updatePackageItem.title
									&& compareForUpdates(installedExtensionData, updatePackageItem)) {
								targetIndex = i
								break
							}
						}
					}
					if (targetIndex < 0) {
						targetIndex = 0
						listItem.warning = warningUnsupported
					}
				}
			} else {
				// Restore state
				val packageItem = applicationItem.packageItems[targetIndex]
				if (!checkVersionValid(applicationItem, packageItem, minApiVersion, maxApiVersion)) {
					listItem.warning = warningUnsupported
				}
			}
			listItem.setTarget(context, applicationItem, targetIndex)
			return listItem
		}

		private val UPDATE_DATA_COMPARATOR = Comparator<ReadUpdateTask.ApplicationItem> { lhs, rhs ->
			var result = lhs.type.compareTo(rhs.type)
			if (result != 0) {
				return@Comparator result
			}
			result = lhs.title.compareTo(rhs.title)
			if (result != 0) {
				return@Comparator result
			}
			lhs.name.compareTo(rhs.name)
		}

		private fun collectSorted(updateDataMap: ReadUpdateTask.UpdateDataMap,
				installed: Boolean): ArrayList<ReadUpdateTask.ApplicationItem> {
			val applicationItems = ArrayList<ReadUpdateTask.ApplicationItem>()
			for (extensionName in updateDataMap.extensionNames(installed)) {
				applicationItems.add(updateDataMap.get(extensionName, installed))
			}
			applicationItems.sortWith(UPDATE_DATA_COMPARATOR)
			return applicationItems
		}

		private fun buildData(context: Context?, updateDataMap: ReadUpdateTask.UpdateDataMap,
				savedInstanceState: Bundle?): List<ListItem> {
			val listItems = ArrayList<ListItem>()
			val warningUnsupported = context?.getString(R.string.unsupported_version)
			val handledExtensionNames = HashSet<String?>()
			val minApiVersion: Int
			val maxApiVersion: Int
			run {
				val applicationItem = updateDataMap.get(ChanManager.EXTENSION_NAME_CLIENT, true)
				val listItem = ListItem(ChanManager.EXTENSION_NAME_CLIENT,
						if (context != null) AndroidUtils.getApplicationLabel(context) else null,
						applicationItem.packageItems.size >= 2, true)
				var targetIndex = if (savedInstanceState != null)
						savedInstanceState.getInt(EXTRA_TARGET_PREFIX + listItem.extensionName, -1) else -1
				if (targetIndex < 0) {
					targetIndex = 0
					for (i in 1 until applicationItem.packageItems.size) {
						val updatePackageItem = applicationItem.packageItems[i]
						if (VERSION_TITLE_RELEASE == updatePackageItem.title &&
								compareForUpdates(applicationItem.packageItems[0], updatePackageItem)) {
							targetIndex = 1
							break
						}
					}
				}
				listItem.setTarget(context, applicationItem, targetIndex)
				val currentApplicationPackageItem = applicationItem.packageItems[targetIndex]
				minApiVersion = currentApplicationPackageItem.minApiVersion
				maxApiVersion = currentApplicationPackageItem.maxApiVersion
				listItems.add(listItem)
			}
			handledExtensionNames.add(ChanManager.EXTENSION_NAME_CLIENT)
			val manager = ChanManager.getInstance()
			for (extensionItem in manager.getExtensionItems()) {
				if (extensionItem.type == ChanManager.ExtensionItem.Type.LIBRARY) {
					val applicationItem = updateDataMap.get(extensionItem.name, true)
					if (applicationItem != null) {
						val listItem = handleAddListItem(context, applicationItem,
								savedInstanceState, minApiVersion, maxApiVersion, true, warningUnsupported)
						listItems.add(listItem)
						handledExtensionNames.add(extensionItem.name)
					}
				}
			}
			for (chan in manager.getAvailableChans()) {
				val applicationItem = updateDataMap.get(chan.name, true)
				if (applicationItem != null) {
					val listItem = handleAddListItem(context, applicationItem,
							savedInstanceState, minApiVersion, maxApiVersion, true, warningUnsupported)
					listItems.add(listItem)
					handledExtensionNames.add(chan.name)
				}
			}
			for (applicationItem in collectSorted(updateDataMap, true)) {
				if (!handledExtensionNames.contains(applicationItem.name)) {
					val listItem = handleAddListItem(context, applicationItem, savedInstanceState,
							minApiVersion, maxApiVersion, true, warningUnsupported)
					listItems.add(listItem)
					handledExtensionNames.add(applicationItem.name)
				}
			}
			var availableHeaderAdded = false
			for (applicationItem in collectSorted(updateDataMap, false)) {
				if (!handledExtensionNames.contains(applicationItem.name)) {
					if (!availableHeaderAdded) {
						if (context != null) {
							listItems.add(ListItem("", context.getString(R.string.available__plural), false, false))
						}
						availableHeaderAdded = true
					}
					val listItem = handleAddListItem(context, applicationItem, savedInstanceState,
							minApiVersion, maxApiVersion, false, warningUnsupported)
					listItems.add(listItem)
					handledExtensionNames.add(applicationItem.name)
				}
			}
			return listItems
		}

		private fun displayUpdateReminderDialog(fragmentManager: FragmentManager) {
			InstanceDialog(fragmentManager, null) { provider ->
				AlertDialog.Builder(provider.context)
						.setMessage(R.string.update_reminder__sentence)
						.setPositiveButton(android.R.string.ok) { _, _ ->
							(provider.activity as FragmentHandler).removeFragment()
						}
						.setOnCancelListener { (provider.activity as FragmentHandler).removeFragment() }
						.create()
			}
		}

		private fun handleListItemValidity(updateDataMap: ReadUpdateTask.UpdateDataMap,
				listItem: ListItem, minApiVersion: Int, maxApiVersion: Int, warningUnsupported: String?) {
			var valid = true
			if (listItem.targetIndex >= 0) {
				val applicationItem = updateDataMap.get(listItem.extensionName, listItem.installed)
				val packageItem = applicationItem.packageItems[listItem.targetIndex]
				valid = checkVersionValid(applicationItem, packageItem, minApiVersion, maxApiVersion)
			}
			listItem.warning = if (valid) null else warningUnsupported
		}

		private fun onTargetChanged(context: Context, adapter: Adapter,
				updateDataMap: ReadUpdateTask.UpdateDataMap, listItem: ListItem) {
			val warningUnsupported = context.getString(R.string.unsupported_version)
			val applicationListItem = adapter.listItems[0]
			if (ChanManager.EXTENSION_NAME_CLIENT != applicationListItem.extensionName) {
				throw IllegalStateException()
			}
			val applicationPackageItem = updateDataMap
					.get(ChanManager.EXTENSION_NAME_CLIENT, true).packageItems[applicationListItem.targetIndex]
			val minApiVersion = applicationPackageItem.minApiVersion
			val maxApiVersion = applicationPackageItem.maxApiVersion
			if (ChanManager.EXTENSION_NAME_CLIENT == listItem.extensionName) {
				for (invalidateListItem in adapter.listItems) {
					if (!invalidateListItem.isHeader()) {
						handleListItemValidity(updateDataMap, invalidateListItem,
								minApiVersion, maxApiVersion, warningUnsupported)
					}
				}
			} else {
				handleListItemValidity(updateDataMap, listItem, minApiVersion, maxApiVersion, warningUnsupported)
			}
		}

		@JvmStatic
		fun checkNewVersions(updateDataMap: ReadUpdateTask.UpdateDataMap): Int {
			var count = 0
			val listItems = buildData(null, updateDataMap, null)
			for (listItem in listItems) {
				if (listItem.willBeInstalled()) {
					count++
				}
			}
			return count
		}
	}
}
