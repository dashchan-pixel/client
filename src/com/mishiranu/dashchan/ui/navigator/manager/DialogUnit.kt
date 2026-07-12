package com.mishiranu.dashchan.ui.navigator.manager

import chan.util.StringUtils

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.util.Pair
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import chan.content.Chan.Companion.get
import chan.content.ChanConfiguration
import chan.content.ChanConfiguration.Archivation
import chan.content.ChanLocator.NavigationData
import chan.content.ChanManager
import chan.util.CommonUtils.equals
import chan.util.StringUtils.copyToClipboard
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.Preferences.getPassword
import com.mishiranu.dashchan.content.async.ReadSinglePostTask
import com.mishiranu.dashchan.content.async.SendLocalArchiveTask
import com.mishiranu.dashchan.content.async.SendLocalArchiveTask.DownloadResult
import com.mishiranu.dashchan.content.async.SendMultifunctionalTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.service.AudioPlayerService.Companion.start
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay.NavigatePostMode
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit.StackInstance.AttachmentDialog
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.DemandSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.posting.Replyable
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.NavigationUtils.isOpenableVideoExtension
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getColorStateList
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.isTablet
import com.mishiranu.dashchan.util.ResourceUtils.isTabletLarge
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.applyMonospaceTypeface
import com.mishiranu.dashchan.util.ViewUtils.drawSystemInsetsOver
import com.mishiranu.dashchan.util.ViewUtils.makeRoundedCorners
import com.mishiranu.dashchan.util.ViewUtils.setTextSizeScaled
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.AttachmentView
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.CommentTextView
import com.mishiranu.dashchan.widget.CommentTextView.LinkListener
import com.mishiranu.dashchan.widget.CommentTextView.RecyclerKeeper
import com.mishiranu.dashchan.widget.DialogStack
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.InsetsLayout
import com.mishiranu.dashchan.widget.InsetsLayout.Companion.isTargetGesture29
import com.mishiranu.dashchan.widget.ListPosition
import com.mishiranu.dashchan.widget.ListPosition.Companion.obtain
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.PostsLayoutManager
import com.mishiranu.dashchan.widget.ProgressDialog
import com.mishiranu.dashchan.widget.SafePasteEditText
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.applyStyle
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.markDecorAsDialog
import com.mishiranu.dashchan.widget.ViewFactory.createProgressLayout

class DialogUnit internal constructor(private val uiManager: UiManager) {
    class StackInstance internal constructor(internal val dialogStack: DialogStack<DialogFactory?>) {
        internal class AttachmentDialog(
            val attachmentItems: MutableList<AttachmentItem>, val startImageIndex: Int,
            val navigatePostMode: NavigatePostMode?, val gallerySet: GalleryItem.Set
        )

        class State internal constructor(
            internal val factories: MutableList<DialogProvider.Factory<*>>,
            internal val attachmentDialog: AttachmentDialog?,
            internal val postContextMenu: PostNumber?
        ) {
            fun dropState() {
                for (factory in factories) {
                    factory.release()
                }
            }
        }

        internal var attachmentDialog: Pair<AttachmentDialog?, Dialog?>? = null
        internal var postContextMenu: Pair<PostNumber?, Dialog?>? = null

        fun collectState(): State {
            val factories = ArrayList<DialogProvider.Factory<*>>()
            for (pair in dialogStack) {
                if (pair!!.second != null) {
                    pair.first!!.delegate.saveState(pair.second!!)
                }
                factories.add(pair.first!!.delegate.factory)
                pair.first!!.delegate.factory.use()
            }
            return State(
                factories, if (attachmentDialog != null) attachmentDialog!!.first else null,
                if (postContextMenu != null) postContextMenu!!.first else null
            )
        }
    }

    fun createStackInstance(): StackInstance {
        return StackInstance(DialogStack<DialogFactory?>(uiManager.context!!))
    }

    internal class DialogFactory(
        factory: DialogProvider.Factory<*>,
        uiManager: UiManager, configurationSet: ConfigurationSet?
    ) : DialogStack.ViewFactory<DialogFactory?> {
        val delegate: TypedDialogFactory<*>

        init {
            @Suppress("UNCHECKED_CAST")
            delegate = TypedDialogFactory(factory as DialogProvider.Factory<Any?>, uiManager, configurationSet)
        }

        override fun createView(dialogStack: DialogStack<DialogFactory?>): View {
            return delegate.createView(dialogStack)
        }

        override fun destroyView(view: View, remove: Boolean) {
            delegate.destroyView(view, remove)
        }

        override fun isScrolledToTop(view: View): Boolean {
            return delegate.isScrolledToTop(view)
        }

        override fun isScrolledToBottom(view: View): Boolean {
            return delegate.isScrolledToBottom(view)
        }
    }

    internal class TypedDialogFactory<T>(
        factory: DialogProvider.Factory<T>,
        uiManager: UiManager, configurationSet: ConfigurationSet?
    ) {
        internal val provider: DialogProvider<T>
        internal val factory: DialogProvider.Factory<T>

        init {
            factory.use()
            this.provider = factory.create(uiManager, configurationSet!!)
            this.factory = factory
        }

        fun createView(dialogStack: DialogStack<DialogFactory?>): View {
            val context = provider.uiManager.context!!
            val density = obtainDensity(context)
            val content = FrameLayout(context)
            content.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val recyclerView = PaddedRecyclerView(context)
            content.addView(
                recyclerView, FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            recyclerView.setMotionEventSplittingEnabled(false)
            recyclerView.setVerticalScrollBarEnabled(true)
            recyclerView.setClipToPadding(false)
            val progress = createProgressLayout(content)
            progress.setPadding(0, (60f * density).toInt(), 0, (60f * density).toInt())
            val dividerPadding = (12f * density).toInt()
            recyclerView.setLayoutManager(PostsLayoutManager(recyclerView.getContext()))
            provider.uiManager.view().bindThreadsPostRecyclerView(recyclerView)
            val adapter = DialogPostsAdapter(provider.uiManager, provider, recyclerView)
            recyclerView.setAdapter(adapter)
            recyclerView.addItemDecoration(
                DividerItemDecoration(
                    recyclerView.getContext(),
                    DividerItemDecoration.Callback { c: DividerItemDecoration.Configuration?, position: Int ->
                        c!!.need(
                            true
                        ).horizontal(dividerPadding, dividerPadding)
                    })
            )
            val holder = DialogHolder(adapter, provider, recyclerView, progress)
            provider.uiManager.observable().register(holder)
            content.setTag(holder)
            if (factory.listPosition != null) {
                factory.listPosition!!.apply(recyclerView)
                factory.listPosition = null
            }
            provider.setStateListener(StateListener { state: State? ->
                when (state) {
                    State.LIST -> {
                        holder.setShowLoading(false)
                        holder.requestUpdate()
                        return@StateListener true
                    }

                    State.LOADING -> {
                        holder.setShowLoading(true)
                        return@StateListener true
                    }

                    State.ERROR -> {
                        if (!holder.cancelled) {
                            dialogStack.pop()
                            return@StateListener true
                        }
                        return@StateListener false
                    }

                    else -> {}
                }
                false
            })
            return content
        }

        fun destroyView(view: View, remove: Boolean) {
            saveState(view)
            if (remove) {
                factory.release()
            }
            val holder = view.getTag() as DialogHolder<*>
            provider.uiManager.observable().unregister(holder)
            holder.cancel()
        }

        fun saveState(view: View) {
            val holder = view.getTag() as DialogHolder<*>
            val listPosition = obtain(holder.recyclerView, null)
            if (listPosition != null) {
                factory.listPosition = listPosition
            }
        }

        fun isScrolledToTop(view: View): Boolean {
            val holder = view.getTag() as DialogHolder<*>
            if (holder.recyclerView.getVisibility() == View.VISIBLE) {
                return holder.recyclerView.computeVerticalScrollOffset() == 0
            }
            return true
        }

        fun isScrolledToBottom(view: View): Boolean {
            val holder = view.getTag() as DialogHolder<*>
            if (holder.recyclerView.getVisibility() == View.VISIBLE) {
                return holder.recyclerView.computeVerticalScrollOffset() +
                        holder.recyclerView.computeVerticalScrollExtent() >=
                        holder.recyclerView.computeVerticalScrollRange()
            }
            return true
        }
    }

    private class DialogHolder<T>(
        val adapter: DialogPostsAdapter<T>, val dialogProvider: DialogProvider<T>,
        val recyclerView: RecyclerView, val progress: View
    ) : UiManager.Observer {
        var cancelled: Boolean = false

        private var postNotifyDataSetChanged: Runnable? = null

        override fun onPostItemMessage(postItem: PostItem, message: UiManager.Message) {
            dialogProvider.onPostItemMessage(postItem, message)
            when (message) {
                UiManager.Message.POST_INVALIDATE_ALL_VIEWS -> {
                    var notify = adapter.postItems.contains(postItem)
                    if (!notify) {
                        // Must notify adapter to update links to shown/hidden posts
                        for (referenceFrom in postItem.getReferencesFrom()) {
                            if (adapter.postNumbers.contains(referenceFrom)) {
                                notify = true
                                break
                            }
                        }
                    }
                    if (notify) {
                        if (postNotifyDataSetChanged == null) {
                            postNotifyDataSetChanged = Runnable { adapter.notifyDataSetChanged() }
                        }
                        recyclerView.removeCallbacks(postNotifyDataSetChanged)
                        recyclerView.post(postNotifyDataSetChanged)
                    }
                }

                UiManager.Message.INVALIDATE_COMMENT_VIEW -> {
                    val position = adapter.postItems.indexOf(postItem)
                    if (position >= 0) {
                        adapter.invalidateComment(position)
                    }
                }

                else -> {}
            }
        }

        override fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {
            dialogProvider.onReloadAttachmentItem(attachmentItem)
            adapter.reloadAttachment(attachmentItem)
        }

        fun requestUpdate() {
            if (cancelled) {
                return
            }
            dialogProvider.onRequestUpdate()
            adapter.notifyDataSetChanged()
        }

        fun cancel() {
            if (cancelled) {
                return
            }
            dialogProvider.onCancel()
            cancelled = true
        }

        fun notifyDataSetChanged() {
            if (cancelled) {
                return
            }
            adapter.notifyDataSetChanged()
        }

        fun setShowLoading(loading: Boolean) {
            if (cancelled) {
                return
            }
            if (loading) {
                progress.setVisibility(View.VISIBLE)
                recyclerView.setVisibility(View.GONE)
            } else {
                recyclerView.setVisibility(View.VISIBLE)
                if (progress.getVisibility() == View.VISIBLE) {
                    val alphaAnimator = ObjectAnimator.ofFloat<View?>(progress, View.ALPHA, 1f, 0f)
                    alphaAnimator.setDuration(200)
                    alphaAnimator.addListener(
                        AnimationUtils.VisibilityListener(
                            progress,
                            View.GONE
                        )
                    )
                    alphaAnimator.start()
                    recyclerView.setAlpha(0f)
                    recyclerView.animate().alpha(1f).setStartDelay(200).setDuration(200).start()
                }
            }
        }
    }

    internal enum class State {
        LIST, LOADING, ERROR
    }

    internal fun interface StateListener {
        fun onStateChanged(state: State?): Boolean
    }

    internal abstract class DialogProvider<T>(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<T>
    ) : UiManager.Observer, Iterable<PostItem>, ClickCallback<PostItem?, RecyclerView.ViewHolder> {
        fun interface ConfigurationSetProvider<T> {
            fun create(dialogProvider: T): ConfigurationSet
        }

        abstract class Factory<T> {
            var listPosition: ListPosition? = null
            var useCount: Int = 0

            abstract fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): DialogProvider<T>

            open fun destroy() {}

            fun use() {
                useCount++
            }

            fun release() {
                useCount--
                if (useCount == 0) {
                    destroy()
                }
            }
        }

        val uiManager: UiManager
        val configurationSet: ConfigurationSet

        protected abstract fun getThis(): T

        open fun onRequestUpdateDemandSet(demandSet: DemandSet, index: Int) {}

        open fun onRequestUpdate() {}

        open fun onCancel() {}

        protected var stateListener: StateListener? = null

        private var queuedState: State? = null
        private var queuedChangeCallback: Runnable? = null

        init {
            val self = getThis()
            check(this === self)
            this.uiManager = uiManager
            this.configurationSet = configurationSetProvider.create(self)
        }

        fun setStateListener(listener: StateListener?) {
            stateListener = listener
            if (queuedState != null) {
                invokeStateChanged(queuedState, queuedChangeCallback)
                queuedState = null
                queuedChangeCallback = null
            }
        }

        protected fun switchState(state: State?, changeCallback: Runnable?) {
            if (stateListener != null) {
                invokeStateChanged(state, changeCallback)
            } else {
                queuedState = state
                queuedChangeCallback = changeCallback
            }
        }

        fun invokeStateChanged(state: State?, changeCallback: Runnable?) {
            val success = stateListener!!.onStateChanged(state)
            if (success && changeCallback != null) {
                changeCallback.run()
            }
        }

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            postItem: PostItem?,
            longClick: Boolean
        ): Boolean {
            if (longClick) {
                uiManager.interaction().handlePostContextMenu(configurationSet, postItem!!)
            } else {
                uiManager.interaction().handlePostClick(
                    holder.itemView,
                    configurationSet.postStateProvider!!, postItem!!, this
                )
            }
            return true
        }
    }

    private class SingleDialogProvider(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<SingleDialogProvider>,
        internal val postItem: PostItem
    ) : DialogProvider<SingleDialogProvider>(uiManager, configurationSetProvider) {
        class Factory internal constructor(internal val postItem: PostItem) :
            DialogProvider.Factory<SingleDialogProvider>() {
            override fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): SingleDialogProvider {
                return SingleDialogProvider(
                    uiManager,
                    ConfigurationSetProvider { dialogProvider: SingleDialogProvider ->
                        configurationSet
                            .copy(dialogProvider, false, true, null)
                    },
                    postItem
                )
            }
        }

        override fun getThis(): SingleDialogProvider {
            return this
        }

        override fun iterator(): Iterator<PostItem> {
            return mutableListOf(postItem).iterator()
        }
    }

    private class ThreadDialogProvider(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<ThreadDialogProvider>,
        postItem: PostItem, gallerySet: GalleryItem.Set
    ) : DialogProvider<ThreadDialogProvider>(uiManager, configurationSetProvider), LinkListener,
        UiManager.PostsProvider {
        class Factory(val postItem: PostItem) : DialogProvider.Factory<ThreadDialogProvider>() {
            override fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): ThreadDialogProvider {
                val chanName = configurationSet.chanName
                val replyable: Replyable = Replyable { click: Boolean, data: Array<out ReplyData> ->
                    val chan = get(chanName)
                    val board = chan.configuration.safe().obtainBoard(postItem.getBoardName())
                    if (click && board.allowPosting) {
                        uiManager.navigator()!!.navigatePosting(
                            chanName,
                            postItem.getBoardName(), postItem.getThreadNumber(), *data
                        )
                    }
                    board.allowPosting
                }
                val gallerySet = GalleryItem.Set(false)
                return ThreadDialogProvider(
                    uiManager,
                    ConfigurationSetProvider { dialogProvider: ThreadDialogProvider ->
                        ConfigurationSet(
                            configurationSet.chanName,
                            replyable,
                            dialogProvider,
                            PostStateProvider.Companion.DEFAULT,
                            gallerySet,
                            configurationSet.fragmentManager,
                            configurationSet.stackInstance,
                            dialogProvider,
                            dialogProvider,
                            false,
                            true,
                            false,
                            false,
                            false,
                            null
                        )
                    },
                    postItem,
                    gallerySet
                )
            }
        }

        private val postItems = ArrayList<PostItem>()

        init {
            if (!postItem.isThreadItem()) {
                throw RuntimeException("Not thread item")
            }
            postItem.setOrdinalIndex(0)
            postItem.clearReferencesFrom()
            postItems.add(postItem)
            val childPostItems: List<PostItem> =
                postItem.getThreadPosts(get(configurationSet.chanName))
            if (!childPostItems.isEmpty()) {
                for (i in childPostItems.indices) {
                    val childPostItem = childPostItems.get(i)
                    postItems.add(childPostItem)
                    for (postNumber in childPostItem.getReferencesTo()) {
                        for (j in 0..<i + 1) {
                            val foundPostItem = postItems.get(j)
                            if (postNumber.equals(foundPostItem.getPostNumber())) {
                                foundPostItem.addReferenceFrom(childPostItem.getPostNumber())
                            }
                        }
                    }
                }
            }
            gallerySet.setThreadTitle(this.postItems.get(0).getSubjectOrComment())
            for (childPostItem in postItems) {
                gallerySet.put(childPostItem.getPostNumber(), childPostItem.getAttachmentItems())
            }
        }

        override fun getThis(): ThreadDialogProvider {
            return this
        }

        override fun findPostItem(postNumber: PostNumber?): PostItem? {
            for (postItem in postItems) {
                if (postItem.getPostNumber() == postNumber) {
                    return postItem
                }
            }
            return null
        }

        override fun iterator(): MutableIterator<PostItem> {
            return postItems.iterator()
        }

        override fun onRequestUpdateDemandSet(demandSet: DemandSet, index: Int) {
            demandSet.showOpenThreadButton = index == 0
        }

        override fun onLinkClick(view: CommentTextView, uri: Uri, extra: LinkListener.Extra, confirmed: Boolean) {
            val originalPostItem = postItems.get(0)
            val boardName = originalPostItem.getBoardName()
            val threadNumber = originalPostItem.getThreadNumber()
            val chan = get(configurationSet.chanName)
            if (extra.chanName != null && chan.locator.safe(false).isThreadUri(uri)
                && (extra.inBoardLink || equals(
                    boardName,
                    chan.locator.safe(false).getBoardName(uri)
                ))
                && equals(threadNumber, chan.locator.safe(false).getThreadNumber(uri))
            ) {
                val postNumber = chan.locator.safe(false).getPostNumber(uri)
                if (postNumber != null) {
                    for (postItem in postItems) {
                        if (postNumber.equals(postItem.getPostNumber())) {
                            uiManager.dialog().displaySingle(configurationSet, postItem)
                            return
                        }
                    }
                } else {
                    uiManager.dialog().displaySingle(configurationSet, originalPostItem)
                    return
                }
            }
            uiManager.interaction().handleLinkClick(configurationSet, uri, extra, confirmed)
        }

        override fun onLinkLongClick(view: CommentTextView, uri: Uri, extra: LinkListener.Extra) {
            uiManager.interaction().handleLinkLongClick(configurationSet, uri)
        }
    }

    private class RepliesDialogProvider(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<RepliesDialogProvider>,
        internal val postItem: PostItem
    ) : DialogProvider<RepliesDialogProvider>(uiManager, configurationSetProvider) {
        class Factory(internal val postItem: PostItem) :
            DialogProvider.Factory<RepliesDialogProvider>() {
            override fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): RepliesDialogProvider {
                return RepliesDialogProvider(
                    uiManager,
                    ConfigurationSetProvider { dialogProvider: RepliesDialogProvider ->
                        configurationSet
                            .copy(dialogProvider, false, true, postItem.getPostNumber())
                    },
                    postItem
                )
            }
        }

        private val postItems = ArrayList<PostItem>()

        init {
            onRequestUpdate()
        }

        override fun getThis(): RepliesDialogProvider {
            return this
        }

        override fun iterator(): MutableIterator<PostItem> {
            return postItems.iterator()
        }

        override fun onRequestUpdate() {
            super.onRequestUpdate()
            postItems.clear()
            val referencesFrom = postItem.getReferencesFrom()
            if (!referencesFrom.isEmpty()) {
                for (postItem in configurationSet.postsProvider!!) {
                    if (referencesFrom.contains(postItem.getPostNumber())) {
                        postItems.add(postItem)
                    }
                }
            }
        }
    }

    private class ListDialogProvider(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<ListDialogProvider>,
        private val postNumbers: HashSet<PostNumber?>
    ) : DialogProvider<ListDialogProvider>(uiManager, configurationSetProvider) {
        class Factory(postNumbers: MutableCollection<PostNumber?>) :
            DialogProvider.Factory<ListDialogProvider>() {
            private val postNumbers: HashSet<PostNumber?>

            init {
                this.postNumbers = HashSet<PostNumber?>(postNumbers)
            }

            override fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): ListDialogProvider {
                return ListDialogProvider(
                    uiManager,
                    ConfigurationSetProvider { dialogProvider: ListDialogProvider ->
                        configurationSet
                            .copy(dialogProvider, false, true, null)
                    },
                    postNumbers
                )
            }
        }

        private val postItems = ArrayList<PostItem>()

        init {
            onRequestUpdate()
        }

        override fun getThis(): ListDialogProvider {
            return this
        }

        override fun iterator(): MutableIterator<PostItem> {
            return postItems.iterator()
        }

        override fun onRequestUpdate() {
            super.onRequestUpdate()
            postItems.clear()
            for (postItem in configurationSet.postsProvider!!) {
                if (postNumbers.contains(postItem.getPostNumber())) {
                    postItems.add(postItem)
                }
            }
        }
    }

    private class AsyncDialogProvider(
        uiManager: UiManager,
        configurationSetProvider: ConfigurationSetProvider<AsyncDialogProvider>,
        internal val factory: Factory,
        private val chanName: String?,
        private val boardName: String?,
        private val threadNumber: String?,
        private val postNumber: PostNumber?,
        private val gallerySet: GalleryItem.Set
    ) : DialogProvider<AsyncDialogProvider>(uiManager, configurationSetProvider),
        UiManager.PostsProvider {
        class Factory(
            private val chanName: String?,
            private val boardName: String?,
            private val threadNumber: String?,
            private val postNumber: PostNumber?
        ) : DialogProvider.Factory<AsyncDialogProvider>(), ReadSinglePostTask.Callback {
            internal val observable = WeakObservable<Runnable>()

            internal var postItem: PostItem? = null
            internal var errorItem: ErrorItem? = null
            private var readTask: ReadSinglePostTask? = null

            override fun create(
                uiManager: UiManager,
                configurationSet: ConfigurationSet
            ): AsyncDialogProvider {
                val gallerySet = GalleryItem.Set(false)
                if (postItem == null && errorItem == null && readTask == null) {
                    readTask =
                        ReadSinglePostTask(this, get(chanName), boardName, threadNumber, postNumber)
                    readTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                }
                return AsyncDialogProvider(
                    uiManager, ConfigurationSetProvider { dialogProvider: AsyncDialogProvider ->
                        ConfigurationSet(
                            configurationSet.chanName,
                            null,
                            dialogProvider,
                            PostStateProvider.Companion.DEFAULT,
                            gallerySet,
                            configurationSet.fragmentManager,
                            configurationSet.stackInstance,
                            null,
                            dialogProvider,
                            false,
                            true,
                            false,
                            false,
                            false,
                            null
                        )
                    }, this,
                    chanName, boardName, threadNumber, postNumber, gallerySet
                )
            }

            private fun notifyObservers() {
                // Error will cause observer to unregister
                var observers: ArrayList<Runnable>? = null
                for (runnable in observable) {
                    if (observers == null) {
                        observers = ArrayList<Runnable>(1)
                    }
                    observers.add(runnable)
                }
                if (observers != null) {
                    for (runnable in observers) {
                        runnable.run()
                    }
                }
            }

            override fun onReadSinglePostSuccess(postItem: PostItem) {
                readTask = null
                this.postItem = postItem
                notifyObservers()
            }

            override fun onReadSinglePostFail(errorItem: ErrorItem) {
                readTask = null
                this.errorItem = errorItem
                notifyObservers()
            }

            override fun destroy() {
                if (readTask != null) {
                    readTask!!.cancel()
                    readTask = null
                }
            }
        }

        override fun getThis(): AsyncDialogProvider {
            return this
        }

        override fun findPostItem(postNumber: PostNumber?): PostItem? {
            return if (factory.postItem != null && factory.postItem!!.getPostNumber()
                    .equals(postNumber)
            )
                factory.postItem
            else
                null
        }

        override fun iterator(): MutableIterator<PostItem> {
            val list: MutableList<PostItem>
            if (factory.postItem != null) {
                list = mutableListOf(factory.postItem!!)
            } else {
                list = mutableListOf()
            }
            return list.iterator()
        }

        override fun onRequestUpdateDemandSet(demandSet: DemandSet, index: Int) {
            demandSet.showOpenThreadButton = true
        }

        override fun onCancel() {
            ConcurrentUtils.HANDLER.removeCallbacks(updateErrorItem)
            factory.observable.unregister(takeResult)
        }

        fun updatePostItem() {
            val postItem = factory.postItem
            val attachmentItems = postItem!!.getAttachmentItems()
            if (attachmentItems != null) {
                if (postItem.isOriginalPost()) {
                    gallerySet.setThreadTitle(postItem.getSubjectOrComment())
                }
                gallerySet.put(postItem.getPostNumber(), attachmentItems)
            }
        }

        fun updateErrorItem() {
            val errorItem = factory.errorItem
            switchState(State.ERROR, Runnable {
                show(
                    errorItem.toString(), null,
                    ClickableToast.Button(R.string.open_thread, false, Runnable {
                        uiManager.navigator()!!.navigatePosts(chanName, boardName, threadNumber, postNumber, null)
                    })
                )
            })
        }

        fun takeResult() {
            if (factory.postItem != null) {
                updatePostItem()
                switchState(State.LIST, null)
            } else {
                updateErrorItem()
            }
        }

        private val updateErrorItem = Runnable { this.updateErrorItem() }
        private val takeResult = Runnable { this.takeResult() }

        init {
            if (factory.postItem != null) {
                updatePostItem()
            } else if (factory.errorItem != null) {
                ConcurrentUtils.HANDLER.post(updateErrorItem)
            } else {
                switchState(State.LOADING, null)
                factory.observable.register(takeResult)
            }
        }
    }

    fun notifyDataSetChangedToAll(stackInstance: StackInstance) {
        for (view in stackInstance.dialogStack.getVisibleViews()) {
            val holder = view!!.getTag() as DialogHolder<*>
            holder.notifyDataSetChanged()
        }
    }

    fun updateAdapters(stackInstance: StackInstance) {
        for (view in stackInstance.dialogStack.getVisibleViews()) {
            val holder = view!!.getTag() as DialogHolder<*>
            holder.requestUpdate()
        }
    }

    fun closeDialogs(stackInstance: StackInstance) {
        if (stackInstance.postContextMenu != null) {
            stackInstance.postContextMenu!!.second!!.dismiss()
            stackInstance.postContextMenu = null
        }
        if (stackInstance.attachmentDialog != null) {
            stackInstance.attachmentDialog!!.second!!.dismiss()
            stackInstance.attachmentDialog = null
        }
        stackInstance.dialogStack.clear()
    }

    fun displaySingle(configurationSet: ConfigurationSet, postItem: PostItem?) {
        display(configurationSet, SingleDialogProvider.Factory(postItem!!))
    }

    fun displayThread(configurationSet: ConfigurationSet, postItem: PostItem) {
        display(configurationSet, ThreadDialogProvider.Factory(postItem))
    }

    fun displayReplies(configurationSet: ConfigurationSet, postItem: PostItem) {
        display(configurationSet, RepliesDialogProvider.Factory(postItem))
    }

    fun displayList(
        configurationSet: ConfigurationSet,
        postNumbers: MutableCollection<PostNumber?>
    ) {
        display(configurationSet, ListDialogProvider.Factory(postNumbers))
    }

    fun displayReplyAsync(
        configurationSet: ConfigurationSet,
        chanName: String?, boardName: String?, threadNumber: String?, postNumber: PostNumber?
    ) {
        display(
            configurationSet,
            AsyncDialogProvider.Factory(chanName, boardName, threadNumber, postNumber)
        )
    }

    private fun display(configurationSet: ConfigurationSet, factory: DialogProvider.Factory<*>) {
        configurationSet.stackInstance!!.dialogStack.push(
            DialogFactory(
                factory,
                uiManager,
                configurationSet
            )
        )
        uiManager.callback()!!.onDialogStackOpen()
    }

    fun restoreState(configurationSet: ConfigurationSet, state: StackInstance.State) {
        var configurationSet = configurationSet
        if (!state.factories.isEmpty()) {
            val dialogFactories: ArrayList<DialogFactory?> = ArrayList<DialogFactory?>()
            for (factory in state.factories) {
                val dialogFactory = DialogFactory(factory, uiManager, configurationSet)
                configurationSet = dialogFactory.delegate.provider.configurationSet
                dialogFactories.add(dialogFactory)
            }
            configurationSet.stackInstance!!.dialogStack.addAll(dialogFactories)
            uiManager.callback()!!.onDialogStackOpen()
        }
        if (state.attachmentDialog != null) {
            showAttachmentsGrid(
                configurationSet,
                state.attachmentDialog.attachmentItems, state.attachmentDialog.startImageIndex,
                state.attachmentDialog.navigatePostMode, state.attachmentDialog.gallerySet
            )
        }
        if (state.postContextMenu != null && configurationSet.postsProvider != null) {
            val postItem = configurationSet.postsProvider.findPostItem(state.postContextMenu)
            if (postItem != null) {
                uiManager.interaction().handlePostContextMenu(configurationSet, postItem)
            }
        }
    }

    private class DialogPostsAdapter<T>(
        private val uiManager: UiManager,
        private val dialogProvider: DialogProvider<T>,
        recyclerView: RecyclerView
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder?>() {
        private val demandSet = DemandSet()
        private val updateObserver: AdapterDataObserver
        private val recyclerKeeper: RecyclerKeeper

        val postItems: ArrayList<PostItem> = ArrayList<PostItem>()
        val postNumbers: HashSet<PostNumber?> = HashSet<PostNumber?>()

        init {
            updateObserver = object : AdapterDataObserver() {
                override fun onChanged() {
                    postItems.clear()
                    postNumbers.clear()
                    for (postItem in dialogProvider) {
                        postItems.add(postItem)
                        postNumbers.add(postItem.getPostNumber())
                    }
                }
            }
            recyclerKeeper = RecyclerKeeper(recyclerView)
            super.registerAdapterDataObserver(updateObserver)
            super.registerAdapterDataObserver(recyclerKeeper)
            updateObserver.onChanged()
        }

        override fun registerAdapterDataObserver(observer: AdapterDataObserver) {
            super.registerAdapterDataObserver(observer)

            // Move observer to the end
            super.unregisterAdapterDataObserver(updateObserver)
            super.registerAdapterDataObserver(updateObserver)
            // Move observer to the end
            super.unregisterAdapterDataObserver(recyclerKeeper)
            super.registerAdapterDataObserver(recyclerKeeper)
        }

        override fun getItemCount(): Int {
            return postItems.size
        }

        override fun getItemViewType(position: Int): Int {
            val postItem = getItem(position)
            return (if (dialogProvider.configurationSet.postStateProvider!!.isHiddenResolve(postItem))
                ViewUnit.ViewType.POST_HIDDEN
            else
                ViewUnit.ViewType.POST).ordinal
        }

        fun getItem(position: Int): PostItem {
            return postItems.get(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return uiManager.view().createView(parent, ViewUnit.ViewType.entries[viewType])
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            onBindViewHolder(holder, position, mutableListOf<Any?>())
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder, position: Int,
            payloads: MutableList<Any?>
        ) {
            val postItem = getItem(position)
            when (ViewUnit.ViewType.entries[holder.getItemViewType()]) {
                ViewUnit.ViewType.POST -> {
                    if (payloads.isEmpty()) {
                        dialogProvider.onRequestUpdateDemandSet(demandSet, position)
                        uiManager.view().bindPostView(
                            holder,
                            postItem,
                            dialogProvider.configurationSet,
                            demandSet
                        )
                    } else {
                        if (payloads.contains(PAYLOAD_INVALIDATE_COMMENT)) {
                            uiManager.view().bindPostViewInvalidateComment(holder)
                        }
                        for (`object` in payloads) {
                            if (`object` is AttachmentItem) {
                                uiManager.view().bindPostViewReloadAttachment(holder, `object`)
                            }
                        }
                    }
                }

                ViewUnit.ViewType.POST_HIDDEN -> {
                    uiManager.view()
                        .bindPostHiddenView(holder, postItem, dialogProvider.configurationSet)
                }
            
                else -> {}
            }
        }

        fun invalidateComment(position: Int) {
            notifyItemChanged(position, PAYLOAD_INVALIDATE_COMMENT)
        }

        fun reloadAttachment(attachmentItem: AttachmentItem) {
            for (i in postItems.indices) {
                val postItem = postItems.get(i)
                if (postItem.getPostNumber().equals(attachmentItem.getPostNumber())) {
                    notifyItemChanged(i, attachmentItem)
                    break
                }
            }
        }

        companion object {
            private const val PAYLOAD_INVALIDATE_COMMENT = "invalidateComment"
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun showAttachmentsGrid(
        configurationSet: ConfigurationSet, attachmentItems: MutableList<AttachmentItem>,
        startImageIndex: Int, navigatePostMode: NavigatePostMode?, gallerySet: GalleryItem.Set
    ) {
        val context = uiManager.context
        val dialog = Dialog(context!!, R.style.Theme_Gallery)
        val styledContext = dialog.getContext()
        val attachmentDialog = Pair<AttachmentDialog?, Dialog?>(
            AttachmentDialog(
                attachmentItems,
                startImageIndex,
                navigatePostMode,
                gallerySet
            ), dialog
        )
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnKeyListener(DialogInterface.OnKeyListener { d: DialogInterface?, keyCode: Int, event: KeyEvent? ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event!!.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) {
                closeDialogs(configurationSet.stackInstance!!)
                return@OnKeyListener true
            }
            false
        })
        val closeListener = View.OnClickListener { v: View? -> dialog.cancel() }
        val inflater = LayoutInflater.from(styledContext)
        val rootView = InsetsLayout(styledContext)
        rootView.setOnClickListener(closeListener)
        val scrollView: ScrollView = object : ScrollView(styledContext) {
            override fun draw(canvas: Canvas) {
                super.draw(canvas)
                drawSystemInsetsOver(this, canvas, isTargetGesture29(this))
            }
        }
        scrollView.setWillNotDraw(false)

        scrollView.setVerticalScrollBarEnabled(false)
        scrollView.setClipToPadding(false)
        rootView.setOnApplyInsetsTarget(scrollView)
        rootView.addView(
            scrollView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )
        val container = LinearLayout(styledContext)
        container.setOrientation(LinearLayout.VERTICAL)
        container.setMotionEventSplittingEnabled(false)
        container.setOnClickListener(closeListener)
        scrollView.addView(
            container,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val clickListener = View.OnClickListener { v: View? ->
            val index = v!!.getTag() as Int
            var imageIndex = startImageIndex
            for (i in 0..<index) {
                if (attachmentItems.get(i).isShowInGallery()) {
                    imageIndex++
                }
            }
            openAttachment(
                v, configurationSet.chanName,
                attachmentItems, index, imageIndex, navigatePostMode, gallerySet
            )
        }
        val chan = get(configurationSet.chanName)
        val configuration = context!!.getResources().getConfiguration()
        val tablet = isTablet(configuration)
        val tabletLarge = isTabletLarge(configuration)
        val density = obtainDensity(context)
        var total = 0
        var linearLayout: LinearLayout? = null
        val padding = (8f * density).toInt()
        val size = ((if (tabletLarge) 180f else if (tablet) 160f else 120f) * density).toInt()
        val columns = if (tablet) 3 else 2
        val attachmentViews = HashMap<AttachmentItem?, AttachmentView?>()
        for (i in attachmentItems.indices) {
            val column = total++ % columns
            if (column == 0) {
                val first = linearLayout == null
                linearLayout = LinearLayout(styledContext)
                linearLayout.setOrientation(LinearLayout.HORIZONTAL)
                linearLayout.setMotionEventSplittingEnabled(false)
                container.addView(
                    linearLayout, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (first) {
                    linearLayout.setPadding(0, padding, 0, padding)
                } else {
                    linearLayout.setPadding(0, 0, 0, padding)
                }
            }
            val attachmentItem = attachmentItems.get(i)
            @SuppressLint("InflateParams") val view =
                inflater.inflate(R.layout.list_item_attachment, null)
            makeRoundedCorners(view, (2f * density + 0.5f).toInt(), true)
            val attachmentView = view.findViewById<AttachmentView>(R.id.thumbnail)
            val textView = view.findViewById<TextView>(R.id.attachment_info)
            textView.setBackgroundColor(-0x33ddddde)
            textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

            attachmentItem.configureAndLoad(attachmentView, chan, false, true)
            textView.setText(attachmentItem.getDescription(AttachmentItem.FormatMode.TWO_LINES))
            val clickView = view.findViewById<View>(R.id.attachment_click)
            clickView.setOnClickListener(clickListener)
            clickView.setOnLongClickListener(OnLongClickListener { v: View? ->
                uiManager.interaction().showThumbnailLongClickDialog(
                    configurationSet,
                    attachmentItem, attachmentView, gallerySet.getThreadTitle()
                )
                true
            })
            clickView.setTag(i)
            var totalSize = size
            if (column == 0) {
                view.setPadding(padding, 0, padding, 0)
                totalSize += 2 * padding
            } else {
                view.setPadding(0, 0, padding, 0)
                totalSize += padding
            }
            linearLayout!!.addView(view, totalSize, size)
            attachmentViews.put(attachmentItem, attachmentView)
        }
        dialog.setContentView(rootView)
        val window = dialog.getWindow()
        val layoutParams = window!!.getAttributes()
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val attrs =
            intArrayOf(android.R.attr.windowAnimationStyle, android.R.attr.backgroundDimAmount)
        val typedArray =
            styledContext.obtainStyledAttributes(null, attrs, android.R.attr.dialogTheme, 0)
        try {
            layoutParams.windowAnimations = typedArray.getResourceId(0, 0)
            layoutParams.dimAmount = typedArray.getFloat(1, 0.6f)
        } finally {
            typedArray.recycle()
        }
        ViewUtils.setWindowLayoutFullscreen(window)

        markDecorAsDialog(window.getDecorView())
        val observer: UiManager.Observer = object : UiManager.Observer {
            override fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {
                val attachmentView = attachmentViews.get(attachmentItem)
                if (attachmentView != null) {
                    attachmentItem.configureAndLoad(
                        attachmentView,
                        get(configurationSet.chanName),
                        false,
                        true
                    )
                }
            }
        }
        if (configurationSet.stackInstance!!.attachmentDialog != null) {
            configurationSet.stackInstance!!.attachmentDialog!!.second!!.dismiss()
            configurationSet.stackInstance!!.attachmentDialog = null
        }
        configurationSet.stackInstance!!.attachmentDialog = attachmentDialog
        uiManager.observable().register(observer)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { dialogInterface: DialogInterface? ->
            if (configurationSet.stackInstance!!.attachmentDialog === attachmentDialog) {
                configurationSet.stackInstance!!.attachmentDialog = null
            }
            uiManager.observable().unregister(observer)
        })
        dialog.show()
    }

    fun openAttachmentOrDialog(
        configurationSet: ConfigurationSet, imageView: View?,
        attachmentItems: MutableList<AttachmentItem>, imageIndex: Int,
        navigatePostMode: NavigatePostMode?, gallerySet: GalleryItem.Set
    ) {
        if (attachmentItems.size > 1) {
            showAttachmentsGrid(
                configurationSet,
                attachmentItems,
                imageIndex,
                navigatePostMode,
                gallerySet
            )
        } else {
            openAttachment(
                imageView, configurationSet.chanName,
                attachmentItems, 0, imageIndex, navigatePostMode, gallerySet
            )
        }
    }

    fun openAttachment(
        imageView: View?,
        chanName: String?,
        attachmentItems: MutableList<AttachmentItem>,
        index: Int,
        imageIndex: Int,
        navigatePostMode: NavigatePostMode?,
        gallerySet: GalleryItem.Set?
    ) {
        val context = uiManager.context
        val attachmentItem = attachmentItems.get(index)
        val canDownload = attachmentItem.canDownloadToStorage()
        val chan = get(chanName)
        val uri = attachmentItem.getFileUri(chan)
        val type = attachmentItem.getType()
        if (canDownload && type == AttachmentItem.Type.AUDIO) {
            start(context!!, chanName, uri, attachmentItem.getFileName(chan))
        } else if (canDownload && (type == AttachmentItem.Type.IMAGE || type == AttachmentItem.Type.VIDEO &&
                    isOpenableVideoExtension(attachmentItem.getExtension()))
        ) {
            uiManager.navigator()!!.navigateGallery(
                chanName, gallerySet!!, imageIndex,
                imageView, navigatePostMode, false
            )
        } else {
            NavigationUtils.handleUri(
                context!!,
                chanName,
                uri!!,
                NavigationUtils.BrowserType.EXTERNAL
            )
        }
    }

    fun handlePostContextMenu(
        configurationSet: ConfigurationSet, postNumber: PostNumber?,
        show: Boolean, dialog: AlertDialog?
    ) {
        if (show) {
            if (configurationSet.stackInstance!!.postContextMenu != null) {
                configurationSet.stackInstance!!.postContextMenu!!.second!!.dismiss()
            }
            configurationSet.stackInstance!!.postContextMenu =
                Pair<PostNumber?, Dialog?>(postNumber, dialog)
        } else {
            if (configurationSet.stackInstance!!.postContextMenu != null &&
                configurationSet.stackInstance!!.postContextMenu!!.second === dialog
            ) {
                configurationSet.stackInstance!!.postContextMenu = null
            }
        }
    }

    class IconData {
        internal val title: String?
        internal val attrId: Int
        internal val uri: Uri?

        constructor(title: String?, attrId: Int) {
            this.title = title
            this.attrId = attrId
            this.uri = null
        }

        constructor(title: String?, uri: Uri?) {
            this.title = title
            this.attrId = 0
            this.uri = uri
        }
    }

    fun showPostDescriptionDialog(
        fragmentManager: FragmentManager,
        icons: MutableCollection<IconData>, chanName: String?, emailToCopy: String?
    ) {
        showPostDescriptionDialogStatic(fragmentManager, icons, chanName, emailToCopy)
    }

    fun performSendDeletePosts(
        fragmentManager: FragmentManager,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumbers: List<PostNumber>?
    ) {
        val chan = get(chanName)
        val deleting = chan.configuration.safe().obtainDeleting(boardName)
        if (deleting == null) {
            return
        }
        val context = uiManager.context
        var options: ArrayList<Pair<String, String>>? = null
        if (deleting.optionFilesOnly) {
            options = ArrayList<Pair<String, String>>()
            options.add(
                Pair(
                    SendMultifunctionalTask.OPTION_FILES_ONLY,
                    context!!.getString(R.string.files_only)
                )
            )
        }
        val state = SendMultifunctionalTask.State(
            SendMultifunctionalTask
                .Operation.DELETE,
            chanName,
            boardName,
            threadNumber,
            null,
            options,
            deleting.password
        )
        state.postNumbers = postNumbers
        Companion.showPerformSendDialog(
            fragmentManager,
            state,
            null,
            getPassword(chan),
            null,
            null,
            true
        )
    }

    fun performSendReportPosts(
        fragmentManager: FragmentManager,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumbers: List<PostNumber>?
    ) {
        val chan = get(chanName)
        val reporting = chan.configuration.safe().obtainReporting(boardName)
        if (reporting == null) {
            return
        }
        val state = SendMultifunctionalTask.State(
            SendMultifunctionalTask
                .Operation.REPORT, chanName, boardName, threadNumber, reporting.types,
            reporting.options, reporting.comment
        )
        state.postNumbers = postNumbers
        Companion.showPerformSendDialog(fragmentManager, state, null, null, null, null, true)
    }

    fun performSendArchiveThread(
        fragmentManager: FragmentManager,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        threadTitle: String?,
        posts: Collection<Post>?
    ) {
        performSendArchiveThread(
            uiManager.context!!, fragmentManager,
            chanName, boardName, threadNumber, threadTitle, posts
        )
    }

    fun performSendVotePost(
        fragmentManager: FragmentManager,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        isLike: Boolean
    ) {
        val chan = get(chanName)
        val voting = chan.configuration.safe().obtainVoting(boardName)
        if (voting == null) {
            return
        }
        val state = SendMultifunctionalTask.State(
            SendMultifunctionalTask
                .Operation.VOTE, chanName, boardName, threadNumber, null, null, false
        )
        state.postNumbers = listOf(postNumber!!)
        state.like = isLike
        state.dislike = !isLike
        startMultifunctionalProcess(fragmentManager, state, null, null, null)
    }

    class MultifunctionalViewModel :
        TaskViewModel.Proxy<SendMultifunctionalTask, SendMultifunctionalTask.Callback?>()

    class LocalArchiveViewModel : TaskViewModel<SendLocalArchiveTask, DownloadResult?>(),
        SendLocalArchiveTask.Callback {
        val progress: MutableLiveData<Int?> = MutableLiveData<Int?>()

        override fun onLocalArchivationProgressUpdate(handledPostsCount: Int) {
            progress.setValue(handledPostsCount)
        }

        override fun onLocalArchivationComplete(result: DownloadResult?) {
            handleResult(if (result != null) result else DOWNLOAD_RESULT_ERROR)
        }
    }

    companion object {
        private fun showPostDescriptionDialogStatic(
            fragmentManager: FragmentManager,
            icons: MutableCollection<IconData>, chanName: String?, emailToCopy: String?
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    DialogUnit.Companion.createPostDescriptionDialog(
                        provider!!.context,
                        icons, chanName, emailToCopy
                    )!!
                })
        }

        private fun createPostDescriptionDialog(
            context: Context,
            icons: MutableCollection<IconData>, chanName: String?, emailToCopy: String?
        ): AlertDialog? {
            val density = obtainDensity(context)
            val imageLoader: ImageLoader = ImageLoader.getInstance()
            val chan = get(chanName)
            val container = LinearLayout(context)
            container.setOrientation(LinearLayout.VERTICAL)
            container.setPadding(0, (12f * density).toInt(), 0, 0)

            for (icon in icons) {
                val linearLayout = LinearLayout(context)
                container.addView(
                    linearLayout, LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                linearLayout.setOrientation(LinearLayout.HORIZONTAL)
                linearLayout.setGravity(Gravity.CENTER_VERTICAL)
                linearLayout.setMinimumHeight((40f * density).toInt())
                linearLayout.setPadding(
                    (18f * density).toInt(),
                    0,
                    (8f * density).toInt(),
                    0
                ) // 18f = 16f + 2f
                val imageView = ImageView(context)
                linearLayout.addView(imageView, (20f * density).toInt(), (20f * density).toInt())
                if (icon.uri != null) {
                    imageLoader.loadImage(chan, icon.uri, false, imageView)
                } else {
                    imageView.setImageResource(getResourceId(context, icon.attrId, 0))
                    imageView.setImageTintList(
                        getColorStateList(
                            imageView.getContext(),
                            android.R.attr.textColorSecondary
                        )
                    )
                }
                val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
                linearLayout.addView(
                    textView,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                textView.setSingleLine(true)
                textView.setText(icon.title)
                textView.setPadding((26f * density).toInt(), 0, 0, 0) // 26f = 24f + 2f
                setTextSizeScaled(textView, 14)
                textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
            }
            val builder = AlertDialog.Builder(context).setPositiveButton(android.R.string.ok, null)
            if (!StringUtils.isEmpty(emailToCopy)) {
                builder.setNeutralButton(
                    R.string.copy_email,
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                        StringUtils.copyToClipboard(
                            context,
                            emailToCopy
                        )
                    })
            }
            builder.setView(container)
            return builder.create()
        }

        private fun performSendArchiveThread(
            context: Context,
            fragmentManager: FragmentManager,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            threadTitle: String?,
            posts: Collection<Post>?
        ) {
            val chan = get(chanName)
            val canArchiveLocal = !chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)
            val archiveChanNames = ChanManager.getInstance().getArchiveChanNames(chanName)
            val state = SendMultifunctionalTask.State(
                SendMultifunctionalTask
                    .Operation.ARCHIVE, chanName, boardName, threadNumber, null, null, false
            )
            state.archiveThreadTitle = threadTitle
            if (canArchiveLocal && archiveChanNames.size > 0 || archiveChanNames.size > 1) {
                InstanceDialog(
                    fragmentManager,
                    null,
                    InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                        val chanNameItems =
                            arrayOfNulls<String>(archiveChanNames.size + (if (canArchiveLocal) 1 else 0))
                        val items =
                            arrayOfNulls<String>(archiveChanNames.size + (if (canArchiveLocal) 1 else 0))
                        if (canArchiveLocal) {
                            items[0] = provider!!.context.getString(R.string.local_archive)
                        }
                        for (i in archiveChanNames.indices) {
                            val archiveChan = get(archiveChanNames.get(i))
                            chanNameItems[if (canArchiveLocal) i + 1 else i] = archiveChan.name
                            items[if (canArchiveLocal) i + 1 else i] =
                                archiveChan.configuration.getTitle()
                        }
                        AlertDialog.Builder(provider!!.context)
                            .setTitle(R.string.archive__verb)
                            .setItems(
                                items,
                                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                    performSendArchiveThreadInternal(
                                        provider.context,
                                        provider.fragmentManager,
                                        state,
                                        chanNameItems[which],
                                        posts
                                    )
                                })
                            .create()
                    })
            } else if (canArchiveLocal) {
                performSendArchiveThreadInternal(context, fragmentManager, state, null, posts)
            }
        }

        const val OPTION_THUMBNAILS: String = "thumbnails"
        const val OPTION_FILES: String = "files"

        private fun performSendArchiveThreadInternal(
            context: Context,
            fragmentManager: FragmentManager,
            state: SendMultifunctionalTask.State,
            archiveChanName: String?,
            posts: Collection<Post>?
        ) {
            val archivation: Archivation?
            if (archiveChanName == null) {
                archivation = Archivation()
                archivation.options.add(
                    Pair(
                        OPTION_THUMBNAILS,
                        context.getString(R.string.save_thumbnails)
                    )
                )
                archivation.options.add(
                    Pair(
                        OPTION_FILES,
                        context.getString(R.string.save_files)
                    )
                )
            } else {
                val archiveChan = get(archiveChanName)
                archivation = archiveChan.configuration.safe().obtainArchivation()
            }
            if (archivation == null) {
                return
            }
            state.archiveChanName = archiveChanName
            state.options = archivation.options
            state.archiveQueryOnly = archivation.queryOnly
            if (state.isArchiveSimpleQueryOnly()) {
                startMultifunctionalProcess(fragmentManager, state, null, null, null)
            } else {
                showPerformSendDialog(fragmentManager, state, null, null, null, posts, true)
            }
        }

        private fun showPerformSendDialog(
            fragmentManager: FragmentManager,
            state: SendMultifunctionalTask.State,
            defaultType: String?,
            defaultText: String?,
            defaultOptions: List<String>?,
            posts: Collection<Post>?,
            firstTime: Boolean
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    Companion.createPerformSendDialog(
                        provider!!,
                        state, defaultType, defaultText, defaultOptions, posts, firstTime
                    )!!
                })
        }

        private fun createPerformSendDialog(
            provider: InstanceDialog.Provider,
            state: SendMultifunctionalTask.State,
            defaultType: String?,
            defaultText: String?,
            defaultOptions: List<String>?,
            posts: Collection<Post>?,
            firstTime: Boolean
        ): Dialog? {
            val context = provider.context
            val radioGroup: RadioGroup?
            if (state.types != null && state.types!!.size > 0) {
                radioGroup = RadioGroup(context)
                radioGroup.setOrientation(RadioGroup.VERTICAL)
                var check = 0
                for (pair in state.types) {
                    val button = RadioButton(context)
                    applyStyle(button)
                    button.setText(pair.second)
                    button.setId(radioGroup.getChildCount())
                    if (equals(pair.first, defaultType)) {
                        check = button.getId()
                    }
                    radioGroup.addView(button)
                }
                radioGroup.check(check)
            } else {
                radioGroup = null
            }

            val editText: EditText?
            if (state.commentField) {
                editText = SafePasteEditText(context)
                editText.setSingleLine(true)
                editText.setText(defaultText)
                if (defaultText != null) {
                    editText.setSelection(defaultText.length)
                }
                if (state.operation == SendMultifunctionalTask.Operation.DELETE) {
                    editText.setHint(R.string.password)
                    editText.setInputType(InputType.TYPE_CLASS_TEXT)
                    applyMonospaceTypeface(editText)
                } else if (state.operation == SendMultifunctionalTask.Operation.REPORT) {
                    editText.setHint(R.string.reason)
                }
            } else {
                editText = null
            }

            val checkBoxGroup: LinearLayout?
            if (state.options != null && state.options!!.size > 0) {
                checkBoxGroup = LinearLayout(context)
                checkBoxGroup.setOrientation(RadioGroup.VERTICAL)
                for (option in state.options) {
                    val checkBox = CheckBox(context)
                    applyStyle(checkBox)
                    checkBox.setText(option.second)
                    checkBox.setTag(option.first)
                    if (defaultOptions != null && defaultOptions.contains(option.first)) {
                        checkBox.setChecked(true)
                    }
                    checkBoxGroup.addView(checkBox)
                }
            } else {
                checkBoxGroup = null
            }

            val density = obtainDensity(context)
            val linearLayout = LinearLayout(context)
            linearLayout.setOrientation(LinearLayout.VERTICAL)
            if (radioGroup != null) {
                linearLayout.addView(radioGroup)
            }
            if (editText != null) {
                linearLayout.addView(editText)
            }
            if (checkBoxGroup != null) {
                linearLayout.addView(checkBoxGroup)
            }
            if (radioGroup != null && editText != null) {
                (editText.getLayoutParams() as LinearLayout.LayoutParams).topMargin =
                    (8f * density).toInt()
            }
            if ((radioGroup != null || editText != null) && checkBoxGroup != null) {
                (checkBoxGroup.getLayoutParams() as LinearLayout.LayoutParams).topMargin =
                    (8f * density).toInt()
            }
            val padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view)
            linearLayout.setPadding(padding, padding, padding, padding)

            val builder = AlertDialog.Builder(context)
            if (linearLayout.getChildCount() > 0) {
                val scrollView = ScrollView(context)
                scrollView.addView(
                    linearLayout, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                var resId = 0
                when (state.operation) {
                    SendMultifunctionalTask.Operation.DELETE -> {
                        resId = R.string.delete
                    }

                    SendMultifunctionalTask.Operation.REPORT -> {
                        resId = R.string.report
                    }

                    SendMultifunctionalTask.Operation.ARCHIVE -> {
                        resId = R.string.archive__verb
                    }

                    else -> {}
                }
                builder.setTitle(resId)
                builder.setView(scrollView)
            } else {
                if (!firstTime) {
                    return provider.createDismissDialog()
                }
                var resId = 0
                when (state.operation) {
                    SendMultifunctionalTask.Operation.DELETE -> {
                        resId = R.string.confirm_deleting__sentence
                    }

                    SendMultifunctionalTask.Operation.REPORT -> {
                        resId = R.string.confirm_reporting__sentence
                    }

                    SendMultifunctionalTask.Operation.ARCHIVE -> {
                        resId = R.string.confirm_archivation__sentence
                    }

                    else -> {}
                }
                builder.setMessage(resId)
            }

            return builder.setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    val type =
                        if (radioGroup != null) state.types!!.get(radioGroup.getCheckedRadioButtonId()).first else null
                    val text = if (editText != null) editText.getText().toString() else null
                    var options: ArrayList<String>? = null
                    if (checkBoxGroup != null) {
                        options = ArrayList<String>()
                        for (i in 0..<checkBoxGroup.getChildCount()) {
                            val checkBox = checkBoxGroup.getChildAt(i) as CheckBox
                            if (checkBox.isChecked()) {
                                options.add(checkBox.getTag()!! as String?)
                            }
                        }
                    }
                    if (state.operation == SendMultifunctionalTask.Operation.ARCHIVE && state.archiveChanName == null) {
                        if (posts!!.isEmpty()) {
                            show(R.string.cache_is_unavailable)
                        } else {
                            startLocalArchiveProcess(
                                provider.fragmentManager,
                                state.chanName,
                                state.boardName,
                                state.threadNumber,
                                posts,
                                options!!.contains(OPTION_THUMBNAILS),
                                options.contains(OPTION_FILES)
                            )
                        }
                    } else {
                        startMultifunctionalProcess(
                            provider.fragmentManager,
                            state,
                            type,
                            text,
                            options
                        )
                    }
                }).setNegativeButton(android.R.string.cancel, null).create()
        }

        private fun startMultifunctionalProcess(
            fragmentManager: FragmentManager,
            state: SendMultifunctionalTask.State,
            type: String?,
            text: String?,
            options: List<String>?
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val dialog = ProgressDialog(context, null)
                    dialog.setMessage(
                        context.getString(
                            if (state.archiveQueryOnly)
                                R.string.loading__ellipsis
                            else
                                R.string.sending__ellipsis
                        )
                    )
                    val viewModel =
                        provider.getViewModel<MultifunctionalViewModel>(MultifunctionalViewModel::class.java)
                    if (!viewModel.hasTaskOrValue()) {
                        val task = SendMultifunctionalTask(
                            viewModel.callback!!,
                            state, type, text, options
                        )
                        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                        viewModel.attach(task)
                    }
                    viewModel.observe(
                        provider.lifecycleOwner,
                        object : SendMultifunctionalTask.Callback {
                            override fun onSendSuccess(
                                archiveBoardName: String?,
                                archiveThreadNumber: String?
                            ) {
                                provider.dismiss()
                                when (state.operation) {
                                    SendMultifunctionalTask.Operation.DELETE, SendMultifunctionalTask.Operation.REPORT, SendMultifunctionalTask.Operation.VOTE -> {
                                        show(R.string.request_has_been_sent_successfully)
                                    }

                                    SendMultifunctionalTask.Operation.ARCHIVE -> {
                                        if (archiveThreadNumber != null) {
                                            val chanName = state.archiveChanName
                                            val navigationData = NavigationData(
                                                NavigationData.Target.POSTS,
                                                archiveBoardName, archiveThreadNumber, null, null
                                            )
                                            val uiManager: UiManager? =
                                                UiManager.Companion.extract(provider)
                                            if (state.archiveQueryOnly) {
                                                uiManager!!.navigator()!!.navigateTargetAllowReturn(
                                                    chanName,
                                                    navigationData
                                                )
                                            } else {
                                                getInstance().add(
                                                    chanName!!,
                                                    archiveBoardName,
                                                    archiveThreadNumber,
                                                    state.archiveThreadTitle,
                                                    false
                                                )
                                                show(
                                                    context.getString(R.string.completed), null,
                                                    ClickableToast.Button(
                                                        R.string.open_thread,
                                                        false,
                                                        Runnable {
                                                            uiManager!!
                                                                .navigator()!!.navigateTargetAllowReturn(
                                                                    chanName,
                                                                    navigationData
                                                                )
                                                        })
                                                )
                                            }
                                        } else if (state.archiveQueryOnly) {
                                            show(R.string.invalid_server_response)
                                        } else {
                                            show(R.string.completed)
                                        }
                                    }
                                }
                            }

                            override fun onSendFail(errorItem: ErrorItem?) {
                                provider.dismiss()
                                show(errorItem)
                                if (!state.isArchiveSimpleQueryOnly()) {
                                    Companion.showPerformSendDialog(
                                        provider.fragmentManager,
                                        state,
                                        type,
                                        text,
                                        options,
                                        null,
                                        false
                                    )
                                }
                            }
                        })
                    dialog
                })
        }

        private val DOWNLOAD_RESULT_ERROR = DownloadResult { binder: DownloadService.Binder? -> }

        private fun startLocalArchiveProcess(
            fragmentManager: FragmentManager,
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            posts: Collection<Post>?,
            saveThumbnails: Boolean,
            saveFiles: Boolean
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val dialog = ProgressDialog(context, "%d / %d")
                    dialog.setMessage(context.getString(R.string.processing_data__ellipsis))
                    dialog.setMax(posts!!.size)
                    val viewModel =
                        provider.getViewModel<LocalArchiveViewModel>(LocalArchiveViewModel::class.java)
                    if (!viewModel.hasTaskOrValue()) {
                        val task = SendLocalArchiveTask(
                            viewModel, get(chanName),
                            boardName, threadNumber, posts!!, saveThumbnails, saveFiles
                        )
                        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                        viewModel.attach(task)
                    }
                    viewModel.observe(
                        provider.lifecycleOwner,
                        Observer { result: DownloadResult? ->
                            provider.dismiss()
                            if (result === DOWNLOAD_RESULT_ERROR) {
                                show(R.string.unknown_error)
                            } else {
                                val uiManager: UiManager? = UiManager.Companion.extract(provider)
                                result!!.run(uiManager!!.callback()!!.downloadBinder!!)
                            }
                        })
                    viewModel.progress.observe(
                        provider.lifecycleOwner,
                        Observer { value: Int? -> dialog.setValue(value!!) })
                    dialog
                })
        }
    }
}
