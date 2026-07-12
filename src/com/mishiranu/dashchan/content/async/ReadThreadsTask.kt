package com.mishiranu.dashchan.content.async

import android.util.Log
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.RedirectException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpValidator
import chan.util.CommonUtils
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import java.net.HttpURLConnection

class ReadThreadsTask(private val callback: Callback, private val chan: Chan,
		private val boardName: String?, val pageNumber: Int,
		private val validator: HttpValidator?, private val append: Boolean) :
		HttpHolderTask<Void, Boolean>(chan) {
	private var postItems: List<PostItem>? = null
	private var boardSpeed = 0
	private var resultValidator: HttpValidator? = null
	private var hiddenThreads: PostItem.HideState.Map<String>? = null

	private var target: RedirectException.Target? = null
	private var errorItem: ErrorItem? = null

	interface Callback {
		fun onReadThreadsSuccess(postItems: List<PostItem>?, pageNumber: Int, boardSpeed: Int,
				append: Boolean, checkModified: Boolean, validator: HttpValidator?,
				hiddenThreads: PostItem.HideState.Map<String>?)
		fun onReadThreadsRedirect(target: RedirectException.Target)
		fun onReadThreadsFail(errorItem: ErrorItem?, pageNumber: Int)
	}

	fun getPageNumber(): Int = pageNumber

	override fun run(holder: HttpHolder): Boolean {
		try {
			val result = try {
				chan.performer.safe().onReadThreads(ChanPerformer.ReadThreadsData(boardName,
						pageNumber, holder, validator))
			} catch (e: RedirectException) {
				val target = e.obtainTarget(chan.name) ?: throw HttpException.createNotFoundException()
				when {
					target.threadNumber != null -> {
						Log.e("ReadThreadsTask", "Only board redirects allowed")
						errorItem = ErrorItem(ErrorItem.Type.INVALID_DATA_FORMAT)
						return false
					}
					chan.name == target.chanName && CommonUtils.equals(boardName, target.boardName) ->
						throw HttpException.createNotFoundException()
					else -> {
						this.target = target
						return true
					}
				}
			}
			if (result == null) {
				throw HttpException.createNotFoundException()
			}
			val postItems = ArrayList<PostItem>(result.threads.size)
			val threadNumbers = ArrayList<String>(result.threads.size)
			for (thread in result.threads) {
				postItems.add(PostItem.createThread(thread!!.posts!!, thread!!.postsCount, thread!!.filesCount,
						thread!!.postsWithFilesCount, chan, boardName, thread!!.threadNumber))
				threadNumbers.add(thread!!.threadNumber!!)
			}
			this.postItems = postItems
			this.boardSpeed = result.boardSpeed
			this.resultValidator = result.validator ?: holder.extractValidator()
			hiddenThreads = CommonDatabase.getInstance().threads
					.getFlags(chan.name!!, boardName, threadNumbers)
			return true
		} catch (e: HttpException) {
			val responseCode = e.getResponseCode()
			if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
				return true
			}
			errorItem = if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
					responseCode == HttpURLConnection.HTTP_GONE) {
				ErrorItem(ErrorItem.Type.BOARD_NOT_EXISTS)
			} else {
				e.getErrorItemAndHandle()
			}
			return false
		} catch (e: ExtensionException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} catch (e: InvalidResponseException) {
			errorItem = e.getErrorItemAndHandle()
			return false
		} finally {
			chan.configuration.commit()
		}
	}

	override fun onComplete(result: Boolean) {
		if (result) {
			val target = target
			if (target != null) {
				callback.onReadThreadsRedirect(target)
			} else {
				callback.onReadThreadsSuccess(postItems, pageNumber, boardSpeed, append,
						validator != null, resultValidator, hiddenThreads)
			}
		} else {
			callback.onReadThreadsFail(errorItem, pageNumber)
		}
	}
}
