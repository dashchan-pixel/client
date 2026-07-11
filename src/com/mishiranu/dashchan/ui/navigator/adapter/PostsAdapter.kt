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
    postStateProvider: PostStateProvider?,
    fragmentManager: FragmentManager?,
    recyclerView: RecyclerView,
    postItemsMap: MutableMap<PostNumber?, PostItem>,
    hiddenPosts: PostItem.HideState.Map<PostNumber?>
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>(), LinkListener, UiManager.PostsProvider,
    HidePerformer.PostsProvider {
    interface Callback : ClickCallback<PostItem?, RecyclerView.ViewHolder?> {
        fun onItemClick(view: View?, postItem: PostItem?)
        fun onItemLongClick(postItem: PostItem?): Boolean

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int, postItem: PostItem?, longClick: Boolean
        ): Boolean {
            if (longClick) {
                return onItemLongClick(postItem)
            } else {
                onItemClick(holder.itemView, postItem)
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
    private val postItemsMap: MutableMap<PostNumber?, PostItem>
    val hiddenPosts: PostItem.HideState.Map<PostNumber?>
    private val selected = HashSet<PostNumber?>()

    private var bumpLimitOrdinalIndex = PostItem.ORDINAL_INDEX_NONE
    private var selection = false

    fun createPostItemDecoration(context: Context, dividerPadding: Int): ItemDecoration {
        return BumpLimitItemDecorator(context, dividerPadding)
    }

    override fun registerAdapterDataObserver(observer: AdapterDataObserver) {
        super.registerAdapterDataObserver(observer)

        // Move observer to the end
        super.unregisterAdapterDataObserver(recyclerKeeper)
        super.registerAdapterDataObserver(recyclerKeeper)
    }

    override fun getItemCount(): Int {
        return postNumbers.size
    }

    val hiddenPostsCount: Int
        get() = hiddenPosts.count(PostItem.HideState.HIDDEN)

    override fun getItemViewType(position: Int): Int {
        val postItem = getItem(position)
        return (if (configurationSet.postStateProvider!!.isHiddenResolve(postItem))
            ViewUnit.ViewType.POST_HIDDEN
        else
            ViewUnit.ViewType.POST).ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return uiManager.view().createView(parent, ViewUnit.ViewType.values()[viewType])
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf<Any?>())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int,
        payloads: MutableList<Any?>
    ) {
        val postItem = getItem(position)
        when (ViewUnit.ViewType.values()[holder.getItemViewType()]) {
            ViewUnit.ViewType.POST -> {
                val demandSet = this.demandSet
                demandSet.selection =
                    if (selection) if (selected.contains(postItem.getPostNumber()))
                        UiManager.Selection.SELECTED
                    else
                        UiManager.Selection.NOT_SELECTED else UiManager.Selection.DISABLED
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
        }
    }

    fun copyItems(): MutableList<PostItem?> {
        return java.util.ArrayList<PostItem?>(postItemsMap.values)
    }

    fun getItem(position: Int): PostItem {
        return postItemsMap.get(postNumbers.get(position))!!
    }

    fun positionOfPostNumber(postNumber: PostNumber): Int {
        return Collections.binarySearch<PostNumber?>(postNumbers, postNumber)
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

    override fun findPostItem(postNumber: PostNumber?): PostItem? {
        return postItemsMap.get(postNumber)
    }

    override fun iterator(): MutableIterator<PostItem?> {
        return PostsIterator(true, 0)
    }

    override fun onLinkClick(
        view: CommentTextView?,
        uri: Uri,
        extra: LinkListener.Extra,
        confirmed: Boolean
    ) {
        val originalPostItem = getItem(0)
        val chan = get(extra.chanName)
        val boardName = originalPostItem.getBoardName()
        val threadNumber = originalPostItem.getThreadNumber()
        if (extra.chanName != null && chan.locator.safe(false).isThreadUri(uri)
            && (extra.inBoardLink || equals(boardName, chan.locator.safe(false).getBoardName(uri)))
            && equals(threadNumber, chan.locator.safe(false).getThreadNumber(uri))
        ) {
            val postNumber = chan.locator.safe(false).getPostNumber(uri)
            val position = if (postNumber == null) 0 else positionOfPostNumber(postNumber)
            if (position < 0) {
                show(R.string.post_is_not_found)
                return
            }
            uiManager.dialog().displaySingle(configurationSet, getItem(position))
        } else {
            uiManager.interaction().handleLinkClick(configurationSet, uri, extra, confirmed)
        }
    }

    override fun onLinkLongClick(view: CommentTextView?, uri: Uri, extra: LinkListener.Extra?) {
        uiManager.interaction().handleLinkLongClick(configurationSet, uri)
    }

    private fun removeOldReferences(changedOrRemoved: MutableCollection<PostNumber?>) {
        for (postNumber in changedOrRemoved) {
            val oldPostItem = postItemsMap.get(postNumber)
            if (oldPostItem != null) {
                gallerySet.remove(oldPostItem.getPostNumber())
                for (referenceTo in oldPostItem.getReferencesTo()) {
                    val referenced = postItemsMap.get(referenceTo)
                    if (referenced != null) {
                        referenced.removeReferenceFrom(oldPostItem.getPostNumber())
                    }
                }
            }
        }
    }

    fun insertItems(
        changed: MutableMap<PostNumber?, PostItem>,
        removed: MutableCollection<PostNumber?>
    ) {
        cancelPreloading()

        removeOldReferences(changed.keys)
        removeOldReferences(removed)
        for (postItem in changed.values) {
            val oldPostItem = postItemsMap.get(postItem.getPostNumber())
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
        Collections.sort<PostNumber?>(postNumbers)

        for (postItem in changed.values) {
            if (postItem.isOriginalPost()) {
                gallerySet.setThreadTitle(postItem.getSubjectOrComment())
            }
            gallerySet.put(postItem.getPostNumber(), postItem.getAttachmentItems())
            for (referenceTo in postItem.getReferencesTo()) {
                val referenced = postItemsMap.get(referenceTo)
                if (referenced != null) {
                    referenced.addReferenceFrom(postItem.getPostNumber())
                }
            }
        }

        var ordinalIndex = 0
        bumpLimitOrdinalIndex = PostItem.ORDINAL_INDEX_NONE
        val chan = get(configurationSet.chanName)
        val bumpLimit =
            if (getItemCount() > 0) chan.configuration.getBumpLimitWithMode(getItem(0).getBoardName()) else -1
        for (postItem in iterate(true, 0)) {
            if (postItem.isDeleted()) {
                postItem.setOrdinalIndex(PostItem.ORDINAL_INDEX_DELETED)
            } else {
                postItem.setOrdinalIndex(ordinalIndex++)
                if (ordinalIndex == bumpLimit && getItem(0).getBumpLimitReachedState(
                        chan,
                        ordinalIndex
                    ) ==
                    PostItem.BumpLimitState.REACHED
                ) {
                    bumpLimitOrdinalIndex = ordinalIndex
                }
            }
        }

        notifyDataSetChanged()
        preloadPosts(0)
    }

    fun invalidateComment(position: Int) {
        notifyItemChanged(position, PAYLOAD_INVALIDATE_COMMENT)
    }

    fun reloadAttachment(position: Int, attachmentItem: AttachmentItem?) {
        notifyItemChanged(position, attachmentItem)
    }

    fun removeHiddenPost(post: PostItem) {
        val position = positionOfPostNumber(post.getPostNumber())
        val iterator = postItemsMap.values.iterator()
        var wasHidden = false
        while (iterator.hasNext()) {
            val postItem = iterator.next()
            if (post.getPostNumber().equals(postItem.getPostNumber()) && position != 0) {
                wasHidden = true
                cancelPreloading()
                break
            }
        }
        if (wasHidden) {
            recyclerView.post(
                Runnable {
                    for (referenceTo in post.getReferencesTo()) {
                        val referenced = postItemsMap.get(referenceTo)
                        if (referenced != null) {
                            referenced.removeReferenceFrom(post.getPostNumber())
                        }
                    }
                    gallerySet.remove(post.getPostNumber())
                    postItemsMap.remove(post.getPostNumber())
                    postNumbers.clear()
                    postNumbers.addAll(postItemsMap.keys)
                    Collections.sort<PostNumber?>(postNumbers)
                    notifyDataSetChanged()
                }
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
                    val referenced = postItemsMap.get(referenceTo)
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
            Collections.sort<PostNumber?>(postNumbers)
            notifyDataSetChanged()
        }
        return removed
    }

    fun hasOldPosts(): Boolean {
        return getItemCount() >= 2 && getItem(0).isCyclical() && getItem(1).isDeleted()
    }

    fun hasDeletedPosts(): Boolean {
        for (postItem in this) {
            if (postItem!!.isDeleted()) {
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
            if (!configurationSet.postStateProvider!!.isHiddenResolve(postItem)) {
                selected.add(postNumber)
            }
        }
        val position = positionOfPostNumber(postNumber)
        notifyItemChanged(position, SimpleViewHolder.EMPTY_PAYLOAD)
    }

    val selectedItems: ArrayList<PostItem?>
        get() {
            val selected =
                java.util.ArrayList<PostItem?>(this.selected.size)
            for (postNumber in this.selected) {
                val postItem = postItemsMap.get(postNumber)
                if (postItem != null) {
                    selected.add(postItem)
                }
            }
            Collections.sort<PostItem?>(selected)
            return selected
        }

    val selectedCount: Int
        get() = selected.size

    fun cancelPreloading() {
        preloadHandler.removeMessages(0)
    }

    private class PreloadIterator(
        private val ascending: MutableIterator<PostItem>,
        private val descending: MutableIterator<PostItem>
    ) : MutableIterator<PostItem?> {
        private var lastAscending = false

        override fun hasNext(): Boolean {
            return ascending.hasNext() || descending.hasNext()
        }

        override fun next(): PostItem {
            if (lastAscending) {
                lastAscending = false
                return (if (descending.hasNext()) descending else ascending).next()
            } else {
                lastAscending = true
                return (if (ascending.hasNext()) ascending else descending).next()
            }
        }

        override fun remove() {
            throw UnsupportedOperationException()
        }
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
            preloadHandler.obtainMessage(0, 0, 0, PreloadIterator(ascending, descending))
                .sendToTarget()
        }
    }

    private val preloadHandler = Handler(Looper.getMainLooper(), PreloadCallback())

    init {
        configurationSet = ConfigurationSet(
            chanName, replyable, this, postStateProvider,
            gallerySet, fragmentManager, uiManager.dialog().createStackInstance(), this, callback,
            true, false, true, true, true, null
        )
        recyclerKeeper = RecyclerKeeper(recyclerView)
        this.recyclerView = recyclerView
        super.registerAdapterDataObserver(recyclerKeeper)
        this.postItemsMap = postItemsMap
        this.hiddenPosts = hiddenPosts
        postNumbers.addAll(postItemsMap.keys)
        Collections.sort<PostNumber?>(postNumbers)
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
            // Take only 8ms per frame for preloading in main thread
            val iterator: PreloadIterator = msg.obj as PreloadIterator
            val chan = get(configurationSet.chanName)
            val time = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - time < ConcurrentUtils.HALF_FRAME_TIME_MS && iterator.hasNext()) {
                val postItem = iterator.next()
                configurationSet.postStateProvider!!.isHiddenResolve(postItem)
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
            postItem!!.setHidden(PostItem.HideState.UNDEFINED, null)
        }
    }

    fun setHighlightText(highlightText: MutableCollection<String?>) {
        demandSet.highlightText = highlightText
        notifyDataSetChanged()
    }

    fun iterate(ascending: Boolean, from: Int): Iterable<PostItem> {
        return Iterable { PostsIterator(ascending, from) }
    }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int
    ): DividerItemDecoration.Configuration {
        return configuration.need(!needBumpLimitDividerAbove(position + 1))
    }

    private fun needBumpLimitDividerAbove(position: Int): Boolean {
        val postItem = if (position >= 0 && position < getItemCount()) getItem(position) else null
        return postItem != null && bumpLimitOrdinalIndex >= 0 && postItem.getOrdinalIndex() == bumpLimitOrdinalIndex
    }

    private inner class BumpLimitItemDecorator(context: Context, dividerPadding: Int) :
        ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = Rect()
        private val height: Int
        private val padding: Int

        init {
            paint.setColor(getColor(context, R.attr.colorTextError))
            height = (2f * obtainDensity(context)).toInt()
            padding = dividerPadding
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
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
                        paint
                    )
                }
            }
        }

        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (needBumpLimitDividerAbove(position)) {
                outRect.set(0, height, 0, 0)
            } else {
                outRect.set(0, 0, 0, 0)
            }
        }
    }

    private inner class PostsIterator(private val ascending: Boolean, private var position: Int) :
        MutableIterator<PostItem?> {
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

        override fun next(): PostItem {
            return nextInternal()
        }

        override fun remove() {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private const val PAYLOAD_INVALIDATE_COMMENT = "invalidateComment"
    }
}
