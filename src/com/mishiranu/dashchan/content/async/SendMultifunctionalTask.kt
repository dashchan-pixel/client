package com.mishiranu.dashchan.content.async

import android.util.Pair
import chan.content.ApiException
import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostNumber
import java.net.HttpURLConnection
import java.util.Collections

class SendMultifunctionalTask(
    private val callback: Callback,
    private val state: State,
    private val type: String?,
    private val text: String?,
    options: List<String>?,
) : HttpHolderTask<Unit, Boolean>(Chan.get(state.archiveChanName ?: state.chanName)) {
    private val options: List<String>? = if (options != null) Collections.unmodifiableList(options) else null

    private val chan: Chan = Chan.get(state.chanName)
    private val archiveChan: Chan? = if (state.archiveChanName != null) Chan.get(state.archiveChanName) else null

    private var archiveBoardName: String? = null
    private var archiveThreadNumber: String? = null
    private var errorItem: ErrorItem? = null

    enum class Operation { DELETE, REPORT, ARCHIVE, VOTE }

    interface Callback {
        fun onSendSuccess(
            archiveBoardName: String?,
            archiveThreadNumber: String?,
        )

        fun onSendFail(errorItem: ErrorItem?)
    }

    class State(
        @JvmField val operation: Operation,
        @JvmField val chanName: String?,
        @JvmField val boardName: String?,
        @JvmField val threadNumber: String?,
        @JvmField var types: List<Pair<String, String>>?,
        @JvmField var options: List<Pair<String, String>>?,
        @JvmField var commentField: Boolean,
    ) {
        @JvmField var like = false

        @JvmField var dislike = false

        @JvmField var postNumbers: List<PostNumber>? = null

        @JvmField var archiveThreadTitle: String? = null

        @JvmField var archiveChanName: String? = null

        @JvmField var archiveQueryOnly = false

        fun isArchiveSimpleQueryOnly(): Boolean = archiveQueryOnly && options.isNullOrEmpty()
    }

    override fun run(holder: HttpHolder): Boolean {
        var chan = this.chan
        try {
            when (state.operation) {
                Operation.DELETE -> {
                    chan.performer.safe().onSendDeletePosts(
                        ChanPerformer
                            .SendDeletePostsData(
                                state.boardName,
                                state.threadNumber,
                                createPostNumberList(state.postNumbers!!),
                                text,
                                options != null && options.contains(OPTION_FILES_ONLY),
                                holder,
                            ),
                    )
                }

                Operation.REPORT -> {
                    chan.performer.safe().onSendReportPosts(
                        ChanPerformer
                            .SendReportPostsData(
                                state.boardName,
                                state.threadNumber,
                                createPostNumberList(state.postNumbers!!),
                                type,
                                options,
                                text,
                                holder,
                            ),
                    )
                }

                Operation.VOTE -> {
                    chan.performer.safe().onSendVotePost(
                        ChanPerformer
                            .SendVotePostData(
                                state.boardName,
                                state.threadNumber,
                                state.postNumbers!![0].toString(),
                                state.like,
                                type,
                                options,
                                text,
                                holder,
                            ),
                    )
                }

                Operation.ARCHIVE -> {
                    val uri =
                        chan.locator
                            .safe(false)
                            .createThreadUri(state.boardName, state.threadNumber)
                    if (uri == null) {
                        errorItem = ErrorItem(ErrorItem.Type.UNKNOWN)
                        return false
                    }
                    val archiveChan = this.archiveChan
                    if (archiveChan == null) {
                        errorItem = ErrorItem(ErrorItem.Type.UNKNOWN)
                        return false
                    }
                    chan = archiveChan
                    val result: ChanPerformer.SendAddToArchiveResult?
                    try {
                        result =
                            chan.performer.safe().onSendAddToArchive(
                                ChanPerformer
                                    .SendAddToArchiveData(uri, state.boardName, state.threadNumber, options, holder),
                            )
                    } catch (e: HttpException) {
                        if (state.archiveQueryOnly) {
                            val responseCode = e.getResponseCode()
                            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                                errorItem = ErrorItem(ErrorItem.Type.THREAD_NOT_EXISTS)
                                return false
                            }
                        }
                        throw e
                    }
                    if (result != null && result.threadNumber != null) {
                        archiveBoardName = result.boardName
                        archiveThreadNumber = result.threadNumber
                    }
                }
            }
            return true
        } catch (e: ExtensionException) {
            errorItem = e.getErrorItemAndHandle()
            return false
        } catch (e: HttpException) {
            errorItem = e.getErrorItemAndHandle()
            return false
        } catch (e: InvalidResponseException) {
            errorItem = e.getErrorItemAndHandle()
            return false
        } catch (e: ApiException) {
            errorItem = e.errorItem
            return false
        } finally {
            chan.configuration.commit()
        }
    }

    override fun onComplete(result: Boolean) {
        val success = result
        if (success) {
            callback.onSendSuccess(archiveBoardName, archiveThreadNumber)
        } else {
            callback.onSendFail(errorItem)
        }
    }

    companion object {
        const val OPTION_FILES_ONLY = "filesOnly"

        private fun createPostNumberList(numbers: List<PostNumber>): List<String> {
            val postNumbers = ArrayList<String>(numbers.size)
            for (number in numbers) {
                postNumbers.add(number.toString())
            }
            return postNumbers
        }
    }
}
