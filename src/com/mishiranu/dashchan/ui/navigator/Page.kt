package com.mishiranu.dashchan.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import chan.content.Chan.Companion.get
import chan.util.CommonUtils.equals
import com.mishiranu.dashchan.content.Preferences.getDefaultBoardName
import com.mishiranu.dashchan.content.Preferences.isMergeChans
import com.mishiranu.dashchan.ui.navigator.page.ArchivePage
import com.mishiranu.dashchan.ui.navigator.page.BoardsPage
import com.mishiranu.dashchan.ui.navigator.page.HistoryPage
import com.mishiranu.dashchan.ui.navigator.page.InboxPage
import com.mishiranu.dashchan.ui.navigator.page.ListPage
import com.mishiranu.dashchan.ui.navigator.page.PostsPage
import com.mishiranu.dashchan.ui.navigator.page.SearchPage
import com.mishiranu.dashchan.ui.navigator.page.ThreadsPage
import com.mishiranu.dashchan.ui.navigator.page.UserBoardsPage

class Page(
    val content: Content,
    @JvmField val chanName: String?,
    @JvmField val boardName: String?,
    @JvmField val threadNumber: String?,
    val searchQuery: String?,
) : Parcelable {
    enum class Content(
        private val pageFactory: PageFactory,
    ) {
        THREADS(PageFactory { ThreadsPage() }),
        POSTS(PageFactory { PostsPage() }),
        SEARCH(PageFactory { SearchPage() }),
        ARCHIVE(PageFactory { ArchivePage() }),
        BOARDS(PageFactory { BoardsPage() }),
        USER_BOARDS(PageFactory { UserBoardsPage() }),
        HISTORY(PageFactory { HistoryPage() }),
        INBOX(PageFactory { InboxPage() }),
        ;

        private fun interface PageFactory {
            fun newPage(): ListPage
        }

        fun newPage(): ListPage = pageFactory.newPage()
    }

    val isThreadsOrPosts: Boolean
        get() = content == Content.THREADS || content == Content.POSTS

    fun canDestroyIfNotInStack(): Boolean =
        content == Content.SEARCH ||
            content == Content.ARCHIVE ||
            content == Content.BOARDS ||
            content == Content.HISTORY ||
            content == Content.INBOX

    fun canRemoveFromStackIfDeep(): Boolean {
        if (content == Content.BOARDS) {
            val boardName = getDefaultBoardName(get(chanName))
            return boardName != null
        }
        return content == Content.SEARCH ||
            content == Content.ARCHIVE ||
            content == Content.USER_BOARDS ||
            content == Content.HISTORY ||
            content == Content.INBOX
    }

    val isMultiChanAllowed: Boolean
        get() = content == Content.HISTORY || content == Content.INBOX

    fun isThreadsOrPosts(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ): Boolean {
        if (threadNumber != null) {
            return `is`(Content.POSTS, chanName, boardName, threadNumber)
        } else {
            return `is`(Content.THREADS, chanName, boardName, null)
        }
    }

    fun `is`(
        content: Content?,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ): Boolean {
        if (this.content != content) {
            return false
        }
        if (!equals(this.chanName, chanName) && !(this.isMultiChanAllowed && isMergeChans)) {
            return false
        }
        var compareContentTypeOnlyThis = false
        var compareContentTypeOnlyCompared = false
        when (this.content) {
            Content.SEARCH, Content.BOARDS, Content.USER_BOARDS, Content.HISTORY, Content.INBOX -> {
                compareContentTypeOnlyThis = true
            }

            else -> {}
        }
        when (content) {
            Content.SEARCH, Content.BOARDS, Content.USER_BOARDS, Content.HISTORY, Content.INBOX -> {
                compareContentTypeOnlyCompared = true
            }

            else -> {}
        }
        if (compareContentTypeOnlyThis && compareContentTypeOnlyCompared) {
            return this.content == content
        }
        if (compareContentTypeOnlyThis || compareContentTypeOnlyCompared) {
            return false
        }
        return equals(this.boardName, boardName) && equals(this.threadNumber, threadNumber)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other is Page) {
            val page = other
            return content == page.content &&
                equals(chanName, page.chanName) &&
                equals(boardName, page.boardName) &&
                equals(threadNumber, page.threadNumber) &&
                equals(searchQuery, page.searchQuery)
        }
        return false
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + content.hashCode()
        result = prime * result + (if (chanName != null) chanName.hashCode() else 0)
        result = prime * result + (if (boardName != null) boardName.hashCode() else 0)
        result = prime * result + (if (threadNumber != null) threadNumber.hashCode() else 0)
        result = prime * result + (if (searchQuery != null) searchQuery.hashCode() else 0)
        return result
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(content.toString())
        dest.writeString(chanName)
        dest.writeString(boardName)
        dest.writeString(threadNumber)
        dest.writeString(searchQuery)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Page?> =
            object : Parcelable.Creator<Page?> {
                override fun createFromParcel(`in`: Parcel): Page {
                    val content = Content.valueOf(`in`.readString()!!)
                    val chanName = `in`.readString()
                    val boardName = `in`.readString()
                    val threadNumber = `in`.readString()
                    val searchQuery = `in`.readString()
                    return Page(content, chanName, boardName, threadNumber, searchQuery)
                }

                override fun newArray(size: Int): Array<Page?> = arrayOfNulls<Page>(size)
            }
    }
}
