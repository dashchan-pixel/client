package com.mishiranu.dashchan.content.async

import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.InboxDatabase

class GetInboxTask(
    private val callback: Callback,
    private val chanName: String?,
    private val searchQuery: String?,
) : ExecutorTask<Unit, InboxDatabase.InboxCursor?>() {
    fun interface Callback {
        fun onGetInboxResult(cursor: InboxDatabase.InboxCursor?)
    }

    private val signal = CancellationSignal()

    override fun run(): InboxDatabase.InboxCursor? =
        try {
            CommonDatabase.getInstance().inbox.getInbox(chanName, searchQuery, signal)
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

    override fun onCancel(result: InboxDatabase.InboxCursor?) {
        result?.close()
    }

    override fun onComplete(result: InboxDatabase.InboxCursor?) {
        callback.onGetInboxResult(result)
    }
}
