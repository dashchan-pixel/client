package com.mishiranu.dashchan.ui.gallery

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.media.VideoPlayer
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.widget.ClickableToast
import kotlin.math.abs

/**
 * A full-screen, vertically-swiped feed of a thread's video attachments — reels/TikTok-style.
 * Each page auto-plays and loops the video; only the centered page plays. Launched from the
 * thread menu's "Flow" item next to "Gallery".
 */
class FlowDialog :
    DialogFragment(),
    FlowVideoView.Callback {
    /** Retains the (non-Parcelable) item list across configuration changes. */
    class FlowViewModel : ViewModel() {
        var chan: Chan? = null

        /** The full gallery attachment list (images + videos), used to switch back to the gallery. */
        var allItems: List<GalleryItem>? = null

        /** The video-only subset actually shown in the feed; unplayable videos are removed. */
        var items: MutableList<GalleryItem>? = null
        var threadTitle: String? = null

        /** Index within [items] to open at; -1 means the first video. */
        var startIndex = -1

        /** One-shot playback position restore for [startSeekItem] (PiP -> fullscreen handback). */
        var startSeekItem: GalleryItem? = null
        var startPosition = 0L
    }

    private lateinit var viewModel: FlowViewModel
    private var recyclerView: RecyclerView? = null
    private val snapHelper = PagerSnapHelper()
    private var currentPosition = -1
    private var bottomInset = 0

    /**
     * Rotation of the virtual-position -> item mapping: page [position] shows
     * items[(position + itemsOffset) mod size]. Re-anchored when an unplayable video is
     * removed so the pages currently laid out keep their items and playback is undisturbed.
     */
    private var itemsOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        viewModel = ViewModelProvider(this).get(FlowViewModel::class.java)
        if (viewModel.items == null) {
            viewModel.chan = pendingChan
            viewModel.allItems = pendingAllItems
            viewModel.items = pendingItems
            viewModel.threadTitle = pendingThreadTitle
            viewModel.startIndex = pendingItems?.indexOfFirst { it === pendingStartItem } ?: -1
            viewModel.startSeekItem = if (pendingStartPosition > 0) pendingStartItem else null
            viewModel.startPosition = pendingStartPosition
        }
        pendingChan = null
        pendingAllItems = null
        pendingItems = null
        pendingStartItem = null
        pendingThreadTitle = null
        pendingStartPosition = 0
        if (viewModel.items == null) {
            // Handoff lost (e.g. process death) — nothing to show.
            dismiss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Warm ExoPlayer instances so the first pages skip player construction.
        VideoPlayer.prewarm(3)
        val recyclerView = RecyclerView(requireContext())
        this.recyclerView = recyclerView
        recyclerView.setBackgroundColor(Color.BLACK)
        recyclerView.layoutManager =
            object : LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false) {
                // Lay out (and thus bind and pre-buffer) one full page beyond each edge, so the
                // neighbouring videos are downloaded and their decoders readied before the swipe.
                override fun calculateExtraLayoutSpace(
                    state: RecyclerView.State,
                    extraLayoutSpace: IntArray,
                ) {
                    extraLayoutSpace[0] = height
                    extraLayoutSpace[1] = height
                }
            }
        recyclerView.setHasFixedSize(true)
        // Keep neighbours bound so the next page can be pre-buffered.
        recyclerView.setItemViewCacheSize(2)
        snapHelper.attachToRecyclerView(recyclerView)
        // Keep the control bar clear of the gesture nav bar: propagate the bottom inset to pages.
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            for (i in 0 until (view as RecyclerView).childCount) {
                (view.getChildAt(i) as? FlowVideoView)?.setBottomInset(bottomInset)
            }
            insets
        }
        // Pause any page as soon as it leaves the screen. Necessary because RecyclerView keeps
        // recently-detached views in its cache (without recycling them), so their players would
        // otherwise keep playing off-screen.
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {}

                override fun onChildViewDetachedFromWindow(view: View) {
                    (view as? FlowVideoView)?.setActive(false)
                }
            },
        )
        val chan = viewModel.chan
        val items = viewModel.items
        if (chan != null && items != null) {
            recyclerView.adapter = Adapter(chan, items)
            // Track the centered page continuously while scrolling (like the gallery pager),
            // rather than only when the scroll settles.
            recyclerView.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        rv: RecyclerView,
                        dx: Int,
                        dy: Int,
                    ) {
                        updateActive(findCenterPosition(rv))
                    }
                },
            )
            // Start in the middle of the virtual (looping) range, offset to the requested video,
            // so swipes wrap both ways and we open at the same attachment the gallery was showing.
            val startIndex = viewModel.startIndex.coerceIn(0, items.size - 1)
            val startPosition =
                if (items.size > 1) {
                    LOOP_COUNT / 2 - LOOP_COUNT / 2 % items.size + startIndex
                } else {
                    0
                }
            recyclerView.scrollToPosition(startPosition)
            recyclerView.post { if (currentPosition < 0) updateActive(startPosition) }
        }
        return recyclerView
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            // Draw edge-to-edge so the bottom inset is reported and the controls can clear the nav bar.
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
    }

    override fun onResume() {
        super.onResume()
        currentHolder()?.videoView?.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        currentHolder()?.videoView?.setActive(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Releases every player: setting a null adapter recycles all live holders.
        recyclerView?.adapter = null
        recyclerView = null
    }

    private fun currentHolder(): Holder? = recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? Holder

    /** The adapter position of the page whose centre is nearest the viewport centre. */
    private fun findCenterPosition(recyclerView: RecyclerView): Int {
        val center = recyclerView.height / 2
        var best = RecyclerView.NO_POSITION
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val distance = abs((child.top + child.bottom) / 2 - center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = recyclerView.getChildAdapterPosition(child)
            }
        }
        return best
    }

    private fun updateActive(position: Int) {
        if (position == RecyclerView.NO_POSITION || position == currentPosition) {
            return
        }
        val recyclerView = recyclerView ?: return
        (recyclerView.findViewHolderForAdapterPosition(currentPosition) as? Holder)?.videoView?.setActive(false)
        currentPosition = position
        (recyclerView.findViewHolderForAdapterPosition(position) as? Holder)?.videoView?.setActive(true)
        // Pre-buffer the adjacent pages so the next swipe plays instantly.
        preload(position + 1)
        preload(position - 1)
    }

    private fun itemAt(position: Int): GalleryItem? {
        val items = viewModel.items ?: return null
        return if (position >= 0 && items.isNotEmpty()) items[(position + itemsOffset).mod(items.size)] else null
    }

    private fun preload(position: Int) {
        if (position < 0) {
            return
        }
        (recyclerView?.findViewHolderForAdapterPosition(position) as? Holder)?.videoView?.prepare()
    }

    private inner class Adapter(
        private val chan: Chan,
        private val items: List<GalleryItem>,
    ) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val videoView = FlowVideoView(parent.context)
            videoView.layoutParams =
                RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT)
            return Holder(videoView)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val item = itemAt(position) ?: return
            holder.videoView.bind(chan, item, this@FlowDialog)
            holder.videoView.setBottomInset(bottomInset)
            if (position == currentPosition) {
                holder.videoView.setActive(true)
            } else if (position == currentPosition + 1 || position == currentPosition - 1) {
                // This neighbour bound after the active page settled (e.g. via prefetch) — buffer it.
                holder.videoView.prepare()
            }
        }

        // A large virtual count makes swiping wrap around (loop navigation) for multi-video threads.
        override fun getItemCount(): Int = if (items.size > 1) LOOP_COUNT else items.size

        override fun onViewRecycled(holder: Holder) {
            holder.videoView.recycle()
        }
    }

    private class Holder(
        val videoView: FlowVideoView,
    ) : RecyclerView.ViewHolder(videoView)

    // FlowVideoView.Callback

    override fun onGoToPost(galleryItem: GalleryItem) {
        val chan = viewModel.chan ?: return
        (activity as? FragmentHandler)?.scrollToPost(
            chan.name,
            galleryItem.boardName,
            galleryItem.threadNumber,
            galleryItem.postNumber,
            true,
        )
        dismiss()
    }

    override fun getDownloadBinder(): DownloadService.Binder? = (activity as? FragmentHandler)?.getDownloadBinder()

    override fun getThreadTitle(): String? = viewModel.threadTitle

    override fun onSwitchToGallery(galleryItem: GalleryItem) {
        val allItems = viewModel.allItems ?: return
        val chan = viewModel.chan ?: return
        val index = allItems.indexOfFirst { it === galleryItem }
        if (index < 0) {
            return
        }
        // Open the regular gallery in the activity's fragment manager (where it natively lives),
        // at the same attachment, then dismiss this feed so no player keeps running.
        val fragmentManager = requireActivity().supportFragmentManager
        (fragmentManager.findFragmentByTag(GALLERY_TAG) as? GalleryOverlay)?.dismiss()
        val overlay =
            GalleryOverlay(
                chan.name,
                ArrayList(allItems),
                index,
                viewModel.threadTitle,
                null,
                GalleryOverlay.NavigatePostMode.ENABLED,
                false,
            )
        overlay.show(fragmentManager, GALLERY_TAG)
        dismiss()
    }

    override fun onEnterPip(
        view: FlowVideoView,
        galleryItem: GalleryItem,
    ) {
        val chan = viewModel.chan ?: return
        // Silence this page before the floating player takes over, then close the feed so the
        // thread is visible behind the picture-in-picture window and nothing else keeps playing.
        // The feed's video list goes along so the floating player keeps advancing through it;
        // the full attachment list lets an expanded window restore this feed later.
        view.setActive(false)
        VideoPipActivity.start(
            requireActivity(),
            chan,
            galleryItem,
            viewModel.items?.toList(),
            viewModel.allItems,
            null,
            viewModel.threadTitle,
            view.playbackPosition(),
            true,
            view.videoDimensions(),
        )
        dismiss()
    }

    override fun onVideoReady(view: FlowVideoView) {
        // Restore the playback position once for the page the PiP window handed back.
        val startSeekItem = viewModel.startSeekItem ?: return
        if (view.boundGalleryItem() === startSeekItem) {
            viewModel.startSeekItem = null
            if (viewModel.startPosition > 0) {
                view.seekTo(viewModel.startPosition)
            }
        }
    }

    override fun onVideoFailed(
        view: FlowVideoView,
        galleryItem: GalleryItem,
    ) {
        val items = viewModel.items ?: return
        val index = items.indexOfFirst { it === galleryItem }
        if (index < 0) {
            // Already removed (the failure was reported through more than one path).
            return
        }
        if (items.size <= 1) {
            // The only remaining video is unplayable - nothing left to show.
            ClickableToast.show(R.string.playback_error)
            dismiss()
            return
        }
        val recyclerView = recyclerView ?: return
        val adapter = recyclerView.adapter ?: return
        val windowStart = currentPosition - REBIND_WINDOW
        val windowEnd = currentPosition + REBIND_WINDOW
        val oldWindowItems = (windowStart..windowEnd).map { itemAt(it) }
        val currentItem = itemAt(currentPosition)
        items.removeAt(index)
        val newSize = items.size
        if (newSize == 1 || currentPosition < 0) {
            // The looping virtual range collapses (or nothing is centered yet): full reset.
            itemsOffset = 0
            currentPosition = -1
            adapter.notifyDataSetChanged()
            val position = if (newSize > 1) LOOP_COUNT / 2 - LOOP_COUNT / 2 % newSize else 0
            recyclerView.scrollToPosition(position)
            recyclerView.post { if (currentPosition < 0) updateActive(position) }
            return
        }
        // Re-anchor the mapping at the current page: to the current video if it survived,
        // otherwise to the video that followed the removed one, which then takes over the
        // current page in place. Pages around the anchor keep their items (a contiguous
        // arc that avoids the removed index stays contiguous), so playback is undisturbed.
        val anchorNewIndex =
            if (currentItem === galleryItem) {
                index % newSize
            } else {
                items.indexOfFirst { it === currentItem }.coerceAtLeast(0)
            }
        itemsOffset = (anchorNewIndex - currentPosition).mod(newSize)
        // Everything outside the laid-out window is at most in the view cache: invalidate
        // it wholesale so stale pages rebind when reused.
        if (windowStart > 0) {
            adapter.notifyItemRangeChanged(0, windowStart)
        }
        adapter.notifyItemRangeChanged(windowEnd + 1, adapter.itemCount - windowEnd - 1)
        // Inside the window, rebind only pages whose video actually changed.
        (windowStart..windowEnd).forEachIndexed { i, position ->
            if (position >= 0 && itemAt(position) !== oldWindowItems[i]) {
                adapter.notifyItemChanged(position)
            }
        }
    }

    override fun onVideoEnded(view: FlowVideoView) {
        val recyclerView = recyclerView ?: return
        val size = viewModel.items?.size ?: 0
        if (size <= 1) {
            // Nothing to advance to — loop the single clip.
            view.replay()
        } else if (currentHolder()?.videoView === view) {
            // Advance to the next page (wraps around thanks to the looping adapter).
            recyclerView.smoothScrollToPosition(currentPosition + 1)
        }
    }

    companion object {
        private val TAG = FlowDialog::class.java.name
        private val GALLERY_TAG = GalleryOverlay::class.java.name
        private const val LOOP_COUNT = 1_000_000

        // Pages within this distance of the current one may be laid out or cached with a
        // live player; on removal they are rebound only if their video actually changed.
        private const val REBIND_WINDOW = 2
        private var pendingChan: Chan? = null
        private var pendingAllItems: List<GalleryItem>? = null
        private var pendingItems: MutableList<GalleryItem>? = null
        private var pendingStartItem: GalleryItem? = null
        private var pendingThreadTitle: String? = null
        private var pendingStartPosition = 0L

        /**
         * Open the video feed for [galleryItems] (the full gallery attachment list; only videos are shown),
         * starting at [startItem] if given, [startPosition] ms into it (a PiP window handing back).
         * Shows a toast and does nothing if the thread has no videos.
         */
        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            chan: Chan,
            galleryItems: List<GalleryItem>,
            startItem: GalleryItem?,
            threadTitle: String?,
            startPosition: Long = 0,
        ) {
            val videoItems = galleryItems.filterTo(mutableListOf()) { it.isVideo(chan) }
            if (videoItems.isEmpty()) {
                ClickableToast.show(R.string.no_video_attachments)
                return
            }
            (fragmentManager.findFragmentByTag(TAG) as? FlowDialog)?.dismiss()
            pendingChan = chan
            pendingAllItems = galleryItems
            pendingItems = videoItems
            pendingStartItem = startItem
            pendingThreadTitle = threadTitle
            pendingStartPosition = if (startItem != null) startPosition else 0
            FlowDialog().show(fragmentManager, TAG)
        }
    }
}
