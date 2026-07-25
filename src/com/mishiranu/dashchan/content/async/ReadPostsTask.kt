package com.mishiranu.dashchan.content.async

import android.util.Log
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.RedirectException
import chan.content.ThreadRedirectException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PendingUserPost
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import java.io.IOException
import java.net.HttpURLConnection
import java.util.TreeMap

class ReadPostsTask(
    private val callback: Callback,
    private val chan: Chan,
    private val boardName: String?,
    private val threadNumber: String,
    private val loadFullThread: Boolean,
    pendingUserPosts: Collection<PendingUserPost>?,
) : HttpHolderTask<Unit, ReadPostsTask.Result>(chan) {
    private val pendingUserPosts: HashSet<PendingUserPost>? =
        if (pendingUserPosts != null) HashSet(pendingUserPosts) else null

    @JvmSuppressWildcards
    interface Callback {
        fun onPendingUserPostsConsumed(pendingUserPosts: Set<PendingUserPost>)

        fun onReadPostsSuccess(
            cacheState: PagesDatabase.Cache.State?,
            replies: List<PagesDatabase.InsertResult.Reply>?,
            newCount: Int?,
        )

        fun onReadPostsRedirect(target: RedirectException.Target)

        fun onReadPostsFail(errorItem: ErrorItem)
    }

    interface Result {
        class Success(
            @JvmField val cacheState: PagesDatabase.Cache.State?,
            @JvmField val removedPendingUserPosts: Set<PendingUserPost>?,
            @JvmField val replies: List<PagesDatabase.InsertResult.Reply>?,
            @JvmField val newCount: Int?,
        ) : Result

        class Redirect(
            @JvmField val target: RedirectException.Target,
        ) : Result

        class Fail(
            @JvmField val errorItem: ErrorItem,
        ) : Result
    }

    private class UpdateMeta(
        val deleted: Boolean,
        val error: Boolean,
    ) {
        fun isChanged(meta: PagesDatabase.Meta?): Boolean = meta != null && (meta.deleted != deleted || meta.error != error)
    }

    override fun run(holder: HttpHolder): Result {
        val temporary = chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)
        val threadKey = PagesDatabase.ThreadKey(chan.name!!, boardName, threadNumber)
        var meta = PagesDatabase.getInstance().getMeta(threadKey, temporary)
        val lastExistingPostNumber = PagesDatabase.getInstance().getLastExistingPostNumber(threadKey)
        var updateMeta: UpdateMeta? = null
        var originalPostNumber: PostNumber?
        val allowPartialThreadLoading: Boolean
        if (loadFullThread) {
            originalPostNumber = null
            allowPartialThreadLoading = false
        } else {
            val originalPost = PagesDatabase.getInstance().getOriginalPost(threadKey)
            originalPostNumber = originalPost?.number
            allowPartialThreadLoading = originalPost == null ||
                !originalPost.isCyclical ||
                Preferences.cyclicalRefreshMode == Preferences.CyclicalRefreshMode.DEFAULT
        }
        var partial = !loadFullThread && allowPartialThreadLoading && Preferences.isPartialThreadLoading(chan)
        val useValidator = if (!loadFullThread && meta != null) meta.validator else null
        try {
            val result: ChanPerformer.ReadPostsResult?
            try {
                val lastPostNumber = lastExistingPostNumber?.toString()
                result =
                    chan.performer.safe().onReadPosts(
                        ChanPerformer.ReadPostsData(
                            chan.name,
                            boardName,
                            threadNumber,
                            lastPostNumber,
                            partial,
                            lastPostNumber != null,
                            holder,
                            useValidator,
                        ),
                    )
            } catch (e: ThreadRedirectException) {
                val target =
                    e.obtainTarget(chan.name, boardName)
                        ?: throw HttpException.createNotFoundException()
                updateMeta = UpdateMeta(true, false)
                return Result.Redirect(target)
            } catch (e: RedirectException) {
                val target =
                    e.obtainTarget(chan.name)
                        ?: throw HttpException.createNotFoundException()
                if (chan.name != target.chanName || target.threadNumber == null) {
                    Log.e("ReadPostsTask", "Only local thread redirects allowed")
                    updateMeta = UpdateMeta(false, true)
                    return Result.Fail(ErrorItem(ErrorItem.Type.INVALID_DATA_FORMAT))
                } else if (CommonUtils.equals(boardName, target.boardName) &&
                    threadNumber == target.threadNumber
                ) {
                    throw HttpException.createNotFoundException()
                } else {
                    updateMeta = UpdateMeta(true, false)
                    return Result.Redirect(target)
                }
            }
            var validator = result?.validator
            if (validator == null) {
                validator = holder.extractValidator()
            }
            if (validator == null) {
                validator = useValidator
            }
            if (result != null && !result.posts.isEmpty() && result.fullThread) {
                partial = false
            }

            val posts: List<Post>
            if (result != null && !result.posts.isEmpty()) {
                // Remove repeats and sort
                val postsMap = TreeMap<PostNumber, Post>()
                for (post in result.posts) {
                    postsMap[post!!.number] = post
                }
                if (originalPostNumber == null && postsMap.isNotEmpty()) {
                    originalPostNumber = postsMap.firstKey()
                }
                posts = ArrayList(postsMap.values)
            } else {
                posts = emptyList()
            }
            if (posts.isEmpty()) {
                if (partial) {
                    updateMeta = UpdateMeta(false, false)
                    return Result.Success(
                        PagesDatabase.getInstance().getCacheState(threadKey),
                        null,
                        emptyList(),
                        null,
                    )
                } else {
                    updateMeta = UpdateMeta(false, true)
                    return Result.Fail(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE))
                }
            }

            val pendingUserPosts = this.pendingUserPosts
            var removedPendingUserPosts: HashSet<PendingUserPost>? = null
            if (pendingUserPosts != null && pendingUserPosts.isNotEmpty()) {
                val firstPostIsOriginal = posts[0].number == originalPostNumber
                for (pendingUserPost in pendingUserPosts) {
                    val postNumber =
                        pendingUserPost.findUserPost(
                            posts,
                            lastExistingPostNumber,
                            firstPostIsOriginal,
                        )
                    if (postNumber != null) {
                        if (removedPendingUserPosts == null) {
                            removedPendingUserPosts = HashSet()
                        }
                        removedPendingUserPosts.add(pendingUserPost)
                        CommonDatabase.getInstance().posts.setFlags(
                            false,
                            chan.name,
                            boardName,
                            threadNumber,
                            postNumber,
                            PostItem.HideState.UNDEFINED,
                            true,
                        )
                    }
                }
            }

            val insertResult: PagesDatabase.InsertResult
            val newThread = meta == null
            try {
                var archivedThreadUri = result!!.archivedThreadUri
                if (archivedThreadUri == null && meta != null) {
                    archivedThreadUri = meta.archivedThreadUri
                }
                var uniquePosters = result.uniquePosters
                if (uniquePosters <= 0 && meta != null) {
                    uniquePosters = meta.uniquePosters
                }
                meta = PagesDatabase.Meta(validator, archivedThreadUri, uniquePosters, false, false)
                insertResult =
                    PagesDatabase.getInstance().insertNewPosts(
                        threadKey,
                        posts,
                        meta,
                        temporary,
                        newThread,
                        partial,
                    )!!
            } catch (e: IOException) {
                updateMeta = UpdateMeta(false, true)
                return Result.Fail(ErrorItem(ErrorItem.Type.NO_ACCESS_TO_MEMORY))
            }
            val replies = insertResult.replies
            if (replies != null && replies.isNotEmpty() && Preferences.isEcho) {
                // Collect the replies for the Echo even when no notification is shown for them
                val originalPost = posts.firstOrNull { it.number == originalPostNumber }
                CommonDatabase.getInstance().echo.addRepliesAsync(
                    threadKey.chanName,
                    boardName,
                    threadNumber,
                    StringUtils.nullIfEmpty(originalPost?.subject?.trim()),
                    replies,
                )
            }
            return Result.Success(
                insertResult.cacheState,
                removedPendingUserPosts,
                replies,
                insertResult.newCount,
            )
        } catch (e: HttpException) {
            val responseCode = e.getResponseCode()
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                updateMeta = UpdateMeta(false, false)
                return Result.Success(
                    PagesDatabase.getInstance().getCacheState(threadKey),
                    null,
                    emptyList(),
                    null,
                )
            }
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                responseCode == HttpURLConnection.HTTP_GONE
            ) {
                PagesDatabase.getInstance().setMetaFlags(threadKey, true, false)
                if (chan.configuration.getOption(ChanConfiguration.OPTION_READ_SINGLE_POST)) {
                    try {
                        // Check the post belongs to another thread
                        val result =
                            chan.performer.safe().onReadSinglePost(ChanPerformer.ReadSinglePostData(boardName, threadNumber, holder))
                        val post = result?.post
                        if (post != null) {
                            val postThreadNumber = post.threadNumber
                            if (threadNumber != postThreadNumber) {
                                val target =
                                    RedirectException
                                        .toThread(
                                            boardName,
                                            postThreadNumber,
                                            post.post.number,
                                        ).obtainTarget(chan.name)!!
                                updateMeta = UpdateMeta(true, false)
                                return Result.Redirect(target)
                            }
                        }
                    } catch (e2: ExtensionException) {
                        e2.getErrorItemAndHandle()
                    } catch (e2: HttpException) {
                        e2.getErrorItemAndHandle()
                    } catch (e2: InvalidResponseException) {
                        e2.getErrorItemAndHandle()
                    }
                }
                updateMeta = UpdateMeta(true, false)
                return Result.Fail(ErrorItem(ErrorItem.Type.THREAD_NOT_EXISTS))
            } else {
                updateMeta = UpdateMeta(false, true)
                return Result.Fail(e.getErrorItemAndHandle())
            }
        } catch (e: ExtensionException) {
            updateMeta = UpdateMeta(false, true)
            return Result.Fail(e.getErrorItemAndHandle())
        } catch (e: InvalidResponseException) {
            updateMeta = UpdateMeta(false, true)
            return Result.Fail(e.getErrorItemAndHandle())
        } finally {
            if (updateMeta != null && updateMeta.isChanged(meta)) {
                PagesDatabase.getInstance().setMetaFlags(threadKey, updateMeta.deleted, updateMeta.error)
            }
            chan.configuration.commit()
        }
    }

    override fun onCancel(result: Result?) {
        if (result is Result.Success) {
            if (result.removedPendingUserPosts != null) {
                callback.onPendingUserPostsConsumed(result.removedPendingUserPosts)
            }
        }
    }

    override fun onComplete(result: Result) {
        when (result) {
            is Result.Success -> {
                if (result.removedPendingUserPosts != null) {
                    callback.onPendingUserPostsConsumed(result.removedPendingUserPosts)
                }
                callback.onReadPostsSuccess(result.cacheState, result.replies, result.newCount)
            }

            is Result.Redirect -> {
                callback.onReadPostsRedirect(result.target)
            }

            is Result.Fail -> {
                callback.onReadPostsFail(result.errorItem)
            }

            else -> {
                error("Unsupported result type")
            }
        }
    }
}
