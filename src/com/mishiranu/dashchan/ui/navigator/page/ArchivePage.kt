package com.mishiranu.dashchan.ui.navigator.page

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.model.ThreadSummary
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.async.ReadThreadSummariesTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.ArchiveAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.PullableWrapper

class ArchivePage :
    ListPage(),
    ArchiveAdapter.Callback,
    ReadThreadSummariesTask.Callback {
    private class RetainableExtra : Retainable {
        var threadSummaries: List<ThreadSummary>? = null
        var pageNumber = 0

        companion object {
            val FACTORY = ExtraFactory { RetainableExtra() }
        }
    }

    class ReadViewModel : TaskViewModel.Proxy<ReadThreadSummariesTask, ReadThreadSummariesTask.Callback>()

    private fun getAdapter(): ArchiveAdapter = getRecyclerView().adapter as ArchiveAdapter

    override fun onCreate() {
        val recyclerView = getRecyclerView()
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        val adapter = ArchiveAdapter(this)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
        recyclerView.pullable.setPullSides(PullableWrapper.Side.BOTH)
        adapter.applyFilter(getInitSearch().currentQuery)

        val initRequest = getInitRequest()
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        val readViewModel = getViewModel(ReadViewModel::class.java)
        val listPosition = takeListPosition()
        if (initRequest.errorItem != null) {
            switchError(initRequest.errorItem)
        } else {
            var load = true
            if (retainableExtra.threadSummaries != null) {
                load = false
                adapter.setItems(retainableExtra.threadSummaries)
                listPosition?.apply(recyclerView)
            }
            if (readViewModel.hasTaskOrValue()) {
                if (adapter.isRealEmpty()) {
                    recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTH)
                    switchProgress()
                } else {
                    val task = readViewModel.task
                    val bottom = task != null && task.pageNumber > 0
                    recyclerView.pullable.startBusyState(
                        if (bottom) {
                            PullableWrapper.Side.BOTTOM
                        } else {
                            PullableWrapper.Side.TOP
                        },
                    )
                }
            } else if (load) {
                refreshThreads(showPull = false, nextPage = false)
            }
        }
        readViewModel.observe(this, this)
    }

    override fun obtainTitle(): String {
        val page = getPage()
        return getString(R.string.archive) + ": " +
            StringUtils.formatBoardTitle(page.chanName!!, page.boardName, null)
    }

    override fun onItemClick(item: String?) {
        if (item != null) {
            val page = getPage()
            uiManager.navigator()!!.navigatePosts(page.chanName, page.boardName, item, null, null)
        }
    }

    override fun onItemLongClick(item: String?): Boolean {
        val page = getPage()
        showItemPopupMenu(fragmentManager, page.chanName, page.boardName, item)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu) {
        menu
            .add(0, R.id.menu_search, 0, R.string.filter)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        menu
            .add(0, R.id.menu_refresh, 0, R.string.refresh)
            .setIcon(getActionBarIcon(R.attr.iconActionRefresh))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_refresh) {
            refreshThreads(!getAdapter().isRealEmpty(), nextPage = false)
            return true
        }
        return false
    }

    override fun onSearchQueryChange(query: String?) {
        getAdapter().applyFilter(query)
    }

    override fun onListPulled(
        wrapper: PullableWrapper,
        side: PullableWrapper.Side,
    ) {
        refreshThreads(true, side == PullableWrapper.Side.BOTTOM)
    }

    private fun refreshThreads(
        showPull: Boolean,
        nextPage: Boolean,
    ) {
        val page = getPage()
        var pageNumber = 0
        if (nextPage) {
            val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
            if (retainableExtra.threadSummaries != null) {
                pageNumber = retainableExtra.pageNumber + 1
            }
        }
        val readViewModel = getViewModel(ReadViewModel::class.java)
        val task =
            ReadThreadSummariesTask(
                readViewModel.callback,
                chan,
                page.boardName,
                pageNumber,
                ChanPerformer.ReadThreadSummariesData.TYPE_ARCHIVED_THREADS,
            )
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        readViewModel.attach(task)
        val recyclerView = getRecyclerView()
        if (showPull) {
            recyclerView.pullable.startBusyState(PullableWrapper.Side.TOP)
            switchList()
        } else {
            recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTH)
            switchProgress()
        }
    }

    override fun onReadThreadSummariesSuccess(
        threadSummaries: List<ThreadSummary>,
        pageNumber: Int,
    ) {
        val recyclerView = getRecyclerView()
        recyclerView.pullable.cancelBusyState()
        val adapter = getAdapter()
        if (pageNumber == 0 && threadSummaries.isEmpty()) {
            if (adapter.isRealEmpty()) {
                switchError(R.string.empty_response)
            } else {
                ClickableToast.show(R.string.empty_response)
            }
        } else {
            switchList()
            val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
            if (pageNumber == 0) {
                val showScale = adapter.isRealEmpty()
                adapter.setItems(threadSummaries)
                retainableExtra.threadSummaries = threadSummaries
                retainableExtra.pageNumber = 0
                recyclerView.scrollToPosition(0)
                if (showScale) {
                    showScaleAnimation()
                }
            } else {
                val concatenated =
                    ReadThreadSummariesTask
                        .concatenate(retainableExtra.threadSummaries, threadSummaries)
                val oldCount = retainableExtra.threadSummaries!!.size
                if (concatenated.size > oldCount) {
                    var needScroll = false
                    val childCount = recyclerView.childCount
                    if (childCount > 0) {
                        val child = recyclerView.getChildAt(childCount - 1)
                        val position = recyclerView.getChildViewHolder(child).bindingAdapterPosition
                        needScroll = position + 1 == oldCount &&
                            recyclerView.height - recyclerView.paddingBottom - child.bottom >= 0
                    }
                    adapter.setItems(concatenated)
                    retainableExtra.threadSummaries = concatenated
                    retainableExtra.pageNumber = pageNumber
                    if (needScroll) {
                        ListViewUtils.smoothScrollToPosition(recyclerView, oldCount)
                    }
                }
            }
        }
    }

    override fun onReadThreadSummariesFail(errorItem: ErrorItem) {
        getRecyclerView().pullable.cancelBusyState()
        if (getAdapter().isRealEmpty()) {
            switchError(errorItem)
        } else {
            ClickableToast.show(errorItem)
        }
    }

    companion object {
        private fun showItemPopupMenu(
            fragmentManager: FragmentManager,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
        ) {
            InstanceDialog(fragmentManager, null) { provider ->
                val dialogMenu = DialogMenu(provider.context)
                dialogMenu.add(R.string.copy_link) {
                    val chan = Chan.get(chanName)
                    val uri = chan.locator.safe(true).createThreadUri(boardName, threadNumber)
                    if (uri != null) {
                        StringUtils.copyToClipboard(provider.context, uri.toString())
                    }
                }
                if (!FavoritesStorage.getInstance().hasFavorite(chanName, boardName, threadNumber)) {
                    dialogMenu.add(R.string.add_to_favorites) {
                        FavoritesStorage.getInstance().add(chanName!!, boardName, threadNumber!!, null, false)
                    }
                }
                dialogMenu.create()
            }
        }
    }
}
