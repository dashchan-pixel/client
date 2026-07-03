package com.mishiranu.dashchan.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.view.TextureView
import android.view.View
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.mishiranu.dashchan.content.MainApplication
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Video player backed by Media3 ExoPlayer. Supports playback of complete files
 * as well as progressively downloaded partial files: the availability window is
 * updated via [setDownloadRange] and [setPartRange], and seeks outside the
 * downloaded region are forwarded to [RangeCallback].
 */
class VideoPlayer(private val listener: Listener, private val seekAnyFrame: Boolean) {
	interface Listener {
		fun onReady(player: VideoPlayer)
		fun onError(player: VideoPlayer, message: String?)
		fun onComplete(player: VideoPlayer)
		fun onBusyStateChange(player: VideoPlayer, busy: Boolean)
		fun onDimensionChange(player: VideoPlayer)
	}

	fun interface RangeCallback {
		fun requestPartFromPosition(start: Long)
	}

	class InitializationException(message: String?) : IOException(message)

	private var exoPlayer: ExoPlayer? = null
	private var textureView: TextureView? = null
	private var ready = false
	private var released = false

	// Availability window of the partial file, guarded by rangeLock.
	private val rangeLock = Object()
	private var partialFile: RandomAccessFile? = null
	private var rangeCallback: RangeCallback? = null
	private var downloadedBytes = 0L
	private var totalBytes = -1L
	private var partStart = -1L
	private var partEnd = -1L
	private var complete = false

	@Throws(IOException::class)
	fun init(file: File, rangeCallback: RangeCallback?) {
		if (exoPlayer != null || released) {
			throw IllegalStateException("Player is already initialized or released")
		}
		this.rangeCallback = rangeCallback
		if (rangeCallback == null) {
			complete = true
			downloadedBytes = file.length()
			totalBytes = downloadedBytes
		} else {
			// Keep an open handle: the partial file may be renamed once the download completes.
			partialFile = RandomAccessFile(file, "r")
		}
		val player = ExoPlayer.Builder(MainApplication.getInstance()).build()
		exoPlayer = player
		player.setSeekParameters(if (seekAnyFrame) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT)
		player.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_READY && !ready) {
					ready = true
					listener.onReady(this@VideoPlayer)
				}
				listener.onBusyStateChange(this@VideoPlayer, playbackState == Player.STATE_BUFFERING)
				if (playbackState == Player.STATE_ENDED) {
					listener.onComplete(this@VideoPlayer)
				}
			}

			override fun onVideoSizeChanged(videoSize: VideoSize) {
				if (ready) {
					listener.onDimensionChange(this@VideoPlayer)
				}
			}

			override fun onPlayerError(error: PlaybackException) {
				listener.onError(this@VideoPlayer, error.message)
			}
		})
		if (rangeCallback == null) {
			player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
		} else {
			val factory = DataSource.Factory { PartialFileDataSource() }
			player.setMediaSource(ProgressiveMediaSource.Factory(factory)
					.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
		}
		player.prepare()
	}

	fun setDownloadRange(progress: Long, total: Long) {
		synchronized(rangeLock) {
			downloadedBytes = progress
			if (total > 0) {
				totalBytes = total
			}
			if (total in 1..progress) {
				complete = true
			}
			rangeLock.notifyAll()
		}
	}

	fun setPartRange(start: Long, end: Long) {
		synchronized(rangeLock) {
			partStart = start
			partEnd = end
			rangeLock.notifyAll()
		}
	}

	fun isPlaying(): Boolean = exoPlayer?.playWhenReady == true

	fun setPlaying(playing: Boolean) {
		exoPlayer?.playWhenReady = playing
	}

	fun getDimensions(): Point {
		val player = exoPlayer ?: return Point()
		val size = player.videoSize
		return Point(size.width, size.height)
	}

	fun isAudioPresent(): Boolean = exoPlayer?.audioFormat != null

	fun getDuration(): Long {
		val duration = exoPlayer?.duration ?: androidx.media3.common.C.TIME_UNSET
		return if (duration == androidx.media3.common.C.TIME_UNSET) -1 else duration
	}

	fun getPosition(): Long = exoPlayer?.currentPosition ?: 0

	fun setPosition(position: Long) {
		exoPlayer?.seekTo(position)
	}

	fun getMetadata(): Map<String, String> {
		val metadata = LinkedHashMap<String, String>()
		val player = exoPlayer
		if (player != null) {
			val video = player.videoFormat
			if (video != null) {
				metadata["video_format"] = formatName(video)
				metadata["width"] = video.width.toString()
				metadata["height"] = video.height.toString()
				if (video.frameRate != Format.NO_VALUE.toFloat()) {
					metadata["frame_rate"] = video.frameRate.toString()
				}
			}
			val audio = player.audioFormat
			if (audio != null) {
				metadata["audio_format"] = formatName(audio)
				if (audio.channelCount != Format.NO_VALUE) {
					metadata["channels"] = audio.channelCount.toString()
				}
				if (audio.sampleRate != Format.NO_VALUE) {
					metadata["sample_rate"] = audio.sampleRate.toString()
				}
			}
			val title = player.mediaMetadata.title
			if (title != null) {
				metadata["title"] = title.toString()
			}
		}
		return metadata
	}

	fun getVideoView(context: Context): View {
		var textureView = this.textureView
		if (textureView == null) {
			textureView = TextureView(context)
			this.textureView = textureView
			exoPlayer!!.setVideoTextureView(textureView)
		}
		return textureView
	}

	fun getCurrentFrame(): Bitmap? = textureView?.bitmap

	fun destroy() {
		if (released) {
			return
		}
		released = true
		synchronized(rangeLock) {
			rangeLock.notifyAll()
		}
		exoPlayer?.release()
		exoPlayer = null
		try {
			partialFile?.close()
		} catch (e: IOException) {
			// Ignore
		}
		partialFile = null
	}

	// Returns the number of contiguous bytes available at position, 0 if none yet, -1 at end of file.
	private fun availableAt(position: Long): Long {
		if (totalBytes > 0 && position >= totalBytes) {
			return -1
		}
		if (complete) {
			return totalBytes - position
		}
		if (position < downloadedBytes) {
			return downloadedBytes - position
		}
		if (position in partStart until partEnd) {
			return partEnd - position
		}
		return 0
	}

	private inner class PartialFileDataSource : BaseDataSource(false) {
		private var position = 0L
		private var bytesRemaining = 0L
		private var opened = false

		@Throws(IOException::class)
		override fun open(dataSpec: DataSpec): Long {
			position = dataSpec.position
			transferInitializing(dataSpec)
			synchronized(rangeLock) {
				val rangeCallback = rangeCallback
				if (availableAt(position) == 0L && rangeCallback != null &&
						position >= downloadedBytes &&
						!(position in partStart until partEnd)) {
					rangeCallback.requestPartFromPosition(position)
				}
			}
			opened = true
			transferStarted(dataSpec)
			val total: Long
			synchronized(rangeLock) {
				total = totalBytes
			}
			bytesRemaining = when {
				dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong() -> dataSpec.length
				total > 0 -> total - position
				else -> androidx.media3.common.C.LENGTH_UNSET.toLong()
			}
			return bytesRemaining
		}

		@Throws(IOException::class)
		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			if (length == 0) {
				return 0
			}
			if (bytesRemaining == 0L) {
				return androidx.media3.common.C.RESULT_END_OF_INPUT
			}
			var available: Long
			synchronized(rangeLock) {
				while (true) {
					available = availableAt(position)
					if (available != 0L || released) {
						break
					}
					try {
						rangeLock.wait(1000)
					} catch (e: InterruptedException) {
						Thread.currentThread().interrupt()
						throw IOException("Interrupted while waiting for data", e)
					}
				}
			}
			if (released) {
				throw IOException("Player released")
			}
			if (available < 0) {
				return androidx.media3.common.C.RESULT_END_OF_INPUT
			}
			var toRead = minOf(length.toLong(), available).toInt()
			if (bytesRemaining != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
				toRead = minOf(toRead.toLong(), bytesRemaining).toInt()
			}
			val file = partialFile ?: throw IOException("Player released")
			val read: Int
			synchronized(file) {
				file.seek(position)
				read = file.read(buffer, offset, toRead)
			}
			if (read > 0) {
				position += read
				if (bytesRemaining != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
					bytesRemaining -= read
				}
				bytesTransferred(read)
			}
			return read
		}

		override fun getUri(): Uri? = null

		override fun close() {
			if (opened) {
				opened = false
				transferEnded()
			}
		}
	}

	companion object {
		private fun formatName(format: Format): String {
			val mimeType = format.sampleMimeType ?: return "unknown"
			return when (mimeType) {
				"video/x-vnd.on2.vp8" -> "VP8"
				"video/x-vnd.on2.vp9" -> "VP9"
				"video/av01" -> "AV1"
				"video/avc" -> "H.264"
				"video/hevc" -> "H.265"
				"audio/mp4a-latm" -> "AAC"
				"audio/opus" -> "Opus"
				"audio/vorbis" -> "Vorbis"
				"audio/mpeg" -> "MP3"
				else -> mimeType.substring(mimeType.indexOf('/') + 1)
			}
		}
	}
}
