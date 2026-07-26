package com.mishiranu.dashchan.content.model

import com.mishiranu.dashchan.text.HtmlParser
import com.mishiranu.dashchan.text.SimilarTextEstimator
import kotlin.math.abs

interface PendingUserPost {
    fun findUserPost(
        posts: List<Post>,
        lastExistingPostNumber: PostNumber?,
        firstPostIsOriginal: Boolean,
    ): PostNumber?

    class NewThread private constructor() : PendingUserPost {
        override fun findUserPost(
            posts: List<Post>,
            lastExistingPostNumber: PostNumber?,
            firstPostIsOriginal: Boolean,
        ): PostNumber? = if (firstPostIsOriginal && posts.isNotEmpty()) posts[0].number else null

        override fun equals(other: Any?): Boolean = other is NewThread

        override fun hashCode(): Int = 1

        companion object {
            @JvmField
            val INSTANCE = NewThread()
        }
    }

    class SimilarComment(
        comment: String?,
        private val time: Long,
    ) : PendingUserPost {
        private val wordsData: SimilarTextEstimator.WordsData<Unit>? = ESTIMATOR.getWords(comment)

        override fun findUserPost(
            posts: List<Post>,
            lastExistingPostNumber: PostNumber?,
            firstPostIsOriginal: Boolean,
        ): PostNumber? {
            var checkPosts = posts
            if (lastExistingPostNumber != null) {
                for (i in checkPosts.indices) {
                    if (checkPosts[i].number > lastExistingPostNumber) {
                        checkPosts = checkPosts.subList(i, checkPosts.size)
                        break
                    }
                }
            }
            var foundPost: Post? = null
            for (post in checkPosts) {
                val comment = HtmlParser.clear(post.comment)
                val wordsData = ESTIMATOR.getWords<Unit>(comment)
                if (ESTIMATOR.checkSimiliar(this.wordsData, wordsData) ||
                    (this.wordsData == null && wordsData == null)
                ) {
                    if (foundPost == null || abs(foundPost.timestamp - time) > abs(post.timestamp - time)) {
                        foundPost = post
                    }
                }
            }
            return foundPost?.number
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is SimilarComment) {
                return other.wordsData === wordsData ||
                    (
                        other.wordsData != null &&
                            wordsData != null &&
                            other.wordsData.count == wordsData.count &&
                            other.wordsData.words == wordsData.words
                    )
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            if (wordsData != null) {
                result = prime * result + wordsData.words.hashCode()
                result = prime * result + wordsData.count
            }
            return result
        }

        companion object {
            private val ESTIMATOR = SimilarTextEstimator(Int.MAX_VALUE, true)
        }
    }
}
