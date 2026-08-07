package chan.content

import android.os.Parcel
import android.os.Parcelable
import chan.annotation.Public
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.util.FlagUtils.get

@Public
class ApiException : Exception {
    val errorType: Int
    private val flags: Int
    internal val extra: Any?

    @Public
    constructor(errorType: Int) : this(errorType, 0, null)

    @Public
    constructor(errorType: Int, flags: Int) : this(errorType, flags, null)

    @Public
    constructor(errorType: Int, extra: Any?) : this(errorType, 0, extra)

    @Public
    constructor(errorType: Int, flags: Int, extra: Any?) {
        this.errorType = errorType
        this.flags = flags
        this.extra = extra
    }

    @Public
    constructor(detailMessage: String?) : this(detailMessage, 0)

    @Public
    constructor(detailMessage: String?, flags: Int) : super(detailMessage) {
        this.errorType = 0
        this.flags = flags
        this.extra = null
    }

    fun checkFlag(flag: Int): Boolean = get(flags, flag)

    fun getExtra(): Extra? {
        when (errorType) {
            SEND_ERROR_BANNED -> {
                if (extra is BanExtra) {
                    return extra
                }
            }

            SEND_ERROR_SPAM_LIST -> {
                if (extra is WordsExtra) {
                    return extra
                }
            }
        }
        return null
    }

    val errorItem: ErrorItem
        get() {
            val message = message
            if (!isEmpty(message)) {
                return ErrorItem(message)
            }
            return ErrorItem(ErrorItem.Type.API, errorType)
        }

    interface Extra : Parcelable

    @Public
    class BanExtra
        @Public
        constructor() : Extra {
            var id: String? = null
            var message: String? = null
            var startDate: Long = 0
            var expireDate: Long = 0

            @Public
            fun setId(id: String?): BanExtra {
                this.id = id
                return this
            }

            @Public
            fun setMessage(message: String?): BanExtra {
                this.message = message
                return this
            }

            @Public
            fun setStartDate(startDate: Long): BanExtra {
                this.startDate = startDate
                return this
            }

            @Public
            fun setExpireDate(expireDate: Long): BanExtra {
                this.expireDate = expireDate
                return this
            }

            override fun describeContents(): Int = 0

            override fun writeToParcel(
                dest: Parcel,
                flags: Int,
            ) {
                dest.writeString(id)
                dest.writeString(message)
                dest.writeLong(startDate)
                dest.writeLong(expireDate)
            }

            companion object {
                @JvmField
                val CREATOR: Parcelable.Creator<BanExtra?> =
                    object : Parcelable.Creator<BanExtra?> {
                        override fun createFromParcel(`in`: Parcel): BanExtra {
                            val id = `in`.readString()
                            val message = `in`.readString()
                            val startDate = `in`.readLong()
                            val expireDate = `in`.readLong()
                            return BanExtra()
                                .setId(id)
                                .setMessage(message)
                                .setStartDate(startDate)
                                .setExpireDate(expireDate)
                        }

                        override fun newArray(size: Int): Array<BanExtra?> = arrayOfNulls<BanExtra>(size)
                    }
            }
        }

    @Public
    class WordsExtra
        @Public
        constructor() : Extra {
            val words: LinkedHashSet<String?> = LinkedHashSet<String?>()

            @Public
            fun addWord(word: String?): WordsExtra {
                words.add(word)
                return this
            }

            override fun describeContents(): Int = 0

            override fun writeToParcel(
                dest: Parcel,
                flags: Int,
            ) {
                dest.writeStringList(ArrayList<String?>(words))
            }

            companion object {
                @JvmField
                val CREATOR: Parcelable.Creator<WordsExtra?> =
                    object : Parcelable.Creator<WordsExtra?> {
                        override fun createFromParcel(`in`: Parcel): WordsExtra {
                            val words = `in`.createStringArrayList()
                            val wordsExtra = WordsExtra()
                            for (word in words!!) {
                                wordsExtra.addWord(word)
                            }
                            return wordsExtra
                        }

                        override fun newArray(size: Int): Array<WordsExtra?> = arrayOfNulls<WordsExtra>(size)
                    }
            }
        }

    companion object {
        @Public
        const val SEND_ERROR_NO_BOARD: Int = 100

        @Public
        const val SEND_ERROR_NO_THREAD: Int = 101

        @Public
        const val SEND_ERROR_NO_ACCESS: Int = 102

        @Public
        const val SEND_ERROR_CAPTCHA: Int = 103

        @Public
        const val SEND_ERROR_BANNED: Int = 104

        @Public
        const val SEND_ERROR_CLOSED: Int = 105

        @Public
        const val SEND_ERROR_TOO_FAST: Int = 106

        @Public
        const val SEND_ERROR_FIELD_TOO_LONG: Int = 107

        @Public
        const val SEND_ERROR_FILE_EXISTS: Int = 108

        @Public
        const val SEND_ERROR_FILE_NOT_SUPPORTED: Int = 109

        @Public
        const val SEND_ERROR_FILE_TOO_BIG: Int = 110

        @Public
        const val SEND_ERROR_FILES_TOO_MANY: Int = 111

        @Public
        const val SEND_ERROR_SPAM_LIST: Int = 112

        @Public
        const val SEND_ERROR_EMPTY_FILE: Int = 113

        @Public
        const val SEND_ERROR_EMPTY_SUBJECT: Int = 114

        @Public
        const val SEND_ERROR_EMPTY_COMMENT: Int = 115

        @Public
        const val SEND_ERROR_FILES_LIMIT: Int = 116

        @Public
        const val DELETE_ERROR_NO_ACCESS: Int = 200

        @Public
        const val DELETE_ERROR_PASSWORD: Int = 201

        @Public
        const val DELETE_ERROR_NOT_FOUND: Int = 202

        @Public
        const val DELETE_ERROR_TOO_NEW: Int = 203

        @Public
        const val DELETE_ERROR_TOO_OLD: Int = 204

        @Public
        const val DELETE_ERROR_TOO_OFTEN: Int = 205

        @Public
        const val REPORT_ERROR_NO_ACCESS: Int = 300

        @Public
        const val REPORT_ERROR_TOO_OFTEN: Int = 301

        @Public
        const val REPORT_ERROR_EMPTY_COMMENT: Int = 302

        @Public
        const val ARCHIVE_ERROR_NO_ACCESS: Int = 400

        @Public
        const val ARCHIVE_ERROR_TOO_OFTEN: Int = 401

        @Public
        const val VOTE_ERROR_POSTING_PROHIBITED: Int = 500

        @Public
        const val FLAG_KEEP_CAPTCHA: Int = 0x00000001

        fun getResId(errorType: Int): Int {
            var resId = 0
            when (errorType) {
                SEND_ERROR_NO_BOARD -> {
                    resId = R.string.board_doesnt_exist
                }

                SEND_ERROR_NO_THREAD -> {
                    resId = R.string.thread_doesnt_exist
                }

                SEND_ERROR_NO_ACCESS -> {
                    resId = R.string.no_access
                }

                SEND_ERROR_CAPTCHA -> {
                    resId = R.string.captcha_is_not_valid
                }

                SEND_ERROR_BANNED -> {
                    resId = R.string.your_ip_banned
                }

                SEND_ERROR_CLOSED -> {
                    resId = R.string.thread_is_closed
                }

                SEND_ERROR_TOO_FAST -> {
                    resId = R.string.you_cant_send_too_often
                }

                SEND_ERROR_FIELD_TOO_LONG -> {
                    resId = R.string.fields_limit_exceeded
                }

                SEND_ERROR_FILE_EXISTS -> {
                    resId = R.string.repeated_files_are_prohibited
                }

                SEND_ERROR_FILE_NOT_SUPPORTED -> {
                    resId = R.string.file_format_is_not_supported
                }

                SEND_ERROR_FILE_TOO_BIG -> {
                    resId = R.string.attachments_are_too_large
                }

                SEND_ERROR_FILES_TOO_MANY -> {
                    resId = R.string.too_many_attachments
                }

                SEND_ERROR_SPAM_LIST -> {
                    resId = R.string.post_rejected
                }

                SEND_ERROR_EMPTY_FILE -> {
                    resId = R.string.you_need_to_attach_a_file
                }

                SEND_ERROR_EMPTY_SUBJECT -> {
                    resId = R.string.subject_is_too_short
                }

                SEND_ERROR_EMPTY_COMMENT -> {
                    resId = R.string.comment_is_too_short
                }

                SEND_ERROR_FILES_LIMIT -> {
                    resId = R.string.files_limit_reached
                }

                DELETE_ERROR_NO_ACCESS -> {
                    resId = R.string.no_access
                }

                DELETE_ERROR_PASSWORD -> {
                    resId = R.string.incorrect_password
                }

                DELETE_ERROR_NOT_FOUND -> {
                    resId = R.string.not_found
                }

                DELETE_ERROR_TOO_NEW -> {
                    resId = R.string.you_cant_delete_too_new_posts
                }

                DELETE_ERROR_TOO_OLD -> {
                    resId = R.string.you_cant_delete_too_old_posts
                }

                DELETE_ERROR_TOO_OFTEN -> {
                    resId = R.string.you_cant_send_too_often
                }

                REPORT_ERROR_NO_ACCESS -> {
                    resId = R.string.no_access
                }

                REPORT_ERROR_TOO_OFTEN -> {
                    resId = R.string.you_cant_send_too_often
                }

                REPORT_ERROR_EMPTY_COMMENT -> {
                    resId = R.string.comment_is_too_short
                }

                ARCHIVE_ERROR_NO_ACCESS -> {
                    resId = R.string.no_access
                }

                ARCHIVE_ERROR_TOO_OFTEN -> {
                    resId = R.string.you_cant_send_too_often
                }

                VOTE_ERROR_POSTING_PROHIBITED -> {
                    resId = R.string.vote_posting_prohibited
                }
            }
            return resId
        }
    }
}
