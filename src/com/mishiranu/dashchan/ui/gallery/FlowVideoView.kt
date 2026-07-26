package com.mishiranu.dashchan.ui.gallery

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.AdvancedPreferences
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.ReadVideoTask
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.media.VideoPlayer
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.AspectRatioFrameLayout
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * A single page of the video Flow feed (reels/TikTok-style). Downloads one video attachment
 * progressively — mirroring the gallery's [com.mishiranu.dashchan.ui.gallery.VideoUnit] —
 * and plays it looped. Uses the same control bar as the single-video gallery player
 * (scrub bar, elapsed/total time, play/pause button, muted indicator); tap toggles the bar.
 *
 * The host distinguishes three states via [prepare] and [setActive]:
 * - [prepare]: begin buffering (download + ready the player) but stay paused — used to
 *   pre-buffer the next page so it plays instantly on arrival.
 * - [setActive] true: the page is centered; buffer if needed and play.
 * - [setActive] false: the page left the center/screen; pause (but keep it buffered).
 * [recycle] fully releases the player when the RecyclerView reuses the view.
 */
class FlowVideoView(
    context: Context,
) : FrameLayout(context),
    ReadVideoTask.Callback,
    VideoPlayer.RangeCallback {
    private val coverView = ImageView(context)
    private val progressBar = ProgressBar(context)
    private val errorView = TextView(context)
    private var videoWrapper: AspectRatioFrameLayout? = null

    private val controlsView: LinearLayout
    private val configurationView: LinearLayout
    private val sideControls: VideoSideControls
    private val seekFeedbackView: TextView
    private val playPauseButton: ImageButton
    private val positionText: TextView
    private val durationText: TextView
    private val seekBar: SeekBar
    private var tracking = false
    private var controlsVisible = false

    private val seekDetector = VideoSeekTapDetector()
    private val gestureDetector: GestureDetector
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tapValid = false
    private var longPressed = false
    private var lastSeekTime = 0L
    private val seekFeedbackRunnable = Runnable { hideSeekFeedback() }

    /** Cleared in the standalone PiP player, where the multi-tap seek gesture is not offered. */
    private var seekGestureEnabled = true

    /** Cleared in the PiP window, where the system window provides the playback controls. */
    private var controlsEnabled = true

    /** Cleared in the PiP window; set again when the player is maximized to fullscreen. */
    private var contextMenuEnabled = true

    /**
     * Which of the context menu's host-dependent actions to offer. The standalone fullscreen PiP
     * player hides the ones it cannot service: "Gallery" is redundant when it was itself launched
     * from the gallery, and "Save" / "Go to post" need the thread UI it does not host.
     */
    private var switchToGalleryMenuEnabled = true
    private var hostActionsMenuEnabled = true

    private var chan: Chan? = null
    private var galleryItem: GalleryItem? = null
    private var uri: Uri? = null
    private var callback: Callback? = null

    private var player: VideoPlayer? = null

    // Per-clip playback speed: not persisted, reset to 1× for every new video (see [bind]).
    private var playbackSpeed = 1f
    private var downloadTask: ReadVideoTask? = null
    private var rangeTask: ReadVideoTask? = null
    private var allowRangeRequests = true
    private var active = false
    private var started = false
    private var reportedFailure = false
    private var renderedFirstFrame = false
    private var aspectKnown = false
    private var downloadProgress = 0L
    private var downloadMax = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable =
        object : Runnable {
            override fun run() {
                updateControls()
                handler.postDelayed(this, 500)
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
        // Opaque: the SurfaceView behind punches a hole through the window, and its first
        // frame can render stretched before the aspect-ratio layout settles - nothing may
        // shine through around the fit-centered thumbnail while the cover is up.
        coverView.setBackgroundColor(Color.BLACK)
        coverView.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progressBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        progressBar.visibility = GONE
        errorView.setTextColor(Color.WHITE)
        errorView.visibility = GONE
        addView(errorView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Controls mirror VideoUnit's single-video (portrait) layout.
        val density = ResourceUtils.obtainDensity(context)
        controlsView = LinearLayout(context)
        controlsView.orientation = LinearLayout.VERTICAL
        controlsView.visibility = GONE

        configurationView = LinearLayout(context)
        configurationView.orientation = LinearLayout.HORIZONTAL
        configurationView.gravity = Gravity.END
        configurationView.setPadding((8f * density).toInt(), 0, (8f * density).toInt(), 0)
        controlsView.addView(
            configurationView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val controls = LinearLayout(context)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(CONTROLS_BACKGROUND_COLOR)
        controls.setPadding((8f * density).toInt(), (8f * density).toInt(), (8f * density).toInt(), 0)
        controls.isClickable = true
        controlsView.addView(
            controls,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        positionText = timeLabel(context)
        durationText = timeLabel(context)

        seekBar = SeekBar(context)
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        positionText.text = formatTime(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    tracking = false
                    player?.setPosition(seekBar.progress.toLong())
                }
            },
        )

        playPauseButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle)
        playPauseButton.scaleType = ImageView.ScaleType.CENTER
        playPauseButton.setOnClickListener { toggle() }

        val controls1 = LinearLayout(context)
        controls1.orientation = LinearLayout.HORIZONTAL
        controls1.gravity = Gravity.CENTER_VERTICAL
        controls1.setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
        val controls2 = LinearLayout(context)
        controls2.orientation = LinearLayout.HORIZONTAL
        controls2.gravity = Gravity.CENTER_VERTICAL
        controls.addView(controls1, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        controls.addView(controls2, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        controls1.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        controls2.addView(positionText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls2.addView(playPauseButton, (80f * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        controls2.addView(durationText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(controlsView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Reels-style column of overlay controls at the bottom-right edge, above the control bar.
        sideControls = VideoSideControls(context, makeSideControlsCallback())
        sideControls.visibility = GONE
        val sideParams =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM)
        sideParams.rightMargin = (8f * density).toInt()
        sideParams.bottomMargin = (SIDE_CONTROLS_BOTTOM_DP * density).toInt()
        addView(sideControls, sideParams)

        seekFeedbackView = TextView(context)
        seekFeedbackView.setTextColor(Color.WHITE)
        ViewUtils.setTextSizeScaled(seekFeedbackView, 18)
        seekFeedbackView.typeface = ResourceUtils.TYPEFACE_MEDIUM
        seekFeedbackView.gravity = Gravity.CENTER
        seekFeedbackView.setPadding(
            (16f * density).toInt(),
            (10f * density).toInt(),
            (16f * density).toInt(),
            (10f * density).toInt(),
        )
        seekFeedbackView.background =
            GradientDrawable().apply {
                cornerRadius = Preferences.uiCornerRadius * density
                setColor(0x99000000.toInt())
            }
        seekFeedbackView.visibility = GONE
        addView(seekFeedbackView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        gestureDetector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Suppressed right after a seek so the burst's trailing tap doesn't also
                        // toggle the control bar.
                        if (controlsEnabled && !isSeekingRecently()) {
                            setControlsVisible(!controlsVisible, true)
                        }
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        longPressed = true
                        if (contextMenuEnabled && !ViewUtils.isInBottomGestureRegion(this@FlowVideoView, e.getY())) {
                            displayContextMenu()
                        }
                    }
                },
            )
        isClickable = true
        isLongClickable = true
    }

    private fun makeSideControlsCallback(): VideoSideControls.Callback =
        object : VideoSideControls.Callback {
            override fun onSpeedClick() {
                sideControls.showSpeedPopup(playbackSpeed, Preferences.enabledVideoSpeeds) { speed ->
                    playbackSpeed = speed
                    player?.setPlaybackSpeed(speed)
                    sideControls.setSpeed(speed)
                }
            }

            override fun onMuteClick() {
                val muted = !Preferences.isVideoMuted
                Preferences.isVideoMuted = muted
                player?.setVolume(if (muted) 0f else 1f)
                sideControls.setMuteState(muted, player?.isAudioPresent() == true)
            }

            override fun onPipClick() {
                val galleryItem = galleryItem ?: return
                callback?.onEnterPip(this@FlowVideoView, galleryItem)
            }
        }

    /** Lift the control bar above the system navigation bar / gesture area. */
    fun setBottomInset(bottom: Int) {
        controlsView.setPadding(0, 0, 0, bottom)
        val density = ResourceUtils.obtainDensity(context)
        (sideControls.layoutParams as LayoutParams).bottomMargin =
            (SIDE_CONTROLS_BOTTOM_DP * density).toInt() + bottom
        sideControls.requestLayout()
    }

    /** Standalone PiP player: the multi-tap seek gesture is not offered there. */
    fun setSeekGestureEnabled(enabled: Boolean) {
        seekGestureEnabled = enabled
    }

    /** Apply the mute state to the current player (used by the PiP window's remote action). */
    fun setMuted(muted: Boolean) {
        player?.setVolume(if (muted) 0f else 1f)
        updateSideControls()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tapValid = true
                longPressed = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tapValid = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (tapValid &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    tapValid = false
                }
            }

            MotionEvent.ACTION_UP -> {
                if (seekGestureEnabled &&
                    Preferences.isVideoMultiTapSeek &&
                    tapValid &&
                    !longPressed &&
                    player != null
                ) {
                    val seek = seekDetector.onTap(event.x, width)
                    if (seek != null) {
                        lastSeekTime = SystemClock.uptimeMillis()
                        doSeek(seek)
                    }
                }
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun isSeekingRecently(): Boolean = SystemClock.uptimeMillis() - lastSeekTime <= SEEK_SUPPRESS_MS

    private fun doSeek(seek: VideoSeekTapDetector.Seek) {
        val player = player ?: return
        val duration = player.getDuration()
        var target = (player.getPosition() + seek.deltaMs).coerceAtLeast(0)
        if (duration > 0) {
            target = target.coerceAtMost(duration)
        }
        player.setPosition(target)
        updateControls()
        showSeekFeedback(seek)
    }

    private fun showSeekFeedback(seek: VideoSeekTapDetector.Seek) {
        val seconds = abs(seek.burstMs) / 1000
        seekFeedbackView.text =
            if (seek.forward) "+ $seconds s" else "- $seconds s"
        seekFeedbackView.visibility = VISIBLE
        handler.removeCallbacks(seekFeedbackRunnable)
        handler.postDelayed(seekFeedbackRunnable, 700)
    }

    private fun hideSeekFeedback() {
        seekFeedbackView.visibility = GONE
    }

    private fun updateSideControls() {
        sideControls.setSpeed(playbackSpeed)
        sideControls.setSpeedButtonVisible(Preferences.enabledVideoSpeeds.size > 1)
        sideControls.setMuteState(Preferences.isVideoMuted, player?.isAudioPresent() == true)
        sideControls.setPipButtonVisible(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
        )
    }

    private fun timeLabel(context: Context): TextView {
        val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
        ViewUtils.setTextSizeScaled(textView, 14)
        textView.gravity = Gravity.CENTER_HORIZONTAL
        textView.typeface = ResourceUtils.TYPEFACE_MEDIUM
        textView.text = formatTime(0)
        return textView
    }

    fun bind(
        chan: Chan,
        galleryItem: GalleryItem,
        callback: Callback,
    ) {
        recycle()
        this.chan = chan
        this.galleryItem = galleryItem
        this.callback = callback
        // Every new video starts at 1×; a speed the user picked on the previous clip does not carry.
        playbackSpeed = 1f
        reportedFailure = false
        this.uri = galleryItem.getFileUri(chan)
        coverView.visibility = VISIBLE
        coverView.setImageDrawable(null)
        val thumbnailUri = galleryItem.getThumbnailUri(chan)
        if (thumbnailUri != null) {
            ImageLoader.getInstance().loadImage(chan, thumbnailUri, false, coverView)
        }
    }

    /** Begin buffering (download + ready player) without forcing playback. */
    fun prepare() {
        if (!started) {
            start()
        }
    }

    /** Play/resume when centered (true); pause when off-center/off-screen (false). */
    fun setActive(active: Boolean) {
        this.active = active
        if (active) {
            prepare()
            player?.setPlaying(true)
        } else {
            player?.setPlaying(false)
        }
        updatePlayPauseIcon()
    }

    private fun toggle() {
        val player = player ?: return
        player.setPlaying(!player.isPlaying())
        updatePlayPauseIcon()
    }

    /** Restart this clip from the beginning (used to loop a single-video thread). */
    fun replay() {
        val player = player ?: return
        player.setPosition(0)
        player.setPlaying(true)
        updatePlayPauseIcon()
    }

    fun isPlaying(): Boolean = player?.isPlaying() == true

    fun isAudioPresent(): Boolean = player?.isAudioPresent() == true

    fun playbackPosition(): Long = player?.getPosition() ?: 0

    fun seekTo(position: Long) {
        player?.setPosition(position)
    }

    fun playbackSpeed(): Float = playbackSpeed

    /**
     * Carry a speed the user picked elsewhere onto this clip (a picture-in-picture handoff in either
     * direction). Applies to the current player if there is one, and to the one [bind] is still
     * waiting on otherwise. Call it after [bind], which resets the speed to 1×.
     */
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        player?.setPlaybackSpeed(speed)
        sideControls.setSpeed(speed)
    }

    fun videoDimensions(): Point? = player?.getDimensions()?.takeIf { it.x > 0 && it.y > 0 }

    fun boundGalleryItem(): GalleryItem? = galleryItem

    fun setControlsEnabled(enabled: Boolean) {
        controlsEnabled = enabled
        if (!enabled) {
            setControlsVisible(visible = false, animate = false)
        }
    }

    fun setContextMenuEnabled(enabled: Boolean) {
        contextMenuEnabled = enabled
    }

    /** Standalone PiP player: hide "Gallery" (when gallery-launched) and the thread-host actions. */
    fun setMenuScope(
        switchToGallery: Boolean,
        hostActions: Boolean,
    ) {
        switchToGalleryMenuEnabled = switchToGallery
        hostActionsMenuEnabled = hostActions
    }

    private fun start() {
        val chan = chan ?: return
        val uri = uri ?: return
        started = true
        errorView.visibility = GONE
        progressBar.visibility = VISIBLE
        allowRangeRequests = !AdvancedPreferences.isSingleConnection(chan.name)
        val newPlayer = VideoPlayer(playerListener, false)
        player = newPlayer
        val cachedFile: File? =
            try {
                CacheManager.getInstance().getMediaFileOrThrow(uri, true)
            } catch (e: CacheManager.CacheException) {
                showError()
                return
            }
        if (cachedFile == null) {
            showError()
            return
        }
        if (cachedFile.exists() && cachedFile.length() > 0) {
            // Already fully cached — play the complete file directly.
            try {
                newPlayer.init(cachedFile, null)
                return
            } catch (e: IOException) {
                newPlayer.destroy()
                player = VideoPlayer(playerListener, false)
            }
        }
        // Otherwise download progressively into the partial cache file and stream it.
        val task = ReadVideoTask(this, chan, uri, 0)
        downloadTask = task
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    private fun showError() {
        progressBar.visibility = GONE
        errorView.text = context.getString(R.string.playback_error)
        errorView.visibility = VISIBLE
        val galleryItem = galleryItem
        val callback = callback
        if (!reportedFailure && galleryItem != null && callback != null) {
            reportedFailure = true
            // Posted: the feed removes the item from its adapter, which must not
            // happen re-entrantly from a bind or layout pass.
            handler.post { callback.onVideoFailed(this, galleryItem) }
        }
    }

    fun recycle() {
        handler.removeCallbacks(progressRunnable)
        active = false
        started = false
        downloadProgress = 0L
        downloadMax = 0L
        tracking = false
        downloadTask?.cancel()
        downloadTask = null
        rangeTask?.cancel()
        rangeTask = null
        player?.destroy()
        player = null
        videoWrapper?.let { removeView(it) }
        videoWrapper = null
        renderedFirstFrame = false
        aspectKnown = false
        progressBar.visibility = GONE
        errorView.visibility = GONE
        configurationView.removeAllViews()
        controlsView.animate().cancel()
        controlsView.visibility = GONE
        controlsView.alpha = 1f
        controlsView.translationY = 0f
        sideControls.animate().cancel()
        sideControls.visibility = GONE
        sideControls.alpha = 1f
        controlsVisible = false
        handler.removeCallbacks(seekFeedbackRunnable)
        hideSeekFeedback()
        seekDetector.reset()
        coverView.visibility = VISIBLE
    }

    private fun setControlsVisible(
        visible: Boolean,
        animate: Boolean,
    ) {
        if (controlsVisible == visible || player == null) {
            return
        }
        controlsVisible = visible
        controlsView.animate().cancel()
        sideControls.animate().cancel()
        if (visible) {
            controlsView.visibility = VISIBLE
            sideControls.visibility = VISIBLE
            if (animate) {
                controlsView
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .start()
                sideControls
                    .animate()
                    .alpha(1f)
                    .setDuration(250)
                    .start()
            } else {
                controlsView.alpha = 1f
                controlsView.translationY = 0f
                sideControls.alpha = 1f
            }
        } else if (animate) {
            controlsView
                .animate()
                .alpha(0f)
                .translationY(controlsView.height.toFloat())
                .setDuration(250)
                .withEndAction { controlsView.visibility = GONE }
                .start()
            sideControls
                .animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { sideControls.visibility = GONE }
                .start()
        } else {
            controlsView.alpha = 0f
            controlsView.visibility = GONE
            sideControls.alpha = 0f
            sideControls.visibility = GONE
        }
    }

    // Only drop the cover once a frame is rendered AND the surface has its true aspect
    // ratio - hiding it earlier exposes a stretched full-bleed first frame.
    private fun maybeHideCover() {
        if (renderedFirstFrame && aspectKnown) {
            coverView.visibility = GONE
        }
    }

    private fun updatePlayPauseIcon() {
        playPauseButton.setImageResource(
            ResourceUtils.getResourceId(
                context,
                if (player?.isPlaying() == true) R.attr.iconButtonPause else R.attr.iconButtonPlay,
                0,
            ),
        )
    }

    private fun updateControls() {
        val player = player ?: return
        val duration = player.getDuration()
        if (duration > 0) {
            if (!tracking) {
                seekBar.max = duration.toInt()
                seekBar.progress = player.getPosition().toInt()
                positionText.text = formatTime(player.getPosition())
            }
            durationText.text = formatTime(duration)
            if (downloadMax > 0) {
                seekBar.secondaryProgress = (duration * downloadProgress / downloadMax).toInt()
            }
        }
        updatePlayPauseIcon()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60 % 60, totalSeconds % 60)
    }

    private val playerListener =
        object : VideoPlayer.Listener {
            override fun onReady(player: VideoPlayer) {
                if (player != this@FlowVideoView.player) {
                    return
                }
                progressBar.visibility = GONE
                val dimensions = player.getDimensions()
                aspectKnown = dimensions.y > 0
                val wrapper = AspectRatioFrameLayout(context)
                wrapper.setAspectRatio(if (dimensions.y > 0) dimensions.x.toFloat() / dimensions.y else 0f)
                wrapper.addView(
                    player.getVideoView(context),
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
                videoWrapper = wrapper
                // Insert below the cover (index 0) so the thumbnail hides the surface until first frame.
                addView(wrapper, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
                // Carry the global mute / playback-speed state onto the freshly-ready player.
                player.setVolume(if (Preferences.isVideoMuted) 0f else 1f)
                player.setPlaybackSpeed(playbackSpeed)
                player.setPlaying(active)
                // The mute button in the side column now reflects the audio state.
                updateSideControls()
                if (controlsEnabled) {
                    controlsView.visibility = VISIBLE
                    controlsView.alpha = 1f
                    controlsView.translationY = 0f
                    sideControls.visibility = VISIBLE
                    sideControls.alpha = 1f
                    controlsVisible = true
                }
                updateControls()
                handler.removeCallbacks(progressRunnable)
                handler.post(progressRunnable)
                callback?.onVideoReady(this@FlowVideoView)
            }

            override fun onError(
                player: VideoPlayer,
                message: String?,
            ) {
                if (player == this@FlowVideoView.player) {
                    showError()
                }
            }

            override fun onComplete(player: VideoPlayer) {
                if (player == this@FlowVideoView.player) {
                    // Reset to the start and pause; the feed decides whether to advance to the next
                    // video or (for a single-video thread) loop this one. Resetting here means the clip
                    // plays from the beginning if the user swipes back to it later.
                    player.setPosition(0)
                    player.setPlaying(false)
                    val callback = callback
                    if (callback != null) {
                        callback.onVideoEnded(this@FlowVideoView)
                    } else {
                        player.setPlaying(active)
                    }
                }
            }

            override fun onBusyStateChange(
                player: VideoPlayer,
                busy: Boolean,
            ) {}

            override fun onDimensionChange(player: VideoPlayer) {
                if (player == this@FlowVideoView.player) {
                    val dimensions = player.getDimensions()
                    if (dimensions.y > 0) {
                        videoWrapper?.setAspectRatio(dimensions.x.toFloat() / dimensions.y)
                        aspectKnown = true
                        maybeHideCover()
                        callback?.onVideoSizeChanged(this@FlowVideoView)
                    }
                }
            }

            override fun onRenderedFirstFrame(player: VideoPlayer) {
                if (player == this@FlowVideoView.player) {
                    renderedFirstFrame = true
                    maybeHideCover()
                }
            }
        }

    // ReadVideoTask.Callback — mirrors VideoUnit.ReadVideoCallback.

    override fun onReadVideoInit(partialFile: File) {
        val player = player ?: return
        try {
            player.init(partialFile, this)
        } catch (e: IOException) {
            showError()
        }
    }

    override fun onReadVideoProgressUpdate(
        progress: Long,
        progressMax: Long,
    ) {
        downloadProgress = progress
        downloadMax = progressMax
        player?.setDownloadRange(progress, progressMax)
    }

    override fun onReadVideoRangeUpdate(
        start: Long,
        end: Long,
    ) {
        player?.setPartRange(start, end)
    }

    override fun onReadVideoSuccess(
        partial: Boolean,
        file: File,
    ) {
        if (partial) {
            rangeTask = null
        } else {
            downloadTask = null
            val length = file.length()
            downloadProgress = length
            downloadMax = length
            player?.setDownloadRange(length, length)
        }
    }

    override fun onReadVideoFail(
        partial: Boolean,
        errorItem: ErrorItem,
        disallowRangeRequests: Boolean,
    ) {
        if (partial) {
            rangeTask = null
            if (disallowRangeRequests) {
                allowRangeRequests = false
            }
        } else {
            downloadTask = null
            showError()
        }
    }

    override fun requestPartFromPosition(start: Long) {
        rangeTask?.cancel()
        rangeTask = null
        val chan = chan ?: return
        val uri = uri ?: return
        if (allowRangeRequests && start > 0) {
            val task = ReadVideoTask(this, chan, uri, start)
            rangeTask = task
            task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        }
    }

    // Per-video context menu, mirroring the gallery's popup (video items only).
    private fun displayContextMenu() {
        val chan = chan ?: return
        val galleryItem = galleryItem ?: return
        val uri = uri ?: return
        val callback = callback
        val dialogMenu = DialogMenu(context)
        dialogMenu.setTitle(
            if (!StringUtils.isEmpty(galleryItem.originalName)) {
                galleryItem.originalName
            } else {
                galleryItem.getFileName(chan)
            },
        )
        if (switchToGalleryMenuEnabled) {
            dialogMenu.add(R.string.gallery) { callback?.onSwitchToGallery(galleryItem) }
        }
        // Picture-in-picture is offered through the side-column button, not this menu.
        if (hostActionsMenuEnabled) {
            dialogMenu.add(R.string.save) {
                val binder = callback?.getDownloadBinder()
                if (binder != null) {
                    galleryItem.downloadStorage(binder, chan, callback.getThreadTitle())
                }
            }
        }
        if (player != null) {
            dialogMenu.add(R.string.metadata) { showMetadata() }
        }
        if (hostActionsMenuEnabled && galleryItem.postNumber != null) {
            dialogMenu.add(R.string.go_to_post) { callback?.onGoToPost(galleryItem) }
        }
        dialogMenu.add(R.string.copy_link) { StringUtils.copyToClipboard(context, uri.toString()) }
        dialogMenu.add(R.string.share_link) { NavigationUtils.shareLink(context, null, uri) }
        dialogMenu.add(R.string.share_file) {
            val file = CacheManager.getInstance().getMediaFile(uri, false)
            if (file != null) {
                NavigationUtils.shareFile(context, file, galleryItem.getFileName(chan))
            } else {
                ClickableToast.show(R.string.cache_is_unavailable)
            }
        }
        dialogMenu.create().show()
    }

    private fun showMetadata() {
        val player = player ?: return
        val metadata = player.getMetadata()
        if (metadata.isEmpty()) {
            return
        }
        val builder = StringBuilder()
        for ((key, value) in metadata) {
            builder
                .append(key)
                .append(": ")
                .append(value)
                .append('\n')
        }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.metadata)
            .setMessage(builder.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Actions that need the hosting fragment/activity (post navigation, downloads, advancing). */
    interface Callback {
        fun onGoToPost(galleryItem: GalleryItem)

        fun getDownloadBinder(): DownloadService.Binder?

        fun getThreadTitle(): String?

        fun onVideoEnded(view: FlowVideoView)

        /** The video cannot be played (download or playback error) - drop it from the feed. */
        fun onVideoFailed(
            view: FlowVideoView,
            galleryItem: GalleryItem,
        )

        /** Switch to the regular gallery, opened at this same attachment. */
        fun onSwitchToGallery(galleryItem: GalleryItem)

        /** Continue this video in the floating picture-in-picture player. */
        fun onEnterPip(
            view: FlowVideoView,
            galleryItem: GalleryItem,
        )

        /** The player reached its first ready state: position and duration are now valid. */
        fun onVideoReady(view: FlowVideoView) {}

        /** The video's true dimensions (via [videoDimensions]) became known or changed. */
        fun onVideoSizeChanged(view: FlowVideoView) {}
    }

    companion object {
        // Matches GalleryOverlay's action bar chrome colour.
        private const val CONTROLS_BACKGROUND_COLOR = 0xaa202020.toInt()

        // Distance of the reels-style side column above the bottom edge (plus any bottom inset).
        private const val SIDE_CONTROLS_BOTTOM_DP = 96f

        // A single tap this soon after a seek is swallowed rather than toggling the control bar.
        private const val SEEK_SUPPRESS_MS = 500L
    }
}
