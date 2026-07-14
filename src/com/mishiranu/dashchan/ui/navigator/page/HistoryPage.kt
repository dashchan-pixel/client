package com.mishiranu.dashchan.ui.navigator.page

import android.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.GetHistoryTask
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.HistoryDatabase
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.HistoryAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.HeaderItemDecoration

class HistoryPage :
    ListPage(),
    HistoryAdapter.Callback,
    GetHistoryTask.Callback {
    private var chanName: String? = null
    private var searchQuery: String? = null

    private var task: GetHistoryTask? = null
    private var firstLoad = true

    private fun getAdapter(): HistoryAdapter = getRecyclerView().adapter as HistoryAdapter

    override fun onCreate() {
        val recyclerView = getRecyclerView()
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        chanName = if (Preferences.isMergeChans) null else getPage().chanName
        searchQuery = getInitSearch().currentQuery
        CommonDatabase.getInstance().history.registerObserver(updateHistoryRunnable)
        val adapter = HistoryAdapter(context, this, chanName)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
        recyclerView.addItemDecoration(HeaderItemDecoration(adapter::getItemHeader))
        recyclerView.itemAnimator = null
        switchProgress()
        updateHistory()
    }

    override fun onDestroy() {
        CommonDatabase.getInstance().history.unregisterObserver(updateHistoryRunnable)
        getAdapter().setCursor(null)
        if (task != null) {
            task!!.cancel()
            task = null
        }
    }

    override fun obtainTitle(): String = getString(R.string.history)

    override fun onItemClick(item: HistoryDatabase.HistoryItem?) {
        uiManager!!.navigator()!!.navigatePosts(
            item!!.chanName,
            item.boardName,
            item.threadNumber,
            null,
            null,
        )
    }

    override fun onItemLongClick(item: HistoryDatabase.HistoryItem?): Boolean {
        showItemPopupMenu(fragmentManager, item!!)
        return true
    }

    private fun showItemPopupMenu(
        fragmentManager: FragmentManager,
        historyItem: HistoryDatabase.HistoryItem,
    ) {
        InstanceDialog(fragmentManager, null) { provider ->
            val dialogMenu = DialogMenu(provider.context)
            dialogMenu.add(R.string.copy_link) {
                val uri =
                    Chan
                        .get(historyItem.chanName)
                        .locator
                        .safe(true)
                        .createThreadUri(historyItem.boardName, historyItem.threadNumber)
                if (uri != null) {
                    StringUtils.copyToClipboard(context, uri.toString())
                }
            }
            if (!FavoritesStorage.getInstance().hasFavorite(
                    historyItem.chanName,
                    historyItem.boardName,
                    historyItem.threadNumber,
                )
            ) {
                dialogMenu.add(R.string.add_to_favorites) {
                    FavoritesStorage.getInstance().add(
                        historyItem.chanName!!,
                        historyItem.boardName,
                        historyItem.threadNumber!!,
                        historyItem.title,
                        true,
                    )
                }
            }
            dialogMenu.add(R.string.remove_from_history) {
                CommonDatabase.getInstance().history.remove(
                    historyItem.chanName!!,
                    historyItem.boardName,
                    historyItem.threadNumber!!,
                )
            }
            dialogMenu.create()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu) {
        menu
            .add(0, R.id.menu_search, 0, R.string.filter)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        menu.add(0, R.id.menu_clear, 0, R.string.clear_history)
        menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_clear) {
            showClearHistoryDialog(fragmentManager, chanName)
            return true
        }
        return false
    }

    override fun onSearchQueryChange(query: String?) {
        searchQuery = query
        updateHistory()
    }

    private val updateHistoryRunnable = Runnable { updateHistory() }

    private fun updateHistory() {
        if (task != null) {
            task!!.cancel()
        }
        task = GetHistoryTask(this, chanName, searchQuery)
        task!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    override fun onGetHistoryResult(cursor: HistoryDatabase.HistoryCursor?) {
        task = null
        val firstLoad = this.firstLoad
        this.firstLoad = false
        getAdapter().setCursor(cursor)
        val listPosition = takeListPosition()
        if (cursor!!.hasItems) {
            switchList()
            if (firstLoad && listPosition != null) {
                listPosition.apply(getRecyclerView())
            }
        } else {
            switchError(R.string.history_is_empty)
        }
    }

    companion object {
        private fun showClearHistoryDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
        ) {
            InstanceDialog(fragmentManager, null) { provider ->
                AlertDialog
                    .Builder(provider.context)
                    .setMessage(R.string.clear_history__sentence)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CommonDatabase.getInstance().history.clearHistory(chanName)
                    }.create()
            }
        }
    }
}
