package com.mishiranu.dashchan.content.async

import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem

class ReadBoardsTask(private val callback: Callback,
		private val chan: Chan) : HttpHolderTask<Void, ErrorItem?>(chan) {
	interface Callback {
		fun onReadBoardsSuccess()
		fun onReadBoardsFail(errorItem: ErrorItem)
	}

	override fun run(holder: HttpHolder): ErrorItem? {
		try {
			val result = chan.performer.safe().onReadBoards(ChanPerformer.ReadBoardsData(holder))
			var boardCategories = result?.boardCategories
			if (boardCategories != null && boardCategories.isEmpty()) {
				boardCategories = null
			}
			if (boardCategories != null) {
				chan.configuration.updateFromBoards(boardCategories!!)
			}
			if (!ChanDatabase.getInstance().setBoards(chan.name!!, boardCategories)) {
				return ErrorItem(ErrorItem.Type.EMPTY_RESPONSE)
			}
			return null
		} catch (e: ExtensionException) {
			return e.getErrorItemAndHandle()
		} catch (e: HttpException) {
			return e.getErrorItemAndHandle()
		} catch (e: InvalidResponseException) {
			return e.getErrorItemAndHandle()
		} finally {
			chan.configuration.commit()
		}
	}

	override fun onComplete(result: ErrorItem?) {
		if (result == null) {
			callback.onReadBoardsSuccess()
		} else {
			callback.onReadBoardsFail(result)
		}
	}
}
