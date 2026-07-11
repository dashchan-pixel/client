package com.mishiranu.dashchan.ui.navigator.page

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.GetBoardsTask
import com.mishiranu.dashchan.content.async.ReadBoardsTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.BoardsAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.HeaderItemDecoration
import com.mishiranu.dashchan.widget.PullableWrapper

class BoardsPage : ListPage(), BoardsAdapter.Callback, GetBoardsTask.Callback, ReadBoardsTask.Callback {
	private class RetainableExtra : Retainable {
		var firstLoad = true

		companion object {
			val FACTORY = ExtraFactory { RetainableExtra() }
		}
	}

	class ReadViewModel : TaskViewModel.Proxy<ReadBoardsTask, ReadBoardsTask.Callback>()

	private var searchQuery: String? = null

	private var getTask: GetBoardsTask? = null

	private fun getAdapter(): BoardsAdapter = getRecyclerView().adapter as BoardsAdapter

	override fun onCreate() {
		val recyclerView = getRecyclerView()
		recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
		searchQuery = getInitSearch().currentQuery
		val adapter = BoardsAdapter(this)
		recyclerView.adapter = adapter
		recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
		recyclerView.addItemDecoration(HeaderItemDecoration { _, position -> adapter.getItemHeader(position) })
		recyclerView.itemAnimator = null

		val initRequest = getInitRequest()
		recyclerView.pullable!!.setPullSides(PullableWrapper.Side.TOP)
		val readViewModel = getViewModel(ReadViewModel::class.java)
		if (initRequest.errorItem != null) {
			switchError(initRequest.errorItem)
		} else {
			recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
			switchProgress()
			updateBoards()
		}
		readViewModel.observe(this, this)
	}

	override fun onDestroy() {
		getAdapter().setCursor(null)
		if (getTask != null) {
			getTask!!.cancel()
			getTask = null
		}
	}

	override fun obtainTitle(): String {
		val hasUserBoards = chan.configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS)
		return getString(if (hasUserBoards) R.string.general_boards else R.string.boards)
	}

	override fun onItemClick(boardItem: ChanDatabase.BoardItem?) {
		uiManager!!.navigator()!!.navigateBoardsOrThreads(getPage().chanName, boardItem!!.boardName)
	}

	override fun onItemLongClick(boardItem: ChanDatabase.BoardItem?): Boolean {
		showItemPopupMenu(fragmentManager, getPage().chanName, boardItem!!)
		return true
	}

	override fun onCreateOptionsMenu(menu: Menu) {
		menu.add(0, R.id.menu_search, 0, R.string.filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
		menu.add(0, R.id.menu_make_home_page, 0, R.string.make_home_page)
				.setVisible(Preferences.getDefaultBoardName(chan) != null)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_refresh -> {
				val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
				if (!retainableExtra.firstLoad) {
					refreshBoards(!getAdapter().isRealEmpty())
				}
				return true
			}
			R.id.menu_make_home_page -> {
				Preferences.setDefaultBoardName(getPage().chanName, null)
				item.isVisible = false
				return true
			}
		}
		return false
	}

	override fun onSearchQueryChange(query: String?) {
		searchQuery = query
		updateBoards()
	}

	override fun onListPulled(wrapper: PullableWrapper, side: PullableWrapper.Side) {
		refreshBoards(true)
	}

	private fun updateBoards() {
		if (getTask != null) {
			getTask!!.cancel()
		}
		getTask = GetBoardsTask(this, chan, null, searchQuery)
		getTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
	}

	private fun refreshBoards(showPull: Boolean) {
		val readViewModel = getViewModel(ReadViewModel::class.java)
		val task = ReadBoardsTask(readViewModel.callback, chan)
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

	override fun onGetBoardsResult(cursor: ChanDatabase.BoardCursor?) {
		getTask = null
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		val firstLoad = retainableExtra.firstLoad
		retainableExtra.firstLoad = false
		val readViewModel = getViewModel(ReadViewModel::class.java)
		val listPosition = takeListPosition()
		if (cursor == null || !cursor.hasItems) {
			cursor?.close()
			getAdapter().setCursor(null)
			if (!readViewModel.hasTaskOrValue()) {
				if (firstLoad) {
					refreshBoards(false)
				} else {
					onReadBoardsFail(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE))
				}
			}
		} else {
			switchList()
			getAdapter().setCursor(cursor)
			val recyclerView = getRecyclerView()
			listPosition?.apply(recyclerView)
			if (readViewModel.hasTaskOrValue()) {
				recyclerView.pullable!!.startBusyState(PullableWrapper.Side.TOP)
			}
		}
	}

	override fun onReadBoardsSuccess() {
		val recyclerView = getRecyclerView()
		recyclerView.pullable!!.cancelBusyState()
		updateBoards()
		recyclerView.scrollToPosition(0)
	}

	override fun onReadBoardsFail(errorItem: ErrorItem) {
		getRecyclerView().pullable!!.cancelBusyState()
		if (getAdapter().isRealEmpty()) {
			switchError(errorItem)
		} else {
			ClickableToast.show(errorItem)
		}
	}

	companion object {
		private fun showItemPopupMenu(fragmentManager: FragmentManager,
				chanName: String?, boardItem: ChanDatabase.BoardItem) {
			InstanceDialog(fragmentManager, null) { provider ->
				val dialogMenu = DialogMenu(provider.context)
				dialogMenu.add(R.string.copy_link) {
					val chan = Chan.get(chanName)
					val uri = chan.locator.safe(true).createBoardUri(boardItem.boardName, 0)
					if (uri != null) {
						StringUtils.copyToClipboard(provider.context, uri.toString())
					}
				}
				if (!FavoritesStorage.getInstance().hasFavorite(chanName, boardItem.boardName, null)) {
					dialogMenu.add(R.string.add_to_favorites) {
						FavoritesStorage.getInstance().add(chanName!!, boardItem.boardName)
					}
				}
				dialogMenu.create()
			}
		}
	}
}
