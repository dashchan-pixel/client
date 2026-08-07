package com.mishiranu.dashchan.ui.navigator.page

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.Pair
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanConfiguration
import chan.content.ChanManager
import chan.content.RedirectException
import chan.text.JsonSerial
import chan.text.JsonSerial.reader
import chan.text.JsonSerial.writer
import chan.text.ParseException
import chan.util.CommonUtils
import chan.util.CommonUtils.equals
import chan.util.StringUtils
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.formatThreadTitle
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CommandRunner
import com.mishiranu.dashchan.content.HidePerformer
import com.mishiranu.dashchan.content.HidePerformer.AddResult
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.cyclicalRefreshMode
import com.mishiranu.dashchan.content.Preferences.isActiveScrollbar
import com.mishiranu.dashchan.content.Preferences.isAdvancedSearch
import com.mishiranu.dashchan.content.Preferences.isDisplayHiddenPostsEnabled
import com.mishiranu.dashchan.content.Preferences.isHighlightUserPosts
import com.mishiranu.dashchan.content.Preferences.isShowImportantPostsOnFastScrollBar
import com.mishiranu.dashchan.content.WatcherNotifications
import com.mishiranu.dashchan.content.async.CallbackProxy
import com.mishiranu.dashchan.content.async.ExtractPostsTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.AttachmentItem.GeneralType
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostItem.HideState
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseNullable
import com.mishiranu.dashchan.content.service.PostingService.Companion.consumeNewPostData
import com.mishiranu.dashchan.content.service.WatcherService
import com.mishiranu.dashchan.content.service.WatcherService.Session.Callback.ConsumeReplies
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem
import com.mishiranu.dashchan.content.storage.StatisticsStorage
import com.mishiranu.dashchan.content.storage.StatisticsStorage.Companion.getInstance
import com.mishiranu.dashchan.ui.DrawerForm
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.gallery.FlowDialog.Companion.show
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay
import com.mishiranu.dashchan.ui.navigator.adapter.PostsAdapter
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit
import com.mishiranu.dashchan.ui.navigator.manager.ThreadshotPerformer
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.posting.Replyable
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.DelayedProgress
import com.mishiranu.dashchan.util.ListViewUtils.smoothScrollToPosition
import com.mishiranu.dashchan.util.ResourceUtils.getColonString
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.isTabletOrLandscape
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.SearchHelper
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.setNewMarginRelative
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ClickableToast.Companion.isShowing
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.DividerItemDecoration.SkipCallback
import com.mishiranu.dashchan.widget.FloatingToolbar
import com.mishiranu.dashchan.widget.ImportantPostsMarksFastScrollBarDecoration
import com.mishiranu.dashchan.widget.ListPosition
import com.mishiranu.dashchan.widget.ListPosition.Companion.obtain
import com.mishiranu.dashchan.widget.ListPosition.PositionTest
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.PostsLayoutManager
import com.mishiranu.dashchan.widget.PullableWrapper
import com.mishiranu.dashchan.widget.SummaryLayout
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

class PostsPage :
    ListPage(),
    PostsAdapter.Callback,
    FavoritesStorage.Observer,
    UiManager.Observer,
    ExtractPostsTask.Callback,
    WatcherService.Session.Callback {
    private class RetainableExtra : Retainable {
        var cache: PagesDatabase.Cache? = null
        var cacheState: PagesDatabase.Cache.State? = null
        var initialExtract: Boolean = true
        var eraseExtract: Boolean = false
        val postItems: HashMap<PostNumber?, PostItem> = HashMap()
        val hiddenPosts: HideState.Map<PostNumber?> = HideState.Map<PostNumber?>()
        val userPosts: HashSet<PostNumber> = HashSet<PostNumber>()
        var importantPostsMarksFastScrollBarDecorationData: ImportantPostsMarksFastScrollBarDecoration.Data? =
            null

        var threadExtra: ByteArray? = null
        var errorItem: ErrorItem? = null

        var archivedThreadUri: Uri? = null
        var uniquePosters: Int = 0

        var searchPostNumbers: MutableList<PostNumber> = mutableListOf()
        var searching: Boolean = false
        var searchLastIndex: Int = 0

        var dialogsState: DialogUnit.StackInstance.State? = null

        fun shouldExtract(): Boolean {
            val cache = this.cache ?: return true
            return cache.state != cacheState
        }

        override fun clear() {
            dialogsState?.dropState()
            dialogsState = null
        }

        companion object {
            val FACTORY: ExtraFactory<RetainableExtra> = ExtraFactory { RetainableExtra() }
        }
    }

    private class ParcelableExtra : Parcelable {
        val expandedPosts: HashSet<PostNumber> = HashSet<PostNumber>()
        val unreadPosts: HashSet<PostNumber> = HashSet<PostNumber>()
        var isAddedToHistory: Boolean = false
        var threadTitle: String? = null
        var scrollToPostNumber: PostNumber? = null
        var selectedPosts: MutableSet<PostNumber>? = null
        var popularPosts: Boolean = false

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeInt(expandedPosts.size)
            for (number in expandedPosts) {
                number.writeToParcel(dest, flags)
            }
            dest.writeInt(unreadPosts.size)
            for (number in unreadPosts) {
                number.writeToParcel(dest, flags)
            }
            dest.writeByte((if (isAddedToHistory) 1 else 0).toByte())
            dest.writeString(threadTitle)
            val scrollToPostNumber = this.scrollToPostNumber
            dest.writeByte((if (scrollToPostNumber != null) 1 else 0).toByte())
            scrollToPostNumber?.writeToParcel(dest, flags)
            val selectedPosts = this.selectedPosts
            dest.writeInt(selectedPosts?.size ?: -1)
            if (selectedPosts != null) {
                for (number in selectedPosts) {
                    number.writeToParcel(dest, flags)
                }
            }
            dest.writeByte((if (popularPosts) 1 else 0).toByte())
        }

        companion object {
            val FACTORY: ExtraFactory<ParcelableExtra> = ExtraFactory { ParcelableExtra() }

            @JvmField
            val CREATOR: Parcelable.Creator<ParcelableExtra?> =
                object : Parcelable.Creator<ParcelableExtra?> {
                    override fun createFromParcel(source: Parcel): ParcelableExtra {
                        val parcelableExtra = ParcelableExtra()
                        val expandedPostsCount = source.readInt()
                        for (i in 0..<expandedPostsCount) {
                            parcelableExtra.expandedPosts.add(
                                PostNumber.CREATOR.createFromParcel(
                                    source,
                                ),
                            )
                        }
                        val unreadPostsCount = source.readInt()
                        for (i in 0..<unreadPostsCount) {
                            parcelableExtra.unreadPosts.add(
                                PostNumber.CREATOR.createFromParcel(
                                    source,
                                ),
                            )
                        }
                        parcelableExtra.isAddedToHistory = source.readByte().toInt() != 0
                        parcelableExtra.threadTitle = source.readString()
                        if (source.readByte().toInt() != 0) {
                            parcelableExtra.scrollToPostNumber =
                                PostNumber.CREATOR.createFromParcel(source)
                        }
                        val selectedPostsCount = source.readInt()
                        if (selectedPostsCount >= 0) {
                            val selectedPosts = HashSet<PostNumber>(selectedPostsCount)
                            for (i in 0..<selectedPostsCount) {
                                selectedPosts.add(PostNumber.CREATOR.createFromParcel(source))
                            }
                            parcelableExtra.selectedPosts = selectedPosts
                        }
                        parcelableExtra.popularPosts = source.readByte().toInt() != 0
                        return parcelableExtra
                    }

                    override fun newArray(size: Int): Array<ParcelableExtra?> = arrayOfNulls<ParcelableExtra>(size)
                }
        }
    }

    class ExtractViewModel : TaskViewModel.Proxy<ExtractPostsTask, ExtractPostsTask.Callback>()

    class ReadViewModel : ViewModel() {
        private var session: WatcherService.Session? = null
        private val result =
            MutableLiveData<Pair<CallbackProxy<WatcherService.Session.Callback>, Boolean>?>()

        private var visibleRefresh = false
        var visibleReadResult: Boolean = false

        fun init(
            client: WatcherService.Client,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
        ) {
            if (session == null) {
                val callback: WatcherService.Session.Callback?
                callback =
                    CallbackProxy.create(
                        WatcherService.Session.Callback::class.java,
                        CallbackProxy.Handler { result: CallbackProxy<WatcherService.Session.Callback> ->
                            val visible = visibleRefresh
                            visibleRefresh = false
                            this.result.setValue(
                                Pair<CallbackProxy<WatcherService.Session.Callback>, Boolean>(
                                    result,
                                    visible,
                                ),
                            )
                        },
                    )
                session = client.newSession(chanName!!, boardName, threadNumber!!, callback)
            }
        }

        fun refresh(
            reload: Boolean,
            visible: Boolean,
            checkInterval: Int,
        ) {
            val session = this.session
            if (session != null && session.refresh(reload, checkInterval)) {
                visibleRefresh = visible
            }
        }

        fun hasTaskOrValue(): Boolean {
            val session = this.session
            if (session != null && session.hasTask() && visibleRefresh) {
                return true
            }
            val result = this.result.getValue()
            return result != null && result.second
        }

        fun notifyExtracted() {
            session?.notifyExtracted()
        }

        fun notifyEraseStarted() {
            session?.notifyEraseStarted()
        }

        fun observe(
            owner: LifecycleOwner,
            callback: WatcherService.Session.Callback,
        ) {
            result.observe(
                owner,
                Observer { result: Pair<CallbackProxy<WatcherService.Session.Callback>, Boolean>? ->
                    if (result != null) {
                        this.result.setValue(null)
                        visibleReadResult = result.second
                        result.first.invoke(callback)
                    }
                },
            )
        }

        override fun onCleared() {
            session!!.destroy()
            session = null
        }
    }

    private var searchWorker: SearchWorker? = null

    private var replyable: Replyable? = null
    private lateinit var hidePerformer: HidePerformer

    private var selectionMode: ActionMode? = null

    private var searchControlView: View? = null
    private var searchProcessView: View? = null
    private lateinit var searchResultText: Button

    /**
     * Puts the list's busy indicator up while a thread command runs, but only once it has been running
     * long enough to be worth saying so (see [DelayedProgress]) — most commands finish before the user
     * could notice anything at all, and one that fetches or decrypts should not look like a no-op.
     */
    private val commandsProgress =
        DelayedProgress({ startCommandProgress() }, { cancelCommandProgress() })

    /** The command runs started here that may still be going; see [cancelCommandRuns]. */
    private val commandRuns = ArrayList<CommandRunner.Run>()

    private var lastNewPostNumbers = mutableSetOf<PostNumber?>()
    private var lastEditedPostNumbers = mutableSetOf<PostNumber?>()
    private var importantPostsMarksFastScrollBarDecoration: ImportantPostsMarksFastScrollBarDecoration? =
        null

    private val postStateProvider: PostStateProvider =
        object : PostStateProvider {
            override fun isHiddenResolve(postItem: PostItem): Boolean {
                if (postItem.getHideState() == HideState.UNDEFINED) {
                    val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
                    val hideState = retainableExtra.hiddenPosts.get(postItem.getPostNumber())
                    if (hideState != HideState.UNDEFINED) {
                        postItem.setHidden(hideState, null)
                    } else {
                        val hideReason = hidePerformer.checkHidden(chan, postItem)
                        if (hideReason != null) {
                            postItem.setHidden(HideState.HIDDEN, hideReason)
                        } else {
                            postItem.setHidden(HideState.SHOWN, null)
                        }
                    }
                    if (!isDisplayHiddenPostsEnabled && postItem.isHidden()) {
                        adapter.removeHiddenPost(postItem)
                        setPostHideState(postItem, postItem.getHideState())
                        notifyTitleChanged()
                    }
                }
                return postItem.getHideState().hidden
            }

            override fun isUserPost(postNumber: PostNumber?): Boolean {
                val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
                return retainableExtra.userPosts.contains(postNumber)
            }

            override fun isExpanded(postNumber: PostNumber?): Boolean {
                val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
                return parcelableExtra.expandedPosts.contains(postNumber)
            }

            override fun setExpanded(postNumber: PostNumber?) {
                val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
                parcelableExtra.expandedPosts.add(postNumber!!)
            }

            override fun isRead(postNumber: PostNumber?): Boolean {
                val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
                return !parcelableExtra.unreadPosts.contains(postNumber)
            }

            override fun setRead(postNumber: PostNumber?) {
                val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
                parcelableExtra.unreadPosts.remove(postNumber)
            }
        }

    private val adapter: PostsAdapter
        get() = getRecyclerView().getAdapter() as PostsAdapter

    override val dialogsConfigurationSet: UiManager.ConfigurationSet?
        get() = (getRecyclerView().getAdapter() as? PostsAdapter)?.configurationSet

    override fun onCreate() {
        val context: Context = context
        val recyclerView = getRecyclerView()
        recyclerView.setLayoutManager(PostsLayoutManager(recyclerView.getContext()))
        setupSwipeAction(recyclerView)
        val page = getPage()
        uiManager.view().bindThreadsPostRecyclerView(recyclerView)
        val density = obtainDensity(context)
        val dividerPadding = (12f * density).toInt()
        hidePerformer = HidePerformer(context)
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        replyable =
            Replyable { click: Boolean, data: Array<out ReplyData> ->
                val board = chan.configuration.safe().obtainBoard(page.boardName)
                if (click && board.allowPosting) {
                    uiManager.navigator()!!.navigatePosting(
                        page.chanName,
                        page.boardName,
                        page.threadNumber,
                        *data,
                    )
                }
                board.allowPosting
            }
        val adapter =
            PostsAdapter(
                this,
                page.chanName,
                uiManager,
                replyable,
                postStateProvider,
                fragmentManager,
                recyclerView,
                retainableExtra.postItems,
                retainableExtra.hiddenPosts,
            )
        recyclerView.setAdapter(adapter)
        val divider =
            DividerItemDecoration(
                recyclerView.getContext(),
                DividerItemDecoration.Callback { c: DividerItemDecoration.Configuration, position: Int ->
                    adapter
                        .configureDivider(
                            c,
                            position,
                        ).horizontal(dividerPadding, dividerPadding)
                },
            )
        if (isHighlightUserPosts) {
            divider.setSkipCallback(
                SkipCallback { position: Int ->
                    if (position >= 0) {
                        if (adapter.configurationSet.postStateProvider.isUserPost(
                                adapter
                                    .getItem(
                                        position,
                                    ).getPostNumber(),
                            )
                        ) {
                            return@SkipCallback true
                        }
                    }
                    if ((position + 1) < adapter.getItemCount()) {
                        return@SkipCallback adapter.configurationSet.postStateProvider.isUserPost(
                            adapter.getItem(position + 1).getPostNumber(),
                        )
                    }
                    false
                },
            )
        }
        recyclerView.addItemDecoration(divider)
        recyclerView.addItemDecoration(adapter.createPostItemDecoration(context, dividerPadding))
        recyclerView.pullable.setPullSides(PullableWrapper.Side.BOTH)
        recyclerView.addOnScrollListener(scrollListener)
        initializeImportantPostsMarksFastScrollBarDecoration(recyclerView, retainableExtra)

        uiManager.observable().register(this)
        FavoritesStorage.getInstance().getObservable().register(this)
        hidePerformer.setPostsProvider(adapter)

        val toolbarContext: Context = this.toolbarContext
        val searchControlLayout = LinearLayout(toolbarContext)
        this.searchControlView = searchControlLayout
        searchControlLayout.setOrientation(LinearLayout.HORIZONTAL)
        searchControlLayout.setGravity(Gravity.CENTER_VERTICAL)
        val buttonPadding = (10f * density).toInt()
        searchResultText = Button(toolbarContext, null, android.R.attr.borderlessButtonStyle)
        ViewUtils.setTextSizeScaled(searchResultText, 11)
        searchResultText.setPadding((14f * density).toInt(), 0, (14f * density).toInt(), 0)
        searchResultText.setMinimumWidth(0)
        searchResultText.setMinWidth(0)
        searchResultText.setOnClickListener(View.OnClickListener { v: View? -> showSearchDialog() })
        searchControlLayout.addView(
            searchResultText,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val backButtonView = ImageView(toolbarContext, null, android.R.attr.borderlessButtonStyle)
        backButtonView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        backButtonView.setImageDrawable(getActionBarIcon(R.attr.iconActionBack))
        backButtonView.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
        backButtonView.setOnClickListener(View.OnClickListener { v: View? -> findNext(-1) })
        searchControlLayout.addView(
            backButtonView,
            (48f * density).toInt(),
            (48f * density).toInt(),
        )
        val forwardButtonView =
            ImageView(toolbarContext, null, android.R.attr.borderlessButtonStyle)
        forwardButtonView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        forwardButtonView.setImageDrawable(getActionBarIcon(R.attr.iconActionForward))
        forwardButtonView.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
        forwardButtonView.setOnClickListener(View.OnClickListener { v: View? -> findNext(1) })
        searchControlLayout.addView(
            forwardButtonView,
            (48f * density).toInt(),
            (48f * density).toInt(),
        )
        var i = 0
        val last = searchControlLayout.getChildCount() - 1
        while (i <= last) {
            val view = searchControlLayout.getChildAt(i)
            if (i == 0) {
                setNewMarginRelative(view, (-6f * density).toInt(), null, null, null)
            }
            if (i == last) {
                setNewMarginRelative(view, null, null, (6f * density).toInt(), null)
            } else {
                setNewMarginRelative(view, null, null, (-6f * density).toInt(), null)
            }
            i++
        }

        val searchProcessLayout = FrameLayout(toolbarContext)
        val searchProgress = ProgressBar(toolbarContext, null, android.R.attr.progressBarStyleSmall)
        val color = getColor(toolbarContext, android.R.attr.textColorPrimary)
        searchProgress.setIndeterminateTintList(ColorStateList.valueOf(color))

        searchProcessLayout.addView(
            searchProgress,
            (20f * density).toInt(),
            (20f * density).toInt(),
        )
        (searchProgress.getLayoutParams() as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        setNewMarginRelative(searchProgress, (12f * density).toInt(), 0, (16f * density).toInt(), 0)

        searchProcessView = searchProcessLayout

        val initRequest = getInitRequest()
        val extractViewModel = getViewModel(ExtractViewModel::class.java)
        val readViewModel = getViewModel(ReadViewModel::class.java)
        readViewModel.init(
            uiManager.callback()!!.watcherClient,
            page.chanName,
            page.boardName,
            page.threadNumber,
        )
        if (initRequest.threadTitle != null && parcelableExtra.threadTitle == null) {
            parcelableExtra.threadTitle = initRequest.threadTitle
        }
        if (initRequest.postNumber != null) {
            parcelableExtra.scrollToPostNumber = initRequest.postNumber
        }
        val hasNewPosts = consumeNewPostData()
        val load = initRequest.shouldLoad || hasNewPosts
        if (initRequest.errorItem != null && !load) {
            switchError(initRequest.errorItem)
        } else {
            var extract = true
            if (retainableExtra.cache != null && retainableExtra.postItems.size > 0) {
                extract = false
                onExtractPostsCompleteInternal(true, null)
                val searchSubmitQuery = getInitSearch().submitQuery
                if (searchSubmitQuery == null) {
                    retainableExtra.searching = false
                }
                if (retainableExtra.searching && !retainableExtra.searchPostNumbers.isEmpty()) {
                    setCustomSearchView(searchControlView)
                    updateSearchTitle()
                }
                decodeThreadExtra()
                val dialogsState = retainableExtra.dialogsState
                if (dialogsState != null) {
                    uiManager
                        .dialog()
                        .restoreState(adapter.configurationSet, dialogsState)
                    dialogsState.dropState()
                    retainableExtra.dialogsState = null
                }
            } else {
                retainableExtra.cache = null
                check(retainableExtra.postItems.isEmpty())
                retainableExtra.initialExtract = true
                retainableExtra.searching = false
            }
            var progress = false
            if (extractViewModel.hasTaskOrValue()) {
                progress = true
            } else if (extract) {
                extractPostsWithoutIndication(PagesDatabase.Cleanup.NONE)
                progress = true
            }
            if (readViewModel.hasTaskOrValue()) {
                progress = true
            } else if (load) {
                refreshPostsWithoutIndication(false)
                progress = true
            }
            if (progress) {
                if (adapter.getItemCount() == 0) {
                    recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTH)
                    switchProgress()
                } else {
                    recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTTOM)
                }
            }
        }
        extractViewModel.observe(this, this)
        readViewModel.observe(this, this)
        retainableExtra.dialogsState?.dropState()
        retainableExtra.dialogsState = null
        // Last, so that the hide rules the order depends on are already decoded.
        adapter.setPopularMode(parcelableExtra.popularPosts)
        queueNextRefresh(true)
    }

    override fun onResume() {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.dialogsState?.dropState()
        retainableExtra.dialogsState = null
    }

    override fun onDestroy() {
        stopRefresh()
        selectionMode?.finish()
        selectionMode = null
        this.adapter.cancelPreloading()
        uiManager.dialog().closeDialogs(this.adapter.configurationSet.stackInstance!!)
        uiManager.observable().unregister(this)
        searchWorker?.cancel()
        searchWorker = null
        getRecyclerView().removeOnScrollListener(scrollListener)
        if (ConcurrentUtils.HANDLER.hasCallbacks(storePositionRunnable)) {
            ConcurrentUtils.HANDLER.removeCallbacks(storePositionRunnable)
            storePositionRunnable.run()
        }
        FavoritesStorage.getInstance().getObservable().unregister(this)
        setCustomSearchView(null)
        // A command still running outlives the page: its callback finds it gone and never releases the
        // indicator, so drop it here.
        cancelCommandRuns()
        commandsProgress.cancel()
    }

    override fun onNotifyAllAdaptersChanged() {
        uiManager
            .dialog()
            .notifyDataSetChangedToAll(this.adapter.configurationSet.stackInstance!!)
    }

    override fun onHandleNewPostDataList() {
        if (consumeNewPostData()) {
            refreshPosts(false)
        }
    }

    override fun onScrollToPost(postNumber: PostNumber) {
        val position = positionToScrollTo(postNumber)
        if (position >= 0) {
            uiManager.dialog().closeDialogs(this.adapter.configurationSet.stackInstance!!)
            smoothScrollToPosition(getRecyclerView(), position)
        }
    }

    override fun onRequestStoreExtra(saveToStack: Boolean) {
        val adapter = this.adapter
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.dialogsState?.dropState()
        retainableExtra.dialogsState = adapter.configurationSet.stackInstance!!.collectState()
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        parcelableExtra.selectedPosts = null
        if (selectionMode != null && !saveToStack) {
            val selected = adapter.selectedItems
            val selectedPosts = HashSet<PostNumber>(selected.size)
            parcelableExtra.selectedPosts = selectedPosts
            for (postItem in selected) {
                selectedPosts.add(postItem.getPostNumber())
            }
        }
    }

    public override fun obtainTitle(): String? {
        val page = getPage()
        // A favorite's custom name wins over the thread's own subject: that name is what the thread
        // is recognized by in the drawer, so the header must agree with it. Only the display is
        // overridden — threadTitle stays the real subject for history, the gallery and sharing.
        val favoriteTitle =
            FavoritesStorage
                .getInstance()
                .getFavorite(page.chanName, page.boardName, page.threadNumber)
                ?.takeIf { it.modifiedTitle }
                ?.title
        if (!StringUtils.isEmptyOrWhitespace(favoriteTitle)) {
            return favoriteTitle
        }
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        if (!StringUtils.isEmptyOrWhitespace(parcelableExtra.threadTitle)) {
            return parcelableExtra.threadTitle
        } else {
            return StringUtils.formatThreadTitle(
                page.chanName!!,
                page.boardName,
                page.threadNumber!!,
            )
        }
    }

    public override fun obtainTitleSubtitle(): Pair<String?, String?>? {
        var subtitle: String? = null
        if (this.adapter.isPopularMode) {
            // The reordered list is easy to mistake for a broken thread, so say what it is.
            subtitle = getString(R.string.popular)
        } else if (!isDisplayHiddenPostsEnabled) {
            val hidden = this.adapter.hiddenPostsCount
            if (hidden > 0) subtitle = getString(R.string.hidden_posts_count__format, hidden)
        }
        return Pair<String?, String?>(this.obtainTitle(), subtitle)
    }

    override fun onItemClick(
        view: View?,
        postItem: PostItem?,
    ) {
        val selectionMode = this.selectionMode
        if (selectionMode != null) {
            this.adapter.toggleItemSelected(postItem!!)
            selectionMode.setTitle(
                getColonString(
                    resources,
                    R.string.selected,
                    this.adapter.selectedCount,
                ),
            )
            return
        }
        uiManager.interaction().handlePostClick(view!!, postStateProvider, postItem!!, this.adapter)
    }

    override fun onItemLongClick(postItem: PostItem?): Boolean {
        if (selectionMode != null) {
            return false
        }
        uiManager.interaction().handlePostContextMenu(this.adapter.configurationSet, postItem!!)
        return true
    }

    /**
     * Right-to-left swipe on a post runs the context menu entry chosen in the preferences.
     * The preference is read per gesture rather than at setup, so changing it takes effect
     * without reopening the thread.
     */
    private fun setupSwipeAction(recyclerView: RecyclerView) {
        val callback =
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    val position = viewHolder.bindingAdapterPosition
                    return if (Preferences.postSwipeAction == Preferences.PostSwipeAction.DISABLED ||
                        selectionMode != null ||
                        position == RecyclerView.NO_POSITION
                    ) {
                        0
                    } else {
                        super.getSwipeDirs(recyclerView, viewHolder)
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int,
                ) {
                    val adapter = this@PostsPage.adapter
                    val position = viewHolder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        uiManager.interaction().performPostSwipeAction(
                            adapter.configurationSet,
                            adapter.getItem(position),
                            Preferences.postSwipeAction,
                        )
                        // Nothing is being removed, so rebind to undo the swipe-away: without
                        // this the row stays translated off-screen.
                        adapter.notifyItemChanged(position)
                    }
                }

                override fun onChildDraw(
                    c: Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean,
                ) {
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                        val itemView = viewHolder.itemView
                        itemView.alpha = (1 - 1.5 * (abs(dX) / itemView.width)).toFloat()
                    }
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    // Reset opacity or the view will still be transparent when reused.
                    viewHolder.itemView.alpha = 1f
                    super.clearView(recyclerView, viewHolder)
                }
            }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    private fun setPostUserPost(
        postItem: PostItem,
        userPost: Boolean,
    ) {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        if (userPost) {
            retainableExtra.userPosts.add(postItem.getPostNumber())
        } else {
            retainableExtra.userPosts.remove(postItem.getPostNumber())
        }
        CommonDatabase.getInstance().posts.setFlags(
            true,
            getPage().chanName!!,
            postItem.getBoardName(),
            postItem.getThreadNumber()!!,
            postItem.getPostNumber(),
            retainableExtra.hiddenPosts.get(postItem.getPostNumber()),
            userPost,
        )
    }

    private fun setPostHideState(
        postItem: PostItem,
        hideState: HideState,
    ) {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.hiddenPosts.set(postItem.getPostNumber(), hideState)
        CommonDatabase.getInstance().posts.setFlags(
            true,
            getPage().chanName!!,
            postItem.getBoardName(),
            postItem.getThreadNumber()!!,
            postItem.getPostNumber(),
            hideState,
            retainableExtra.userPosts.contains(postItem.getPostNumber()),
        )
        postItem.setHidden(hideState, null)
    }

    public override fun onCreateOptionsMenu(menu: Menu) {
        menu
            .add(0, R.id.menu_add_post, 0, R.string.reply)
            .setIcon(getActionBarIcon(R.attr.iconActionAddPost))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu
            .add(0, R.id.menu_search, 0, R.string.search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        menu.add(0, R.id.menu_gallery, 0, R.string.gallery)
        menu.add(0, R.id.menu_flow, 0, R.string.flow)
        menu.add(0, R.id.menu_popular, 0, R.string.popular).setCheckable(true)
        menu.add(0, R.id.menu_select, 0, R.string.select)
        val contentsMenu = menu.addSubMenu(0, R.id.menu_contents, 0, R.string.contents)
        contentsMenu
            .getItem()
            .setIcon(getActionBarIcon(R.attr.iconActionSync))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        contentsMenu.add(0, R.id.menu_refresh, 0, R.string.refresh)
        contentsMenu.add(0, R.id.menu_reload, 0, R.string.reload)
        contentsMenu.add(0, R.id.menu_erase, 0, R.string.erase)
        contentsMenu.add(0, R.id.menu_clear_old, 0, R.string.clear_old)
        contentsMenu.add(0, R.id.menu_clear_deleted, 0, R.string.clear_deleted)
        menu.add(0, R.id.menu_summary, 0, R.string.summary)
        menu.addSubMenu(0, R.id.menu_commands, 0, R.string.commands)
        menu.add(0, R.id.menu_hidden_posts, 0, R.string.hidden_posts)
        menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance)
        menu.add(0, R.id.menu_star_text, 0, R.string.add_to_favorites)
        menu.add(0, R.id.menu_unstar_text, 0, R.string.remove_from_favorites)
        menu
            .add(0, R.id.menu_star_icon, 0, R.string.add_to_favorites)
            .setIcon(getActionBarIcon(R.attr.iconActionAddToFavorites))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu
            .add(0, R.id.menu_unstar_icon, 0, R.string.remove_from_favorites)
            .setIcon(getActionBarIcon(R.attr.iconActionRemoveFromFavorites))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, R.id.menu_open_original_thread, 0, R.string.open_original)
        menu.add(0, R.id.menu_archive, 0, R.string.archive__verb)
    }

    /**
     * A thread's two floating buttons: the pencil that opens a reply to it, and the *Contents* dropdown
     * that refreshes, reloads and clears what is on screen. Both keep their entries in the menu — the
     * toolbar simply does not show them while the bar does.
     */
    override val floatingActions: List<FloatingAction>
        get() =
            listOf(
                FloatingAction(FloatingToolbar.Slot.COMPOSE, R.id.menu_add_post, R.attr.iconActionAddPost),
                FloatingAction(FloatingToolbar.Slot.CONTENTS, R.id.menu_contents, R.attr.iconActionSync),
            )

    public override fun onPrepareOptionsMenu(menu: Menu) {
        val page = getPage()
        val adapter = this.adapter
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        menu
            .findItem(R.id.menu_add_post)
            .setVisible(replyable?.onRequestReply(false) == true)
        menu.findItem(R.id.menu_erase).setVisible(adapter.getItemCount() > 0)
        menu
            .findItem(R.id.menu_popular)
            .setVisible(adapter.postCount > 0)
            .setChecked(adapter.isPopularMode)
        menu.findItem(R.id.menu_clear_old).setVisible(adapter.hasOldPosts())
        menu.findItem(R.id.menu_clear_deleted).setVisible(adapter.hasDeletedPosts())
        menu.findItem(R.id.menu_hidden_posts).setVisible(hidePerformer.hasLocalFilters())
        prepareThreadCommandsMenu(menu.findItem(R.id.menu_commands))
        val isFavorite =
            FavoritesStorage.getInstance().hasFavorite(
                page.chanName,
                page.boardName,
                page.threadNumber,
            )
        val iconFavorite = isTabletOrLandscape(resources.configuration)
        menu.findItem(R.id.menu_star_text).setVisible(!iconFavorite && !isFavorite)
        menu.findItem(R.id.menu_unstar_text).setVisible(!iconFavorite && isFavorite)
        menu.findItem(R.id.menu_star_icon).setVisible(iconFavorite && !isFavorite)
        menu.findItem(R.id.menu_unstar_icon).setVisible(iconFavorite && isFavorite)
        menu
            .findItem(R.id.menu_open_original_thread)
            .setVisible(getPreferred(null, retainableExtra.archivedThreadUri).name != null)
        val canBeArchived =
            !ChanManager.getInstance().getArchiveChanNames(page.chanName).isEmpty() ||
                !chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)
        menu.findItem(R.id.menu_archive).setVisible(canBeArchived)
    }

    public override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val page = getPage()
        val adapter = this.adapter
        if (item.getGroupId() == COMMANDS_GROUP) {
            availableThreadCommands().getOrNull(item.getItemId())?.let { runThreadCommand(it) }
            return true
        }
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == R.id.menu_add_post) {
            uiManager.navigator()!!.navigatePosting(
                page.chanName,
                page.boardName,
                page.threadNumber,
            )
            return true
        } else if (switchItemId0 == R.id.menu_gallery) {
            var imageIndex = -1
            val recyclerView = getRecyclerView()
            val child = recyclerView.getChildAt(0)
            val gallerySet = adapter.gallerySet
            if (child != null) {
                val position = recyclerView.getChildAdapterPosition(child)
                OUTER@ for (v in 0..1) {
                    for (postItem in adapter.iterate(v == 0, position)) {
                        imageIndex = gallerySet.findIndex(postItem)
                        if (imageIndex >= 0) {
                            break@OUTER
                        }
                    }
                }
            }
            uiManager.navigator()!!.navigateGallery(
                page.chanName,
                gallerySet,
                imageIndex,
                null,
                GalleryOverlay.NavigatePostMode.ENABLED,
                true,
            )
            return true
        } else if (switchItemId0 == R.id.menu_flow) {
            val chan = chan
            val gallerySet = adapter.gallerySet
            show(
                fragmentManager,
                chan,
                gallerySet.createList(),
                null,
                gallerySet.getThreadTitle(),
            )
            return true
        } else if (switchItemId0 == R.id.menu_popular) {
            setPopularPosts(!adapter.isPopularMode)
            return true
        } else if (switchItemId0 == R.id.menu_select) {
            selectionMode = startActionMode(SelectionCallback(this))
            return true
        } else if (switchItemId0 == R.id.menu_refresh) {
            refreshPosts(false)
            return true
        } else if (switchItemId0 == R.id.menu_reload) {
            refreshPosts(true)
            return true
        } else if (switchItemId0 == R.id.menu_erase) {
            showEraseDialog(fragmentManager)
            return true
        } else if (switchItemId0 == R.id.menu_clear_old) {
            extractPosts(PagesDatabase.Cleanup.OLD)
            return true
        } else if (switchItemId0 == R.id.menu_clear_deleted) {
            showClearDeletedDialog(fragmentManager)
            return true
        } else if (switchItemId0 == R.id.menu_summary) {
            showSummaryDialog(fragmentManager)
            return true
        } else if (switchItemId0 == R.id.menu_edit_commands) {
            uiManager.navigator()?.navigateCommands()
            return true
        } else if (switchItemId0 == R.id.menu_hidden_posts) {
            val localFilters = hidePerformer.getReadableLocalFilters(context)
            showHiddenPostsDialog(fragmentManager, localFilters)
            return true
        } else if (switchItemId0 == R.id.menu_star_text || switchItemId0 == R.id.menu_star_icon) {
            val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
            FavoritesStorage.getInstance().add(
                page.chanName!!,
                page.boardName,
                page.threadNumber!!,
                parcelableExtra.threadTitle,
                true,
            )
            updateOptionsMenu()
            return true
        } else if (switchItemId0 == R.id.menu_unstar_text || switchItemId0 == R.id.menu_unstar_icon) {
            FavoritesStorage.getInstance().remove(page.chanName, page.boardName, page.threadNumber)
            updateOptionsMenu()
            return true
        } else if (switchItemId0 == R.id.menu_open_original_thread) {
            val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
            val chan = getPreferred(null, retainableExtra.archivedThreadUri)
            if (chan.name != null) {
                val uri = retainableExtra.archivedThreadUri
                val boardName = chan.locator.safe(true).getBoardName(uri)
                val threadNumber = chan.locator.safe(true).getThreadNumber(uri)
                if (threadNumber != null) {
                    val threadTitle = adapter.originalPostItem?.getSubjectOrComment()
                    uiManager
                        .navigator()!!
                        .navigatePosts(chan.name, boardName, threadNumber, null, threadTitle)
                }
            }
            return true
        } else if (switchItemId0 == R.id.menu_archive) {
            var threadTitle: String? = null
            val posts = ArrayList<Post>()
            for (postItem in adapter) {
                if (threadTitle == null) {
                    threadTitle =
                        StringUtils.emptyIfNull(postItem.getSubjectOrComment())
                }
                posts.add(postItem.getPost())
            }
            uiManager.dialog().performSendArchiveThread(
                fragmentManager,
                page.chanName,
                page.boardName,
                page.threadNumber,
                threadTitle,
                posts,
            )
            return true
        }
        return false
    }

    /**
     * Switches the list between the thread and the popular posts — the posts that got replies, most
     * replied first. Hidden posts are left out and their replies don't count towards anyone's total.
     * A thread nobody replied in has nothing to show, so the switch is refused instead of emptying
     * the list.
     */
    private fun setPopularPosts(popular: Boolean) {
        val adapter = this.adapter
        adapter.setPopularMode(popular)
        if (popular && adapter.getItemCount() == 0) {
            adapter.setPopularMode(false)
            show(R.string.not_found)
            return
        }
        getParcelableExtra(ParcelableExtra.FACTORY).popularPosts = popular
        selectionMode?.finish()
        (getRecyclerView().getLayoutManager() as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
        updateImportantPostsFastScrollBarDecorationData()
        notifyTitleChanged()
        updateOptionsMenu()
    }

    /**
     * Position of [postNumber] in the list, leaving the popular view first when it doesn't list that
     * post: going to a post means going to it in the thread. Negative if the post isn't loaded at all.
     */
    private fun positionToScrollTo(postNumber: PostNumber): Int {
        val position = this.adapter.positionOfPostNumber(postNumber)
        if (position >= 0 || !this.adapter.isPopularMode) {
            return position
        }
        setPopularPosts(false)
        return this.adapter.positionOfPostNumber(postNumber)
    }

    override fun onFavoritesUpdate(
        favoriteItem: FavoriteItem,
        action: FavoritesStorage.Action,
    ) {
        val page = getPage()
        if (!favoriteItem.equals(page.chanName, page.boardName, page.threadNumber)) {
            return
        }
        when (action) {
            FavoritesStorage.Action.ADD, FavoritesStorage.Action.REMOVE -> {
                updateOptionsMenu()
                // Removing a renamed favorite takes its name with it: the header falls back to the subject.
                notifyTitleChanged()
            }

            FavoritesStorage.Action.MODIFY_TITLE -> {
                notifyTitleChanged()
            }

            else -> {}
        }
    }

    public override fun onAppearanceOptionChanged(what: Int) {
        val switchItemId1 = what
        if (switchItemId1 == R.id.menu_spoilers || switchItemId1 == R.id.menu_my_posts || switchItemId1 == R.id.menu_sfw_mode) {
            notifyAllAdaptersChanged()
        }
    }

    private fun onCreateSelection(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val page = getPage()
        val chan = chan
        this.adapter.setSelectionModeEnabled(true)
        mode.setTitle(
            getColonString(
                resources,
                R.string.selected,
                this.adapter.selectedCount,
            ),
        )
        val board = chan.configuration.safe().obtainBoard(page.boardName)
        menu
            .add(0, R.id.menu_make_threadshot, 0, R.string.make_threadshot)
            .setIcon(getActionBarIcon(R.attr.iconActionMakeThreadshot))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (replyable?.onRequestReply(false) == true) {
            menu
                .add(0, R.id.menu_reply, 0, R.string.reply)
                .setIcon(getActionBarIcon(R.attr.iconActionPaste))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (board.allowDeleting) {
            val deleting = chan.configuration.safe().obtainDeleting(page.boardName)
            if (deleting != null && deleting.multiplePosts) {
                menu
                    .add(0, R.id.menu_delete, 0, R.string.delete)
                    .setIcon(getActionBarIcon(R.attr.iconActionDelete))
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        if (board.allowReporting) {
            val reporting = chan.configuration.safe().obtainReporting(page.boardName)
            if (reporting != null && reporting.multiplePosts) {
                menu
                    .add(0, R.id.menu_report, 0, R.string.report)
                    .setIcon(getActionBarIcon(R.attr.iconActionReport))
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        return true
    }

    private fun onSelectionItemSelected(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        val adapter = this.adapter
        val switchItemId2 = item.getItemId()
        if (switchItemId2 == R.id.menu_make_threadshot) {
            val postItems = adapter.selectedItems
            if (postItems.size > 0) {
                val page = getPage()
                val threadTitle = adapter.originalPostItem?.getSubjectOrComment()
                ThreadshotPerformer(
                    fragmentManager,
                    page.chanName!!,
                    page.boardName,
                    page.threadNumber,
                    threadTitle,
                    postItems,
                    getRecyclerView().getWidth(),
                )
            }
            mode.finish()
            return true
        } else if (switchItemId2 == R.id.menu_reply) {
            val data = ArrayList<ReplyData>()
            for (postItem in adapter.selectedItems) {
                data.add(ReplyData(postItem.getPostNumber(), null))
            }
            if (data.size > 0) {
                replyable!!.onRequestReply(
                    true,
                    *CommonUtils.toArray(data, ReplyData::class.java).orEmpty(),
                )
            }
            mode.finish()
            return true
        } else if (switchItemId2 == R.id.menu_delete) {
            val postItems = adapter.selectedItems
            val postNumbers = ArrayList<PostNumber>()
            for (postItem in postItems) {
                if (!postItem.isDeleted()) {
                    postNumbers.add(postItem.getPostNumber())
                }
            }
            if (postNumbers.size > 0) {
                val page = getPage()
                uiManager.dialog().performSendDeletePosts(
                    fragmentManager,
                    page.chanName,
                    page.boardName,
                    page.threadNumber,
                    postNumbers,
                )
            }
            mode.finish()
            return true
        } else if (switchItemId2 == R.id.menu_report) {
            val postItems = adapter.selectedItems
            val postNumbers = ArrayList<PostNumber>()
            for (postItem in postItems) {
                if (!postItem.isDeleted()) {
                    postNumbers.add(postItem.getPostNumber())
                }
            }
            if (postNumbers.size > 0) {
                val page = getPage()
                uiManager.dialog().performSendReportPosts(
                    fragmentManager,
                    page.chanName,
                    page.boardName,
                    page.threadNumber,
                    postNumbers,
                )
            }
            mode.finish()
            return true
        }
        return false
    }

    private fun onDestroySelection() {
        this.adapter.setSelectionModeEnabled(false)
        selectionMode = null
    }

    public override fun onSearchSubmit(query: String): Boolean {
        val adapter = this.adapter
        if (adapter.getItemCount() == 0) {
            return true
        }
        val postItems: MutableList<PostItem> = adapter.copyItems()
        searchWorker?.cancel()
        searchWorker =
            SearchWorker(
                postStateProvider,
                chan,
                postItems,
                query,
                lastEditedPostNumbers,
                lastNewPostNumbers,
                SearchWorker.Callback { foundPostNumbers: MutableList<PostNumber>, queries: MutableSet<String> ->
                    this.onSearchResult(
                        foundPostNumbers,
                        queries,
                    )
                },
            )
        setCustomSearchView(searchProcessView)
        return false
    }

    public override fun onSearchCancel() {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        if (retainableExtra.searching) {
            retainableExtra.searching = false
            setCustomSearchView(null)
            updateOptionsMenu()
            this.adapter.setHighlightText(mutableListOf())
        }
    }

    private fun onSearchResult(
        foundPostNumbers: MutableList<PostNumber>,
        queries: MutableSet<String>,
    ) {
        searchWorker = null
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.searchPostNumbers = foundPostNumbers
        retainableExtra.searching = true
        if (foundPostNumbers.isEmpty()) {
            setCustomSearchView(null)
            this.adapter.setHighlightText(mutableListOf())
            show(R.string.not_found)
            updateSearchTitle()
        } else {
            setCustomSearchView(searchControlView)
            this.adapter.setHighlightText(queries)
            val listPosition =
                (getRecyclerView().getLayoutManager() as LinearLayoutManager)
                    .findFirstVisibleItemPosition()
            clearSearchFocus()
            val postNumber =
                if (listPosition >= 0) {
                    this.adapter
                        .getItem(listPosition)
                        .getPostNumber()
                } else {
                    null
                }
            var index = 0
            if (postNumber != null) {
                for (i in foundPostNumbers.indices) {
                    if (foundPostNumbers[i].compareTo(postNumber) >= 0) {
                        index = i
                        break
                    }
                }
            }
            retainableExtra.searchLastIndex = index
            findNext(index)
        }
    }

    private fun showSearchDialog() {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        if (!retainableExtra.searchPostNumbers.isEmpty()) {
            uiManager
                .dialog()
                .displayList(this.adapter.configurationSet, retainableExtra.searchPostNumbers)
        }
    }

    private fun findNext(addIndex: Int) {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        val count = retainableExtra.searchPostNumbers.size
        if (count > 0) {
            retainableExtra.searchLastIndex =
                (retainableExtra.searchLastIndex + addIndex + count) % count
            val position =
                positionToScrollTo(
                    retainableExtra.searchPostNumbers[retainableExtra.searchLastIndex],
                )
            if (position >= 0) {
                smoothScrollToPosition(getRecyclerView(), position)
            }
            updateSearchTitle()
        }
    }

    private fun updateSearchTitle() {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        searchResultText.setText(
            (retainableExtra.searchLastIndex + 1).toString() + "/" +
                retainableExtra.searchPostNumbers.size,
        )
    }

    private fun consumeNewPostData(): Boolean {
        val page = getPage()
        return consumeNewPostData(context, page.chanName, page.boardName, page.threadNumber)
    }

    public override fun onDrawerNumberEntered(number: Int): Int {
        if (this.adapter.isPopularMode) {
            // Both the ordinal index and the post number the drawer takes count the thread, not the
            // popular view, so go back to it before looking anything up.
            setPopularPosts(false)
        }
        val adapter = this.adapter
        val count = adapter.getItemCount()
        var success = false
        if (count > 0 && number > 0) {
            if (number <= count) {
                val position = adapter.positionOfOrdinalIndex(number - 1)
                if (position >= 0) {
                    smoothScrollToPosition(getRecyclerView(), position)
                    success = true
                }
            }
            if (!success) {
                val position = adapter.positionOfPostNumber(PostNumber(number, 0))
                if (position >= 0) {
                    smoothScrollToPosition(getRecyclerView(), position)
                    success = true
                } else {
                    show(R.string.post_is_not_found)
                }
            }
        }
        var result = DrawerForm.RESULT_REMOVE_ERROR_MESSAGE
        if (success) {
            result = result or DrawerForm.RESULT_SUCCESS
        }
        return result
    }

    public override fun updatePageConfiguration(postNumber: PostNumber?) {
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        parcelableExtra.scrollToPostNumber = postNumber
        if (!scrollToPostFromExtra(false)) {
            if (!hasReadTask()) {
                refreshPosts(false)
            }
        }
    }

    public override fun onListPulled(
        wrapper: PullableWrapper,
        side: PullableWrapper.Side,
    ) {
        switchList()
        refreshPostsWithoutIndication(false)
    }

    private fun scrollToPostFromExtra(instantly: Boolean): Boolean {
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        val scrollToPostNumber = parcelableExtra.scrollToPostNumber
        if (scrollToPostNumber != null) {
            val position = positionToScrollTo(scrollToPostNumber)
            if (position >= 0) {
                val recyclerView = getRecyclerView()
                if (instantly) {
                    (recyclerView.getLayoutManager() as LinearLayoutManager)
                        .scrollToPositionWithOffset(position, 0)
                } else {
                    smoothScrollToPosition(recyclerView, position)
                }
                parcelableExtra.scrollToPostNumber = null
                return true
            }
        }
        return false
    }

    private fun decodeThreadExtra() {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        var localFiltersDecoded = false
        val threadExtra = retainableExtra.threadExtra
        if (threadExtra != null) {
            try {
                JsonSerial.reader(threadExtra).use { reader ->
                    reader.startObject()
                    while (!reader.endStruct()) {
                        when (reader.nextName()) {
                            "filters" -> {
                                hidePerformer.decodeLocalFilters(reader)
                                localFiltersDecoded = true
                            }

                            else -> {
                                reader.skip()
                            }
                        }
                    }
                }
            } catch (e: ParseException) {
                e.printStackTrace()
                retainableExtra.threadExtra = null
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
        if (!localFiltersDecoded) {
            try {
                hidePerformer.decodeLocalFilters(null)
            } catch (e: ParseException) {
                e.printStackTrace()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }

    private fun encodeAndStoreThreadExtra() {
        var extra: ByteArray? = null
        if (hidePerformer.hasLocalFilters()) {
            try {
                writer().use { writer ->
                    writer.startObject()
                    writer.name("filters")
                    hidePerformer.encodeLocalFilters(writer)
                    writer.endObject()
                    extra = writer.build()
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.threadExtra = extra
        val page = getPage()
        CommonDatabase.getInstance().threads.setStateExtra(
            true,
            page.chanName!!,
            page.boardName,
            page.threadNumber!!,
            false,
            null,
            true,
            extra,
        )
    }

    private fun decodeThreadState(state: ByteArray?): Pair<PostNumber, Int>? {
        var positionPostNumber: PostNumber? = null
        var positionOffset = 0
        if (state != null) {
            try {
                reader(state).use { reader ->
                    reader.startObject()
                    while (!reader.endStruct()) {
                        when (reader.nextName()) {
                            "position" -> {
                                reader.startObject()
                                while (!reader.endStruct()) {
                                    when (reader.nextName()) {
                                        "number" -> {
                                            positionPostNumber = parseNullable(reader.nextString())
                                        }

                                        "offset" -> {
                                            positionOffset = reader.nextInt()
                                        }

                                        else -> {
                                            reader.skip()
                                        }
                                    }
                                }
                            }

                            else -> {
                                reader.skip()
                            }
                        }
                    }
                }
            } catch (e: ParseException) {
                e.printStackTrace()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
        val postNumber = positionPostNumber ?: return null
        return Pair<PostNumber, Int>(postNumber, positionOffset)
    }

    private val storePositionRunnable =
        Runnable {
            if (this.adapter.isPopularMode) {
                // The popular view isn't the thread's order, so where it stands says nothing about
                // where the user was reading. Keep the position stored for the thread view.
                return@Runnable
            }
            val listPosition = obtain(getRecyclerView(), null)
            var state: ByteArray? = null
            if (listPosition != null) {
                try {
                    writer().use { writer ->
                        writer.startObject()
                        writer.name("position")
                        writer.startObject()
                        writer.name("number")
                        writer.value(
                            this.adapter
                                .getItem(listPosition.position)
                                .getPostNumber()
                                .toString(),
                        )
                        writer.name("offset")
                        writer.value(listPosition.offset)
                        writer.endObject()
                        writer.endObject()
                        state = writer.build()
                    }
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            }
            val page = getPage()
            CommonDatabase.getInstance().threads.setStateExtra(
                true,
                page.chanName!!,
                page.boardName,
                page.threadNumber!!,
                true,
                state,
                false,
                null,
            )
        }

    private val scrollListener: RecyclerView.OnScrollListener =
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: RecyclerView,
                dx: Int,
                dy: Int,
            ) {
                ConcurrentUtils.HANDLER.removeCallbacks(storePositionRunnable)
                ConcurrentUtils.HANDLER.postDelayed(storePositionRunnable, 2000L)
            }
        }

    private fun transformListPositionToPair(listPosition: ListPosition?): Pair<PostNumber, Int>? {
        listPosition ?: return null
        val postNumber = this.adapter.getItem(listPosition.position).getPostNumber() ?: return null
        return Pair<PostNumber, Int>(postNumber, listPosition.offset)
    }

    private fun transformPairToListPosition(positionPair: Pair<PostNumber, Int>?): ListPosition? {
        if (positionPair != null) {
            val position = this.adapter.positionOfPostNumber(positionPair.first)
            return if (position >= 0) ListPosition(position, positionPair.second) else null
        } else {
            return null
        }
    }

    private fun extractPosts(cleanup: PagesDatabase.Cleanup) {
        startProgressIfNecessary()
        extractPostsWithoutIndication(cleanup)
    }

    private fun extractPostsWithoutIndication(cleanup: PagesDatabase.Cleanup) {
        var currentCleanup = cleanup
        val page = getPage()
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        if (currentCleanup == PagesDatabase.Cleanup.ERASE) {
            retainableExtra.eraseExtract = true
        }
        if (currentCleanup == PagesDatabase.Cleanup.NONE &&
            cyclicalRefreshMode ==
            Preferences.CyclicalRefreshMode.FULL_LOAD_CLEANUP
        ) {
            currentCleanup = PagesDatabase.Cleanup.OLD
        }
        if (retainableExtra.eraseExtract) {
            currentCleanup = PagesDatabase.Cleanup.ERASE
            val readViewModel = getViewModel(ReadViewModel::class.java)
            readViewModel.notifyEraseStarted()
        }
        val extractViewModel = getViewModel(ExtractViewModel::class.java)
        val task =
            ExtractPostsTask(
                extractViewModel.callback,
                retainableExtra.cache,
                chan,
                page.boardName,
                page.threadNumber,
                retainableExtra.initialExtract,
                currentCleanup,
            )
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        extractViewModel.attach(task)
    }

    private val autoRefreshInterval: Int
        get() = Preferences.autoRefreshInterval * 1000

    private val refreshRunnable =
        Runnable {
            val interval = this.autoRefreshInterval
            if (interval > 0 && !hasReadTask()) {
                val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
                if (!retainableExtra.eraseExtract) {
                    val readViewModel = getViewModel(ReadViewModel::class.java)
                    readViewModel.refresh(false, false, interval)
                }
            }
            queueNextRefresh(false)
        }

    private fun queueNextRefresh(instant: Boolean) {
        ConcurrentUtils.HANDLER.removeCallbacks(refreshRunnable)
        val interval = this.autoRefreshInterval
        if (interval > 0) {
            if (instant) {
                ConcurrentUtils.HANDLER.post(refreshRunnable)
            } else {
                ConcurrentUtils.HANDLER.postDelayed(refreshRunnable, interval.toLong())
            }
        }
    }

    private fun stopRefresh() {
        ConcurrentUtils.HANDLER.removeCallbacks(refreshRunnable)
    }

    private fun refreshPosts(reload: Boolean) {
        startProgressIfNecessary()
        refreshPostsWithoutIndication(reload)
    }

    private fun refreshPostsWithoutIndication(reload: Boolean) {
        val readViewModel = getViewModel(ReadViewModel::class.java)
        readViewModel.refresh(reload, true, 0)
    }

    private fun hasExtractTask(): Boolean {
        val extractViewModel = getViewModel(ExtractViewModel::class.java)
        return extractViewModel.hasTaskOrValue()
    }

    private fun hasReadTask(): Boolean {
        val readViewModel = getViewModel(ReadViewModel::class.java)
        return readViewModel.hasTaskOrValue()
    }

    private fun startProgressIfNecessary() {
        if (!hasExtractTask() && !hasReadTask()) {
            val recyclerView = getRecyclerView()
            if (this.adapter.getItemCount() == 0) {
                recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTH)
                switchProgress()
            } else {
                recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTTOM)
                switchList()
            }
        }
    }

    private fun cancelProgressIfNecessary() {
        if (!hasExtractTask() && !hasReadTask()) {
            val recyclerView = getRecyclerView()
            recyclerView.pullable.cancelBusyState()
            // A command still running keeps the indicator the load is done with.
            if (commandsProgress.isShowing) {
                recyclerView.pullable.startBusyState(PullableWrapper.Side.TOP)
            }
            switchList()
            val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
            val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
            if (retainableExtra.errorItem != null) {
                val errorItem = retainableExtra.errorItem
                retainableExtra.errorItem = null
                showOrSwitchError(errorItem)
            }
            if (parcelableExtra.scrollToPostNumber != null) {
                scrollToPostFromExtra(recyclerView.getChildCount() == 0)
                // Forget about the request on fail
                parcelableExtra.scrollToPostNumber = null
            }
        }
    }

    private fun handleError(errorItem: ErrorItem?) {
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.errorItem = errorItem
        if (!hasExtractTask() && !hasReadTask()) {
            retainableExtra.errorItem = null
            showOrSwitchError(errorItem)
        } else {
            retainableExtra.errorItem = errorItem
        }
    }

    private fun showOrSwitchError(errorItem: ErrorItem?) {
        if (this.adapter.getItemCount() == 0) {
            switchError(errorItem)
        } else {
            show(errorItem)
        }
    }

    private class LastToast {
        var id: String? = null
        var newCount: Int = 0
        var deletedCount: Int = 0
        var hasEdited: Boolean = false
        var replyCount: Int = 0
        var postNumber: PostNumber? = null

        fun update(
            toastVisible: Boolean,
            newCount: Int,
            deletedCount: Int,
            hasEdited: Boolean,
            replyCount: Int,
        ): Boolean {
            if (toastVisible) {
                this.newCount += newCount
                this.deletedCount += deletedCount
                this.hasEdited = this.hasEdited or hasEdited
                this.replyCount += replyCount
            } else {
                this.newCount = newCount
                this.deletedCount = deletedCount
                this.hasEdited = hasEdited
                this.replyCount = replyCount
            }
            return this.newCount > 0 || this.deletedCount > 0 || this.hasEdited || this.replyCount > 0
        }
    }

    private val lastToast = LastToast()

    override fun onExtractPostsComplete(
        result: ExtractPostsTask.Result?,
        cancelled: Boolean,
    ) {
        val page = getPage()
        if (result != null) {
            WatcherNotifications.cancelReplies(
                context,
                page.chanName,
                page.boardName,
                page.threadNumber,
                result.replyPosts,
            )
            CommonDatabase.getInstance().echo.markReadAsync(
                page.chanName!!,
                page.boardName,
                page.threadNumber!!,
                result.replyPosts,
            )
        }
        if (cancelled) {
            return
        }

        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        val recyclerView = getRecyclerView()
        val adapter = this.adapter
        var updateAdapters = false
        var listPositionFromState: ListPosition? = null
        var keepPositionPair: Pair<PostNumber, Int>? = null
        val initial = retainableExtra.initialExtract
        retainableExtra.initialExtract = false
        val erase = retainableExtra.eraseExtract
        retainableExtra.eraseExtract = false
        val wasEmpty = adapter.getItemCount() == 0

        if (result != null) {
            if (erase && result.cache.isEmpty) {
                FavoritesStorage
                    .getInstance()
                    .remove(page.chanName, page.boardName, page.threadNumber)
                closePage()
                return
            }
            if (result.cache.isNewThreadOnce) {
                parcelableExtra.unreadPosts.addAll(result.postItems.keys)
                StatisticsStorage.getInstance().incrementThreadsViewed(getPage().chanName!!)
            } else {
                parcelableExtra.unreadPosts.addAll(result.newPosts)
                parcelableExtra.unreadPosts.addAll(result.deletedPosts)
                parcelableExtra.unreadPosts.addAll(result.editedPosts)
            }
            retainableExtra.cache = result.cache
            if (retainableExtra.cacheState == null) {
                retainableExtra.cacheState = result.cache.state
            }
            if (result.cacheChanged) {
                retainableExtra.archivedThreadUri = result.archivedThreadUri
                retainableExtra.uniquePosters = result.uniquePosters
            }
            if (!result.postItems.isEmpty() || !result.removedPosts.isEmpty()) {
                if (adapter.getItemCount() > 0) {
                    var listPosition =
                        obtain(
                            recyclerView,
                            PositionTest { position: Int -> !adapter.getItem(position).isDeleted() },
                        )
                    if (listPosition == null) {
                        listPosition = obtain(recyclerView, null)
                    }
                    keepPositionPair = transformListPositionToPair(listPosition)
                }
                adapter.insertItems(result.postItems, result.removedPosts)
                updateAdapters = true
            }
            if (result.flags != null) {
                retainableExtra.hiddenPosts.clear()
                retainableExtra.hiddenPosts.addAll(result.flags.hiddenPosts!!)
                retainableExtra.userPosts.clear()
                retainableExtra.userPosts.addAll(result.flags.userPosts!!)
            }
            if (result.stateExtra != null) {
                listPositionFromState =
                    transformPairToListPosition(decodeThreadState(result.stateExtra.state))
                retainableExtra.threadExtra = result.stateExtra.extra
                decodeThreadExtra()
            }

            val newCount = result.newPosts.size
            val deletedCount = result.deletedPosts.size
            val hasEdited = !result.editedPosts.isEmpty()
            val replyCount = result.replyPosts.size
            val toastVisible = isShowing(lastToast.id)
            if (lastToast.update(toastVisible, newCount, deletedCount, hasEdited, replyCount)) {
                updateAdapters = true
                var message: String?
                if (lastToast.replyCount > 0 || lastToast.deletedCount > 0) {
                    message =
                        resources.getQuantityString(
                            R.plurals.number_new__format,
                            lastToast.newCount,
                            lastToast.newCount,
                        )
                    if (lastToast.replyCount > 0) {
                        message =
                            getString(
                                R.string.__enumeration_format,
                                message,
                                resources.getQuantityString(
                                    R.plurals.number_replies__format,
                                    lastToast.replyCount,
                                    lastToast.replyCount,
                                ),
                            )
                    }
                    if (lastToast.deletedCount > 0) {
                        message =
                            getString(
                                R.string.__enumeration_format,
                                message,
                                resources.getQuantityString(
                                    R.plurals.number_deleted__format,
                                    lastToast.deletedCount,
                                    lastToast.deletedCount,
                                ),
                            )
                    }
                } else if (lastToast.newCount > 0) {
                    message =
                        resources.getQuantityString(
                            R.plurals.number_new_posts__format,
                            lastToast.newCount,
                            lastToast.newCount,
                        )
                } else {
                    message = getString(R.string.some_posts_have_been_edited)
                }

                if (lastToast.newCount > 0) {
                    val showPostNumber: PostNumber
                    val lastToastPostNumber = lastToast.postNumber
                    if (toastVisible && lastToastPostNumber != null) {
                        showPostNumber = lastToastPostNumber
                    } else {
                        showPostNumber = Collections.min<PostNumber>(result.newPosts)
                        adapter.preloadPosts(showPostNumber)
                        lastToast.postNumber = showPostNumber
                    }
                    lastToast.id =
                        show(
                            message,
                            lastToast.id,
                            ClickableToast.Button(
                                R.string.show,
                                true,
                                Runnable {
                                    if (isRunning) {
                                        val newPostIndex = adapter.positionOfPostNumber(showPostNumber)
                                        if (newPostIndex >= 0) {
                                            smoothScrollToPosition(getRecyclerView(), newPostIndex)
                                        }
                                    }
                                },
                            ),
                        )
                } else {
                    lastToast.id = show(message, lastToast.id, null)
                }

                if (deletedCount > 0 || hasEdited) {
                    val editedPostNumbers =
                        if (toastVisible) {
                            HashSet<PostNumber?>(lastEditedPostNumbers)
                        } else {
                            HashSet<PostNumber?>()
                        }
                    editedPostNumbers.addAll(result.deletedPosts)
                    editedPostNumbers.addAll(result.editedPosts)
                    lastEditedPostNumbers = editedPostNumbers
                }
                if (newCount > 0) {
                    val newPostNumbers =
                        if (toastVisible) {
                            HashSet<PostNumber?>(lastNewPostNumbers)
                        } else {
                            HashSet<PostNumber?>()
                        }
                    newPostNumbers.addAll(result.newPosts)
                    lastNewPostNumbers = newPostNumbers
                }
                retainableExtra.errorItem = null
            }
            updateImportantPostsFastScrollBarDecorationData()

            val readViewModel = getViewModel(ReadViewModel::class.java)
            readViewModel.notifyExtracted()
        }

        if (updateAdapters) {
            uiManager.dialog().updateAdapters(this.adapter.configurationSet.stackInstance!!)
            notifyAllAdaptersChanged()
            val listPosition = transformPairToListPosition(keepPositionPair)
            if (listPosition != null) {
                listPosition.apply(recyclerView)
            }
        }
        if (result != null) {
            if (initial && result.postItems.isEmpty()) {
                if (!hasReadTask()) {
                    refreshPostsWithoutIndication(false)
                }
            } else {
                if (wasEmpty && !result.postItems.isEmpty()) {
                    recyclerView.pullable.cancelBusyState()
                    switchList()
                    recyclerView.pullable.startBusyState(PullableWrapper.Side.BOTTOM)
                    showScaleAnimation()
                }
                if (retainableExtra.shouldExtract()) {
                    extractPostsWithoutIndication(PagesDatabase.Cleanup.NONE)
                }
            }
            onExtractPostsCompleteInternal(wasEmpty, listPositionFromState)
        } else {
            cancelProgressIfNecessary()
            handleError(ErrorItem(ErrorItem.Type.UNKNOWN))
        }
    }

    private fun onExtractPostsCompleteInternal(
        firstLayout: Boolean,
        listPositionFromState: ListPosition?,
    ) {
        val adapter = this.adapter
        var listPosition = takeListPosition()
        if (listPosition == null) {
            listPosition = listPositionFromState
        }
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        val page = getPage()

        if (firstLayout &&
            (
                parcelableExtra.scrollToPostNumber == null ||
                    !scrollToPostFromExtra(true)
            ) &&
            listPosition != null
        ) {
            listPosition.apply(getRecyclerView())
        }

        if (!parcelableExtra.isAddedToHistory) {
            parcelableExtra.isAddedToHistory = true
            CommonDatabase.getInstance().history.addHistoryAsync(
                page.chanName!!,
                page.boardName,
                page.threadNumber!!,
                parcelableExtra.threadTitle,
            )
        }
        val iterator = this.adapter.iterator()
        if (iterator.hasNext()) {
            var title: String? = iterator.next().getSubjectOrComment()
            if (StringUtils.isEmptyOrWhitespace(title)) {
                title = null
            }
            FavoritesStorage.getInstance().updateTitle(
                page.chanName,
                page.boardName,
                page.threadNumber,
                title,
                false,
            )
            if (!equals(StringUtils.nullIfEmpty(parcelableExtra.threadTitle), title)) {
                CommonDatabase
                    .getInstance()
                    .history
                    .updateTitleAsync(page.chanName!!, page.boardName, page.threadNumber!!, title)
                parcelableExtra.threadTitle = title
                notifyTitleChanged()
            }
        }

        val selected = parcelableExtra.selectedPosts
        if (selected != null) {
            parcelableExtra.selectedPosts = null
            for (postNumber in selected) {
                val postItem = adapter.findPostItem(postNumber)
                if (postItem != null) {
                    adapter.toggleItemSelected(postItem)
                }
            }
            selectionMode = startActionMode(SelectionCallback(this))
        }

        updateOptionsMenu()
        cancelProgressIfNecessary()
        autoRunThreadCommands()
    }

    /** Thread commands ([CommandsStorage.UseIn.THREAD]) scoped to this thread's forum/board. */
    private fun availableThreadCommands(): List<CommandsStorage.CommandItem> {
        val page = getPage()
        return CommandsStorage
            .getInstance()
            .getAvailable(CommandsStorage.UseIn.THREAD, page.chanName, page.boardName)
    }

    /**
     * Fills the thread menu's Commands submenu with the commands available here, hiding the whole entry
     * when there are none. Auto-run commands are listed but disabled — they fire when the thread opens
     * and can't be triggered by hand. Item ids are indexes into [availableThreadCommands]; a trailing
     * entry in its own group (hence the divider) opens the Commands screen for editing, which a submenu
     * item can't offer on a long tap the way the posting screen's dropdown does.
     */
    private fun prepareThreadCommandsMenu(item: MenuItem) {
        val commands = availableThreadCommands()
        item.setVisible(commands.isNotEmpty())
        val subMenu = item.getSubMenu() ?: return
        subMenu.clear()
        subMenu.setGroupDividerEnabled(true)
        commands.forEachIndexed { index, command ->
            val name = command.name
            val title = if (name.isNullOrEmpty()) getString(R.string.command) else name
            subMenu.add(COMMANDS_GROUP, index, index, title).setEnabled(!command.autoRun)
        }
        subMenu.add(0, R.id.menu_edit_commands, commands.size, R.string.edit)
    }

    /**
     * Snapshots [postItems] as the `posts` array handed to a thread command. Comments go out as the
     * post's HTML and attachments as the files the post arrived with, the same forms the replacements
     * come back in — always the post as it arrived, never an override a previous run left behind, so
     * re-running a command is idempotent.
     *
     * Only a post's [files][Post.Attachment.File] are handed over: what the chan embedded, and what the
     * app parsed out of the comment, is not a file a script could point elsewhere.
     */
    private fun collectThreadPosts(postItems: List<PostItem>): List<CommandRunner.ThreadPost> {
        val posts = ArrayList<CommandRunner.ThreadPost>()
        for (postItem in postItems) {
            val post = postItem.getPost()
            posts.add(
                CommandRunner.ThreadPost(
                    postItem.getPostNumber().toString(),
                    nullIfEmpty(post.name),
                    nullIfEmpty(post.email),
                    post.icons.firstOrNull()?.title,
                    nullIfEmpty(post.subject),
                    emptyIfNull(post.comment),
                    post.attachments.filterIsInstance<Post.Attachment.File>(),
                ),
            )
        }
        return posts
    }

    /**
     * Runs the auto-run thread commands against the currently loaded posts. Called after every extract,
     * so freshly loaded/refreshed posts get transformed too (e.g. re-decrypted). Spawns no JS engine
     * when the user has no auto-run thread commands, so it costs nothing for everyone else.
     */
    private fun autoRunThreadCommands() {
        for (command in availableThreadCommands().filter { it.autoRun }) {
            runThreadCommand(command, notifyEmpty = false)
        }
    }

    /**
     * Shows a long-running command as the same top busy indicator a refresh uses, the thread staying
     * readable while it works. A load already showing it wins — [PullableWrapper.startBusyState] is a
     * no-op then, and the load owns what it put up.
     */
    private fun startCommandProgress() {
        getRecyclerView().pullable.startBusyState(PullableWrapper.Side.TOP)
    }

    /**
     * Takes the indicator down, unless a load is using it — the command's [startCommandProgress] was
     * then a no-op, so its end must not end the load's. A load finishing first leaves the command
     * without an indicator (see [cancelProgressIfNecessary]).
     *
     * Skipped once the page is gone: the indicator goes with the list, and the view models this asks
     * about are no longer ours to reach.
     */
    private fun cancelCommandProgress() {
        if (isRunning && !hasExtractTask() && !hasReadTask()) {
            getRecyclerView().pullable.cancelBusyState()
        }
    }

    /**
     * Runs [command] over [postItems] and applies its inline replacements to the displayed comments.
     * [notifyEmpty] toasts when a manual run changed nothing, so the user gets feedback that it ran;
     * auto-runs stay silent.
     *
     * [postItems] is the whole thread for a run started from the thread menu and a single post for one
     * started from that post's context menu (see [onRunPostCommand]). It is snapshotted here rather
     * than read again when the result arrives, so what the command was handed and what its result is
     * applied to are the same posts even if the thread has been refreshed meanwhile.
     */
    private fun runThreadCommand(
        command: CommandsStorage.CommandItem,
        postItems: List<PostItem> = adapter.toList(),
        notifyEmpty: Boolean = true,
    ) {
        val posts = collectThreadPosts(postItems)
        commandsProgress.start()
        val run =
            CommandRunner.runThread(
                command,
                posts,
                getPage().chanName,
                getPage().threadNumber,
                getPage().boardName,
            ) { result ->
                // Delivered on the main thread; the page may have been left by the time it arrives. The
                // indicator is released before that check, since nothing releases it afterwards.
                commandsProgress.finish()
                if (!isRunning) {
                    return@runThread
                }
                when (result) {
                    is CommandRunner.ThreadResult.Success -> {
                        val changed = applyThreadChanges(postItems, result.changes)
                        if (changed == 0 && notifyEmpty) {
                            show(R.string.command_no_changes)
                        }
                    }

                    is CommandRunner.ThreadResult.Failure -> {
                        show(getString(R.string.command_failed__format, result.message))
                    }
                }
            }
        trackCommandRun(run)
    }

    /**
     * Keeps [run] so [cancelCommandRuns] can reach it, dropping the ones that are over first — a thread
     * that auto-runs a command on every refresh would otherwise collect an entry per run for as long as
     * it stays open.
     */
    private fun trackCommandRun(run: CommandRunner.Run) {
        commandRuns.removeAll { it.isFinished }
        commandRuns.add(run)
    }

    /**
     * Stops the commands still running for this page. Their results would be dropped by the `isRunning`
     * check anyway, so letting them go on would only hold an engine — and this page with it — until
     * each one's deadline, which a per-post run over a long thread measures in minutes.
     */
    private fun cancelCommandRuns() {
        for (run in commandRuns) {
            run.cancel()
        }
        commandRuns.clear()
    }

    /**
     * Applies a thread command's [changes] (post number → replacement comment and files) to [postItems],
     * overriding what the matching posts display and clearing any override on the rest, then rebinds.
     * Returns how many posts the command changed.
     *
     * Only the posts the run was given are touched: a run over a single post must leave the overrides of
     * the posts it never saw alone, while a run over the whole thread clearing them is what makes
     * re-running a command replace its previous result rather than add to it.
     *
     * A post whose files were replaced also has its gallery entry rebuilt, since the gallery is a list
     * of its own, filled when a post is loaded — leaving it alone would open the file the post no longer
     * shows.
     */
    private fun applyThreadChanges(
        postItems: List<PostItem>,
        changes: Map<String, CommandRunner.ThreadChange>,
    ): Int {
        val chan = chan
        val gallerySet = adapter.gallerySet
        val changedPostItems = ArrayList<PostItem>()
        for (postItem in postItems) {
            val change = changes[postItem.getPostNumber().toString()]
            postItem.setCommentOverride(change?.comment)
            if (postItem.setAttachmentsOverride(chan, change?.attachments)) {
                gallerySet.remove(postItem.getPostNumber())
                gallerySet.put(postItem.getPostNumber(), postItem.getAttachmentItems())
            }
            if (change != null) {
                changedPostItems.add(postItem)
            }
        }
        notifyAllAdaptersChanged()
        // The dialogs standing over these posts show the page's own post items, so the override is
        // already theirs — but their lists are not this adapter's, and one of them may be where the
        // command was started from. A post whose override was only cleared is not in the changes at all,
        // which is why the rebind above is not just this.
        for (postItem in changedPostItems) {
            uiManager.sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS)
        }
        return changedPostItems.size
    }

    /**
     * Runs a per-post thread command over the one post its context menu was opened on (see
     * [UiManager.Observer.onRunPostCommand]). Broadcast to every page, so the posts that aren't this
     * thread's are dropped — the same guard [onPostItemMessage] uses.
     */
    override fun onRunPostCommand(
        postItem: PostItem,
        command: CommandsStorage.CommandItem,
    ) {
        // The list's own post item, not the one the menu was built from: the override belongs on what
        // this page renders, which is the same object for every menu that can reach here but need not
        // stay so.
        val target = adapter.findPostItem(postItem.getPostNumber()) ?: return
        runThreadCommand(command, listOf(target))
    }

    private fun initializeImportantPostsMarksFastScrollBarDecoration(
        recyclerView: PaddedRecyclerView,
        retainableExtra: RetainableExtra,
    ) {
        val showImportantPostsOnFastScrollBar =
            isShowImportantPostsOnFastScrollBar && isActiveScrollbar
        if (showImportantPostsOnFastScrollBar) {
            val decoration = ImportantPostsMarksFastScrollBarDecoration(context)
            importantPostsMarksFastScrollBarDecoration = decoration
            recyclerView.setImportantPostsMarksFastScrollBarDecoration(decoration)
            val fastScrollBarDecorationData =
                retainableExtra.importantPostsMarksFastScrollBarDecorationData

            if (fastScrollBarDecorationData == null) {
                updateImportantPostsFastScrollBarDecorationData()
            } else {
                decoration.setData(fastScrollBarDecorationData)
            }
        } else {
            retainableExtra.importantPostsMarksFastScrollBarDecorationData = null
        }
    }

    private fun updateImportantPostsFastScrollBarDecorationData() {
        val decoration = importantPostsMarksFastScrollBarDecoration ?: return

        var importantPostsMarksFastScrollBarDecorationData: ImportantPostsMarksFastScrollBarDecoration.Data? =
            null
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        val userPosts = retainableExtra.userPosts

        if (!userPosts.isEmpty()) {
            val adapter = this.adapter
            val userPostsPositions: MutableSet<Int> = HashSet()
            val repliesPositions: MutableSet<Int> = HashSet()

            for (userPostNumber in userPosts) {
                val userPostPosition = adapter.positionOfPostNumber(userPostNumber)
                if (userPostPosition < 0) {
                    continue
                }
                val showUserPostOnScrollBar =
                    !postStateProvider.isHiddenResolve(adapter.getItem(userPostPosition))
                if (showUserPostOnScrollBar) {
                    userPostsPositions.add(userPostPosition)
                }
                val repliesPostNumbers: Set<PostNumber> =
                    adapter.getItem(userPostPosition).getReferencesFrom()
                for (replyPostNumber in repliesPostNumbers) {
                    val replyPosition = adapter.positionOfPostNumber(replyPostNumber)
                    val showReplyOnScrollBar =
                        replyPosition >= 0 &&
                            !postStateProvider.isHiddenResolve(
                                adapter.getItem(replyPosition),
                            )
                    if (showReplyOnScrollBar) {
                        repliesPositions.add(replyPosition)
                    }
                }
            }

            importantPostsMarksFastScrollBarDecorationData =
                ImportantPostsMarksFastScrollBarDecoration.Data(
                    userPostsPositions,
                    repliesPositions,
                    adapter.getItemCount(),
                )
        }

        retainableExtra.importantPostsMarksFastScrollBarDecorationData =
            importantPostsMarksFastScrollBarDecorationData
        decoration.setData(
            importantPostsMarksFastScrollBarDecorationData,
        )
        getRecyclerView().invalidateItemDecorations()
    }

    override fun onReadPostsSuccess(
        cacheState: PagesDatabase.Cache.State?,
        consumeReplies: ConsumeReplies,
    ) {
        val readViewModel = getViewModel(ReadViewModel::class.java)
        val retainableExtra = getRetainableExtra(RetainableExtra.FACTORY)
        retainableExtra.cacheState = cacheState
        // The cache now holds what the board just said, so a decorator's local payload replacement
        // has to give way to it. Rebind explicitly: a post that came back identical to its cached
        // copy is reported as unchanged, so extraction alone would never redraw it.
        if (uiManager.decorator().discardExtraOverrides(this.adapter)) {
            scheduleNotifyDataSetChanged()
        }
        if ((readViewModel.visibleReadResult || this.autoRefreshInterval > 0) && !hasExtractTask() && retainableExtra.shouldExtract()) {
            consumeReplies.consume()
            if (!readViewModel.visibleReadResult) {
                startProgressIfNecessary()
            }
            extractPostsWithoutIndication(PagesDatabase.Cleanup.NONE)
        } else {
            cancelProgressIfNecessary()
        }
        queueNextRefresh(false)
    }

    override fun onReadPostsRedirect(target: RedirectException.Target) {
        cancelProgressIfNecessary()
        queueNextRefresh(false)
        handleRedirect(target.chanName, target.boardName, target.threadNumber, target.postNumber)
    }

    override fun onReadPostsFail(errorItem: ErrorItem?) {
        cancelProgressIfNecessary()
        handleError(errorItem)
        val parcelableExtra = getParcelableExtra(ParcelableExtra.FACTORY)
        parcelableExtra.scrollToPostNumber = null
        queueNextRefresh(false)
    }

    private var postNotifyDataSetChanged: Runnable? = null

    private fun scheduleNotifyDataSetChanged() {
        if (postNotifyDataSetChanged == null) {
            postNotifyDataSetChanged =
                Runnable {
                    this.adapter.notifyDataSetChanged()
                    if (updateImportantPostsFastScrollBarDecorationDataAfterInvalidateAllViews) {
                        updateImportantPostsFastScrollBarDecorationData()
                        updateImportantPostsFastScrollBarDecorationDataAfterInvalidateAllViews =
                            false
                    }
                }
        }
        val recyclerView = getRecyclerView()
        recyclerView.removeCallbacks(postNotifyDataSetChanged)
        recyclerView.post(postNotifyDataSetChanged)
    }

    private var updateImportantPostsFastScrollBarDecorationDataAfterInvalidateAllViews = false

    override fun onPostItemMessage(
        postItem: PostItem,
        message: UiManager.Message,
    ) {
        // The message is broadcast to every page, so drop the posts that aren't this thread's. Asking
        // the thread rather than the list keeps hiding and marking working on the posts the popular
        // view leaves out; the two branches that need a row check the position themselves.
        if (this.adapter.findPostItem(postItem.getPostNumber()) == null) {
            return
        }
        val position = this.adapter.positionOfPostNumber(postItem.getPostNumber())
        val recyclerView = getRecyclerView()
        when (message) {
            UiManager.Message.POST_INVALIDATE_ALL_VIEWS -> {
                scheduleNotifyDataSetChanged()
            }

            UiManager.Message.INVALIDATE_COMMENT_VIEW -> {
                if (position >= 0) {
                    this.adapter.invalidateComment(position)
                }
            }

            UiManager.Message.PERFORM_SWITCH_USER_MARK -> {
                setPostUserPost(postItem, !postStateProvider.isUserPost(postItem.getPostNumber()))
                updateImportantPostsFastScrollBarDecorationDataAfterInvalidateAllViews = true
                uiManager.sendPostItemMessage(
                    postItem,
                    UiManager.Message.POST_INVALIDATE_ALL_VIEWS,
                )
            }

            UiManager.Message.PERFORM_SWITCH_HIDE -> {
                setPostHideState(
                    postItem,
                    if (!postItem.getHideState().hidden) {
                        HideState.HIDDEN
                    } else {
                        HideState.SHOWN
                    },
                )
                if (postItem.getHideState() == HideState.HIDDEN) {
                    if (!isDisplayHiddenPostsEnabled) this.adapter.removeHiddenPost(postItem)
                }
                // Hiding a post drops it and its replies out of the popular ranking.
                this.adapter.invalidatePopularOrder()
                notifyTitleChanged()
                updateImportantPostsFastScrollBarDecorationDataAfterInvalidateAllViews = true
                uiManager.sendPostItemMessage(
                    postItem,
                    UiManager.Message.POST_INVALIDATE_ALL_VIEWS,
                )
            }

            UiManager.Message.PERFORM_HIDE_REPLIES, UiManager.Message.PERFORM_HIDE_NAME, UiManager.Message.PERFORM_HIDE_SIMILAR -> {
                val adapter = this.adapter
                adapter.cancelPreloading()
                val result: AddResult?
                when (message) {
                    UiManager.Message.PERFORM_HIDE_REPLIES -> {
                        result = hidePerformer.addHideByReplies(postItem)
                    }

                    UiManager.Message.PERFORM_HIDE_NAME -> {
                        result = hidePerformer.addHideByName(chan, postItem)
                    }

                    UiManager.Message.PERFORM_HIDE_SIMILAR -> {
                        result = hidePerformer.addHideSimilar(chan, postItem)
                    }

                    else -> {
                        throw RuntimeException()
                    }
                }
                if (result == AddResult.SUCCESS) {
                    setPostHideState(postItem, HideState.UNDEFINED)
                    adapter.invalidateHidden()
                    notifyAllAdaptersChanged()
                    encodeAndStoreThreadExtra()
                } else if (result == AddResult.EXISTS && !postItem.getHideState().hidden) {
                    setPostHideState(postItem, HideState.UNDEFINED)
                    if (message == UiManager.Message.PERFORM_HIDE_REPLIES) {
                        for (postNumber in postItem.getReferencesFrom()) {
                            val post = this.adapter.findPostItem(postNumber)
                            if (post != null) setPostHideState(post, HideState.UNDEFINED)
                        }
                    }
                    notifyAllAdaptersChanged()
                }
                adapter.invalidatePopularOrder()
                adapter.preloadPosts(
                    (recyclerView.getLayoutManager() as LinearLayoutManager)
                        .findFirstVisibleItemPosition(),
                )
            }

            UiManager.Message.PERFORM_GO_TO_POST -> {
                val goToPosition = positionToScrollTo(postItem.getPostNumber())
                if (goToPosition >= 0) {
                    // Avoid concurrent modification
                    recyclerView.post(
                        Runnable {
                            uiManager
                                .dialog()
                                .closeDialogs(this.adapter.configurationSet.stackInstance!!)
                        },
                    )
                    smoothScrollToPosition(recyclerView, goToPosition)
                }
            }
        }
    }

    override fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {
        val adapter = this.adapter
        val position = adapter.positionOfPostNumber(attachmentItem.getPostNumber())
        if (position >= 0) {
            adapter.reloadAttachment(position, attachmentItem)
        }
    }

    private class SelectionCallback(
        postsPage: PostsPage?,
    ) : ActionMode.Callback {
        private val postsPage: WeakReference<PostsPage?>

        init {
            this.postsPage = WeakReference<PostsPage?>(postsPage)
        }

        override fun onCreateActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean {
            val postsPage = this.postsPage.get()
            return postsPage != null && postsPage.onCreateSelection(mode, menu)
        }

        override fun onPrepareActionMode(
            mode: ActionMode?,
            menu: Menu?,
        ): Boolean = false

        override fun onActionItemClicked(
            mode: ActionMode,
            item: MenuItem,
        ): Boolean {
            val postsPage = this.postsPage.get()
            return postsPage != null && postsPage.onSelectionItemSelected(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            val postsPage = this.postsPage.get()
            if (postsPage != null) {
                postsPage.onDestroySelection()
            }
        }
    }

    private class SearchWorker(
        private val postStateProvider: PostStateProvider,
        private val chan: Chan,
        private val postItems: MutableList<PostItem>,
        query: String,
        private val editedPostNumbers: MutableSet<PostNumber?>,
        private val newPostNumbers: MutableSet<PostNumber?>,
        private val callback: Callback,
    ) : Runnable {
        fun interface Callback {
            fun onResult(
                foundPostNumbers: MutableList<PostNumber>,
                queries: MutableSet<String>,
            )
        }

        private val helper: SearchHelper
        private val queries: MutableSet<String>
        private val fileNames = HashSet<String>()
        private val foundPostNumbers = ArrayList<PostNumber>()

        private var start = 0

        init {
            helper = SearchHelper(isAdvancedSearch)
            helper.setFlags("m", "r", "a", "d", "e", "n", "op")
            queries = helper.handleQueries(Locale.getDefault(), query)
            ConcurrentUtils.HANDLER.post(this)
        }

        override fun run() {
            val time = SystemClock.elapsedRealtime()
            val budget = ConcurrentUtils.HALF_FRAME_TIME_MS
            val locale = Locale.getDefault()
            val fileNames = this.fileNames
            OUTER@ while (true) {
                if (SystemClock.elapsedRealtime() - time >= budget) {
                    ConcurrentUtils.HANDLER.post(this)
                    break
                }
                val index = start++
                if (index >= postItems.size) {
                    foundPostNumbers.sort()
                    callback.onResult(foundPostNumbers, queries)
                    break
                }
                val postItem = postItems[index]
                if (!postStateProvider.isHiddenResolve(postItem)) {
                    val postNumber = postItem.getPostNumber()
                    val comment = postItem.getComment(chan).toString().lowercase(locale)
                    val userPost = postStateProvider.isUserPost(postNumber)
                    var reply = false
                    for (referenceTo in postItem.getReferencesTo()) {
                        if (postStateProvider.isUserPost(referenceTo)) {
                            reply = true
                            break
                        }
                    }
                    val hasAttachments = postItem.hasAttachments()
                    val deleted = postItem.isDeleted()
                    val edited = editedPostNumbers.contains(postNumber)
                    val newPost = newPostNumbers.contains(postNumber)
                    val originalPoster = postItem.isOriginalPoster()
                    if (!helper.checkFlags(
                            "m",
                            userPost,
                            "r",
                            reply,
                            "a",
                            hasAttachments,
                            "d",
                            deleted,
                            "e",
                            edited,
                            "n",
                            newPost,
                            "op",
                            originalPoster,
                        )
                    ) {
                        continue
                    }
                    for (lowQuery in helper.getExcluded()) {
                        if (comment.contains(lowQuery)) {
                            continue@OUTER
                        }
                    }
                    val subject = postItem.getSubject().lowercase(locale)
                    val name = postItem.getFullName(chan).toString().lowercase(locale)
                    fileNames.clear()
                    val attachmentItems: List<AttachmentItem>? =
                        postItem.getAttachmentItems()
                    if (attachmentItems != null) {
                        for (attachmentItem in attachmentItems) {
                            val fileName = attachmentItem.getFileName(chan)
                            if (!fileName.isNullOrEmpty()) {
                                fileNames.add(fileName.lowercase(locale))
                                val originalName = attachmentItem.getOriginalName()
                                if (!originalName.isNullOrEmpty()) {
                                    fileNames.add(originalName.lowercase(locale))
                                }
                            }
                        }
                    }
                    var found = false
                    if (helper.hasIncluded()) {
                        QUERIES@ for (lowQuery in helper.getIncluded()) {
                            if (comment.contains(lowQuery)) {
                                found = true
                                break
                            } else if (subject.contains(lowQuery)) {
                                found = true
                                break
                            } else if (name.contains(lowQuery)) {
                                found = true
                                break
                            } else {
                                for (fileName in fileNames) {
                                    if (fileName.contains(lowQuery)) {
                                        found = true
                                        break@QUERIES
                                    }
                                }
                            }
                        }
                    } else {
                        found = true
                    }
                    if (found) {
                        foundPostNumbers.add(postNumber)
                    }
                }
            }
        }

        fun cancel() {
            ConcurrentUtils.HANDLER.removeCallbacks(this)
        }
    }

    companion object {
        /** Group of the Commands submenu's items, which carry indexes rather than `R.id` values. */
        private const val COMMANDS_GROUP = 1

        private fun showEraseDialog(fragmentManager: FragmentManager) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    AlertDialog
                        .Builder(provider.context)
                        .setTitle(R.string.erase)
                        .setMessage(R.string.thread_will_be_deleted_from_cache__sentence)
                        .setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                val postsPage = extract<PostsPage>(provider)!!
                                postsPage.extractPosts(PagesDatabase.Cleanup.ERASE)
                            },
                        ).setNegativeButton(android.R.string.cancel, null)
                        .create()
                },
            )
        }

        private fun showClearDeletedDialog(fragmentManager: FragmentManager) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    AlertDialog
                        .Builder(provider.context)
                        .setTitle(R.string.clear_deleted)
                        .setMessage(R.string.deleted_posts_will_be_deleted__sentence)
                        .setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                val postsPage = extract<PostsPage>(provider)!!
                                postsPage.extractPosts(PagesDatabase.Cleanup.DELETED)
                            },
                        ).setNegativeButton(android.R.string.cancel, null)
                        .create()
                },
            )
        }

        private fun showSummaryDialog(fragmentManager: FragmentManager) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    val context = provider.context
                    val postsPage = extract<PostsPage>(provider)!!
                    val page = postsPage.getPage()
                    val retainableExtra =
                        postsPage.getRetainableExtra(RetainableExtra.Companion.FACTORY)
                    var files = 0
                    var postsWithFiles = 0
                    var links = 0
                    for (postItem in postsPage.adapter) {
                        val attachmentItems: List<AttachmentItem>? =
                            postItem.getAttachmentItems()
                        if (attachmentItems != null) {
                            var itFiles = 0
                            for (attachmentItem in attachmentItems) {
                                val generalType = attachmentItem.getGeneralType()
                                when (generalType) {
                                    GeneralType.FILE, GeneralType.EMBEDDED -> {
                                        itFiles++
                                    }

                                    GeneralType.LINK -> {
                                        links++
                                    }
                                }
                            }
                            if (itFiles > 0) {
                                postsWithFiles++
                                files += itFiles
                            }
                        }
                    }
                    val dialog =
                        AlertDialog
                            .Builder(context)
                            .setTitle(R.string.summary)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                    val layout = SummaryLayout(dialog)
                    val boardName = page.boardName
                    if (boardName != null) {
                        var title = get(page.chanName).configuration.getBoardTitle(boardName)
                        title = StringUtils.formatBoardTitle(page.chanName!!, boardName, title)
                        layout.add(context.getString(R.string.board), title)
                    }
                    layout.add(context.getString(R.string.files__genitive), files.toString())
                    layout.add(
                        context.getString(R.string.posts_with_files__genitive),
                        postsWithFiles.toString(),
                    )
                    layout.add(
                        context.getString(R.string.links_attachments__genitive),
                        links.toString(),
                    )
                    if (retainableExtra.uniquePosters > 0) {
                        layout.add(
                            context.getString(R.string.unique_posters__genitive),
                            retainableExtra.uniquePosters.toString(),
                        )
                    }
                    dialog
                },
            )
        }

        private fun showHiddenPostsDialog(
            fragmentManager: FragmentManager,
            localFilters: MutableList<String?>,
        ) {
            val checked = BooleanArray(localFilters.size)
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    AlertDialog
                        .Builder(provider.context)
                        .setTitle(R.string.remove_rules)
                        .setMultiChoiceItems(
                            localFilters.toTypedArray(),
                            checked,
                            OnMultiChoiceClickListener { d: DialogInterface?, which: Int, isChecked: Boolean ->
                                checked[which] = isChecked
                            },
                        ).setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                val postsPage = extract<PostsPage>(provider)!!
                                var hasDeleted = false
                                var i = 0
                                var j = 0
                                while (i < checked.size) {
                                    if (checked[i]) {
                                        postsPage.hidePerformer.removeLocalFilter(j--)
                                        hasDeleted = true
                                    }
                                    i++
                                    j++
                                }
                                if (hasDeleted) {
                                    val adapter = postsPage.adapter
                                    adapter.invalidateHidden()
                                    adapter.invalidatePopularOrder()
                                    postsPage.notifyAllAdaptersChanged()
                                    postsPage.encodeAndStoreThreadExtra()
                                    adapter.preloadPosts(
                                        (
                                            postsPage
                                                .getRecyclerView()
                                                .getLayoutManager() as LinearLayoutManager
                                        ).findFirstVisibleItemPosition(),
                                    )
                                }
                            },
                        ).setNegativeButton(android.R.string.cancel, null)
                        .create()
                },
            )
        }
    }
}
