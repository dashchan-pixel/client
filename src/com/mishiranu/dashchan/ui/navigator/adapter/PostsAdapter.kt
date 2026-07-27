package com.mishiranu.dashchan.ui.navigator.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import chan.content.Chan.Companion.get
import chan.util.CommonUtils.equals
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.HidePerformer
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.DemandSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.navigator.manager.ViewUnit
import com.mishiranu.dashchan.ui.posting.Replyable
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.CommentTextView
import com.mishiranu.dashchan.widget.CommentTextView.LinkListener
import com.mishiranu.dashchan.widget.CommentTextView.RecyclerKeeper
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import java.util.Collections

class PostsAdapter(
    callback: Callback?,
    chanName: String?,
    private val uiManager: UiManager,
    replyable: Replyable?,
    postStateProvider: PostStateProvider,
    fragmentManager: FragmentManager?,
    recyclerView: RecyclerView,
    postItemsMap: MutableMap<PostNumber?, PostItem>,
    hiddenPosts: PostItem.HideState.Map<PostNumber?>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>(),
    LinkListener,
    UiManager.PostsProvider,
    HidePerformer.PostsProvider {
    interface Callback : ClickCallback<PostItem?, RecyclerView.ViewHolder> {
        fun onItemClick(
            view: View?,
            postItem: PostItem?,
        )

        fun onItemLongClick(postItem: PostItem?): Boolean

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            item: PostItem?,
            longClick: Boolean,
        ): Boolean {
            if (longClick) {
                return onItemLongClick(item)
            } else {
                onItemClick(holder.itemView, item)
                return true
            }
        }
    }

    @JvmField
    val configurationSet: ConfigurationSet
    private val demandSet = DemandSet()

    @JvmField
    val gallerySet: GalleryItem.Set = GalleryItem.Set(true)
    private val recyclerKeeper: RecyclerKeeper
    private val recyclerView: RecyclerView

    private val postNumbers = java.util.ArrayList<PostNumber?>()

    /**
     * What the list actually shows. Equal to [postNumbers] in the default view; in the popular view it
     * holds only the posts that have replies, most replied first, so it is neither sorted nor complete.
     * Everything that means "the thread" — the original post, ordinal indexes, the bump limit divider,
     * [iterator] — goes through [postNumbers] instead.
     */
    private val displayNumbers = java.util.ArrayList<PostNumber?>()

    /** Reverse index of [displayNumbers], kept only for the popular view where a binary search can't work. */
    private val displayPositions = HashMap<PostNumber?, Int>()
    private val postItemsMap: MutableMap<PostNumber?, PostItem>
    val hiddenPosts: PostItem.HideState.Map<PostNumber?>
    private val selected = HashSet<PostNumber?>()

    private var bumpLimitOrdinalIndex = PostItem.ORDINAL_INDEX_NONE
    private var selection = false

    /** Whether the list is showing the popular posts instead of the thread. See [setPopularMode]. */
    var isPopularMode: Boolean = false
        private set

    fun createPostItemDecoration(
        context: Context,
        dividerPadding: Int,
    ): ItemDecoration = BumpLimitItemDecorator(context, dividerPadding)

    override fun registerAdapterDataObserver(observer: AdapterDataObserver) {
        super.registerAdapterDataObserver(observer)

        // Move observer to the end
        super.unregisterAdapterDataObserver(recyclerKeeper)
        super.registerAdapterDataObserver(recyclerKeeper)
    }

    override fun getItemCount(): Int = displayNumbers.size

    /** Number of posts the thread holds, whatever the current view shows. */
    val postCount: Int
        get() = postNumbers.size

    /** The original post, or `null` while the thread is empty. Always the first one in post number order. */
    val originalPostItem: PostItem?
        get() = getPostAt(0)

    /** [index]-th post in post number order, ignoring the current view. */
    private fun getPostAt(index: Int): PostItem? = if (index >= 0 && index < postNumbers.size) postItemsMap[postNumbers[index]] else null

    val hiddenPostsCount: Int
        get() = hiddenPosts.count(PostItem.HideState.HIDDEN)

    override fun getItemViewType(position: Int): Int {
        val postItem = getItem(position)
        return (
            if (configurationSet.postStateProvider.isHiddenResolve(postItem)) {
                ViewUnit.ViewType.POST_HIDDEN
            } else {
                ViewUnit.ViewType.POST
            }
        ).ordinal
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder = uiManager.view().createView(parent, ViewUnit.ViewType.values()[viewType])

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        onBindViewHolder(holder, position, mutableListOf<Any?>())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any?>,
    ) {
        val postItem = getItem(position)
        when (ViewUnit.ViewType.values()[holder.getItemViewType()]) {
            ViewUnit.ViewType.POST -> {
                val demandSet = this.demandSet
                demandSet.selection =
                    if (selection) {
                        if (selected.contains(postItem.getPostNumber())) {
                            UiManager.Selection.SELECTED
                        } else {
                            UiManager.Selection.NOT_SELECTED
                        }
                    } else {
                        UiManager.Selection.DISABLED
                    }
                demandSet.lastInList = position == getItemCount() - 1
                if (payloads.isEmpty() || payloads.contains(SimpleViewHolder.EMPTY_PAYLOAD)) {
                    uiManager.view().bindPostView(holder, postItem, configurationSet, demandSet)
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
                uiManager.view().bindPostHiddenView(holder, postItem, configurationSet)
            }

            else -> {}
        }
    }

    fun copyItems(): MutableList<PostItem> = java.util.ArrayList(postItemsMap.values)

    fun getItem(position: Int): PostItem = postItemsMap[displayNumbers[position]]!!

    fun positionOfPostNumber(postNumber: PostNumber): Int =
        if (isPopularMode) {
            displayPositions[postNumber] ?: -1
        } else {
            Collections.binarySearch<PostNumber?>(displayNumbers, postNumber)
        }

    /**
     * Switches between the thread and the popular view. The popular view drops the posts nobody replied
     * to and orders the rest by how many replies they got, ties going to the earlier post.
     */
    fun setPopularMode(popular: Boolean) {
        if (isPopularMode != popular) {
            isPopularMode = popular
            cancelPreloading()
            rebuildDisplayOrder()
            notifyDataSetChanged()
            preloadPosts(0)
        }
    }

    /**
     * Recomputes the popular order, which depends on what is hidden right now. A no-op in the thread
     * view, where hiding a post can't reorder anything.
     */
    fun invalidatePopularOrder() {
        if (isPopularMode) {
            cancelPreloading()
            rebuildDisplayOrder()
            notifyDataSetChanged()
            preloadPosts(0)
        }
    }

    private fun rebuildDisplayOrder() {
        displayNumbers.clear()
        displayPositions.clear()
        if (!isPopularMode) {
            displayNumbers.addAll(postNumbers)
            return
        }
        val postStateProvider = configurationSet.postStateProvider
        val replyCounts = HashMap<PostNumber?, Int>()
        for (postNumber in postNumbers) {
            val postItem = postItemsMap[postNumber]
            if (postItem == null || postStateProvider.isHiddenResolve(postItem)) {
                continue
            }
            // A reply the user has hidden is not a vote for anything, so hiding a spammer takes his
            // replies out of the ranking as well as his posts.
            var replyCount = 0
            for (referenceFrom in postItem.getReferencesFrom()) {
                val reply = postItemsMap[referenceFrom]
                if (reply != null && !postStateProvider.isHiddenResolve(reply)) {
                    replyCount++
                }
            }
            if (replyCount > 0) {
                replyCounts[postNumber] = replyCount
            }
        }
        val ordered = java.util.ArrayList<PostNumber?>(replyCounts.keys)
        ordered.sortWith(nullsFirst(naturalOrder()))
        // The sort is stable, so posts with the same number of replies stay in thread order.
        displayNumbers.addAll(ordered.sortedByDescending { replyCounts[it] ?: 0 })
        for (position in displayNumbers.indices) {
            displayPositions[displayNumbers[position]] = position
        }
    }

    fun positionOfOrdinalIndex(ordinalIndex: Int): Int {
        for (i in 0..<getItemCount()) {
            val postItem = getItem(i)
            if (postItem.getOrdinalIndex() == ordinalIndex) {
                return i
            }
        }
        return -1
    }

    override fun findPostItem(postNumber: PostNumber?): PostItem? = postItemsMap[postNumber]

    /**
     * Every post of the thread in post number order — not what the list shows. Use [iterate] to walk the
     * list itself.
     */
    override fun iterator(): MutableIterator<PostItem> = ThreadIterator()

    override fun onLinkClick(
        view: CommentTextView,
        uri: Uri,
        extra: LinkListener.Extra,
        confirmed: Boolean,
    ) {
        val originalPostItem = this.originalPostItem ?: return
        val chan = get(extra.chanName)
        val boardName = originalPostItem.getBoardName()
        val threadNumber = originalPostItem.getThreadNumber()
        if (extra.chanName != null &&
            chan.locator.safe(false).isThreadUri(uri) &&
            (extra.inBoardLink || equals(boardName, chan.locator.safe(false).getBoardName(uri))) &&
            equals(threadNumber, chan.locator.safe(false).getThreadNumber(uri))
        ) {
            // A link resolves against the thread, not against what the list happens to show, so it
            // opens posts the popular view leaves out too.
            val postNumber = chan.locator.safe(false).getPostNumber(uri)
            val postItem = if (postNumber == null) originalPostItem else postItemsMap[postNumber]
            if (postItem == null) {
                show(R.string.post_is_not_found)
                return
            }
            uiManager.dialog().displaySingle(configurationSet, postItem)
        } else {
            uiManager.interaction().handleLinkClick(configurationSet, uri, extra, confirmed)
        }
    }

    override fun onLinkLongClick(
        view: CommentTextView,
        uri: Uri,
        extra: LinkListener.Extra,
    ) {
        uiManager.interaction().handleLinkLongClick(configurationSet, uri)
    }

    private fun removeOldReferences(changedOrRemoved: Collection<PostNumber?>) {
        for (postNumber in changedOrRemoved) {
            val oldPostItem = postItemsMap[postNumber]
            if (oldPostItem != null) {
                gallerySet.remove(oldPostItem.getPostNumber())
                for (referenceTo in oldPostItem.getReferencesTo()) {
                    val referenced = postItemsMap[referenceTo]
                    if (referenced != null) {
                        referenced.removeReferenceFrom(oldPostItem.getPostNumber())
                    }
                }
            }
        }
    }

    fun insertItems(
        changed: Map<out PostNumber?, PostItem>,
        removed: Collection<PostNumber?>,
    ) {
        cancelPreloading()

        removeOldReferences(changed.keys)
        removeOldReferences(removed)
        for (postItem in changed.values) {
            val oldPostItem = postItemsMap[postItem.getPostNumber()]
            if (oldPostItem != null) {
                for (postNumber in oldPostItem.getReferencesFrom()) {
                    if (!changed.containsKey(postNumber)) {
                        postItem.addReferenceFrom(postNumber)
                    }
                }
            }
        }

        postItemsMap.putAll(changed)
        postItemsMap.keys.removeAll(removed)
        postNumbers.clear()
        postNumbers.addAll(postItemsMap.keys)
        postNumbers.sortWith(nullsFirst(naturalOrder()))

        for (postItem in changed.values) {
            if (postItem.isOriginalPost()) {
                gallerySet.setThreadTitle(postItem.getSubjectOrComment())
            }
            gallerySet.put(postItem.getPostNumber(), postItem.getAttachmentItems())
            for (referenceTo in postItem.getReferencesTo()) {
                val referenced = postItemsMap[referenceTo]
                if (referenced != null) {
                    referenced.addReferenceFrom(postItem.getPostNumber())
                }
            }
        }

        var ordinalIndex = 0
        bumpLimitOrdinalIndex = PostItem.ORDINAL_INDEX_NONE
        val chan = get(configurationSet.chanName)
        val originalPostItem = this.originalPostItem
        val bumpLimit =
            if (originalPostItem != null) chan.configuration.getBumpLimitWithMode(originalPostItem.getBoardName()) else -1
        for (postItem in this) {
            if (postItem.isDeleted()) {
                postItem.setOrdinalIndex(PostItem.ORDINAL_INDEX_DELETED)
            } else {
                postItem.setOrdinalIndex(ordinalIndex++)
                if (ordinalIndex == bumpLimit &&
                    originalPostItem?.getBumpLimitReachedState(
                        chan,
                        ordinalIndex,
                    ) ==
                    PostItem.BumpLimitState.REACHED
                ) {
                    bumpLimitOrdinalIndex = ordinalIndex
                }
            }
        }
        rebuildDisplayOrder()

        notifyDataSetChanged()
        preloadPosts(0)
    }

    fun invalidateComment(position: Int) {
        notifyItemChanged(position, PAYLOAD_INVALIDATE_COMMENT)
    }

    fun reloadAttachment(
        position: Int,
        attachmentItem: AttachmentItem?,
    ) {
        notifyItemChanged(position, attachmentItem)
    }

    fun removeHiddenPost(post: PostItem) {
        // Never remove the original post, it keeps the thread's subject and gallery title.
        val wasHidden = post !== originalPostItem && postItemsMap.containsKey(post.getPostNumber())
        if (wasHidden) {
            cancelPreloading()
            recyclerView.post(
                Runnable {
                    for (referenceTo in post.getReferencesTo()) {
                        val referenced = postItemsMap[referenceTo]
                        if (referenced != null) {
                            referenced.removeReferenceFrom(post.getPostNumber())
                        }
                    }
                    gallerySet.remove(post.getPostNumber())
                    postItemsMap.remove(post.getPostNumber())
                    postNumbers.clear()
                    postNumbers.addAll(postItemsMap.keys)
                    postNumbers.sortWith(nullsFirst(naturalOrder()))
                    rebuildDisplayOrder()
                    notifyDataSetChanged()
                },
            )
        }
    }

    fun clearDeletedPosts(): Boolean {
        var removed = false
        val iterator = postItemsMap.values.iterator()
        while (iterator.hasNext()) {
            val postItem = iterator.next()
            if (postItem.isDeleted()) {
                if (!removed) {
                    removed = true
                    cancelPreloading()
                }
                for (referenceTo in postItem.getReferencesTo()) {
                    val referenced = postItemsMap[referenceTo]
                    if (referenced != null) {
                        referenced.removeReferenceFrom(postItem.getPostNumber())
                    }
                }
                gallerySet.remove(postItem.getPostNumber())
                iterator.remove()
            }
        }
        if (removed) {
            postNumbers.clear()
            postNumbers.addAll(postItemsMap.keys)
            postNumbers.sortWith(nullsFirst(naturalOrder()))
            rebuildDisplayOrder()
            notifyDataSetChanged()
        }
        return removed
    }

    fun hasOldPosts(): Boolean = getPostAt(0)?.isCyclical() == true && getPostAt(1)?.isDeleted() == true

    fun hasDeletedPosts(): Boolean {
        for (postItem in this) {
            if (postItem.isDeleted()) {
                return true
            }
        }
        return false
    }

    fun setSelectionModeEnabled(enabled: Boolean) {
        selection = enabled
        if (!enabled) {
            selected.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleItemSelected(postItem: PostItem) {
        val postNumber = postItem.getPostNumber()
        if (selected.contains(postNumber)) {
            selected.remove(postNumber)
        } else {
            if (!configurationSet.postStateProvider.isHiddenResolve(postItem)) {
                selected.add(postNumber)
            }
        }
        val position = positionOfPostNumber(postNumber)
        notifyItemChanged(position, SimpleViewHolder.EMPTY_PAYLOAD)
    }

    val selectedItems: ArrayList<PostItem>
        get() {
            val selected =
                java.util.ArrayList<PostItem>(this.selected.size)
            for (postNumber in this.selected) {
                val postItem = postItemsMap[postNumber]
                if (postItem != null) {
                    selected.add(postItem)
                }
            }
            selected.sortWith(naturalOrder())
            return selected
        }

    val selectedCount: Int
        get() = selected.size

    fun cancelPreloading() {
        preloadHandler.removeMessages(0)
    }

    private class PreloadIterator(
        private val ascending: MutableIterator<PostItem>,
        private val descending: MutableIterator<PostItem>,
    ) : MutableIterator<PostItem?> {
        private var lastAscending = false

        override fun hasNext(): Boolean = ascending.hasNext() || descending.hasNext()

        override fun next(): PostItem {
            if (lastAscending) {
                lastAscending = false
                return (if (descending.hasNext()) descending else ascending).next()
            } else {
                lastAscending = true
                return (if (ascending.hasNext()) ascending else descending).next()
            }
        }

        override fun remove(): Unit = throw UnsupportedOperationException()
    }

    fun preloadPosts(fromPostNumber: PostNumber?) {
        val position = if (fromPostNumber != null) positionOfPostNumber(fromPostNumber) else -1
        if (position >= 0) {
            preloadPosts(position)
        }
    }

    fun preloadPosts(from: Int) {
        if (from >= 0 && from < getItemCount()) {
            cancelPreloading()
            // Preload to both sides
            val ascending: MutableIterator<PostItem> = PostsIterator(true, from)
            val descending: MutableIterator<PostItem> = PostsIterator(false, from)
            preloadHandler
                .obtainMessage(0, 0, 0, PreloadIterator(ascending, descending))
                .sendToTarget()
        }
    }

    private val preloadHandler = Handler(Looper.getMainLooper(), PreloadCallback())

    init {
        configurationSet =
            ConfigurationSet(
                chanName,
                replyable,
                this,
                postStateProvider,
                gallerySet,
                fragmentManager,
                uiManager.dialog().createStackInstance(),
                this,
                callback,
                true,
                false,
                true,
                true,
                true,
                null,
            )
        recyclerKeeper = RecyclerKeeper(recyclerView)
        this.recyclerView = recyclerView
        super.registerAdapterDataObserver(recyclerKeeper)
        this.postItemsMap = postItemsMap
        this.hiddenPosts = hiddenPosts
        postNumbers.addAll(postItemsMap.keys)
        postNumbers.sortWith(nullsFirst(naturalOrder()))
        displayNumbers.addAll(postNumbers)
        preloadPosts(0)
        for (postItem in postItemsMap.values) {
            if (postItem.isOriginalPost()) {
                gallerySet.setThreadTitle(postItem.getSubjectOrComment())
            }
            gallerySet.put(postItem.getPostNumber(), postItem.getAttachmentItems())
        }
    }

    private inner class PreloadCallback : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            // Take only half a frame per message for preloading in main thread
            val iterator: PreloadIterator = msg.obj as PreloadIterator
            val chan = get(configurationSet.chanName)
            val time = SystemClock.elapsedRealtime()
            val budget = ConcurrentUtils.HALF_FRAME_TIME_MS
            while (SystemClock.elapsedRealtime() - time < budget && iterator.hasNext()) {
                val postItem = iterator.next()
                configurationSet.postStateProvider.isHiddenResolve(postItem)
                postItem.getComment(chan)
            }
            if (iterator.hasNext()) {
                msg.getTarget().obtainMessage(0, 0, 0, iterator).sendToTarget()
            }
            return true
        }
    }

    fun invalidateHidden() {
        cancelPreloading()
        for (postItem in this) {
            postItem.setHidden(PostItem.HideState.UNDEFINED, null)
        }
    }

    fun setHighlightText(highlightText: MutableCollection<String>) {
        demandSet.highlightText = highlightText
        notifyDataSetChanged()
    }

    fun iterate(
        ascending: Boolean,
        from: Int,
    ): Iterable<PostItem> = Iterable { PostsIterator(ascending, from) }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(!needBumpLimitDividerAbove(position + 1))

    private fun needBumpLimitDividerAbove(position: Int): Boolean {
        // The bump limit marks a place in the thread; the popular view has no such place.
        if (isPopularMode) {
            return false
        }
        val postItem = if (position >= 0 && position < getItemCount()) getItem(position) else null
        return postItem != null && bumpLimitOrdinalIndex >= 0 && postItem.getOrdinalIndex() == bumpLimitOrdinalIndex
    }

    private inner class BumpLimitItemDecorator(
        context: Context,
        dividerPadding: Int,
    ) : ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = Rect()
        private val height: Int
        private val padding: Int

        init {
            paint.setColor(getColor(context, R.attr.colorTextError))
            height = (2f * obtainDensity(context)).toInt()
            padding = dividerPadding
        }

        override fun onDraw(
            c: Canvas,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val childCount = parent.getChildCount()
            val left = parent.getPaddingLeft()
            val right = parent.getWidth() - parent.getPaddingRight()
            for (i in 0..<childCount) {
                val view = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(view)
                if (needBumpLimitDividerAbove(position)) {
                    parent.getDecoratedBoundsWithMargins(view, rect)
                    c.drawRect(
                        (left + padding).toFloat(),
                        rect.top.toFloat(),
                        (right - padding).toFloat(),
                        (rect.top + height).toFloat(),
                        paint,
                    )
                }
            }
        }

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (needBumpLimitDividerAbove(position)) {
                outRect.set(0, height, 0, 0)
            } else {
                outRect.set(0, 0, 0, 0)
            }
        }
    }

    private inner class ThreadIterator : MutableIterator<PostItem> {
        private var index = 0

        override fun hasNext(): Boolean = index < postNumbers.size

        override fun next(): PostItem = postItemsMap[postNumbers[index++]]!!

        override fun remove(): Unit = throw UnsupportedOperationException()
    }

    private inner class PostsIterator(
        private val ascending: Boolean,
        private var position: Int,
    ) : MutableIterator<PostItem> {
        override fun hasNext(): Boolean {
            val count = getItemCount()
            return if (ascending) position < count else position >= 0
        }

        fun nextInternal(): PostItem {
            val postItem = getItem(position)
            if (ascending) {
                position++
            } else {
                position--
            }
            return postItem
        }

        override fun next(): PostItem = nextInternal()

        override fun remove(): Unit = throw UnsupportedOperationException()
    }

    companion object {
        private const val PAYLOAD_INVALIDATE_COMMENT = "invalidateComment"
    }
}
