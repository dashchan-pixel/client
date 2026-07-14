package com.mishiranu.dashchan.content.async

import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.HistoryDatabase

class GetHistoryTask(private val callback: Callback, private val chanName: String?,
		private val searchQuery: String?) : ExecutorTask<Void, HistoryDatabase.HistoryCursor?>() {
	fun interface Callback {
		fun onGetHistoryResult(cursor: HistoryDatabase.HistoryCursor?)
	}

	private val signal = CancellationSignal()

	override fun run(): HistoryDatabase.HistoryCursor? {
		return try {
			CommonDatabase.getInstance().history.getHistory(chanName, searchQuery, signal)
		} catch (e: OperationCanceledException) {
			null
		}
	}

	override fun cancel() {
		super.cancel()
		try {
			signal.cancel()
		} catch (e: Exception) {
			// Ignore
		}
	}

	override fun onCancel(result: HistoryDatabase.HistoryCursor?) {
		result?.close()
	}

	override fun onComplete(result: HistoryDatabase.HistoryCursor?) {
		callback.onGetHistoryResult(result)
	}
}
