package com.mishiranu.dashchan.content.async

import android.os.SystemClock
import chan.http.HttpRequest
import chan.http.MultipartEntity

open class TimedProgressHandler :
    HttpRequest.OutputListener,
    MultipartEntity.OpenableOutputListener {
    private val lastProgressUpdate = LongArray(3)
    private var progressMax = -1L

    private fun checkNeedToUpdate(
        index: Int,
        progress: Long,
        progressMax: Long,
    ): Boolean {
        val time = SystemClock.elapsedRealtime()
        if (time - lastProgressUpdate[index] >= 200 || progress == 0L || progress == progressMax) {
            lastProgressUpdate[index] = time
            return true
        }
        return false
    }

    fun updateProgress(count: Long) {
        if (checkNeedToUpdate(0, count, progressMax)) {
            onProgressChange(count, progressMax)
        }
    }

    fun setInputProgressMax(progressMax: Long) {
        this.progressMax = progressMax
    }

    final override fun onOutputProgressChange(
        progress: Long,
        progressMax: Long,
    ) {
        if (checkNeedToUpdate(1, progress, progressMax)) {
            onProgressChange(progress, progressMax)
        }
    }

    final override fun onOutputProgressChange(
        openable: MultipartEntity.Openable,
        progress: Long,
        progressMax: Long,
    ) {
        if (checkNeedToUpdate(2, progress, progressMax)) {
            onProgressChange(openable, progress, progressMax)
        }
    }

    open fun onProgressChange(
        progress: Long,
        progressMax: Long,
    ) {}

    open fun onProgressChange(
        openable: MultipartEntity.Openable,
        progress: Long,
        progressMax: Long,
    ) {}
}
