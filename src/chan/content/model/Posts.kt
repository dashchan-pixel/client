package chan.content.model

import android.net.Uri
import chan.annotation.Public
import chan.util.CommonUtils
import com.mishiranu.dashchan.content.database.PagesDatabase

@Public
class Posts {
	private val builder: ChanBuilder?
	private val provider: LazyProvider?

	@Public
	constructor() {
		builder = ChanBuilder()
		provider = null
	}

	@Public
	constructor(vararg posts: Post?) : this() {
		setPosts(*posts)
	}

	@Public
	constructor(posts: Collection<Post>?) : this() {
		setPosts(posts)
	}

	constructor(chanName: String, boardName: String, threadNumber: String) {
		builder = null
		provider = LazyProvider(PagesDatabase.ThreadKey(chanName, boardName, threadNumber))
	}

	@Public
	fun getPosts(): Array<Post?>? {
		return builder?.posts ?: provider?.getPosts()
	}

	@Public
	fun setPosts(vararg posts: Post?): Posts {
		@Suppress("UNCHECKED_CAST")
		builder!!.posts = CommonUtils.removeNullItems(posts as Array<Post?>, Post::class.java)
		return this
	}

	@Public
	fun setPosts(posts: Collection<Post>?): Posts {
		@Suppress("UNCHECKED_CAST")
		return setPosts(*(CommonUtils.toArray(posts, Post::class.java) ?: emptyArray()))
	}

	fun getThreadNumber(): String? = builder!!.posts!![0]!!.getThreadNumberOrOriginalPostNumber()

	@Public
	fun getArchivedThreadUri(): Uri? = builder?.archivedThreadUri

	@Public
	fun setArchivedThreadUri(uri: Uri?): Posts {
		builder?.archivedThreadUri = uri
		return this
	}

	@Public
	fun getUniquePosters(): Int = builder?.uniquePosters ?: 0

	@Public
	fun setUniquePosters(uniquePosters: Int): Posts {
		if (builder != null && uniquePosters > 0) {
			builder.uniquePosters = uniquePosters
		}
		return this
	}

	@Public
	fun getPostsCount(): Int = builder?.postsCount ?: 0

	@Public
	fun addPostsCount(postsCount: Int): Posts {
		var count = postsCount
		if (builder != null && count > 0) {
			if (builder.postsCount == -1) {
				count++
			}
			builder.postsCount += count
		}
		return this
	}

	@Public
	fun getFilesCount(): Int = builder?.filesCount ?: 0

	@Public
	fun addFilesCount(filesCount: Int): Posts {
		var count = filesCount
		if (builder != null && count > 0) {
			if (builder.filesCount == -1) {
				count++
			}
			builder.filesCount += count
		}
		return this
	}

	@Public
	fun getPostsWithFilesCount(): Int = builder?.postsWithFilesCount ?: 0

	@Public
	fun addPostsWithFilesCount(postsWithFilesCount: Int): Posts {
		var count = postsWithFilesCount
		if (builder != null && count > 0) {
			if (builder.postsWithFilesCount == -1) {
				count++
			}
			builder.postsWithFilesCount += count
		}
		return this
	}

	fun length(): Int = getPosts()?.size ?: 0

	private class ChanBuilder {
		var posts: Array<Post?>? = null

		var archivedThreadUri: Uri? = null
		var uniquePosters = 0

		var postsCount = -1
		var filesCount = -1
		var postsWithFilesCount = -1
	}

	private class LazyProvider(val threadKey: PagesDatabase.ThreadKey) {
		@Volatile private var posts: Array<Post?>? = null

		fun getPosts(): Array<Post?> {
			var posts = this.posts
			if (posts == null) {
				synchronized(this) {
					posts = this.posts
					if (posts == null) {
						val postNumbers = PagesDatabase.getInstance().getPostNumbers(threadKey)
						posts = Array(postNumbers.size) { Post(postNumbers[it]!!) as Post? }
						this.posts = posts
					}
				}
			}
			return posts!!
		}
	}
}
