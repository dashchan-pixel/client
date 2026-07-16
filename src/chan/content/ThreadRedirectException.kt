package chan.content

import chan.annotation.Public
import com.mishiranu.dashchan.content.model.PostNumber

// Retained: caught by ReadPostsTask and declared by ChanPerformer for thread-redirect handling.
@Public
class ThreadRedirectException
    @Public
    constructor(
        private val boardName: String?,
        private val threadNumber: String?,
        private val postNumber: String?,
    ) : Exception() {
        init {
            PostNumber.validateThreadNumber(threadNumber, false)
        }

        @Public
        constructor(threadNumber: String?, postNumber: String?) : this(null, threadNumber, postNumber)

        @Throws(ExtensionException::class)
        fun obtainTarget(
            chanName: String?,
            boardName: String?,
        ): RedirectException.Target =
            RedirectException
                .toThread(
                    this.boardName ?: boardName,
                    threadNumber,
                    postNumber,
                ).obtainTarget(chanName)!!
    }
