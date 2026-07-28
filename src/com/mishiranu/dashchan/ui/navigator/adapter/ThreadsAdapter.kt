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
    postStateProvider: PostStateProvider,
    fragmentManager: FragmentManager?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>(),
    GalleryItem.Provider {
    interface Callback : ListViewUtils.SimpleCallback<PostItem?>

    private class GridMode(
        val columns: Int,
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
                mayCollapse = false,
                isDialog = false,
                allowMyMarkEdit = false,
                allowHiding = false,
                allowCommands = false,
                allowGoToPost = false,
                repliesToPost = null,
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
                if (configurationSet.postStateProvider.isHiddenResolve(postItem)) {
                    (if (cardsMode) ViewUnit.ViewType.THREAD_CARD_HIDDEN else ViewUnit.ViewType.THREAD_HIDDEN)
                } else {
                    (if (cardsMode) ViewUnit.ViewType.THREAD_CARD else ViewUnit.ViewType.THREAD)
                }
            }
        ).ordinal
    }

    private fun getPostItems(): MutableList<PostItem> = filteredPostItems ?: catalogSortedPostItems ?: postItems

    private fun getItem(position: Int): PostItem = getPostItems()[position]

    override fun getItemCount(): Int = getPostItems().size

    fun applyItemPadding(
        view: View,
        position: Int,
        column: Int,
        rect: Rect,
    ) {
        if (!cardsMode) {
            rect.set(0, 0, 0, 0)
        } else {
            // One spacing value for every gap: between two cards and between a card and the edge of the
            // list alike. The cards themselves add nothing on top of it (see CardView.useCompatPadding).
            val spacing = (CARD_SPACING_DP * obtainDensity(view)).toInt()
            val columns = gridMode?.columns ?: 1
            val left: Int
            val right: Int
            if (columns >= 2) {
                // Every column gets the same amount of horizontal space taken away from it, otherwise the
                // cards would come out unequally wide; the split between left and right slides from
                // "all left" in the first column to "all right" in the last one so that the visible
                // gutters stay exactly one spacing wide.
                val average = ((columns + 1) * spacing).toFloat() / columns
                left =
                    lerp(
                        spacing.toFloat(),
                        average - spacing,
                        column.toFloat() / (columns - 1),
                    ).toInt()
                right = average.toInt() - left
            } else {
                left = spacing
                right = spacing
            }
            // The top offset carries the whole gap between two rows, so the bottom one is only needed to
            // close the list off after the last row.
            val lastRow = position + columns - column >= getItemCount()
            rect.set(left, spacing, right, if (lastRow) spacing else 0)
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
                    !configurationSet.postStateProvider.isHiddenResolve(current)
            val nextImage =
                next != null &&
                    next.hasAttachments() &&
                    !configurationSet.postStateProvider.isHiddenResolve(
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
                if (displayHidden || !configurationSet.postStateProvider.isHiddenResolve(postItem)) {
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
            val comparator = catalogSort?.comparator
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
            val filterText = this.filterText
            if (!filterText.isNullOrEmpty()) {
                val filteredPostItems =
                    this.filteredPostItems?.also { it.clear() }
                        ?: ArrayList<PostItem>().also { this.filteredPostItems = it }
                val text = filterText.lowercase(Locale.getDefault())
                val chan = get(configurationSet.chanName)
                val locale = Locale.getDefault()
                for (postItem in (catalogSortedPostItems ?: postItems)) {
                    val add =
                        postItem.getSubject().lowercase(locale).contains(text) ||
                            postItem
                                .getComment(chan)
                                .toString()
                                .lowercase(locale)
                                .contains(text)
                    if (add) {
                        filteredPostItems.add(postItem)
                    }
                }
            } else {
                this.filteredPostItems = null
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
        if (threadsView == ThreadsView.GRID) {
            val density = obtainDensity(context)
            val totalWidth =
                (context.getResources().getConfiguration().screenWidthDp * density).toInt()
            val minWidth = (CARD_MIN_WIDTH_DP * density).toInt()
            val spacing = (CARD_SPACING_DP * density).toInt()
            val columns: Int = calculateColumnsCount(totalWidth, minWidth, spacing)
            val contentWidth = (totalWidth - (columns + 1) * spacing) / columns
            val contentHeight = (contentWidth * 1.5f).toInt()
            cardsMode = true
            gridMode = GridMode(columns, contentHeight)
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
        private const val CARD_MIN_WIDTH_DP = 120

        /**
         * The single gap used by the cards and grid views, both between two cards and between a card and
         * the edge of the list. 16dp is the Material 3 list/card margin the platform Settings app uses.
         */
        private const val CARD_SPACING_DP = 16

        /** How many columns of at least [minWidth] fit once every gap has been paid for. */
        private fun calculateColumnsCount(
            totalWidth: Int,
            minWidth: Int,
            spacing: Int,
        ): Int = min(max(1, (totalWidth - spacing) / (minWidth + spacing)), 6)
    }
}
