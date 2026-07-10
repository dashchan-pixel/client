package com.mishiranu.dashchan.ui.gallery

import android.app.Activity
import android.app.ActivityOptions
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
import android.view.WindowInsets
import chan.content.Chan
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.util.AudioFocus
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import java.lang.ref.WeakReference

/**
 * Floating picture-in-picture video player, reached from the gallery's and the Flow feed's
 * "Picture-in-picture" menu item. Launched directly into PiP mode ([ActivityOptions.makeLaunchIntoPip]);
 * playback is rendered by a chrome-less [FlowVideoView], which downloads the attachment
 * progressively on its own, so the launching gallery/feed dialog is dismissed immediately and
 * the thread stays readable behind the floating window. Expanding the window turns this into a
 * plain fullscreen player (tap toggles the control bar); leaving it re-enters PiP automatically.
 */
class VideoPipActivity : Activity(), FlowVideoView.Callback {
	private class Playback(val chan: Chan, val item: GalleryItem, val playlist: List<GalleryItem>?,
			val threadTitle: String?, val position: Long, val playing: Boolean, val dimensions: Point?)

	private lateinit var videoView: FlowVideoView
	private lateinit var audioFocus: AudioFocus

	private var chan: Chan? = null
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

	private val controlReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.getIntExtra(EXTRA_COMMAND, -1)) {
				COMMAND_PLAY -> setPlaying(true)
				COMMAND_PAUSE -> setPlaying(false)
				COMMAND_NEXT -> advance(1)
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val playback = pendingPlayback
		pendingPlayback = null
		if (playback == null) {
			// Recreated without a handoff (e.g. after process death) — nothing left to play.
			finish()
			return
		}
		instance = WeakReference(this)
		audioFocus = AudioFocus(this) { change ->
			when (change) {
				AudioFocus.Change.LOSS -> setPlaying(false)
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
		setContentView(videoView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT))
		// The window is edge-to-edge: keep the expanded player's control bar clear of the
		// gesture navigation area (insets are zero in the PiP window itself).
		videoView.setOnApplyWindowInsetsListener { view, insets ->
			(view as FlowVideoView).setBottomInset(insets.getInsets(WindowInsets.Type.systemBars()).bottom)
			insets
		}
		registerReceiver(controlReceiver, IntentFilter(ACTION_CONTROL), RECEIVER_NOT_EXPORTED)
		beginPlayback(playback)
	}

	private fun beginPlayback(playback: Playback) {
		chan = playback.chan
		threadTitle = playback.threadTitle
		pendingSeekPosition = playback.position
		dimensions = playback.dimensions
		playing = playback.playing
		pausedByTransientLossOfFocus = false
		playlist = playback.playlist?.toMutableList()
		playlistIndex = (playlist?.indexOfFirst { it === playback.item } ?: 0).coerceAtLeast(0)
		videoView.bind(playback.chan, playback.item, this)
		// Starts buffering right away; playback begins once the player is ready.
		videoView.prepare()
		videoView.setActive(playback.playing)
		updatePictureInPictureParams()
	}

	/** Skip [delta] entries forward in the Flow playlist, wrapping around like the feed. */
	private fun advance(delta: Int) {
		val chan = chan ?: return
		val playlist = playlist?.takeIf { it.isNotEmpty() } ?: return
		playlistIndex = (playlistIndex + delta).mod(playlist.size)
		pendingSeekPosition = -1
		playing = true
		pausedByTransientLossOfFocus = false
		videoView.bind(chan, playlist[playlistIndex], this)
		videoView.prepare()
		videoView.setActive(true)
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

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		// Expanded to fullscreen: enable the tap-toggled control bar; the small window has no
		// room for it and offers the play/pause remote action instead.
		videoView.setControlsEnabled(!isInPictureInPictureMode)
	}

	override fun onStop() {
		super.onStop()
		// Reached when the PiP window is dismissed or the expanded player is left for another
		// app: never keep playing audio without a visible surface. (While in PiP the activity
		// is merely paused, so playback continues there.)
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
		val builder = PictureInPictureParams.Builder()
				.setAutoEnterEnabled(true)
				.setSeamlessResizeEnabled(true)
		pipAspectRatio(dimensions)?.let { builder.setAspectRatio(it) }
		val actions = mutableListOf(if (playing) {
			remoteAction(COMMAND_PAUSE, R.attr.iconButtonPause, R.string.pause)
		} else {
			remoteAction(COMMAND_PLAY, R.attr.iconButtonPlay, R.string.play)
		})
		if ((playlist?.size ?: 0) > 1) {
			actions.add(remoteAction(COMMAND_NEXT, R.attr.iconButtonForward, R.string.skip))
		}
		builder.setActions(actions)
		setPictureInPictureParams(builder.build())
	}

	private fun remoteAction(command: Int, iconAttr: Int, titleRes: Int): RemoteAction {
		val label = getString(titleRes)
		val intent = PendingIntent.getBroadcast(this, command,
				Intent(ACTION_CONTROL).setPackage(packageName).putExtra(EXTRA_COMMAND, command),
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		return RemoteAction(Icon.createWithResource(this,
				ResourceUtils.getResourceId(this, iconAttr, 0)), label, label, intent)
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
				advance(1)
			} else {
				view.replay()
			}
		} else if (Preferences.getVideoCompletionMode() == Preferences.VideoCompletionMode.LOOP) {
			view.replay()
		} else {
			// The view already reset the clip to its start and paused.
			playing = false
			audioFocus.release()
			updatePictureInPictureParams()
		}
	}

	override fun onVideoFailed(view: FlowVideoView, galleryItem: GalleryItem) {
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

	// The remaining actions need the thread UI behind the gallery; the context menu offering
	// them is disabled in this window.
	override fun onGoToPost(galleryItem: GalleryItem) {}
	override fun getDownloadBinder(): DownloadService.Binder? = null
	override fun onSwitchToGallery(galleryItem: GalleryItem) {}
	override fun onEnterPip(view: FlowVideoView, galleryItem: GalleryItem) {}

	companion object {
		private const val ACTION_CONTROL = "com.mishiranu.dashchan.action.VIDEO_PIP_CONTROL"
		private const val EXTRA_COMMAND = "command"
		private const val COMMAND_PLAY = 1
		private const val COMMAND_PAUSE = 2
		private const val COMMAND_NEXT = 3

		// In-process handoff, like FlowDialog's pending state: GalleryItem is not Parcelable.
		private var pendingPlayback: Playback? = null
		private var instance: WeakReference<VideoPipActivity>? = null

		/**
		 * Pop [galleryItem]'s video out into the floating player, resuming at [position]
		 * (already-known [dimensions] shape the window before the first frame). A Flow feed
		 * passes its video list as [playlist] so completed videos advance to the next one;
		 * the gallery passes null to keep single-video semantics. The caller is expected to
		 * stop its own playback and dismiss itself afterwards.
		 */
		@JvmStatic
		fun start(activity: Activity, chan: Chan, galleryItem: GalleryItem, playlist: List<GalleryItem>?,
				threadTitle: String?, position: Long, playing: Boolean, dimensions: Point?) {
			val playback = Playback(chan, galleryItem, playlist, threadTitle, position, playing, dimensions)
			val existing = instance?.get()
			if (existing != null && !existing.isFinishing && !existing.isDestroyed) {
				// A floating player is already up — swap its video instead of relaunching.
				existing.beginPlayback(playback)
				return
			}
			pendingPlayback = playback
			val builder = PictureInPictureParams.Builder().setAutoEnterEnabled(true)
			pipAspectRatio(dimensions)?.let { builder.setAspectRatio(it) }
			activity.startActivity(Intent(activity, VideoPipActivity::class.java),
					ActivityOptions.makeLaunchIntoPip(builder.build()).toBundle())
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
