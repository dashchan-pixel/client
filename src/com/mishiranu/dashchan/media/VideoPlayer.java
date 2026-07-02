package com.mishiranu.dashchan.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Video player backed by Media3 ExoPlayer. Supports playback of complete files
 * as well as progressively downloaded partial files: the availability window is
 * updated via {@link #setDownloadRange(long, long)} and {@link #setPartRange(long, long)},
 * and seeks outside the downloaded region are forwarded to {@link RangeCallback}.
 */
public class VideoPlayer {
	public interface Listener {
		void onReady(VideoPlayer player);
		void onError(VideoPlayer player, String message);
		void onComplete(VideoPlayer player);
		void onBusyStateChange(VideoPlayer player, boolean busy);
		void onDimensionChange(VideoPlayer player);
	}

	public interface RangeCallback {
		void requestPartFromPosition(long start);
	}

	public static class InitializationException extends IOException {
		public InitializationException(String message) {
			super(message);
		}
	}

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Listener listener;
	private final boolean seekAnyFrame;

	private ExoPlayer exoPlayer;
	private TextureView textureView;
	private boolean ready = false;
	private boolean released = false;

	// Availability window of the partial file, guarded by rangeLock.
	private final Object rangeLock = new Object();
	private RandomAccessFile partialFile;
	private RangeCallback rangeCallback;
	private long downloadedBytes;
	private long totalBytes = -1;
	private long partStart = -1;
	private long partEnd = -1;
	private boolean complete = false;

	public VideoPlayer(Listener listener, boolean seekAnyFrame) {
		this.listener = listener;
		this.seekAnyFrame = seekAnyFrame;
	}

	public void init(File file, RangeCallback rangeCallback) throws IOException {
		if (exoPlayer != null || released) {
			throw new IllegalStateException("Player is already initialized or released");
		}
		this.rangeCallback = rangeCallback;
		if (rangeCallback == null) {
			complete = true;
			downloadedBytes = totalBytes = file.length();
		} else {
			// Keep an open handle: the partial file may be renamed once the download completes.
			partialFile = new RandomAccessFile(file, "r");
		}
		ExoPlayer player = new ExoPlayer.Builder(com.mishiranu.dashchan.content.MainApplication.getInstance()).build();
		exoPlayer = player;
		player.setSeekParameters(seekAnyFrame ? SeekParameters.CLOSEST_SYNC : SeekParameters.DEFAULT);
		player.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(int playbackState) {
				if (playbackState == Player.STATE_READY && !ready) {
					ready = true;
					listener.onReady(VideoPlayer.this);
				}
				listener.onBusyStateChange(VideoPlayer.this, playbackState == Player.STATE_BUFFERING);
				if (playbackState == Player.STATE_ENDED) {
					listener.onComplete(VideoPlayer.this);
				}
			}

			@Override
			public void onVideoSizeChanged(VideoSize videoSize) {
				if (ready) {
					listener.onDimensionChange(VideoPlayer.this);
				}
			}

			@Override
			public void onPlayerError(PlaybackException error) {
				listener.onError(VideoPlayer.this, error.getMessage());
			}
		});
		if (rangeCallback == null) {
			player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
		} else {
			DataSource.Factory factory = PartialFileDataSource::new;
			player.setMediaSource(new ProgressiveMediaSource.Factory(factory)
					.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))));
		}
		player.prepare();
	}

	public void setDownloadRange(long progress, long total) {
		synchronized (rangeLock) {
			downloadedBytes = progress;
			if (total > 0) {
				totalBytes = total;
			}
			if (total > 0 && progress >= total) {
				complete = true;
			}
			rangeLock.notifyAll();
		}
	}

	public void setPartRange(long start, long end) {
		synchronized (rangeLock) {
			partStart = start;
			partEnd = end;
			rangeLock.notifyAll();
		}
	}

	public boolean isPlaying() {
		return exoPlayer != null && exoPlayer.getPlayWhenReady();
	}

	public void setPlaying(boolean playing) {
		if (exoPlayer != null) {
			exoPlayer.setPlayWhenReady(playing);
		}
	}

	public Point getDimensions() {
		if (exoPlayer == null) {
			return new Point();
		}
		VideoSize size = exoPlayer.getVideoSize();
		return new Point(size.width, size.height);
	}

	public boolean isAudioPresent() {
		return exoPlayer != null && exoPlayer.getAudioFormat() != null;
	}

	public long getDuration() {
		long duration = exoPlayer != null ? exoPlayer.getDuration() : C.TIME_UNSET;
		return duration == C.TIME_UNSET ? -1 : duration;
	}

	public long getPosition() {
		return exoPlayer != null ? exoPlayer.getCurrentPosition() : 0;
	}

	public void setPosition(long position) {
		if (exoPlayer != null) {
			exoPlayer.seekTo(position);
		}
	}

	public Map<String, String> getMetadata() {
		LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
		if (exoPlayer != null) {
			Format video = exoPlayer.getVideoFormat();
			if (video != null) {
				metadata.put("video_format", formatName(video));
				metadata.put("width", Integer.toString(video.width));
				metadata.put("height", Integer.toString(video.height));
				if (video.frameRate != Format.NO_VALUE) {
					metadata.put("frame_rate", Float.toString(video.frameRate));
				}
			}
			Format audio = exoPlayer.getAudioFormat();
			if (audio != null) {
				metadata.put("audio_format", formatName(audio));
				if (audio.channelCount != Format.NO_VALUE) {
					metadata.put("channels", Integer.toString(audio.channelCount));
				}
				if (audio.sampleRate != Format.NO_VALUE) {
					metadata.put("sample_rate", Integer.toString(audio.sampleRate));
				}
			}
			CharSequence title = exoPlayer.getMediaMetadata().title;
			if (title != null) {
				metadata.put("title", title.toString());
			}
		}
		return metadata;
	}

	private static String formatName(Format format) {
		String mimeType = format.sampleMimeType;
		if (mimeType == null) {
			return "unknown";
		}
		switch (mimeType) {
			case "video/x-vnd.on2.vp8": return "VP8";
			case "video/x-vnd.on2.vp9": return "VP9";
			case "video/av01": return "AV1";
			case "video/avc": return "H.264";
			case "video/hevc": return "H.265";
			case "audio/mp4a-latm": return "AAC";
			case "audio/opus": return "Opus";
			case "audio/vorbis": return "Vorbis";
			case "audio/mpeg": return "MP3";
			default: return mimeType.substring(mimeType.indexOf('/') + 1);
		}
	}

	public View getVideoView(Context context) {
		if (textureView == null) {
			textureView = new TextureView(context);
			exoPlayer.setVideoTextureView(textureView);
		}
		return textureView;
	}

	public Bitmap getCurrentFrame() {
		return textureView != null ? textureView.getBitmap() : null;
	}

	public void destroy() {
		if (released) {
			return;
		}
		released = true;
		synchronized (rangeLock) {
			rangeLock.notifyAll();
		}
		if (exoPlayer != null) {
			exoPlayer.release();
			exoPlayer = null;
		}
		if (partialFile != null) {
			try {
				partialFile.close();
			} catch (IOException e) {
				// Ignore
			}
			partialFile = null;
		}
	}

	// Returns the number of contiguous bytes available at position, 0 if none yet, -1 at end of file.
	private long availableAt(long position) {
		if (totalBytes > 0 && position >= totalBytes) {
			return -1;
		}
		if (complete) {
			return totalBytes - position;
		}
		if (position < downloadedBytes) {
			return downloadedBytes - position;
		}
		if (position >= partStart && position < partEnd) {
			return partEnd - position;
		}
		return 0;
	}

	private class PartialFileDataSource extends BaseDataSource {
		private long position;
		private long bytesRemaining;
		private boolean opened = false;

		PartialFileDataSource() {
			super(false);
		}

		@Override
		public long open(DataSpec dataSpec) throws IOException {
			position = dataSpec.position;
			transferInitializing(dataSpec);
			synchronized (rangeLock) {
				if (availableAt(position) == 0 && rangeCallback != null
						&& position >= downloadedBytes && !(position >= partStart && position < partEnd)) {
					rangeCallback.requestPartFromPosition(position);
				}
			}
			opened = true;
			transferStarted(dataSpec);
			long total;
			synchronized (rangeLock) {
				total = totalBytes;
			}
			bytesRemaining = dataSpec.length != C.LENGTH_UNSET ? dataSpec.length
					: total > 0 ? total - position : C.LENGTH_UNSET;
			return bytesRemaining;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (bytesRemaining == 0) {
				return C.RESULT_END_OF_INPUT;
			}
			long available;
			synchronized (rangeLock) {
				while (true) {
					available = availableAt(position);
					if (available != 0 || released) {
						break;
					}
					try {
						rangeLock.wait(1000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for data", e);
					}
				}
			}
			if (released) {
				throw new IOException("Player released");
			}
			if (available < 0) {
				return C.RESULT_END_OF_INPUT;
			}
			int toRead = (int) Math.min(length, available);
			if (bytesRemaining != C.LENGTH_UNSET) {
				toRead = (int) Math.min(toRead, bytesRemaining);
			}
			int read;
			RandomAccessFile file = partialFile;
			if (file == null) {
				throw new IOException("Player released");
			}
			synchronized (file) {
				file.seek(position);
				read = file.read(buffer, offset, toRead);
			}
			if (read > 0) {
				position += read;
				if (bytesRemaining != C.LENGTH_UNSET) {
					bytesRemaining -= read;
				}
				bytesTransferred(read);
			}
			return read;
		}

		@Override
		public Uri getUri() {
			return null;
		}

		@Override
		public void close() {
			if (opened) {
				opened = false;
				transferEnded();
			}
		}
	}
}
