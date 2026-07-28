package com.mishiranu.dashchan.content.model

import android.content.res.Resources
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanMarkup
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.text.HtmlParser
import com.mishiranu.dashchan.text.style.LinkSpan
import com.mishiranu.dashchan.text.style.LinkSuffixSpan
import com.mishiranu.dashchan.text.style.MediumSpan
import com.mishiranu.dashchan.text.style.NameColorSpan
import com.mishiranu.dashchan.text.style.SpoilerSpan
import com.mishiranu.dashchan.util.PostDateFormatter
import java.util.TreeSet

class PostItem private constructor(
    private val post: Post,
    threadDataBase: ThreadData.Base?,
    chan: Chan,
    private val boardName: String?,
    internal val threadNumber: String?,
    private val originalPostNumber: PostNumber,
) : AttachmentItem.Master,
    ChanMarkup.MarkupExtra,
    Comparable<PostItem>,
    Preferences.CatalogSort.Comparable {
    enum class HideState(
        @JvmField val hidden: Boolean,
    ) {
        UNDEFINED(false),
        HIDDEN(true),
        SHOWN(false),
        ;

        class Map<T> {
            private val map = HashMap<T, Boolean>()

            fun get(key: T): HideState {
                val hidden = map[key]
                return if (hidden == null) {
                    UNDEFINED
                } else if (hidden) {
                    HIDDEN
                } else {
                    SHOWN
                }
            }

            fun set(
                key: T,
                hideState: HideState,
            ) {
                when (hideState) {
                    HIDDEN -> map[key] = true
                    SHOWN -> map[key] = false
                    UNDEFINED -> map.remove(key)
                }
            }

            fun addAll(map: Map<T>) {
                this.map.putAll(map.map)
            }

            fun clear() {
                map.clear()
            }

            fun size(): Int = map.size

            fun count(state: HideState): Int {
                val seek = state == HIDDEN
                return map.values.count { it == seek }
            }
        }
    }

    private val threadData: ThreadData?
    private val attachmentItems: List<AttachmentItem>?

    private var ordinalIndex = ORDINAL_INDEX_NONE

    private var subject: String? = null
    private var comment: CharSequence? = null
    private var commentOverride: String? = null
    private var fullName: CharSequence? = null
    private var commentSpans: Array<ColorScheme.Span>? = null
    private var fullNameSpans: Array<ColorScheme.Span>? = null
    private var linkSpans: Array<LinkSpan>? = null
    private var linkSuffixSpans: Array<LinkSuffixSpan>? = null
    private var dateTimeHolder: PostDateFormatter.Holder? = null
    private var useDefaultName = false

    private val referencesTo: Set<PostNumber>
    private var referencesFrom: TreeSet<PostNumber>? = null

    private var hideState = HideState.UNDEFINED
    private var hideReason: String? = null

    private class ThreadData(
        val base: Base,
        val commentShort: CharSequence,
        val commentShortSpans: Array<ColorScheme.Span>?,
        val gallerySet: GalleryItem.Set?,
    ) {
        class Base(
            val postsCount: Int,
            val filesCount: Int,
            val postsWithFilesCount: Int,
            val posts: List<Post>,
        )
    }

    init {
        attachmentItems = AttachmentItem.obtain(this, post, chan.locator)
        if (threadDataBase != null) {
            val commentShort = obtainThreadComment(post.comment, chan.markup, this)
            val commentShortSpans = ColorScheme.getSpans(commentShort)
            var gallerySet: GalleryItem.Set? = null
            if (attachmentItems != null) {
                gallerySet = GalleryItem.Set(false)
                gallerySet.setThreadTitle(getSubjectOrComment())
                gallerySet.put(post.number, attachmentItems)
            }
            threadData = ThreadData(threadDataBase, commentShort, commentShortSpans, gallerySet)
            referencesTo = emptySet()
        } else {
            threadData = null
            referencesTo = collectReferences(null, post.comment).orEmpty()
        }
    }

    fun getPost(): Post = post

    // Replacement for the parsed decorator payload, set by a ChanPostDecorator action so the post
    // can show what the action changed without re-reading the thread. Deliberately not persisted:
    // it lasts only until the thread is read again, at which point the cache holds the board's own
    // answer and this must give way to it -- see DecoratorUnit.discardExtraOverrides.
    private var decoratorExtraOverride: String? = null

    fun getDecoratorExtra(): String? = decoratorExtraOverride ?: StringUtils.nullIfEmpty(post.extra)

    /** The replacement alone, so a caller can tell it apart from the parsed payload. */
    fun getDecoratorExtraOverride(): String? = decoratorExtraOverride

    fun setDecoratorExtraOverride(extra: String?) {
        decoratorExtraOverride = extra
    }

    fun setOrdinalIndex(ordinalIndex: Int) {
        this.ordinalIndex = ordinalIndex
    }

    fun getOrdinalIndex(): Int = ordinalIndex

    fun getOrdinalIndexString(): String? {
        if (ordinalIndex >= 0) {
            return (ordinalIndex + 1).toString()
        }
        if (ordinalIndex == ORDINAL_INDEX_DELETED) {
            return "X"
        }
        return null
    }

    override fun getBoardName(): String? = boardName

    override fun getThreadNumber(): String? = threadNumber

    override fun getPostNumber(): PostNumber = post.number

    fun getOriginalPostNumber(): PostNumber = originalPostNumber

    fun isOriginalPost(): Boolean = originalPostNumber == post.number

    override fun compareTo(other: PostItem): Int = post.compareTo(other.getPost())

    // Returns whether name is default. Call this method only after getFullName.
    fun isUseDefaultName(): Boolean = useDefaultName

    private fun makeFullName(configuration: ChanConfiguration): CharSequence {
        var name = post.name
        val identifier = post.identifier
        val tripcode = post.tripcode
        val capcode = post.capcode
        val defaultName =
            configuration
                .getDefaultName(boardName)
                ?.takeIf { !StringUtils.isEmptyOrWhitespace(it) }
                ?: "Anonymous"
        name = if (StringUtils.isEmptyOrWhitespace(name)) defaultName else name.trim()
        var useDefaultName = post.isDefaultName || name == defaultName
        val hasIdentifier = !StringUtils.isEmptyOrWhitespace(identifier)
        val hasTripcode = !StringUtils.isEmptyOrWhitespace(tripcode)
        val hasCapcode = !StringUtils.isEmptyOrWhitespace(capcode)
        val fullName: CharSequence
        if (hasIdentifier || hasTripcode || hasCapcode) {
            val spannable = SpannableStringBuilder()
            if (!useDefaultName) {
                spannable.append(name)
            }
            if (hasIdentifier) {
                if (spannable.isNotEmpty()) {
                    spannable.append(' ')
                }
                StringUtils.appendSpan(spannable, identifier, NameColorSpan(NameColorSpan.TYPE_TRIPCODE))
            }
            if (hasTripcode) {
                if (spannable.isNotEmpty()) {
                    spannable.append(' ')
                }
                StringUtils.appendSpan(spannable, tripcode, NameColorSpan(NameColorSpan.TYPE_TRIPCODE))
            }
            if (hasCapcode) {
                if (spannable.isNotEmpty()) {
                    spannable.append(' ')
                }
                StringUtils.appendSpan(spannable, "## $capcode", NameColorSpan(NameColorSpan.TYPE_CAPCODE))
            }
            fullName = spannable
            useDefaultName = false
        } else {
            fullName = name
        }
        this.useDefaultName = useDefaultName
        return fullName
    }

    fun getFullName(chan: Chan): CharSequence {
        var fullName = this.fullName
        if (fullName == null) {
            fullName = makeFullName(chan.configuration)
            if (StringUtils.isEmpty(fullName)) {
                fullName = ""
            }
            fullNameSpans = ColorScheme.getSpans(fullName)
            this.fullName = fullName
        }
        return fullName
    }

    fun getFullNameSpans(): Array<ColorScheme.Span>? = fullNameSpans

    fun getEmail(): String = post.email

    fun isSage(): Boolean = post.isSage && !isOriginalPost()

    fun isSticky(): Boolean = post.isSticky && isOriginalPost()

    fun isClosed(): Boolean = (post.isClosed || post.isArchived) && isOriginalPost()

    fun isCyclical(): Boolean = post.isCyclical && isOriginalPost()

    fun isOriginalPoster(): Boolean = post.isOriginalPoster || isOriginalPost()

    fun isPosterWarned(): Boolean = post.isPosterWarned

    fun isPosterBanned(): Boolean = post.isPosterBanned

    fun isHidden(): Boolean = hideState.hidden

    enum class BumpLimitState { NOT_REACHED, REACHED, NEED_COUNT }

    fun getBumpLimitReachedState(
        chan: Chan,
        postsCount: Int,
    ): BumpLimitState {
        if (!isOriginalPost() || isSticky() || isCyclical()) {
            return BumpLimitState.NOT_REACHED
        }
        if (post.isBumpLimitReached) {
            return BumpLimitState.REACHED
        }
        var count = postsCount
        if (threadData != null) {
            count = threadData.base.postsCount
        }
        if (count > 0) {
            val bumpLimit = chan.configuration.getBumpLimitWithMode(getBoardName())
            if (bumpLimit != ChanConfiguration.BUMP_LIMIT_INVALID) {
                return if (count >= bumpLimit) BumpLimitState.REACHED else BumpLimitState.NOT_REACHED
            }
        }
        return if (threadData != null) BumpLimitState.NOT_REACHED else BumpLimitState.NEED_COUNT
    }

    fun isDeleted(): Boolean = post.deleted

    fun getSubjectOrComment(): String {
        val subject = getSubject()
        if (!StringUtils.isEmpty(subject)) {
            return subject
        }
        return StringUtils.cutIfLongerToLine(HtmlParser.clear(post.comment), 50, true)
    }

    fun getSubject(): String {
        var subject = this.subject
        if (subject == null) {
            subject = post.subject
            if (!StringUtils.isEmpty(subject)) {
                subject = subject.replace("\r", "").replace("\n", " ").trim()
                if (subject.length == 1 && (subject[0] == '\u202d' || subject[0] == '\u202e')) {
                    subject = ""
                }
            } else {
                subject = ""
            }
            this.subject = subject
        }
        return subject
    }

    fun getComment(chan: Chan): CharSequence {
        var comment = this.comment
        if (comment == null) {
            // A thread command's override stands in for the post's HTML, not for its rendered text, so
            // it goes through the same parse — markup, colours and links survive it.
            comment =
                obtainComment(
                    commentOverride ?: post.comment,
                    chan.markup,
                    getThreadNumber(),
                    getOriginalPostNumber(),
                    this,
                )
            comment = StringUtils.reduceEmptyLines(comment)
            commentSpans = ColorScheme.getSpans(comment)
            linkSpans = (comment as? Spanned)?.getSpans(0, comment.length, LinkSpan::class.java)
            linkSuffixSpans =
                (comment as? Spanned)?.getSpans(
                    0,
                    comment.length,
                    LinkSuffixSpan::class.java,
                )
            this.comment = comment
        }
        return comment
    }

    /**
     * Replaces the HTML this post's comment is rendered from with [html] (a
     * [com.mishiranu.dashchan.content.CommandRunner] thread command's inline result), or clears a
     * previous override when [html] is `null`. It is the same source form as
     * [Post.comment][chan.content.model.Post.getComment] and is parsed the same way, so whatever markup
     * it carries — greentext, spoilers, `>>` links — renders as it would in a real post. The cached
     * comment products are dropped so the next [getComment] rebuilds from the override (or, once
     * cleared, from the source post). No-op when nothing actually changes, so unchanged posts are not
     * needlessly re-parsed.
     *
     * Only the rendering changes: [getCommentMarkup] and the reply graph keep describing the post as it
     * arrived, which is also what a re-run of the command sees.
     */
    fun setCommentOverride(html: String?) {
        if (commentOverride == html) {
            return
        }
        commentOverride = html
        comment = null
        commentSpans = null
        linkSpans = null
        linkSuffixSpans = null
    }

    fun getComment(
        chan: Chan,
        repliesToPost: PostNumber,
    ): CharSequence {
        val comment = SpannableString(getComment(chan))
        val spans = comment.getSpans(0, comment.length, LinkSpan::class.java)
        if (spans != null) {
            val commentString = comment.toString()
            val reference = ">>$repliesToPost"
            for (linkSpan in spans) {
                val start = comment.getSpanStart(linkSpan)
                if (commentString.indexOf(reference, start) == start) {
                    val end = comment.getSpanEnd(linkSpan)
                    comment.setSpan(MediumSpan(), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return comment
    }

    fun getCommentSpans(): Array<ColorScheme.Span>? = commentSpans

    fun getThreadCommentShort(
        maxWidth: Int,
        textSize: Float,
        maxLines: Int,
    ): CharSequence {
        val factor = maxWidth * maxLines / textSize
        val count = (factor * 3f).toInt()
        val comment = threadData!!.commentShort
        if (comment is Spanned) {
            val spoilerSpans = comment.getSpans(0, comment.length, SpoilerSpan::class.java)
            if (spoilerSpans != null) {
                val enabled = !Preferences.isShowSpoilers
                for (spoilerSpan in spoilerSpans) {
                    spoilerSpan.setEnabled(enabled)
                }
            }
        }
        return if (count > 0 && comment.length > count) comment.subSequence(0, count) else comment
    }

    fun getThreadCommentShortSpans(): Array<ColorScheme.Span>? = threadData!!.commentShortSpans

    fun getCommentMarkup(chan: Chan): String =
        if (!StringUtils.isEmpty(post.commentMarkup)) {
            post.commentMarkup
        } else if (!StringUtils.isEmpty(post.comment)) {
            HtmlParser.unmark(post.comment, chan.markup.markup, this)
        } else {
            ""
        }

    // Must be called only after getComment.
    fun getLinkSuffixSpansAfterComment(): Array<LinkSuffixSpan>? = linkSuffixSpans

    // Must be called only after getComment.
    fun getLinkSpansAfterComment(): Array<LinkSpan>? = linkSpans

    fun getIcons(): List<Post.Icon> = post.icons

    fun hasAttachments(): Boolean = attachmentItems != null

    fun getAttachmentItems(): List<AttachmentItem>? = attachmentItems

    fun getAttachmentsDescription(
        resources: Resources,
        formatMode: AttachmentItem.FormatMode,
    ): String {
        val attachmentItems = this.attachmentItems!!
        val count = attachmentItems.size
        if (count == 1) {
            return attachmentItems[0].getDescription(formatMode)
        } else {
            var size = 0
            for (attachmentItem in attachmentItems) {
                size += attachmentItem.getSize()
            }
            val builder = StringBuilder()
            if (size > 0) {
                builder.append(StringUtils.formatFileSize(size.toLong(), true)).append(' ')
            }
            builder.append(resources.getQuantityString(R.plurals.number_files__format, count, count))
            return builder.toString()
        }
    }

    fun addReferenceFrom(postNumber: PostNumber) {
        var referencesFrom = this.referencesFrom
        if (referencesFrom == null) {
            referencesFrom = TreeSet()
            this.referencesFrom = referencesFrom
        }
        referencesFrom.add(postNumber)
    }

    fun removeReferenceFrom(postNumber: PostNumber) {
        referencesFrom?.remove(postNumber)
    }

    fun clearReferencesFrom() {
        referencesFrom?.clear()
    }

    fun getReferencesTo(): Set<PostNumber> = referencesTo

    fun getReferencesFrom(): Set<PostNumber> = referencesFrom.orEmpty()

    fun getPostReplyCount(): Int = referencesFrom?.size ?: 0

    fun getThreadGallerySet(): GalleryItem.Set = threadData!!.gallerySet!!

    override fun getThreadPostsCount(): Int = threadData!!.base.postsCount

    fun getThreadPosts(chan: Chan): List<PostItem> {
        val threadData = this.threadData!!
        val count = threadData.base.posts.size
        if (count >= 2) {
            var startIndex = threadData.base.postsCount - count + 1
            val postItems = ArrayList<PostItem>(count - 1)
            for (post in threadData.base.posts.subList(1, count)) {
                val postItem = createPost(post, chan, boardName, threadNumber, originalPostNumber)
                postItem.setOrdinalIndex(if (startIndex > 0) startIndex++ else ORDINAL_INDEX_NONE)
                postItems.add(postItem)
            }
            return postItems
        }
        return emptyList()
    }

    fun interface DescriptionBuilder {
        fun append(value: String)
    }

    fun formatThreadCardDescription(
        resources: Resources,
        repliesOnly: Boolean,
        builder: DescriptionBuilder,
    ) {
        val threadData = this.threadData!!
        val originalPostFiles = post.attachments.size
        val replies = threadData.base.postsCount - 1
        val files = threadData.base.filesCount - originalPostFiles
        val postsWithFiles = threadData.base.postsWithFilesCount - if (originalPostFiles > 0) 1 else 0
        var hasInformation = false
        if (replies >= 0) {
            hasInformation = true
            builder.append(resources.getQuantityString(R.plurals.number_replies__format, replies, replies))
        }
        if (!repliesOnly) {
            if (postsWithFiles >= 0) {
                hasInformation = true
                builder.append(resources.getString(R.string.number_with_files__format, postsWithFiles))
            } else if (files >= 0) {
                hasInformation = true
                builder.append(resources.getQuantityString(R.plurals.number_files__format, files, files))
            }
            if (hasAttachments()) {
                var size = 0
                for (attachmentItem in attachmentItems!!) {
                    size += attachmentItem.getSize()
                }
                if (size > 0) {
                    hasInformation = true
                    builder.append(StringUtils.formatFileSize(size.toLong(), true))
                }
            }
        }
        if (!hasInformation) {
            builder.append(resources.getString(R.string.no_information))
        }
    }

    override fun getTimestamp(): Long = post.timestamp

    fun getDateTime(formatter: PostDateFormatter): String? {
        val time = getTimestamp()
        return if (time > 0L) {
            val holder = formatter.formatDateTime(getTimestamp(), dateTimeHolder)
            dateTimeHolder = holder
            holder.text
        } else {
            null
        }
    }

    fun isThreadItem(): Boolean = threadData != null

    fun getHideState(): HideState = hideState

    fun getHideReason(): String? = hideReason

    fun getLikes(): Int = post.vote?.like ?: 0

    fun getDislikes(): Int = post.vote?.dislike ?: 0

    fun isShowVotes(): Boolean = post.vote?.showVotes ?: false

    fun setHidden(
        hideState: HideState,
        hideReason: String?,
    ) {
        this.hideState = hideState
        this.hideReason = hideReason
    }

    companion object {
        const val ORDINAL_INDEX_NONE = -1
        const val ORDINAL_INDEX_DELETED = -2

        @JvmStatic
        fun createPost(
            post: Post,
            chan: Chan,
            boardName: String?,
            threadNumber: String?,
            originalPostNumber: PostNumber,
        ): PostItem = PostItem(post, null, chan, boardName, threadNumber, originalPostNumber)

        @JvmStatic
        fun createThread(
            posts: List<Post>,
            postsCount: Int,
            filesCount: Int,
            postsWithFilesCount: Int,
            chan: Chan,
            boardName: String?,
            threadNumber: String?,
        ): PostItem {
            val post = posts[0]
            val threadData = ThreadData.Base(postsCount, filesCount, postsWithFilesCount, posts)
            return PostItem(post, threadData, chan, boardName, threadNumber, post.number)
        }

        @JvmStatic
        fun collectReferences(
            references: MutableSet<PostNumber>?,
            comment: String?,
        ): Set<PostNumber>? {
            var result = references
            if (!comment.isNullOrEmpty()) {
                // Fast find all <a.+?>(?:>>|&gt;&gt;)(\d+)(?:\.(\d+))?</a>
                var index1 = -1
                while (true) {
                    index1 = StringUtils.nearestIndexOf(comment, index1, "<a ", "<a\n", "<a\r")
                    if (index1 == -1) {
                        break
                    }
                    index1 = comment.indexOf(">", index1)
                    if (index1 == -1) {
                        break
                    }
                    val index2 = comment.indexOf("</a>", index1)
                    if (index2 > index1++) {
                        var start = -1
                        val length = index2 - index1
                        if (length > 2 && comment.startsWith(">>", index1)) {
                            start = 2
                        } else if (length > 8 && comment.startsWith("&gt;&gt;", index1)) {
                            start = 8
                        }
                        if (start >= 0) {
                            var number = true
                            var dotIndex = -1
                            for (i in start until length) {
                                val c = comment[index1 + i]
                                if (c == '.') {
                                    if (dotIndex < 0) {
                                        dotIndex = i
                                    } else {
                                        number = false
                                        break
                                    }
                                } else if (c < '0' || c > '9') {
                                    number = false
                                    break
                                }
                            }
                            if (!number || dotIndex in 0..start || dotIndex >= length - 1) {
                                continue
                            }
                            if (result == null) {
                                result = TreeSet()
                            }
                            val major =
                                comment
                                    .substring(
                                        index1 + start,
                                        index1 + if (dotIndex >= 0) dotIndex else length,
                                    ).toInt()
                            val minor =
                                if (dotIndex >= 0) {
                                    comment.substring(index1 + dotIndex + 1, index1 + length).toInt()
                                } else {
                                    0
                                }
                            result.add(PostNumber(major, minor))
                        }
                    } else {
                        break
                    }
                }
            }
            return result
        }

        private fun obtainComment(
            comment: String?,
            markup: ChanMarkup,
            threadNumber: String?,
            originalPostNumber: PostNumber?,
            extra: ChanMarkup.MarkupExtra,
        ): CharSequence =
            if (comment.isNullOrEmpty()) {
                ""
            } else {
                HtmlParser.spanify(
                    comment,
                    markup.markup,
                    threadNumber,
                    originalPostNumber,
                    extra,
                )
            }

        private fun obtainThreadComment(
            comment: String?,
            markup: ChanMarkup,
            extra: ChanMarkup.MarkupExtra,
        ): CharSequence {
            val commentBuilder =
                SpannableStringBuilder(
                    obtainComment(
                        comment,
                        markup,
                        null,
                        null,
                        extra,
                    ),
                )
            var linebreaks = 0
            // Remove more than one linebreaks in sequence
            for (i in commentBuilder.length - 1 downTo 0) {
                val c = commentBuilder[i]
                if (c == '\n') {
                    linebreaks++
                } else {
                    if (linebreaks > 1) {
                        // Remove linebreaks - 1 characters, keeping one line break
                        commentBuilder.delete(i + 1, i + linebreaks)
                    }
                    linebreaks = 0
                }
            }
            return commentBuilder
        }
    }
}
