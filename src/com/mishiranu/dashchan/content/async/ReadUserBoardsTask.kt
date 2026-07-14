package com.mishiranu.dashchan.content.async

import android.util.Pair
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.model.ErrorItem

class ReadUserBoardsTask(
    private val callback: Callback,
    private val chan: Chan,
) : HttpHolderTask<Long, Pair<ErrorItem?, List<String>?>>(chan) {
    interface Callback {
        fun onReadUserBoardsSuccess(boardNames: List<String>)

        fun onReadUserBoardsFail(errorItem: ErrorItem)
    }

    override fun run(holder: HttpHolder): Pair<ErrorItem?, List<String>?> {
        try {
            val result =
                chan.performer
                    .safe()
                    .onReadUserBoards(ChanPerformer.ReadUserBoardsData(holder))
            var boards = result?.boards
            if (boards != null && boards.isEmpty()) {
                boards = null
            }
            val boardNamesSet = HashSet<String>()
            val boardNamesList = ArrayList<String>()
            if (boards != null) {
                chan.configuration.updateFromBoards(boards)
                for (board in boards) {
                    if (board != null) {
                        val boardName = board.getBoardName()
                        if (boardNamesSet.add(boardName)) {
                            boardNamesList.add(boardName)
                        }
                    }
                }
            }
            if (boardNamesList.isEmpty()) {
                return Pair(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), null)
            }
            return Pair(null, boardNamesList)
        } catch (e: ExtensionException) {
            return Pair(e.getErrorItemAndHandle(), null)
        } catch (e: HttpException) {
            return Pair(e.getErrorItemAndHandle(), null)
        } catch (e: InvalidResponseException) {
            return Pair(e.getErrorItemAndHandle(), null)
        } finally {
            chan.configuration.commit()
        }
    }

    override fun onComplete(result: Pair<ErrorItem?, List<String>?>) {
        val boardNames = result.second
        if (boardNames != null) {
            callback.onReadUserBoardsSuccess(boardNames)
        } else {
            callback.onReadUserBoardsFail(result.first!!)
        }
    }
}
