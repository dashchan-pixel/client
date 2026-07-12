package com.mishiranu.dashchan.ui.navigator.page

import android.os.Parcel
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import androidx.recyclerview.widget.LinearLayoutManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.async.ReadSearchTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.ui.navigator.adapter.SearchAdapter
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.HeaderItemDecoration
import com.mishiranu.dashchan.widget.PullableWrapper

class SearchPage : ListPage(), SearchAdapter.Callback, UiManager.Observer, ReadSearchTask.Callback {
	private class RetainableExtra : Retainable {
		val postItems = ArrayList<PostItem>()
		var pageNumber = 0

		var dialogsState: DialogUnit.StackInstance.State? = null

		override fun clear() {
			dialogsState?.dropState()
			dialogsState = null
		}

		companion object {
			val FACTORY = ExtraFactory { RetainableExtra() }
		}
	}

	private class ParcelableExtra : Parcelable {
		var groupMode = false

		override fun describeContents(): Int = 0

		override fun writeToParcel(dest: Parcel, flags: Int) {
			dest.writeByte(if (groupMode) 1 else 0)
		}

		companion object {
			val FACTORY = ExtraFactory { ParcelableExtra() }

			@JvmField
			val CREATOR = object : Parcelable.Creator<ParcelableExtra> {
				override fun createFromParcel(source: Parcel): ParcelableExtra {
					val parcelableExtra = ParcelableExtra()
					parcelableExtra.groupMode = source.readByte().toInt() != 0
					return parcelableExtra
				}

				override fun newArray(size: Int): Array<ParcelableExtra?> = arrayOfNulls(size)
			}
		}
	}

	class ReadViewModel : TaskViewModel.Proxy<ReadSearchTask, ReadSearchTask.Callback>()

	private fun getAdapter(): SearchAdapter = getRecyclerView().adapter as SearchAdapter

	override fun onCreate() {
		val recyclerView = getRecyclerView()
		recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
		val page = getPage()
		val uiManager = uiManager
		uiManager!!.view().bindThreadsPostRecyclerView(recyclerView)
		val density = ResourceUtils.obtainDensity(resources)
		val dividerPadding = (12f * density).toInt()
		val adapter = SearchAdapter(context, this, page.chanName,
				uiManager, fragmentManager, page.searchQuery)
		recyclerView.adapter = adapter
		recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context) { c, position ->
			adapter.configureDivider(c, position).horizontal(dividerPadding, dividerPadding)
		})
		recyclerView.addItemDecoration(HeaderItemDecoration(adapter::configureItemHeader)
				{ _, position -> adapter.getItemHeader(position) })
		recyclerView.pullable!!.setPullSides(PullableWrapper.Side.BOTH)
		uiManager!!.observable().register(this)

		val initRequest = getInitRequest()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
		val readViewModel = getViewModel(ReadViewModel::class.java)
		if (initRequest.shouldLoad) {
			parcelableExtra.groupMode = false
		}
		adapter.setGroupMode(parcelableExtra.groupMode)
		val listPosition = takeListPosition()
		if (initRequest.errorItem != null) {
			switchError(initRequest.errorItem)
		} else {
			var load = true
			if (!initRequest.shouldLoad && retainableExtra.postItems.isNotEmpty()) {
				load = false
				adapter.setItems(retainableExtra.postItems)
				listPosition?.apply(recyclerView)
				val dialogsState = retainableExtra.dialogsState
				if (dialogsState != null) {
					uiManager!!.dialog().restoreState(adapter.configurationSet, dialogsState)
					dialogsState.dropState()
					retainableExtra.dialogsState = null
				}
			}
			if (readViewModel.hasTaskOrValue()) {
				if (adapter.itemCount == 0) {
					recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
					switchProgress()
				} else {
					val task = readViewModel.task
					val bottom = task != null && task.pageNumber > 0
					recyclerView.pullable!!.startBusyState(if (bottom)
							PullableWrapper.Side.BOTTOM else PullableWrapper.Side.TOP)
				}
			} else if (load) {
				retainableExtra.postItems.clear()
				retainableExtra.pageNumber = 0
				refreshSearch(showPull = false, nextPage = false)
			}
		}
		readViewModel.observe(this, this)
	}

	override fun onResume() {
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		retainableExtra.dialogsState?.dropState()
		retainableExtra.dialogsState = null
	}

	override fun onDestroy() {
		uiManager!!.observable().unregister(this)
	}

	override fun onNotifyAllAdaptersChanged() {
		uiManager!!.dialog().notifyDataSetChangedToAll(getAdapter().configurationSet.stackInstance)
	}

	override fun onRequestStoreExtra(saveToStack: Boolean) {
		val adapter = getAdapter()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		retainableExtra.dialogsState?.dropState()
		retainableExtra.dialogsState = adapter.configurationSet.stackInstance!!.collectState()
	}

	override fun obtainTitle(): String = getPage().searchQuery!!

	override fun onItemClick(postItem: PostItem?) {
		val page = getPage()
		uiManager!!.navigator()!!.navigatePosts(page.chanName, page.boardName,
				postItem!!.threadNumber, postItem.getPostNumber(), null)
	}

	override fun onItemLongClick(postItem: PostItem?): Boolean {
		uiManager!!.interaction().handlePostContextMenu(getAdapter().configurationSet, postItem!!)
		return true
	}

	private var allowSearch = false

	override fun onCreateOptionsMenu(menu: Menu) {
		menu.add(0, R.id.menu_search, 0, R.string.search)
				.setIcon(getActionBarIcon(R.attr.iconActionSearch))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
		menu.add(0, R.id.menu_group, 0, R.string.group).isCheckable = true
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
	}

	override fun onPrepareOptionsMenu(menu: Menu) {
		val board = chan.configuration.safe().obtainBoard(getPage().boardName)
		this.allowSearch = board.allowSearch
		menu.findItem(R.id.menu_search).isVisible = board.allowSearch
		menu.findItem(R.id.menu_refresh).isVisible = board.allowSearch
		menu.findItem(R.id.menu_group).isChecked = getAdapter().isGroupMode
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_refresh -> {
				refreshSearch(getAdapter().itemCount > 0, nextPage = false)
				return true
			}
			R.id.menu_group -> {
				val adapter = getAdapter()
				val groupMode = !adapter.isGroupMode
				adapter.setGroupMode(groupMode)
				getParcelableExtra(ParcelableExtra.FACTORY).groupMode = groupMode
				return true
			}
		}
		return false
	}

	override fun onAppearanceOptionChanged(what: Int) {
		if (what == R.id.menu_spoilers || what == R.id.menu_sfw_mode) {
			notifyAllAdaptersChanged()
		}
	}

	override fun onSearchSubmit(query: String): Boolean {
		if (allowSearch) {
			// Collapse search view
			getRecyclerView().post {
				val page = getPage()
				uiManager!!.navigator()!!.navigateSearch(page.chanName, page.boardName, query)
			}
			return true
		}
		return false
	}

	override fun onListPulled(wrapper: PullableWrapper, side: PullableWrapper.Side) {
		refreshSearch(true, side == PullableWrapper.Side.BOTTOM)
	}

	private fun refreshSearch(showPull: Boolean, nextPage: Boolean) {
		val page = getPage()
		var pageNumber = 0
		if (nextPage) {
			val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
			if (retainableExtra.postItems.isNotEmpty()) {
				pageNumber = retainableExtra.pageNumber + 1
			}
		}
		val readViewModel = getViewModel(ReadViewModel::class.java)
		val task = ReadSearchTask(readViewModel.callback,
				chan, page.boardName, page.searchQuery, pageNumber)
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
		readViewModel.attach(task)
		val recyclerView = getRecyclerView()
		if (showPull) {
			recyclerView.pullable!!.startBusyState(PullableWrapper.Side.TOP)
			switchList()
		} else {
			recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
			switchProgress()
		}
	}

	override fun onReadSearchSuccess(postItems: List<PostItem>?, pageNumber: Int) {
		val recyclerView = getRecyclerView()
		recyclerView.pullable!!.cancelBusyState()
		val adapter = getAdapter()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		if (pageNumber == 0 && postItems.isNullOrEmpty()) {
			switchError(R.string.not_found)
			adapter.setItems(null)
			retainableExtra.postItems.clear()
		} else {
			switchList()
			if (pageNumber == 0) {
				val showScale = adapter.itemCount == 0
				adapter.setItems(postItems)
				retainableExtra.postItems.clear()
				retainableExtra.postItems.addAll(postItems!!)
				retainableExtra.pageNumber = 0
				recyclerView.scrollToPosition(0)
				if (showScale) {
					showScaleAnimation()
				}
			} else {
				val existingPostNumbers = HashSet<PostNumber>()
				for (postItem in retainableExtra.postItems) {
					existingPostNumbers.add(postItem.getPostNumber())
				}
				if (postItems != null) {
					for (postItem in postItems) {
						if (!existingPostNumbers.contains(postItem.getPostNumber())) {
							retainableExtra.postItems.add(postItem)
						}
					}
				}
				if (retainableExtra.postItems.size > existingPostNumbers.size) {
					val oldCount = adapter.itemCount
					val groupMode = adapter.isGroupMode
					var needScroll = false
					val childCount = recyclerView.childCount
					if (childCount > 0) {
						val child = recyclerView.getChildAt(childCount - 1)
						val position = recyclerView.getChildViewHolder(child).bindingAdapterPosition
						needScroll = position + 1 == oldCount &&
								recyclerView.height - recyclerView.paddingBottom - child.bottom >= 0
					}
					adapter.setItems(retainableExtra.postItems)
					retainableExtra.pageNumber = pageNumber
					if (!groupMode && needScroll) {
						ListViewUtils.smoothScrollToPosition(recyclerView, oldCount)
					}
				} else {
					ClickableToast.show(R.string.search_completed)
				}
			}
		}
	}

	override fun onReadSearchFail(errorItem: ErrorItem) {
		getRecyclerView().pullable!!.cancelBusyState()
		if (getAdapter().itemCount == 0) {
			switchError(errorItem)
		} else {
			ClickableToast.show(errorItem)
		}
	}

	override fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {
		getAdapter().reloadAttachment(attachmentItem)
	}
}
