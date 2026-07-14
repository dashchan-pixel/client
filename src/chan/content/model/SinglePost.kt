package chan.content.model

import com.mishiranu.dashchan.content.model.PostNumber

class SinglePost {
    @JvmField val post: com.mishiranu.dashchan.content.model.Post

    @JvmField val threadNumber: String

    @JvmField val originalPostNumber: PostNumber?

    constructor(
        post: com.mishiranu.dashchan.content.model.Post,
        threadNumber: String,
        originalPostNumber: PostNumber?,
    ) {
        this.post = post
        this.threadNumber = threadNumber
        this.originalPostNumber = originalPostNumber
    }

    constructor(post: Post) {
        threadNumber = post.getThreadNumberOrOriginalPostNumber()!!
        originalPostNumber = post.getOriginalPostNumber()?.let { PostNumber.parseOrThrow(it) }
        this.post = post.build()
    }
}
