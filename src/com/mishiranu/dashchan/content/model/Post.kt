package com.mishiranu.dashchan.content.model

import android.net.Uri
import chan.text.JsonSerial
import chan.text.ParseException
import chan.util.StringUtils
import com.mishiranu.dashchan.util.FlagUtils
import java.io.IOException

/**
 * A parsed post.
 *
 * [extra] is an opaque extension-owned payload, empty when the extension supplied none. The client
 * never interprets it: it is stored and restored verbatim, including through the post cache, so a
 * [chan.content.ChanPostDecorator] can render data that has no representation in this model.
 */
class Post private constructor(
    @JvmField val number: PostNumber,
    @JvmField val deleted: Boolean,
    private val flags: Int,
    @JvmField val timestamp: Long,
    @JvmField val subject: String,
    @JvmField val comment: String,
    @JvmField val commentMarkup: String,
    @JvmField val name: String,
    @JvmField val identifier: String,
    @JvmField val tripcode: String,
    @JvmField val capcode: String,
    @JvmField val email: String,
    @JvmField val attachments: List<Attachment>,
    @JvmField val icons: List<Icon>,
    @JvmField val vote: Vote?,
    @JvmField val extra: String,
) : Comparable<Post> {
    interface Attachment {
        class File private constructor(
            @JvmField val fileUri: Uri?,
            @JvmField val thumbnailUri: Uri?,
            @JvmField val originalName: String,
            @JvmField val size: Int,
            @JvmField val width: Int,
            @JvmField val height: Int,
            @JvmField val spoiler: Boolean,
        ) : Attachment {
            companion object {
                @JvmStatic
                fun createExternal(
                    fileUri: Uri?,
                    thumbnailUri: Uri?,
                    originalName: String?,
                    size: Int,
                    width: Int,
                    height: Int,
                    spoiler: Boolean,
                ): File? =
                    if (fileUri != null || thumbnailUri != null) {
                        File(
                            fileUri,
                            thumbnailUri,
                            StringUtils.emptyIfNull(originalName),
                            size,
                            width,
                            height,
                            spoiler,
                        )
                    } else {
                        null
                    }

                internal fun createInternal(
                    fileUri: Uri?,
                    thumbnailUri: Uri?,
                    originalName: String,
                    size: Int,
                    width: Int,
                    height: Int,
                    spoiler: Boolean,
                ): File = File(fileUri, thumbnailUri, originalName, size, width, height, spoiler)
            }
        }

        class Embedded private constructor(
            @JvmField val fileUri: Uri?,
            @JvmField val thumbnailUri: Uri?,
            @JvmField val embeddedType: String,
            @JvmField val contentType: ContentType?,
            @JvmField val canDownload: Boolean,
            @JvmField val forcedName: String,
        ) : Attachment {
            enum class ContentType { AUDIO, VIDEO }

            companion object {
                internal fun validate(
                    throwOnFail: Boolean,
                    fileUri: Uri?,
                    embeddedType: String?,
                    contentType: ContentType?,
                ): Boolean {
                    if (fileUri == null) {
                        require(!throwOnFail) { "fileUri is null" }
                        return false
                    }
                    if (embeddedType.isNullOrEmpty()) {
                        require(!throwOnFail) { "embeddedType is empty" }
                        return false
                    }
                    if (contentType == null) {
                        require(!throwOnFail) { "contentType is null" }
                        return false
                    }
                    return true
                }

                @JvmStatic
                fun createExternal(
                    throwOnFail: Boolean,
                    fileUri: Uri?,
                    thumbnailUri: Uri?,
                    embeddedType: String?,
                    contentType: ContentType?,
                    canDownload: Boolean,
                    forcedName: String?,
                ): Embedded? {
                    if (!validate(throwOnFail, fileUri, embeddedType, contentType)) {
                        return null
                    }
                    return Embedded(
                        fileUri,
                        thumbnailUri,
                        StringUtils.emptyIfNull(embeddedType),
                        contentType,
                        canDownload,
                        StringUtils.emptyIfNull(forcedName),
                    )
                }

                internal fun createInternal(
                    fileUri: Uri?,
                    thumbnailUri: Uri?,
                    embeddedType: String,
                    contentType: ContentType?,
                    canDownload: Boolean,
                    forcedName: String,
                ): Embedded =
                    Embedded(
                        fileUri,
                        thumbnailUri,
                        embeddedType,
                        contentType,
                        canDownload,
                        forcedName,
                    )
            }
        }
    }

    class Icon internal constructor(
        @JvmField val uri: Uri?,
        @JvmField val title: String,
    ) {
        companion object {
            @JvmStatic
            fun createExternal(
                uri: Uri?,
                title: String?,
            ): Icon? = if (uri != null && !title.isNullOrEmpty()) Icon(uri, title) else null
        }
    }

    class Vote internal constructor(
        @JvmField val like: Int,
        @JvmField val dislike: Int,
    ) {
        @JvmField val showVotes: Boolean = true

        companion object {
            @JvmStatic
            fun createExternal(
                likes: Int,
                dislikes: Int,
            ): Vote = Vote(likes, dislikes)
        }
    }

    private object Flags {
        const val SAGE = 0x00000001
        const val STICKY = 0x00000002
        const val CLOSED = 0x00000004
        const val ARCHIVED = 0x00000008
        const val CYCLICAL = 0x00000010
        const val POSTER_WARNED = 0x00000020
        const val POSTER_BANNED = 0x00000040
        const val ORIGINAL_POSTER = 0x00000080
        const val DEFAULT_NAME = 0x00000100
        const val BUMP_LIMIT_REACHED = 0x00000200
        const val AI_GENERATED = 0x00000400
    }

    val isSage: Boolean
        get() = FlagUtils.get(flags, Flags.SAGE)

    val isSticky: Boolean
        get() = FlagUtils.get(flags, Flags.STICKY)

    val isClosed: Boolean
        get() = FlagUtils.get(flags, Flags.CLOSED)

    val isArchived: Boolean
        get() = FlagUtils.get(flags, Flags.ARCHIVED)

    val isCyclical: Boolean
        get() = FlagUtils.get(flags, Flags.CYCLICAL)

    val isPosterWarned: Boolean
        get() = FlagUtils.get(flags, Flags.POSTER_WARNED)

    val isPosterBanned: Boolean
        get() = FlagUtils.get(flags, Flags.POSTER_BANNED)

    val isOriginalPoster: Boolean
        get() = FlagUtils.get(flags, Flags.ORIGINAL_POSTER)

    val isDefaultName: Boolean
        get() = FlagUtils.get(flags, Flags.DEFAULT_NAME)

    val isBumpLimitReached: Boolean
        get() = FlagUtils.get(flags, Flags.BUMP_LIMIT_REACHED)

    val isAiGenerated: Boolean
        get() = FlagUtils.get(flags, Flags.AI_GENERATED)

    override fun compareTo(other: Post): Int = number.compareTo(other.number)

    @Throws(IOException::class)
    fun serialize(writer: JsonSerial.Writer) {
        writer.startObject()
        writer.name("flags")
        writer.value(flags)
        writer.name("timestamp")
        writer.value(timestamp)
        if (subject.isNotEmpty()) {
            writer.name("subject")
            writer.value(subject)
        }
        if (comment.isNotEmpty()) {
            writer.name("comment")
            writer.value(comment)
        }
        if (commentMarkup.isNotEmpty()) {
            writer.name("commentMarkup")
            writer.value(commentMarkup)
        }
        if (name.isNotEmpty()) {
            writer.name("name")
            writer.value(name)
        }
        if (identifier.isNotEmpty()) {
            writer.name("identifier")
            writer.value(identifier)
        }
        if (tripcode.isNotEmpty()) {
            writer.name("tripcode")
            writer.value(tripcode)
        }
        if (capcode.isNotEmpty()) {
            writer.name("capcode")
            writer.value(capcode)
        }
        if (email.isNotEmpty()) {
            writer.name("email")
            writer.value(email)
        }
        if (attachments.isNotEmpty()) {
            writer.name("attachments")
            writer.startArray()
            for (attachment in attachments) {
                if (attachment is Attachment.File) {
                    writer.startObject()
                    writer.name("type")
                    writer.value("file")
                    if (attachment.fileUri != null) {
                        writer.name("fileUri")
                        writer.value(attachment.fileUri.toString())
                    }
                    if (attachment.thumbnailUri != null) {
                        writer.name("thumbnailUri")
                        writer.value(attachment.thumbnailUri.toString())
                    }
                    if (attachment.originalName.isNotEmpty()) {
                        writer.name("originalName")
                        writer.value(attachment.originalName)
                    }
                    writer.name("size")
                    writer.value(attachment.size)
                    writer.name("width")
                    writer.value(attachment.width)
                    writer.name("height")
                    writer.value(attachment.height)
                    writer.name("spoiler")
                    writer.value(attachment.spoiler)
                    writer.endObject()
                } else if (attachment is Attachment.Embedded) {
                    writer.startObject()
                    writer.name("type")
                    writer.value("embedded")
                    if (attachment.fileUri != null) {
                        writer.name("fileUri")
                        writer.value(attachment.fileUri.toString())
                    }
                    if (attachment.thumbnailUri != null) {
                        writer.name("thumbnailUri")
                        writer.value(attachment.thumbnailUri.toString())
                    }
                    if (attachment.embeddedType.isNotEmpty()) {
                        writer.name("embeddedType")
                        writer.value(attachment.embeddedType)
                    }
                    if (attachment.contentType != null) {
                        writer.name("contentType")
                        writer.value(attachment.contentType.toString())
                    }
                    writer.name("canDownload")
                    writer.value(attachment.canDownload)
                    if (attachment.forcedName.isNotEmpty()) {
                        writer.name("forcedName")
                        writer.value(attachment.forcedName)
                    }
                    writer.endObject()
                }
            }
            writer.endArray()
        }
        if (icons.isNotEmpty()) {
            writer.name("icons")
            writer.startArray()
            for (icon in icons) {
                writer.startObject()
                if (icon.uri != null) {
                    writer.name("uri")
                    writer.value(icon.uri.toString())
                }
                if (icon.title.isNotEmpty()) {
                    writer.name("title")
                    writer.value(icon.title)
                }
                writer.endObject()
            }
            writer.endArray()
        }
        if (vote != null) {
            writer.name("vote")
            writer.startObject()
            writer.name("like")
            writer.value(vote.like)
            writer.name("dislike")
            writer.value(vote.dislike)
            writer.endObject()
        }
        if (extra.isNotEmpty()) {
            writer.name("extra")
            writer.value(extra)
        }
        writer.endObject()
    }

    class Builder {
        @JvmField var number: PostNumber? = null
        private var flags = 0

        @JvmField var timestamp = 0L

        @JvmField var subject: String? = null

        @JvmField var comment: String? = null

        @JvmField var commentMarkup: String? = null

        @JvmField var name: String? = null

        @JvmField var identifier: String? = null

        @JvmField var tripcode: String? = null

        @JvmField var capcode: String? = null

        @JvmField var email: String? = null

        @JvmField var attachments: MutableList<Attachment>? = null

        @JvmField var icons: MutableList<Icon>? = null

        @JvmField var vote: Vote? = null

        /** Opaque extension-owned payload. See [Post.extra]. */
        @JvmField var extra: String? = null

        var isSage: Boolean
            get() = FlagUtils.get(flags, Flags.SAGE)
            set(value) {
                flags = FlagUtils.set(flags, Flags.SAGE, value)
            }

        var isSticky: Boolean
            get() = FlagUtils.get(flags, Flags.STICKY)
            set(value) {
                flags = FlagUtils.set(flags, Flags.STICKY, value)
            }

        var isClosed: Boolean
            get() = FlagUtils.get(flags, Flags.CLOSED)
            set(value) {
                flags = FlagUtils.set(flags, Flags.CLOSED, value)
            }

        var isArchived: Boolean
            get() = FlagUtils.get(flags, Flags.ARCHIVED)
            set(value) {
                flags = FlagUtils.set(flags, Flags.ARCHIVED, value)
            }

        var isCyclical: Boolean
            get() = FlagUtils.get(flags, Flags.CYCLICAL)
            set(value) {
                flags = FlagUtils.set(flags, Flags.CYCLICAL, value)
            }

        var isPosterWarned: Boolean
            get() = FlagUtils.get(flags, Flags.POSTER_WARNED)
            set(value) {
                flags = FlagUtils.set(flags, Flags.POSTER_WARNED, value)
            }

        var isPosterBanned: Boolean
            get() = FlagUtils.get(flags, Flags.POSTER_BANNED)
            set(value) {
                flags = FlagUtils.set(flags, Flags.POSTER_BANNED, value)
            }

        var isOriginalPoster: Boolean
            get() = FlagUtils.get(flags, Flags.ORIGINAL_POSTER)
            set(value) {
                flags = FlagUtils.set(flags, Flags.ORIGINAL_POSTER, value)
            }

        var isDefaultName: Boolean
            get() = FlagUtils.get(flags, Flags.DEFAULT_NAME)
            set(value) {
                flags = FlagUtils.set(flags, Flags.DEFAULT_NAME, value)
            }

        var isBumpLimitReached: Boolean
            get() = FlagUtils.get(flags, Flags.BUMP_LIMIT_REACHED)
            set(value) {
                flags = FlagUtils.set(flags, Flags.BUMP_LIMIT_REACHED, value)
            }

        fun setAIGenerated(aiGenerated: Boolean) {
            flags = FlagUtils.set(flags, Flags.AI_GENERATED, aiGenerated)
        }

        fun build(deleted: Boolean): Post {
            val number = checkNotNull(this.number) { "Post number is null" }
            return Post(
                number,
                deleted,
                flags,
                timestamp,
                StringUtils.emptyIfNull(subject),
                StringUtils.emptyIfNull(comment),
                StringUtils.emptyIfNull(commentMarkup),
                StringUtils.emptyIfNull(name),
                StringUtils.emptyIfNull(identifier),
                StringUtils.emptyIfNull(tripcode),
                StringUtils.emptyIfNull(capcode),
                StringUtils.emptyIfNull(email),
                attachments.orEmpty(),
                icons.orEmpty(),
                vote,
                StringUtils.emptyIfNull(extra),
            )
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class, ParseException::class)
        fun deserialize(
            number: PostNumber,
            deleted: Boolean,
            reader: JsonSerial.Reader,
        ): Post {
            var flags = 0
            var timestamp = 0L
            var subject = ""
            var comment = ""
            var commentMarkup = ""
            var name = ""
            var identifier = ""
            var tripcode = ""
            var capcode = ""
            var email = ""
            var attachments: List<Attachment> = emptyList()
            var icons: List<Icon> = emptyList()
            var vote: Vote? = null
            var extra = ""
            reader.startObject()
            while (!reader.endStruct()) {
                when (reader.nextName()) {
                    "flags" -> {
                        flags = reader.nextInt()
                    }

                    "timestamp" -> {
                        timestamp = reader.nextLong()
                    }

                    "subject" -> {
                        subject = reader.nextString()!!
                    }

                    "comment" -> {
                        comment = reader.nextString()!!
                    }

                    "commentMarkup" -> {
                        commentMarkup = reader.nextString()!!
                    }

                    "name" -> {
                        name = reader.nextString()!!
                    }

                    "identifier" -> {
                        identifier = reader.nextString()!!
                    }

                    "tripcode" -> {
                        tripcode = reader.nextString()!!
                    }

                    "capcode" -> {
                        capcode = reader.nextString()!!
                    }

                    "email" -> {
                        email = reader.nextString()!!
                    }

                    "attachments" -> {
                        reader.startArray()
                        val list = ArrayList<Attachment>()
                        attachments = list
                        while (!reader.endStruct()) {
                            reader.startObject()
                            var type = ""
                            var fileUri: Uri? = null
                            var thumbnailUri: Uri? = null
                            var originalName = ""
                            var size = 0
                            var width = 0
                            var height = 0
                            var spoiler = false
                            var embeddedType = ""
                            var contentType: Attachment.Embedded.ContentType? = null
                            var canDownload = false
                            var forcedName = ""
                            while (!reader.endStruct()) {
                                when (reader.nextName()) {
                                    "type" -> {
                                        type = reader.nextString()!!
                                    }

                                    "fileUri" -> {
                                        fileUri = Uri.parse(reader.nextString())
                                    }

                                    "thumbnailUri" -> {
                                        thumbnailUri = Uri.parse(reader.nextString())
                                    }

                                    "originalName" -> {
                                        originalName = reader.nextString()!!
                                    }

                                    "size" -> {
                                        size = reader.nextInt()
                                    }

                                    "width" -> {
                                        width = reader.nextInt()
                                    }

                                    "height" -> {
                                        height = reader.nextInt()
                                    }

                                    "spoiler" -> {
                                        spoiler = reader.nextBoolean()
                                    }

                                    "embeddedType" -> {
                                        embeddedType = reader.nextString()!!
                                    }

                                    "contentType" -> {
                                        contentType =
                                            try {
                                                Attachment.Embedded.ContentType.valueOf(reader.nextString()!!)
                                            } catch (e: IllegalArgumentException) {
                                                null
                                            }
                                    }

                                    "canDownload" -> {
                                        canDownload = reader.nextBoolean()
                                    }

                                    "forcedName" -> {
                                        forcedName = reader.nextString()!!
                                    }

                                    else -> {
                                        reader.skip()
                                    }
                                }
                            }
                            if ("file" == type) {
                                list.add(
                                    Attachment.File.createInternal(
                                        fileUri,
                                        thumbnailUri,
                                        originalName,
                                        size,
                                        width,
                                        height,
                                        spoiler,
                                    ),
                                )
                            } else if ("embedded" == type &&
                                Attachment.Embedded
                                    .validate(false, fileUri, embeddedType, contentType)
                            ) {
                                list.add(
                                    Attachment.Embedded.createInternal(
                                        fileUri,
                                        thumbnailUri,
                                        embeddedType,
                                        contentType,
                                        canDownload,
                                        forcedName,
                                    ),
                                )
                            }
                        }
                    }

                    "icons" -> {
                        reader.startArray()
                        val list = ArrayList<Icon>()
                        icons = list
                        while (!reader.endStruct()) {
                            reader.startObject()
                            var uri: Uri? = null
                            var title = ""
                            while (!reader.endStruct()) {
                                when (reader.nextName()) {
                                    "uri" -> uri = Uri.parse(reader.nextString())
                                    "title" -> title = reader.nextString()!!
                                    else -> reader.skip()
                                }
                            }
                            list.add(Icon(uri, title))
                        }
                    }

                    "vote" -> {
                        var like = 0
                        var dislike = 0
                        reader.startObject()
                        while (!reader.endStruct()) {
                            when (reader.nextName()) {
                                "like" -> like = reader.nextInt()
                                "dislike" -> dislike = reader.nextInt()
                                else -> reader.skip()
                            }
                        }
                        vote = Vote(like, dislike)
                    }

                    "extra" -> {
                        extra = reader.nextString()!!
                    }

                    else -> {
                        reader.skip()
                    }
                }
            }
            return Post(
                number,
                deleted,
                flags,
                timestamp,
                subject,
                comment,
                commentMarkup,
                name,
                identifier,
                tripcode,
                capcode,
                email,
                attachments,
                icons,
                vote,
                extra,
            )
        }
    }
}
