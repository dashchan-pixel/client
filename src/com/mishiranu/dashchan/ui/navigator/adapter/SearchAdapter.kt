package com.mishiranu.dashchan.ui.navigator.adapter

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import chan.util.CommonUtils.equals
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.DemandSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.navigator.manager.ViewUnit
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.setNewPadding
import com.mishiranu.dashchan.widget.DividerItemDecoration

class SearchAdapter(
    private val context: Context,
    callback: Callback?,
    chanName: String?,
    private val uiManager: UiManager,
    fragmentManager: FragmentManager?,
    searchQuery: String?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>() {
    interface Callback : ListViewUtils.SimpleCallback<PostItem?>

    private class ListItem(
        val postItem: PostItem,
        val group: String?,
    )

    val configurationSet: ConfigurationSet
    private val demandSet = DemandSet()
    private val gallerySet = GalleryItem.Set(false)

    private val postItems = ArrayList<PostItem>()
    private val groupItems = ArrayList<ListItem>()

    private var groupMode = false

    init {
        configurationSet =
            ConfigurationSet(
                chanName,
                null,
                null,
                PostStateProvider.DEFAULT,
                gallerySet,
                fragmentManager,
                uiManager.dialog().createStackInstance(),
                null,
                callback,
                mayCollapse = true,
                isDialog = false,
                allowMyMarkEdit = false,
                allowHiding = false,
                allowCommands = false,
                allowGoToPost = false,
                repliesToPost = null,
            )
        demandSet.highlightText = mutableSetOf(searchQuery!!)
    }

    override fun getItemCount(): Int = if (groupMode) groupItems.size else postItems.size

    override fun getItemViewType(position: Int): Int = ViewUnit.ViewType.POST.ordinal

    private fun getItem(position: Int): PostItem = if (groupMode) groupItems[position].postItem else postItems[position]

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder = uiManager.view().createView(parent, ViewUnit.ViewType.POST)

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
        if (payloads.isEmpty()) {
            uiManager.view().bindPostView(holder, getItem(position), configurationSet, demandSet)
        } else {
            for (`object` in payloads) {
                if (`object` is AttachmentItem) {
                    uiManager.view().bindPostViewReloadAttachment(holder, `object`)
                }
            }
        }
    }

    fun setItems(postItems: List<PostItem>?) {
        this.postItems.clear()
        if (postItems != null) {
            this.postItems.addAll(postItems)
        }
        handleItems()
    }

    fun setGroupMode(groupMode: Boolean) {
        if (this.groupMode != groupMode) {
            this.groupMode = groupMode
            handleItems()
        }
    }

    val isGroupMode: Boolean get() {
        return groupMode
    }

    private fun handleItems() {
        groupItems.clear()
        gallerySet.clear()
        if (postItems.size > 0) {
            if (groupMode) {
                val map = LinkedHashMap<String?, ArrayList<PostItem>>()
                for (postItem in postItems) {
                    val threadNumber = postItem.getThreadNumber()
                    var postItems = map[threadNumber]
                    if (postItems == null) {
                        postItems = ArrayList<PostItem>()
                        map[threadNumber] = postItems
                    }
                    postItems.add(postItem)
                }
                for (entry in map.entries) {
                    val threadNumber: String? = entry.key
                    // The Java called Integer.parseInt(threadNumber), which throws
                    // NumberFormatException for a null thread number: it degrades to
                    // number = false rather than crashing.
                    val number = threadNumber?.toIntOrNull() != null
                    val group =
                        context.getString(
                            R.string.in_thread_number__format,
                            if (number) "#" + threadNumber else threadNumber,
                        )
                    var ordinalIndex = 0
                    for (postItem in entry.value) {
                        groupItems.add(SearchAdapter.ListItem(postItem, group))
                        postItem.setOrdinalIndex(ordinalIndex++)
                    }
                }
            } else {
                for (i in postItems.indices) {
                    postItems[i].setOrdinalIndex(i)
                }
            }
        }
        var i = 0
        val count = getItemCount()
        while (i < count) {
            val postItem = getItem(i)
            gallerySet.put(postItem.getPostNumber(), postItem.getAttachmentItems())
            i++
        }
        notifyDataSetChanged()
    }

    fun reloadAttachment(attachmentItem: AttachmentItem) {
        for (i in 0..<getItemCount()) {
            val postItem = getItem(i)
            if (postItem.getPostNumber().equals(attachmentItem.getPostNumber())) {
                notifyItemChanged(i, attachmentItem)
                break
            }
        }
    }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(true)

    fun configureItemHeader(
        context: Context,
        headerView: TextView,
    ) {
        val density = obtainDensity(context)
        setNewPadding(headerView, (12f * density).toInt(), null, (12f * density).toInt(), null)
    }

    fun getItemHeader(position: Int): String? {
        if (groupMode) {
            if (position == 0) {
                return groupItems[0].group
            } else {
                val previous = groupItems[position - 1].group
                val current = groupItems[position].group
                return if (equals(previous, current)) null else current
            }
        } else {
            return null
        }
    }
}
