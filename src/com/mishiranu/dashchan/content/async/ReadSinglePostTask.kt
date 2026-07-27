package com.mishiranu.dashchan.content.async

import android.util.Pair
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import java.net.HttpURLConnection

class ReadSinglePostTask(
    private val callback: Callback,
    private val chan: Chan,
    private val boardName: String?,
    private val threadNumber: String?,
    private val postNumber: PostNumber?,
) : HttpHolderTask<Unit, Pair<ErrorItem?, PostItem?>>(chan) {
    interface Callback {
        fun onReadSinglePostSuccess(postItem: PostItem)

        fun onReadSinglePostFail(errorItem: ErrorItem)
    }

    override fun run(holder: HttpHolder): Pair<ErrorItem?, PostItem?> {
        try {
            val postNumber = this.postNumber?.toString() ?: threadNumber
            val result =
                chan.performer.safe().onReadSinglePost(
                    ChanPerformer
                        .ReadSinglePostData(boardName, postNumber, threadNumber, holder),
                )
            val post = result?.post ?: throw HttpException.createNotFoundException()
            return Pair(
                null,
                PostItem.createPost(
                    post.post,
                    chan,
                    boardName,
                    post.threadNumber,
                    post.originalPostNumber!!,
                ),
            )
        } catch (e: HttpException) {
            var errorItem = e.getErrorItemAndHandle()
            if (errorItem.httpResponseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                errorItem.httpResponseCode == HttpURLConnection.HTTP_GONE
            ) {
                errorItem = ErrorItem(ErrorItem.Type.POST_NOT_FOUND)
            }
            return Pair(errorItem, null)
        } catch (e: ExtensionException) {
            return Pair(e.getErrorItemAndHandle(), null)
        } catch (e: InvalidResponseException) {
            return Pair(e.getErrorItemAndHandle(), null)
        } finally {
            chan.configuration.commit()
        }
    }

    override fun onComplete(result: Pair<ErrorItem?, PostItem?>) {
        val postItem = result.second
        if (postItem != null) {
            callback.onReadSinglePostSuccess(postItem)
        } else {
            callback.onReadSinglePostFail(result.first!!)
        }
    }
}
