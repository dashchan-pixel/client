package com.mishiranu.dashchan.ui.navigator

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.isActiveScrollbar
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.ui.ContentFragment
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.page.ListPage
import com.mishiranu.dashchan.ui.navigator.page.ListPage.InitRequest
import com.mishiranu.dashchan.ui.navigator.page.ListPage.InitSearch
import com.mishiranu.dashchan.ui.navigator.page.ListPage.Retainable
import com.mishiranu.dashchan.widget.CustomSearchView
import com.mishiranu.dashchan.widget.CustomSearchView.OnSubmitListener
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.ListPosition
import com.mishiranu.dashchan.widget.MenuExpandListener
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.PullableWrapper
import com.mishiranu.dashchan.widget.PullableWrapper.PullStateListener
import com.mishiranu.dashchan.widget.ViewFactory.ErrorHolder
import com.mishiranu.dashchan.widget.ViewFactory.createErrorLayout
import com.mishiranu.dashchan.widget.ViewFactory.createProgressLayout
import java.util.UUID

class PageFragment : ContentFragment, FragmentHandler.Callback, ListPage.Callback {
    interface Callback {
        val uiManager: UiManager?
        fun getRetainableExtra(retainId: String?): Retainable?
        fun storeRetainableExtra(retainId: String?, extra: Retainable?)
        fun setPageTitle(title: String?, subtitle: String?)
        fun invalidateHomeUpState()
        fun handleRedirect(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?
        )

        fun closeCurrentPage()
    }

    constructor()

    constructor(page: Page?, retainId: String?) {
        val args = Bundle()
        args.putParcelable(EXTRA_PAGE, page)
        args.putString(EXTRA_RETAIN_ID, retainId)
        setArguments(args)
    }

    val page: Page?
        get() = BundleCompat.getParcelable<Page?>(
            requireArguments(),
            EXTRA_PAGE,
            Page::class.java
        )

    val retainId: String?
        get() = requireArguments().getString(EXTRA_RETAIN_ID)

    private val callback: Callback
        get() = requireActivity() as Callback

    private var listPage: ListPage? = null
    private var progressView: View? = null
    private var errorHolder: ErrorHolder? = null
    private var recyclerView: PaddedRecyclerView? = null

    private var searchView: CustomSearchView? = null
    private var searchMenuItem: MenuItem? = null

    private var actionBarLockerPull: String? = null
    private var actionBarLockerSearch: String? = null

    private var listPosition: ListPosition? = null
    private var parcelableExtra: Parcelable? = null
    private var initErrorItem: ErrorItem? = null
    private var searchCurrentQuery: String? = null
    private var searchSubmitQuery: String? = null
    private var searchFocused = false

    private var initRequest: InitRequest? = null
    private var resetScroll = false

    private var allowShowScale = false
    private var doOnResume: Runnable? = null
    var isBackHandled: Boolean = false
        private set
    private var saveToStack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listPosition = if (savedInstanceState != null && !resetScroll)
            BundleCompat.getParcelable<ListPosition?>(
                savedInstanceState,
                EXTRA_LIST_POSITION,
                ListPosition::class.java
            )
        else
            null
        parcelableExtra = if (savedInstanceState != null) BundleCompat
            .getParcelable<Parcelable?>(
                savedInstanceState,
                EXTRA_PARCELABLE_EXTRA,
                Parcelable::class.java
            ) else null
        initErrorItem = if (savedInstanceState != null) BundleCompat
            .getParcelable<ErrorItem?>(
                savedInstanceState,
                EXTRA_INIT_ERROR_ITEM,
                ErrorItem::class.java
            ) else null
        searchCurrentQuery = if (savedInstanceState != null) savedInstanceState
            .getString(EXTRA_SEARCH_CURRENT_QUERY) else null
        searchSubmitQuery = if (savedInstanceState != null) savedInstanceState
            .getString(EXTRA_SEARCH_SUBMIT_QUERY) else null
        searchFocused = savedInstanceState != null && savedInstanceState.getBoolean(
            EXTRA_SEARCH_FOCUSED
        )
        resetScroll = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedInstanceState: Bundle?
    ): View {
        actionBarLockerPull = "pull-" + UUID.randomUUID()
        actionBarLockerSearch = "search-" + UUID.randomUUID()

        val layout = ExpandedLayout(container.getContext(), false)
        recyclerView = PaddedRecyclerView(layout.getContext())
        layout.addView(
            recyclerView, ExpandedLayout.LayoutParams.MATCH_PARENT,
            ExpandedLayout.LayoutParams.MATCH_PARENT
        )
        layout.setRecyclerView(recyclerView)
        recyclerView!!.setMotionEventSplittingEnabled(false)
        recyclerView!!.setVerticalScrollBarEnabled(true)
        recyclerView!!.setClipToPadding(false)
        recyclerView!!.setFastScrollerEnabled(isActiveScrollbar)
        progressView = createProgressLayout(layout)
        errorHolder = createErrorLayout(layout)
        errorHolder!!.layout.setVisibility(View.GONE)
        layout.addView(errorHolder!!.layout)

        allowShowScale = true
        listPage = this.page!!.content.newPage()
        recyclerView!!.pullable!!.setOnPullListener(listPage!!)
        recyclerView!!.pullable!!.setPullStateListener(PullStateListener { wrapper: PullableWrapper?, busy: Boolean ->
            (requireActivity() as FragmentHandler)
                .setActionBarLocked(actionBarLockerPull!!, busy)
        })
        return layout
    }

    public override fun onDestroyView() {
        super.onDestroyView()

        val fragmentHandler = requireActivity() as FragmentHandler
        fragmentHandler.setActionBarLocked(actionBarLockerPull!!, false)
        fragmentHandler.setActionBarLocked(actionBarLockerSearch!!, false)

        if (listPage != null) {
            listPage!!.destroy()
            listPage = null
        }
        progressView = null
        errorHolder = null
        recyclerView = null
        searchView = null
        searchMenuItem = null
    }

    public override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initErrorItem = this.initErrorItem
        this.initErrorItem = null
        var initRequest = this.initRequest
        this.initRequest = null
        if (initRequest == null && initErrorItem != null) {
            initRequest = InitRequest(initErrorItem)
        }
        listPage!!.init(
            this.page, this, this, recyclerView, listPosition, this.callback.uiManager,
            this.callback.getRetainableExtra(this.retainId), parcelableExtra, initRequest,
            InitSearch(searchCurrentQuery, searchSubmitQuery)
        )
        notifyTitleChanged()
    }

    public override fun onResume() {
        super.onResume()

        listPage!!.resume()
        val doOnResume = this.doOnResume
        this.doOnResume = null
        if (doOnResume != null) {
            doOnResume.run()
        }
    }

    override fun onPause() {
        super.onPause()

        if (listPage != null) {
            listPage!!.pause()
        }
    }

    fun setSaveToStack(saveToStack: Boolean) {
        this.saveToStack = saveToStack
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (listPage != null) {
            listPosition = listPage!!.getListPosition()
            val extraPair = listPage!!.getExtraToStore(saveToStack)
            this.callback.storeRetainableExtra(this.retainId, extraPair.first)
            parcelableExtra = extraPair.second
            val searchView = getSearchView(false)
            if (searchView != null) {
                searchFocused = searchView.isSearchFocused()
            }
        }
        outState.putParcelable(EXTRA_LIST_POSITION, listPosition)
        outState.putParcelable(EXTRA_PARCELABLE_EXTRA, parcelableExtra)
        if (!saveToStack && initErrorItem != null) {
            outState.putParcelable(EXTRA_INIT_ERROR_ITEM, initErrorItem)
        }
        outState.putString(EXTRA_SEARCH_CURRENT_QUERY, searchCurrentQuery)
        outState.putString(EXTRA_SEARCH_SUBMIT_QUERY, searchSubmitQuery)
        outState.putBoolean(EXTRA_SEARCH_FOCUSED, searchFocused && !saveToStack)
    }

    public override fun onTerminate() {
        super.onTerminate()

        if (listPage != null) {
            listPage!!.destroy()
            listPage = null
        }
    }

    override fun onChansChanged(
        changed: MutableCollection<String?>,
        removed: MutableCollection<String?>?
    ) {
        val page = this.page
        if (changed.contains(page!!.chanName)) {
            invalidateOptionsMenu()
        }
    }

    private fun getSearchView(required: Boolean): CustomSearchView? {
        if (searchView == null && required) {
            searchView = obtainSearchView()
            searchView!!.setOnSubmitListener(OnSubmitListener { query: String? ->
                if (listPage!!.onSearchSubmit(query)) {
                    searchSubmitQuery = null
                    setSearchMode(false)
                    return@setOnSubmitListener true
                } else {
                    searchSubmitQuery = query
                    return@setOnSubmitListener false
                }
            })
            searchView!!.setOnChangeListener(CustomSearchView.OnChangeListener { query: String? ->
                if (listPage != null) {
                    listPage!!.onSearchQueryChange(query)
                }
                if (searchCurrentQuery != null) {
                    searchCurrentQuery = query
                }
            })
        }
        return searchView
    }

    private fun setSearchMode(search: Boolean, toggle: Boolean): Boolean {
        val menuItem = searchMenuItem
        if (menuItem != null && this.isBackHandled != search) {
            this.isBackHandled = search
            if (search) {
                val searchView = getSearchView(true)
                searchView!!.setHint(menuItem.getTitle())
                listPage!!.onSearchQueryChange(searchView.getQuery())
            } else {
                listPage!!.onSearchQueryChange("")
                listPage!!.onSearchCancel()
            }
            invalidateOptionsMenu()
            (requireActivity() as FragmentHandler).setActionBarLocked(
                actionBarLockerSearch!!,
                search
            )
            this.callback.invalidateHomeUpState()
            notifyBackHandledChanged()
            if (toggle) {
                if (search) {
                    menuItem.expandActionView()
                } else {
                    menuItem.collapseActionView()
                }
            }
            return true
        }
        return false
    }

    private fun setSearchMode(search: Boolean): Boolean {
        return setSearchMode(search, true)
    }

    fun setInitRequest(initRequest: InitRequest?) {
        this.initRequest = initRequest
    }

    fun requestResetScroll() {
        resetScroll = true
    }

    fun onAppearanceOptionChanged(what: Int) {
        listPage!!.onAppearanceOptionChanged(what)
    }

    fun onDrawerNumberEntered(number: Int): Int {
        return listPage!!.onDrawerNumberEntered(number)
    }

    fun updatePageConfiguration(postNumber: PostNumber?) {
        if (listPage != null) {
            listPage!!.updatePageConfiguration(postNumber)
        } else {
            val last = this.initRequest
            initRequest = InitRequest(
                last != null && last.shouldLoad,
                postNumber, if (last != null) last.threadTitle else null
            )
        }
    }

    fun handleNewPostDataListNow() {
        listPage!!.handleNewPostDataListNow()
    }

    fun scrollToPost(postNumber: PostNumber?) {
        listPage!!.handleScrollToPost(postNumber)
    }

    val isValidOptionsMenuState: Boolean
        get() = listPage != null && listPage!!.isRunning

    public override fun onCreateOptionsMenu(menu: Menu, primary: Boolean) {
        listPage!!.onCreateOptionsMenu(menu)
        if (primary) {
            val searchMenuItem = menu.findItem(R.id.menu_search)
            this.searchMenuItem = searchMenuItem
            if (searchMenuItem != null) {
                this.searchMenuItem = searchMenuItem
                searchMenuItem.setActionView(getSearchView(true))
                searchMenuItem.setOnActionExpandListener(MenuExpandListener(MenuExpandListener.Callback { menuItem: MenuItem?, expand: Boolean ->
                    if (expand) {
                        searchView!!.setFocusOnExpand(searchFocused)
                        if (searchCurrentQuery != null) {
                            searchView!!.setQuery(searchCurrentQuery)
                        } else {
                            searchCurrentQuery = ""
                        }
                    } else {
                        searchCurrentQuery = null
                        searchSubmitQuery = null
                    }
                    setSearchMode(expand, false)
                    true
                }))
                if (searchCurrentQuery != null) {
                    searchMenuItem.expandActionView()
                }
            }
        }
    }

    public override fun onPrepareOptionsMenu(menu: Menu, primary: Boolean) {
        for (i in 0..<menu.size()) {
            val menuItem = menu.getItem(i)
            if (!menuItem.isVisible() && !this.isBackHandled) {
                menuItem.setVisible(true)
            }
        }
        listPage!!.onPrepareOptionsMenu(menu)
        for (i in 0..<menu.size()) {
            val menuItem = menu.getItem(i)
            if (menuItem.isVisible() && this.isBackHandled && menuItem.getItemId() != R.id.menu_search) {
                menuItem.setVisible(false)
            }
        }
    }

    public override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.getItemId() == R.id.menu_search) {
            if (item === searchMenuItem) {
                searchFocused = true
                return false
            } else if (searchMenuItem != null) {
                searchFocused = true
                searchMenuItem!!.expandActionView()
                return true
            } else {
                return true
            }
        }
        if (listPage!!.onOptionsItemSelected(item)) {
            return true
        }
        return super.onMenuItemSelected(item)
    }

    public override fun onSearchRequested(): Boolean {
        return setSearchMode(true) || this.isBackHandled
    }

    public override fun onBackPressed(): Boolean {
        return setSearchMode(false)
    }

    override fun notifyTitleChanged() {
        val titleSubtitle = listPage!!.obtainTitleSubtitle()
        this.callback.setPageTitle(
            if (titleSubtitle != null) titleSubtitle.first else null,
            if (titleSubtitle != null) titleSubtitle.second else null
        )
    }

    override fun setCustomSearchView(view: View?) {
        getSearchView(true)!!.setCustomView(view)
    }

    override fun clearSearchFocus() {
        val searchView = getSearchView(false)
        if (searchView != null) {
            searchView.clearFocus()
        }
    }

    override fun getToolbarContext(): Context {
        return (requireActivity() as FragmentHandler).getToolbarContext()
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return requireActivity().startActionMode(callback)
    }

    override fun switchList() {
        initErrorItem = null
        progressView!!.setVisibility(View.GONE)
        errorHolder!!.layout.setVisibility(View.GONE)
    }

    override fun switchProgress() {
        initErrorItem = null
        progressView!!.setVisibility(View.VISIBLE)
        errorHolder!!.layout.setVisibility(View.GONE)
    }

    override fun switchError(errorItem: ErrorItem?) {
        var errorItem = errorItem
        if (errorItem == null) {
            errorItem = ErrorItem(ErrorItem.Type.UNKNOWN)
        }
        initErrorItem = errorItem
        progressView!!.setVisibility(View.GONE)
        errorHolder!!.layout.setVisibility(View.VISIBLE)
        errorHolder!!.text.setText(errorItem.toString())
    }

    override fun showScaleAnimation() {
        if (allowShowScale) {
            allowShowScale = false
            createAnimator(recyclerView, true).start()
        }
    }

    override fun handleRedirect(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?
    ) {
        if (isStateSaved()) {
            doOnResume = Runnable? { handleRedirect(chanName, boardName, threadNumber, postNumber) }
        } else {
            this.callback.handleRedirect(chanName, boardName, threadNumber, postNumber)
        }
    }

    override fun closePage() {
        if (isStateSaved()) {
            doOnResume = Runnable? { this.callback.closeCurrentPage() }
        } else {
            this.callback.closeCurrentPage()
        }
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_RETAIN_ID = "retainId"

        private const val EXTRA_LIST_POSITION = "listPosition"
        private const val EXTRA_PARCELABLE_EXTRA = "parcelableExtra"
        private const val EXTRA_INIT_ERROR_ITEM = "initErrorItem"
        private const val EXTRA_SEARCH_CURRENT_QUERY = "searchCurrentQuery"
        private const val EXTRA_SEARCH_SUBMIT_QUERY = "searchSubmitQuery"
        private const val EXTRA_SEARCH_FOCUSED = "searchFocused"
    }
}
