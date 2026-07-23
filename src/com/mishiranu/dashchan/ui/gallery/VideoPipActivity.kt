package com.mishiranu.dashchan.ui.gallery

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.FragmentActivity
import chan.content.Chan
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.ui.MainActivity
import com.mishiranu.dashchan.util.AudioFocus
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.applyTheme
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.attach
import java.lang.ref.WeakReference

/**
 * Standalone picture-in-picture video player, reached from the gallery's and the Flow feed's
 * "Picture-in-picture" menu item. Playback is rendered by a [FlowVideoView], which downloads the
 * attachment progressively on its own, so the launching gallery/feed dialog is dismissed
 * immediately and the thread stays readable behind the window.
 *
 * Launched as an ordinary fullscreen activity that pops itself into the floating window on first
 * resume (see [onResume]). Because it has a real fullscreen state, the window's two system buttons
 * behave independently, YouTube-style: the maximize button restores it to fullscreen (with its own
 * control bar and context menu), and the close button finishes it — the launch-into-PiP model this
 * replaced made the two indistinguishable, so close used to reopen the gallery like maximize.
 */
class VideoPipActivity :
    Activity(),
    FlowVideoView.Callback {
    private class Playback(
        val chan: Chan,
        val item: GalleryItem,
        val playlist: List<GalleryItem>?,
        val allItems: List<GalleryItem>?,
        val navigatePostMode: String?,
        val threadTitle: String?,
        val position: Long,
        val playing: Boolean,
        val dimensions: Point?,
    )

    /** Snapshot for the fullscreen player's "Gallery" menu item, handed to [MainActivity][C.ACTION_VIDEO_PIP]. */
    private class Reopen(
        val chan: Chan,
        val item: GalleryItem,
        val flow: Boolean,
        val allItems: List<GalleryItem>,
        val navigatePostMode: String?,
        val threadTitle: String?,
        val position: Long,
    )

    private lateinit var videoView: FlowVideoView
    private lateinit var audioFocus: AudioFocus

    private var chan: Chan? = null
    private var currentItem: GalleryItem? = null
    private var allItems: List<GalleryItem>? = null
    private var navigatePostMode: String? = null
    private var threadTitle: String? = null
    private var pendingSeekPosition = -1L
    private var dimensions: Point? = null

    /**
     * When launched from the Flow feed: its video list, so completed videos advance to the
     * next one (wrapping around) like the feed itself; unplayable entries are dropped.
     * Null when launched from the gallery, which honours the completion preference instead.
     */
    private var playlist: MutableList<GalleryItem>? = null
    private var playlistIndex = 0

    /** The intended playback state; the player itself lags while buffering. */
    private var playing = true
    private var pausedByTransientLossOfFocus = false
    private var initialPipRequested = false

    private val controlReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.getIntExtra(EXTRA_COMMAND, -1)) {
                    COMMAND_PLAY -> setPlaying(true)
                    COMMAND_PAUSE -> setPlaying(false)
                    COMMAND_NEXT -> advance(1)
                    COMMAND_MUTE -> toggleMute()
                }
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(attach(LocaleManager.getInstance().apply(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Attach to the theme engine so the fullscreen player's themed dialogs (context menu) work.
        // applyTheme() switches to a base theme that has an action bar, so suppress the title decor
        // first (like MainActivity) to keep the window full-bleed — no header over the video.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        applyTheme(this)
        val playback = pendingPlayback
        pendingPlayback = null
        if (playback == null) {
            // Recreated without a handoff (e.g. after process death) — nothing left to play.
            finish()
            return
        }
        instance = WeakReference(this)
        audioFocus =
            AudioFocus(this) { change ->
                when (change) {
                    AudioFocus.Change.LOSS -> {
                        setPlaying(false)
                    }

                    AudioFocus.Change.LOSS_TRANSIENT -> {
                        val wasPlaying = playing
                        setPlaying(false)
                        pausedByTransientLossOfFocus = wasPlaying
                    }

                    AudioFocus.Change.GAIN -> {
                        if (pausedByTransientLossOfFocus) {
                            setPlaying(true)
                        }
                    }
                }
            }
        videoView = FlowVideoView(this)
        videoView.setControlsEnabled(false)
        videoView.setContextMenuEnabled(false)
        // The multi-tap seek gesture is a big-player affordance; the floating window keeps its
        // simple system controls (play/pause, skip, mute) instead.
        videoView.setSeekGestureEnabled(false)
        setContentView(
            videoView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        registerReceiver(controlReceiver, IntentFilter(ACTION_CONTROL), RECEIVER_NOT_EXPORTED)
        beginPlayback(playback)
    }

    private fun beginPlayback(playback: Playback) {
        chan = playback.chan
        currentItem = playback.item
        allItems = playback.allItems
        navigatePostMode = playback.navigatePostMode
        threadTitle = playback.threadTitle
        pendingSeekPosition = playback.position
        dimensions = playback.dimensions
        playing = playback.playing
        pausedByTransientLossOfFocus = false
        playlist = playback.playlist?.toMutableList()
        playlistIndex = (playlist?.indexOfFirst { it === playback.item } ?: 0).coerceAtLeast(0)
        // Fullscreen context menu: offer "Gallery" only when launched from the Flow feed (not the
        // gallery itself); hide the thread-host actions ("Save", "Go to post") this window can't run.
        videoView.setMenuScope(switchToGallery = playback.playlist != null, hostActions = false)
        videoView.bind(playback.chan, playback.item, this)
        // Starts buffering right away; playback begins once the player is ready.
        videoView.prepare()
        videoView.setActive(playback.playing)
        updatePictureInPictureParams()
    }

    /** Skip [delta] entries forward in the Flow playlist, wrapping around like the feed. */
    private fun advance(delta: Int) {
        if (isFinishing || isDestroyed) {
            return
        }
        val chan = chan ?: return
        val playlist = playlist?.takeIf { it.isNotEmpty() } ?: return
        playlistIndex = (playlistIndex + delta).mod(playlist.size)
        pendingSeekPosition = -1
        playing = true
        pausedByTransientLossOfFocus = false
        currentItem = playlist[playlistIndex]
        videoView.bind(chan, playlist[playlistIndex], this)
        videoView.prepare()
        videoView.setActive(true)
        updatePictureInPictureParams()
    }

    private fun toggleMute() {
        val muted = !Preferences.isVideoMuted
        Preferences.isVideoMuted = muted
        videoView.setMuted(muted)
        updatePictureInPictureParams()
    }

    private fun setPlaying(playing: Boolean) {
        this.playing = playing
        pausedByTransientLossOfFocus = false
        if (playing && videoView.isAudioPresent()) {
            audioFocus.acquire()
        } else if (!playing) {
            audioFocus.release()
        }
        videoView.setActive(playing)
        updatePictureInPictureParams()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Floating window: the system window draws the controls, so hide our own chrome.
            videoView.setControlsEnabled(false)
            videoView.setContextMenuEnabled(false)
        } else if (!isFinishing) {
            // Maximize restores this activity to fullscreen. Rather than expand this standalone
            // player (whose chrome and state differ from the real players), hand playback back to
            // the gallery or Flow feed it came from. Closing the window goes straight to
            // finish()/onDestroy() and never reaches here.
            reopenInAppFullscreen()
        }
    }

    /**
     * Maximizing the floating window returns to the real in-app player — the gallery or the Flow
     * feed it was launched from — at the current attachment and position, then finishes this
     * activity. This mirrors the "Gallery" hand-off ([onSwitchToGallery]) but keeps the original
     * mode instead of always switching to the gallery.
     */
    private fun reopenInAppFullscreen() {
        val chan = chan
        val item = currentItem
        if (chan == null || item == null) {
            finish()
            return
        }
        pendingReopen =
            Reopen(
                chan,
                item,
                flow = playlist != null,
                allItems ?: playlist ?: listOf(item),
                navigatePostMode,
                threadTitle,
                videoView.playbackPosition(),
            )
        startActivity(Intent(this, MainActivity::class.java).setAction(C.ACTION_VIDEO_PIP))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Launched as a normal fullscreen activity (so maximize has a fullscreen state to restore
        // to); pop straight into the floating window once, on first resume.
        if (!initialPipRequested && !isInPictureInPictureMode && !isFinishing) {
            initialPipRequested = true
            enterPictureInPictureMode(buildPipParams())
        }
    }

    override fun onStop() {
        super.onStop()
        // Reached when the window is dismissed or hidden (e.g. screen off): never keep playing
        // audio without a visible surface. (While in PiP the activity is merely paused, so
        // playback continues there.)
        setPlaying(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() === this) {
            instance = null
        }
        if (this::videoView.isInitialized) {
            // Releases the player and cancels the download tasks.
            videoView.recycle()
            audioFocus.release()
            unregisterReceiver(controlReceiver)
        }
    }

    private fun updatePictureInPictureParams() {
        if (!this::videoView.isInitialized || isDestroyed) {
            return
        }
        setPictureInPictureParams(buildPipParams())
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder =
            PictureInPictureParams
                .Builder()
                .setAutoEnterEnabled(true)
                .setSeamlessResizeEnabled(true)
        pipAspectRatio(dimensions)?.let { builder.setAspectRatio(it) }
        val actions =
            mutableListOf(
                if (playing) {
                    remoteAction(COMMAND_PAUSE, R.attr.iconButtonPause, R.string.pause)
                } else {
                    remoteAction(COMMAND_PLAY, R.attr.iconButtonPlay, R.string.play)
                },
            )
        if ((playlist?.size ?: 0) > 1) {
            actions.add(remoteAction(COMMAND_NEXT, R.attr.iconButtonForward, R.string.skip))
        }
        if (this::videoView.isInitialized && videoView.isAudioPresent()) {
            val muted = Preferences.isVideoMuted
            actions.add(
                remoteActionRes(
                    COMMAND_MUTE,
                    if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                    if (muted) R.string.unmute else R.string.mute,
                ),
            )
        }
        builder.setActions(actions)
        return builder.build()
    }

    private fun remoteAction(
        command: Int,
        iconAttr: Int,
        titleRes: Int,
    ): RemoteAction = remoteActionRes(command, ResourceUtils.getResourceId(this, iconAttr, 0), titleRes)

    private fun remoteActionRes(
        command: Int,
        iconRes: Int,
        titleRes: Int,
    ): RemoteAction {
        val label = getString(titleRes)
        val intent =
            PendingIntent.getBroadcast(
                this,
                command,
                Intent(ACTION_CONTROL).setPackage(packageName).putExtra(EXTRA_COMMAND, command),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            label,
            label,
            intent,
        )
    }

    // FlowVideoView.Callback

    override fun onVideoReady(view: FlowVideoView) {
        if (pendingSeekPosition > 0) {
            view.seekTo(pendingSeekPosition)
        }
        pendingSeekPosition = -1
        if (playing && view.isAudioPresent()) {
            audioFocus.acquire()
        }
        dimensions = view.videoDimensions()
        updatePictureInPictureParams()
    }

    override fun onVideoSizeChanged(view: FlowVideoView) {
        dimensions = view.videoDimensions()
        updatePictureInPictureParams()
    }

    override fun onVideoEnded(view: FlowVideoView) {
        val playlist = playlist
        if (playlist != null) {
            // Flow semantics: advance to the next video, or loop the only one.
            if (playlist.size > 1) {
                // Posted: rebinding recycles the reporting player, which must not happen
                // from within that player's own completion callback.
                view.post { advance(1) }
            } else {
                view.replay()
            }
        } else if (Preferences.videoCompletionMode == Preferences.VideoCompletionMode.LOOP) {
            view.replay()
        } else {
            // The view already reset the clip to its start and paused.
            playing = false
            audioFocus.release()
            updatePictureInPictureParams()
        }
    }

    override fun onVideoFailed(
        view: FlowVideoView,
        galleryItem: GalleryItem,
    ) {
        // With a Flow playlist, drop the unplayable video and move on (like the feed);
        // give up only when nothing playable remains.
        val playlist = playlist
        if (playlist != null) {
            val index = playlist.indexOfFirst { it === galleryItem }
            if (index >= 0 && index != playlistIndex) {
                // A stale report for a video that is not the one playing: just drop it.
                playlist.removeAt(index)
                if (index < playlistIndex) {
                    playlistIndex--
                }
                return
            }
            if (index == playlistIndex) {
                playlist.removeAt(index)
                if (playlist.isNotEmpty()) {
                    // The next video took over the removed one's index; advance(0) wraps it.
                    advance(0)
                    return
                }
            }
        }
        ClickableToast.show(R.string.playback_error)
        finish()
    }

    override fun getThreadTitle(): String? = threadTitle

    // Context-menu actions from the restored fullscreen player. "Save" and "Go to post" are hidden
    // here (they need the thread UI); [setMenuScope] gates them.
    override fun onGoToPost(galleryItem: GalleryItem) {}

    override fun getDownloadBinder(): DownloadService.Binder? = null

    /** The menu's "Gallery" item (shown only when this was launched from the Flow feed): open the
     * regular gallery at this attachment, back inside the app. */
    override fun onSwitchToGallery(galleryItem: GalleryItem) {
        val chan = chan ?: return
        pendingReopen =
            Reopen(
                chan,
                galleryItem,
                flow = false,
                allItems ?: playlist ?: listOf(galleryItem),
                navigatePostMode,
                threadTitle,
                videoView.playbackPosition(),
            )
        startActivity(Intent(this, MainActivity::class.java).setAction(C.ACTION_VIDEO_PIP))
        finish()
    }

    /** The menu's "Picture-in-picture" item: minimize the fullscreen player back into the window. */
    override fun onEnterPip(
        view: FlowVideoView,
        galleryItem: GalleryItem,
    ) {
        if (!isInPictureInPictureMode && !isFinishing) {
            enterPictureInPictureMode(buildPipParams())
        }
    }

    companion object {
        private const val ACTION_CONTROL = "com.mishiranu.dashchan.action.VIDEO_PIP_CONTROL"
        private const val EXTRA_COMMAND = "command"
        private const val COMMAND_PLAY = 1
        private const val COMMAND_PAUSE = 2
        private const val COMMAND_NEXT = 3
        private const val COMMAND_MUTE = 4

        // In-process handoffs, like FlowDialog's pending state: GalleryItem is not Parcelable.
        private var pendingPlayback: Playback? = null
        private var pendingReopen: Reopen? = null
        private var instance: WeakReference<VideoPipActivity>? = null

        /**
         * Reopen the gallery the fullscreen player's "Gallery" menu item came from, at the same
         * video and position. Called by MainActivity for [C.ACTION_VIDEO_PIP].
         */
        @JvmStatic
        fun reopenInApp(activity: FragmentActivity) {
            val reopen = pendingReopen ?: return
            pendingReopen = null
            val fragmentManager = activity.supportFragmentManager
            if (reopen.flow) {
                FlowDialog.show(
                    fragmentManager,
                    reopen.chan,
                    reopen.allItems,
                    reopen.item,
                    reopen.threadTitle,
                    reopen.position,
                )
            } else {
                val galleryTag = GalleryOverlay::class.java.name
                (fragmentManager.findFragmentByTag(galleryTag) as? GalleryOverlay)?.dismiss()
                val index = reopen.allItems.indexOfFirst { it === reopen.item }.coerceAtLeast(0)
                GalleryOverlay(
                    reopen.chan.name,
                    ArrayList(reopen.allItems),
                    index,
                    reopen.threadTitle,
                    null,
                    reopen.navigatePostMode?.let { GalleryOverlay.NavigatePostMode.valueOf(it) }
                        ?: GalleryOverlay.NavigatePostMode.DISABLED,
                    false,
                ).setInitialVideoPosition(reopen.position)
                    .show(fragmentManager, galleryTag)
            }
        }

        /**
         * Pop [galleryItem]'s video out into the floating player, resuming at [position]
         * (already-known [dimensions] shape the window before the first frame). A Flow feed
         * passes its video list as [playlist] so completed videos advance to the next one;
         * the gallery passes null to keep single-video semantics. [allItems] (with
         * [navigatePostMode] for the gallery) lets the fullscreen player's "Gallery" menu item
         * reopen the gallery via [reopenInApp]. The caller is expected to stop its own playback
         * and dismiss itself afterwards.
         */
        @JvmStatic
        fun start(
            activity: Activity,
            chan: Chan,
            galleryItem: GalleryItem,
            playlist: List<GalleryItem>?,
            allItems: List<GalleryItem>?,
            navigatePostMode: String?,
            threadTitle: String?,
            position: Long,
            playing: Boolean,
            dimensions: Point?,
        ) {
            val playback =
                Playback(
                    chan,
                    galleryItem,
                    playlist,
                    allItems,
                    navigatePostMode,
                    threadTitle,
                    position,
                    playing,
                    dimensions,
                )
            val existing = instance?.get()
            if (existing != null && !existing.isFinishing && !existing.isDestroyed) {
                if (existing.isInPictureInPictureMode) {
                    // A floating player is already up — swap its video instead of relaunching.
                    existing.beginPlayback(playback)
                    return
                }
                // A leftover instance that is no longer a visible PiP window — replace it.
                existing.finish()
            }
            pendingPlayback = playback
            // Launch as a normal fullscreen activity (not makeLaunchIntoPip), so it has a fullscreen
            // state to restore to; it pops itself into the floating window from onResume.
            activity.startActivity(Intent(activity, VideoPipActivity::class.java))
        }

        private fun pipAspectRatio(dimensions: Point?): Rational? {
            if (dimensions == null || dimensions.x <= 0 || dimensions.y <= 0) {
                return null
            }
            // The system rejects PiP aspect ratios outside [1:2.39, 2.39:1] — clamp extreme videos.
            val ratio = dimensions.x.toFloat() / dimensions.y
            return when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 100f / 239f -> Rational(100, 239)
                else -> Rational(dimensions.x, dimensions.y)
            }
        }
    }
}
