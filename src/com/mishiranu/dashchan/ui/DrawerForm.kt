package com.mishiranu.dashchan.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.annotation.RequiresApi
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanConfiguration
import chan.content.ChanManager
import chan.content.ChanMarkup
import chan.util.CommonUtils.equals
import chan.util.StringUtils
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.isEmptyOrWhitespace
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.PagesListMode
import com.mishiranu.dashchan.content.Preferences.chansOrder
import com.mishiranu.dashchan.content.Preferences.getDefaultBoardName
import com.mishiranu.dashchan.content.Preferences.isFavoritesHidedAll
import com.mishiranu.dashchan.content.Preferences.isFavoritesHidedDeleted
import com.mishiranu.dashchan.content.Preferences.isMergeChans
import com.mishiranu.dashchan.content.Preferences.isRememberHistory
import com.mishiranu.dashchan.content.Preferences.setFavoritesHideAll
import com.mishiranu.dashchan.content.Preferences.setFavoritesHideDeleted
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.service.WatcherService
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem
import com.mishiranu.dashchan.graphics.ChanIconDrawable
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.GraphicsUtils.mixColors
import com.mishiranu.dashchan.util.IOUtils.readRawResourceString
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.ListViewUtils.getRootViewInList
import com.mishiranu.dashchan.util.NavigationUtils.shareLink
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.getColorStateList
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground
import com.mishiranu.dashchan.util.ViewUtils.setTextSizeScaled
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.DividerItemDecoration.AboveCallback
import com.mishiranu.dashchan.widget.EdgeEffectHandler
import com.mishiranu.dashchan.widget.EdgeEffectHandler.Shift
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.SafePasteEditText
import com.mishiranu.dashchan.widget.SortableHelper
import com.mishiranu.dashchan.widget.SortableHelper.DragState
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import com.mishiranu.dashchan.widget.WatcherView
import com.mishiranu.dashchan.widget.WatcherView.ColorSet
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.Array
import kotlin.Boolean
import kotlin.CharSequence
import kotlin.Comparable
import kotlin.Float
import kotlin.IllegalStateException
import kotlin.IndexOutOfBoundsException
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.String
import kotlin.intArrayOf
import kotlin.run

class DrawerForm(
    private val context: Context,
    private val callback: Callback,
    private val fragmentManager: FragmentManager,
    private val watcherServiceClient: WatcherService.Client,
) : RecyclerView.Adapter<DrawerForm.ViewHolder>(),
    Shift,
    DrawerListener,
    OnEditorActionListener,
    SortableHelper.Callback<DrawerForm.ViewHolder> {
    private val watcherViewColorSet: ColorSet
    private val sortableHelper: SortableHelper<ViewHolder>
    private val drawerIconColor: Int

    private val inputMethodManager: InputMethodManager?

    private val recyclerView: PaddedRecyclerView
    private val searchEdit: EditText
    private val selectorContainer: View
    val headerView: View
    private val restartView: View
    private val chanNameView: TextView
    private val chanSelectorIcon: ImageView

    private val chanIcons = HashMap<String?, ChanIconDrawable?>()
    private val watcherSupportSet = HashSet<String?>()

    private val chans = ArrayList<ListItem>()
    private val pages = ArrayList<ListItem>()
    private val favorites = ArrayList<ListItem>()
    private val menu = ArrayList<ListItem>()

    private var mergeChans = false
    private var showHistory = false
    private var pagesListMode: PagesListMode? = null
    private var chanSelectMode = false
    private var showRestartButton = false
    private var categoriesOrder: CategoriesOrder? = null
    private var chanName: String? = null

    private enum class CategoriesOrder {
        PAGES_FIRST,
        FAVORITES_FIRST,
        HIDE_PAGES,
    }

    class Page(
        val chanName: String,
        val boardName: String?,
        val threadNumber: String?,
        val threadTitle: String?,
        val createRealtime: Long,
    ) : Comparable<Page> {
        override fun compareTo(other: Page): Int = other.createRealtime.compareTo(createRealtime)
    }

    interface Callback {
        fun onSelectChan(chanName: String?)

        fun onSelectBoard(
            chanName: String?,
            boardName: String?,
            fromCache: Boolean,
        )

        fun onSelectThread(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?,
            threadTitle: String?,
            fromCache: Boolean,
        ): Boolean

        fun onClosePage(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
        )

        fun onCloseAllPages()

        fun onEnterNumber(number: Int): Int

        fun onSelectDrawerMenuItem(item: Int)

        fun onDraggingStateChanged(dragging: Boolean)

        fun obtainDrawerPages(): Collection<Page>

        fun restartApplication()
    }

    private fun updateConfigurationInternal(
        chanName: String?,
        force: Boolean,
    ) {
        if (!equals(chanName, this.chanName) || force || menu.isEmpty()) {
            this.chanName = chanName
            val chan = get(chanName)
            chanNameView.setText(chan.configuration.getTitle())
            menu.clear()
            val context = this.context
            val typedArray =
                context.obtainStyledAttributes(
                    intArrayOf(
                        R.attr.iconDrawerMenuBoards,
                        R.attr.iconDrawerMenuUserBoards,
                        R.attr.iconDrawerMenuHistory,
                        R.attr.iconDrawerMenuPreferences,
                    ),
                )
            val hasUserBoards =
                chan.configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS)
            if (chanName != null && !chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
                menu.add(
                    ListItem(
                        ListItem.Type.MENU,
                        MENU_ITEM_BOARDS,
                        typedArray.getResourceId(0, 0),
                        context.getString(if (hasUserBoards) R.string.general_boards else R.string.boards),
                    ),
                )
            }
            if (chanName != null && hasUserBoards) {
                menu.add(
                    ListItem(
                        ListItem.Type.MENU,
                        MENU_ITEM_USER_BOARDS,
                        typedArray.getResourceId(1, 0),
                        context.getString(R.string.user_boards),
                    ),
                )
            }
            if (chanName != null && isRememberHistory) {
                menu.add(
                    ListItem(
                        ListItem.Type.MENU,
                        MENU_ITEM_HISTORY,
                        typedArray.getResourceId(2, 0),
                        context.getString(R.string.history),
                    ),
                )
            }
            menu.add(
                ListItem(
                    ListItem.Type.MENU,
                    MENU_ITEM_PREFERENCES,
                    typedArray.getResourceId(3, 0),
                    context.getString(R.string.preferences),
                ),
            )
            typedArray.recycle()
            updateItems(true, true)
        }
    }

    fun updateConfiguration(chanName: String?) {
        updateConfigurationInternal(chanName, false)
    }

    val contentView: View
        get() = recyclerView

    fun setChanSelectMode(enabled: Boolean) {
        if (chans.size >= 2 && chanSelectMode != enabled) {
            chanSelectMode = enabled
            chanSelectorIcon.setRotation(if (enabled) 180f else 0f)
            notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            updateRestartViewVisibility()
        }
    }

    fun isChanSelectMode(): Boolean = chanSelectMode

    fun updateRestartViewVisibility() {
        val showRestartButton = !chanSelectMode && ChanManager.getInstance().isRestartRequired
        if (this.showRestartButton != showRestartButton) {
            this.showRestartButton = showRestartButton
            notifyDataSetChanged()
        }
    }

    fun updateChans() {
        updateChansWithoutConfiguration()
        updateConfigurationInternal(chanName, true)
    }

    private fun updateChansWithoutConfiguration() {
        val manager = ChanManager.getInstance()
        val availableChans = manager.availableChans
        var availableChansCount = 0
        chans.clear()
        watcherSupportSet.clear()
        for (chan in availableChans) {
            availableChansCount++
            if (watcherServiceClient.isWatcherSupported(chan)) {
                watcherSupportSet.add(chan.name)
            }
            chans.add(
                DrawerForm.ListItem(
                    ListItem.Type.CHAN,
                    0,
                    chan.name!!,
                    null,
                    null,
                    chan.configuration.getTitle(),
                ),
            )
        }
        selectorContainer.setVisibility(if (availableChansCount >= 2) View.VISIBLE else View.GONE)
        if (chanSelectMode && availableChansCount <= 1) {
            setChanSelectMode(false)
        }
        notifyDataSetChanged()
    }

    fun updatePreferences() {
        if (updatePreferencesWithoutConfiguration()) {
            updateConfigurationInternal(chanName, true)
        }
    }

    private fun updatePreferencesWithoutConfiguration(): Boolean {
        val mergeChans = isMergeChans
        val showHistory = isRememberHistory
        val pagesListMode = Preferences.pagesListMode
        if (this.mergeChans != mergeChans || this.showHistory != showHistory || this.pagesListMode != pagesListMode) {
            this.mergeChans = mergeChans
            this.showHistory = showHistory
            this.pagesListMode = pagesListMode
            return true
        }
        return false
    }

    override fun getEdgeEffectShift(side: EdgeEffectHandler.Side): Int {
        val shift = recyclerView.obtainEdgeEffectShift(side)
        return if (side == EdgeEffectHandler.Side.TOP) shift + headerView.getPaddingTop() else shift
    }

    private fun onItemClick(position: Int) {
        val listItem = getItem(position)
        when (listItem.type) {
            ListItem.Type.PAGE, ListItem.Type.FAVORITE -> {
                val fromCache = listItem.type == ListItem.Type.PAGE
                if (!listItem.isThreadItem) {
                    callback.onSelectBoard(listItem.chanName, listItem.boardName, fromCache)
                } else {
                    callback.onSelectThread(
                        listItem.chanName,
                        listItem.boardName,
                        listItem.threadNumber,
                        null,
                        listItem.title,
                        fromCache,
                    )
                }
            }

            ListItem.Type.MENU -> {
                callback.onSelectDrawerMenuItem(listItem.data)
            }

            ListItem.Type.CHAN -> {
                callback.onSelectChan(listItem.chanName)
                setChanSelectMode(false)
            }

            else -> {}
        }
    }

    private fun onItemLongClick(holder: ViewHolder): Boolean {
        if (chanSelectMode) {
            sortableHelper.start(holder)
            return true
        }
        val listItem = getItem(holder.getBindingAdapterPosition())
        if (listItem.type == ListItem.Type.FAVORITE &&
            listItem.threadNumber != null &&
            FavoritesStorage.getInstance().canSortManually() &&
            holder.isMultipleFingers
        ) {
            sortableHelper.start(holder)
            return true
        }
        when (listItem.type) {
            ListItem.Type.PAGE, ListItem.Type.FAVORITE -> {
                showPageFavoriteMenu(
                    fragmentManager,
                    listItem.type == ListItem.Type.FAVORITE,
                    listItem.isThreadItem,
                    listItem.chanName!!,
                    listItem.boardName,
                    listItem.threadNumber,
                    listItem.title,
                )
                return true
            }

            else -> {}
        }
        return false
    }

    override fun onDrawerSlide(
        drawerView: View,
        slideOffset: Float,
    ) {}

    override fun onDrawerOpened(drawerView: View) {}

    override fun onDrawerClosed(drawerView: View) {
        hideKeyboard()
        setChanSelectMode(false)
    }

    override fun onDrawerStateChanged(newState: Int) {}

    private fun clearTextAndHideKeyboard() {
        searchEdit.setText(null)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        searchEdit.clearFocus()
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0)
        }
    }

    private fun onSearchClick() {
        val text = searchEdit.getText().toString().trim { it <= ' ' }
        var number = -1
        try {
            number = text.toInt()
        } catch (e: NumberFormatException) {
            // Not a number, ignore exception
        }
        if (number >= 0) {
            val result = callback.onEnterNumber(number)
            if (get(result, RESULT_SUCCESS)) {
                clearTextAndHideKeyboard()
                return
            }
            if (get(result, RESULT_REMOVE_ERROR_MESSAGE)) {
                return
            }
        } else {
            run {
                var boardName: String? = null
                var threadNumber: String? = null
                var matcher: Matcher = PATTERN_NAVIGATION_BOARD_THREAD.matcher(text)
                if (matcher.matches()) {
                    boardName = matcher.group(1)
                    threadNumber = matcher.group(2)
                } else {
                    matcher = PATTERN_NAVIGATION_BOARD.matcher(text)
                    if (matcher.matches()) {
                        boardName = matcher.group(1)
                    } else {
                        matcher = PATTERN_NAVIGATION_THREAD.matcher(text)
                        if (matcher.matches()) {
                            threadNumber = matcher.group(1)
                        }
                    }
                }
                if (boardName != null || threadNumber != null) {
                    val success: Boolean
                    if (threadNumber == null) {
                        callback.onSelectBoard(chanName, boardName, false)
                        success = true
                    } else {
                        success =
                            callback.onSelectThread(
                                chanName,
                                boardName,
                                threadNumber,
                                null,
                                null,
                                false,
                            )
                    }
                    if (success) {
                        clearTextAndHideKeyboard()
                        return
                    }
                }
            }
            val uri = Uri.parse(text)
            val chan = getPreferred(null, uri)
            if (chan.name != null) {
                var success = false
                var boardName: String? = null
                var threadNumber: String? = null
                var postNumber: PostNumber? = null
                if (chan.locator.safe(false).isThreadUri(uri)) {
                    boardName = chan.locator.safe(false).getBoardName(uri)
                    threadNumber = chan.locator.safe(false).getThreadNumber(uri)
                    postNumber = chan.locator.safe(false).getPostNumber(uri)
                    success = true
                } else if (chan.locator.safe(false).isBoardUri(uri)) {
                    boardName = chan.locator.safe(false).getBoardName(uri)
                    threadNumber = null
                    postNumber = null
                    success = true
                }
                if (success) {
                    if (threadNumber == null) {
                        callback.onSelectBoard(chan.name, boardName, false)
                    } else {
                        callback.onSelectThread(
                            chan.name,
                            boardName,
                            threadNumber,
                            postNumber,
                            null,
                            false,
                        )
                    }
                    clearTextAndHideKeyboard()
                    return
                }
            }
        }
        if (text.isEmpty()) {
            var searchHelpFormat: SearchHelpFormat? = null
            if (chanName != null) {
                val chan = get(chanName)
                if (chan.name != null && !chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
                    searchHelpFormat = SearchHelpFormat.obtain(chan, false)
                }
            }
            if (searchHelpFormat == null) {
                for (chan in ChanManager.getInstance().availableChans) {
                    if (!chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
                        searchHelpFormat = SearchHelpFormat.obtain(chan, false)
                        if (searchHelpFormat != null) {
                            break
                        }
                    }
                }
            }
            if (searchHelpFormat == null) {
                for (chan in ChanManager.getInstance().availableChans) {
                    if (chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
                        searchHelpFormat = SearchHelpFormat.obtain(chan, true)
                        if (searchHelpFormat != null) {
                            break
                        }
                    }
                }
            }
            if (searchHelpFormat == null) {
                searchHelpFormat =
                    SearchHelpFormat("mobi", "307707", "https://2ch.hk/mobi/res/307707.html")
            }
            showSearchHelp(fragmentManager, searchHelpFormat)
            return
        }
        show(R.string.enter_valid_data)
    }

    override fun onEditorAction(
        v: TextView?,
        actionId: Int,
        event: KeyEvent?,
    ): Boolean {
        onSearchClick()
        return true
    }

    private class SearchHelpFormat(
        val boardName: String,
        val threadNumber: String,
        val threadUrl: String,
    ) {
        companion object {
            fun obtain(
                chan: Chan,
                allowEmptyBoardName: Boolean,
            ): SearchHelpFormat? {
                var boardName = getDefaultBoardName(chan)
                if (boardName == null) {
                    val favoriteItems =
                        FavoritesStorage
                            .getInstance()
                            .getBoards(chan.name)
                    if (!favoriteItems.isEmpty()) {
                        boardName = favoriteItems[0].boardName
                    }
                }
                if (boardName == null) {
                    if (allowEmptyBoardName) {
                        boardName = "b"
                    } else {
                        return null
                    }
                }
                var threadNumber: String? = null
                val favoriteItems =
                    FavoritesStorage
                        .getInstance()
                        .getThreads(chan.name)
                if (!favoriteItems.isEmpty()) {
                    threadNumber = favoriteItems[0].threadNumber
                }
                if (threadNumber == null) {
                    return null
                }
                val uri = chan.locator.safe(false).createThreadUri(boardName, threadNumber)
                if (uri == null) {
                    return null
                }
                return SearchHelpFormat(boardName, threadNumber, uri.toString())
            }
        }
    }

    fun updateItems(
        pages: Boolean,
        favorites: Boolean,
    ) {
        if (pages && pagesListMode != PagesListMode.HIDE_PAGES) {
            updateListPages()
        }
        if (favorites) {
            updateListFavorites()
        }
        if (pagesListMode == null) {
            categoriesOrder = null
        } else {
            when (pagesListMode) {
                PagesListMode.PAGES_FIRST -> {
                    categoriesOrder = CategoriesOrder.PAGES_FIRST
                }

                PagesListMode.FAVORITES_FIRST -> {
                    categoriesOrder = CategoriesOrder.FAVORITES_FIRST
                }

                PagesListMode.HIDE_PAGES -> {
                    categoriesOrder = CategoriesOrder.HIDE_PAGES
                }

                else -> {
                    error("Unexpected pages list mode: $pagesListMode")
                }
            }
        }
        notifyDataSetChanged()
    }

    private fun updateListPages() {
        this.pages.clear()
        val mergeChans = this.mergeChans
        val allPages = callback.obtainDrawerPages()
        val pages = ArrayList<Page>()
        for (page in allPages) {
            if (mergeChans || page.chanName == chanName) {
                if (page.threadNumber != null ||
                    !get(page.chanName)
                        .configuration
                        .getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)
                ) {
                    pages.add(page)
                }
            }
        }
        if (pages.size > 0) {
            pages.sort()
            this.pages.add(
                ListItem(
                    ListItem.Type.SECTION,
                    SECTION_ACTION_CLOSE_ALL,
                    getResourceId(context, R.attr.iconButtonCancel, 0),
                    context.getString(R.string.open_pages__noun),
                ),
            )
            for (page in pages) {
                if (page.threadNumber != null) {
                    this.pages.add(
                        ListItem(
                            ListItem.Type.PAGE,
                            0,
                            page.chanName,
                            page.boardName,
                            page.threadNumber,
                            page.threadTitle,
                        ),
                    )
                } else {
                    this.pages.add(
                        ListItem(
                            ListItem.Type.PAGE,
                            0,
                            page.chanName,
                            page.boardName,
                            null,
                            get(page.chanName).configuration.getBoardTitle(page.boardName),
                        ),
                    )
                }
            }
        }
    }

    private fun updateListFavorites() {
        this.favorites.clear()
        val mergeChans = this.mergeChans
        val favoritesStorage = FavoritesStorage.getInstance()
        val favoriteBoards =
            favoritesStorage.getBoards(
                if (mergeChans) {
                    null
                } else {
                    chanName
                },
            )
        val favoriteThreads =
            favoritesStorage.getThreads(
                if (mergeChans) {
                    null
                } else {
                    chanName
                },
            )
        var addSection = true
        for (i in favoriteThreads.indices) {
            val favoriteItem = favoriteThreads[i]
            val chan = get(favoriteItem.chanName)
            if (chan.name == null) {
                continue
            }
            if (mergeChans || favoriteItem.chanName == chanName) {
                if (addSection) {
                    if (watcherSupportSet.contains(favoriteItem.chanName) ||
                        mergeChans &&
                        !watcherSupportSet.isEmpty()
                    ) {
                        favorites.add(
                            ListItem(
                                ListItem.Type.SECTION,
                                SECTION_ACTION_FAVORITES_MENU,
                                getResourceId(context, R.attr.iconButtonMore, 0),
                                context.getString(R.string.favorite_threads),
                            ),
                        )
                    } else {
                        favorites.add(
                            DrawerForm.ListItem(
                                ListItem.Type.SECTION,
                                null,
                                null,
                                null,
                                context.getString(R.string.favorite_threads),
                            ),
                        )
                    }
                    addSection = false
                }
                if (!isFavoritesHidedAll) {
                    if (!(
                            isFavoritesHidedDeleted &&
                                watcherServiceClient
                                    .getCounter(
                                        favoriteItem.chanName,
                                        favoriteItem.boardName,
                                        favoriteItem.threadNumber!!,
                                    )!!
                                    .deleted
                        )
                    ) {
                        val listItem =
                            DrawerForm.ListItem(
                                ListItem.Type.FAVORITE,
                                0,
                                favoriteItem.chanName,
                                favoriteItem.boardName!!,
                                favoriteItem.threadNumber,
                                favoriteItem.title,
                            )
                        favorites.add(listItem)
                    }
                }
            }
        }
        addSection = true
        for (i in favoriteBoards.indices) {
            val favoriteItem = favoriteBoards[i]
            val chan = get(favoriteItem.chanName)
            if (chan.name == null) {
                continue
            }
            if (mergeChans || favoriteItem.chanName == chanName) {
                if (addSection) {
                    favorites.add(
                        DrawerForm.ListItem(
                            ListItem.Type.SECTION,
                            null,
                            null,
                            null,
                            context.getString(R.string.favorite_boards),
                        ),
                    )
                    addSection = false
                }
                favorites.add(
                    DrawerForm.ListItem(
                        ListItem.Type.FAVORITE,
                        0,
                        favoriteItem.chanName,
                        favoriteItem.boardName!!,
                        null,
                        chan.configuration.getBoardTitle(favoriteItem.boardName),
                    ),
                )
            }
        }
    }

    private fun formatBoardThreadTitle(
        threadItem: Boolean,
        boardName: String?,
        threadNumber: String?,
        title: String?,
    ): String? {
        if (threadItem) {
            if (!isEmptyOrWhitespace(title)) {
                return title
            } else {
                return StringUtils.formatThreadTitle(chanName!!, boardName, threadNumber!!)
            }
        } else {
            return StringUtils.formatBoardTitle(chanName!!, boardName, title)
        }
    }

    private class ListItem(
        val type: Type,
        val data: Int,
        val iconChan: Boolean,
        val iconResId: Int,
        val chanName: String?,
        val boardName: String?,
        val threadNumber: String?,
        val title: String?,
    ) {
        enum class Type {
            HEADER,
            RESTART,
            SECTION,
            PAGE,
            FAVORITE,
            MENU,
            CHAN,
        }

        val id: kotlin.Long

        init {
            id = nextItemId
        }

        constructor(
            type: Type,
            data: Int,
            iconResId: Int,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) : this(type, data, false, iconResId, chanName, boardName, threadNumber, title)

        constructor(
            type: Type,
            data: Int,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) : this(type, data, true, 0, chanName, boardName, threadNumber, title)

        constructor(
            type: Type,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) : this(type, 0, 0, chanName, boardName, threadNumber, title)

        constructor(type: Type, data: Int, iconResId: Int, title: String?) : this(
            type,
            data,
            false,
            iconResId,
            null,
            null,
            null,
            title,
        )

        val isThreadItem: Boolean
            get() = threadNumber != null

        fun compare(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
        ): Boolean =
            equals(this.chanName, chanName) &&
                equals(this.boardName, boardName) &&
                equals(this.threadNumber, threadNumber)

        companion object {
            val HEADER: ListItem = DrawerForm.ListItem(Type.HEADER, null, null, null, null)
            val RESTART: ListItem = DrawerForm.ListItem(Type.RESTART, null, null, null, null)

            private var nextItemId: kotlin.Long = 0
                get() = field++
        }
    }

    private val closeButtonListener: View.OnClickListener =
        View.OnClickListener { v ->
            val listItem = getItemFromChild(v)
            if (listItem != null && listItem.type == ListItem.Type.PAGE) {
                callback.onClosePage(listItem.chanName, listItem.boardName, listItem.threadNumber)
            }
        }

    @SuppressLint("NewApi")
    private val sectionButtonListener: View.OnClickListener =
        View.OnClickListener { v ->
            val listItem = getItemFromChild(v)
            if (listItem != null && listItem.type == ListItem.Type.SECTION) {
                when (listItem.data) {
                    SECTION_ACTION_CLOSE_ALL -> {
                        callback.onCloseAllPages()
                    }

                    SECTION_ACTION_FAVORITES_MENU -> {
                        var hasEnabled = false
                        val deleteFavoriteItems = ArrayList<FavoriteItem>()
                        val favoritesStorage = FavoritesStorage.getInstance()
                        for (itListItem in favorites) {
                            if (itListItem.isThreadItem) {
                                val favoriteItem =
                                    favoritesStorage.getFavorite(
                                        itListItem.chanName,
                                        itListItem.boardName,
                                        itListItem.threadNumber,
                                    )
                                if (favoriteItem != null) {
                                    hasEnabled = hasEnabled or favoriteItem.watcherEnabled
                                    if (getCounter(itListItem)!!.deleted) {
                                        deleteFavoriteItems.add(favoriteItem)
                                    }
                                }
                            }
                        }
                        val popupMenu: PopupMenu?
                        val resId =
                            getResourceId(
                                context,
                                android.R.attr.popupTheme,
                                0,
                            )
                        val context = v.getContext()
                        val popupContext: Context? =
                            if (resId != 0) ContextThemeWrapper(context, resId) else context
                        popupMenu =
                            PopupMenu(
                                popupContext,
                                v,
                                Gravity.END,
                                0,
                                R.style.Widget_OverlapPopupMenu,
                            )

                        popupMenu
                            .getMenu()
                            .add(0, FAVORITES_MENU_REFRESH, 0, R.string.refresh)
                            .setEnabled(hasEnabled)
                        popupMenu
                            .getMenu()
                            .add(0, FAVORITES_MENU_CLEAR_DELETED, 0, R.string.clear_deleted)
                            .setEnabled(!deleteFavoriteItems.isEmpty())
                        popupMenu
                            .getMenu()
                            .add(
                                0,
                                FAVORITES_MENU_HIDE_DELETED,
                                0,
                                if (isFavoritesHidedDeleted) R.string.favorites_show_deleted else R.string.favorites_hide_deleted,
                            ).setEnabled(!isFavoritesHidedAll)
                        popupMenu
                            .getMenu()
                            .add(
                                0,
                                FAVORITES_MENU_HIDE_ALL,
                                0,
                                if (isFavoritesHidedAll) R.string.favorites_show_all else R.string.favorites_hide_all,
                            ).setEnabled(true)
                        popupMenu.setOnMenuItemClickListener(
                            PopupMenu.OnMenuItemClickListener { item: MenuItem? ->
                                when (item!!.getItemId()) {
                                    FAVORITES_MENU_REFRESH -> {
                                        if (mergeChans) {
                                            watcherServiceClient.refreshAll(null)
                                        } else if (chanName != null) {
                                            watcherServiceClient.refreshAll(chanName)
                                        }
                                        return@OnMenuItemClickListener true
                                    }

                                    FAVORITES_MENU_CLEAR_DELETED -> {
                                        val builder =
                                            StringBuilder(
                                                context
                                                    .getString(R.string.threads_will_be_deleted__sentence),
                                            )
                                        builder.append("\n")
                                        for (favoriteItem in deleteFavoriteItems) {
                                            builder.append("\n\u2022 ").append(
                                                formatBoardThreadTitle(
                                                    true,
                                                    favoriteItem.boardName,
                                                    favoriteItem.threadNumber!!,
                                                    favoriteItem.title,
                                                ),
                                            )
                                        }
                                        showDeleteFavoritesDialog(
                                            fragmentManager,
                                            builder,
                                            deleteFavoriteItems,
                                        )
                                        return@OnMenuItemClickListener true
                                    }

                                    FAVORITES_MENU_HIDE_DELETED -> {
                                        if (isFavoritesHidedDeleted) {
                                            item.setTitle(R.string.favorites_show_deleted)
                                        } else {
                                            item.setTitle(R.string.favorites_hide_deleted)
                                        }
                                        setFavoritesHideDeleted(!isFavoritesHidedDeleted)
                                        if (isFavoritesHidedDeleted) {
                                            favorites.removeIf { fav: ListItem? ->
                                                fav!!.isThreadItem &&
                                                    getCounter(
                                                        fav,
                                                    )!!.deleted
                                            }
                                        } else {
                                            updateListFavorites()
                                        }
                                        notifyDataSetChanged()
                                        return@OnMenuItemClickListener true
                                    }

                                    FAVORITES_MENU_HIDE_ALL -> {
                                        if (isFavoritesHidedAll) {
                                            item.setTitle(R.string.favorites_show_all)
                                            setFavoritesHideDeleted(false)
                                        } else {
                                            item.setTitle(R.string.favorites_hide_all)
                                        }
                                        setFavoritesHideAll(!isFavoritesHidedAll)
                                        if (isFavoritesHidedAll) {
                                            favorites.removeIf { fav: ListItem? ->
                                                fav!!.type == ListItem.Type.FAVORITE &&
                                                    fav.isThreadItem
                                            }
                                        } else {
                                            updateListFavorites()
                                        }
                                        notifyDataSetChanged()
                                        return@OnMenuItemClickListener true
                                    }
                                }
                                false
                            },
                        )
                        popupMenu.show()
                    }
                }
            }
        }

    private enum class ViewType(
        val icon: Boolean,
        val watcher: Boolean,
        val closeable: Boolean,
    ) {
        HEADER(false, false, false),
        RESTART(false, false, false),
        SECTION(false, false, false),
        SECTION_BUTTON(true, false, false),
        ITEM(false, false, false),
        ITEM_ICON(true, false, false),
        WATCHER(false, true, false),
        WATCHER_ICON(true, true, false),
        CLOSEABLE(false, false, true),
        CLOSEABLE_ICON(true, false, true),
    }

    override fun getItemViewType(position: Int): Int {
        val listItem = getItem(position)
        val viewType: ViewType =
            when (listItem.type) {
                ListItem.Type.HEADER -> {
                    ViewType.HEADER
                }

                ListItem.Type.RESTART -> {
                    ViewType.RESTART
                }

                ListItem.Type.SECTION -> {
                    if (listItem.iconChan || listItem.iconResId != 0) {
                        ViewType.SECTION_BUTTON
                    } else {
                        ViewType.SECTION
                    }
                }

                ListItem.Type.PAGE -> {
                    if (mergeChans) ViewType.CLOSEABLE_ICON else ViewType.CLOSEABLE
                }

                ListItem.Type.FAVORITE -> {
                    if (listItem.threadNumber != null) {
                        val watcherSupported = watcherSupportSet.contains(listItem.chanName)
                        if (mergeChans) {
                            if (watcherSupported) ViewType.WATCHER_ICON else ViewType.ITEM_ICON
                        } else {
                            if (watcherSupported) ViewType.WATCHER else ViewType.ITEM
                        }
                    } else {
                        if (mergeChans) ViewType.ITEM_ICON else ViewType.ITEM
                    }
                }

                ListItem.Type.MENU -> {
                    ViewType.ITEM_ICON
                }

                ListItem.Type.CHAN -> {
                    ViewType.ITEM_ICON
                }
            }
        return viewType.ordinal
    }

    private val categoriesArray: Array<MutableList<ListItem>> = Array(2) { ArrayList() }

    private fun prepareCategoriesArray(): Int {
        when (categoriesOrder) {
            CategoriesOrder.PAGES_FIRST -> {
                categoriesArray[0] = pages
                categoriesArray[1] = favorites
                return 2
            }

            CategoriesOrder.FAVORITES_FIRST -> {
                categoriesArray[0] = favorites
                categoriesArray[1] = pages
                return 2
            }

            CategoriesOrder.HIDE_PAGES -> {
                categoriesArray[0] = favorites
                return 1
            }

            else -> {
                return 0
            }
        }
    }

    override fun getItemCount(): Int {
        var count = if (showRestartButton) 2 else 1
        if (chanSelectMode) {
            count += chans.size
        } else {
            val arraySize = prepareCategoriesArray()
            val categoriesArray = this.categoriesArray
            for (i in 0..<arraySize) {
                count += categoriesArray[i].size
            }
            count += menu.size
        }
        return count
    }

    private fun getItem(position: Int): ListItem {
        var position = position
        if (position == 0) {
            return ListItem.Companion.HEADER
        }
        position--
        if (showRestartButton) {
            if (position == 0) {
                return ListItem.Companion.RESTART
            }
            position--
        }
        if (position >= 0) {
            if (chanSelectMode) {
                if (position < chans.size) {
                    return chans.get(position)
                }
            } else {
                val arraySize = prepareCategoriesArray()
                val categoriesArray = this.categoriesArray
                for (i in 0..<arraySize) {
                    val listItems = categoriesArray[i]
                    if (position < listItems.size) {
                        return listItems.get(position)
                    }
                    position -= listItems.size
                    if (position < 0) {
                        throw IndexOutOfBoundsException()
                    }
                }
                if (position < menu.size) {
                    return menu.get(position)
                }
            }
        }
        throw IndexOutOfBoundsException()
    }

    override fun getItemId(position: Int): kotlin.Long = getItem(position).id

    private fun makeCommonTextView(section: Boolean): TextView {
        val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
        setTextSizeScaled(textView, 14)
        textView.setGravity(Gravity.CENTER_VERTICAL)
        textView.setEllipsize(TextUtils.TruncateAt.END)
        textView.setSingleLine(true)
        textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
        var color = textView.getTextColors().getDefaultColor()
        if (section) {
            color = color and 0x5effffff
        } else {
            color = color and -0x22000001
        }
        textView.setTextColor(color)

        return textView
    }

    private fun createItem(
        viewType: ViewType,
        density: Float,
    ): ViewHolder {
        val size = (48f * density).toInt()
        val linearLayout = LinearLayout(context)
        linearLayout.setOrientation(LinearLayout.HORIZONTAL)
        linearLayout.setGravity(Gravity.CENTER_VERTICAL)
        var iconView: ImageView? = null
        if (viewType.icon) {
            iconView = ImageView(context)
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
            linearLayout.addView(iconView, (24f * density).toInt(), size)
            iconView.setImageTintList(ColorStateList.valueOf(drawerIconColor))
        }
        val textView = makeCommonTextView(false)
        linearLayout.addView(textView, LinearLayout.LayoutParams(0, size, 1f))
        var watcherView: WatcherView? = null
        if (viewType.watcher) {
            watcherView = WatcherView(context, watcherViewColorSet)
            watcherView.setOnClickListener(watcherClickListener)
            linearLayout.addView(watcherView, size, size)
        }
        if (!viewType.watcher && viewType.closeable) {
            val closeView = ImageView(context)
            closeView.setScaleType(ImageView.ScaleType.CENTER)
            closeView.setImageResource(getResourceId(context, R.attr.iconButtonCancel, 0))
            closeView.setImageTintList(
                getColorStateList(
                    closeView.getContext(),
                    android.R.attr.textColorPrimary,
                ),
            )

            closeView.setBackgroundResource(
                getResourceId(
                    context,
                    android.R.attr.borderlessButtonStyle,
                    android.R.attr.background,
                    0,
                ),
            )
            linearLayout.addView(closeView, size, size)
            closeView.setOnClickListener(closeButtonListener)
        }
        var layoutLeftDp = 0
        var layoutRightDp = 0
        var textLeftDp: Int
        var textRightDp: Int
        textLeftDp = 16
        textRightDp = 16
        if (viewType.icon) {
            layoutLeftDp = 16
            textLeftDp = 32
        }
        if (viewType.watcher || viewType.closeable) {
            layoutRightDp = 4
            textRightDp = 8
        }

        linearLayout.setPadding(
            (layoutLeftDp * density).toInt(),
            0,
            (layoutRightDp * density).toInt(),
            0,
        )
        textView.setPadding((textLeftDp * density).toInt(), 0, (textRightDp * density).toInt(), 0)
        setSelectableItemBackground(linearLayout)
        linearLayout.setLayoutParams(
            RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ),
        )
        return DrawerForm.ViewHolder(linearLayout, iconView, textView, watcherView)
    }

    private fun createSection(
        parent: ViewGroup?,
        button: Boolean,
        density: Float,
    ): ViewHolder {
        val linearLayout = LinearLayout(context)
        linearLayout.setOrientation(LinearLayout.VERTICAL)
        val linearLayout2 = LinearLayout(context)
        linearLayout2.setOrientation(LinearLayout.HORIZONTAL)
        linearLayout.addView(
            linearLayout2,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val textView = makeCommonTextView(true)
        var layoutParams = LinearLayout.LayoutParams(0, (32f * density).toInt(), 1f)
        layoutParams.setMargins(
            (16f * density).toInt(),
            (8f * density).toInt(),
            (16f * density).toInt(),
            (8f * density).toInt(),
        )
        linearLayout2.addView(textView, layoutParams)
        var imageView: ImageView? = null
        if (button) {
            imageView = ImageView(context)
            imageView.setScaleType(ImageView.ScaleType.CENTER)
            imageView.setBackgroundResource(
                getResourceId(
                    context,
                    android.R.attr.borderlessButtonStyle,
                    android.R.attr.background,
                    0,
                ),
            )
            imageView.setOnClickListener(sectionButtonListener)
            imageView.setImageTintList(textView.getTextColors())
            val size = (48f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            layoutParams.rightMargin = (4f * density).toInt()
            linearLayout2.addView(imageView, layoutParams)
        }
        linearLayout.setLayoutParams(
            RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ),
        )
        return DrawerForm.ViewHolder(linearLayout, imageView, textView, null)
    }

    private val clickCallback =
        ClickCallback { holder: ViewHolder, position: Int, item: Void?, longClick: Boolean ->
            if (longClick) {
                return@ClickCallback onItemLongClick(holder)
            } else {
                onItemClick(position)
                return@ClickCallback true
            }
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val density = obtainDensity(context)
        val enumViewType = ViewType.entries[viewType]
        return when (enumViewType) {
            ViewType.HEADER -> {
                DrawerForm.ViewHolder(headerView, null, null, null)
            }

            ViewType.RESTART -> {
                DrawerForm.ViewHolder(restartView, null, null, null)
            }

            ViewType.SECTION, ViewType.SECTION_BUTTON -> {
                createSection(parent, enumViewType.icon, density)
            }

            ViewType.ITEM, ViewType.ITEM_ICON, ViewType.WATCHER, ViewType.WATCHER_ICON, ViewType.CLOSEABLE, ViewType.CLOSEABLE_ICON -> {
                ListViewUtils.bind<Void?, ViewHolder>(
                    createItem(enumViewType, density),
                    true,
                    null,
                    clickCallback,
                )
            }
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val listItem = getItem(position)
        when (listItem.type) {
            ListItem.Type.HEADER, ListItem.Type.RESTART -> {}

            ListItem.Type.PAGE, ListItem.Type.FAVORITE -> {
                holder.text!!.setText(
                    formatBoardThreadTitle(
                        listItem.isThreadItem,
                        listItem.boardName,
                        listItem.threadNumber,
                        listItem.title,
                    ),
                )
                if (listItem.type == ListItem.Type.FAVORITE &&
                    listItem.isThreadItem &&
                    watcherSupportSet.contains(listItem.chanName)
                ) {
                    holder.watcher!!.update(getCounter(listItem)!!)
                }
            }

            ListItem.Type.SECTION, ListItem.Type.MENU, ListItem.Type.CHAN -> {
                holder.text!!.setText(listItem.title)
            }
        }
        if (holder.icon != null) {
            if (listItem.iconChan) {
                if (!chanIcons.containsKey(listItem.chanName)) {
                    val drawable = ChanManager.getInstance().getIcon(get(listItem.chanName))
                    chanIcons.put(listItem.chanName, drawable)
                }
                val chanIcon = chanIcons.get(listItem.chanName)
                holder.icon.setImageDrawable(if (chanIcon != null) chanIcon.newInstance() else null)
            } else if (listItem.iconResId != 0) {
                holder.icon.setImageResource(listItem.iconResId)
            } else {
                throw IllegalStateException()
            }
        }
    }

    class ViewHolder(
        itemView: View,
        val icon: ImageView?,
        val text: TextView?,
        val watcher: WatcherView?,
    ) : RecyclerView.ViewHolder(itemView),
        OnTouchListener {
        private var originalTextColors: ColorStateList? = null
        private var originalTintColors: ColorStateList? = null

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        fun setDragging(
            dragging: Boolean,
            activeColor: Int,
        ) {
            if (dragging) {
                if (originalTextColors == null) {
                    originalTextColors = text!!.getTextColors()
                }
                text!!.setTextColor(activeColor)
                if (icon != null) {
                    if (originalTintColors == null) {
                        originalTintColors = icon.getImageTintList()
                    }
                    icon.setImageTintList(ColorStateList.valueOf(activeColor))
                }
            } else {
                if (originalTextColors != null) {
                    text!!.setTextColor(originalTextColors)
                }
                if (icon != null && originalTintColors != null) {
                    icon.setImageTintList(originalTintColors)
                }
            }
        }

        private var multipleFingersCountingTime = false
        private var multipleFingersTime: kotlin.Long = 0
        private var multipleFingersStartTime: kotlin.Long = 0

        init {
            itemView.setOnTouchListener(this)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            v: View?,
            event: MotionEvent,
        ): Boolean {
            when (event.getActionMasked()) {
                MotionEvent.ACTION_DOWN -> {
                    multipleFingersCountingTime = false
                    multipleFingersStartTime = 0L
                    multipleFingersTime = 0L
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!multipleFingersCountingTime) {
                        multipleFingersCountingTime = true
                        multipleFingersStartTime = SystemClock.elapsedRealtime()
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerCount() <= 2) {
                        if (multipleFingersCountingTime) {
                            multipleFingersCountingTime = false
                            multipleFingersTime += SystemClock.elapsedRealtime() - multipleFingersStartTime
                        }
                    }
                }
            }
            return false
        }

        val isMultipleFingers: Boolean
            get() {
                var time = multipleFingersTime
                if (multipleFingersCountingTime) {
                    time += SystemClock.elapsedRealtime() - multipleFingersStartTime
                }
                return time >= ViewConfiguration.getLongPressTimeout() / 10
            }
    }

    private fun getItemFromChild(child: View?): ListItem? {
        val view = getRootViewInList(child)
        val holder = ListViewUtils.getViewHolder(view!!, ViewHolder::class.java)
        val position = holder!!.getBindingAdapterPosition()
        return if (position >= 0) getItem(position) else null
    }

    private fun needDivider(
        current: ListItem,
        next: ListItem,
    ): Boolean =
        current.type == ListItem.Type.HEADER ||
            current.type == ListItem.Type.RESTART ||
            current.type != ListItem.Type.CHAN &&
            next.type == ListItem.Type.CHAN ||
            current.type != ListItem.Type.MENU &&
            next.type == ListItem.Type.MENU ||
            current.type == ListItem.Type.MENU &&
            current.data == MENU_ITEM_BOARDS &&
            (next.type != ListItem.Type.MENU || next.data != MENU_ITEM_USER_BOARDS) ||
            current.type == ListItem.Type.MENU &&
            current.data == MENU_ITEM_USER_BOARDS

    private fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration {
        val density = obtainDensity(context)
        val padding = (8f * density).toInt()
        val current = getItem(position)
        val next = if (position + 1 < getItemCount()) getItem(position + 1) else null
        if (next == null) {
            return configuration.need(false).vertical(0, 0)
        } else if (next.type == ListItem.Type.SECTION) {
            return configuration.need(true).vertical(padding, 0)
        } else if (needDivider(current, next)) {
            return configuration.need(true).vertical(padding, padding)
        } else {
            return configuration.need(false).vertical(0, 0)
        }
    }

    private fun getCounter(listItem: ListItem): WatcherService.Counter? =
        watcherServiceClient.getCounter(
            listItem.chanName!!,
            listItem.boardName,
            listItem.threadNumber!!,
        )

    private val watcherClickListener =
        View.OnClickListener { v: View? ->
            val listItem = getItemFromChild(v)
            if (listItem != null) {
                FavoritesStorage.getInstance().setWatcherEnabled(
                    listItem.chanName,
                    listItem.boardName,
                    listItem.threadNumber,
                    null,
                )
            }
        }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun onWatcherUpdate(
        chanName: String,
        boardName: String?,
        threadNumber: String?,
        counter: WatcherService.Counter,
    ) {
        if (counter.deleted && isFavoritesHidedDeleted) {
            favorites.removeIf { fav ->
                fav.type == ListItem.Type.FAVORITE &&
                    (
                        fav.chanName == chanName &&
                            fav.boardName == boardName &&
                            fav.threadNumber != null &&
                            fav.threadNumber == threadNumber
                    )
            }
            notifyDataSetChanged()
        } else if (!isFavoritesHidedAll) {
            if (mergeChans || chanName == this.chanName) {
                val childCount = recyclerView.getChildCount()
                for (i in 0..<childCount) {
                    val holder =
                        recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as ViewHolder
                    val position = holder.getBindingAdapterPosition()
                    if (position >= 0) {
                        val listItem = getItem(position)
                        if (listItem.type == ListItem.Type.FAVORITE &&
                            listItem.compare(chanName, boardName, threadNumber)
                        ) {
                            holder.watcher!!.update(counter)
                            break
                        }
                    }
                }
            }
        }
    }

    private val chanDragState = DragState()
    private val favoriteDragState = DragState()

    init {
        val enabledColor = getTheme(context)!!.accent
        val disabledColor = -0x99999a
        val unavailableColor = mixColors(disabledColor, enabledColor and 0x7fffffff)
        watcherViewColorSet = ColorSet(enabledColor, unavailableColor, disabledColor)

        recyclerView = PaddedRecyclerView(context)
        recyclerView.setId(R.id.drawer_recycler_view)
        recyclerView.setMotionEventSplittingEnabled(false)
        recyclerView.setClipToPadding(false)
        recyclerView.setEdgeEffectShift(this)
        recyclerView.setLayoutManager(
            object : LinearLayoutManager(recyclerView.getContext()) {
                override fun requestChildRectangleOnScreen(
                    parent: RecyclerView,
                    child: View,
                    rect: Rect,
                    immediate: Boolean,
                    focusedChildVisible: Boolean,
                ): Boolean {
                    if (child === headerView) {
                        // Keep EditText on top and don't allow LinearLayoutManager weird scrolls
                        val dy = child.getTop() - parent.getPaddingTop()
                        if (dy != 0) {
                            if (immediate) {
                                parent.scrollBy(0, dy)
                            } else {
                                parent.smoothScrollBy(0, dy)
                            }
                            return true
                        }
                    }
                    return false
                }
            },
        )
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    // Hide keyboard when list is scrolled
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        val focusView = recyclerView.getFocusedChild()
                        if (focusView != null) {
                            focusView.clearFocus()
                            hideKeyboard()
                        }
                    }
                }
            },
        )
        setHasStableIds(true)
        recyclerView.setAdapter(this)
        val dividerItemDecoration =
            DividerItemDecoration(
                recyclerView.getContext(),
                DividerItemDecoration.Callback { c: DividerItemDecoration.Configuration?, position: Int ->
                    configureDivider(
                        c!!,
                        position,
                    ).translate(false)
                },
            )
        recyclerView.addItemDecoration(dividerItemDecoration)
        dividerItemDecoration.setAboveCallback(
            AboveCallback { position: Int ->
                val listItem = getItem(position)
                listItem.type == ListItem.Type.SECTION || listItem.type == ListItem.Type.MENU
            },
        )
        recyclerView.setItemAnimator(null)
        sortableHelper = SortableHelper<ViewHolder>(recyclerView, this)
        drawerIconColor = getColor(context, android.R.attr.textColorSecondary)

        val density = obtainDensity(context)

        val headerView = LinearLayout(context)
        headerView.setOrientation(LinearLayout.VERTICAL)
        headerView.setLayoutParams(
            RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ),
        )
        this.headerView = headerView

        val editTextContainer = LinearLayout(context)
        editTextContainer.setGravity(Gravity.CENTER_VERTICAL)
        // Reset focus to parent view
        editTextContainer.setFocusableInTouchMode(true)
        headerView.addView(editTextContainer)

        searchEdit = SafePasteEditText(context)
        searchEdit.setOnKeyListener(
            View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
                if (event!!.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    v!!.clearFocus()
                }
                false
            },
        )
        searchEdit.setHint(context.getString(R.string.code_number_address))
        searchEdit.setOnEditorActionListener(this)
        searchEdit.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI)

        val searchIcon = ImageView(context, null, android.R.attr.buttonBarButtonStyle)
        searchIcon.setImageResource(getResourceId(context, R.attr.iconButtonForward, 0))
        searchIcon.setImageTintList(
            getColorStateList(
                searchIcon.getContext(),
                android.R.attr.textColorPrimary,
            ),
        )

        searchIcon.setScaleType(ImageView.ScaleType.CENTER)
        searchIcon.setOnClickListener(View.OnClickListener { v: View? -> onSearchClick() })
        editTextContainer.addView(
            searchEdit,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        editTextContainer.addView(searchIcon, (40f * density).toInt(), (40f * density).toInt())
        editTextContainer.setPadding(
            (12f * density).toInt(),
            (8f * density).toInt(),
            (8f * density).toInt(),
            0,
        )

        val selectorContainer = LinearLayout(context)
        this.selectorContainer = selectorContainer
        selectorContainer.setBackgroundResource(
            getResourceId(
                context,
                android.R.attr.selectableItemBackground,
                0,
            ),
        )
        selectorContainer.setOrientation(LinearLayout.HORIZONTAL)
        selectorContainer.setGravity(Gravity.CENTER_VERTICAL)
        selectorContainer.setOnClickListener(
            View.OnClickListener { v: View? ->
                hideKeyboard()
                setChanSelectMode(!chanSelectMode)
            },
        )
        headerView.addView(selectorContainer)
        selectorContainer.setMinimumHeight((40f * density).toInt())
        selectorContainer.setPadding((16f * density).toInt(), 0, (16f * density).toInt(), 0)
        (selectorContainer.getLayoutParams() as LinearLayout.LayoutParams).topMargin =
            (4f * density).toInt()

        chanNameView = TextView(context, null, android.R.attr.textAppearanceListItem)
        setTextSizeScaled(chanNameView, 14)
        chanNameView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

        selectorContainer.addView(
            chanNameView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )

        chanSelectorIcon = ImageView(context)
        chanSelectorIcon.setImageResource(
            getResourceId(
                context,
                R.attr.iconButtonDropDown,
                0,
            ),
        )
        chanSelectorIcon.setImageTintList(
            getColorStateList(
                context,
                android.R.attr.textColorPrimary,
            ),
        )

        selectorContainer.addView(
            chanSelectorIcon,
            (24f * density).toInt(),
            (24f * density).toInt(),
        )
        (chanSelectorIcon.getLayoutParams() as LinearLayout.LayoutParams).gravity =
            (
                Gravity.CENTER_VERTICAL
                    or Gravity.END
            )

        val restartView = LinearLayout(context)
        restartView.setOrientation(LinearLayout.VERTICAL)
        this.restartView = restartView

        val restartTextView = TextView(context, null, android.R.attr.textAppearanceSmall)
        restartTextView.setText(R.string.new_extensions_installed__sentence)
        restartTextView.setTextColor(getColor(context, android.R.attr.textColorPrimary))
        restartTextView.setPadding(
            (16f * density).toInt(),
            (8f * density).toInt(),
            (16f * density).toInt(),
            (8f * density).toInt(),
        )

        restartView.addView(
            restartTextView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val restartButtonViewHolder = createItem(ViewType.ITEM, density)
        restartButtonViewHolder.text!!.setText(R.string.restart)
        restartButtonViewHolder.itemView.setOnClickListener(View.OnClickListener { v: View? -> callback.restartApplication() })
        restartView.addView(
            restartButtonViewHolder.itemView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        updatePreferencesWithoutConfiguration()
        updateChansWithoutConfiguration()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onDragStart(holder: ViewHolder) {
        chanDragState.reset()
        favoriteDragState.reset()
        holder.setDragging(true, watcherViewColorSet.enabledColor)
        callback.onDraggingStateChanged(true)
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onDragFinish(
        holder: ViewHolder?,
        cancelled: Boolean,
    ) {
        if (!cancelled) {
            val chanMovedTo = chanDragState.getMovedTo()
            val favoriteMovedTo = favoriteDragState.getMovedTo()
            if (chanMovedTo >= 0) {
                val chanNames = ArrayList<String?>()
                for (listItem in chans) {
                    chanNames.add(listItem.chanName)
                }
                chansOrder = chanNames
                // Regroup favorite threads
                if (mergeChans) {
                    updateItems(false, true)
                }
            } else if (favoriteMovedTo >= 0) {
                // "to" is always > 0 since favorites list contains header
                val listItem = favorites.get(favoriteMovedTo)
                val afterListItem = favorites.get(favoriteMovedTo - 1)
                val favoritesStorage = FavoritesStorage.getInstance()
                val favoriteItem =
                    favoritesStorage.getFavorite(
                        listItem.chanName,
                        listItem.boardName,
                        listItem.threadNumber,
                    )
                val afterFavoriteItem =
                    if (afterListItem.type ==
                        ListItem.Type.FAVORITE &&
                        afterListItem.chanName == favoriteItem!!.chanName
                    ) {
                        favoritesStorage.getFavorite(
                            afterListItem.chanName,
                            afterListItem.boardName,
                            afterListItem.threadNumber,
                        )
                    } else {
                        null
                    }
                favoritesStorage.moveAfter(favoriteItem!!, afterFavoriteItem)
            }
        }
        holder!!.setDragging(false, 0)
        callback.onDraggingStateChanged(false)
    }

    override fun onDragCanMove(
        fromHolder: ViewHolder,
        toHolder: ViewHolder,
    ): Boolean {
        val from = getItem(fromHolder.getBindingAdapterPosition())
        val to = getItem(toHolder.getBindingAdapterPosition())
        return from.type == to.type &&
            (
                from.type == ListItem.Type.CHAN ||
                    from.type == ListItem.Type.FAVORITE &&
                    equals(
                        from.chanName,
                        to.chanName,
                    ) &&
                    (from.threadNumber == null) == (to.threadNumber == null)
            )
    }

    override fun onDragMove(
        fromHolder: ViewHolder,
        toHolder: ViewHolder,
    ): Boolean {
        val fromIndex = fromHolder.getBindingAdapterPosition()
        val toIndex = toHolder.getBindingAdapterPosition()
        val from = getItem(fromIndex)
        val to = getItem(toIndex)
        val chansFrom = chans.indexOf(from)
        val chansTo = chans.indexOf(to)
        val favoritesFrom = favorites.indexOf(from)
        val favoritesTo = favorites.indexOf(to)
        var workList: ArrayList<ListItem>? = null
        var dragState: DragState? = null
        var workFrom = -1
        var workTo = -1
        if (chansFrom >= 0 && chansTo >= 0) {
            workList = chans
            dragState = chanDragState
            workFrom = chansFrom
            workTo = chansTo
        } else if (favoritesFrom >= 0 && favoritesTo >= 0) {
            workList = favorites
            dragState = favoriteDragState
            workFrom = favoritesFrom
            workTo = favoritesTo
        }
        if (workList != null && dragState != null) {
            workList.add(workTo, workList.removeAt(workFrom))
            notifyItemMoved(fromIndex, toIndex)
            dragState.set(workFrom, workTo)
            return true
        }
        return false
    }

    companion object {
        const val RESULT_REMOVE_ERROR_MESSAGE: Int = 0x00000001
        const val RESULT_SUCCESS: Int = 0x00000002

        const val MENU_ITEM_BOARDS: Int = 1
        const val MENU_ITEM_USER_BOARDS: Int = 2
        const val MENU_ITEM_HISTORY: Int = 3
        const val MENU_ITEM_PREFERENCES: Int = 4

        private fun showPageFavoriteMenu(
            fragmentManager: FragmentManager,
            isFavorite: Boolean,
            isThread: Boolean,
            chanName: String,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val dialogMenu = DialogMenu(provider.context)
                    dialogMenu.add(
                        R.string.copy_link,
                        Runnable {
                            onCopyShareLink(
                                context,
                                isThread,
                                false,
                                chanName,
                                boardName,
                                threadNumber,
                                title,
                            )
                        },
                    )
                    if (isThread) {
                        dialogMenu.add(
                            R.string.share_link,
                            Runnable {
                                onCopyShareLink(
                                    context,
                                    isThread,
                                    true,
                                    chanName,
                                    boardName,
                                    threadNumber,
                                    title,
                                )
                            },
                        )
                    }
                    if (isFavorite) {
                        dialogMenu.add(
                            R.string.remove_from_favorites,
                            Runnable {
                                FavoritesStorage
                                    .getInstance()
                                    .remove(chanName, boardName, threadNumber)
                            },
                        )
                        if (threadNumber != null) {
                            dialogMenu.add(
                                R.string.rename,
                                Runnable {
                                    showRenameFragment(
                                        provider.fragmentManager,
                                        chanName,
                                        boardName,
                                        threadNumber,
                                        title,
                                    )
                                },
                            )
                        }
                    } else if (!FavoritesStorage
                            .getInstance()
                            .hasFavorite(chanName, boardName, threadNumber)
                    ) {
                        dialogMenu.add(
                            R.string.add_to_favorites,
                            Runnable {
                                if (isThread) {
                                    FavoritesStorage
                                        .getInstance()
                                        .add(chanName, boardName, threadNumber!!, title, true)
                                } else {
                                    FavoritesStorage.getInstance().add(chanName, boardName)
                                }
                            },
                        )
                    }
                    dialogMenu.create()
                },
            )
        }

        private fun onCopyShareLink(
            context: Context,
            isThread: Boolean,
            share: Boolean,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) {
            val chan = get(chanName)
            val uri =
                if (isThread) {
                    chan.locator.safe(true).createThreadUri(boardName, threadNumber)
                } else {
                    chan.locator.safe(true).createBoardUri(boardName, 0)
                }
            if (uri != null) {
                if (share) {
                    shareLink(
                        context,
                        if (StringUtils.isEmptyOrWhitespace(title)) {
                            uri.toString()
                        } else {
                            title
                        },
                        uri,
                    )
                } else {
                    StringUtils.copyToClipboard(context, uri.toString())
                }
            }
        }

        private fun showRenameFragment(
            fragmentManager: FragmentManager,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            title: String?,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val editText: EditText = SafePasteEditText(context)
                    editText.setId(android.R.id.edit)
                    editText.setSingleLine(true)
                    editText.setText(title)
                    editText.setSelection(editText.length())
                    val linearLayout = LinearLayout(context)
                    linearLayout.setOrientation(LinearLayout.HORIZONTAL)
                    linearLayout.addView(
                        editText,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    val padding =
                        context.getResources().getDimensionPixelSize(
                            R.dimen
                                .dialog_padding_view,
                        )
                    linearLayout.setPadding(padding, padding, padding, padding)
                    val dialog =
                        AlertDialog
                            .Builder(context)
                            .setView(linearLayout)
                            .setTitle(R.string.rename)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(
                                android.R.string.ok,
                                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                    val newTitle = editText.getText().toString()
                                    FavoritesStorage
                                        .getInstance()
                                        .updateTitle(chanName, boardName, threadNumber, newTitle, true)
                                },
                            ).create()
                    dialog
                        .getWindow()!!
                        .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                    dialog
                },
            )
        }

        private val PATTERN_NAVIGATION_BOARD_THREAD: Pattern = Pattern.compile("([\\w_-]+) (\\d+)")
        private val PATTERN_NAVIGATION_BOARD: Pattern = Pattern.compile("/?([\\w_-]+)")
        private val PATTERN_NAVIGATION_THREAD: Pattern = Pattern.compile("#(\\d+)")

        private fun showSearchHelp(
            fragmentManager: FragmentManager,
            searchHelpFormat: SearchHelpFormat,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val html =
                        readRawResourceString(context.getResources(), R.raw.markup_drawer_search)
                            .replace("__REPLACE_BOARD_NAME__", searchHelpFormat.boardName)
                            .replace("__REPLACE_THREAD_NUMBER__", searchHelpFormat.threadNumber)
                            .replace("__REPLACE_THREAD_URL__", searchHelpFormat.threadUrl)
                    AlertDialog
                        .Builder(context)
                        .setTitle(R.string.code_number_address)
                        .setMessage(BUILDER_SEARCH_HELP.fromHtmlReduced(html))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                },
            )
        }

        private val BUILDER_SEARCH_HELP =
            ChanMarkup.MarkupBuilder(
                ChanMarkup.MarkupBuilder.Constructor { markup: ChanMarkup? ->
                    markup!!.addTag("h1", ChanMarkup.TAG_BOLD)
                    markup.addTag("u", ChanMarkup.TAG_UNDERLINE)
                },
            )

        private const val SECTION_ACTION_CLOSE_ALL = 0
        private const val SECTION_ACTION_FAVORITES_MENU = 1

        private const val FAVORITES_MENU_REFRESH = 1
        private const val FAVORITES_MENU_CLEAR_DELETED = 2
        private const val FAVORITES_MENU_HIDE_DELETED = 3
        private const val FAVORITES_MENU_HIDE_ALL = 4

        private fun showDeleteFavoritesDialog(
            fragmentManager: FragmentManager,
            message: CharSequence?,
            deleteFavoriteItems: MutableList<FavoriteItem>,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    AlertDialog
                        .Builder(provider!!.context)
                        .setMessage(message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                val favoritesStorage = FavoritesStorage.getInstance()
                                for (favoriteItem in deleteFavoriteItems) {
                                    favoritesStorage.remove(
                                        favoriteItem.chanName,
                                        favoriteItem.boardName,
                                        favoriteItem.threadNumber,
                                    )
                                }
                            },
                        ).create()
                },
            )
        }
    }
}
