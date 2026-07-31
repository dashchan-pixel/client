package com.mishiranu.dashchan.ui.gallery

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import chan.content.Chan.Companion.getPreferred
import chan.util.StringUtils.isEmptyOrWhitespace
import chan.util.StringUtils.stripTrailingZeros
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.AdvancedPreferences.isSingleConnection
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.VideoCompletionMode
import com.mishiranu.dashchan.content.Preferences.isVideoSeekAnyFrame
import com.mishiranu.dashchan.content.Preferences.videoCompletionMode
import com.mishiranu.dashchan.content.async.ReadVideoTask
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.graphics.BaseDrawable
import com.mishiranu.dashchan.media.VideoPlayer
import com.mishiranu.dashchan.media.VideoPlayer.RangeCallback
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.AnimationUtils.measureDynamicHeight
import com.mishiranu.dashchan.util.AudioFocus
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.isTabletOrLandscape
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.AspectRatioFrameLayout
import com.mishiranu.dashchan.widget.SummaryLayout
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

class VideoUnit(
    private val instance: PagerInstance,
) {
    private val controlsView: LinearLayout
    private val sideControls: VideoSideControls
    private val seekFeedbackView: TextView
    private val seekDetector = VideoSeekTapDetector()
    private val audioFocus: AudioFocus

    private var layoutConfiguration = -1

    // Created together in recreateVideoControls, which always runs its creation branch on the
    // first call (layoutConfiguration starts at -1). The two reads that happen before that first
    // creation -- carrying the old time text and secondary progress over to the new views -- are
    // gated on the same `firstTimeLayout` flag, which is by construction equivalent to "not yet
    // assigned": layoutConfiguration leaves -1 in the very block that assigns these.
    private lateinit var configurationView: LinearLayout
    private lateinit var timeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: ImageButton

    private var player: VideoPlayer? = null

    // Per-clip playback speed: not persisted, reset to 1× for every new video (see [applyVideo]).
    private var playbackSpeed = 1f
    private var backgroundDrawable: BackgroundDrawable? = null
    var isInitialized: Boolean = false
        private set
    private var wasPlaying = false
    private var pausedByTransientLossOfFocus = false
    private var finishedPlayback = false
    private var trackingNow = false
    private var hideSurfaceOnInit = false
    private var videoCover: View? = null

    private var readVideoCallback: ReadVideoCallback? = null
    private lateinit var videoUri: Uri
    private lateinit var videoFile: File
    private var initFromFile = false

    // One-shot start position and speed for the next initialized video (PiP window handing back).
    private var initialSeekPosition: Long = 0
    private var initialPlaybackSpeed = 0f

    fun addViews(frameLayout: FrameLayout) {
        frameLayout.addView(
            controlsView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        val density = obtainDensity(instance.galleryInstance.context)
        val sideParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            )
        sideParams.rightMargin = (8f * density).toInt()
        sideParams.bottomMargin = (SIDE_CONTROLS_BOTTOM_DP * density).toInt()
        frameLayout.addView(sideControls, sideParams)
        frameLayout.addView(
            seekFeedbackView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    fun onResume() {
        if (player != null && this@VideoUnit.isInitialized) {
            setPlaying(wasPlaying, true)
            updatePlayState()
        } else {
            wasPlaying = true
        }
    }

    fun onPause() {
        val player = this.player
        if (player != null && this@VideoUnit.isInitialized) {
            wasPlaying = player.isPlaying()
            setPlaying(false, true)
        } else {
            wasPlaying = false
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED) {
            if (layoutConfiguration != -1) {
                recreateVideoControls()
            }
        }
    }

    fun onApplyWindowInsets(
        left: Int,
        right: Int,
        bottom: Int,
    ) {
        controlsView.setPadding(left, 0, right, bottom)
        val density = obtainDensity(instance.galleryInstance.context)
        (sideControls.layoutParams as? FrameLayout.LayoutParams)?.let { sideParams ->
            sideParams.rightMargin = (8f * density).toInt() + right
            sideParams.bottomMargin = (SIDE_CONTROLS_BOTTOM_DP * density).toInt() + bottom
            sideControls.requestLayout()
        }
    }

    val isCreated: Boolean
        get() = player != null

    val isPlaying: Boolean
        get() = this@VideoUnit.isInitialized && player!!.isPlaying()

    val playbackPosition: Long
        get() = if (this@VideoUnit.isInitialized) player!!.getPosition() else 0

    val videoDimensions: Point?
        get() = if (this@VideoUnit.isInitialized) player!!.getDimensions() else null

    /** The speed the current clip plays at, so a picture-in-picture handoff can keep it. */
    val currentPlaybackSpeed: Float
        get() = playbackSpeed

    fun setInitialSeekPosition(
        position: Long,
        speed: Float,
    ) {
        initialSeekPosition = position
        initialPlaybackSpeed = speed
    }

    fun interrupt() {
        readVideoCallback?.cancel()
        readVideoCallback = null
        instance.currentHolder?.photoView?.videoSeekEnabled = false
        seekDetector.reset()
        hideSeekFeedback()
        if (this@VideoUnit.isInitialized) {
            audioFocus.release()
            this@VideoUnit.isInitialized = false
        }
        invalidateControlsVisibility()
        val player = this.player
        if (player != null) {
            player.destroy()
            this.player = null
            instance.currentHolder!!.progressBar.setVisible(false, false)
        }
        backgroundDrawable?.recycle()
        backgroundDrawable = null
        videoCover = null
        interruptHolder(instance.leftHolder)
        interruptHolder(instance.currentHolder)
        interruptHolder(instance.rightHolder)
    }

    private fun interruptHolder(holder: PagerInstance.ViewHolder?) {
        if (holder != null) {
            holder.surfaceParent.removeAllViews()
        }
    }

    fun forcePause() {
        if (this@VideoUnit.isInitialized) {
            wasPlaying = false
            setPlaying(false, true)
        }
    }

    fun applyVideo(
        uri: Uri,
        file: File,
        reload: Boolean,
    ) {
        wasPlaying = true
        finishedPlayback = false
        hideSurfaceOnInit = false
        // Every new video starts at 1×; a speed the user picked on the previous clip does not carry.
        playbackSpeed = 1f
        videoUri = uri
        videoFile = file
        readVideoCallback = null
        val seekAnyFrame = isVideoSeekAnyFrame
        var player = VideoPlayer(playerListener, seekAnyFrame)
        this.player = player
        initFromFile = !reload && file.exists()
        if (initFromFile) {
            try {
                player.init(file, null)
                // Initialization continues in playerListener.onReady
            } catch (e: IOException) {
                initFromFile = false
                player.destroy()
                player = VideoPlayer(playerListener, seekAnyFrame)
                this.player = player
            }
        }
        if (!initFromFile) {
            startDownload(player)
        }
    }

    private fun startDownload(player: VideoPlayer) {
        val holder = instance.currentHolder!!
        val progressBar = holder.progressBar
        progressBar.setIndeterminate(true)
        progressBar.setVisible(true, false)
        readVideoCallback =
            ReadVideoCallback(
                player,
                holder,
                instance.galleryInstance.chanName,
                videoUri,
            )
    }

    private fun setPlaying(
        playing: Boolean,
        resetFocus: Boolean,
    ): Boolean {
        val player = this.player!!
        if (player.isPlaying() != playing) {
            if (resetFocus && player.isAudioPresent()) {
                if (playing) {
                    if (!audioFocus.acquire()) {
                        return false
                    }
                } else {
                    audioFocus.release()
                }
            }
            player.setPlaying(playing)
            pausedByTransientLossOfFocus = false
        }
        return true
    }

    private fun initializePlayer() {
        val player = this.player!!
        val holder = instance.currentHolder!!
        holder.progressBar.setVisible(false, false)
        val dimensions = player.getDimensions()
        if (holder.mediaSummary.updateDimensions(dimensions.x, dimensions.y)) {
            instance.galleryInstance.callback.updateTitle()
        }
        val backgroundDrawable = BackgroundDrawable()
        this.backgroundDrawable = backgroundDrawable
        backgroundDrawable.width = dimensions.x
        backgroundDrawable.height = dimensions.y
        holder.recyclePhotoView()
        holder.photoView.setImage(backgroundDrawable, false, true, false)
        val videoView = player.getVideoView(instance.galleryInstance.context)
        // Host the surface in an aspect-ratio container sized (fit-center) at layout time, so the
        // SurfaceView is created at the correct shape and the video is never rendered stretched to the
        // full screen before its first frame.
        val videoWrapper = AspectRatioFrameLayout(instance.galleryInstance.context)
        videoWrapper.setAspectRatio(aspectRatio(dimensions))
        videoWrapper.addView(
            videoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        holder.surfaceParent.addView(
            videoWrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        // Cover the surface with an opaque view until ExoPlayer renders its first frame, so the surface is
        // never shown before the video actually starts (it briefly renders stretched otherwise).
        val videoCover = View(instance.galleryInstance.context)
        videoCover.setBackgroundColor(Color.BLACK)
        holder.surfaceParent.addView(
            videoCover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        this.videoCover = videoCover
        recreateVideoControls()
        playPauseButton.setEnabled(true)
        seekBar.setEnabled(true)
        this@VideoUnit.isInitialized = true
        applyPlaybackPreferences(player, holder)
        pausedByTransientLossOfFocus = false
        if (initialSeekPosition > 0) {
            player.setPosition(initialSeekPosition)
            initialSeekPosition = 0
        }
        if (hideSurfaceOnInit) {
            showHideVideoView(false)
        }
        invalidateControlsVisibility()
        setPlaying(wasPlaying, true)
        updatePlayState()
    }

    private fun recreateVideoControls() {
        val context = instance.galleryInstance.context
        val density = obtainDensity(context)
        val targetLayoutCounfiguration =
            if (isTabletOrLandscape(
                    context
                        .getResources()
                        .getConfiguration(),
                )
            ) {
                1
            } else {
                0
            }
        if (targetLayoutCounfiguration != layoutConfiguration) {
            val firstTimeLayout = layoutConfiguration < 0
            layoutConfiguration = targetLayoutCounfiguration
            val longLayout = targetLayoutCounfiguration == 1

            controlsView.removeAllViews()
            if (!firstTimeLayout) {
                seekBar.removeCallbacks(progressRunnable)
            }
            trackingNow = false

            val configurationView = LinearLayout(context)
            this.configurationView = configurationView
            configurationView.setOrientation(LinearLayout.HORIZONTAL)
            configurationView.setGravity(Gravity.END)
            configurationView.setPadding((8f * density).toInt(), 0, (8f * density).toInt(), 0)
            controlsView.addView(
                configurationView,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            val controls = LinearLayout(context)
            controls.setOrientation(if (longLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)
            controls.setBackgroundColor(instance.galleryInstance.actionBarColor)
            controls.setPadding(
                (8f * density).toInt(),
                if (longLayout) 0 else (8f * density).toInt(),
                (8f * density).toInt(),
                0,
            )
            controls.setClickable(true)
            controlsView.addView(
                controls,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            val oldTimeText = if (firstTimeLayout) null else timeTextView.getText()
            val timeTextView = TextView(context, null, android.R.attr.textAppearanceListItem)
            this.timeTextView = timeTextView
            ViewUtils.setTextSizeScaled(timeTextView, 14)
            timeTextView.setGravity(Gravity.CENTER_HORIZONTAL)
            timeTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

            if (oldTimeText != null) {
                timeTextView.setText(oldTimeText)
            }

            val totalTimeTextView = TextView(context, null, android.R.attr.textAppearanceListItem)
            this.totalTimeTextView = totalTimeTextView
            ViewUtils.setTextSizeScaled(totalTimeTextView, 14)
            totalTimeTextView.setGravity(Gravity.CENTER_HORIZONTAL)
            totalTimeTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

            val oldSecondaryProgress = if (firstTimeLayout) -1 else seekBar.getSecondaryProgress()
            val seekBar = SeekBar(context)
            this.seekBar = seekBar
            seekBar.setOnSeekBarChangeListener(seekBarListener)
            if (oldSecondaryProgress >= 0) {
                seekBar.setSecondaryProgress(oldSecondaryProgress)
            }

            val playPauseButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle)
            this.playPauseButton = playPauseButton
            playPauseButton.setScaleType(android.widget.ImageView.ScaleType.CENTER)
            playPauseButton.setOnClickListener(playPauseClickListener)

            if (longLayout) {
                controls.setGravity(Gravity.CENTER_VERTICAL)
                controls.addView(
                    timeTextView,
                    (48f * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls.addView(
                    seekBar,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
                controls.addView(
                    playPauseButton,
                    (80f * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls.addView(
                    totalTimeTextView,
                    (48f * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            } else {
                val controls1 = LinearLayout(context)
                controls1.setOrientation(LinearLayout.HORIZONTAL)
                controls1.setGravity(Gravity.CENTER_VERTICAL)
                controls1.setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
                val controls2 = LinearLayout(context)
                controls2.setOrientation(LinearLayout.HORIZONTAL)
                controls2.setGravity(Gravity.CENTER_VERTICAL)
                controls.addView(
                    controls1,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls.addView(
                    controls2,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls1.addView(
                    seekBar,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls2.addView(
                    timeTextView,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
                controls2.addView(
                    playPauseButton,
                    (80f * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                controls2.addView(
                    totalTimeTextView,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
            }
            if (firstTimeLayout) {
                measureDynamicHeight(controlsView)
                controlsView.setTranslationY(controlsView.getMeasuredHeight().toFloat())
                controlsView.setAlpha(0f)
            }
        }
        val player = this.player
        if (player != null) {
            // The no-audio / mute state now lives on the side column's mute button.
            configurationView.removeAllViews()
            val duration = player.getDuration()
            totalTimeTextView.setText(formatVideoTime(duration))
            seekBar.setMax(duration.toInt())
        }
        seekBar.removeCallbacks(progressRunnable)
        seekBar.post(progressRunnable)
        updatePlayState()
    }

    private val playPauseClickListener =
        View.OnClickListener {
            if (this@VideoUnit.isInitialized) {
                val player = this.player!!
                if (finishedPlayback) {
                    finishedPlayback = false
                    player.setPosition(0)
                    setPlaying(true, true)
                } else {
                    val playing = !player.isPlaying()
                    setPlaying(playing, true)
                }
                updatePlayState()
            }
        }

    private val progressRunnable: Runnable =
        object : Runnable {
            override fun run() {
                val seekBar = this@VideoUnit.seekBar
                if (this@VideoUnit.isInitialized) {
                    val position: Int
                    if (trackingNow) {
                        position = seekBar.getProgress()
                    } else {
                        position = player!!.getPosition().toInt()
                        seekBar.setProgress(position)
                    }
                    timeTextView.setText(formatVideoTime(position.toLong()))
                }
                seekBar.postDelayed(this, 200)
            }
        }

    private val seekBarListener: OnSeekBarChangeListener =
        object : OnSeekBarChangeListener {
            private var nextSeekPosition = 0

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingNow = false
                seekBar.removeCallbacks(progressRunnable)
                if (nextSeekPosition != -1) {
                    seekBar.setProgress(nextSeekPosition)
                    player!!.setPosition(nextSeekPosition.toLong())
                    seekBar.postDelayed(progressRunnable, 250)
                    if (finishedPlayback) {
                        finishedPlayback = false
                        updatePlayState()
                    }
                } else {
                    progressRunnable.run()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingNow = true
                seekBar.removeCallbacks(progressRunnable)
                nextSeekPosition = -1
            }

            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                if (fromUser) {
                    nextSeekPosition = progress
                }
            }
        }

    private fun updatePlayState() {
        val player = this.player
        if (player != null) {
            val playing = player.isPlaying()
            playPauseButton.setImageResource(
                getResourceId(
                    instance.galleryInstance.context,
                    if (finishedPlayback) {
                        R.attr.iconButtonRefresh
                    } else {
                        if (playing) {
                            R.attr.iconButtonPause
                        } else {
                            R.attr.iconButtonPlay
                        }
                    },
                    0,
                ),
            )
            instance.galleryInstance.callback.setScreenOnFixed(!finishedPlayback && playing)
        }
    }

    fun viewMetadata() {
        if (this@VideoUnit.isInitialized) {
            val metadata = player!!.getMetadata()
            showMetadata(instance.galleryInstance.callback.getChildFragmentManager(), metadata)
        }
    }

    private var controlsVisible = false

    fun invalidateControlsVisibility() {
        val visible = this@VideoUnit.isInitialized && instance.galleryInstance.callback.isSystemUiVisible()
        if (layoutConfiguration >= 0 && controlsVisible != visible) {
            controlsView.animate().cancel()
            sideControls.animate().cancel()
            if (visible) {
                controlsView.setVisibility(View.VISIBLE)
                controlsView
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setListener(null)
                    .setInterpolator(AnimationUtils.DECELERATE_INTERPOLATOR)
                    .start()
                sideControls.setVisibility(View.VISIBLE)
                sideControls
                    .animate()
                    .alpha(1f)
                    .setDuration(250)
                    .setListener(null)
                    .setInterpolator(AnimationUtils.DECELERATE_INTERPOLATOR)
                    .start()
            } else {
                controlsView
                    .animate()
                    .alpha(0f)
                    .translationY(
                        (
                            controlsView.getHeight() -
                                configurationView.getHeight()
                        ).toFloat(),
                    ).setDuration(350)
                    .setListener(AnimationUtils.VisibilityListener(controlsView, View.GONE))
                    .setInterpolator(AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR)
                    .start()
                sideControls
                    .animate()
                    .alpha(0f)
                    .setDuration(350)
                    .setListener(AnimationUtils.VisibilityListener(sideControls, View.GONE))
                    .setInterpolator(AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR)
                    .start()
            }
            controlsVisible = visible
        }
    }

    private fun applyPlaybackPreferences(
        player: VideoPlayer,
        holder: PagerInstance.ViewHolder,
    ) {
        // When enabled, route the video surface's double-tap into the seek gesture (off by default,
        // leaving the double-tap-to-zoom intact).
        holder.photoView.videoSeekEnabled = Preferences.isVideoMultiTapSeek
        if (initialPlaybackSpeed > 0) {
            // A picture-in-picture window handing playback back: keep the speed it was playing at
            // instead of the 1× applyVideo just reset to.
            playbackSpeed = initialPlaybackSpeed
            initialPlaybackSpeed = 0f
        }
        // Carry the global mute / playback-speed state onto the freshly-ready player.
        player.setVolume(if (Preferences.isVideoMuted) 0f else 1f)
        player.setPlaybackSpeed(playbackSpeed)
        updateSideControls()
    }

    private fun updateSideControls() {
        val context = instance.galleryInstance.context
        sideControls.setSpeed(playbackSpeed)
        sideControls.setSpeedButtonVisible(Preferences.enabledVideoSpeeds.size > 1)
        sideControls.setMuteState(Preferences.isVideoMuted, player?.isAudioPresent() == true)
        sideControls.setPipButtonVisible(
            context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
        )
    }

    private val hideSeekFeedbackRunnable = Runnable { seekFeedbackView.setVisibility(View.GONE) }

    private fun hideSeekFeedback() {
        seekFeedbackView.removeCallbacks(hideSeekFeedbackRunnable)
        seekFeedbackView.setVisibility(View.GONE)
    }

    private fun showSeekFeedback(seek: VideoSeekTapDetector.Seek) {
        val seconds = abs(seek.burstMs) / 1000
        seekFeedbackView.setText(if (seek.forward) "+$seconds s" else "–$seconds s")
        seekFeedbackView.setVisibility(View.VISIBLE)
        seekFeedbackView.removeCallbacks(hideSeekFeedbackRunnable)
        seekFeedbackView.postDelayed(hideSeekFeedbackRunnable, 700)
    }

    /** A multi-tap seek from the video surface (see [PhotoView] / [VideoSeekTapDetector]). */
    fun handleSeekTap(
        x: Float,
        width: Int,
    ): Boolean {
        if (!this@VideoUnit.isInitialized || !Preferences.isVideoMultiTapSeek) {
            return false
        }
        val player = this.player ?: return false
        val seek = seekDetector.onTap(x, width) ?: return false
        val duration = player.getDuration()
        var target = (player.getPosition() + seek.deltaMs).coerceAtLeast(0)
        if (duration > 0) {
            target = target.coerceAtMost(duration)
        }
        player.setPosition(target)
        if (finishedPlayback) {
            finishedPlayback = false
        }
        seekBar.setProgress(target.toInt())
        timeTextView.setText(formatVideoTime(target))
        updatePlayState()
        showSeekFeedback(seek)
        return true
    }

    private val playerListener: VideoPlayer.Listener =
        object : VideoPlayer.Listener {
            override fun onReady(player: VideoPlayer) {
                if (player != this@VideoUnit.player || this@VideoUnit.isInitialized) {
                    return
                }
                val holder = instance.currentHolder!!
                holder.progressBar.setVisible(false, false)
                initializePlayer()
                val readVideoCallback = this@VideoUnit.readVideoCallback
                if (readVideoCallback == null) {
                    if (holder.mediaSummary.updateSize(videoFile.length())) {
                        instance.galleryInstance.callback.updateTitle()
                    }
                }
                if (readVideoCallback == null || readVideoCallback.isDownloadFinished) {
                    val seekBar = this@VideoUnit.seekBar
                    seekBar.setSecondaryProgress(seekBar.getMax())
                    holder.loadState = PagerInstance.LoadState.COMPLETE
                }
                instance.galleryInstance.callback.invalidateOptionsMenu()
            }

            override fun onError(
                player: VideoPlayer,
                message: String?,
            ) {
                if (player != this@VideoUnit.player) {
                    return
                }
                val holder = instance.currentHolder
                if (!this@VideoUnit.isInitialized && initFromFile) {
                    // The cached file cannot be played, download a fresh copy
                    initFromFile = false
                    player.destroy()
                    val newPlayer = VideoPlayer(playerListener, isVideoSeekAnyFrame)
                    this@VideoUnit.player = newPlayer
                    startDownload(newPlayer)
                } else if (!this@VideoUnit.isInitialized) {
                    readVideoCallback?.handleInitFailure()
                } else {
                    instance.callback.showError(
                        holder!!,
                        instance.galleryInstance.context
                            .getString(R.string.playback_error),
                    )
                }
            }

            override fun onComplete(player: VideoPlayer) {
                when (videoCompletionMode) {
                    VideoCompletionMode.NOTHING -> {
                        finishedPlayback = true
                        updatePlayState()
                    }

                    VideoCompletionMode.LOOP -> {
                        player.setPosition(0L)
                    }

                    else -> {
                        error("Unsupported video completion mode")
                    }
                }
            }

            override fun onBusyStateChange(
                player: VideoPlayer,
                busy: Boolean,
            ) {
                if (this@VideoUnit.isInitialized) {
                    val progressBar = instance.currentHolder!!.progressBar
                    if (busy) {
                        progressBar.setIndeterminate(true)
                    }
                    progressBar.setVisible(busy, false)
                }
            }

            override fun onDimensionChange(player: VideoPlayer) {
                val backgroundDrawable = this@VideoUnit.backgroundDrawable ?: return
                backgroundDrawable.recycle()
                val dimensions = player.getDimensions()
                backgroundDrawable.width = dimensions.x
                backgroundDrawable.height = dimensions.y
                instance.currentHolder!!.photoView.resetScale()
                val videoView = player.getVideoView(instance.galleryInstance.context)
                val parent = videoView.getParent()
                if (parent is AspectRatioFrameLayout) {
                    parent.setAspectRatio(aspectRatio(dimensions))
                }
            }

            override fun onRenderedFirstFrame(player: VideoPlayer) {
                removeVideoCover()
                // The surface has content again - drop the last-frame snapshot.
                backgroundDrawable?.recycle()
            }
        }

    init {
        val context = instance.galleryInstance.context
        controlsView = LinearLayout(context)
        controlsView.setOrientation(LinearLayout.VERTICAL)
        controlsView.setVisibility(View.GONE)

        // Reels-style overlay column at the bottom-right edge: speed, mute, picture-in-picture.
        sideControls =
            VideoSideControls(
                context,
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
                        instance.galleryInstance.callback.switchToPip()
                    }
                },
            )
        sideControls.setVisibility(View.GONE)

        val density = obtainDensity(context)
        seekFeedbackView = TextView(context)
        seekFeedbackView.setTextColor(Color.WHITE)
        ViewUtils.setTextSizeScaled(seekFeedbackView, 18)
        seekFeedbackView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
        seekFeedbackView.setGravity(Gravity.CENTER)
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
        seekFeedbackView.setVisibility(View.GONE)

        audioFocus =
            AudioFocus(
                instance.galleryInstance.context,
                AudioFocus.Callback { change: AudioFocus.Change? ->
                    when (change) {
                        AudioFocus.Change.LOSS -> {
                            setPlaying(false, false)
                            updatePlayState()
                        }

                        AudioFocus.Change.LOSS_TRANSIENT -> {
                            val playing = player!!.isPlaying()
                            setPlaying(false, false)
                            if (playing) {
                                pausedByTransientLossOfFocus = true
                            }
                            updatePlayState()
                        }

                        AudioFocus.Change.GAIN -> {
                            if (pausedByTransientLossOfFocus) {
                                setPlaying(true, false)
                            }
                            updatePlayState()
                        }

                        else -> {}
                    }
                },
            )
    }

    private fun removeVideoCover() {
        val videoCover = this.videoCover ?: return
        val parent = videoCover.getParent()
        if (parent is ViewGroup) {
            parent.removeView(videoCover)
        }
        this.videoCover = null
    }

    fun showHideVideoView(show: Boolean) {
        if (this@VideoUnit.isInitialized) {
            val player = this.player!!
            val videoView = player.getVideoView(instance.galleryInstance.context)
            if (show) {
                // Keep the last-frame snapshot in backgroundDrawable visible until the
                // recreated surface has rendered; it is recycled in onRenderedFirstFrame.
                videoView.setVisibility(View.VISIBLE)
            } else {
                player.captureCurrentFrame(
                    VideoPlayer.FrameCallback { frame: Bitmap? ->
                        val backgroundDrawable = this.backgroundDrawable
                        if (backgroundDrawable != null) {
                            backgroundDrawable.setFrame(frame)
                        } else if (frame != null) {
                            frame.recycle()
                        }
                        videoView.setVisibility(View.GONE)
                    },
                )
            }
        }
    }

    fun handleSwipingContent(
        swiping: Boolean,
        hideSurface: Boolean,
    ) {
        if (this@VideoUnit.isInitialized) {
            playPauseButton.setEnabled(!swiping)
            seekBar.setEnabled(!swiping)
            if (swiping) {
                wasPlaying = player!!.isPlaying()
                setPlaying(false, true)
                if (hideSurface) {
                    showHideVideoView(false)
                }
            } else {
                setPlaying(wasPlaying, true)
                if (hideSurface) {
                    showHideVideoView(true)
                }
                updatePlayState()
            }
        } else if (player != null) {
            wasPlaying = !swiping
            hideSurfaceOnInit = hideSurface && swiping
        }
    }

    private inner class ReadVideoCallback(
        private val workPlayer: VideoPlayer,
        private val holder: PagerInstance.ViewHolder,
        private val chanName: String?,
        private val uri: Uri,
    ) : ReadVideoTask.Callback,
        RangeCallback {
        private var downloadTask: ReadVideoTask?
        private var rangeTask: ReadVideoTask? = null
        private var allowRangeRequests: Boolean

        init {
            allowRangeRequests = !isSingleConnection(chanName)
            val chan = getPreferred(chanName, uri)
            val downloadTask = ReadVideoTask(this, chan, uri, 0)
            this.downloadTask = downloadTask
            downloadTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        }

        fun cancel() {
            downloadTask?.cancel()
            downloadTask = null
            rangeTask?.cancel()
            rangeTask = null
        }

        val isDownloadFinished: Boolean
            get() = downloadTask == null

        fun handleInitFailure() {
            holder.progressBar.setVisible(false, false)
            val downloadTask = this.downloadTask
            if (downloadTask != null) {
                if (!downloadTask.isError()) {
                    downloadTask.cancel()
                    this.downloadTask = null
                } else {
                    return
                }
            }
            rangeTask?.cancel()
            rangeTask = null
            instance.callback.showError(
                holder,
                instance.galleryInstance.context
                    .getString(R.string.playback_error),
            )
        }

        override fun onReadVideoInit(partialFile: File) {
            if (workPlayer == player) {
                try {
                    workPlayer.init(partialFile, this)
                    // Initialization continues in playerListener.onReady
                } catch (e: IOException) {
                    handleInitFailure()
                }
            }
        }

        override fun onReadVideoProgressUpdate(
            progress: Long,
            progressMax: Long,
        ) {
            if (workPlayer == player) {
                workPlayer.setDownloadRange(progress, progressMax)
                if (instance.currentHolder!!.mediaSummary.updateSize(progressMax)) {
                    instance.galleryInstance.callback.updateTitle()
                }
                if (this@VideoUnit.isInitialized) {
                    val seekBar = this@VideoUnit.seekBar
                    val max = seekBar.getMax()
                    if (max > 0 && progressMax > 0) {
                        val newProgress = (max * progress / progressMax).toInt()
                        seekBar.setSecondaryProgress(newProgress)
                    }
                }
            }
        }

        override fun onReadVideoRangeUpdate(
            start: Long,
            end: Long,
        ) {
            if (workPlayer == player) {
                workPlayer.setPartRange(start, end)
            }
        }

        override fun onReadVideoSuccess(
            partial: Boolean,
            file: File,
        ) {
            if (workPlayer == player) {
                if (partial) {
                    rangeTask = null
                } else {
                    downloadTask = null
                    val length = file.length()
                    workPlayer.setDownloadRange(length, length)
                    if (instance.currentHolder!!.mediaSummary.updateSize(length)) {
                        instance.galleryInstance.callback.updateTitle()
                    }
                    if (this@VideoUnit.isInitialized) {
                        val seekBar = this@VideoUnit.seekBar
                        seekBar.setSecondaryProgress(seekBar.getMax())
                        holder.loadState = PagerInstance.LoadState.COMPLETE
                        instance.galleryInstance.callback.invalidateOptionsMenu()
                    }
                }
            }
        }

        override fun onReadVideoFail(
            partial: Boolean,
            errorItem: ErrorItem,
            disallowRangeRequests: Boolean,
        ) {
            if (workPlayer == player) {
                if (partial) {
                    rangeTask = null
                    if (disallowRangeRequests) {
                        allowRangeRequests = false
                    }
                } else {
                    holder.progressBar.setVisible(false, false)
                    instance.callback.showError(holder, errorItem.toString())
                }
            }
        }

        override fun requestPartFromPosition(start: Long) {
            rangeTask?.cancel()
            rangeTask = null
            if (allowRangeRequests && start > 0) {
                val chan = getPreferred(chanName, uri)
                val rangeTask = ReadVideoTask(this, chan, uri, start)
                this.rangeTask = rangeTask
                rangeTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
            }
        }
    }

    private class BackgroundDrawable : BaseDrawable() {
        var width: Int = 0
        var height: Int = 0

        private var frame: Bitmap? = null
        private var draw = false

        fun setFrame(frame: Bitmap?) {
            recycleInternal()
            this.frame = frame
            draw = true
            invalidateSelf()
        }

        fun recycle() {
            recycleInternal()
            if (draw) {
                draw = false
                invalidateSelf()
            }
        }

        fun recycleInternal() {
            frame?.recycle()
            frame = null
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        public override fun draw(canvas: Canvas) {
            if (draw) {
                val bounds = getBounds()
                paint.setColor(Color.BLACK)
                canvas.drawRect(bounds, paint)
                val frame = this.frame
                if (frame != null) {
                    canvas.drawBitmap(frame, null, bounds, paint)
                }
            }
        }

        public override fun getOpacity(): Int = PixelFormat.TRANSPARENT

        override fun getIntrinsicWidth(): Int = width

        override fun getIntrinsicHeight(): Int = height
    }

    companion object {
        // Distance of the reels-style side column above the bottom edge (plus any bottom inset).
        private const val SIDE_CONTROLS_BOTTOM_DP = 96f

        private fun aspectRatio(dimensions: Point): Float = if (dimensions.x > 0 && dimensions.y > 0) dimensions.x.toFloat() / dimensions.y else 0f

        private fun formatVideoTime(position: Long): String {
            val seconds = position / 1000
            val m = (seconds / 60 % 60).toInt()
            val s = (seconds % 60).toInt()
            return String.format(Locale.US, "%02d:%02d", m, s)
        }

        private fun showMetadata(
            fragmentManager: FragmentManager,
            metadata: Map<String, String>,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider ->
                    val context = GalleryInstance.getCallback(provider).getWindow()!!.getContext()
                    val dialog =
                        AlertDialog
                            .Builder(context)
                            .setTitle(R.string.metadata)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                    val layout = SummaryLayout(dialog)
                    val videoFormat = metadata["video_format"]
                    val width = metadata["width"]
                    val height = metadata["height"]
                    val frameRate = metadata["frame_rate"]
                    val pixelFormat = metadata["pixel_format"]
                    val surfaceFormat = metadata["surface_format"]
                    val frameConversion = metadata["frame_conversion"]
                    val audioFormat = metadata["audio_format"]
                    val channels = metadata["channels"]
                    val sampleRate = metadata["sample_rate"]
                    val encoder = metadata["encoder"]
                    val title = metadata["title"]
                    if (videoFormat != null) {
                        layout.add("Video", videoFormat)
                    }
                    if (width != null && height != null) {
                        layout.add("Resolution", width + '×' + height)
                    }
                    if (frameRate != null) {
                        layout.add("Frame rate", stripTrailingZeros(frameRate) + " FPS")
                    }
                    if (pixelFormat != null) {
                        layout.add("Pixels", pixelFormat)
                    }
                    if (surfaceFormat != null) {
                        layout.add("Surface", surfaceFormat)
                    }
                    if (frameConversion != null) {
                        layout.add("Frame conversion", frameConversion)
                    }
                    layout.addDivider()
                    if (audioFormat != null) {
                        layout.add("Audio", audioFormat)
                    }
                    if (channels != null) {
                        layout.add("Channels", channels)
                    }
                    if (sampleRate != null) {
                        layout.add("Sample rate", sampleRate + " Hz")
                    }
                    layout.addDivider()
                    if (encoder != null) {
                        layout.add("Encoder", encoder)
                    }
                    if (title != null && !isEmptyOrWhitespace(title)) {
                        layout.add("Title", title)
                    }
                    dialog
                },
            )
        }
    }
}
