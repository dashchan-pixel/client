package com.mishiranu.dashchan.ui.navigator.page

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.util.Pair
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.navigator.Page
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.util.ResourceUtils.getActionBarIcon
import com.mishiranu.dashchan.widget.ListPosition
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.PullableWrapper
import com.mishiranu.dashchan.widget.PullableWrapper.PullCallback
import java.lang.ref.WeakReference

abstract class ListPage :
    LifecycleOwner,
    PullCallback {
    fun interface ExtraFactory<T> {
        fun newExtra(): T
    }

    interface Retainable {
        fun clear() {}
    }

    class InitRequest {
        @JvmField
        val shouldLoad: Boolean

        @JvmField
        val postNumber: PostNumber?

        @JvmField
        val threadTitle: String?

        @JvmField
        val errorItem: ErrorItem?

        constructor(shouldLoad: Boolean, postNumber: PostNumber?, threadTitle: String?) {
            this.shouldLoad = shouldLoad
            this.postNumber = postNumber
            this.threadTitle = threadTitle
            errorItem = null
        }

        constructor(errorItem: ErrorItem?) {
            shouldLoad = false
            postNumber = null
            threadTitle = null
            this.errorItem = errorItem
        }

        companion object {
            internal val EMPTY_REQUEST = InitRequest(false, null, null)
        }
    }

    class InitSearch(
        val currentQuery: String?,
        @JvmField val submitQuery: String?,
    ) {
        companion object {
            internal val EMPTY_SEARCH = InitSearch(null, null)
        }
    }

    private lateinit var page: Page
    private lateinit var callback: Callback
    private lateinit var fragment: Fragment
    private var lifecycleRegistry: LifecycleRegistry? = null
    private lateinit var recyclerView: PaddedRecyclerView
    private var listPosition: ListPosition? = null
    protected lateinit var uiManager: UiManager
        private set
    private var retainableExtra: Retainable? = null
    private var parcelableExtra: Parcelable? = null
    private var initRequest: InitRequest? = null
    private var initSearch: InitSearch? = null

    fun init(
        page: Page,
        callback: Callback,
        fragment: Fragment,
        recyclerView: PaddedRecyclerView,
        listPosition: ListPosition?,
        uiManager: UiManager,
        retainableExtra: Retainable?,
        parcelableExtra: Parcelable?,
        initRequest: InitRequest?,
        initSearch: InitSearch?,
    ) {
        if (lifecycleRegistry == null) {
            lifecycleRegistry = LifecycleRegistry(this)
            this.callback = callback
            this.fragment = fragment
            this.page = page
            this.recyclerView = recyclerView
            this.listPosition = listPosition
            this.uiManager = uiManager
            this.retainableExtra = retainableExtra
            this.parcelableExtra = parcelableExtra
            this.initRequest = initRequest
            this.initSearch = initSearch
            getViewModel(fragment).update(this)
            lifecycleRegistry!!.currentState = Lifecycle.State.INITIALIZED
            onCreate()
            lifecycleRegistry!!.currentState = Lifecycle.State.STARTED
            this.initRequest = null
            this.initSearch = null
        }
    }

    private val state: Lifecycle.State?
        get() = lifecycleRegistry?.currentState

    protected val context: Context
        get() = recyclerView.getContext()

    protected val toolbarContext: Context?
        get() = callback.toolbarContext

    protected val resources: Resources
        get() = this.context.resources

    protected fun getString(resId: Int): String = this.context.getString(resId)

    protected fun getString(
        resId: Int,
        vararg formatArgs: Any?,
    ): String = this.context.getString(resId, *formatArgs)

    internal fun getPage(): Page = page

    protected val fragmentManager: FragmentManager
        get() = fragment.getChildFragmentManager()

    protected fun <T : ViewModel> getViewModel(modelClass: Class<T>): T = ViewModelProvider(fragment).get(modelClass)

    protected val chan: Chan
        get() = get(page.chanName)

    protected fun getRecyclerView(): PaddedRecyclerView = recyclerView

    protected fun takeListPosition(): ListPosition? {
        val listPosition = this.listPosition
        this.listPosition = null
        return listPosition
    }

    protected fun getInitRequest(): InitRequest = initRequest ?: InitRequest.Companion.EMPTY_REQUEST

    protected fun getInitSearch(): InitSearch = initSearch ?: InitSearch.Companion.EMPTY_SEARCH

    protected fun notifyAllAdaptersChanged() {
        recyclerView.getAdapter()!!.notifyDataSetChanged()
        onNotifyAllAdaptersChanged()
    }

    protected fun getActionBarIcon(attr: Int): Drawable = getActionBarIcon(this.toolbarContext!!, attr)

    protected fun notifyTitleChanged() {
        callback.notifyTitleChanged()
    }

    protected fun updateOptionsMenu() {
        if (this.isRunning) {
            callback.invalidateOptionsMenu()
        }
    }

    protected fun setCustomSearchView(view: View?) {
        callback.setCustomSearchView(view)
    }

    protected fun clearSearchFocus() {
        callback.clearSearchFocus()
    }

    protected fun startActionMode(callback: ActionMode.Callback?): ActionMode? = this.callback.startActionMode(callback)

    protected fun switchList() {
        callback.switchList()
    }

    protected fun switchProgress() {
        callback.switchProgress()
    }

    protected fun switchError(errorItem: ErrorItem?) {
        callback.switchError(if (errorItem != null) errorItem else ErrorItem(ErrorItem.Type.UNKNOWN))
    }

    protected fun switchError(message: String?) {
        callback.switchError(if (message != null) ErrorItem(message) else ErrorItem(ErrorItem.Type.UNKNOWN))
    }

    protected fun switchError(message: Int) {
        callback.switchError(if (message != 0) ErrorItem(message) else ErrorItem(ErrorItem.Type.UNKNOWN))
    }

    protected fun showScaleAnimation() {
        callback.showScaleAnimation()
    }

    internal fun handleRedirect(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
    ) {
        callback.handleRedirect(chanName, boardName, threadNumber, postNumber)
    }

    protected fun closePage() {
        callback.closePage()
    }

    protected fun <T : Retainable> getRetainableExtra(factory: ExtraFactory<T>): T {
        if (retainableExtra == null) {
            retainableExtra = factory.newExtra()
        }
        @Suppress("UNCHECKED_CAST")
        return retainableExtra as T
    }

    protected fun <T : Parcelable> getParcelableExtra(factory: ExtraFactory<T>): T {
        if (parcelableExtra == null) {
            parcelableExtra = factory.newExtra()
        }
        @Suppress("UNCHECKED_CAST")
        return parcelableExtra as T
    }

    protected open fun onCreate() {}

    protected open fun onResume() {}

    protected fun onPause() {}

    protected open fun onDestroy() {}

    protected open fun onNotifyAllAdaptersChanged() {}

    protected open fun onHandleNewPostDataList() {}

    protected open fun onScrollToPost(postNumber: PostNumber) {}

    protected open fun onRequestStoreExtra(saveToStack: Boolean) {}

    open fun obtainTitle(): String? = null

    open fun obtainTitleSubtitle(): Pair<String?, String?>? = Pair<String?, String?>(obtainTitle(), null)

    open fun onCreateOptionsMenu(menu: Menu) {}

    open fun onPrepareOptionsMenu(menu: Menu) {}

    open fun onOptionsItemSelected(item: MenuItem): Boolean = false

    open fun onAppearanceOptionChanged(what: Int) {}

    open fun onSearchQueryChange(query: String?) {}

    open fun onSearchSubmit(query: String): Boolean = false

    open fun onSearchCancel() {}

    override fun onListPulled(
        wrapper: PullableWrapper,
        side: PullableWrapper.Side,
    ) {}

    open fun onDrawerNumberEntered(number: Int): Int = 0

    open fun updatePageConfiguration(postNumber: PostNumber?) {}

    val isRunning: Boolean
        get() {
            val state = this.state
            return state == Lifecycle.State.STARTED || state == Lifecycle.State.RESUMED
        }

    private fun performResume() {
        onResume()
        onHandleNewPostDataList()
    }

    fun resume() {
        if (this.state == Lifecycle.State.STARTED) {
            lifecycleRegistry!!.currentState = Lifecycle.State.RESUMED
            performResume()
        }
    }

    fun pause() {
        if (this.state == Lifecycle.State.RESUMED) {
            lifecycleRegistry!!.currentState = Lifecycle.State.STARTED
            onPause()
        }
    }

    fun destroy() {
        if (this.isRunning) {
            if (this.state == Lifecycle.State.RESUMED) {
                lifecycleRegistry!!.currentState = Lifecycle.State.STARTED
                onPause()
            }
            lifecycleRegistry!!.currentState = Lifecycle.State.DESTROYED
            onDestroy()
        }
    }

    fun handleNewPostDataListNow() {
        if (this.state == Lifecycle.State.RESUMED) {
            onHandleNewPostDataList()
        }
    }

    fun handleScrollToPost(postNumber: PostNumber?) {
        if (this.state == Lifecycle.State.RESUMED && postNumber != null) {
            onScrollToPost(postNumber)
        }
    }

    fun getListPosition(): ListPosition? = listPosition ?: ListPosition.obtain(recyclerView, null)

    fun getExtraToStore(saveToStack: Boolean): Pair<Retainable?, Parcelable?> {
        onRequestStoreExtra(saveToStack)
        return Pair<Retainable?, Parcelable?>(retainableExtra, parcelableExtra)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry!!

    class PageViewModel : ViewModel() {
        internal var listPage: WeakReference<ListPage?>? = null

        fun update(listPage: ListPage?) {
            this.listPage = if (listPage != null) WeakReference<ListPage?>(listPage) else null
        }
    }

    interface Callback {
        fun notifyTitleChanged()

        fun invalidateOptionsMenu()

        fun setCustomSearchView(view: View?)

        fun clearSearchFocus()

        val toolbarContext: Context?

        fun startActionMode(callback: ActionMode.Callback?): ActionMode?

        fun switchList()

        fun switchProgress()

        fun switchError(errorItem: ErrorItem?)

        fun showScaleAnimation()

        fun handleRedirect(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?,
        )

        fun closePage()
    }

    companion object {
        private fun getViewModel(fragment: Fragment): PageViewModel = ViewModelProvider(fragment).get<PageViewModel>(PageViewModel::class.java)

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        protected fun <T : ListPage?> extract(provider: InstanceDialog.Provider): T? {
            val viewModel: PageViewModel = Companion.getViewModel(provider.parentFragment!!)
            val listPage = viewModel.listPage?.get()
            return listPage as T?
        }
    }
}
