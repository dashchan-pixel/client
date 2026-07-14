package com.mishiranu.dashchan.content.async

import chan.content.Chan
import chan.content.ChanPerformer
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.content.model.ThreadSummary
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.content.model.ErrorItem

class ReadThreadSummariesTask(
    private val callback: Callback,
    private val chan: Chan,
    private val boardName: String?,
    val pageNumber: Int,
    private val type: Int,
) : HttpHolderTask<Unit, List<ThreadSummary>?>(chan) {
    private var errorItem: ErrorItem? = null

    interface Callback {
        fun onReadThreadSummariesSuccess(
            threadSummaries: List<ThreadSummary>,
            pageNumber: Int,
        )

        fun onReadThreadSummariesFail(errorItem: ErrorItem)
    }

    override fun run(holder: HttpHolder): List<ThreadSummary>? {
        try {
            val result =
                chan.performer
                    .safe()
                    .onReadThreadSummaries(
                        ChanPerformer
                            .ReadThreadSummariesData(boardName, pageNumber, type, holder),
                    )
            val threadSummaries = result?.threadSummaries
            return if (threadSummaries != null && threadSummaries.isNotEmpty()) {
                threadSummaries.filterNotNull()
            } else {
                emptyList()
            }
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

    override fun onComplete(result: List<ThreadSummary>?) {
        if (result != null) {
            callback.onReadThreadSummariesSuccess(result, pageNumber)
        } else {
            callback.onReadThreadSummariesFail(errorItem!!)
        }
    }

    companion object {
        @JvmStatic
        fun concatenate(
            threadSummaries1: List<ThreadSummary>?,
            threadSummaries2: List<ThreadSummary>?,
        ): List<ThreadSummary> {
            if (threadSummaries1 == null) {
                return threadSummaries2.orEmpty()
            } else if (threadSummaries2 == null) {
                return threadSummaries1
            }
            val threadSummaries = ArrayList(threadSummaries1)
            val identifiers = HashSet<String>()
            for (threadSummary in threadSummaries) {
                identifiers.add(threadSummary.getBoardName() + '/' + threadSummary.getThreadNumber())
            }
            for (threadSummary in threadSummaries2) {
                if (!identifiers.contains(
                        threadSummary.getBoardName() + '/' +
                            threadSummary.getThreadNumber(),
                    )
                ) {
                    threadSummaries.add(threadSummary)
                }
            }
            return threadSummaries
        }
    }
}
