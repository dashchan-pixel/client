package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import chan.content.Chan
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.AdvancedPreferences
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.async.ReadVideoTask
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.media.VideoPlayer
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.widget.AspectRatioFrameLayout
import java.io.File
import java.io.IOException

/**
 * A single page of the video Flow feed (reels/TikTok-style). Downloads one video attachment
 * progressively — mirroring the gallery's [com.mishiranu.dashchan.ui.gallery.VideoUnit] —
 * and plays it looped. The host calls [setActive] to start/resume playback when the page
 * scrolls into view and to pause it when it leaves, and [recycle] when the view is reused
 * by the RecyclerView for a different item.
 */
class FlowVideoView(context: Context) : FrameLayout(context),
		ReadVideoTask.Callback, VideoPlayer.RangeCallback {
	private val coverView = ImageView(context)
	private val progressBar = ProgressBar(context)
	private val errorView = TextView(context)
	private var videoWrapper: AspectRatioFrameLayout? = null

	private var chan: Chan? = null
	private var uri: Uri? = null

	private var player: VideoPlayer? = null
	private var downloadTask: ReadVideoTask? = null
	private var rangeTask: ReadVideoTask? = null
	private var allowRangeRequests = true
	private var active = false
	private var started = false

	init {
		setBackgroundColor(Color.BLACK)
		coverView.scaleType = ImageView.ScaleType.FIT_CENTER
		addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
		addView(progressBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
		progressBar.visibility = GONE
		errorView.setTextColor(Color.WHITE)
		errorView.visibility = GONE
		addView(errorView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
		setOnClickListener { toggle() }
	}

	fun bind(chan: Chan, galleryItem: GalleryItem) {
		recycle()
		this.chan = chan
		this.uri = galleryItem.getFileUri(chan)
		coverView.visibility = VISIBLE
		coverView.setImageDrawable(null)
		val thumbnailUri = galleryItem.getThumbnailUri(chan)
		if (thumbnailUri != null) {
			ImageLoader.getInstance().loadImage(chan, thumbnailUri, false, coverView)
		}
	}

	/** Start/resume playback when true (the page is centered), pause when false. */
	fun setActive(active: Boolean) {
		this.active = active
		if (active) {
			if (!started) {
				start()
			} else {
				player?.setPlaying(true)
			}
		} else {
			player?.setPlaying(false)
		}
	}

	private fun toggle() {
		val player = player ?: return
		player.setPlaying(!player.isPlaying())
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
		active = false
		started = false
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
		coverView.visibility = VISIBLE
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
		}

		override fun onError(player: VideoPlayer, message: String?) {
			if (player == this@FlowVideoView.player) {
				showError()
			}
		}

		override fun onComplete(player: VideoPlayer) {
			if (player == this@FlowVideoView.player) {
				// Loop the clip, reels-style.
				player.setPosition(0)
				player.setPlaying(active)
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
}
