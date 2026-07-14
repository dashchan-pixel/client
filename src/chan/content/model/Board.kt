package chan.content.model

import chan.annotation.Public

@Public
class Board
    @Public
    constructor(
        private val boardName: String,
        private val title: String?,
        private val description: String?,
    ) : Comparable<Board> {
        @Public
        constructor(boardName: String, title: String?) : this(boardName, title, null)

        @Public
        fun getBoardName(): String = boardName

        @Public
        fun getTitle(): String? = title

        @Public
        fun getDescription(): String? = description

        @Public
        override fun compareTo(other: Board): Int = boardName.compareTo(other.boardName)
    }
