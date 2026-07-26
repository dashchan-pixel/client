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
import com.mishiranu.dashchan.content.async.GetEchoTask
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.EchoDatabase
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.EchoAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.HeaderItemDecoration

class EchoPage :
    ListPage(),
    EchoAdapter.Callback,
    GetEchoTask.Callback {
    private var chanName: String? = null
    private var searchQuery: String? = null

    private var task: GetEchoTask? = null
    private var firstLoad = true

    private fun getAdapter(): EchoAdapter = getRecyclerView().adapter as EchoAdapter

    override fun onCreate() {
        val recyclerView = getRecyclerView()
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        chanName = if (Preferences.isMergeChans) null else getPage().chanName
        searchQuery = getInitSearch().currentQuery
        CommonDatabase.getInstance().echo.registerObserver(updateEchoRunnable)
        val adapter = EchoAdapter(context, this, chanName)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            DividerItemDecoration(recyclerView.context) { configuration, _ -> configuration.need(true) },
        )
        recyclerView.addItemDecoration(HeaderItemDecoration(adapter::getItemHeader))
        recyclerView.itemAnimator = null
        switchProgress()
        updateEcho()
    }

    override fun onDestroy() {
        CommonDatabase.getInstance().echo.unregisterObserver(updateEchoRunnable)
        getAdapter().setCursor(null)
        if (task != null) {
            task?.cancel()
            task = null
        }
    }

    override fun obtainTitle(): String = getString(R.string.echo)

    override fun onItemClick(item: EchoDatabase.EchoItem?) {
        val echoItem = item ?: return
        CommonDatabase.getInstance().echo.markReadAsync(
            echoItem.chanName,
            echoItem.boardName,
            echoItem.threadNumber,
            listOf(echoItem.postNumber),
        )
        uiManager.navigator()?.navigatePosts(
            echoItem.chanName,
            echoItem.boardName,
            echoItem.threadNumber,
            echoItem.postNumber,
            null,
        )
    }

    override fun onItemLongClick(item: EchoDatabase.EchoItem?): Boolean {
        showItemPopupMenu(fragmentManager, item ?: return false)
        return true
    }

    private fun showItemPopupMenu(
        fragmentManager: FragmentManager,
        echoItem: EchoDatabase.EchoItem,
    ) {
        InstanceDialog(fragmentManager, null) { provider ->
            val dialogMenu = DialogMenu(provider.context)
            dialogMenu.add(R.string.copy_link) {
                val uri =
                    Chan
                        .get(echoItem.chanName)
                        .locator
                        .safe(true)
                        .createPostUri(
                            echoItem.boardName,
                            echoItem.threadNumber,
                            echoItem.postNumber,
                        )
                if (uri != null) {
                    StringUtils.copyToClipboard(context, uri.toString())
                }
            }

            dialogMenu.add(R.string.remove_from_echo) {
                CommonDatabase.getInstance().echo.remove(
                    echoItem.chanName,
                    echoItem.boardName,
                    echoItem.threadNumber,
                    echoItem.postNumber,
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
        menu.add(0, R.id.menu_clear, 0, R.string.clear_echo)
        menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_mark_all_read -> {
                CommonDatabase.getInstance().echo.markAllRead(chanName)
                return true
            }

            R.id.menu_clear -> {
                showClearEchoDialog(fragmentManager, chanName)
                return true
            }
        }
        return false
    }

    override fun onSearchQueryChange(query: String?) {
        searchQuery = query
        updateEcho()
    }

    private val updateEchoRunnable = Runnable { updateEcho() }

    private fun updateEcho() {
        if (task != null) {
            task?.cancel()
        }
        val task = GetEchoTask(this, chanName, searchQuery)
        this.task = task
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    override fun onGetEchoResult(cursor: EchoDatabase.EchoCursor?) {
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
            switchError(R.string.echo_is_empty)
        }
    }

    companion object {
        private fun showClearEchoDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
        ) {
            InstanceDialog(fragmentManager, null) { provider ->
                AlertDialog
                    .Builder(provider.context)
                    .setMessage(R.string.clear_echo__sentence)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CommonDatabase.getInstance().echo.clearEcho(chanName)
                    }.create()
            }
        }
    }
}
