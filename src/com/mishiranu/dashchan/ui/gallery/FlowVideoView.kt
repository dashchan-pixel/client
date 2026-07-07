package com.mishiranu.dashchan.ui.gallery

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
class FlowVideoView(context: Context) : FrameLayout(context),
		ReadVideoTask.Callback, VideoPlayer.RangeCallback {
	private val coverView = ImageView(context)
	private val progressBar = ProgressBar(context)
	private val errorView = TextView(context)
	private var videoWrapper: AspectRatioFrameLayout? = null

	private val controlsView: LinearLayout
	private val configurationView: LinearLayout
	private val playPauseButton: ImageButton
	private val positionText: TextView
	private val durationText: TextView
	private val seekBar: SeekBar
	private var tracking = false
	private var controlsVisible = false

	private var chan: Chan? = null
	private var galleryItem: GalleryItem? = null
	private var uri: Uri? = null
	private var callback: Callback? = null

	private var player: VideoPlayer? = null
	private var downloadTask: ReadVideoTask? = null
	private var rangeTask: ReadVideoTask? = null
	private var allowRangeRequests = true
	private var active = false
	private var started = false
	private var downloadProgress = 0L
	private var downloadMax = 0L

	private val handler = Handler(Looper.getMainLooper())
	private val progressRunnable = object : Runnable {
		override fun run() {
			updateControls()
			handler.postDelayed(this, 500)
		}
	}

	init {
		setBackgroundColor(Color.BLACK)
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
		controlsView.addView(configurationView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT)

		val controls = LinearLayout(context)
		controls.orientation = LinearLayout.VERTICAL
		controls.setBackgroundColor(CONTROLS_BACKGROUND_COLOR)
		controls.setPadding((8f * density).toInt(), (8f * density).toInt(), (8f * density).toInt(), 0)
		controls.isClickable = true
		controlsView.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT)

		positionText = timeLabel(context)
		durationText = timeLabel(context)

		seekBar = SeekBar(context)
		seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
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
		})

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

		setOnClickListener { setControlsVisible(!controlsVisible, true) }
		setOnLongClickListener {
			displayContextMenu()
			true
		}
	}

	/** Lift the control bar above the system navigation bar / gesture area. */
	fun setBottomInset(bottom: Int) {
		controlsView.setPadding(0, 0, 0, bottom)
	}

	private fun timeLabel(context: Context): TextView {
		val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
		ViewUtils.setTextSizeScaled(textView, 14)
		textView.gravity = Gravity.CENTER_HORIZONTAL
		textView.typeface = ResourceUtils.TYPEFACE_MEDIUM
		textView.text = formatTime(0)
		return textView
	}

	fun bind(chan: Chan, galleryItem: GalleryItem, callback: Callback) {
		recycle()
		this.chan = chan
		this.galleryItem = galleryItem
		this.callback = callback
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

	private fun start() {
		val chan = chan ?: return
		val uri = uri ?: return
		started = true
		errorView.visibility = GONE
		progressBar.visibility = VISIBLE
		allowRangeRequests = !AdvancedPreferences.isSingleConnection(chan.name)
		val newPlayer = VideoPlayer(playerListener, false)
		player = newPlayer
		val cachedFile: File? = try {
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
		progressBar.visibility = GONE
		errorView.visibility = GONE
		configurationView.removeAllViews()
		controlsView.animate().cancel()
		controlsView.visibility = GONE
		controlsView.alpha = 1f
		controlsView.translationY = 0f
		controlsVisible = false
		coverView.visibility = VISIBLE
	}

	private fun setControlsVisible(visible: Boolean, animate: Boolean) {
		if (controlsVisible == visible || player == null) {
			return
		}
		controlsVisible = visible
		controlsView.animate().cancel()
		if (visible) {
			controlsView.visibility = VISIBLE
			if (animate) {
				controlsView.animate().alpha(1f).translationY(0f).setDuration(250).start()
			} else {
				controlsView.alpha = 1f
				controlsView.translationY = 0f
			}
		} else if (animate) {
			controlsView.animate().alpha(0f).translationY(controlsView.height.toFloat())
					.setDuration(250).withEndAction { controlsView.visibility = GONE }.start()
		} else {
			controlsView.alpha = 0f
			controlsView.visibility = GONE
		}
	}

	private fun updatePlayPauseIcon() {
		playPauseButton.setImageResource(ResourceUtils.getResourceId(context,
				if (player?.isPlaying() == true) R.attr.iconButtonPause else R.attr.iconButtonPlay, 0))
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

	private val playerListener = object : VideoPlayer.Listener {
		override fun onReady(player: VideoPlayer) {
			if (player != this@FlowVideoView.player) {
				return
			}
			progressBar.visibility = GONE
			val dimensions = player.getDimensions()
			val wrapper = AspectRatioFrameLayout(context)
			wrapper.setAspectRatio(if (dimensions.y > 0) dimensions.x.toFloat() / dimensions.y else 0f)
			wrapper.addView(player.getVideoView(context),
					LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
			videoWrapper = wrapper
			// Insert below the cover (index 0) so the thumbnail hides the surface until first frame.
			addView(wrapper, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
			player.setPlaying(active)
			// Muted indicator, as in the gallery player.
			configurationView.removeAllViews()
			if (!player.isAudioPresent()) {
				val density = ResourceUtils.obtainDensity(context)
				val imageView = ImageView(context)
				imageView.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconActionVolumeOff, 0))
				imageView.scaleType = ImageView.ScaleType.CENTER
				imageView.imageAlpha = 0x99
				configurationView.addView(imageView, (48f * density).toInt(), (48f * density).toInt())
			}
			controlsView.visibility = VISIBLE
			controlsView.alpha = 1f
			controlsView.translationY = 0f
			controlsVisible = true
			updateControls()
			handler.removeCallbacks(progressRunnable)
			handler.post(progressRunnable)
		}

		override fun onError(player: VideoPlayer, message: String?) {
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

		override fun onBusyStateChange(player: VideoPlayer, busy: Boolean) {}

		override fun onDimensionChange(player: VideoPlayer) {
			if (player == this@FlowVideoView.player) {
				val dimensions = player.getDimensions()
				if (dimensions.y > 0) {
					videoWrapper?.setAspectRatio(dimensions.x.toFloat() / dimensions.y)
				}
			}
		}

		override fun onRenderedFirstFrame(player: VideoPlayer) {
			if (player == this@FlowVideoView.player) {
				coverView.visibility = GONE
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

	override fun onReadVideoProgressUpdate(progress: Long, progressMax: Long) {
		downloadProgress = progress
		downloadMax = progressMax
		player?.setDownloadRange(progress, progressMax)
	}

	override fun onReadVideoRangeUpdate(start: Long, end: Long) {
		player?.setPartRange(start, end)
	}

	override fun onReadVideoSuccess(partial: Boolean, file: File) {
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

	override fun onReadVideoFail(partial: Boolean, errorItem: ErrorItem?, disallowRangeRequests: Boolean) {
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
		dialogMenu.setTitle(if (!StringUtils.isEmpty(galleryItem.originalName)) galleryItem.originalName
				else galleryItem.getFileName(chan))
		dialogMenu.add(R.string.gallery) { callback?.onSwitchToGallery(galleryItem) }
		dialogMenu.add(R.string.save) {
			val binder = callback?.getDownloadBinder()
			if (binder != null) {
				galleryItem.downloadStorage(binder, chan, callback.getThreadTitle())
			}
		}
		if (player != null) {
			dialogMenu.add(R.string.metadata) { showMetadata() }
		}
		if (galleryItem.postNumber != null) {
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
			builder.append(key).append(": ").append(value).append('\n')
		}
		AlertDialog.Builder(context)
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

		/** Switch to the regular gallery, opened at this same attachment. */
		fun onSwitchToGallery(galleryItem: GalleryItem)
	}

	companion object {
		// Matches GalleryOverlay's action bar chrome colour.
		private const val CONTROLS_BACKGROUND_COLOR = 0xaa202020.toInt()
	}
}
