package chan.content

import android.net.Uri
import chan.annotation.Public
import com.mishiranu.dashchan.content.model.PostNumber

@Public
class RedirectException : Exception {
    private val uri: Uri?
    private val boardName: String?
    private val threadNumber: String?
    private val postNumber: PostNumber?

    private constructor(boardName: String?, threadNumber: String?, postNumber: PostNumber?) : super() {
        this.uri = null
        this.boardName = boardName
        this.threadNumber = threadNumber
        this.postNumber = postNumber
    }

    private constructor(uri: Uri) : super() {
        this.uri = uri
        this.boardName = null
        this.threadNumber = null
        this.postNumber = null
    }

    class Target internal constructor(
        @JvmField val chanName: String?,
        @JvmField val boardName: String?,
        @JvmField val threadNumber: String?,
        @JvmField val postNumber: PostNumber?,
    )

    @Throws(ExtensionException::class)
    fun obtainTarget(chanName: String?): Target? {
        val uri = uri
        if (uri != null) {
            val chan = Chan.getPreferred(null, uri)
            return if (chan.name != null) {
                try {
                    when {
                        chan.locator.isBoardUri(uri) -> {
                            val boardName = chan.locator.getBoardName(uri)
                            Target(chan.name, boardName, null, null)
                        }

                        chan.locator.isThreadUri(uri) -> {
                            val boardName = chan.locator.getBoardName(uri)
                            val threadNumber = chan.locator.getThreadNumber(uri)
                            val postNumberString = chan.locator.getPostNumber(uri)
                            PostNumber.validateThreadNumber(threadNumber, false)
                            val postNumber =
                                if (postNumberString != null) {
                                    PostNumber.parseOrThrow(postNumberString)
                                } else {
                                    null
                                }
                            Target(chan.name, boardName, threadNumber, postNumber)
                        }

                        else -> {
                            null
                        }
                    }
                } catch (e: LinkageError) {
                    throw ExtensionException(e)
                } catch (e: RuntimeException) {
                    throw ExtensionException(e)
                }
            } else {
                null
            }
        } else {
            return Target(chanName, boardName, threadNumber, postNumber)
        }
    }

    companion object {
        @JvmStatic
        @Public
        fun toUri(uri: Uri?): RedirectException {
            if (uri == null) {
                throw NullPointerException("uri must not be null")
            }
            return RedirectException(uri)
        }

        @JvmStatic
        @Public
        fun toBoard(boardName: String?): RedirectException = RedirectException(boardName, null, null)

        @JvmStatic
        @Public
        fun toThread(
            boardName: String?,
            threadNumber: String?,
            postNumber: String?,
        ): RedirectException =
            toThread(
                boardName,
                threadNumber,
                if (postNumber != null) PostNumber.parseOrThrow(postNumber) else null,
            )

        @JvmStatic
        fun toThread(
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?,
        ): RedirectException {
            PostNumber.validateThreadNumber(threadNumber, false)
            return RedirectException(boardName, threadNumber, postNumber)
        }
    }
}
