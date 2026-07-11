package com.mishiranu.dashchan.content.async

import android.net.Uri
import android.util.Log
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanManager
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.util.DataFile
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.model.ErrorItem
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class ReadFileTask private constructor(private val callback: Callback, private val chan: Chan,
		private val fromUri: Uri, private val toFile: DataFile, private val cachedMediaFile: File?,
		private val overwrite: Boolean, private val checkSha256: ByteArray?,
		private val checkFingerprints: ChanManager.Fingerprints?) : HttpHolderTask<LongArray, Boolean>(chan) {
	interface Callback {
		fun onStartDownloading()
		fun onFinishDownloading(success: Boolean, uri: Uri, file: DataFile, errorItem: ErrorItem?)
		fun onCancelDownloading() {}
		fun onUpdateProgress(progress: Long, progressMax: Long)
	}

	interface FileCallback : Callback {
		fun onFinishDownloading(success: Boolean, uri: Uri, file: File, errorItem: ErrorItem?)

		override fun onFinishDownloading(success: Boolean, uri: Uri, file: DataFile, errorItem: ErrorItem?) {
			val realFile = file.getFileOrUri().first ?: throw IllegalStateException()
			onFinishDownloading(success, uri, realFile, errorItem)
		}
	}

	private var errorItem: ErrorItem? = null

	private var loadingStarted = false

	private val progressHandler = object : TimedProgressHandler() {
		override fun onProgressChange(progress: Long, progressMax: Long) {
			notifyProgress(longArrayOf(progress, progressMax))
		}
	}

	override fun onPrepare() {
		callback.onStartDownloading()
	}

	override fun run(holder: HttpHolder): Boolean {
		var success = false
		try {
			loadingStarted = true
			var digest: MessageDigest? = null
			if (checkSha256 != null) {
				digest = try {
					MessageDigest.getInstance("SHA-256")
				} catch (e: NoSuchAlgorithmException) {
					throw RuntimeException(e)
				}
			}
			if (!overwrite && toFile.exists()) {
				// Do nothing
			} else if (cachedMediaFile != null) {
				progressHandler.setInputProgressMax(cachedMediaFile.length())
				try {
					FileInputStream(cachedMediaFile).use { input ->
						toFile.openOutputStream().use { output ->
							copyStream(input, output, progressHandler, digest)
						}
					}
				} catch (e: IOException) {
					val type = getErrorTypeFromExceptionAndHandle(e)
					errorItem = ErrorItem(type ?: ErrorItem.Type.UNKNOWN)
					return false
				}
			} else if (ChanConfiguration.SCHEME_CHAN == fromUri.scheme) {
				try {
					toFile.openOutputStream().use { output ->
						if (!chan.configuration.readResourceUri(fromUri, output)) {
							throw HttpException.createNotFoundException()
						}
					}
				} catch (e: IOException) {
					val type = getErrorTypeFromExceptionAndHandle(e)
					errorItem = ErrorItem(type ?: ErrorItem.Type.UNKNOWN)
					return false
				}
			} else {
				val result = chan.performer.safe()
						.onReadContent(ChanPerformer.ReadContentData(fromUri,
								CONNECT_TIMEOUT, READ_TIMEOUT, holder, -1, -1))
				val response = result?.response
				if (response == null) {
					errorItem = ErrorItem(ErrorItem.Type.DOWNLOAD)
					return false
				}
				progressHandler.setInputProgressMax(response.length)
				try {
					response.open().use { input ->
						toFile.openOutputStream().use { output ->
							copyStream(input, output, progressHandler, digest)
						}
					}
				} catch (e: IOException) {
					val errorType = getErrorTypeFromExceptionAndHandle(e)
					if (errorType != null) {
						errorItem = ErrorItem(errorType)
						return false
					} else {
						throw response.fail(e)
					}
				} finally {
					response.cleanupAndDisconnect()
				}
			}
			if (digest != null) {
				val sha256 = digest.digest()
				if (!sha256.contentEquals(checkSha256)) {
					Log.e("ReadFileTask", "SHA-256 validation failed: requested " +
							checkSha256.contentToString() + ", got " + sha256.contentToString())
					errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
					return false
				}
			}
			if (checkFingerprints != null) {
				var errorReason: String? = null
				val file = toFile.getFileOrUri().first
				if (file == null) {
					errorReason = "not a regular file"
				} else {
					val fingerprints = ChanManager.getInstance().getFingerprints(file)
					if (fingerprints == null) {
						errorReason = "invalid file"
					} else {
						if (checkFingerprints != fingerprints) {
							errorReason = "fingerprints do not match"
						}
					}
				}
				if (errorReason != null) {
					Log.e("ReadFileTask", "Fingerprint validation failed: $errorReason")
					errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
					return false
				}
			}
			success = true
			return true
		} catch (e: ExtensionException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: HttpException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: InvalidResponseException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} finally {
			if (!success) {
				toFile.delete()
			}
			val file = toFile.getFileOrUri().first
			if (file != null) {
				CacheManager.getInstance().handleDownloadedFile(file, success)
			}
			if (chan.name != null) {
				chan.configuration.commit()
			}
		}
	}

	override fun onProgress(values: LongArray) {
		callback.onUpdateProgress(values[0], values[1])
	}

	override fun onComplete(success: Boolean) {
		callback.onFinishDownloading(success, fromUri, toFile, errorItem)
	}

	fun isDownloadingFromCache(): Boolean = cachedMediaFile != null

	fun getFileName(): String? = toFile.getName()

	override fun cancel() {
		super.cancel()

		if (loadingStarted) {
			toFile.delete()
			val file = toFile.getFileOrUri().first
			if (file != null) {
				CacheManager.getInstance().handleDownloadedFile(file, false)
			}
		}
		callback.onCancelDownloading()
	}

	companion object {
		private const val CONNECT_TIMEOUT = 15000
		private const val READ_TIMEOUT = 15000

		@Throws(IOException::class)
		private fun copyStream(input: InputStream, output: OutputStream,
				progressHandler: TimedProgressHandler, digest: MessageDigest?) {
			val data = ByteArray(8192)
			var count: Int
			var read = 0L
			while (input.read(data).also { count = it } != -1) {
				output.write(data, 0, count)
				read += count
				progressHandler.updateProgress(read)
				digest?.update(data, 0, count)
			}
		}

		@JvmStatic
		fun createCachedMediaFile(callback: FileCallback, chan: Chan,
				fromUri: Uri, cachedMediaFile: File): ReadFileTask {
			val toFile = DataFile.obtain(DataFile.Target.CACHE, cachedMediaFile.name)
			return ReadFileTask(callback, chan, fromUri, toFile, null, true, null, null)
		}

		@JvmStatic
		fun createShared(callback: Callback, chan: Chan, fromUri: Uri, toFile: DataFile,
				overwrite: Boolean, checkSha256: ByteArray?,
				checkFingerprints: ChanManager.Fingerprints?): ReadFileTask {
			var cachedMediaFile = CacheManager.getInstance().getMediaFile(fromUri, true)
			if (cachedMediaFile == null || !cachedMediaFile.exists() ||
					CacheManager.getInstance().cancelCachedMediaBusy(cachedMediaFile)) {
				cachedMediaFile = null
			}
			return ReadFileTask(callback, chan, fromUri, toFile, cachedMediaFile,
					overwrite, checkSha256, checkFingerprints)
		}

		@JvmStatic
		fun getErrorTypeFromExceptionAndHandle(exception: IOException): ErrorItem.Type? {
			if (exception is FileNotFoundException) {
				exception.printStackTrace()
				return ErrorItem.Type.NO_ACCESS_TO_MEMORY
			} else {
				val message = exception.message
				if (message != null && message.contains("ENOSPC")) {
					exception.printStackTrace()
					return ErrorItem.Type.INSUFFICIENT_SPACE
				}
			}
			return null
		}
	}
}
