package com.mishiranu.dashchan.ui.navigator.page

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.content.RedirectException
import chan.http.HttpValidator
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.HidePerformer
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.ReadThreadsTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.service.PostingService
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.DrawerForm
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.ThreadsAdapter
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.PullableWrapper
import com.mishiranu.dashchan.widget.SummaryLayout
import kotlin.math.abs

class ThreadsPage : ListPage(), ThreadsAdapter.Callback,
		FavoritesStorage.Observer, UiManager.Observer, ReadThreadsTask.Callback {
	private class RetainableExtra : Retainable {
		val cachedPostItems = ArrayList<List<PostItem>>()
		val hiddenThreads = PostItem.HideState.Map<String>()
		var startPageNumber = 0
		var boardSpeed = 0
		var validator: HttpValidator? = null

		var dialogsState: DialogUnit.StackInstance.State? = null

		override fun clear() {
			dialogsState?.dropState()
			dialogsState = null
		}

		companion object {
			val FACTORY = ExtraFactory { RetainableExtra() }
		}
	}

	class ReadViewModel : TaskViewModel.Proxy<ReadThreadsTask, ReadThreadsTask.Callback>()

	private lateinit var hidePerformer: HidePerformer

	private val postStateProvider = object : UiManager.PostStateProvider {
		override fun isHiddenResolve(postItem: PostItem): Boolean {
			if (postItem.getHideState() == PostItem.HideState.UNDEFINED) {
				val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
				val hideState = retainableExtra.hiddenThreads.get(postItem.getThreadNumber()!!)
				if (hideState != PostItem.HideState.UNDEFINED) {
					postItem.setHidden(hideState, null)
				} else {
					val hideReason = hidePerformer.checkHidden(chan, postItem)
					if (hideReason != null) {
						postItem.setHidden(PostItem.HideState.HIDDEN, hideReason)
					} else {
						postItem.setHidden(PostItem.HideState.SHOWN, null)
					}
				}
			}
			return postItem.getHideState().hidden
		}
	}

	private fun getAdapter(): ThreadsAdapter = getRecyclerView().adapter as ThreadsAdapter

	override fun onCreate() {
		val context = context
		val recyclerView = getRecyclerView()
		if (swipeToHideThreadEnabled()) {
			setupSwipeToHideThread(recyclerView)
		}
		setupRecyclerViewAnimations(recyclerView)
		val layoutManager = GridLayoutManager(recyclerView.context, 1)
		recyclerView.layoutManager = layoutManager
		val page = getPage()
		val chan = chan
		hidePerformer = HidePerformer(context)
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		val uiManager = uiManager
		uiManager!!.view().bindThreadsPostRecyclerView(recyclerView)
		val adapter = ThreadsAdapter(context, this, page.chanName, uiManager,
				postStateProvider, fragmentManager)
		recyclerView.adapter = adapter
		recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
			override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView,
					state: RecyclerView.State) {
				val column = (view.layoutParams as GridLayoutManager.LayoutParams).spanIndex
				adapter.applyItemPadding(view, parent.getChildAdapterPosition(view), column, outRect)
			}
		})
		recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
		recyclerView.pullable!!.setPullSides(PullableWrapper.Side.BOTH)
		uiManager!!.observable().register(this)
		layoutManager.spanCount = adapter.setThreadsView(Preferences.threadsView)
		adapter.setCatalogSort(Preferences.catalogSort)
		adapter.applyFilter(getInitSearch().currentQuery)
		FavoritesStorage.getInstance().getObservable().register(this)

		val initRequest = getInitRequest()
		val readViewModel = getViewModel(ReadViewModel::class.java)
		val listPosition = takeListPosition()
		if (initRequest.errorItem != null) {
			switchError(initRequest.errorItem)
		} else {
			var load = true
			if (!initRequest.shouldLoad && retainableExtra.cachedPostItems.isNotEmpty()) {
				load = false
				adapter.setItems(retainableExtra.cachedPostItems,
						retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG)
				listPosition?.apply(recyclerView)
				val dialogsState = retainableExtra.dialogsState
				if (dialogsState != null) {
					uiManager!!.dialog().restoreState(adapter.configurationSet, dialogsState)
					dialogsState.dropState()
					retainableExtra.dialogsState = null
				}
			}
			if (readViewModel.hasTaskOrValue()) {
				if (getAdapter().isRealEmpty) {
					recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
					switchProgress()
				} else {
					val task = readViewModel.task
					val bottom = task != null && task.pageNumber > retainableExtra.startPageNumber
					recyclerView.pullable!!.startBusyState(if (bottom)
							PullableWrapper.Side.BOTTOM else PullableWrapper.Side.TOP)
				}
			} else if (load) {
				val board = chan.configuration.safe().obtainBoard(page.boardName)
				retainableExtra.cachedPostItems.clear()
				retainableExtra.startPageNumber = if (board.allowCatalog && Preferences.isLoadCatalog(chan))
						PAGE_NUMBER_CATALOG else 0
				refreshThreads(RefreshPage.CURRENT, false)
			}
		}
		readViewModel.observe(this, this)
	}

	private fun swipeToHideThreadEnabled(): Boolean = Preferences.isSwipeToHideThreadEnabled

	private fun setupSwipeToHideThread(recyclerView: RecyclerView) {
		val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
			override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
				val threadPosition = viewHolder.bindingAdapterPosition
				return if (threadPosition == RecyclerView.NO_POSITION || threadHidden(threadPosition)) {
					0 // disable swipe for hidden threads or if can't get thread's position
				} else {
					super.getSwipeDirs(recyclerView, viewHolder)
				}
			}

			private fun threadHidden(threadPosition: Int): Boolean {
				val adapter = getAdapter()
				val post = adapter.getThread(threadPosition)
				return post.getHideState().hidden
			}

			override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
					target: RecyclerView.ViewHolder): Boolean = false

			override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
				val threadPosition = viewHolder.bindingAdapterPosition
				if (threadPosition != RecyclerView.NO_POSITION) {
					hideThreadAndNotifyAdapter(threadPosition)
				} else {
					getAdapter().notifyDataSetChanged() // this will bring swiped thread view back
				}
			}

			private fun hideThreadAndNotifyAdapter(threadPosition: Int) {
				val adapter = getAdapter()
				val thread = adapter.getThread(threadPosition)
				setThreadHideState(thread, PostItem.HideState.HIDDEN)
				adapter.notifyThreadHidden(thread)
			}

			override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
					dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
				val threadIsBeingSwiped = actionState == ItemTouchHelper.ACTION_STATE_SWIPE
				if (threadIsBeingSwiped) {
					setOpacityForSwipedThread(viewHolder, dX)
				}
				super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
			}

			private fun setOpacityForSwipedThread(swipedThreadViewWidth: RecyclerView.ViewHolder, swipeDeltaX: Float) {
				val threadRootView = swipedThreadViewWidth.itemView
				val opacity = calculateOpacityForSwipedThread(threadRootView.width, swipeDeltaX)
				threadRootView.alpha = opacity
			}

			private fun calculateOpacityForSwipedThread(swipedThreadViewWidth: Int, swipeDeltaX: Float): Float {
				return (1 - 1.5 * (abs(swipeDeltaX) / swipedThreadViewWidth)).toFloat()
			}

			override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
				// need to reset opacity of a thread view holder or it will be transparent when reused
				resetOpacityForThread(viewHolder)
				super.clearView(recyclerView, viewHolder)
			}

			private fun resetOpacityForThread(threadViewHolder: RecyclerView.ViewHolder) {
				threadViewHolder.itemView.alpha = 1f
			}
		}

		val itemTouchHelper = ItemTouchHelper(callback)
		itemTouchHelper.attachToRecyclerView(recyclerView)
	}

	private fun setupRecyclerViewAnimations(recyclerView: RecyclerView) {
		val animator = object : DefaultItemAnimator() {
			override fun animateChange(oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder,
					fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
				val threadWasSwiped = oldHolder.itemView.x != newHolder.itemView.x
				if (threadWasSwiped) {
					// if a thread was swiped to hide - animate appearance
					// of a hidden thread view with fade-in animation instead of default
					dispatchChangeFinished(oldHolder, true)
					animateHiddenThreadFadeIn(newHolder)
					return false
				}
				return super.animateChange(oldHolder, newHolder, fromX, fromY, toX, toY)
			}

			private fun animateHiddenThreadFadeIn(hiddenThreadViewHolder: RecyclerView.ViewHolder) {
				val hiddenThreadRootView = hiddenThreadViewHolder.itemView
				dispatchChangeStarting(hiddenThreadViewHolder, false)
				hiddenThreadRootView.alpha = 0f
				hiddenThreadRootView
						.animate()
						.alpha(1f)
						.setDuration(changeDuration)
						.withEndAction { dispatchChangeFinished(hiddenThreadViewHolder, false) }
						.start()
			}
		}

		recyclerView.itemAnimator = animator
	}

	override fun onResume() {
		super.onResume()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		retainableExtra.dialogsState?.dropState()
		retainableExtra.dialogsState = null
	}

	override fun onDestroy() {
		uiManager!!.dialog().closeDialogs(getAdapter().configurationSet.stackInstance!!)
		uiManager!!.observable().unregister(this)
		FavoritesStorage.getInstance().getObservable().unregister(this)
	}

	override fun onNotifyAllAdaptersChanged() {
		uiManager!!.dialog().notifyDataSetChangedToAll(getAdapter().configurationSet.stackInstance!!)
	}

	override fun onHandleNewPostDataList() {
		val page = getPage()
		val newPostData = PostingService.consumeNewThreadData(context,
				page.chanName, page.boardName)
		if (newPostData != null) {
			uiManager!!.navigator()!!.navigatePosts(newPostData.key!!.chanName, newPostData.key!!.boardName,
					newPostData.key!!.threadNumber, null, null)
		}
	}

	override fun onRequestStoreExtra(saveToStack: Boolean) {
		val adapter = getAdapter()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		retainableExtra.dialogsState?.dropState()
		retainableExtra.dialogsState = adapter.configurationSet.stackInstance!!.collectState()
	}

	override fun obtainTitleSubtitle(): Pair<String?, String?> {
		val page = getPage()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		var title = chan.configuration.getBoardTitle(page.boardName)
		title = StringUtils.formatBoardTitle(page.chanName!!, page.boardName, title)
		var subtitle: String? = null
		if (retainableExtra.startPageNumber > 0) {
			subtitle = getString(R.string.number_page__format, retainableExtra.startPageNumber)
		} else if (retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG) {
			subtitle = getString(R.string.catalog)
		} else if (retainableExtra.boardSpeed > 0) {
			subtitle = resources.getQuantityString(R.plurals.number_posts_per_hour__format,
					retainableExtra.boardSpeed, retainableExtra.boardSpeed)
		}
		return Pair<String?, String?>(title, subtitle)
	}

	override fun onItemClick(item: PostItem?) {
		if (item != null) {
			val page = getPage()
			if (item.getHideState().hidden) {
				setThreadHideState(item, PostItem.HideState.SHOWN)
				getAdapter().notifyThreadShown(item)
			} else {
				uiManager!!.navigator()!!.navigatePosts(page.chanName, page.boardName,
						item.getThreadNumber(), null, item.getSubjectOrComment())
			}
		}
	}

	override fun onItemLongClick(item: PostItem?): Boolean {
		if (item != null) {
			showItemPopupMenu(fragmentManager, item)
			return true
		}
		return false
	}

	private fun setThreadHideState(postItem: PostItem, hideState: PostItem.HideState) {
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		retainableExtra.hiddenThreads.set(postItem.getThreadNumber()!!, hideState)
		CommonDatabase.getInstance().threads.setFlagsAsync(getPage().chanName!!,
				postItem.getBoardName(), postItem.getThreadNumber()!!, hideState)
		postItem.setHidden(hideState, null)
	}

	private var allowSearch = false

	override fun onCreateOptionsMenu(menu: Menu) {
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
		menu.add(0, R.id.menu_search, 0, R.string.search)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
		menu.add(0, R.id.menu_catalog, 0, R.string.catalog)
		menu.add(0, R.id.menu_pages, 0, R.string.pages)
		val sorting = menu.addSubMenu(0, R.id.menu_sorting, 0, R.string.sorting)
		for (catalogSort in Preferences.CatalogSort.values()) {
			sorting.add(R.id.menu_sorting, catalogSort.menuItemId, 0, catalogSort.titleResId)
		}
		sorting.setGroupCheckable(R.id.menu_sorting, true, true)
		menu.add(0, R.id.menu_archive, 0, R.string.archive)
		menu.add(0, R.id.menu_new_thread, 0, R.string.new_thread)
		menu.add(0, R.id.menu_summary, 0, R.string.summary)
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
		val viewOptions = menu.addSubMenu(0, R.id.menu_threads_view, 0, R.string.threads_view)
		for (threadsView in Preferences.ThreadsView.values()) {
			viewOptions.add(R.id.menu_threads_view, threadsView.menuItemId, 0, threadsView.titleResId)
		}
		viewOptions.setGroupCheckable(R.id.menu_threads_view, true, true)
		menu.add(0, R.id.menu_star_text, 0, R.string.add_to_favorites)
		menu.add(0, R.id.menu_unstar_text, 0, R.string.remove_from_favorites)
		menu.add(0, R.id.menu_star_icon, 0, R.string.add_to_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionAddToFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(0, R.id.menu_unstar_icon, 0, R.string.remove_from_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionRemoveFromFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		menu.add(0, R.id.menu_make_home_page, 0, R.string.make_home_page)
	}

	override fun onPrepareOptionsMenu(menu: Menu) {
		val page = getPage()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		val chan = chan
		val board = chan.configuration.safe().obtainBoard(page.boardName)
		this.allowSearch = board.allowSearch
		val isCatalogOpen = retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG
		menu.findItem(R.id.menu_search).setTitle(if (board.allowSearch) R.string.search else R.string.filter)
		menu.findItem(R.id.menu_catalog).isVisible = board.allowCatalog && !isCatalogOpen
		menu.findItem(R.id.menu_pages).isVisible = board.allowCatalog && isCatalogOpen
		menu.findItem(R.id.menu_sorting).isVisible = board.allowCatalog && isCatalogOpen
		menu.findItem(Preferences.catalogSort!!.menuItemId).isChecked = true
		menu.findItem(R.id.menu_archive).isVisible = board.allowArchive
		menu.findItem(R.id.menu_new_thread).isVisible = board.allowPosting
		menu.findItem(Preferences.threadsView!!.menuItemId).isChecked = true
		val singleBoardMode = chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)
		val isFavorite = FavoritesStorage.getInstance().hasFavorite(page.chanName, page.boardName, null)
		val iconFavorite = ResourceUtils.isTabletOrLandscape(resources.configuration)
		menu.findItem(R.id.menu_star_text).isVisible = !iconFavorite && !isFavorite && !singleBoardMode
		menu.findItem(R.id.menu_unstar_text).isVisible = !iconFavorite && isFavorite
		menu.findItem(R.id.menu_star_icon).isVisible = iconFavorite && !isFavorite && !singleBoardMode
		menu.findItem(R.id.menu_unstar_icon).isVisible = iconFavorite && isFavorite
		menu.findItem(R.id.menu_make_home_page).isVisible = !singleBoardMode &&
				!CommonUtils.equals(page.boardName, Preferences.getDefaultBoardName(chan))
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val page = getPage()
		when (item.itemId) {
			R.id.menu_refresh -> {
				refreshThreads(RefreshPage.CURRENT)
				return true
			}
			R.id.menu_catalog -> {
				loadThreadsPage(PAGE_NUMBER_CATALOG, false)
				return true
			}
			R.id.menu_pages -> {
				loadThreadsPage(0, false)
				return true
			}
			R.id.menu_archive -> {
				uiManager!!.navigator()!!.navigateArchive(page.chanName, page.boardName)
				return true
			}
			R.id.menu_new_thread -> {
				uiManager!!.navigator()!!.navigatePosting(page.chanName, page.boardName, null)
				return true
			}
			R.id.menu_summary -> {
				showSummaryDialog(fragmentManager, page.chanName, page.boardName)
				return true
			}
			R.id.menu_star_text, R.id.menu_star_icon -> {
				FavoritesStorage.getInstance().add(page.chanName!!, page.boardName)
				return true
			}
			R.id.menu_unstar_text, R.id.menu_unstar_icon -> {
				FavoritesStorage.getInstance().remove(page.chanName, page.boardName, null)
				return true
			}
			R.id.menu_make_home_page -> {
				Preferences.setDefaultBoardName(page.chanName, page.boardName)
				item.isVisible = false
				return true
			}
		}
		for (catalogSort in Preferences.CatalogSort.values()) {
			if (item.itemId == catalogSort.menuItemId) {
				Preferences.catalogSort = catalogSort
				getAdapter().setCatalogSort(catalogSort)
				return true
			}
		}
		for (threadsView in Preferences.ThreadsView.values()) {
			if (item.itemId == threadsView.menuItemId) {
				Preferences.threadsView = threadsView
				val gridLayoutManager = getRecyclerView().layoutManager as GridLayoutManager
				gridLayoutManager.spanCount = getAdapter().setThreadsView(threadsView)
				getAdapter().notifyDataSetChanged()
				return true
			}
		}
		return false
	}

	override fun onFavoritesUpdate(favoriteItem: FavoritesStorage.FavoriteItem, action: FavoritesStorage.Action) {
		when (action) {
			FavoritesStorage.Action.ADD, FavoritesStorage.Action.REMOVE -> {
				val page = getPage()
				if (favoriteItem.equals(page.chanName, page.boardName, null)) {
					updateOptionsMenu()
				}
			}
			else -> {}
		}
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

	override fun onDrawerNumberEntered(number: Int): Int {
		var result = 0
		if (number >= 0) {
			// loadDesiredThreadsPage will leave error message, if number is incorrect
			result = result or DrawerForm.RESULT_REMOVE_ERROR_MESSAGE
			if (loadThreadsPage(number, false)) {
				result = result or DrawerForm.RESULT_SUCCESS
			}
		}
		return result
	}

	override fun onSearchQueryChange(query: String?) {
		getAdapter().applyFilter(query!!)
	}

	override fun onListPulled(wrapper: PullableWrapper, side: PullableWrapper.Side) {
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		refreshThreads(if (getAdapter().isRealEmpty || retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG)
				RefreshPage.CURRENT else if (side == PullableWrapper.Side.BOTTOM)
				RefreshPage.NEXT else RefreshPage.PREVIOUS, true)
	}

	private enum class RefreshPage { CURRENT, PREVIOUS, NEXT, CATALOG }

	private fun refreshThreads(refreshPage: RefreshPage) {
		refreshThreads(refreshPage, !getAdapter().isRealEmpty)
	}

	private fun refreshThreads(refreshPage: RefreshPage, showPull: Boolean) {
		val pageNumber: Int
		var append = false
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		if (refreshPage == RefreshPage.CATALOG || refreshPage == RefreshPage.CURRENT &&
				retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG) {
			pageNumber = PAGE_NUMBER_CATALOG
		} else {
			var currentPageNumber = retainableExtra.startPageNumber
			if (retainableExtra.cachedPostItems.isNotEmpty()) {
				currentPageNumber += retainableExtra.cachedPostItems.size - 1
			}
			val pageByPage = Preferences.isPageByPage
			if (pageByPage) {
				var number = if (refreshPage == RefreshPage.NEXT) currentPageNumber + 1
						else if (refreshPage == RefreshPage.PREVIOUS) currentPageNumber - 1 else currentPageNumber
				if (number < 0) {
					number = 0
				}
				pageNumber = number
			} else {
				pageNumber = if (refreshPage == RefreshPage.NEXT && currentPageNumber >= 0)
						currentPageNumber + 1 else 0
				if (pageNumber != 0) {
					append = true
				}
			}
		}
		loadThreadsPage(pageNumber, append, showPull)
	}

	private fun loadThreadsPage(pageNumber: Int, append: Boolean): Boolean {
		return loadThreadsPage(pageNumber, append, !getAdapter().isRealEmpty)
	}

	private fun loadThreadsPage(pageNumber: Int, append: Boolean, showPull: Boolean): Boolean {
		val page = getPage()
		val chan = chan
		val readViewModel = getViewModel(ReadViewModel::class.java)
		val recyclerView = getRecyclerView()
		if (pageNumber < PAGE_NUMBER_CATALOG || pageNumber >=
				maxOf(chan.configuration.getPagesCount(page.boardName), 1)) {
			recyclerView.pullable!!.cancelBusyState()
			ClickableToast.show(getString(R.string.number_page_doesnt_exist__format, pageNumber))
			readViewModel.attach(null)
			return false
		} else {
			val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
			val validator = if (!append && retainableExtra.cachedPostItems.size == 1 &&
					retainableExtra.startPageNumber == pageNumber) retainableExtra.validator else null
			val task = ReadThreadsTask(readViewModel.callback,
					chan, page.boardName, pageNumber, validator, append)
			task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
			readViewModel.attach(task)
			if (showPull) {
				recyclerView.pullable!!.startBusyState(PullableWrapper.Side.TOP)
				switchList()
			} else {
				recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
				switchProgress()
			}
			return true
		}
	}

	override fun onReadThreadsSuccess(postItems: List<PostItem>?, pageNumber: Int,
			boardSpeed: Int, append: Boolean, checkModified: Boolean, validator: HttpValidator?,
			hiddenThreads: PostItem.HideState.Map<String>?) {
		val recyclerView = getRecyclerView()
		recyclerView.pullable!!.cancelBusyState()
		switchList()
		val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
		var items = postItems
		var append = append
		if (items != null && items.isEmpty()) {
			items = null
		}
		if (retainableExtra.cachedPostItems.isEmpty()) {
			append = false
		}
		if (hiddenThreads != null) {
			if (!append) {
				retainableExtra.hiddenThreads.clear()
			}
			retainableExtra.hiddenThreads.addAll(hiddenThreads)
		}
		if (items != null && append) {
			val threadNumbers = HashSet<String>()
			for (pagePostItems in retainableExtra.cachedPostItems) {
				for (postItem in pagePostItems) {
					threadNumbers.add(postItem.getThreadNumber()!!)
				}
			}
			var list: List<PostItem> = items
			var newList: ArrayList<PostItem>? = null
			for (i in list.indices.reversed()) {
				if (threadNumbers.contains(list[i].getThreadNumber())) {
					if (newList == null) {
						newList = ArrayList(list)
						list = newList
					}
					newList.removeAt(i)
				}
			}
			items = list
		}
		val adapter = getAdapter()
		if (items != null && items.isNotEmpty()) {
			val oldCount = adapter.itemCount
			var needScroll = false
			val childCount = recyclerView.childCount
			if (childCount > 0) {
				val child = recyclerView.getChildAt(childCount - 1)
				val position = recyclerView.getChildViewHolder(child).bindingAdapterPosition
				needScroll = position + 1 == oldCount &&
						recyclerView.height - recyclerView.paddingBottom - child.bottom >= 0
			}
			if (append) {
				adapter.appendItems(items)
			} else {
				adapter.setItems(setOf(items), pageNumber == PAGE_NUMBER_CATALOG)
			}
			if (!append) {
				recyclerView.scrollToPosition(0)
			} else if (needScroll) {
				ListViewUtils.smoothScrollToPosition(recyclerView, oldCount)
			}
			retainableExtra.validator = validator
			if (!append) {
				retainableExtra.cachedPostItems.clear()
				retainableExtra.startPageNumber = pageNumber
				retainableExtra.boardSpeed = boardSpeed
			}
			retainableExtra.cachedPostItems.add(items)
			notifyTitleChanged()
			updateOptionsMenu()
			if (oldCount == 0 && !adapter.isRealEmpty) {
				showScaleAnimation()
			}
		} else if (checkModified && items == null) {
			adapter.notifyNotModified()
			recyclerView.scrollToPosition(0)
		} else if (adapter.isRealEmpty) {
			switchError(R.string.empty_response)
		} else {
			ClickableToast.show(R.string.empty_response)
		}
	}

	override fun onReadThreadsRedirect(target: RedirectException.Target) {
		getRecyclerView().pullable!!.cancelBusyState()
		if (!CommonUtils.equals(target.chanName, getPage().chanName)) {
			if (getAdapter().isRealEmpty) {
				switchError(R.string.board_doesnt_exist)
			}
			showRedirectDialog(fragmentManager, target)
		} else {
			handleRedirect(target.chanName, target.boardName, null, null)
		}
	}

	override fun onReadThreadsFail(errorItem: ErrorItem?, pageNumber: Int) {
		getRecyclerView().pullable!!.cancelBusyState()
		val message = if (errorItem!!.type == ErrorItem.Type.BOARD_NOT_EXISTS && pageNumber >= 1)
				getString(R.string.number_page_doesnt_exist__format, pageNumber) else errorItem.toString()
		if (getAdapter().isRealEmpty) {
			switchError(message)
		} else {
			ClickableToast.show(message)
		}
	}

	override fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {
		getAdapter().reloadAttachment(attachmentItem)
	}

	companion object {
		private val PAGE_NUMBER_CATALOG = ChanPerformer.ReadThreadsData.PAGE_NUMBER_CATALOG

		private fun showItemPopupMenu(fragmentManager: FragmentManager, postItem: PostItem) {
			InstanceDialog(fragmentManager, null) { provider ->
				val threadsPage = extract<ThreadsPage>(provider)
				val page = threadsPage!!.getPage()
				val dialogMenu = DialogMenu(provider.context)
				dialogMenu.add(R.string.copy_link) {
					val uri = threadsPage.chan.locator.safe(true)
							.createThreadUri(page.boardName, postItem.getThreadNumber())
					if (uri != null) {
						StringUtils.copyToClipboard(provider.context, uri.toString())
					}
				}
				dialogMenu.add(R.string.share_link) {
					val uri = threadsPage.chan.locator.safe(true)
							.createThreadUri(page.boardName, postItem.getThreadNumber())
					var subject = postItem.getSubjectOrComment()
					if (StringUtils.isEmptyOrWhitespace(subject)) {
						subject = uri.toString()
					}
					NavigationUtils.shareLink(provider.context, subject, uri!!)
				}
				if (!postItem.getHideState().hidden) {
					dialogMenu.add(R.string.hide) {
						threadsPage!!.setThreadHideState(postItem, PostItem.HideState.HIDDEN)
						threadsPage!!.getAdapter().notifyThreadHidden(postItem)
					}
				}
				dialogMenu.create()
			}
		}

		private fun showSummaryDialog(fragmentManager: FragmentManager, chanName: String?, boardName: String?) {
			InstanceDialog(fragmentManager, null) { provider ->
				val chan = Chan.get(chanName)
				val context = provider.context
				val dialog = AlertDialog.Builder(context)
						.setTitle(R.string.summary)
						.setPositiveButton(android.R.string.ok, null)
						.create()
				val layout = SummaryLayout(dialog)
				if (boardName != null) {
					var title = chan.configuration.getBoardTitle(boardName)
					title = StringUtils.formatBoardTitle(chanName!!, boardName, title)
					layout.add(context.getString(R.string.board), title)
					val description = chan.configuration.getBoardDescription(boardName)
					if (!StringUtils.isEmpty(description)) {
						layout.add(context.getString(R.string.description), description!!)
					}
				}
				val pagesCount = maxOf(chan.configuration.getPagesCount(boardName), 1)
				if (pagesCount != ChanConfiguration.PAGES_COUNT_INVALID) {
					layout.add(context.getString(R.string.pages_count), pagesCount.toString())
				}
				val board = chan.configuration.safe().obtainBoard(boardName)
				val posting = if (board.allowPosting)
						chan.configuration.safe().obtainPosting(boardName, true) else null
				if (posting != null) {
					val bumpLimit = chan.configuration.getBumpLimit(boardName)
					if (bumpLimit != ChanConfiguration.BUMP_LIMIT_INVALID) {
						layout.add(context.getString(R.string.bump_limit), context.resources
								.getQuantityString(R.plurals.number_posts__format, bumpLimit, bumpLimit))
					}
				}
				layout.addDivider()
				if (posting != null) {
					val builder = StringBuilder()
					if (!posting.allowSubject) {
						builder.append("• ").append(context.getString(R.string.subjects_are_disabled)).append('\n')
					}
					if (!posting.allowName) {
						builder.append("• ").append(context.getString(R.string.names_are_disabled)).append('\n')
					} else if (!posting.allowTripcode) {
						builder.append("• ").append(context.getString(R.string.tripcodes_are_disabled)).append('\n')
					}
					if (posting.attachmentCount <= 0) {
						builder.append("• ").append(context.getString(R.string.images_are_disabled)).append('\n')
					}
					if (!posting.optionSage) {
						builder.append("• ").append(context.getString(R.string.sage_is_disabled)).append('\n')
					}
					if (posting.hasCountryFlags) {
						builder.append("• ").append(context.getString(R.string.flags_are_enabled)).append('\n')
					}
					if (posting.userIcons.size > 0) {
						builder.append("• ").append(context.getString(R.string.icons_are_enabled)).append('\n')
					}
					if (builder.isNotEmpty()) {
						builder.setLength(builder.length - 1)
						layout.add(context.getString(R.string.configuration), builder)
					}
				} else {
					layout.add(context.getString(R.string.configuration), context.getString(R.string.read_only))
				}
				dialog
			}
		}

		private fun showRedirectDialog(fragmentManager: FragmentManager, target: RedirectException.Target) {
			val tag = ThreadsPage::class.java.name + ":Redirect"
			InstanceDialog(fragmentManager, tag) { provider ->
				val threadsPage = extract<ThreadsPage>(provider)
				val message = provider.context.getString(R.string.open_forum__format_sentence,
						Chan.get(target.chanName).configuration.getTitle())
				AlertDialog.Builder(provider.context)
						.setMessage(message)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(android.R.string.ok) { _, _ -> threadsPage!!
								.handleRedirect(target.chanName, target.boardName, null, null) }
						.create()
			}
		}
	}
}
