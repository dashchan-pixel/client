package com.mishiranu.dashchan.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.util.ConcurrentUtils
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Video player backed by Media3 ExoPlayer. Supports playback of complete files
 * as well as progressively downloaded partial files: the availability window is
 * updated via [setDownloadRange] and [setPartRange], and seeks outside the
 * downloaded region are forwarded to [RangeCallback].
 *
 * The partial-file streaming model is built on Media3 parts that are still marked
 * `@UnstableApi` (`DataSource`, `ProgressiveMediaSource`, `SeekParameters`, `C`,
 * `Format`), so the whole facade opts in once here.
 */
@OptIn(UnstableApi::class)
class VideoPlayer(
    private val listener: Listener,
    private val seekAnyFrame: Boolean,
) {
    interface Listener {
        fun onReady(player: VideoPlayer)

        fun onError(
            player: VideoPlayer,
            message: String?,
        )

        fun onComplete(player: VideoPlayer)

        fun onBusyStateChange(
            player: VideoPlayer,
            busy: Boolean,
        )

        fun onDimensionChange(player: VideoPlayer)

        fun onRenderedFirstFrame(player: VideoPlayer) {}
    }

    fun interface RangeCallback {
        fun requestPartFromPosition(start: Long)
    }

    class InitializationException(
        message: String?,
    ) : IOException(message)

    fun interface FrameCallback {
        fun onFrame(frame: Bitmap?)
    }

    private var exoPlayer: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null

    private val playerListener =
        object : Player.Listener {
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

            override fun onRenderedFirstFrame() {
                listener.onRenderedFirstFrame(this@VideoPlayer)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isTruncatedTailError(error)) {
                    // A file whose container index outruns its actual bytes (a "cut" clip: the last
                    // fragment/samples are missing) decodes fine until the extractor reads past the
                    // physical end, then throws EOF. We have effectively played the whole clip, so
                    // report normal completion instead of a failure. The player is left in its error
                    // state; setPosition re-prepares it on the next replay / loop / seek.
                    recoverableError = true
                    listener.onComplete(this@VideoPlayer)
                    return
                }
                listener.onError(this@VideoPlayer, error.message)
            }
        }
    private var ready = false
    private var released = false
    private var recoverableError = false

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
    fun init(
        file: File,
        rangeCallback: RangeCallback?,
    ) {
        check(exoPlayer == null && !released) { "Player is already initialized or released" }
        this.rangeCallback = rangeCallback
        if (rangeCallback == null) {
            complete = true
            downloadedBytes = file.length()
            totalBytes = downloadedBytes
        } else {
            // Keep an open handle: the partial file may be renamed once the download completes.
            partialFile = RandomAccessFile(file, "r")
        }
        val player = obtainPooledPlayer()
        exoPlayer = player
        player.setSeekParameters(if (seekAnyFrame) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.addListener(playerListener)
        if (rangeCallback == null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        } else {
            val factory = DataSource.Factory { PartialFileDataSource() }
            // The complete-file path goes through the player's DefaultMediaSourceFactory (which
            // carries EXTRACTORS_FACTORY); the streaming path builds its own source, so it has to
            // pass the same tuned extractors explicitly to inherit constant-bitrate seeking.
            player.setMediaSource(
                ProgressiveMediaSource
                    .Factory(factory, EXTRACTORS_FACTORY)
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(file))),
            )
        }
        player.prepare()
    }

    fun setDownloadRange(
        progress: Long,
        total: Long,
    ) {
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

    fun setPartRange(
        start: Long,
        end: Long,
    ) {
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

    /** Set the output volume in [0, 1]; 0 mutes. Applied to the current player only. */
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    /** Set the playback speed multiplier (1.0 = normal). Applied to the current player only. */
    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
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
        val player = exoPlayer ?: return
        if (recoverableError && player.playerError != null) {
            // Recover from a truncated-tail EOF (see onPlayerError): a fresh prepare() clears the
            // error and re-runs the extractor, so replay / loop / scrubbing works again. It will
            // play to the same truncated end and complete cleanly once more.
            recoverableError = false
            player.prepare()
        }
        player.seekTo(position)
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
        var surfaceView = this.surfaceView
        if (surfaceView == null) {
            // SurfaceView instead of TextureView: the decoder output goes straight to the
            // compositor (no GPU copy through the view hierarchy, lower power), and
            // ExoPlayer's Surface.setFrameRate votes reach the display, so fixed-rate
            // videos get refresh-rate matching on 90/120 Hz panels.
            surfaceView = SurfaceView(context)
            this.surfaceView = surfaceView
            exoPlayer!!.setVideoSurfaceView(surfaceView)
        }
        return surfaceView
    }

    /**
     * Asynchronously snapshots the current video frame via [PixelCopy]. The callback is
     * invoked on the main thread with null if the frame could not be captured.
     */
    fun captureCurrentFrame(callback: FrameCallback) {
        val surfaceView = this.surfaceView
        if (surfaceView == null ||
            surfaceView.width <= 0 ||
            surfaceView.height <= 0 ||
            !surfaceView.holder.surface.isValid
        ) {
            callback.onFrame(null)
            return
        }
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    callback.onFrame(bitmap)
                } else {
                    bitmap.recycle()
                    callback.onFrame(null)
                }
            }, ConcurrentUtils.HANDLER)
        } catch (e: IllegalArgumentException) {
            // The surface was released between the validity check and the request
            bitmap.recycle()
            callback.onFrame(null)
        }
    }

    fun destroy() {
        if (released) {
            return
        }
        released = true
        synchronized(rangeLock) {
            rangeLock.notifyAll()
        }
        exoPlayer?.let { player ->
            player.removeListener(playerListener)
            player.clearVideoSurface()
            recyclePooledPlayer(player)
        }
        exoPlayer = null
        try {
            partialFile?.close()
        } catch (e: IOException) {
            // Ignore
        }
        partialFile = null
    }

    // True when the error is a source-side EOF (the file's bytes ran out mid-parse) that surfaces
    // after playback has started -- i.e. a truncated file, not a stream that failed to prepare or a
    // decoder failure. A browser plays whatever was readable and stops; we do the same by reporting
    // completion. When the container declares a duration we additionally require being near it (so a
    // clip cut off early still errors); when the duration is unknown -- e.g. a WebM with no Duration
    // element -- having reached a ready, playing-into-the-stream state is all we can rely on.
    private fun isTruncatedTailError(error: PlaybackException): Boolean {
        val isEof = generateSequence<Throwable>(error) { it.cause }.any { it is EOFException }
        if (!isEof || !ready) {
            return false
        }
        val duration = getDuration()
        val position = getPosition()
        return if (duration > 0) {
            position >= duration - END_OF_STREAM_TOLERANCE_MS
        } else {
            position > 0
        }
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
                if (availableAt(position) == 0L &&
                    rangeCallback != null &&
                    position >= downloadedBytes &&
                    !(position in partStart until partEnd)
                ) {
                    rangeCallback.requestPartFromPosition(position)
                }
            }
            opened = true
            transferStarted(dataSpec)
            val total: Long
            synchronized(rangeLock) {
                total = totalBytes
            }
            bytesRemaining =
                when {
                    dataSpec.length !=
                        androidx.media3.common.C.LENGTH_UNSET
                            .toLong()
                    -> {
                        dataSpec.length
                    }

                    total > 0 -> {
                        total - position
                    }

                    else -> {
                        androidx.media3.common.C.LENGTH_UNSET
                            .toLong()
                    }
                }
            return bytesRemaining
        }

        @Throws(IOException::class)
        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
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
            if (bytesRemaining !=
                androidx.media3.common.C.LENGTH_UNSET
                    .toLong()
            ) {
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
                if (bytesRemaining !=
                    androidx.media3.common.C.LENGTH_UNSET
                        .toLong()
                ) {
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
        private const val POOL_SIZE = 4

        // How close to the declared duration playback must be for a source EOF to count as a
        // benign truncated tail rather than a genuine mid-stream failure.
        private const val END_OF_STREAM_TOLERANCE_MS = 1000L

        // Idle ExoPlayer instances kept for reuse (player + playback thread construction is
        // skipped on the next init()). Main-thread only, like every ExoPlayer interaction here.
        private val playerPool = ArrayDeque<ExoPlayer>()

        // Shared extractor tuning. Applied to the complete-file path via the player's media-source
        // factory and to the streaming path via ProgressiveMediaSource.Factory. MP3/ADTS(AAC)/AMR
        // streams carry no seek index, so without constant-bitrate seeking their duration comes back
        // wrong (or unknown) and a scrub/rewind into the un-indexed region fails; "…Always" extends
        // that estimate to streams whose total length isn't known yet (a still-downloading partial
        // file), so the scrubber reads right and rewind works mid-download too. No effect on the
        // seekable containers (mp4/mkv/webm) that make up most clips.
        private val EXTRACTORS_FACTORY: DefaultExtractorsFactory =
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

        private fun buildPlayer(): ExoPlayer {
            val context = MainApplication.getInstance()
            // Enable decoder fallback: if the primary (usually hardware) decoder fails to
            // initialize or decode — common with the off-spec H.264/HEVC/VP9 profiles that turn up
            // on imageboards — the renderer retries on the next decoder (typically the platform's
            // built-in software MediaCodec) instead of surfacing a fatal playback error on the
            // first frame.
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
            return ExoPlayer
                .Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, EXTRACTORS_FACTORY))
                .build()
        }

        /** Pre-create idle players (up to the pool cap) so upcoming [init] calls start warm. */
        @JvmStatic
        fun prewarm(count: Int) {
            ConcurrentUtils.HANDLER.post {
                while (playerPool.size < minOf(count, POOL_SIZE)) {
                    playerPool.addLast(buildPlayer())
                }
            }
        }

        private fun obtainPooledPlayer(): ExoPlayer = playerPool.removeFirstOrNull() ?: buildPlayer()

        private fun recyclePooledPlayer(player: ExoPlayer) {
            // Silence before pausing/stopping: stop() flushes the audio track, and any samples
            // already queued would otherwise leak a brief audible tail after the video is closed.
            // A reused player has its volume restored in onReady before it starts playing again.
            player.volume = 0f
            // A player that hit a playback error is not trusted for reuse.
            if (playerPool.size >= POOL_SIZE || player.playerError != null) {
                player.release()
                return
            }
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            playerPool.addLast(player)
        }

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
