package com.mishiranu.dashchan.content.async

import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.EchoDatabase

class GetEchoTask(
    private val callback: Callback,
    private val chanName: String?,
    private val searchQuery: String?,
) : ExecutorTask<Unit, EchoDatabase.EchoCursor?>() {
    fun interface Callback {
        fun onGetEchoResult(cursor: EchoDatabase.EchoCursor?)
    }

    private val signal = CancellationSignal()

    override fun run(): EchoDatabase.EchoCursor? =
        try {
            CommonDatabase.getInstance().echo.getEcho(chanName, searchQuery, signal)
        } catch (_: OperationCanceledException) {
            null
        }

    override fun cancel() {
        super.cancel()
        try {
            signal.cancel()
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun onCancel(result: EchoDatabase.EchoCursor?) {
        result?.close()
    }

    override fun onComplete(result: EchoDatabase.EchoCursor?) {
        callback.onGetEchoResult(result)
    }
}
