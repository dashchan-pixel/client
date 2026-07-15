package com.mishiranu.dashchan.ui.navigator.page

import android.os.Parcel
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.async.GetBoardsTask
import com.mishiranu.dashchan.content.async.ReadUserBoardsTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.adapter.UserBoardsAdapter
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.PullableWrapper

class UserBoardsPage :
    ListPage(),
    UserBoardsAdapter.Callback,
    GetBoardsTask.Callback,
    ReadUserBoardsTask.Callback {
    private class ParcelableExtra : Parcelable {
        var boardNames: List<String> = emptyList()

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeStringList(boardNames)
        }

        companion object {
            val FACTORY = ExtraFactory { ParcelableExtra() }

            @JvmField
            val CREATOR =
                object : Parcelable.Creator<ParcelableExtra> {
                    override fun createFromParcel(source: Parcel): ParcelableExtra {
                        val parcelableExtra = ParcelableExtra()
                        parcelableExtra.boardNames = source.createStringArrayList().orEmpty()
                        return parcelableExtra
                    }

                    override fun newArray(size: Int): Array<ParcelableExtra?> = arrayOfNulls(size)
                }
        }
    }

    class ReadViewModel : TaskViewModel.Proxy<ReadUserBoardsTask, ReadUserBoardsTask.Callback>()

    private var searchQuery: String? = null

    private var getTask: GetBoardsTask? = null

    private fun getAdapter(): UserBoardsAdapter = getRecyclerView().adapter as UserBoardsAdapter

    override fun onCreate() {
        val recyclerView = getRecyclerView()
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        searchQuery = getInitSearch().currentQuery
        val adapter = UserBoardsAdapter(this)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context) { c, _ -> c.need(true) })
        recyclerView.itemAnimator = null
        recyclerView.pullable!!.setPullSides(PullableWrapper.Side.TOP)

        val initRequest = getInitRequest()
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        val readViewModel = getViewModel(ReadViewModel::class.java)
        if (initRequest.errorItem != null) {
            switchError(initRequest.errorItem)
        } else {
            var load = true
            if (parcelableExtra.boardNames.isNotEmpty()) {
                load = false
                updateBoards()
            }
            if (readViewModel.hasTaskOrValue()) {
                if (parcelableExtra.boardNames.isEmpty()) {
                    recyclerView.pullable!!.startBusyState(PullableWrapper.Side.BOTH)
                    switchProgress()
                } else {
                    recyclerView.pullable!!.startBusyState(PullableWrapper.Side.TOP)
                }
            } else if (load) {
                refreshBoards(false)
            }
        }
        readViewModel.observe(this, this)
    }

    override fun onDestroy() {
        getAdapter().setCursor(null)
        if (getTask != null) {
            getTask?.cancel()
            getTask = null
        }
    }

    override fun obtainTitle(): String = getString(R.string.user_boards)

    override fun onItemClick(item: ChanDatabase.BoardItem?) {
        uiManager!!.navigator()!!.navigateBoardsOrThreads(getPage().chanName, item!!.boardName)
    }

    override fun onItemLongClick(item: ChanDatabase.BoardItem?): Boolean {
        showItemPopupMenu(fragmentManager, getPage().chanName, item!!)
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
            val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
            refreshBoards(parcelableExtra.boardNames.isNotEmpty())
            return true
        }
        return false
    }

    override fun onSearchQueryChange(query: String?) {
        searchQuery = query
        updateBoards()
    }

    override fun onListPulled(
        wrapper: PullableWrapper,
        side: PullableWrapper.Side,
    ) {
        refreshBoards(true)
    }

    private fun updateBoards() {
        if (getTask != null) {
            getTask?.cancel()
            getTask = null
        }
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        if (parcelableExtra.boardNames.isEmpty()) {
            getAdapter().setCursor(null)
        } else {
            getTask = GetBoardsTask(this, chan, parcelableExtra.boardNames, searchQuery)
            getTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        }
    }

    private fun refreshBoards(showPull: Boolean) {
        val readViewModel = getViewModel(ReadViewModel::class.java)
        val task = ReadUserBoardsTask(readViewModel.callback, chan)
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
        getAdapter().setCursor(cursor)
        val listPosition = takeListPosition()
        listPosition?.apply(getRecyclerView())
    }

    override fun onReadUserBoardsSuccess(boardNames: List<String>) {
        val recyclerView = getRecyclerView()
        recyclerView.pullable!!.cancelBusyState()
        switchList()
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        parcelableExtra.boardNames = boardNames
        updateBoards()
        recyclerView.scrollToPosition(0)
    }

    override fun onReadUserBoardsFail(errorItem: ErrorItem) {
        getRecyclerView().pullable!!.cancelBusyState()
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        if (parcelableExtra.boardNames.isEmpty()) {
            switchError(errorItem)
        } else {
            ClickableToast.show(errorItem)
        }
    }

    companion object {
        private fun showItemPopupMenu(
            fragmentManager: FragmentManager,
            chanName: String?,
            boardItem: ChanDatabase.BoardItem,
        ) {
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
