package chan.content.model

import chan.annotation.Public
import com.mishiranu.dashchan.content.model.PostNumber

@Public
class ThreadSummary
    @Public
    constructor(
        private val boardName: String?,
        private val threadNumber: String,
        private val description: String?,
    ) {
        private var postsCount = -1

        init {
            PostNumber.validateThreadNumber(threadNumber, false)
        }

        @Public
        fun getBoardName(): String? = boardName

        @Public
        fun getThreadNumber(): String = threadNumber

        @Public
        fun getDescription(): String? = description

        @Public
        fun getPostsCount(): Int = postsCount

        @Public
        fun setPostsCount(postsCount: Int): ThreadSummary {
            this.postsCount = postsCount
            return this
        }
    }
