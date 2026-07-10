package com.mishiranu.dashchan

import java.util.Collections

object C {
	@JvmField val IMAGE_EXTENSIONS: Set<String> =
			immutableSet("jpg", "jpe", "jpeg", "png", "apng", "gif", "webp", "bmp", "svg")
	@JvmField val AUDIO_EXTENSIONS: Set<String> = immutableSet("mp3", "ogg", "flac", "wav")
	@JvmField val VIDEO_EXTENSIONS: Set<String> = immutableSet("webm", "mkv", "mp4")

	@JvmField val OPENABLE_VIDEO_EXTENSIONS: Set<String> = immutableSet("webm", "mkv", "mp4")

	@JvmField val EXTENSION_TRANSFORMATION: Map<String, String> = Collections.unmodifiableMap(
			hashMapOf("jpg" to "jpeg", "jpe" to "jpeg", "apng" to "png"))

	private fun <T> immutableSet(vararg items: T): Set<T> {
		val hashSet = HashSet<T>()
		for (item in items) {
			if (item != null) {
				hashSet.add(item)
			}
		}
		return Collections.unmodifiableSet(hashSet)
	}

	const val DEFAULT_DOWNLOAD_PATH = "/Download/Dashchan/"

	const val ACTION_POSTING = "com.mishiranu.dashchan.action.POSTING"
	const val ACTION_GALLERY = "com.mishiranu.dashchan.action.GALLERY"
	const val ACTION_PLAYER = "com.mishiranu.dashchan.action.PLAYER"
	const val ACTION_VIDEO_PIP = "com.mishiranu.dashchan.action.VIDEO_PIP"
	const val ACTION_BROWSER = "com.mishiranu.dashchan.action.BROWSER"

	const val NOTIFICATION_ID_POSTING = 1
	const val NOTIFICATION_ID_DOWNLOADING = 2
	const val NOTIFICATION_ID_AUDIO_PLAYER = 3
	const val NOTIFICATION_ID_UPDATES = 4
	const val NOTIFICATION_ID_REPLIES = 5

	const val NOTIFICATION_CHANNEL_POSTING = "posting"
	const val NOTIFICATION_CHANNEL_POSTING_COMPLETE = "postingComplete"
	const val NOTIFICATION_CHANNEL_DOWNLOADING = "downloading"
	const val NOTIFICATION_CHANNEL_DOWNLOADING_COMPLETE = "downloadingComplete"
	const val NOTIFICATION_CHANNEL_AUDIO_PLAYER = "audioPlayer"
	const val NOTIFICATION_CHANNEL_UPDATES = "updates"
	const val NOTIFICATION_CHANNEL_REPLIES = "replies"

	const val EXTRA_BOARD_NAME = "com.mishiranu.dashchan.extra.BOARD_NAME"
	const val EXTRA_CHAN_NAME = "com.mishiranu.dashchan.extra.CHAN_NAME"
	const val EXTRA_FAIL_RESULT = "com.mishiranu.dashchan.extra.FAIL_RESULT"
	const val EXTRA_POST_NUMBER = "com.mishiranu.dashchan.extra.POST_NUMBER"
	const val EXTRA_FROM_CLIENT = "com.mishiranu.dashchan.extra.FROM_CLIENT"
	const val EXTRA_THREAD_NUMBER = "com.mishiranu.dashchan.extra.THREAD_NUMBER"
	const val EXTRA_UPDATE_DATA_MAP = "com.mishiranu.dashchan.extra.UPDATE_DATA_MAP"
}
