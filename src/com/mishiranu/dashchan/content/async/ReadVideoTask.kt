package com.mishiranu.dashchan.content.async

import android.net.Uri
import android.util.Log
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.util.ConcurrentUtils
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.regex.Pattern
import kotlin.math.max

class ReadVideoTask(
    private val callback: Callback,
    private val chan: Chan,
    private val uri: Uri,
    private val start: Long,
) : HttpHolderTask<LongArray, Boolean>(chan) {
    private val file: File? = CacheManager.getInstance().getMediaFile(uri, false)
    private val partialFile: File? = CacheManager.getInstance().getPartialMediaFile(uri)

    private var errorItem: ErrorItem? = null
    private var disallowRangeRequests = false

    interface Callback {
        fun onReadVideoInit(partialFile: File)

        fun onReadVideoProgressUpdate(
            progress: Long,
            progressMax: Long,
        )

        fun onReadVideoRangeUpdate(
            start: Long,
            end: Long,
        )

        fun onReadVideoSuccess(
            partial: Boolean,
            file: File,
        )

        fun onReadVideoFail(
            partial: Boolean,
            errorItem: ErrorItem,
            disallowRangeRequests: Boolean,
        )
    }

    private val progressHandler =
        object : TimedProgressHandler() {
            override fun onProgressChange(
                progress: Long,
                progressMax: Long,
            ) {
                notifyProgress(longArrayOf(progress, progressMax))
            }
        }

    override fun run(holder: HttpHolder): Boolean {
        val file = this.file
        val partialFile = this.partialFile
        if (file == null || partialFile == null) {
            errorItem = ErrorItem(ErrorItem.Type.NO_ACCESS_TO_MEMORY)
            return false
        }
        var success = false
        try {
            val result =
                chan.performer
                    .safe()
                    .onReadContent(
                        ChanPerformer.ReadContentData(
                            uri,
                            CONNECT_TIMEOUT,
                            READ_TIMEOUT,
                            holder,
                            if (start > 0) start else -1,
                            -1,
                        ),
                    )
            val response = result?.response
            if (response == null) {
                errorItem = ErrorItem(ErrorItem.Type.DOWNLOAD)
                return false
            }
            if (start > 0) {
                val headers = response.getHeaderFields()["Content-Range"]
                if (headers == null || headers.size != 1) {
                    Log.e("ReadVideoTask", "Not a partial response")
                    errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
                    disallowRangeRequests = true
                    return false
                }
                val contentRange = headers[0]
                val matcher = PATTERN_BYTES.matcher(contentRange)
                if (!matcher.matches()) {
                    Log.e("ReadVideoTask", "Invalid header: $contentRange")
                    errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
                    disallowRangeRequests = true
                    return false
                }
                val responseStart = matcher.group(1)!!.toLong()
                val responseEnd = matcher.group(2)!!.toLong() + 1
                val responseTotal = matcher.group(3)!!.toLong()
                if (responseEnd <= responseStart || responseTotal != responseEnd) {
                    Log.e("ReadVideoTask", "Invalid header data range")
                    errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
                    return false
                }
                if (max(1, responseStart - 100000) > start) {
                    Log.e("ReadVideoTask", "Invalid data range start")
                    errorItem = ErrorItem(ErrorItem.Type.INVALID_RESPONSE)
                    return false
                }
            }
            if (start <= 0) {
                progressHandler.setInputProgressMax(response.length)
            }
            try {
                response.open().use { input ->
                    RandomAccessFile(partialFile, "rw").use { output ->
                        if (start <= 0) {
                            ConcurrentUtils.mainGet {
                                callback.onReadVideoInit(partialFile)
                                null
                            }
                        } else {
                            output.seek(start)
                        }
                        var count: Int
                        var read = 0L
                        val buffer = ByteArray(8192)
                        while (input.read(buffer).also { count = it } > 0) {
                            output.write(buffer, 0, count)
                            read += count
                            if (start <= 0) {
                                progressHandler.updateProgress(read)
                            } else {
                                notifyProgress(longArrayOf(read))
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                val errorType = ReadFileTask.getErrorTypeFromExceptionAndHandle(e)
                if (errorType != null) {
                    errorItem = ErrorItem(errorType)
                    return false
                } else {
                    throw response.fail(e)
                }
            } finally {
                response.cleanupAndDisconnect()
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
            if (start <= 0) {
                file.delete()
                if (success) {
                    partialFile.renameTo(file)
                } else {
                    partialFile.delete()
                }
                CacheManager.getInstance().handleDownloadedFile(partialFile, false)
                CacheManager.getInstance().handleDownloadedFile(file, success)
                if (chan.name != null) {
                    chan.configuration.commit()
                }
            }
        }
    }

    override fun onProgress(progress: LongArray) {
        val values = progress
        if (start > 0) {
            callback.onReadVideoRangeUpdate(start, start + values[0])
        } else {
            callback.onReadVideoProgressUpdate(values[0], values[1])
        }
    }

    override fun onComplete(result: Boolean) {
        val success = result
        if (success) {
            callback.onReadVideoSuccess(start > 0, file!!)
        } else {
            callback.onReadVideoFail(start > 0, errorItem!!, disallowRangeRequests)
        }
    }

    fun isError(): Boolean = errorItem != null

    companion object {
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 15000

        private val PATTERN_BYTES = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
