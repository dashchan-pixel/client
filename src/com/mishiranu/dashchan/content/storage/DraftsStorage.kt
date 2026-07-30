package com.mishiranu.dashchan.content.storage

import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import androidx.core.os.ParcelCompat
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.text.JsonSerial
import chan.text.ParseException
import chan.util.StringUtils
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.async.ReadCaptchaTask
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.ui.CaptchaForm
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.Hasher
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DraftsStorage private constructor() : StorageManager.Storage<DraftsStorage.Data>("drafts", 2000, 10000) {
    private val postDrafts = LruCache<String, PostDraft>(5) { _, v -> handleRemovePostDraft(v) }

    private var captchaChanName: String? = null
    private var captchaDraft: CaptchaDraft? = null
    private val futureAttachmentDrafts = ArrayList<AttachmentDraft>()
    private var futureComment: String? = null

    class Data(
        val postDrafts: List<PostDraft>,
        val attachmentDrafts: List<AttachmentDraft>,
        val futureComment: String?,
    )

    init {
        startRead()
        val directory = getAttachmentDraftsDirectory()
        if (directory != null) {
            val files = directory.listFiles()
            if (files != null && files.isNotEmpty()) {
                val hashes = collectAttachmentDraftHashes()
                for (file in files) {
                    if (!hashes.contains(file.name)) {
                        file.delete()
                    }
                }
            }
        }
    }

    override fun onClone(): Data = Data(ArrayList(postDrafts.values), ArrayList(futureAttachmentDrafts), futureComment)

    @Throws(IOException::class)
    override fun onRead(input: InputStream) {
        try {
            val reader = JsonSerial.reader(input)
            reader.startObject()
            while (!reader.endStruct()) {
                when (reader.nextName()) {
                    KEY_POST_DRAFTS -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            val postDraft = PostDraft.deserialize(reader)
                            postDrafts[makeKey(postDraft)] = postDraft
                        }
                    }

                    KEY_FUTURE_ATTACHMENT_DRAFTS -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            val attachmentDraft = AttachmentDraft.deserialize(reader)
                            if (attachmentDraft != null) {
                                futureAttachmentDrafts.add(attachmentDraft)
                            }
                        }
                    }

                    KEY_FUTURE_COMMENT -> {
                        futureComment = StringUtils.nullIfEmpty(reader.nextString())
                    }

                    else -> {
                        reader.skip()
                    }
                }
            }
        } catch (e: ParseException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override fun onWrite(
        data: Data,
        output: OutputStream,
    ) {
        val writer = JsonSerial.writer(output)
        writer.startObject()
        if (data.postDrafts.isNotEmpty()) {
            writer.name(KEY_POST_DRAFTS)
            writer.startArray()
            for (postDraft in data.postDrafts) {
                postDraft.serialize(writer)
            }
            writer.endArray()
        }
        if (data.attachmentDrafts.isNotEmpty()) {
            writer.name(KEY_FUTURE_ATTACHMENT_DRAFTS)
            writer.startArray()
            for (attachmentDraft in data.attachmentDrafts) {
                attachmentDraft.serialize(writer)
            }
            writer.endArray()
        }
        data.futureComment?.let {
            writer.name(KEY_FUTURE_COMMENT)
            writer.value(it)
        }
        writer.endObject()
        writer.flush()
    }

    fun store(postDraft: PostDraft?) {
        if (postDraft != null) {
            var serialize = true
            if (postDraft.isEmpty) {
                serialize = postDrafts.remove(makeKey(postDraft)) != null
            } else {
                postDrafts[makeKey(postDraft)] = postDraft
            }
            if (serialize) {
                serialize()
            }
        }
    }

    fun getPostDraft(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ): PostDraft? = postDrafts[makeKey(chanName, boardName, threadNumber)]

    fun removePostDraft(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ) {
        val postDraft = postDrafts.remove(makeKey(chanName, boardName, threadNumber))
        if (postDraft != null) {
            serialize()
        }
    }

    fun store(
        chanName: String?,
        captchaDraft: CaptchaDraft?,
    ) {
        this.captchaChanName = chanName
        this.captchaDraft = captchaDraft
    }

    fun getCaptchaDraft(chanName: String?): CaptchaDraft? {
        val captchaDraft = captchaDraft
        if (captchaDraft != null && captchaChanName == chanName) {
            val captcha = captchaDraft.captcha
            if (captcha != null && !captcha.hasLifetime()) {
                val now = SystemClock.elapsedRealtime()
                val minutesSinceCaptchaCreation =
                    TimeUnit.MILLISECONDS
                        .toMinutes(now - captcha.creationTimeMillis)
                val maximumCaptchaDraftLifetimeMinutes = 5L
                if (minutesSinceCaptchaCreation > maximumCaptchaDraftLifetimeMinutes) {
                    return null
                }
            }
            return captchaDraft
        }
        return null
    }

    fun removeCaptchaDraft() {
        captchaDraft = null
        captchaChanName = null
    }

    fun getAttachmentDraftFileHolder(hash: String?): FileHolder? {
        val file = getAttachmentDraftStoredFile(hash)
        return if (file != null) FileHolder.obtain(file) else null
    }

    /**
     * The file an attachment draft is stored in, or null when it is gone. The file is named after the
     * content hash alone, with no extension, so anything that goes by the file name — the media type
     * of a preview, of the player to open it in — has to take it from the draft's own name instead.
     */
    fun getAttachmentDraftStoredFile(hash: String?): File? {
        val file = getAttachmentDraftFile(hash)
        return if (file != null && file.isFile) file else null
    }

    fun store(fileHolder: FileHolder): String? {
        val hash =
            try {
                fileHolder.openInputStream().use { input ->
                    StringUtils.formatHex(Hasher.getInstanceSha256().calculate(input))
                }
            } catch (e: IOException) {
                return null
            }
        val file = getAttachmentDraftFile(hash) ?: return null
        if (file.isFile) {
            return hash
        }
        return try {
            fileHolder.openInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    IOUtils.copyStream(input, output)
                }
            }
            serialize()
            hash
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    private fun collectAttachmentDraftHashes(): HashSet<String> {
        val hashes = HashSet<String>()
        for (postDraft in postDrafts.values) {
            postDraft.attachmentDrafts?.forEach { hashes.add(it.hash) }
        }
        for (attachmentDraft in futureAttachmentDrafts) {
            hashes.add(attachmentDraft.hash)
        }
        return hashes
    }

    fun storeFuture(fileHolder: FileHolder): Boolean {
        val hash = store(fileHolder)
        if (hash != null) {
            val attachmentDraft =
                AttachmentDraft(
                    hash,
                    fileHolder.name,
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    null,
                    false,
                )
            futureAttachmentDrafts.add(attachmentDraft)
            serialize()
            return true
        }
        return false
    }

    fun getFutureAttachmentDrafts(): ArrayList<AttachmentDraft> = futureAttachmentDrafts

    /**
     * Stashes text shared from another app until the next posting form is opened. Repeated shares
     * accumulate, the way repeated image shares add up as future attachment drafts.
     */
    fun storeFutureComment(comment: String) {
        val text = comment.trim { it <= ' ' }
        if (text.isEmpty()) {
            return
        }
        val futureComment = this.futureComment
        this.futureComment = if (futureComment.isNullOrEmpty()) text else futureComment + "\n\n" + text
        serialize()
    }

    fun getFutureComment(): String? = futureComment

    fun consumeFutureComment() {
        if (!futureComment.isNullOrEmpty()) {
            futureComment = null
            serialize()
        }
    }

    fun consumeFutureAttachmentDrafts() {
        if (futureAttachmentDrafts.isNotEmpty()) {
            val attachmentDrafts = ArrayList(futureAttachmentDrafts)
            futureAttachmentDrafts.clear()
            handleRemoveAttachmentDrafts(attachmentDrafts)
            serialize()
        }
    }

    private fun handleRemovePostDraft(postDraft: PostDraft) {
        postDraft.attachmentDrafts?.let { handleRemoveAttachmentDrafts(it) }
    }

    private fun handleRemoveAttachmentDrafts(attachmentDrafts: ArrayList<AttachmentDraft>) {
        val hashes = collectAttachmentDraftHashes()
        for (attachmentDraft in attachmentDrafts) {
            if (!hashes.contains(attachmentDraft.hash)) {
                getAttachmentDraftFile(attachmentDraft.hash)?.delete()
            }
        }
    }

    class PostDraft(
        @JvmField val chanName: String?,
        @JvmField val boardName: String?,
        @JvmField val threadNumber: String?,
        @JvmField val name: String?,
        @JvmField val email: String?,
        @JvmField val password: String?,
        @JvmField val subject: String?,
        @JvmField val comment: String?,
        @JvmField val commentCarriage: Int,
        @JvmField val attachmentDrafts: ArrayList<AttachmentDraft>?,
        @JvmField val optionSage: Boolean,
        @JvmField val optionSpoiler: Boolean,
        @JvmField val optionOriginalPoster: Boolean,
        @JvmField val userIcon: String?,
    ) {
        constructor(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            name: String?,
            email: String?,
            password: String?,
            optionSage: Boolean,
            optionOriginalPoster: Boolean,
            userIcon: String?,
        ) :
            this(
                chanName,
                boardName,
                threadNumber,
                name,
                email,
                password,
                null,
                null,
                0,
                null,
                optionSage,
                false,
                optionOriginalPoster,
                userIcon,
            )

        val isEmpty: Boolean
            get() =
                StringUtils.isEmpty(name) &&
                    StringUtils.isEmpty(email) &&
                    StringUtils.isEmpty(password) &&
                    StringUtils.isEmpty(subject) &&
                    StringUtils.isEmpty(comment) &&
                    attachmentDrafts.isNullOrEmpty() &&
                    !optionSage &&
                    !optionSpoiler &&
                    !optionOriginalPoster &&
                    StringUtils.isEmpty(userIcon)

        @Throws(IOException::class)
        fun serialize(writer: JsonSerial.Writer) {
            writer.startObject()
            if (!chanName.isNullOrEmpty()) {
                writer.name(KEY_CHAN_NAME)
                writer.value(chanName)
            }
            if (!boardName.isNullOrEmpty()) {
                writer.name(KEY_BOARD_NAME)
                writer.value(boardName)
            }
            if (!threadNumber.isNullOrEmpty()) {
                writer.name(KEY_THREAD_NUMBER)
                writer.value(threadNumber)
            }
            if (!name.isNullOrEmpty()) {
                writer.name(KEY_NAME)
                writer.value(name)
            }
            if (!email.isNullOrEmpty()) {
                writer.name(KEY_EMAIL)
                writer.value(email)
            }
            if (!password.isNullOrEmpty()) {
                writer.name(KEY_PASSWORD)
                writer.value(password)
            }
            if (!subject.isNullOrEmpty()) {
                writer.name(KEY_SUBJECT)
                writer.value(subject)
            }
            if (!comment.isNullOrEmpty()) {
                writer.name(KEY_COMMENT)
                writer.value(comment)
            }
            writer.name(KEY_COMMENT_CARRIAGE)
            writer.value(commentCarriage)
            if (!attachmentDrafts.isNullOrEmpty()) {
                writer.name(KEY_ATTACHMENT_DRAFTS)
                writer.startArray()
                for (attachmentDraft in attachmentDrafts) {
                    attachmentDraft.serialize(writer)
                }
                writer.endArray()
            }
            writer.name(KEY_OPTION_SAGE)
            writer.value(optionSage)
            writer.name(KEY_OPTION_SPOILER)
            writer.value(optionSpoiler)
            writer.name(KEY_OPTION_ORIGINAL_POSTER)
            writer.value(optionOriginalPoster)
            if (!userIcon.isNullOrEmpty()) {
                writer.name(KEY_USER_ICON)
                writer.value(userIcon)
            }
            writer.endObject()
        }

        companion object {
            private const val KEY_CHAN_NAME = "chanName"
            private const val KEY_BOARD_NAME = "boardName"
            private const val KEY_THREAD_NUMBER = "threadNumber"

            private const val KEY_NAME = "name"
            private const val KEY_EMAIL = "email"
            private const val KEY_PASSWORD = "password"
            private const val KEY_SUBJECT = "subject"
            private const val KEY_COMMENT = "comment"
            private const val KEY_COMMENT_CARRIAGE = "commentCarriage"
            private const val KEY_ATTACHMENT_DRAFTS = "attachmentDrafts"
            private const val KEY_OPTION_SAGE = "optionSage"
            private const val KEY_OPTION_SPOILER = "optionSpoiler"
            private const val KEY_OPTION_ORIGINAL_POSTER = "optionOriginalPoster"
            private const val KEY_USER_ICON = "userIcon"

            @Throws(IOException::class, ParseException::class)
            fun deserialize(reader: JsonSerial.Reader): PostDraft {
                var chanName: String? = null
                var boardName: String? = null
                var threadNumber: String? = null
                var name: String? = null
                var email: String? = null
                var password: String? = null
                var subject: String? = null
                var comment: String? = null
                var commentCarriage = 0
                var attachmentDrafts: ArrayList<AttachmentDraft>? = null
                var optionSage = false
                var optionSpoiler = false
                var optionOriginalPoster = false
                var userIcon: String? = null
                reader.startObject()
                while (!reader.endStruct()) {
                    when (reader.nextName()) {
                        KEY_CHAN_NAME -> {
                            chanName = reader.nextString()
                        }

                        KEY_BOARD_NAME -> {
                            boardName = reader.nextString()
                        }

                        KEY_THREAD_NUMBER -> {
                            threadNumber = reader.nextString()
                        }

                        KEY_NAME -> {
                            name = reader.nextString()
                        }

                        KEY_EMAIL -> {
                            email = reader.nextString()
                        }

                        KEY_PASSWORD -> {
                            password = reader.nextString()
                        }

                        KEY_SUBJECT -> {
                            subject = reader.nextString()
                        }

                        KEY_COMMENT -> {
                            comment = reader.nextString()
                        }

                        KEY_COMMENT_CARRIAGE -> {
                            commentCarriage = reader.nextInt()
                        }

                        KEY_ATTACHMENT_DRAFTS -> {
                            reader.startArray()
                            while (!reader.endStruct()) {
                                val attachmentDraft = AttachmentDraft.deserialize(reader)
                                if (attachmentDraft != null) {
                                    if (attachmentDrafts == null) {
                                        attachmentDrafts = ArrayList()
                                    }
                                    attachmentDrafts.add(attachmentDraft)
                                }
                            }
                        }

                        KEY_OPTION_SAGE -> {
                            optionSage = reader.nextBoolean()
                        }

                        KEY_OPTION_SPOILER -> {
                            optionSpoiler = reader.nextBoolean()
                        }

                        KEY_OPTION_ORIGINAL_POSTER -> {
                            optionOriginalPoster = reader.nextBoolean()
                        }

                        KEY_USER_ICON -> {
                            userIcon = reader.nextString()
                        }

                        else -> {
                            reader.skip()
                        }
                    }
                }
                return PostDraft(
                    chanName,
                    boardName,
                    threadNumber,
                    name,
                    email,
                    password,
                    subject,
                    comment,
                    commentCarriage,
                    attachmentDrafts,
                    optionSage,
                    optionSpoiler,
                    optionOriginalPoster,
                    userIcon,
                )
            }
        }
    }

    class CaptchaDraft(
        @JvmField val captchaType: String?,
        @JvmField val captchaState: ReadCaptchaTask.CaptchaState?,
        @JvmField val captchaData: ChanPerformer.CaptchaData?,
        @JvmField val loadedCaptchaType: String?,
        @JvmField val loadedInput: ChanConfiguration.Captcha.Input?,
        @JvmField val loadedValidity: ChanConfiguration.Captcha.Validity?,
        @JvmField val text: String?,
        @JvmField val captcha: CaptchaForm.Captcha?,
        @JvmField val large: Boolean,
        @JvmField val blackAndWhite: Boolean,
        @JvmField val boardName: String?,
        @JvmField val threadNumber: String?,
    ) : Parcelable {
        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeString(captchaType)
            dest.writeString(captchaState?.name)
            dest.writeByte(if (captchaData != null) 1 else 0)
            captchaData?.writeToParcel(dest, flags)
            dest.writeString(loadedCaptchaType)
            dest.writeString(loadedInput?.name)
            dest.writeString(loadedValidity?.name)
            dest.writeString(text)
            dest.writeParcelable(captcha, flags)
            dest.writeInt(if (large) 1 else 0)
            dest.writeInt(if (blackAndWhite) 1 else 0)
            dest.writeString(boardName)
            dest.writeString(threadNumber)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<CaptchaDraft> =
                object : Parcelable.Creator<CaptchaDraft> {
                    override fun createFromParcel(source: Parcel): CaptchaDraft {
                        val captchaType = source.readString()
                        val captchaState =
                            source
                                .readString()
                                ?.let { ReadCaptchaTask.CaptchaState.valueOf(it) }
                        val captchaData =
                            if (source.readByte().toInt() != 0) {
                                ChanPerformer.CaptchaData.CREATOR.createFromParcel(source)
                            } else {
                                null
                            }
                        val loadedCaptchaType = source.readString()
                        val loadedInput =
                            source
                                .readString()
                                ?.let { ChanConfiguration.Captcha.Input.valueOf(it) }
                        val loadedValidity =
                            source
                                .readString()
                                ?.let { ChanConfiguration.Captcha.Validity.valueOf(it) }
                        val text = source.readString()
                        val captcha =
                            ParcelCompat.readParcelable(
                                source,
                                CaptchaForm.Captcha::class.java.classLoader,
                                CaptchaForm.Captcha::class.java,
                            )
                        val large = source.readInt() != 0
                        val blackAndWhite = source.readInt() != 0
                        val boardName = source.readString()
                        val threadNumber = source.readString()
                        return CaptchaDraft(
                            captchaType,
                            captchaState,
                            captchaData,
                            loadedCaptchaType,
                            loadedInput,
                            loadedValidity,
                            text,
                            captcha,
                            large,
                            blackAndWhite,
                            boardName,
                            threadNumber,
                        )
                    }

                    override fun newArray(size: Int): Array<CaptchaDraft?> = arrayOfNulls(size)
                }
        }
    }

    class AttachmentDraft(
        @JvmField val hash: String,
        @JvmField val name: String?,
        @JvmField val newname: String?,
        @JvmField val rating: String?,
        @JvmField val optionUniqueHash: Boolean,
        @JvmField val optionRemoveMetadata: Boolean,
        @JvmField val optionRemoveFileName: Boolean,
        @JvmField val optionSpoiler: Boolean,
        @JvmField val reencoding: GraphicsUtils.Reencoding?,
        @JvmField val optionCustomName: Boolean,
    ) {
        @Throws(IOException::class)
        fun serialize(writer: JsonSerial.Writer) {
            writer.startObject()
            if (!StringUtils.isEmpty(hash)) {
                writer.name(KEY_HASH)
                writer.value(hash)
            }
            if (!name.isNullOrEmpty()) {
                writer.name(KEY_NAME)
                writer.value(name)
            }
            if (!newname.isNullOrEmpty()) {
                writer.name(KEY_NEWNAME)
                writer.value(newname)
            }
            if (!rating.isNullOrEmpty()) {
                writer.name(KEY_RATING)
                writer.value(rating)
            }
            writer.name(KEY_OPTION_UNIQUE_HASH)
            writer.value(optionUniqueHash)
            writer.name(KEY_OPTION_REMOVE_METADATA)
            writer.value(optionRemoveMetadata)
            writer.name(KEY_OPTION_REMOVE_FILE_NAME)
            writer.value(optionRemoveFileName)
            writer.name(KEY_OPTION_SPOILER)
            writer.value(optionSpoiler)
            writer.name(KEY_OPTION_NEW_FILENAME)
            writer.value(optionCustomName)
            if (reencoding != null) {
                writer.name(KEY_REENCODING)
                writer.startObject()
                if (!StringUtils.isEmpty(reencoding.format)) {
                    writer.name(KEY_REENCODING_FORMAT)
                    writer.value(reencoding.format)
                }
                writer.name(KEY_REENCODING_QUALITY)
                writer.value(reencoding.quality)
                writer.name(KEY_REENCODING_REDUCE)
                writer.value(reencoding.reduce)
                writer.endObject()
            }
            writer.endObject()
        }

        companion object {
            private const val KEY_HASH = "hash"
            private const val KEY_NAME = "name"
            private const val KEY_NEWNAME = "newname"
            private const val KEY_RATING = "rating"
            private const val KEY_OPTION_UNIQUE_HASH = "optionUniqueHash"
            private const val KEY_OPTION_REMOVE_METADATA = "optionRemoveMetadata"
            private const val KEY_OPTION_REMOVE_FILE_NAME = "optionRemoveFileName"
            private const val KEY_OPTION_SPOILER = "optionSpoiler"
            private const val KEY_REENCODING = "reencoding"
            private const val KEY_OPTION_NEW_FILENAME = "optionCustomName"

            private const val KEY_REENCODING_FORMAT = "format"
            private const val KEY_REENCODING_QUALITY = "quality"
            private const val KEY_REENCODING_REDUCE = "reduce"

            @Throws(IOException::class, ParseException::class)
            fun deserialize(reader: JsonSerial.Reader): AttachmentDraft? {
                var hash: String? = null
                var name: String? = null
                var newname: String? = null
                var rating: String? = null
                var optionUniqueHash = false
                var optionRemoveMetadata = false
                var optionRemoveFileName = false
                var optionSpoiler = false
                var reencoding: GraphicsUtils.Reencoding? = null
                var optionCustomName = false
                reader.startObject()
                while (!reader.endStruct()) {
                    when (reader.nextName()) {
                        KEY_HASH -> {
                            hash = reader.nextString()
                        }

                        KEY_NAME -> {
                            name = reader.nextString()
                        }

                        KEY_NEWNAME -> {
                            newname = reader.nextString()
                        }

                        KEY_RATING -> {
                            rating = reader.nextString()
                        }

                        KEY_OPTION_UNIQUE_HASH -> {
                            optionUniqueHash = reader.nextBoolean()
                        }

                        KEY_OPTION_REMOVE_METADATA -> {
                            optionRemoveMetadata = reader.nextBoolean()
                        }

                        KEY_OPTION_REMOVE_FILE_NAME -> {
                            optionRemoveFileName = reader.nextBoolean()
                        }

                        KEY_OPTION_SPOILER -> {
                            optionSpoiler = reader.nextBoolean()
                        }

                        KEY_OPTION_NEW_FILENAME -> {
                            optionCustomName = reader.nextBoolean()
                        }

                        KEY_REENCODING -> {
                            var format: String? = null
                            var quality = 0
                            var reduce = 0
                            reader.startObject()
                            while (!reader.endStruct()) {
                                when (reader.nextName()) {
                                    KEY_REENCODING_FORMAT -> format = reader.nextString()
                                    KEY_REENCODING_QUALITY -> quality = reader.nextInt()
                                    KEY_REENCODING_REDUCE -> reduce = reader.nextInt()
                                    else -> reader.skip()
                                }
                            }
                            reencoding = GraphicsUtils.Reencoding(format, quality, reduce)
                        }

                        else -> {
                            reader.skip()
                        }
                    }
                }
                if (StringUtils.isEmpty(hash)) {
                    return null
                }
                return AttachmentDraft(
                    hash!!,
                    name,
                    newname,
                    rating,
                    optionUniqueHash,
                    optionRemoveMetadata,
                    optionRemoveFileName,
                    optionSpoiler,
                    reencoding,
                    optionCustomName,
                )
            }
        }
    }

    companion object {
        private const val KEY_POST_DRAFTS = "postDrafts"
        private const val KEY_FUTURE_ATTACHMENT_DRAFTS = "futureAttachmentDrafts"
        private const val KEY_FUTURE_COMMENT = "futureComment"

        private val INSTANCE = DraftsStorage()

        @JvmStatic
        fun getInstance(): DraftsStorage = INSTANCE

        private fun makeKey(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
        ): String = "$chanName/$boardName/$threadNumber"

        private fun makeKey(postDraft: PostDraft): String = makeKey(postDraft.chanName, postDraft.boardName, postDraft.threadNumber)

        private fun getAttachmentDraftsDirectory(): File? {
            val directory = CacheManager.getInstance().getCacheDirectory() ?: return null
            val attachments = File(directory, "attachments")
            return if (attachments.isDirectory || attachments.mkdirs()) attachments else null
        }

        private fun getAttachmentDraftFile(hash: String?): File? {
            val directory = getAttachmentDraftsDirectory()
            return if (directory != null) File(directory, hash!!) else null
        }
    }
}
