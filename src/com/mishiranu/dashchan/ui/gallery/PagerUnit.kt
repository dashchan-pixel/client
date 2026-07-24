package com.mishiranu.dashchan.ui.gallery

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.util.Consumer
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.util.StringUtils
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.FileUriClipboard
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.isCutThumbnails
import com.mishiranu.dashchan.content.Preferences.isVideoPlayAfterScroll
import com.mishiranu.dashchan.content.Preferences.loadThumbnails
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.graphics.SimpleBitmapDrawable
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.SearchImageDialog
import com.mishiranu.dashchan.ui.gallery.PagerInstance.MediaSummary
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.CircularProgressBar
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.InsetsLayout
import com.mishiranu.dashchan.widget.PhotoView
import com.mishiranu.dashchan.widget.PhotoViewPager
import com.mishiranu.dashchan.widget.ViewFactory.createErrorLayout
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PagerUnit(
    private val galleryInstance: GalleryInstance,
) : PagerInstance.Callback {
    private val pagerInstance: PagerInstance

    private val imageUnit: ImageUnit
    private val videoUnit: VideoUnit

    private val viewPagerParent: FrameLayout
    private val viewPager: PhotoViewPager
    private val pagerAdapter: PagerAdapter

    class PagerUnitViewModel : ViewModel() {
        internal var pagerUnit: WeakReference<PagerUnit>? = null
    }

    val view: View
        get() = viewPagerParent

    fun addAndInitViews(
        frameLayout: FrameLayout,
        initialPosition: Int,
    ) {
        videoUnit.addViews(frameLayout)
        viewPager.setCurrentIndex(max(initialPosition, 0))
    }

    fun onViewsCreated(imageViewPosition: IntArray?) {
        if (!galleryInstance.callback.isGalleryWindow() && imageViewPosition != null) {
            val view = viewPager.currentView
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val holder = view.getTag() as PagerInstance.ViewHolder
                val photoView = holder.photoView
                if (photoView.hasImage()) {
                    photoView.setInitialScaleAnimationData(
                        imageViewPosition,
                        isCutThumbnails,
                    )
                }
            }
        }
    }

    private var resumed = false

    fun onResume() {
        resumed = true
        videoUnit.onResume()
    }

    fun onPause() {
        resumed = false
        videoUnit.onPause()
    }

    val currentIndex: Int
        get() = viewPager.getCurrentIndex()

    val isVideoPlaying: Boolean
        // A not-yet-initialized video reports "playing": the handed-off player should start
        get() = !videoUnit.isInitialized || videoUnit.isPlaying

    val videoPosition: Long
        get() = videoUnit.playbackPosition

    val videoDimensions: Point?
        get() = videoUnit.videoDimensions

    val videoSpeed: Float
        get() = videoUnit.currentPlaybackSpeed

    /** One-shot start position and speed for the next video that initializes (PiP handing back).  */
    fun setInitialVideoSeek(
        position: Long,
        speed: Float,
    ) {
        videoUnit.setInitialSeekPosition(position, speed)
    }

    fun onApplyWindowInsets(insets: InsetsLayout.Insets) {
        videoUnit.onApplyWindowInsets(insets.left, insets.right, insets.bottom)
    }

    fun invalidateControlsVisibility() {
        videoUnit.invalidateControlsVisibility()
    }

    fun onBackToGallery() {
        videoUnit.showHideVideoView(false)
    }

    private var galleryMode = false
    private var hasFocus = true

    private fun updateActive() {
        viewPager.setActive(!galleryMode && hasFocus)
    }

    fun setHasFocus(hasFocus: Boolean) {
        this.hasFocus = hasFocus
        updateActive()
    }

    fun switchMode(
        galleryMode: Boolean,
        duration: Int,
    ) {
        this.galleryMode = galleryMode
        updateActive()
        if (galleryMode) {
            interrupt(true)
            pagerInstance.leftHolder = null
            pagerInstance.currentHolder = null
            pagerInstance.rightHolder = null
            if (duration > 0) {
                viewPager.setAlpha(1f)
                viewPager.setScaleX(1f)
                viewPager.setScaleY(1f)
                viewPager
                    .animate()
                    .alpha(0f)
                    .scaleX(PAGER_SCALE)
                    .scaleY(PAGER_SCALE)
                    .setDuration(duration.toLong())
                    .setListener(AnimationUtils.VisibilityListener(viewPager, View.GONE))
                    .start()
            } else {
                viewPager.setVisibility(View.GONE)
            }
        } else {
            viewPager.setVisibility(View.VISIBLE)
            if (duration > 0) {
                viewPager.setAlpha(0f)
                viewPager.setScaleX(PAGER_SCALE)
                viewPager.setScaleY(PAGER_SCALE)
                viewPager
                    .animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration.toLong())
                    .setListener(null)
                    .start()
            }
        }
    }

    fun navigatePageFromList(
        position: Int,
        duration: Int,
    ) {
        pagerAdapter.setWaitBeforeNextVideo(duration)
        viewPager.setCurrentIndex(position)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        videoUnit.onConfigurationChanged(newConfig)
    }

    fun refreshCurrent() {
        if (pagerInstance.currentHolder != null) {
            loadImageVideo(true, false, 0)
        }
    }

    class OptionsMenuCapabilities(
        val available: Boolean,
        val save: Boolean,
        val refresh: Boolean,
        val viewMetadata: Boolean,
        val searchImage: Boolean,
        val copyImage: Boolean,
        val navigatePost: Boolean,
        val shareFile: Boolean,
    )

    fun obtainOptionsMenuCapabilities(): OptionsMenuCapabilities {
        val holder = pagerInstance.currentHolder
        var available = false
        var save = false
        var refresh = false
        var viewMetadata = false
        var searchImage = false
        var copyImage = false
        var navigatePost = false
        var shareFile = false
        if (holder != null) {
            available = true
            val galleryItem = holder.galleryItem!!
            val chan = get(galleryInstance.chanName)
            val isVideo = galleryItem.isVideo(chan)
            val isOpenableVideo = isVideo && galleryItem.isOpenableVideo(chan)
            val isVideoInitialized = isOpenableVideo && videoUnit.isInitialized
            val imageHasMetadata = imageUnit.hasMetadata()
            save = holder.loadState == PagerInstance.LoadState.COMPLETE ||
                isVideo &&
                (!isOpenableVideo || holder.loadState == PagerInstance.LoadState.ERROR)
            refresh =
                !isVideo ||
                isVideoInitialized ||
                holder.loadState == PagerInstance.LoadState.ERROR
            viewMetadata = isVideoInitialized || imageHasMetadata
            searchImage = galleryItem.getDisplayImageUri(chan) != null
            copyImage =
                galleryItem.isImage(chan) &&
                holder.loadState == PagerInstance.LoadState.COMPLETE
            navigatePost = galleryItem.postNumber != null
            shareFile = holder.loadState == PagerInstance.LoadState.COMPLETE
        }
        return OptionsMenuCapabilities(
            available,
            save,
            refresh,
            viewMetadata,
            searchImage,
            copyImage,
            navigatePost,
            shareFile,
        )
    }

    val currentHolder: PagerInstance.ViewHolder?
        get() = pagerInstance.currentHolder

    private fun interrupt(force: Boolean) {
        imageUnit.interrupt(force)
        videoUnit.interrupt()
    }

    fun onFinish() {
        val holders =
            arrayOf<PagerInstance.ViewHolder?>(
                pagerInstance.leftHolder,
                pagerInstance.currentHolder,
                pagerInstance.rightHolder,
            )
        for (holder in holders) {
            val thumbnailTarget = holder?.thumbnailTarget
            if (thumbnailTarget != null) {
                ImageLoader.getInstance().cancel(thumbnailTarget)
            }
        }
        interrupt(true)
        viewPager.postDelayed(
            Runnable {
                pagerAdapter.recycleAll()
                System.gc()
            },
            200,
        )
    }

    private fun loadImageVideo(
        reload: Boolean,
        mayShowThumbnailOnly: Boolean,
        waitBeforeVideo: Int,
    ) {
        val holder = pagerInstance.currentHolder
        if (holder == null) {
            return
        }
        val chan = get(galleryInstance.chanName)
        interrupt(false)
        holder.loadState = PagerInstance.LoadState.PREVIEW_OR_LOADING
        galleryInstance.callback.invalidateOptionsMenu()
        val cacheManager: CacheManager = CacheManager.getInstance()
        if (!cacheManager.isCacheAvailable) {
            showError(holder, galleryInstance.context.getString(R.string.cache_is_unavailable))
            return
        }
        galleryInstance.callback.modifySystemUiVisibility(GalleryInstance.Flags.LOCKED_ERROR, false)
        holder.errorHolder.layout.setVisibility(View.GONE)
        var thumbnailReady = holder.photoViewThumbnail
        if (!thumbnailReady) {
            holder.recyclePhotoView()
            thumbnailReady = presetThumbnail(holder, reload)
        }
        val galleryItem = holder.galleryItem!!
        val playButton = holder.playButton
        val photoView = holder.photoView
        val isImage = galleryItem.isImage(chan)
        val isVideo = galleryItem.isVideo(chan)
        val isOpenableVideo = isVideo && galleryItem.isOpenableVideo(chan)
        if (waitBeforeVideo > 0 && thumbnailReady && isOpenableVideo && !mayShowThumbnailOnly) {
            viewPagerParent.postDelayed(
                Runnable { loadImageVideo(reload, false, 0) },
                waitBeforeVideo.toLong(),
            )
            return
        }
        if (isVideo && !isOpenableVideo || isOpenableVideo && mayShowThumbnailOnly) {
            playButton.setVisibility(View.VISIBLE)
            photoView.setDrawDimForCurrentImage(true)
            return
        } else {
            playButton.setVisibility(View.GONE)
            photoView.setDrawDimForCurrentImage(false)
        }
        playButton.setVisibility(View.GONE)
        val uri = galleryItem.getFileUri(chan)
        try {
            val cachedFile = cacheManager.getMediaFileOrThrow(uri, true)
            if (isImage) {
                imageUnit.applyImage(uri!!, cachedFile, reload)
            } else if (isVideo) {
                imageUnit.interrupt(true)
                videoUnit.applyVideo(uri!!, cachedFile, reload)
            }
        } catch (e: CacheManager.CacheException) {
            showError(holder, e.message)
        }
    }

    private class PageTarget(
        galleryInstance: GalleryInstance?,
        holder: PagerInstance.ViewHolder?,
    ) : ImageLoader.Target() {
        val galleryInstance: WeakReference<GalleryInstance?>
        val holder: WeakReference<PagerInstance.ViewHolder?>

        var awaitImmediate: Boolean = false
        var keepScale: Boolean = false

        init {
            this.galleryInstance = WeakReference<GalleryInstance?>(galleryInstance)
            this.holder = WeakReference<PagerInstance.ViewHolder?>(holder)
        }

        public override fun onResult(
            key: String?,
            bitmap: Bitmap?,
            error: Boolean,
            instantly: Boolean,
        ) {
            val galleryInstance = this.galleryInstance.get()
            val holder = this.holder.get()
            if (galleryInstance != null && holder != null && bitmap != null) {
                val chan = get(galleryInstance.chanName)
                val photoView = holder.photoView
                val galleryItem = holder.galleryItem
                val setImage =
                    awaitImmediate ||
                        galleryItem != null &&
                        !photoView.hasImage() &&
                        key ==
                        CacheManager.getInstance().getCachedFileKey(
                            galleryItem.getThumbnailUri(chan),
                        )
                if (setImage) {
                    val currentItem = holder.galleryItem!!
                    holder.recyclePhotoView()
                    val simpleBitmapDrawable =
                        SimpleBitmapDrawable(
                            bitmap,
                            currentItem.width,
                            currentItem.height,
                            false,
                        )
                    holder.simpleBitmapDrawable = simpleBitmapDrawable
                    val fitScreen = currentItem.isVideo(chan)
                    val keepScale = this.keepScale && !fitScreen
                    photoView.setImage(
                        simpleBitmapDrawable,
                        bitmap.hasAlpha(),
                        fitScreen,
                        keepScale,
                    )
                    holder.photoViewThumbnail = true
                }
            }
        }
    }

    private fun presetThumbnail(
        holder: PagerInstance.ViewHolder,
        keepScale: Boolean,
    ): Boolean {
        var target: PageTarget? = holder.thumbnailTarget as PageTarget?
        if (target == null) {
            target = PageTarget(galleryInstance, holder)
            holder.thumbnailTarget = target
        }
        val galleryItem = holder.galleryItem
        if (galleryItem == null) {
            return false
        }
        val chan = get(galleryInstance.chanName)
        val uri = galleryItem.getThumbnailUri(chan)
        if (uri != null && galleryItem.width > 0 && galleryItem.height > 0) {
            target.awaitImmediate = true
            target.keepScale = keepScale
            try {
                val allowLoad =
                    galleryInstance.callback.isGalleryWindow() ||
                        loadThumbnails.isNetworkAvailable(getInstance())
                return ImageLoader.getInstance().loadImage(chan, uri, null, !allowLoad, target)
            } finally {
                target.awaitImmediate = false
                target.keepScale = false
            }
        }
        return false
    }

    override fun showError(
        holder: PagerInstance.ViewHolder,
        message: String?,
    ) {
        if (holder == pagerInstance.currentHolder) {
            galleryInstance.callback.modifySystemUiVisibility(
                GalleryInstance.Flags.LOCKED_ERROR,
                true,
            )
            holder.photoView.clearInitialScaleAnimationData()
            holder.recyclePhotoView()
            interrupt(false)
            val errorHolder = holder.errorHolder
            errorHolder.layout.setVisibility(View.VISIBLE)
            errorHolder.text.setText(
                if (!isEmpty(message)) {
                    message
                } else {
                    galleryInstance.context.getString(R.string.unknown_error)
                },
            )
            holder.progressBar.cancelVisibilityTransient()
            holder.loadState = PagerInstance.LoadState.ERROR
            galleryInstance.callback.invalidateOptionsMenu()
        }
    }

    private val photoViewListener: PhotoView.Listener =
        object : PhotoView.Listener {
            override fun onClick(
                photoView: PhotoView?,
                image: Boolean,
                x: Float,
                y: Float,
            ) {
                val holder = pagerInstance.currentHolder!!
                val galleryItem = holder.galleryItem
                val chan = get(galleryInstance.chanName)
                val playButton = holder.playButton
                if (playButton.getVisibility() == View.VISIBLE &&
                    galleryItem!!.isVideo(chan) &&
                    !videoUnit.isCreated
                ) {
                    val centerX = playButton.getLeft() + playButton.getWidth() / 2
                    val centerY = playButton.getTop() + playButton.getHeight() / 2
                    val size = min(playButton.getWidth(), playButton.getHeight())
                    val distance =
                        sqrt(((centerX - x) * (centerX - x) + (centerY - y) * (centerY - y)).toDouble()).toFloat()
                    if (distance <= size / 3f * 2f) {
                        if (!galleryItem.isOpenableVideo(chan)) {
                            NavigationUtils.handleUri(
                                galleryInstance.callback.getWindow()!!.getContext(),
                                galleryInstance.chanName,
                                galleryItem.getFileUri(chan)!!,
                                NavigationUtils.BrowserType.EXTERNAL,
                            )
                        } else {
                            loadImageVideo(false, false, 0)
                        }
                        return
                    }
                }
                if (image) {
                    galleryInstance.callback.toggleSystemUIVisibility(GalleryInstance.Flags.LOCKED_USER)
                } else {
                    galleryInstance.callback.navigateGalleryOrFinish(false)
                }
            }

            override fun onLongClick(
                photoView: PhotoView?,
                x: Float,
                y: Float,
            ) {
                displayPopupMenu(galleryInstance.callback.getChildFragmentManager())
            }

            override fun onVideoSeekTap(
                photoView: PhotoView?,
                x: Float,
                width: Int,
            ): Boolean = videoUnit.handleSeekTap(x, width)

            private var swiping = false

            override fun onVerticalSwipe(
                photoView: PhotoView?,
                down: Boolean,
                value: Float,
            ) {
                val swiping = value != 0f
                if (this.swiping != swiping) {
                    this.swiping = swiping
                    videoUnit.handleSwipingContent(swiping, true)
                }
                galleryInstance.callback.modifyVerticalSwipeState(down, value)
            }

            override fun onClose(
                photoView: PhotoView?,
                down: Boolean,
            ): Boolean {
                galleryInstance.callback.navigateGalleryOrFinish(down)
                return true
            }
        }

    private class PlayShape : Shape() {
        private val path = Path()

        override fun draw(
            canvas: Canvas,
            paint: Paint,
        ) {
            val width = getWidth()
            val height = getHeight()
            val size = min(width, height)
            val radius = (size * 38f / 48f / 2f + 0.5f).toInt()
            paint.setStrokeWidth(size / 48f * 4f)
            paint.setStyle(Paint.Style.STROKE)
            paint.setColor(Color.WHITE)
            canvas.drawCircle(width / 2f, height / 2f, radius.toFloat(), paint)
            paint.setStyle(Paint.Style.FILL)
            val path = this.path
            val side = size / 48f * 16f
            val altitude = (side * sqrt(3.0) / 2f).toFloat()
            path.moveTo(width / 2f + altitude * 2f / 3f, height / 2f)
            path.lineTo(width / 2f - altitude / 3f, height / 2f - side / 2f)
            path.lineTo(width / 2f - altitude / 3f, height / 2f + side / 2f)
            path.close()
            canvas.drawPath(path, paint)
            path.rewind()
        }
    }

    private inner class PagerAdapter(
        private val galleryItems: List<GalleryItem>,
    ) : PhotoViewPager.Adapter {
        private var waitBeforeVideo = 0

        fun setWaitBeforeNextVideo(waitBeforeVideo: Int) {
            this.waitBeforeVideo = waitBeforeVideo
        }

        override fun onCreateView(parent: ViewGroup?): View {
            val view =
                LayoutInflater
                    .from(galleryInstance.context)
                    .inflate(R.layout.list_item_gallery, parent, false) as FrameLayout
            val holder = PagerInstance.ViewHolder()
            val photoView = view.findViewById<PhotoView>(R.id.photo_view)
            val errorHolder = createErrorLayout(view)
            val playButton = view.findViewById<View>(R.id.play)
            holder.photoView = photoView
            holder.surfaceParent = view.findViewById<FrameLayout>(R.id.surface_parent)
            holder.errorHolder = errorHolder
            holder.progressBar = view.findViewById<CircularProgressBar>(android.R.id.progress)
            holder.playButton = playButton
            playButton.setBackground(ShapeDrawable(PlayShape()))
            photoView.setListener(photoViewListener)
            view.addView(errorHolder.layout)
            view.setTag(holder)
            return view
        }

        override fun getPhotoView(view: View): PhotoView? = (view.getTag() as PagerInstance.ViewHolder).photoView

        fun applySideViewData(
            holder: PagerInstance.ViewHolder,
            index: Int,
            active: Boolean,
        ) {
            val galleryItem = galleryItems[index]
            holder.playButton.setVisibility(View.GONE)
            holder.errorHolder.layout.setVisibility(View.GONE)
            if (!active) {
                holder.progressBar.setVisible(false, true)
            }
            var hasValidImage =
                holder.galleryItem == galleryItem &&
                    holder.loadState == PagerInstance.LoadState.COMPLETE &&
                    !galleryItem.isVideo(
                        get(galleryInstance.chanName),
                    )
            if (hasValidImage) {
                if (holder.animatedImageDecoder != null) {
                    holder.recyclePhotoView()
                    hasValidImage = false
                } else {
                    holder.decoderDrawable?.setEnabled(active)
                    holder.photoView.resetScale()
                }
            }
            if (!hasValidImage) {
                holder.loadState = PagerInstance.LoadState.PREVIEW_OR_LOADING
                holder.galleryItem = galleryItem
                holder.mediaSummary = MediaSummary(galleryItem)
                val success = presetThumbnail(holder, false)
                if (!success) {
                    holder.recyclePhotoView()
                }
            }
        }

        private var previousIndex = -1

        override fun onPositionChange(
            view: PhotoViewPager?,
            index: Int,
            centerView: View,
            leftView: View?,
            rightView: View?,
            manually: Boolean,
        ) {
            val mayShowThumbnailOnly = !manually && !isVideoPlayAfterScroll
            val holder = centerView.getTag() as PagerInstance.ViewHolder
            if (index < previousIndex) {
                pagerInstance.scrollingLeft = true
            } else if (index > previousIndex) {
                pagerInstance.scrollingLeft = false
            }
            previousIndex = index
            val leftHolder = leftView?.getTag() as PagerInstance.ViewHolder?
            val rightHolder = rightView?.getTag() as PagerInstance.ViewHolder?
            pagerInstance.leftHolder = leftHolder
            pagerInstance.currentHolder = holder
            pagerInstance.rightHolder = rightHolder
            interrupt(false)
            if (leftHolder != null) {
                applySideViewData(leftHolder, index - 1, false)
            }
            if (rightHolder != null) {
                applySideViewData(rightHolder, index + 1, false)
            }
            applySideViewData(holder, index, true)
            val galleryItem = galleryItems[index]
            if (holder.galleryItem != galleryItem || holder.loadState != PagerInstance.LoadState.COMPLETE) {
                holder.galleryItem = galleryItem
                holder.mediaSummary = MediaSummary(galleryItem)
                loadImageVideo(false, mayShowThumbnailOnly, waitBeforeVideo)
                waitBeforeVideo = 0
            } else {
                galleryInstance.callback.invalidateOptionsMenu()
                galleryInstance.callback.modifySystemUiVisibility(
                    GalleryInstance.Flags.LOCKED_ERROR,
                    false,
                )
            }
            galleryInstance.callback.updateTitle()
            if (galleryItem.postNumber != null && resumed && !galleryInstance.callback.isGalleryMode()) {
                galleryInstance.callback.navigatePost(galleryItem, false, false)
            }
        }

        override fun onSwipingStateChange(
            view: PhotoViewPager?,
            swiping: Boolean,
        ) {
            videoUnit.handleSwipingContent(swiping, false)
        }

        fun recycleAll() {
            for (i in 0..<viewPager.getChildCount()) {
                val holder = viewPager.getChildAt(i).getTag() as PagerInstance.ViewHolder
                holder.recyclePhotoView()
                holder.loadState = PagerInstance.LoadState.PREVIEW_OR_LOADING
            }
        }
    }

    init {
        pagerInstance = PagerInstance(galleryInstance, this)
        imageUnit = ImageUnit(pagerInstance)
        videoUnit = VideoUnit(pagerInstance)
        val density =
            obtainDensity(
                galleryInstance.context,
            )
        viewPagerParent = FrameLayout(galleryInstance.context)
        pagerAdapter = PagerAdapter(galleryInstance.galleryItems)
        pagerAdapter.setWaitBeforeNextVideo(PhotoView.INITIAL_SCALE_TRANSITION_TIME + 100)
        viewPager = PhotoViewPager(galleryInstance.context, pagerAdapter)
        viewPager.setInnerPadding((16f * density).toInt())
        viewPager.setLayoutParams(
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        viewPagerParent.addView(
            viewPager,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        viewPager.setCount(galleryInstance.galleryItems.size)
        val viewModel =
            ViewModelProvider(galleryInstance.callback).get<PagerUnitViewModel>(PagerUnitViewModel::class.java)
        viewModel.pagerUnit = WeakReference<PagerUnit>(this)
    }

    private fun buildPopupMenu(): DialogMenu? {
        // Read the holder only after the capability check: currentHolder is null in gallery mode,
        // and invalidatePopupMenu can reach this while the menu is still shown.
        val capabilities = obtainOptionsMenuCapabilities()
        val holder = pagerInstance.currentHolder
        if (capabilities.available && holder != null) {
            val galleryItem = holder.galleryItem!!
            val chan = get(galleryInstance.chanName)
            val context = GalleryInstance.menuContext(galleryInstance.callback.getWindow()!!.getContext())
            val dialogMenu = DialogMenu(context)
            dialogMenu.setTitle(
                if (!StringUtils.isEmpty(galleryItem.originalName)) {
                    galleryItem.originalName
                } else {
                    galleryItem.getFileName(chan)
                },
            )
            if (galleryItem.isVideo(chan)) {
                // Mirrors the flow player's context menu, which offers switching to the gallery.
                // Picture-in-picture is offered through the side-column button, not this menu.
                dialogMenu.add(R.string.flow, Runnable { galleryInstance.callback.switchToFlow() })
            }
            if (!galleryInstance.callback.isSystemUiVisible()) {
                if (capabilities.save) {
                    dialogMenu.add(
                        R.string.save,
                        Runnable {
                            galleryInstance.callback
                                .downloadGalleryItem(galleryItem)
                        },
                    )
                }
                if (capabilities.refresh) {
                    dialogMenu.add(R.string.refresh, Runnable { this.refreshCurrent() })
                }
            }
            if (capabilities.viewMetadata) {
                dialogMenu.add(
                    R.string.metadata,
                    Runnable {
                        if (galleryItem.isImage(chan)) {
                            imageUnit.viewMetadata()
                        } else if (galleryItem.isVideo(chan)) {
                            videoUnit.viewMetadata()
                        }
                    },
                )
            }
            if (capabilities.searchImage) {
                dialogMenu.add(
                    R.string.search_image,
                    Runnable {
                        videoUnit.forcePause()
                        SearchImageDialog(
                            galleryInstance.chanName,
                            galleryItem.getDisplayImageUri(chan),
                        ).show(galleryInstance.callback.getChildFragmentManager(), null)
                    },
                )
            }
            if (capabilities.copyImage) {
                Companion.addGalleryFileOption(
                    dialogMenu,
                    galleryItem,
                    chan,
                    R.string.copy_image,
                    (
                        Consumer { file: File ->
                            FileUriClipboard.copyFileUriToClipboard(
                                file,
                                galleryItem.getFileName(chan)!!,
                            )
                        }
                    ),
                )
            }
            if (galleryInstance.callback.isAllowNavigatePostManually(true) && capabilities.navigatePost) {
                dialogMenu.add(
                    R.string.go_to_post,
                    Runnable {
                        galleryInstance.callback
                            .navigatePost(galleryItem, true, true)
                    },
                )
            }
            dialogMenu.add(
                R.string.copy_link,
                Runnable {
                    StringUtils.copyToClipboard(
                        context,
                        galleryItem.getFileUri(chan).toString(),
                    )
                },
            )
            dialogMenu.add(
                R.string.share_link,
                Runnable {
                    videoUnit.forcePause()
                    NavigationUtils.shareLink(context, null, galleryItem.getFileUri(chan)!!)
                },
            )
            if (capabilities.shareFile) {
                Companion.addGalleryFileOption(
                    dialogMenu,
                    galleryItem,
                    chan,
                    R.string.share_file,
                    (
                        Consumer { file: File ->
                            NavigationUtils.shareFile(
                                context,
                                file,
                                galleryItem.getFileName(chan),
                            )
                        }
                    ),
                )
            }
            return dialogMenu
        }
        return null
    }

    fun invalidatePopupMenu() {
        val instanceDialog =
            galleryInstance.callback
                .getChildFragmentManager()
                .findFragmentByTag(TAG_POPUP_MENU) as InstanceDialog?
        if (instanceDialog != null) {
            val dialog = instanceDialog.getDialog() as AlertDialog?
            if (dialog != null) {
                val dialogMenu = buildPopupMenu()
                if (dialogMenu != null) {
                    dialogMenu.update(dialog)
                } else {
                    instanceDialog.dismiss()
                }
            }
        }
    }

    companion object {
        private const val PAGER_SCALE = 0.9f

        private val TAG_POPUP_MENU = PagerUnit::class.java.getName() + ":PopupMenu"

        private fun addGalleryFileOption(
            dialogMenu: DialogMenu,
            galleryItem: GalleryItem,
            chan: Chan,
            @StringRes titleResId: Int,
            onClick: Consumer<File>,
        ) {
            dialogMenu.add(
                titleResId,
                Runnable {
                    val file: File? =
                        CacheManager.getInstance().getMediaFile(galleryItem.getFileUri(chan), false)
                    if (file != null) {
                        onClick.accept(file)
                    } else {
                        show(R.string.cache_is_unavailable)
                    }
                },
            )
        }

        private fun displayPopupMenu(fragmentManager: FragmentManager) {
            InstanceDialog(
                fragmentManager,
                TAG_POPUP_MENU,
                InstanceDialog.Factory { provider ->
                    val viewModel =
                        ViewModelProvider(provider.parentFragment!!)
                            .get<PagerUnitViewModel>(PagerUnitViewModel::class.java)
                    val pagerUnit: PagerUnit = viewModel.pagerUnit!!.get()!!
                    val dialogMenu = pagerUnit.buildPopupMenu()
                    if (dialogMenu != null) {
                        return@Factory dialogMenu.create()
                    } else {
                        return@Factory provider.createDismissDialog()
                    }
                },
            )
        }
    }
}
