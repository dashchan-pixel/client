package chan.content.model

import chan.annotation.Public

@Public
class Vote {
    private val likes: Int
    private val dislikes: Int
    private val showVotes: Boolean

    @Public
    constructor() {
        likes = 0
        dislikes = 0
        showVotes = false
    }

    @Public
    constructor(likes: Int, dislikes: Int) {
        this.likes = likes
        this.dislikes = dislikes
        showVotes = true
    }

    @Public
    fun getLikes(): Int = likes

    @Public
    fun getDislikes(): Int = dislikes

    @Public
    fun isShowVotes(): Boolean = showVotes
}
