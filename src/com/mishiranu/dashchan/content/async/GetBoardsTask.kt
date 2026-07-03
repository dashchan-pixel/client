package com.mishiranu.dashchan.content.async

import android.os.CancellationSignal
import android.os.OperationCanceledException
import chan.content.Chan
import com.mishiranu.dashchan.content.database.ChanDatabase

class GetBoardsTask(private val callback: Callback, private val chan: Chan,
		private val userBoardNames: List<String>?, private val searchQuery: String?) :
		ExecutorTask<Void, ChanDatabase.BoardCursor?>() {
	fun interface Callback {
		fun onGetBoardsResult(cursor: ChanDatabase.BoardCursor?)
	}

	private val signal = CancellationSignal()

	override fun run(): ChanDatabase.BoardCursor? {
		return try {
			if (userBoardNames != null) {
				chan.configuration.getUserBoards(userBoardNames, searchQuery, signal)
			} else {
				chan.configuration.getBoards(searchQuery, signal)
			}
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

	override fun onCancel(cursor: ChanDatabase.BoardCursor?) {
		cursor?.close()
	}

	override fun onComplete(cursor: ChanDatabase.BoardCursor?) {
		callback.onGetBoardsResult(cursor)
	}
}
