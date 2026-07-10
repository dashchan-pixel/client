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
	private class Playback(val chan: Chan, val item: GalleryItem, val threadTitle: String?,
			val position: Long, val playing: Boolean, val dimensions: Point?)

	private lateinit var videoView: FlowVideoView
	private lateinit var audioFocus: AudioFocus

	private var threadTitle: String? = null
	private var pendingSeekPosition = -1L
	private var dimensions: Point? = null

	/** The intended playback state; the player itself lags while buffering. */
	private var playing = true
	private var pausedByTransientLossOfFocus = false

	private val controlReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.getIntExtra(EXTRA_COMMAND, -1)) {
				COMMAND_PLAY -> setPlaying(true)
				COMMAND_PAUSE -> setPlaying(false)
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
		threadTitle = playback.threadTitle
		pendingSeekPosition = playback.position
		dimensions = playback.dimensions
		playing = playback.playing
		pausedByTransientLossOfFocus = false
		videoView.bind(playback.chan, playback.item, this)
		// Starts buffering right away; playback begins once the player is ready.
		videoView.prepare()
		videoView.setActive(playback.playing)
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
		val command = if (playing) COMMAND_PAUSE else COMMAND_PLAY
		val icon = if (playing) R.attr.iconButtonPause else R.attr.iconButtonPlay
		val label = getString(if (playing) R.string.pause else R.string.play)
		val intent = PendingIntent.getBroadcast(this, command,
				Intent(ACTION_CONTROL).setPackage(packageName).putExtra(EXTRA_COMMAND, command),
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		builder.setActions(listOf(RemoteAction(Icon.createWithResource(this,
				ResourceUtils.getResourceId(this, icon, 0)), label, label, intent)))
		setPictureInPictureParams(builder.build())
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
		if (Preferences.getVideoCompletionMode() == Preferences.VideoCompletionMode.LOOP) {
			view.replay()
		} else {
			// The view already reset the clip to its start and paused.
			playing = false
			audioFocus.release()
			updatePictureInPictureParams()
		}
	}

	override fun onVideoFailed(view: FlowVideoView, galleryItem: GalleryItem) {
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

		// In-process handoff, like FlowDialog's pending state: GalleryItem is not Parcelable.
		private var pendingPlayback: Playback? = null
		private var instance: WeakReference<VideoPipActivity>? = null

		/**
		 * Pop [galleryItem]'s video out into the floating player, resuming at [position]
		 * (already-known [dimensions] shape the window before the first frame). The caller
		 * is expected to stop its own playback and dismiss itself afterwards.
		 */
		@JvmStatic
		fun start(activity: Activity, chan: Chan, galleryItem: GalleryItem, threadTitle: String?,
				position: Long, playing: Boolean, dimensions: Point?) {
			val playback = Playback(chan, galleryItem, threadTitle, position, playing, dimensions)
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
