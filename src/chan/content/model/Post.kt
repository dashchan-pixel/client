package chan.content.model

import chan.annotation.Public
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.model.PostNumber

@Public
class Post : Comparable<Post> {
    private val builder: ChanBuilder?
    private val postNumberCompat: PostNumber?

    @Public
    constructor() {
        builder = ChanBuilder()
        postNumberCompat = null
    }

    internal constructor(postNumber: PostNumber) {
        builder = null
        postNumberCompat = postNumber
    }

    @Public
    fun getThreadNumber(): String? = builder!!.threadNumber

    @Public
    fun setThreadNumber(threadNumber: String?): Post {
        PostNumber.validateThreadNumber(threadNumber, true)
        builder!!.threadNumber = threadNumber
        return this
    }

    fun getParentPostNumberOrNull(): String? {
        val parentPostNumber = getParentPostNumber()
        if (parentPostNumber == null || "0" == parentPostNumber || parentPostNumber == getPostNumber()) {
            return null
        }
        return parentPostNumber
    }

    @Public
    fun getParentPostNumber(): String? = builder!!.parentPostNumber

    @Public
    fun setParentPostNumber(parentPostNumber: String?): Post {
        if (parentPostNumber != null) {
            PostNumber.parseOrThrow(parentPostNumber)
        }
        builder!!.parentPostNumber = parentPostNumber
        return this
    }

    @Public
    fun getPostNumber(): String? =
        if (builder != null) {
            builder.builder.number?.toString()
        } else {
            postNumberCompat!!.toString()
        }

    @Public
    fun setPostNumber(postNumber: String): Post {
        builder!!.builder.number = PostNumber.parseOrThrow(postNumber)
        return this
    }

    fun getOriginalPostNumber(): String? = getParentPostNumberOrNull() ?: getPostNumber()

    fun getThreadNumberOrOriginalPostNumber(): String? = getThreadNumber() ?: getOriginalPostNumber()

    @Public
    fun getTimestamp(): Long = builder!!.builder.timestamp

    @Public
    fun setTimestamp(timestamp: Long): Post {
        builder!!.builder.timestamp = timestamp
        return this
    }

    @Public
    fun getSubject(): String? = builder!!.builder.subject

    @Public
    fun setSubject(subject: String?): Post {
        builder!!.builder.subject = StringUtils.nullIfEmpty(subject)
        return this
    }

    @Public
    fun getComment(): String? = builder!!.builder.comment

    @Public
    fun setComment(comment: String?): Post {
        builder!!.builder.comment = StringUtils.nullIfEmpty(comment)
        return this
    }

    @Public
    fun getCommentMarkup(): String? = builder!!.builder.commentMarkup

    @Public
    fun setCommentMarkup(commentMarkup: String?): Post {
        builder!!.builder.commentMarkup = StringUtils.nullIfEmpty(commentMarkup)
        return this
    }

    @Public
    fun getName(): String? = builder!!.builder.name

    @Public
    fun setName(name: String?): Post {
        builder!!.builder.name = StringUtils.nullIfEmpty(name)
        return this
    }

    @Public
    fun getIdentifier(): String? = builder!!.builder.identifier

    @Public
    fun setIdentifier(identifier: String?): Post {
        builder!!.builder.identifier = StringUtils.nullIfEmpty(identifier)
        return this
    }

    @Public
    fun getTripcode(): String? = builder!!.builder.tripcode

    @Public
    fun setTripcode(tripcode: String?): Post {
        builder!!.builder.tripcode = StringUtils.nullIfEmpty(tripcode)
        return this
    }

    @Public
    fun getCapcode(): String? = builder!!.builder.capcode

    @Public
    fun setCapcode(capcode: String?): Post {
        builder!!.builder.capcode = StringUtils.nullIfEmpty(capcode)
        return this
    }

    @Public
    fun getEmail(): String? = builder!!.builder.email

    @Public
    fun setEmail(email: String?): Post {
        builder!!.builder.email = StringUtils.nullIfEmpty(email)
        return this
    }

    @Public
    fun getAttachmentsCount(): Int = builder!!.attachments?.size ?: 0

    @Public
    fun getAttachmentAt(index: Int): Attachment? = builder!!.attachments!![index]

    @Public
    fun setAttachments(vararg attachments: Attachment?): Post {
        @Suppress("UNCHECKED_CAST")
        builder!!.attachments =
            CommonUtils.removeNullItems(
                attachments as Array<Attachment?>,
                Attachment::class.java,
            )
        return this
    }

    @Public
    fun setAttachments(attachments: Collection<Attachment>?): Post {
        @Suppress("UNCHECKED_CAST")
        builder!!.attachments =
            CommonUtils.toArray(attachments, Attachment::class.java)
                as Array<Attachment?>?
        return this
    }

    @Public
    fun getIconsCount(): Int = builder!!.icons?.size ?: 0

    @Public
    fun getIconAt(index: Int): Icon? = builder!!.icons!![index]

    @Public
    fun setIcons(vararg icons: Icon?): Post {
        @Suppress("UNCHECKED_CAST")
        builder!!.icons = CommonUtils.removeNullItems(icons as Array<Icon?>, Icon::class.java)
        return this
    }

    @Public
    fun setIcons(icons: Collection<Icon>?): Post {
        @Suppress("UNCHECKED_CAST")
        builder!!.icons = CommonUtils.toArray(icons, Icon::class.java) as Array<Icon?>?
        return this
    }

    @Public
    fun isSage(): Boolean = builder!!.builder.isSage

    @Public
    fun setSage(sage: Boolean): Post {
        builder!!.builder.isSage = sage
        return this
    }

    @Public
    fun isSticky(): Boolean = builder!!.builder.isSticky

    @Public
    fun setSticky(sticky: Boolean): Post {
        builder!!.builder.isSticky = sticky
        return this
    }

    @Public
    fun isClosed(): Boolean = builder!!.builder.isClosed

    @Public
    fun setClosed(closed: Boolean): Post {
        builder!!.builder.isClosed = closed
        return this
    }

    @Public
    fun isArchived(): Boolean = builder!!.builder.isArchived

    @Public
    fun setArchived(archived: Boolean): Post {
        builder!!.builder.isArchived = archived
        return this
    }

    @Public
    fun isCyclical(): Boolean = builder!!.builder.isCyclical

    @Public
    fun setCyclical(cyclical: Boolean): Post {
        builder!!.builder.isCyclical = cyclical
        return this
    }

    @Public
    fun isPosterWarned(): Boolean = builder!!.builder.isPosterWarned

    @Public
    fun setPosterWarned(posterWarned: Boolean): Post {
        builder!!.builder.isPosterWarned = posterWarned
        return this
    }

    @Public
    fun isPosterBanned(): Boolean = builder!!.builder.isPosterBanned

    @Public
    fun setPosterBanned(posterBanned: Boolean): Post {
        builder!!.builder.isPosterBanned = posterBanned
        return this
    }

    @Public
    fun isOriginalPoster(): Boolean = builder!!.builder.isOriginalPoster

    @Public
    fun setOriginalPoster(originalPoster: Boolean): Post {
        builder!!.builder.isOriginalPoster = originalPoster
        return this
    }

    @Public
    fun isDefaultName(): Boolean = builder!!.builder.isDefaultName

    @Public
    fun setDefaultName(defaultName: Boolean): Post {
        builder!!.builder.isDefaultName = defaultName
        return this
    }

    @Public
    fun isBumpLimitReached(): Boolean = builder!!.builder.isBumpLimitReached

    @Public
    fun setBumpLimitReached(bumpLimitReached: Boolean): Post {
        builder!!.builder.isBumpLimitReached = bumpLimitReached
        return this
    }

    @Public
    fun setVote(
        likes: Int,
        dislikes: Int,
    ): Post {
        builder!!.vote = Vote(likes, dislikes)
        return this
    }

    @Public
    fun setAIGenerated(aiGenerated: Boolean): Post {
        builder!!.builder.setAIGenerated(aiGenerated)
        return this
    }

    @Public
    fun getExtra(): String? = StringUtils.nullIfEmpty(builder!!.builder.extra)

    /**
     * Stores an opaque payload the client keeps with this post and hands back to the extension's
     * [chan.content.ChanPostDecorator] when the post is displayed. The client never parses it, so
     * any self-describing encoding works; [chan.text.JsonSerial] is the intended one.
     *
     * The payload is written to the post cache along with the rest of the post, so it survives an
     * application restart. Keep it small: it is held in memory for every loaded post.
     */
    @Public
    fun setExtra(extra: String?): Post {
        builder!!.builder.extra = extra
        return this
    }

    @Public
    override fun compareTo(other: Post): Int = builder!!.builder.number!!.compareTo(other.builder!!.builder.number!!)

    private class ChanBuilder {
        val builder =
            com.mishiranu.dashchan.content.model.Post
                .Builder()

        var threadNumber: String? = null
        var parentPostNumber: String? = null
        var attachments: Array<Attachment?>? = null
        var icons: Array<Icon?>? = null
        var vote: Vote? = null
    }

    fun build(): com.mishiranu.dashchan.content.model.Post {
        val builder = this.builder!!
        val attachments = builder.attachments
        if (attachments != null && attachments.isNotEmpty()) {
            val list = ArrayList<com.mishiranu.dashchan.content.model.Post.Attachment>()
            builder.builder.attachments = list
            for (attachment in attachments) {
                if (attachment is FileAttachment) {
                    val file =
                        com.mishiranu.dashchan.content.model.Post.Attachment.File
                            .createExternal(
                                attachment.fileUri,
                                attachment.thumbnailUri,
                                attachment.getOriginalName(),
                                attachment.getSize(),
                                attachment.getWidth(),
                                attachment.getHeight(),
                                attachment.isSpoiler(),
                            )
                    if (file != null) {
                        list.add(file)
                    }
                } else if (attachment is EmbeddedAttachment) {
                    list.add(attachment.embedded)
                }
            }
        }
        val icons = builder.icons
        if (icons != null && icons.isNotEmpty()) {
            val list = ArrayList<com.mishiranu.dashchan.content.model.Post.Icon>()
            builder.builder.icons = list
            for (icon in icons) {
                if (icon != null) {
                    val postIcon =
                        com.mishiranu.dashchan.content.model.Post.Icon
                            .createExternal(icon.uri, icon.getTitle())
                    if (postIcon != null) {
                        list.add(postIcon)
                    }
                }
            }
        }
        val vote = builder.vote
        if (vote != null) {
            builder.builder.vote =
                com.mishiranu.dashchan.content.model.Post.Vote
                    .createExternal(vote.getLikes(), vote.getDislikes())
        }

        return builder.builder.build(false)
    }
}
