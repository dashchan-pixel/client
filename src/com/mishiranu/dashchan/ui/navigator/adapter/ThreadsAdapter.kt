package com.mishiranu.dashchan.ui.navigator.adapter

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan.Companion.get
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.Preferences.CatalogSort
import com.mishiranu.dashchan.content.Preferences.ThreadsView
import com.mishiranu.dashchan.content.Preferences.isDisplayHiddenThreads
import com.mishiranu.dashchan.content.Preferences.thumbnailsScale
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.navigator.manager.ViewUnit
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.DividerItemDecoration
import java.util.Collections
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ThreadsAdapter(
    private val context: Context,
    callback: Callback?,
    chanName: String?,
    private val uiManager: UiManager,
    postStateProvider: PostStateProvider?,
    fragmentManager: FragmentManager?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>(),
    GalleryItem.Provider {
    interface Callback : ListViewUtils.SimpleCallback<PostItem?>

    private class GridMode(
        val columns: Int,
        val small: Boolean,
        val gridItemContentHeight: Int,
    )

    private val postItems = ArrayList<PostItem>()
    private var catalogSortedPostItems: ArrayList<PostItem>? = null
    private var filteredPostItems: ArrayList<PostItem>? = null
    private var catalog = false

    val configurationSet: ConfigurationSet

    private var filterText: String? = null
    private var catalogSort: CatalogSort? = CatalogSort.UNSORTED
    private var cardsMode = false
    private var gridMode: GridMode? = null

    init {
        configurationSet =
            ConfigurationSet(
                chanName,
                null,
                null,
                postStateProvider,
                this,
                fragmentManager,
                uiManager.dialog().createStackInstance(),
                null,
                callback,
                false,
                false,
                false,
                false,
                false,
                null,
            )
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
            ViewUnit.ViewType.THREAD, ViewUnit.ViewType.THREAD_CARD -> {
                if (payloads.isEmpty()) {
                    uiManager.view().bindThreadView(holder, postItem, configurationSet)
                } else {
                    for (`object` in payloads) {
                        if (`object` is AttachmentItem) {
                            uiManager.view().bindThreadViewReloadAttachment(holder, `object`)
                        }
                    }
                }
            }

            ViewUnit.ViewType.THREAD_HIDDEN, ViewUnit.ViewType.THREAD_CARD_HIDDEN -> {
                uiManager.view().bindThreadHiddenView(holder, postItem, configurationSet)
            }

            ViewUnit.ViewType.THREAD_CARD_CELL -> {
                if (payloads.isEmpty()) {
                    uiManager.view().bindThreadCellView(
                        holder,
                        postItem,
                        configurationSet,
                        gridMode!!.small,
                        gridMode!!.gridItemContentHeight,
                    )
                } else {
                    for (`object` in payloads) {
                        if (`object` is AttachmentItem) {
                            uiManager.view().bindThreadViewReloadAttachment(holder, `object`)
                        }
                    }
                }
            }

            else -> {}
        }
    }

    override fun getGallerySet(postItem: PostItem): GalleryItem.Set = postItem.getThreadGallerySet()

    val isRealEmpty: Boolean
        get() = postItems.isEmpty()

    override fun getItemViewType(position: Int): Int {
        val postItem = getItem(position)
        return (
            if (gridMode != null) {
                ViewUnit.ViewType.THREAD_CARD_CELL
            } else {
                if (configurationSet.postStateProvider!!.isHiddenResolve(postItem)) {
                    (if (cardsMode) ViewUnit.ViewType.THREAD_CARD_HIDDEN else ViewUnit.ViewType.THREAD_HIDDEN)
                } else {
                    (if (cardsMode) ViewUnit.ViewType.THREAD_CARD else ViewUnit.ViewType.THREAD)
                }
            }
        ).ordinal
    }

    private fun getPostItems(): MutableList<PostItem> =
        if (filteredPostItems != null) {
            filteredPostItems!!
        } else if (catalogSortedPostItems != null) {
            catalogSortedPostItems!!
        } else {
            postItems
        }

    private fun getItem(position: Int): PostItem = getPostItems()[position]

    override fun getItemCount(): Int = getPostItems().size

    fun applyItemPadding(
        view: View,
        position: Int,
        column: Int,
        rect: Rect,
    ) {
        val density = obtainDensity(view)
        val paddingOut = (CARD_PADDING_OUT_DP * density).toInt()
        val paddingIn = (CARD_PADDING_IN_DP * density).toInt()
        if (!cardsMode) {
            rect.set(0, 0, 0, 0)
        } else {
            val columns = if (gridMode != null) gridMode!!.columns else 1
            val left: Int
            val right: Int
            if (columns >= 2) {
                val paddingInExtra =
                    ((CARD_PADDING_IN_DP + CARD_PADDING_IN_EXTRA_DP) * density).toInt()
                val total = 2 * paddingOut + (columns - 1) * paddingInExtra
                val average = total.toFloat() / columns
                left =
                    lerp(
                        paddingOut.toFloat(),
                        average - paddingOut,
                        column.toFloat() / (columns - 1),
                    ).toInt()
                right = average.toInt() - left
            } else {
                left = paddingOut
                right = paddingOut
            }
            val firstRow = position - column == 0
            val lastRow = position + columns - column >= getItemCount()
            rect.set(
                left,
                if (firstRow) paddingOut else paddingIn,
                right,
                if (lastRow) paddingOut else 0,
            )
        }
    }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration {
        if (cardsMode) {
            return configuration.need(false)
        } else {
            val current = getItem(position)
            val next = if (position + 1 < getItemCount()) getItem(position + 1) else null
            val density = obtainDensity(context)
            val scale = thumbnailsScale
            val padding = (LIST_PADDING * density).toInt()
            val imagePadding = ((10 + 64 * scale + 10) * density).toInt()
            val currentImage =
                current.hasAttachments() &&
                    !configurationSet.postStateProvider!!.isHiddenResolve(current)
            val nextImage =
                next != null &&
                    next.hasAttachments() &&
                    !configurationSet.postStateProvider!!.isHiddenResolve(
                        next,
                    )
            return configuration
                .need(true)
                .horizontal(if (currentImage && nextImage) imagePadding else padding, padding)
        }
    }

    fun setItems(
        postItemsCollection: Collection<List<PostItem>>,
        catalog: Boolean,
    ) {
        postItems.clear()
        for (postItems in postItemsCollection) {
            appendItemsInternal(postItems)
        }
        this.catalog = catalog
        applyCurrentSortingAndFilter(true, true)
        notifyDataSetChanged()
    }

    fun appendItems(postItems: List<PostItem>?) {
        appendItemsInternal(postItems)
        applyCurrentSortingAndFilter(true, true)
        notifyDataSetChanged()
    }

    fun notifyNotModified() {
        for (postItem in postItems) {
            postItem.setHidden(PostItem.HideState.UNDEFINED, null)
        }
        notifyDataSetChanged()
    }

    private fun appendItemsInternal(postItems: List<PostItem>?) {
        val displayHidden = isDisplayHiddenThreads
        if (postItems != null) {
            for (postItem in postItems) {
                if (displayHidden || !configurationSet.postStateProvider!!.isHiddenResolve(postItem)) {
                    this.postItems.add(postItem)
                }
            }
        }
    }

    fun applyFilter(text: String?) {
        if (emptyIfNull(filterText) != emptyIfNull(text)) {
            filterText = text
            applyCurrentSortingAndFilter(false, true)
            notifyDataSetChanged()
        }
    }

    fun setCatalogSort(catalogSort: CatalogSort?) {
        if (this.catalogSort != catalogSort) {
            this.catalogSort = catalogSort
            if (catalog) {
                applyCurrentSortingAndFilter(true, false)
                notifyDataSetChanged()
            }
        }
    }

    private fun applyCurrentSortingAndFilter(
        sorting: Boolean,
        filter: Boolean,
    ) {
        if (sorting) {
            val comparator =
                if (catalogSort != null) catalogSort!!.comparator else null
            if (catalog && comparator != null) {
                val sortedPostItems =
                    catalogSortedPostItems
                        ?: ArrayList<PostItem>().also { catalogSortedPostItems = it }
                sortedPostItems.clear()
                sortedPostItems.addAll(postItems)
                Collections.sort(sortedPostItems, comparator)
            } else {
                catalogSortedPostItems = null
            }
        }
        if (sorting || filter) {
            var text = filterText
            if (!isEmpty(text)) {
                if (filteredPostItems == null) {
                    filteredPostItems = ArrayList()
                } else {
                    filteredPostItems!!.clear()
                }
                text = text!!.lowercase(Locale.getDefault())
                val chan = get(configurationSet.chanName)
                val locale = Locale.getDefault()
                for (postItem in ((if (catalogSortedPostItems != null) catalogSortedPostItems else postItems)!!)) {
                    val add =
                        postItem.getSubject().lowercase(locale).contains(text) ||
                            postItem
                                .getComment(chan)
                                .toString()
                                .lowercase(locale)
                                .contains(text)
                    if (add) {
                        filteredPostItems!!.add(postItem)
                    }
                }
            } else {
                filteredPostItems = null
            }
        }
    }

    fun reloadAttachment(attachmentItem: AttachmentItem) {
        for (i in 0..<getItemCount()) {
            val postItem = getItem(i)
            if (postItem.getPostNumber().equals(attachmentItem.getPostNumber())) {
                notifyItemChanged(i, attachmentItem)
            }
        }
    }

    fun setThreadsView(threadsView: ThreadsView?): Int {
        if (threadsView == ThreadsView.LARGE_GRID ||
            threadsView == ThreadsView.SMALL_GRID
        ) {
            val density = obtainDensity(context)
            val totalWidth =
                (context.getResources().getConfiguration().screenWidthDp * density).toInt()
            val minWidthSmall = (CARD_MIN_WIDTH_SMALL_DP * density).toInt()
            val minWidthLarge = (CARD_MIN_WIDTH_LARGE_DP * density).toInt()
            val paddingOut = (CARD_PADDING_OUT_DP * density).toInt()
            val paddingInExtra = ((CARD_PADDING_IN_DP + CARD_PADDING_IN_EXTRA_DP) * density).toInt()
            var smallColumns: Int =
                calculateColumnsCount(totalWidth, minWidthSmall, paddingOut, paddingInExtra)
            val largeColumns: Int =
                calculateColumnsCount(totalWidth, minWidthLarge, paddingOut, paddingInExtra)
            if (smallColumns == largeColumns) {
                smallColumns++
            }
            val small = threadsView == ThreadsView.SMALL_GRID
            val columns = if (small) smallColumns else largeColumns
            val contentWidth =
                (totalWidth - 2 * paddingOut - (columns - 1) * paddingInExtra) / columns
            val contentHeight = (contentWidth * (if (small) 1.35f else 1.5f)).toInt()
            cardsMode = true
            gridMode = GridMode(columns, small, contentHeight)
            return columns
        } else {
            cardsMode = threadsView == ThreadsView.CARDS
            gridMode = null
            return 1
        }
    }

    fun getThread(position: Int): PostItem = getItem(position)

    fun notifyThreadHidden(thread: PostItem?) {
        val threadPosition = getPostItems().indexOf(thread)
        if (isDisplayHiddenThreads) {
            notifyItemChanged(threadPosition)
        } else {
            getPostItems().removeAt(threadPosition)
            notifyItemRemoved(threadPosition)
        }
    }

    fun notifyThreadShown(thread: PostItem?) {
        val threadPosition = getPostItems().indexOf(thread)
        notifyItemChanged(threadPosition)
    }

    companion object {
        private const val LIST_PADDING = 12
        private const val CARD_MIN_WIDTH_LARGE_DP = 120
        private const val CARD_MIN_WIDTH_SMALL_DP = 90
        private const val CARD_PADDING_OUT_DP = 8
        private const val CARD_PADDING_IN_DP = 4
        private const val CARD_PADDING_IN_EXTRA_DP = 1

        private fun calculateColumnsCount(
            totalWidth: Int,
            minWidth: Int,
            paddingOut: Int,
            paddingInExtra: Int,
        ): Int =
            min(
                max(
                    1,
                    (totalWidth - 2 * paddingOut + paddingInExtra) /
                        (minWidth + paddingInExtra),
                ),
                6,
            )
    }
}
