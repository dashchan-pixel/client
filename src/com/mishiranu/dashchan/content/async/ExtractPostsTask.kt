package com.mishiranu.dashchan.content.async

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.text.ParseException
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.database.PostsDatabase
import com.mishiranu.dashchan.content.database.ThreadsDatabase
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber

class ExtractPostsTask(private val callback: Callback, private val cache: PagesDatabase.Cache?,
		private val chan: Chan, private val boardName: String?, private val threadNumber: String?,
		private val extractStateExtra: Boolean, private val cleanup: PagesDatabase.Cleanup) :
		ExecutorTask<Void, ExtractPostsTask.Result?>() {
	interface Callback {
		fun onExtractPostsComplete(result: Result?, cancelled: Boolean)
	}

	class Result(
			@JvmField val newPosts: Set<PostNumber>,
			@JvmField val deletedPosts: Set<PostNumber>,
			@JvmField val editedPosts: Set<PostNumber>,
			@JvmField val replyPosts: Set<PostNumber>,
			@JvmField val cache: PagesDatabase.Cache,
			@JvmField val cacheChanged: Boolean,
			@JvmField val postItems: Map<PostNumber, PostItem>,
			@JvmField val removedPosts: Collection<PostNumber>,
			@JvmField val flags: PostsDatabase.Flags?,
			@JvmField val stateExtra: ThreadsDatabase.StateExtra?,
			@JvmField val archivedThreadUri: Uri?,
			@JvmField val uniquePosters: Int)

	private val signal = CancellationSignal()

	override fun run(): Result? {
		val threadKey = PagesDatabase.ThreadKey(chan.name!!, boardName, threadNumber!!)
		val diff = try {
			PagesDatabase.getInstance().collectDiffPosts(threadKey, cache, cleanup, signal)
		} catch (e: ParseException) {
			e.printStackTrace()
			return null
		} catch (e: OperationCanceledException) {
			return null
		}
		var meta: PagesDatabase.Meta? = null
		var flags: PostsDatabase.Flags? = null
		var stateExtra: ThreadsDatabase.StateExtra? = null
		var cacheChanged = false
		var postItems: Map<PostNumber, PostItem> = emptyMap()
		var removedPosts: Collection<PostNumber> = emptyList()
		if (!isCancelled() && extractStateExtra) {
			stateExtra = CommonDatabase.getInstance().threads
					.getStateExtra(chan.name!!, boardName, threadNumber!!)
		}
		if (!isCancelled() && diff!!.cache.isChanged(cache)) {
			val temporary = chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)
			meta = PagesDatabase.getInstance().getMeta(threadKey, temporary)
			flags = CommonDatabase.getInstance().posts.getFlags(chan.name!!, boardName, threadNumber!!)
			cacheChanged = true
			val map = HashMap<PostNumber, PostItem>(diff!!.changed!!.size)
			removedPosts = diff!!.removed
			val originalPostNumber = diff!!.cache.originalPostNumber
			for (post in diff!!.changed) {
				map[post!!.number] = PostItem.createPost(post, chan, boardName, threadNumber, originalPostNumber)
			}
			postItems = map
		}
		return Result(diff!!.newPosts, diff!!.deletedPosts, diff!!.editedPosts, diff!!.replyPosts,
				diff!!.cache, cacheChanged, postItems, removedPosts, flags, stateExtra,
				meta?.archivedThreadUri, meta?.uniquePosters ?: 0)
	}

	override fun onCancel(result: Result?) {
		if (result != null) {
			callback.onExtractPostsComplete(result, true)
		}
	}

	override fun onComplete(result: Result?) {
		callback.onExtractPostsComplete(result, false)
	}

	override fun cancel() {
		super.cancel()
		try {
			signal.cancel()
		} catch (e: Exception) {
			// Ignore
		}
	}
}
