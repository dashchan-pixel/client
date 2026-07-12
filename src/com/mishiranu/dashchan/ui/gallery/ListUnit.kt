package com.mishiranu.dashchan.ui.gallery

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.SparseIntArray
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import chan.content.Chan.Companion.get
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.formatFileSize
import chan.util.StringUtils.getFileExtension
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.graphics.SelectorBorderDrawable
import com.mishiranu.dashchan.graphics.SelectorCheckDrawable
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.SearchImageDialog
import com.mishiranu.dashchan.ui.gallery.GalleryInstance.Companion.getCallback
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getActionBarIcon
import com.mishiranu.dashchan.util.ResourceUtils.getColonString
import com.mishiranu.dashchan.util.ResourceUtils.isTablet
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.setNewMargin
import com.mishiranu.dashchan.util.ViewUtils.setNewPadding
import com.mishiranu.dashchan.widget.AttachmentView
import com.mishiranu.dashchan.widget.EdgeEffectHandler
import com.mishiranu.dashchan.widget.InsetsLayout
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import java.util.Locale
import kotlin.math.max

class ListUnit(private val instance: GalleryInstance) : ActionMode.Callback {
    private val recyclerView: PaddedRecyclerView
    private val selected = SparseIntArray()

    private var selectionMode: ActionMode? = null

    private val callback: GridAdapter.Callback = object : GridAdapter.Callback {
        override fun isItemChecked(position: Int): Boolean {
            return selected.indexOfKey(position) >= 0
        }

        override fun onItemClick(view: View, position: Int) {
            this@ListUnit.onItemClick(view, position)
        }

        override fun onItemLongClick(position: Int): Boolean {
            return this@ListUnit.onItemLongClick(position)
        }
    }

    fun getRecyclerView(): RecyclerView {
        return recyclerView
    }

    private val adapter: GridAdapter?
        get() = recyclerView.getAdapter() as GridAdapter?

    val selectedPositions: IntArray?
        get() {
            if (selectionMode != null) {
                val array = SparseIntArray()
                val count = this.adapter!!.getItemCount()
                for (i in 0..<count) {
                    if (callback.isItemChecked(i)) {
                        array.put(i, i)
                    }
                }
                val result = IntArray(array.size())
                for (i in 0..<array.size()) {
                    result[i] = array.keyAt(i)
                }
                return result
            }
            return null
        }

    fun scrollListToPosition(position: Int, checkVisibility: Boolean) {
        val layoutManager = recyclerView.getLayoutManager() as GridLayoutManager?
        if (checkVisibility) {
            if (position >= layoutManager!!.findFirstCompletelyVisibleItemPosition() &&
                position <= layoutManager.findLastCompletelyVisibleItemPosition()
            ) {
                return
            }
        }
        layoutManager!!.scrollToPositionWithOffset(position, 0)
    }

    fun areItemsSelectable(): Boolean {
        return this.adapter!!.getItemCount() > 0
    }

    fun startSelectionMode(selected: IntArray?) {
        this.selected.clear()
        selectionMode = recyclerView.startActionMode(this)
        if (selectionMode != null && selected != null) {
            var selectedCount = 0
            val count = this.adapter!!.getItemCount()
            for (position in selected) {
                if (position >= 0 && position < count) {
                    this.selected.append(position, position)
                    selectedCount++
                }
            }
            updateAllGalleryItemsChecked()
            selectionMode!!.setTitle(
                getColonString(
                    instance.context.getResources(),
                    R.string.selected, selectedCount
                )
            )
        }
    }

    fun onApplyWindowInsets(insets: InsetsLayout.Insets): Boolean {
        val top = insets.top + this.actionBarHeight
        setNewMargin(recyclerView, insets.left, null, insets.right, null)
        setNewPadding(recyclerView, null, top, null, insets.bottom)
        return true
    }

    init {
        val density = obtainDensity(instance.context)
        val spacing = (GRID_SPACING_DP * density).toInt()
        recyclerView = GalleryRecyclerView(instance.context, spacing)
        recyclerView.setId(android.R.id.list)
        recyclerView.setMotionEventSplittingEnabled(false)
        recyclerView.setClipToPadding(false)
        recyclerView.setLayoutManager(GridLayoutManager(recyclerView.getContext(), 1))
        recyclerView.addItemDecoration(SpacingItemDecoration(spacing))
        val adapter = GridAdapter(callback, instance.chanName, instance.galleryItems)
        recyclerView.setAdapter(adapter)
        updateGridMetrics(instance.context.getResources().getConfiguration())
    }

    fun switchMode(galleryMode: Boolean, duration: Int) {
        if (galleryMode) {
            recyclerView.setVisibility(View.VISIBLE)
            this.adapter!!.activate()
            if (duration > 0) {
                recyclerView.setAlpha(0f)
                recyclerView.setScaleX(GRID_SCALE)
                recyclerView.setScaleY(GRID_SCALE)
                recyclerView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(duration.toLong()).setListener(null).start()
            }
        } else {
            if (duration > 0) {
                recyclerView.setAlpha(1f)
                recyclerView.setScaleX(1f)
                recyclerView.setScaleY(1f)
                recyclerView.animate().alpha(0f).scaleX(GRID_SCALE).scaleY(GRID_SCALE)
                    .setDuration(duration.toLong())
                    .setListener(AnimationUtils.VisibilityListener(recyclerView, View.GONE)).start()
            } else {
                recyclerView.setVisibility(View.GONE)
            }
        }
    }

    private fun onItemClick(view: View, position: Int) {
        if (selectionMode != null) {
            val index = selected.indexOfKey(position)
            if (index >= 0) {
                selected.removeAt(index)
            } else {
                selected.put(position, position)
            }
            updateGalleryItemChecked(view, position)
            selectionMode!!.setTitle(
                getColonString(
                    instance.context.getResources(),
                    R.string.selected, selected.size()
                )
            )
        } else {
            instance.callback.navigatePageFromList(position)
        }
    }

    private fun onItemLongClick(position: Int): Boolean {
        if (selectionMode != null) {
            return false
        }
        showItemMenu(
            instance.callback.getChildFragmentManager(), instance.chanName,
            this.adapter!!.getItem(position), instance.callback.isAllowNavigatePostManually(false)
        )
        return true
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED) {
            updateGridMetrics(newConfig)
        }
    }

    private fun updateGalleryItemChecked(view: View, position: Int) {
        val checked = callback.isItemChecked(position)
        val holder = recyclerView.getChildViewHolder(view) as GridAdapter.ViewHolder
        holder.selectorCheckDrawable.setSelected(checked, true)
    }

    private fun updateAllGalleryItemsChecked() {
        val childCount = recyclerView.getChildCount()
        for (i in 0..<childCount) {
            val view = recyclerView.getChildAt(i)
            val position = recyclerView.getChildAdapterPosition(view)
            updateGalleryItemChecked(view, position)
        }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        selected.clear()
        mode.setTitle(getColonString(instance.context.getResources(), R.string.selected, 0))
        menu.add(0, R.id.menu_select_all, 0, R.string.select_all)
            .setIcon(getActionBarIcon(instance.context, R.attr.iconActionSelectAll))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, R.id.menu_download, 0, R.string.download_files)
            .setIcon(getActionBarIcon(instance.context, R.attr.iconActionDownload))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == R.id.menu_select_all) {
            val count = this.adapter!!.getItemCount()
            for (i in 0..<count) {
                selected.put(i, i)
            }
            selectionMode!!.setTitle(
                getColonString(
                    instance.context.getResources(),
                    R.string.selected, count
                )
            )
            updateAllGalleryItemsChecked()
            return true
        } else if (switchItemId0 == R.id.menu_download) {
            val galleryItems = ArrayList<GalleryItem?>()
            val adapter = this.adapter
            for (i in 0..<adapter!!.getItemCount()) {
                if (callback.isItemChecked(i)) {
                    galleryItems.add(adapter.getItem(i))
                }
            }
            instance.callback.downloadGalleryItems(galleryItems)
            mode.finish()
            return true
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionMode = null
        selected.clear()
        updateAllGalleryItemsChecked()
    }

    private val actionBarHeight: Int
        get() {
            val typedArray =
                instance.context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
            try {
                return typedArray.getDimensionPixelSize(0, 0)
            } finally {
                typedArray.recycle()
            }
        }

    private fun updateGridMetrics(configuration: Configuration) {
        // Items count in row must fit to this inequality: (widthDp - (i + 1) * GRID_SPACING_DP) / i >= SIZE
        // Where SIZE - size of item in grid, i - items count in row, unknown quantity
        // The solution is: i <= (widthDp + GRID_SPACING_DP) / (SIZE + GRID_SPACING_DP)
        val widthDp = configuration.screenWidthDp
        val size = if (isTablet(configuration)) 160 else 100
        val spanCount: Int = (widthDp - GRID_SPACING_DP) / (size + GRID_SPACING_DP)
        (recyclerView.getLayoutManager() as GridLayoutManager).setSpanCount(spanCount)
    }

    private class GalleryRecyclerView(context: Context, private val spacing: Int) :
        PaddedRecyclerView(context) {
        private val rect = Rect()
        private val paint = Paint()

        init {
            setVerticalScrollBarEnabled(true)
        }

        override fun onDrawVerticalScrollBar(
            canvas: Canvas,
            scrollBar: Drawable,
            l: Int,
            t: Int,
            r: Int,
            b: Int
        ) {
            var t = t
            var b = b
            val spacing = this.spacing
            val thickness = (spacing * 2f / 3f + 0.5f).toInt()
            val rect = this.rect
            if (l == 0) {
                rect.left = 0
                rect.right = thickness
            } else if (r == getWidth()) {
                rect.left = r - thickness
                rect.right = r
            } else {
                return
            }
            if (b - t == getHeight()) {
                t += getEdgeEffectShift(EdgeEffectHandler.Side.TOP)
                b -= getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM)
            }
            t += spacing
            b -= spacing
            val range = computeVerticalScrollRange()
            if (range > 0) {
                val height = b - t
                val extent = computeVerticalScrollExtent()
                var length = (height * (extent.toFloat() / range) + 0.5f).toInt()
                length = max(length, 2 * spacing)
                val offset = ((height - length) * (computeVerticalScrollOffset().toFloat() /
                        (range - extent)) + 0.5f).toInt()
                if (length > 0) {
                    rect.top = t + offset
                    rect.bottom = t + offset + length
                    paint.setColor(
                        Color.argb(
                            0x7f * (scrollBar.getAlpha()) / 0xff,
                            0xff, 0xff, 0xff
                        )
                    )
                    canvas.drawRect(rect, paint)
                }
            }
        }
    }

    private class GridAdapter(
        private val callback: Callback,
        private val chanName: String?,
        private val galleryItems: MutableList<GalleryItem>
    ) : RecyclerView.Adapter<GridAdapter.ViewHolder?>() {
        interface Callback : ClickCallback<Void?, ViewHolder?> {
            fun isItemChecked(position: Int): Boolean
            fun onItemClick(view: View?, position: Int)
            fun onItemLongClick(position: Int): Boolean

            override fun onItemClick(
                holder: ViewHolder,
                position: Int,
                nothing: Void?,
                longClick: Boolean
            ): Boolean {
                if (longClick) {
                    return onItemLongClick(position)
                } else {
                    onItemClick(holder.itemView, position)
                    return true
                }
            }
        }

        private class ViewHolder(parent: ViewGroup, callback: Callback) :
            RecyclerView.ViewHolder(object : FrameLayout(parent.getContext()) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, widthMeasureSpec)
                }
            }) {
            val thumbnail: AttachmentView
            val attachmentInfo: TextView
            val selectorBorderDrawable: SelectorBorderDrawable?
            val selectorCheckDrawable: SelectorCheckDrawable

            init {
                val layout = itemView as FrameLayout
                layout.setLayoutParams(
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                )
                LayoutInflater.from(layout.getContext())
                    .inflate(R.layout.list_item_attachment, layout)
                val child = layout.getChildAt(0) as FrameLayout
                child.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT
                child.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT
                thumbnail = itemView.findViewById<AttachmentView>(R.id.thumbnail)
                thumbnail.setBackgroundColor(-0xcccccd)
                thumbnail.setCropEnabled(true)
                attachmentInfo = itemView.findViewById<TextView>(R.id.attachment_info)
                attachmentInfo.setBackgroundColor(-0x55eeeeef)
                attachmentInfo.setGravity(Gravity.CENTER)
                attachmentInfo.setSingleLine(true)
                attachmentInfo.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

                ListViewUtils.bind<Void?, ViewHolder>(
                    this,
                    itemView.findViewById<View?>(R.id.attachment_click),
                    true,
                    null,
                    callback
                )
                selectorBorderDrawable = null
                selectorCheckDrawable = SelectorCheckDrawable()
                child.setForeground(selectorCheckDrawable)
            }
        }

        private var enabled = false

        fun activate() {
            enabled = true
        }

        override fun getItemCount(): Int {
            return if (enabled) galleryItems.size else 0
        }

        fun getItem(position: Int): GalleryItem {
            return galleryItems.get(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(parent, callback)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val galleryItem = getItem(position)
            val chan = get(chanName)
            holder.attachmentInfo.setText(
                chan.util.StringUtils
                    .getFileExtension(galleryItem.getFileName(chan))
                    .uppercase(Locale.getDefault()) +
                        (if (galleryItem.size > 0) " " + StringUtils.formatFileSize(
                            galleryItem.size.toLong(),
                            true
                        ) else "")
            )
            val thumbnailUri = galleryItem.getThumbnailUri(chan)
            if (thumbnailUri != null) {
                val cacheManager: CacheManager = CacheManager.getInstance()
                val key = cacheManager.getCachedFileKey(thumbnailUri)
                holder.thumbnail.resetImage(key, AttachmentView.Overlay.NONE)
                ImageLoader.getInstance()
                    .loadImage(chan, thumbnailUri, key, false, holder.thumbnail)
            } else {
                holder.thumbnail.resetImage(null, AttachmentView.Overlay.WARNING)
                ImageLoader.getInstance().cancel(holder.thumbnail)
            }
            val checked = callback.isItemChecked(position)
            holder.selectorCheckDrawable.setSelected(checked, false)
        }
    }

    private class SpacingItemDecoration(private val spacing: Int) : ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val column = (view.getLayoutParams() as GridLayoutManager.LayoutParams).getSpanIndex()
            val columns = (parent.getLayoutManager() as GridLayoutManager).getSpanCount()
            val left: Int
            val right: Int
            if (columns >= 2) {
                val total = (columns + 1) * spacing
                val average = total.toFloat() / columns
                left = lerp(
                    spacing.toFloat(),
                    average - spacing,
                    column.toFloat() / (columns - 1)
                ).toInt()
                right = average.toInt() - left
            } else {
                left = spacing
                right = spacing
            }
            val firstRow = position - column == 0
            outRect.set(left, if (firstRow) spacing else 0, right, spacing)
        }
    }

    companion object {
        private const val GRID_SPACING_DP = 4

        private const val GRID_SCALE = 1.1f

        private fun showItemMenu(
            fragmentManager: FragmentManager,
            chanName: String?, galleryItem: GalleryItem, allowNavigatePostManually: Boolean
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    Companion.createItemMenu(
                        provider!!,
                        chanName, galleryItem, allowNavigatePostManually
                    )
                })
        }

        private fun createItemMenu(
            provider: InstanceDialog.Provider,
            chanName: String?, galleryItem: GalleryItem, allowNavigatePostManually: Boolean
        ): AlertDialog {
            val callback = getCallback(provider)
            val context = callback.getWindow().getContext()
            val chan = get(chanName)
            val dialogMenu = DialogMenu(context)
            dialogMenu.setTitle(
                if (!StringUtils.isEmpty(galleryItem.originalName))
                    galleryItem.originalName
                else
                    galleryItem.getFileName(chan)
            )
            dialogMenu.add(
                R.string.download_file,
                Runnable { callback.downloadGalleryItem(galleryItem) })
            if (galleryItem.getDisplayImageUri(chan) != null) {
                dialogMenu.add(R.string.search_image, Runnable {
                    SearchImageDialog(
                        chanName,
                        galleryItem.getDisplayImageUri(chan)
                    ).show(provider.fragmentManager, null)
                })
            }
            dialogMenu.add(R.string.copy_link, Runnable {
                StringUtils.copyToClipboard(
                    context,
                    galleryItem.getFileUri(chan).toString()
                )
            })
            if (allowNavigatePostManually && galleryItem.postNumber != null) {
                dialogMenu.add(
                    R.string.go_to_post,
                    Runnable { callback.navigatePost(galleryItem, true, true) })
            }
            dialogMenu.add(R.string.share_link, Runnable {
                NavigationUtils.shareLink(
                    context, null,
                    galleryItem.getFileUri(chan)!!
                )
            })
            return dialogMenu.create()
        }
    }
}
