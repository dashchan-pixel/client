package com.mishiranu.dashchan.ui.navigator

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.BundleCompat
import androidx.core.view.children
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CommandRunner
import com.mishiranu.dashchan.content.Preferences.isActiveScrollbar
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.net.VisibleIpCommand
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.ui.ContentFragment
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.page.ListPage
import com.mishiranu.dashchan.ui.navigator.page.ListPage.InitRequest
import com.mishiranu.dashchan.ui.navigator.page.ListPage.InitSearch
import com.mishiranu.dashchan.ui.navigator.page.ListPage.Retainable
import com.mishiranu.dashchan.ui.preference.CommandsFragment
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getActionBarIcon
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.CommandsPopup
import com.mishiranu.dashchan.widget.CustomSearchView
import com.mishiranu.dashchan.widget.CustomSearchView.OnSubmitListener
import com.mishiranu.dashchan.widget.DropdownPopup
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.FloatingToolbar
import com.mishiranu.dashchan.widget.ListPosition
import com.mishiranu.dashchan.widget.MenuExpandListener
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.ProgressDialog
import com.mishiranu.dashchan.widget.PullableWrapper
import com.mishiranu.dashchan.widget.PullableWrapper.PullStateListener
import com.mishiranu.dashchan.widget.ViewFactory.ErrorHolder
import com.mishiranu.dashchan.widget.ViewFactory.createErrorLayout
import com.mishiranu.dashchan.widget.ViewFactory.createProgressLayout
import java.util.UUID

class PageFragment :
    ContentFragment,
    FragmentHandler.Callback,
    ListPage.Callback {
    interface Callback {
        val uiManager: UiManager

        fun getRetainableExtra(retainId: String?): Retainable?

        fun storeRetainableExtra(
            retainId: String?,
            extra: Retainable?,
        )

        fun setPageTitle(
            title: String?,
            subtitle: String?,
        )

        fun invalidateHomeUpState()

        fun handleRedirect(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?,
        )

        fun closeCurrentPage()
    }

    constructor()

    constructor(page: Page, retainId: String?) {
        val args = Bundle()
        args.putParcelable(EXTRA_PAGE, page)
        args.putString(EXTRA_RETAIN_ID, retainId)
        setArguments(args)
    }

    val page: Page
        get() =
            checkNotNull(
                BundleCompat.getParcelable<Page?>(
                    requireArguments(),
                    EXTRA_PAGE,
                    Page::class.java,
                ),
            ) { "PageFragment has no page: arguments were not set by its constructor" }

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
    private var floatingToolbar: FloatingToolbar? = null
    private var primaryMenu: Menu? = null
    private var appCommandRun: CommandRunner.Run? = null
    private var appCommandDialog: ProgressDialog? = null

    private lateinit var actionBarLockerPull: String
    private lateinit var actionBarLockerSearch: String

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
    override var isBackHandled: Boolean = false
        private set
    private var saveToStack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listPosition =
            if (savedInstanceState != null && !resetScroll) {
                BundleCompat.getParcelable<ListPosition?>(
                    savedInstanceState,
                    EXTRA_LIST_POSITION,
                    ListPosition::class.java,
                )
            } else {
                null
            }
        parcelableExtra =
            if (savedInstanceState != null) {
                BundleCompat
                    .getParcelable<Parcelable?>(
                        savedInstanceState,
                        EXTRA_PARCELABLE_EXTRA,
                        Parcelable::class.java,
                    )
            } else {
                null
            }
        initErrorItem =
            if (savedInstanceState != null) {
                BundleCompat
                    .getParcelable<ErrorItem?>(
                        savedInstanceState,
                        EXTRA_INIT_ERROR_ITEM,
                        ErrorItem::class.java,
                    )
            } else {
                null
            }
        searchCurrentQuery =
            if (savedInstanceState != null) {
                savedInstanceState
                    .getString(EXTRA_SEARCH_CURRENT_QUERY)
            } else {
                null
            }
        searchSubmitQuery =
            if (savedInstanceState != null) {
                savedInstanceState
                    .getString(EXTRA_SEARCH_SUBMIT_QUERY)
            } else {
                null
            }
        searchFocused = savedInstanceState != null &&
            savedInstanceState.getBoolean(
                EXTRA_SEARCH_FOCUSED,
            )
        resetScroll = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        actionBarLockerPull = "pull-" + UUID.randomUUID()
        actionBarLockerSearch = "search-" + UUID.randomUUID()

        val layout = ExpandedLayout(container!!.getContext(), false)
        val recyclerView = PaddedRecyclerView(layout.getContext())
        this.recyclerView = recyclerView
        layout.addView(
            recyclerView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        layout.setRecyclerView(recyclerView)
        recyclerView.setMotionEventSplittingEnabled(false)
        recyclerView.setVerticalScrollBarEnabled(true)
        recyclerView.setClipToPadding(false)
        recyclerView.setFastScrollerEnabled(isActiveScrollbar)
        progressView = createProgressLayout(layout)
        val errorHolder = createErrorLayout(layout)
        this.errorHolder = errorHolder
        errorHolder.layout.setVisibility(View.GONE)
        layout.addView(errorHolder.layout)

        allowShowScale = true
        val floatingToolbar = FloatingToolbar(layout.context, this.toolbarContext)
        this.floatingToolbar = floatingToolbar
        floatingToolbar.onContentInsetChanged = layout::setExtraBottom
        layout.addView(
            floatingToolbar,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val listPage = this.page.content.newPage()
        this.listPage = listPage
        recyclerView.pullable.setOnPullListener(listPage)
        recyclerView.pullable.setPullStateListener(
            PullStateListener { wrapper: PullableWrapper?, busy: Boolean ->
                (requireActivity() as FragmentHandler)
                    .setActionBarLocked(actionBarLockerPull, busy)
            },
        )
        return layout
    }

    public override fun onDestroyView() {
        super.onDestroyView()

        val fragmentHandler = requireActivity() as FragmentHandler
        fragmentHandler.setActionBarLocked(actionBarLockerPull, false)
        fragmentHandler.setActionBarLocked(actionBarLockerSearch, false)

        appCommandDialog?.dismiss()
        appCommandDialog = null
        appCommandRun?.cancel()
        appCommandRun = null

        listPage?.destroy()
        listPage = null
        progressView = null
        errorHolder = null
        recyclerView = null
        searchView = null
        searchMenuItem = null
        floatingToolbar = null
        primaryMenu = null
    }

    public override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val initErrorItem = this.initErrorItem
        this.initErrorItem = null
        var initRequest = this.initRequest
        this.initRequest = null
        if (initRequest == null && initErrorItem != null) {
            initRequest = InitRequest(initErrorItem)
        }
        listPage!!.init(
            this.page,
            this,
            this,
            recyclerView!!,
            listPosition,
            this.callback.uiManager,
            this.callback.getRetainableExtra(this.retainId),
            parcelableExtra,
            initRequest,
            InitSearch(searchCurrentQuery, searchSubmitQuery),
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

        listPage?.pause()
    }

    fun setSaveToStack(saveToStack: Boolean) {
        this.saveToStack = saveToStack
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val listPage = this.listPage
        if (listPage != null) {
            listPosition = listPage.getListPosition()
            val extraPair = listPage.getExtraToStore(saveToStack)
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

        listPage?.destroy()
        listPage = null
    }

    override fun onChansChanged(
        changed: Collection<String>,
        removed: Collection<String>,
    ) {
        if (changed.contains(this.page.chanName)) {
            invalidateOptionsMenu()
        }
    }

    private fun getSearchView(required: Boolean): CustomSearchView? {
        if (searchView == null && required) {
            val searchView = obtainSearchView()!!
            this.searchView = searchView
            searchView.setOnSubmitListener(
                OnSubmitListener { query ->
                    if (listPage!!.onSearchSubmit(query)) {
                        searchSubmitQuery = null
                        setSearchMode(false)
                        return@OnSubmitListener true
                    } else {
                        searchSubmitQuery = query
                        return@OnSubmitListener false
                    }
                },
            )
            searchView.setOnChangeListener(
                CustomSearchView.OnChangeListener { query: String? ->
                    listPage?.onSearchQueryChange(query)
                    if (searchCurrentQuery != null) {
                        searchCurrentQuery = query
                    }
                },
            )
        }
        return searchView
    }

    private fun setSearchMode(
        search: Boolean,
        toggle: Boolean,
    ): Boolean {
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
                actionBarLockerSearch,
                search,
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

    private fun setSearchMode(search: Boolean): Boolean = setSearchMode(search, true)

    fun setInitRequest(initRequest: InitRequest?) {
        this.initRequest = initRequest
    }

    fun requestResetScroll() {
        resetScroll = true
    }

    fun onAppearanceOptionChanged(what: Int) {
        listPage!!.onAppearanceOptionChanged(what)
    }

    fun onDrawerNumberEntered(number: Int): Int = listPage!!.onDrawerNumberEntered(number)

    fun updatePageConfiguration(postNumber: PostNumber?) {
        val listPage = this.listPage
        if (listPage != null) {
            listPage.updatePageConfiguration(postNumber)
        } else {
            val last = this.initRequest
            initRequest =
                InitRequest(
                    last != null && last.shouldLoad,
                    postNumber,
                    if (last != null) last.threadTitle else null,
                )
        }
    }

    fun handleNewPostDataListNow() {
        listPage!!.handleNewPostDataListNow()
    }

    fun scrollToPost(postNumber: PostNumber?) {
        listPage!!.handleScrollToPost(postNumber)
    }

    /**
     * Takes the page's post cards off screen while a posting sheet floats over it, and puts them back
     * when it goes — see [ListPage.putDialogsAway]. A page whose view is not there yet has no cards to
     * put away, so both are silently nothing.
     */
    fun putPostDialogsAway() {
        listPage?.putDialogsAway()
    }

    fun bringPostDialogsBack() {
        listPage?.bringDialogsBack()
    }

    override val isValidOptionsMenuState: Boolean
        get() = listPage?.isRunning == true

    public override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        listPage!!.onCreateOptionsMenu(menu)
        if (primary) {
            val searchMenuItem = menu.findItem(R.id.menu_search)
            this.searchMenuItem = searchMenuItem
            if (searchMenuItem != null) {
                this.searchMenuItem = searchMenuItem
                searchMenuItem.setActionView(getSearchView(true))
                searchMenuItem.setOnActionExpandListener(
                    MenuExpandListener(
                        MenuExpandListener.Callback { menuItem: MenuItem?, expand: Boolean ->
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
                        },
                    ),
                )
                if (searchCurrentQuery != null) {
                    searchMenuItem.expandActionView()
                }
            }
        }
    }

    public override fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
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
        if (primary) {
            primaryMenu = menu
            updateFloatingToolbar(menu)
        }
    }

    /**
     * Fills the floating toolbar from the menu the page has just prepared, taking every item the page
     * gave to it ([ListPage.floatingActions]) out of the toolbar as it goes. Reading the prepared menu
     * rather than asking the page again is what keeps one visibility rule for both places: an item the
     * page hid is not in the bar either, and a page that offers nothing leaves the bar off screen.
     *
     * Search takes the toolbar over entirely, and the bar goes with it — its actions are about the page
     * the search is covering, and every one of them is hidden from the menu while it is up.
     */
    private fun updateFloatingToolbar(menu: Menu) {
        val floatingToolbar = this.floatingToolbar ?: return
        val listPage = this.listPage
        val actions = ArrayList<FloatingToolbar.Action>()
        if (listPage != null && !this.isBackHandled) {
            for (floatingAction in listPage.floatingActions) {
                val menuItem = menu.findItem(floatingAction.menuItemId)
                if (menuItem == null || !menuItem.isVisible()) {
                    continue
                }
                menuItem.setVisible(false)
                val itemId = floatingAction.menuItemId
                actions.add(
                    FloatingToolbar.Action(
                        floatingAction.slot,
                        getActionBarIcon(this.toolbarContext, floatingAction.iconAttr),
                        menuItem.getTitle(),
                    ) { anchor -> onFloatingActionClick(itemId, anchor) },
                )
            }
            val commands = appCommands()
            if (actions.isNotEmpty() && commands.isNotEmpty()) {
                actions.add(
                    FloatingToolbar.Action(
                        FloatingToolbar.Slot.COMMANDS,
                        commandsIcon(),
                        getString(R.string.commands),
                    ) { anchor ->
                        CommandsPopup.show(
                            anchor,
                            commands,
                            // The bar and its buttons are the toolbar's context, whose text colour is
                            // the one that reads on the toolbar; the popup's card is app-themed, so its
                            // rows are read from the app's context or they come out white on white.
                            menuContext = requireContext(),
                            alignEnd = true,
                            onRun = ::runAppCommand,
                            onEdit = ::editCommand,
                        )
                    },
                )
            }
        }
        floatingToolbar.setActions(actions)
    }

    /**
     * Runs a floating button's menu item, or drops its dropdown when the item is one — *Contents* is a
     * submenu in the toolbar and stays one here, listing what it holds anchored to the button instead of
     * under the toolbar. The item is looked up again rather than held onto, because a menu can be built
     * afresh between the bar being filled and a button being tapped.
     */
    private fun onFloatingActionClick(
        menuItemId: Int,
        anchor: View,
    ) {
        val menuItem = primaryMenu?.findItem(menuItemId) ?: return
        val subMenu = menuItem.getSubMenu()
        if (subMenu == null) {
            onMenuItemSelected(menuItem)
            return
        }
        val subItems = subMenu.children.filter { it.isVisible() }.toList()
        if (subItems.isEmpty()) {
            return
        }
        DropdownPopup.show(
            anchor,
            // App-themed rather than the bar's toolbar context: the rows sit on the popup's card, not
            // on the toolbar, and the toolbar's text colour is white against it in a light theme.
            requireContext(),
            subItems.map { it.getTitle() ?: "" },
            -1,
            showRadio = false,
            alignEnd = true,
        ) { index -> onMenuItemSelected(subItems[index]) }
    }

    /** The [CommandsStorage.UseIn.APP] commands the forum and board this page is about are in scope of. */
    private fun appCommands(): List<CommandsStorage.CommandItem> =
        CommandsStorage
            .getInstance()
            .getAvailable(CommandsStorage.UseIn.APP, this.page.chanName, this.page.boardName)

    private fun commandsIcon(): Drawable? {
        val context = this.toolbarContext
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_command) ?: return null
        drawable.mutate()
        drawable.setTint(ResourceUtils.getColor(context, android.R.attr.textColorPrimary))
        return drawable
    }

    private fun editCommand(command: CommandsStorage.CommandItem) {
        (requireActivity() as FragmentHandler).pushFragment(CommandsFragment(command.id))
    }

    /**
     * Runs an App command from the page the bar is on, which is a forum and a board — so unlike the run
     * the Commands screen offers, this one hands the script the forum it is looking at. Whatever the
     * command returns is shown as a message, that being all an App command hands back.
     *
     * An [autoRun][CommandsStorage.CommandItem.autoRun] one is the forum's visible-IP command, and a run
     * by hand of it is a force run of exactly what the app runs when it needs another IP — so it goes
     * through [VisibleIpCommand], which drops the pooled connections afterwards. Running it bare would
     * change the IP and leave the app talking over connections still open at the old one.
     */
    private fun runAppCommand(command: CommandsStorage.CommandItem) {
        if (appCommandRun?.isFinished == false) {
            return
        }
        val dialog = ProgressDialog(requireContext(), null)
        appCommandDialog = dialog
        dialog.setMessage(getString(R.string.loading__ellipsis))
        dialog.setOnCancelListener {
            appCommandDialog = null
            appCommandRun?.cancel()
            appCommandRun = null
        }
        dialog.show()
        val deliver: (CommandRunner.AppResult) -> Unit = { result ->
            appCommandRun = null
            appCommandDialog?.dismiss()
            appCommandDialog = null
            when (result) {
                is CommandRunner.AppResult.Success -> {
                    ClickableToast.show(result.message ?: getString(R.string.completed))
                }

                is CommandRunner.AppResult.Failure -> {
                    ClickableToast.show(getString(R.string.command_failed__format, result.message))
                }
            }
        }
        appCommandRun =
            if (command.autoRun) {
                VisibleIpCommand.run(
                    command,
                    this.page.chanName,
                    this.page.threadNumber,
                    this.page.boardName,
                    deliver,
                )
            } else {
                CommandRunner.runApp(
                    command,
                    this.page.chanName,
                    this.page.threadNumber,
                    this.page.boardName,
                    deliver,
                )
            }
    }

    public override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.getItemId() == R.id.menu_search) {
            val searchMenuItem = this.searchMenuItem
            if (item === searchMenuItem) {
                searchFocused = true
                return false
            } else if (searchMenuItem != null) {
                searchFocused = true
                searchMenuItem.expandActionView()
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

    public override fun onSearchRequested(): Boolean = setSearchMode(true) || this.isBackHandled

    public override fun onBackPressed(): Boolean = setSearchMode(false)

    override fun notifyTitleChanged() {
        val titleSubtitle = listPage!!.obtainTitleSubtitle()
        this.callback.setPageTitle(
            if (titleSubtitle != null) titleSubtitle.first else null,
            if (titleSubtitle != null) titleSubtitle.second else null,
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

    override val toolbarContext: Context
        get() = (requireActivity() as FragmentHandler).getToolbarContext()

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = requireActivity().startActionMode(callback)

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
        val resolvedErrorItem = errorItem ?: ErrorItem(ErrorItem.Type.UNKNOWN)
        initErrorItem = resolvedErrorItem
        progressView!!.setVisibility(View.GONE)
        errorHolder!!.layout.setVisibility(View.VISIBLE)
        errorHolder!!.text.setText(resolvedErrorItem.toString())
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
        postNumber: PostNumber?,
    ) {
        if (isStateSaved()) {
            doOnResume = Runnable { handleRedirect(chanName, boardName, threadNumber, postNumber) }
        } else {
            this.callback.handleRedirect(chanName, boardName, threadNumber, postNumber)
        }
    }

    override fun closePage() {
        if (isStateSaved()) {
            doOnResume = Runnable { this.callback.closeCurrentPage() }
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
