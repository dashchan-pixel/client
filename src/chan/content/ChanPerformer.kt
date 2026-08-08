package chan.content

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Pair
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.model.Board
import chan.content.model.BoardCategory
import chan.content.model.Posts
import chan.content.model.SinglePost
import chan.content.model.ThreadSummary
import chan.http.ChanFileOpenable
import chan.http.FirewallResolver
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.http.HttpRequest.OutputListenerPreset
import chan.http.HttpRequest.RangePreset
import chan.http.HttpRequest.TimeoutsPreset
import chan.http.HttpResponse
import chan.http.HttpValidator
import chan.http.MultipartEntity
import chan.http.MultipartEntity.OpenableOutputListener
import chan.util.CommonUtils
import chan.util.CommonUtils.equals
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.MainApplication.Companion.getInstance
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseOrThrow
import com.mishiranu.dashchan.content.model.PostNumber.Companion.validateThreadNumber
import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.GraphicsUtils.Reencoding
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import java.util.Collections
import java.util.Objects

@Extendable
open class ChanPerformer internal constructor(
    chanProvider: Chan.Provider?,
) : Chan.Linked {
    private val chanProvider: Chan.Provider?
    private val firewallResolvers = ArrayList<FirewallResolver>(0)

    private var isInitialized = false

    @Public
    constructor() : this(null)

    override fun init() {
        isInitialized = true
    }

    override fun get(): Chan = chanProvider!!.get()

    private fun checkInit() {
        check(!isInitialized) { "This method available only from constructor" }
    }

    @Public
    protected fun registerFirewallResolver(firewallResolver: FirewallResolver?) {
        Objects.requireNonNull<FirewallResolver?>(firewallResolver)
        checkInit()
        firewallResolvers.add(firewallResolver!!)
    }

    fun getFirewallResolvers(): List<FirewallResolver> = firewallResolvers

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class, RedirectException::class)
    protected open fun onReadThreads(data: ReadThreadsData?): ReadThreadsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(
        HttpException::class,
        InvalidResponseException::class,
        RedirectException::class,
        ThreadRedirectException::class,
    )
    protected open fun onReadPosts(data: ReadPostsData?): ReadPostsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadSinglePost(data: ReadSinglePostData?): ReadSinglePostResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadSearchPosts(data: ReadSearchPostsData?): ReadSearchPostsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadBoards(data: ReadBoardsData?): ReadBoardsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadUserBoards(data: ReadUserBoardsData?): ReadUserBoardsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadThreadSummaries(data: ReadThreadSummariesData?): ReadThreadSummariesResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadPostsCount(data: ReadPostsCountData?): ReadPostsCountResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadContent(data: ReadContentData): ReadContentResult = ReadContentResult(HttpRequest(data.uri, data.direct).perform())

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onCheckAuthorization(data: CheckAuthorizationData?): CheckAuthorizationResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onReadCaptcha(data: ReadCaptchaData?): ReadCaptchaResult = ReadCaptchaResult(CaptchaState.SKIP, null)

    @Extendable
    @Throws(HttpException::class, ApiException::class, InvalidResponseException::class)
    protected open fun onSendPost(data: SendPostData?): SendPostResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, ApiException::class, InvalidResponseException::class)
    protected open fun onSendDeletePosts(data: SendDeletePostsData?): SendDeletePostsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, ApiException::class, InvalidResponseException::class)
    protected open fun onSendReportPosts(data: SendReportPostsData?): SendReportPostsResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, ApiException::class, InvalidResponseException::class)
    protected open fun onSendVotePost(data: SendVotePostData?): SendVotePostResult? = throw UnsupportedOperationException()

    @Extendable
    @Throws(HttpException::class, ApiException::class, InvalidResponseException::class)
    protected open fun onSendAddToArchive(data: SendAddToArchiveData?): SendAddToArchiveResult? = throw UnsupportedOperationException()

    @Public
    class ReadThreadsData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val pageNumber: Int,
        @JvmField val holder: HttpHolder?,
        @field:Public @JvmField val validator: HttpValidator?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        @Public
        fun isCatalog(): Boolean = pageNumber == PAGE_NUMBER_CATALOG

        companion object {
            @Public
            @JvmField
            val PAGE_NUMBER_CATALOG: Int = -1
        }
    }

    @Public
    class ReadThreadsResult
        @Public
        constructor(
            threads: MutableCollection<Posts?>?,
        ) {
            class Thread(
                val posts: List<Post>?,
                val threadNumber: String?,
                val postsCount: Int,
                val filesCount: Int,
                val postsWithFilesCount: Int,
            )

            val threads: MutableList<Thread?>

            var boardSpeed: Int = 0
            var validator: HttpValidator? = null

            @Public
            constructor(vararg threads: Posts?) : this(Arrays.asList<Posts?>(*threads))

            init {
                var list = mutableListOf<Thread?>()
                if (threads != null) {
                    list = ArrayList<Thread?>(threads.size)
                    for (thread in threads) {
                        if (thread != null) {
                            val postsArray = thread.getPosts()
                            if (postsArray != null) {
                                var threadNumber: String? = null
                                val posts: MutableList<Post> = ArrayList()
                                for (post in postsArray) {
                                    if (post != null) {
                                        if (posts.isEmpty()) {
                                            threadNumber = post.getThreadNumberOrOriginalPostNumber()
                                        }
                                        posts.add(post.build())
                                    }
                                }
                                if (!posts.isEmpty()) {
                                    require(!isEmpty(threadNumber)) { "Thread number is not defined" }
                                    list.add(
                                        Thread(
                                            posts,
                                            threadNumber,
                                            thread.getPostsCount(),
                                            thread.getFilesCount(),
                                            thread.getPostsWithFilesCount(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                this.threads = list
            }

            @Public
            fun setBoardSpeed(boardSpeed: Int): ReadThreadsResult {
                this.boardSpeed = boardSpeed
                return this
            }

            @Public
            fun setValidator(validator: HttpValidator?): ReadThreadsResult {
                this.validator = validator
                return this
            }
        }

    @Public
    class ReadPostsData(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        lastPostNumber: String?,
        partialThreadLoading: Boolean,
        hasCachedPosts: Boolean,
        holder: HttpHolder?,
        validator: HttpValidator?,
    ) : HttpRequest.Preset {
        @Public
        @JvmField
        val boardName: String?

        @Public
        @JvmField
        val threadNumber: String?

        @Public
        @JvmField
        val lastPostNumber: String?

        @Public
        @JvmField
        val partialThreadLoading: Boolean

        @Public
        @JvmField
        val cachedPosts: Posts?

        @JvmField
        val holder: HttpHolder?

        @Public
        @JvmField
        val validator: HttpValidator?

        override fun getHolder(): HttpHolder? = holder

        init {
            this.boardName = boardName
            this.threadNumber = threadNumber
            this.lastPostNumber = lastPostNumber
            this.partialThreadLoading = partialThreadLoading
            this.cachedPosts =
                if (hasCachedPosts) Posts(chanName, boardName, threadNumber) else null
            this.holder = holder
            this.validator = validator
        }
    }

    @Public
    class ReadPostsResult private constructor(
        posts: Collection<chan.content.model.Post?>?,
        archivedThreadUri: Uri?,
        uniquePosters: Int,
    ) {
        val posts: MutableList<Post?>
        val archivedThreadUri: Uri?
        val uniquePosters: Int

        var validator: HttpValidator? = null
        var fullThread: Boolean = false

        @Public
        constructor(posts: Posts?) : this(
            posts?.getPosts()?.let { Arrays.asList(*it) },
            posts?.getArchivedThreadUri(),
            posts?.getUniquePosters() ?: 0,
        )

        @Public
        constructor(vararg posts: chan.content.model.Post?) : this(Arrays.asList(*posts), null, 0)

        @Public
        constructor(posts: MutableCollection<chan.content.model.Post>?) : this(posts, null, 0)

        init {
            var list = mutableListOf<Post?>()
            if (posts != null) {
                list = ArrayList<Post?>(posts.size)
                for (post in posts) {
                    list.add(post!!.build())
                }
            }
            this.posts = list
            this.archivedThreadUri = archivedThreadUri
            this.uniquePosters = uniquePosters
        }

        @Public
        fun setValidator(validator: HttpValidator?): ReadPostsResult {
            this.validator = validator
            return this
        }

        @Public
        fun setFullThread(fullThread: Boolean): ReadPostsResult {
            this.fullThread = fullThread
            return this
        }
    }

    @Public
    class ReadSinglePostData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val postNumber: String?,
        /**
         * Thread the post belongs to, when the link the post was reached through named it. Always
         * set on the only path that reaches [onReadSinglePost] today, because the client requires
         * a thread URI to offer the post card at all. Extensions whose engine can serve a post out
         * of its thread should prefer this over resolving the thread themselves.
         */
        @field:Public @JvmField val threadNumber: String?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    class ReadSinglePostResult
        @Public
        constructor(
            post: chan.content.model.Post?,
        ) {
            val post: SinglePost?

            init {
                this.post = if (post != null) SinglePost(post) else null
            }
        }

    @Public
    class ReadSearchPostsData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val searchQuery: String?,
        @field:Public @JvmField val pageNumber: Int,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    class ReadSearchPostsResult
        @Public
        constructor(
            posts: MutableCollection<chan.content.model.Post?>?,
        ) {
            val posts: MutableList<SinglePost?>

            @Public
            constructor(vararg posts: chan.content.model.Post?) : this(
                Arrays.asList<chan.content.model.Post?>(*posts),
            )

            init {
                var list = mutableListOf<SinglePost?>()
                if (posts != null) {
                    list = ArrayList<SinglePost?>(posts.size)
                    for (post in posts) {
                        if (post != null) {
                            list.add(SinglePost(post))
                        }
                    }
                }
                this.posts = list
            }
        }

    @Public
    class ReadBoardsData(
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    class ReadBoardsResult
        @Public
        constructor(
            vararg boardCategories: BoardCategory?,
        ) {
            val boardCategories: Array<BoardCategory>?

            init {
                val categories: Array<BoardCategory?> = arrayOf(*boardCategories)
                for (i in categories.indices) {
                    if (categories[i] != null) {
                        val boards = categories[i]!!.getBoards()
                        if (boards.isNullOrEmpty()) {
                            categories[i] = null
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                this.boardCategories = CommonUtils.removeNullItems(categories, BoardCategory::class.java) as Array<BoardCategory>?
            }

            @Public
            constructor(boardCategories: Collection<BoardCategory>?) : this(
                *(CommonUtils.toArray(boardCategories, BoardCategory::class.java) ?: arrayOfNulls(0)),
            )
        }

    @Public
    class ReadUserBoardsData(
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    class ReadUserBoardsResult
        @Public
        constructor(
            vararg boards: Board?,
        ) {
            val boards: Array<Board?>?

            init {
                this.boards = CommonUtils.removeNullItems(arrayOf(*boards), Board::class.java)
            }

            @Public
            constructor(boards: Collection<Board>?) : this(
                *(CommonUtils.toArray(boards, Board::class.java) ?: arrayOfNulls(0)),
            )
        }

    @Public
    class ReadThreadSummariesData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val pageNumber: Int,
        @field:Public @JvmField val type: Int,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        companion object {
            @Public
            const val TYPE_ARCHIVED_THREADS: Int = 0
        }
    }

    @Public
    class ReadThreadSummariesResult
        @Public
        constructor(
            vararg threadSummaries: ThreadSummary?,
        ) {
            val threadSummaries: Array<ThreadSummary?>?

            init {
                this.threadSummaries = CommonUtils.removeNullItems(arrayOf(*threadSummaries), ThreadSummary::class.java)
            }

            @Public
            constructor(threadSummaries: Collection<ThreadSummary>?) : this(
                *(CommonUtils.toArray(threadSummaries, ThreadSummary::class.java) ?: arrayOfNulls(0)),
            )
        }

    @Public
    class ReadPostsCountData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @JvmField val connectTimeout: Int,
        @JvmField val readTimeout: Int,
        @JvmField val holder: HttpHolder?,
        @field:Public @JvmField val validator: HttpValidator?,
    ) : TimeoutsPreset {
        override fun getHolder(): HttpHolder? = holder

        override fun getConnectTimeout(): Int = connectTimeout

        override fun getReadTimeout(): Int = readTimeout
    }

    @Public
    class ReadPostsCountResult
        @Public
        constructor(
            val postsCount: Int,
        ) {
            var validator: HttpValidator? = null

            @Public
            fun setValidator(validator: HttpValidator?): ReadPostsCountResult {
                this.validator = validator
                return this
            }
        }

    private class ReadContentDirectPreset(
        @JvmField val connectTimeout: Int,
        @JvmField val readTimeout: Int,
        @JvmField val holder: HttpHolder?,
        @JvmField val rangeStart: Long,
        @JvmField val rangeEnd: Long,
    ) : TimeoutsPreset,
        RangePreset {
        override fun getHolder(): HttpHolder? = holder

        override fun getConnectTimeout(): Int = connectTimeout

        override fun getReadTimeout(): Int = readTimeout

        override fun getRangeStart(): Long = rangeStart

        override fun getRangeEnd(): Long = rangeEnd
    }

    @Public
    class ReadContentData(
        @field:Public @JvmField val uri: Uri?,
        connectTimeout: Int,
        readTimeout: Int,
        holder: HttpHolder?,
        rangeStart: Long,
        rangeEnd: Long,
    ) : TimeoutsPreset {
        @Public
        @JvmField
        val direct: HttpRequest.Preset

        init {
            direct =
                ReadContentDirectPreset(connectTimeout, readTimeout, holder, rangeStart, rangeEnd)
        }

        override fun getHolder(): HttpHolder? = direct.getHolder()

        override fun getConnectTimeout(): Int = (direct as ReadContentDirectPreset).connectTimeout

        override fun getReadTimeout(): Int = (direct as ReadContentDirectPreset).readTimeout
    }

    @Public
    class ReadContentResult
        @Public
        constructor(
            val response: HttpResponse?,
        )

    @Public
    class CheckAuthorizationData(
        @field:Public @JvmField val type: Int,
        @field:Public @JvmField val authorizationData: Array<String?>?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        companion object {
            @Public
            const val TYPE_CAPTCHA_PASS: Int = 0

            @Public
            const val TYPE_USER_AUTHORIZATION: Int = 1
        }
    }

    @Public
    class CheckAuthorizationResult
        @Public
        constructor(
            val success: Boolean,
        )

    @Public
    class ReadCaptchaData(
        @field:Public @JvmField val captchaType: String?,
        @field:Public @JvmField val captchaPass: Array<String?>?,
        @field:Public @JvmField val mayShowLoadButton: Boolean,
        @field:Public @JvmField val requirement: String?,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    enum class CaptchaState {
        @Public
        CAPTCHA,

        @Public
        SKIP,

        @Public
        PASS,

        @Public
        NEED_LOAD,
    }

    @Public
    class ReadCaptchaResult
        @Public
        constructor(
            val captchaState: CaptchaState?,
            val captchaData: CaptchaData?,
        ) {
            var captchaType: String? = null
            var input: ChanConfiguration.Captcha.Input? = null
            var validity: ChanConfiguration.Captcha.Validity? = null
            var image: Bitmap? = null
            var large: Boolean = false

            @Public
            fun setCaptchaType(captchaType: String?): ReadCaptchaResult {
                this.captchaType = captchaType
                return this
            }

            @Public
            fun setInput(input: ChanConfiguration.Captcha.Input?): ReadCaptchaResult {
                this.input = input
                return this
            }

            @Public
            fun setValidity(validity: ChanConfiguration.Captcha.Validity?): ReadCaptchaResult {
                this.validity = validity
                return this
            }

            @Public
            fun setImage(image: Bitmap?): ReadCaptchaResult {
                this.image = image
                return this
            }

            @Public
            fun setLarge(large: Boolean): ReadCaptchaResult {
                this.large = large
                return this
            }
        }

    @Public
    class CaptchaData private constructor(
        private val data: MutableMap<String?, String?>,
    ) : Parcelable {
        @Public
        constructor() : this(HashMap<String?, String?>())

        @Public
        fun put(
            key: String?,
            value: String?,
        ) {
            data[key] = value
        }

        @Public
        fun get(key: String?): String? = data[key]

        fun copy(): CaptchaData = CaptchaData(HashMap<String?, String?>(data))

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeInt(data.size)
            for (entry in data.entries) {
                dest.writeString(entry.key)
                dest.writeString(entry.value)
            }
        }

        companion object {
            @Public
            const val CHALLENGE: String = "challenge"

            @Public
            const val INPUT: String = "input"

            @Public
            const val API_KEY: String = "api_key"

            @Public
            const val REFERER: String = "referer"

            /**
             * The name reCAPTCHA v3 binds a token to. The site decides it and verifies it back,
             * so a token minted under the wrong action is refused as firmly as no token at all.
             * Ignored by every other captcha type.
             */
            @Public
            const val ACTION: String = "action"

            @JvmField
            val CREATOR: Parcelable.Creator<CaptchaData?> =
                object : Parcelable.Creator<CaptchaData?> {
                    override fun createFromParcel(source: Parcel): CaptchaData {
                        val count = source.readInt()
                        val data = HashMap<String?, String?>(count)
                        for (i in 0..<count) {
                            data[source.readString()] = source.readString()
                        }
                        return CaptchaData(data)
                    }

                    override fun newArray(size: Int): Array<CaptchaData?> = arrayOfNulls<CaptchaData>(size)
                }
        }
    }

    @Public
    class SendPostData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val subject: String?,
        @field:Public @JvmField val comment: String?,
        @field:Public @JvmField val name: String?,
        @field:Public @JvmField val email: String?,
        @field:Public @JvmField val password: String?,
        @field:Public @JvmField val attachments: Array<Attachment?>?,
        @field:Public @JvmField val optionSage: Boolean,
        @field:Public @JvmField val optionSpoiler: Boolean,
        @field:Public @JvmField val optionOriginalPoster: Boolean,
        @field:Public @JvmField val userIcon: String?,
        @field:Public @JvmField val captchaType: String?,
        @field:Public @JvmField val captchaData: CaptchaData?,
        val captchaNeedLoad: Boolean,
        @JvmField val connectTimeout: Int,
        @JvmField val readTimeout: Int,
    ) : TimeoutsPreset,
        OutputListenerPreset {
        @JvmField
        var holder: HttpHolder? = null
        var listener: HttpRequest.OutputListener? = null

        override fun getHolder(): HttpHolder? = holder

        override fun getConnectTimeout(): Int = connectTimeout

        override fun getReadTimeout(): Int = readTimeout

        @Public
        class Attachment(
            val fileHolder: FileHolder,
            private val fileName: String?,
            @field:Public @JvmField val rating: String?,
            val optionUniqueHash: Boolean,
            val optionRemoveMetadata: Boolean,
            val optionRemoveFileName: Boolean,
            @field:Public @JvmField val optionSpoiler: Boolean,
            val reencoding: Reencoding?,
        ) {
            var listener: OpenableOutputListener? = null

            private var openable: ChanFileOpenable? = null

            private fun ensureOpenable(): ChanFileOpenable {
                var openable = this.openable
                if (openable == null) {
                    openable =
                        ChanFileOpenable(
                            fileHolder,
                            fileName,
                            optionUniqueHash,
                            optionRemoveMetadata,
                            optionRemoveFileName,
                            reencoding,
                        )
                    this.openable = openable
                }
                return openable
            }

            @Public
            fun addToEntity(
                entity: MultipartEntity,
                name: String?,
            ) {
                entity.add(name, ensureOpenable(), listener)
            }

            @Public
            fun getFileName(): String? = ensureOpenable().fileName

            @Public
            fun getMimeType(): String? = ensureOpenable().mimeType

            @Public
            @Throws(IOException::class)
            fun openInputSteam(): InputStream = ensureOpenable().openInputStream()

            @Public
            @Throws(IOException::class)
            fun openInputSteamForSending(): InputStream? {
                val inputStream = openInputSteam()
                if (listener != null) {
                    return InputStreamForSending(inputStream, getSize())
                } else {
                    return inputStream
                }
            }

            @Public
            fun getSize(): Long = ensureOpenable().size

            @Public
            fun getImageSize(): Pair<Int?, Int?>? {
                val openable = ensureOpenable()
                val width = openable.imageWidth
                val height = openable.imageHeight
                if (width > 0 && height > 0) {
                    return Pair<Int?, Int?>(width, height)
                } else {
                    return null
                }
            }

            private inner class InputStreamForSending(
                private val inputStream: InputStream,
                private val progressMax: Long,
            ) : InputStream() {
                private var progress: Long = 0

                fun notify(count: Int) {
                    progress += count.toLong()
                    listener!!.onOutputProgressChange(openable!!, progress, progressMax)
                }

                @Throws(IOException::class)
                override fun read(): Int {
                    val result = inputStream.read()
                    if (result != -1) {
                        notify(1)
                    }
                    return result
                }

                @Throws(IOException::class)
                override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

                @Throws(IOException::class)
                override fun read(
                    buffer: ByteArray,
                    byteOffset: Int,
                    byteCount: Int,
                ): Int {
                    val result = inputStream.read(buffer, byteOffset, byteCount)
                    if (result > 0) {
                        notify(result)
                    }
                    return result
                }

                @Throws(IOException::class)
                override fun close() {
                    inputStream.close()
                }
            }

            override fun equals(other: Any?): Boolean {
                if (other === this) {
                    return true
                }
                if (other is Attachment) {
                    val attachment = other
                    return attachment.fileHolder == fileHolder &&
                        equals(
                            attachment.rating,
                            rating,
                        ) &&
                        attachment.optionUniqueHash == optionUniqueHash &&
                        attachment.optionRemoveMetadata ==
                        optionRemoveMetadata &&
                        attachment.optionRemoveFileName == optionRemoveFileName &&
                        attachment.optionSpoiler == optionSpoiler
                }
                return false
            }

            override fun hashCode(): Int {
                val prime = 31
                var result = 1
                result = prime * result + fileHolder.hashCode()
                result = prime * result + (if (rating != null) rating.hashCode() else 0)
                result = prime * result + (if (optionUniqueHash) 1 else 0)
                result = prime * result + (if (optionRemoveMetadata) 1 else 0)
                result = prime * result + (if (optionRemoveFileName) 1 else 0)
                result = prime * result + (if (optionSpoiler) 1 else 0)
                return result
            }
        }

        override fun getOutputListener(): HttpRequest.OutputListener? = listener
    }

    @Public
    class SendPostResult
        @Public
        constructor(
            val threadNumber: String?,
            postNumber: String?,
        ) {
            val postNumber: PostNumber?

            init {
                this.postNumber = if (postNumber != null) parseOrThrow(postNumber) else null
                validateThreadNumber(threadNumber, true)
            }
        }

    @Public
    class SendDeletePostsData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumbers: List<String>?,
        @field:Public @JvmField val password: String?,
        @field:Public @JvmField val optionFilesOnly: Boolean,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    @Public
    class SendDeletePostsResult
        @Public
        constructor()

    @Public
    class SendReportPostsData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumbers: List<String>?,
        @field:Public @JvmField val type: String?,
        options: List<String>?,
        @field:Public @JvmField val comment: String?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        @Public
        @JvmField
        val options: List<String>?

        init {
            this.options =
                if (options != null) Collections.unmodifiableList(options) else null
        }
    }

    @Public
    class SendVotePostData(
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumber: String?,
        @field:Public @JvmField val isLike: Boolean,
        @field:Public @JvmField val type: String?,
        options: List<String>?,
        comment: String?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        @Public
        @JvmField
        val options: List<String>?

        init {
            this.options =
                if (options != null) Collections.unmodifiableList(options) else null
        }
    }

    @Public
    class SendReportPostsResult
        @Public
        constructor()

    @Public
    class SendVotePostResult
        @Public
        constructor()

    @Public
    class SendAddToArchiveData(
        @field:Public @JvmField val uri: Uri?,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        options: List<String>?,
        @JvmField val holder: HttpHolder?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder

        @Public
        @JvmField
        val options: List<String>?

        init {
            this.options =
                if (options != null) Collections.unmodifiableList(options) else null
        }
    }

    @Public
    class SendAddToArchiveResult
        @Public
        constructor(
            val boardName: String?,
            val threadNumber: String?,
        ) {
            init {
                validateThreadNumber(threadNumber, true)
            }
        }

    private val requireCallState: ThreadLocal<Boolean?> =
        object : ThreadLocal<Boolean?>() {
            override fun initialValue(): Boolean = false
        }

    /**
     * Whether the performer call running on this thread is one the user sends -- a post, a
     * deletion, a report, a vote, an archive submission, an authorization check -- rather than a
     * read. A captcha the extension asks for from inside such a call belongs to it, and is read
     * through the proxy where the forum is set to carry sending only.
     */
    private val sendingCallState: ThreadLocal<Boolean?> =
        object : ThreadLocal<Boolean?>() {
            override fun initialValue(): Boolean = false
        }

    private class PerformerContext(
        val requireCallState: Boolean,
        val sendingCallState: Boolean,
    )

    private fun enterContext(sending: Boolean = false): PerformerContext {
        val requireCallState = this.requireCallState.get()!!
        val sendingCallState = this.sendingCallState.get()!!
        this.requireCallState.set(true)
        this.sendingCallState.set(sending || sendingCallState)
        return PerformerContext(requireCallState, sendingCallState)
    }

    private fun exitContext(context: PerformerContext) {
        requireCallState.set(context.requireCallState)
        sendingCallState.set(context.sendingCallState)
    }

    private fun checkPerformerRequireCall() {
        check(requireCallState.get()!!) { "Invalid call state" }
    }

    @Public
    @Throws(HttpException::class)
    fun requireUserCaptcha(
        requirement: String?,
        boardName: String?,
        threadNumber: String?,
        retry: Boolean,
    ): CaptchaData? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserCaptcha(
                get(),
                requirement,
                boardName,
                threadNumber,
                retry,
                sendingCallState.get() == true,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    @Public
    @Throws(HttpException::class)
    fun requireUserItemSingleChoice(
        selected: Int,
        item: Array<CharSequence?>?,
        descriptionText: String?,
        descriptionImage: Bitmap?,
    ): Int? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserItemSingleChoice(
                selected,
                item,
                descriptionText,
                descriptionImage,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    @Public
    @Throws(HttpException::class)
    fun requireUserItemMultipleChoice(
        selected: BooleanArray?,
        item: Array<CharSequence?>?,
        descriptionText: String?,
        descriptionImage: Bitmap?,
    ): BooleanArray? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserItemMultipleChoice(
                selected,
                item!!,
                descriptionText,
                descriptionImage,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    @Public
    @Throws(HttpException::class)
    fun requireUserImageSingleChoice(
        selected: Int,
        images: Array<Bitmap?>?,
        descriptionText: String?,
        descriptionImage: Bitmap?,
    ): Int? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserImageSingleChoice(
                3,
                selected,
                images,
                descriptionText,
                descriptionImage,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    @Public
    @Throws(HttpException::class)
    fun requireUserImageMultipleChoice(
        selected: BooleanArray?,
        images: Array<Bitmap?>?,
        descriptionText: String?,
        descriptionImage: Bitmap?,
    ): BooleanArray? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserImageMultipleChoice(
                3,
                selected,
                images!!,
                descriptionText,
                descriptionImage,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    /**
     * Suspends this thread and shows a slide-puzzle captcha: the user drags [slider] over
     * [background] to the gap. Returns the piece's chosen left offset in [background]'s pixels, or
     * `null` if the user canceled.
     */
    @Public
    @Throws(HttpException::class)
    fun requireUserImageSlider(
        background: Bitmap,
        slider: Bitmap,
        sliderY: Int,
        descriptionText: String?,
    ): Int? {
        checkPerformerRequireCall()
        try {
            return ForegroundManager.getInstance().requireUserImageSlider(
                background,
                slider,
                sliderY,
                descriptionText,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException(ErrorItem.Type.UNKNOWN, false, false, e)
        }
    }

    class Safe internal constructor(
        private val performer: ChanPerformer,
    ) {
        @Throws(
            ExtensionException::class,
            HttpException::class,
            InvalidResponseException::class,
            RedirectException::class,
        )
        fun onReadThreads(data: ReadThreadsData?): ReadThreadsResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadThreads(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            InvalidResponseException::class,
            RedirectException::class,
            ThreadRedirectException::class,
        )
        fun onReadPosts(data: ReadPostsData?): ReadPostsResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadPosts(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadSinglePost(data: ReadSinglePostData?): ReadSinglePostResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadSinglePost(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadSearchPosts(data: ReadSearchPostsData?): ReadSearchPostsResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadSearchPosts(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadBoards(data: ReadBoardsData?): ReadBoardsResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadBoards(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadUserBoards(data: ReadUserBoardsData?): ReadUserBoardsResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadUserBoards(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadThreadSummaries(data: ReadThreadSummariesData?): ReadThreadSummariesResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadThreadSummaries(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadPostsCount(data: ReadPostsCountData?): ReadPostsCountResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadPostsCount(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadContent(data: ReadContentData): ReadContentResult? {
            val context = performer.enterContext()
            try {
                return performer.onReadContent(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onCheckAuthorization(data: CheckAuthorizationData?): CheckAuthorizationResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onCheckAuthorization(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onReadCaptcha(data: ReadCaptchaData?): ReadCaptchaResult {
            val context = performer.enterContext()
            try {
                return performer.onReadCaptcha(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            ApiException::class,
            InvalidResponseException::class,
        )
        fun onSendPost(data: SendPostData?): SendPostResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onSendPost(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            ApiException::class,
            InvalidResponseException::class,
        )
        fun onSendDeletePosts(data: SendDeletePostsData?): SendDeletePostsResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onSendDeletePosts(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            ApiException::class,
            InvalidResponseException::class,
        )
        fun onSendReportPosts(data: SendReportPostsData?): SendReportPostsResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onSendReportPosts(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            ApiException::class,
            InvalidResponseException::class,
        )
        fun onSendVotePost(data: SendVotePostData?): SendVotePostResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onSendVotePost(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }

        @Throws(
            ExtensionException::class,
            HttpException::class,
            ApiException::class,
            InvalidResponseException::class,
        )
        fun onSendAddToArchive(data: SendAddToArchiveData?): SendAddToArchiveResult? {
            val context = performer.enterContext(sending = true)
            try {
                return performer.onSendAddToArchive(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            } finally {
                performer.exitContext(context)
            }
        }
    }

    private val safe = ChanPerformer.Safe(this)

    init {
        if (chanProvider == null) {
            val holder: ChanManager.Initializer.Holder = INITIALIZER.consume()
            this.chanProvider = holder.chanProvider
        } else {
            this.chanProvider = chanProvider
        }
    }

    fun safe(): Safe = safe

    companion object {
        val INITIALIZER: ChanManager.Initializer = ChanManager.Initializer()

        @Public
        @JvmStatic
        fun get(`object`: Any): ChanPerformer = (`object` as Chan.Linked).get().performer
    }
}
