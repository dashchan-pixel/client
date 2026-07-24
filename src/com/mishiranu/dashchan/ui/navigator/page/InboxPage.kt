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
import com.mishiranu.dashchan.content.async.GetInboxTask
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.InboxDatabase
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.InboxAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.HeaderItemDecoration

class InboxPage :
    ListPage(),
    InboxAdapter.Callback,
    GetInboxTask.Callback {
    private var chanName: String? = null
    private var searchQuery: String? = null

    private var task: GetInboxTask? = null
    private var firstLoad = true

    private fun getAdapter(): InboxAdapter = getRecyclerView().adapter as InboxAdapter

    override fun onCreate() {
        val recyclerView = getRecyclerView()
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        chanName = if (Preferences.isMergeChans) null else getPage().chanName
        searchQuery = getInitSearch().currentQuery
        CommonDatabase.getInstance().inbox.registerObserver(updateInboxRunnable)
        val adapter = InboxAdapter(context, this, chanName)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            DividerItemDecoration(recyclerView.context) { configuration, _ -> configuration.need(true) },
        )
        recyclerView.addItemDecoration(HeaderItemDecoration(adapter::getItemHeader))
        recyclerView.itemAnimator = null
        switchProgress()
        updateInbox()
    }

    override fun onDestroy() {
        CommonDatabase.getInstance().inbox.unregisterObserver(updateInboxRunnable)
        getAdapter().setCursor(null)
        if (task != null) {
            task?.cancel()
            task = null
        }
    }

    override fun obtainTitle(): String = getString(R.string.inbox)

    override fun onItemClick(item: InboxDatabase.InboxItem?) {
        val inboxItem = item ?: return
        CommonDatabase.getInstance().inbox.markReadAsync(
            inboxItem.chanName,
            inboxItem.boardName,
            inboxItem.threadNumber,
            listOf(inboxItem.postNumber),
        )
        uiManager.navigator()?.navigatePosts(
            inboxItem.chanName,
            inboxItem.boardName,
            inboxItem.threadNumber,
            inboxItem.postNumber,
            null,
        )
    }

    override fun onItemLongClick(item: InboxDatabase.InboxItem?): Boolean {
        showItemPopupMenu(fragmentManager, item ?: return false)
        return true
    }

    private fun showItemPopupMenu(
        fragmentManager: FragmentManager,
        inboxItem: InboxDatabase.InboxItem,
    ) {
        InstanceDialog(fragmentManager, null) { provider ->
            val dialogMenu = DialogMenu(provider.context)
            dialogMenu.add(R.string.copy_link) {
                val uri =
                    Chan
                        .get(inboxItem.chanName)
                        .locator
                        .safe(true)
                        .createPostUri(
                            inboxItem.boardName,
                            inboxItem.threadNumber,
                            inboxItem.postNumber,
                        )
                if (uri != null) {
                    StringUtils.copyToClipboard(context, uri.toString())
                }
            }
            dialogMenu.add(R.string.open_thread) {
                uiManager.navigator()?.navigatePosts(
                    inboxItem.chanName,
                    inboxItem.boardName,
                    inboxItem.threadNumber,
                    null,
                    null,
                )
            }
            dialogMenu.add(R.string.remove_from_inbox) {
                CommonDatabase.getInstance().inbox.remove(
                    inboxItem.chanName,
                    inboxItem.boardName,
                    inboxItem.threadNumber,
                    inboxItem.postNumber,
                )
            }
            dialogMenu.create()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu) {
        menu
            .add(0, R.id.menu_search, 0, R.string.filter)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        menu.add(0, R.id.menu_mark_all_read, 0, R.string.mark_all_as_read)
        menu.add(0, R.id.menu_clear, 0, R.string.clear_inbox)
        menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_mark_all_read -> {
                CommonDatabase.getInstance().inbox.markAllRead(chanName)
                return true
            }

            R.id.menu_clear -> {
                showClearInboxDialog(fragmentManager, chanName)
                return true
            }
        }
        return false
    }

    override fun onSearchQueryChange(query: String?) {
        searchQuery = query
        updateInbox()
    }

    private val updateInboxRunnable = Runnable { updateInbox() }

    private fun updateInbox() {
        if (task != null) {
            task?.cancel()
        }
        val task = GetInboxTask(this, chanName, searchQuery)
        this.task = task
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    override fun onGetInboxResult(cursor: InboxDatabase.InboxCursor?) {
        task = null
        if (cursor == null) {
            // The query was cancelled, a newer one is already on its way
            return
        }
        val firstLoad = this.firstLoad
        this.firstLoad = false
        getAdapter().setCursor(cursor)
        val listPosition = takeListPosition()
        if (cursor.hasItems) {
            switchList()
            if (firstLoad && listPosition != null) {
                listPosition.apply(getRecyclerView())
            }
        } else {
            switchError(R.string.inbox_is_empty)
        }
    }

    companion object {
        private fun showClearInboxDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
        ) {
            InstanceDialog(fragmentManager, null) { provider ->
                AlertDialog
                    .Builder(provider.context)
                    .setMessage(R.string.clear_inbox__sentence)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CommonDatabase.getInstance().inbox.clearInbox(chanName)
                    }.create()
            }
        }
    }
}
