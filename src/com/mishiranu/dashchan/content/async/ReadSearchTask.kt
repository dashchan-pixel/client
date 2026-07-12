package com.mishiranu.dashchan.content.async

import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.model.SinglePost
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.util.ConcurrentUtils

class ReadSearchTask(private val callback: Callback, private val chan: Chan,
		private val boardName: String?, private val searchQuery: String?,
		val pageNumber: Int) : HttpHolderTask<Void, List<PostItem>?>(chan) {
	private var errorItem: ErrorItem? = null

	interface Callback {
		fun onReadSearchSuccess(postItems: List<PostItem>?, pageNumber: Int)
		fun onReadSearchFail(errorItem: ErrorItem)
	}

	fun getPageNumber(): Int = pageNumber

	override fun run(holder: HttpHolder): List<PostItem>? {
		try {
			val result = chan.performer.safe().onReadSearchPosts(ChanPerformer
					.ReadSearchPostsData(boardName, searchQuery, pageNumber, holder))
			val posts = ArrayList<SinglePost>()
			if (result != null) {
				posts.addAll(result.posts)
			}
			if (posts.isNotEmpty()) {
				posts.sortWith(TIME_COMPARATOR)
				val postItems = ArrayList<PostItem>(posts.size)
				var i = 0
				while (i < posts.size && !Thread.interrupted()) {
					val post = posts[i]
					val postItem = PostItem.createPost(post.post, chan,
							boardName, post.threadNumber, post.originalPostNumber!!)
					postItem.setOrdinalIndex(i)
					// Preload
					ConcurrentUtils.mainGet { postItem.getComment(chan) }
					postItems.add(postItem)
					i++
				}
				return postItems
			}
			return null
		} catch (e: ExtensionException) {
			errorItem = e.getErrorItemAndHandle()
			return null
		} catch (e: HttpException) {
			errorItem = e.getErrorItemAndHandle()
			return null
		} catch (e: InvalidResponseException) {
			errorItem = e.getErrorItemAndHandle()
			return null
		} finally {
			chan.configuration.commit()
		}
	}

	override fun onComplete(result: List<PostItem>?) {
		val errorItem = errorItem
		if (errorItem == null) {
			callback.onReadSearchSuccess(result, pageNumber)
		} else {
			callback.onReadSearchFail(errorItem)
		}
	}

	companion object {
		private val TIME_COMPARATOR = Comparator<SinglePost> { lhs, rhs ->
			rhs.post.timestamp.compareTo(lhs.post.timestamp)
		}
	}
}
