package chan.content

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.ExtensionException.Companion.logException
import chan.util.CommonUtils
import chan.util.CommonUtils.equals
import chan.util.StringUtils
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.escapeFile
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.content.Preferences.getDomainUnhandled
import com.mishiranu.dashchan.content.Preferences.isUseHttps
import com.mishiranu.dashchan.content.Preferences.isUseHttpsGeneral
import com.mishiranu.dashchan.content.Preferences.setDomainUnhandled
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseOrThrow
import com.mishiranu.dashchan.content.model.PostNumber.Companion.validateThreadNumber
import java.util.regex.Pattern

@Extendable
open class ChanLocator internal constructor(chanProvider: Chan.Provider?) : Chan.Linked {
    private val chanProvider: Chan.Provider?

    private val hosts = LinkedHashMap<String?, Int?>()
    private var httpsMode = HttpsMode.NO_HTTPS

    @Public
    enum class HttpsMode {
        @Public
        NO_HTTPS,

        @Public
        HTTPS_ONLY,

        @Public
        CONFIGURABLE
    }

    @Public
    class NavigationData(
        val target: Target,
        val boardName: String?,
        val threadNumber: String?,
        val postNumber: PostNumber?,
        val searchQuery: String?
    ) : Parcelable {
        enum class Target {
            THREADS, POSTS, SEARCH
        }

        @Public
        constructor(
            target: Int, boardName: String?, threadNumber: String?, postNumber: String?,
            searchQuery: String?
        ) : this(
            transformTarget(target), boardName, threadNumber, if (postNumber != null)
                parseOrThrow(postNumber)
            else
                null, searchQuery
        ) {
            validateThreadNumber(threadNumber, true)
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(target.name)
            dest.writeString(boardName)
            dest.writeString(threadNumber)
            dest.writeByte((if (postNumber != null) 1 else 0).toByte())
            if (postNumber != null) {
                postNumber.writeToParcel(dest, flags)
            }
            dest.writeString(searchQuery)
        }

        init {
            require(
                !(target == Target.POSTS && isEmpty(
                    threadNumber
                ))
            ) { "threadNumber must not be empty!" }
        }

        companion object {
            @Public
            const val TARGET_THREADS: Int = 0

            @Public
            const val TARGET_POSTS: Int = 1

            @Public
            const val TARGET_SEARCH: Int = 2

            private fun transformTarget(target: Int): Target {
                when (target) {
                    TARGET_THREADS -> {
                        return Target.THREADS
                    }

                    TARGET_POSTS -> {
                        return Target.POSTS
                    }

                    TARGET_SEARCH -> {
                        return Target.SEARCH
                    }

                    else -> {
                        throw IllegalArgumentException()
                    }
                }
            }

            @JvmField
            val CREATOR: Parcelable.Creator<NavigationData?> =
                object : Parcelable.Creator<NavigationData?> {
                    override fun createFromParcel(source: Parcel): NavigationData {
                        val target = Target.valueOf(source.readString()!!)
                        val boardName = source.readString()
                        val threadNumber = source.readString()
                        val postNumber =
                            if (source.readByte().toInt() != 0) PostNumber.CREATOR.createFromParcel(
                                source
                            ) else null
                        val searchQuery = source.readString()
                        return NavigationData(
                            target,
                            boardName,
                            threadNumber,
                            postNumber,
                            searchQuery
                        )
                    }

                    override fun newArray(size: Int): Array<NavigationData?> {
                        return arrayOfNulls<NavigationData>(size)
                    }
                }
        }
    }

    @Public
    constructor() : this(null)

    override fun init() {
        if (getChanHosts(true).size == 0) {
            throw RuntimeException("Chan hosts not defined")
        }
    }

    override fun get(): Chan {
        return chanProvider!!.get()
    }

    @Public
    fun addChanHost(host: String?) {
        hosts.put(host, HOST_TYPE_CONFIGURABLE)
    }

    @Public
    fun addConvertableChanHost(host: String?) {
        hosts.put(host, HOST_TYPE_CONVERTABLE)
    }

    @Public
    fun addSpecialChanHost(host: String?) {
        hosts.put(host, HOST_TYPE_SPECIAL)
    }

    @Public
    fun setHttpsMode(httpsMode: HttpsMode) {
        if (httpsMode == null) {
            throw NullPointerException()
        }
        this.httpsMode = httpsMode
    }

    val isHttpsConfigurable: Boolean
        get() = httpsMode == HttpsMode.CONFIGURABLE

    @Public
    fun isUseHttps(): Boolean {
        val httpsMode = this.httpsMode
        if (httpsMode == HttpsMode.CONFIGURABLE) {
            val chan = get()
            return if (chan.name != null) isUseHttps(chan) else isUseHttpsGeneral
        }
        return httpsMode == HttpsMode.HTTPS_ONLY
    }

    fun getChanHosts(configurableOnly: Boolean): ArrayList<String> {
        if (configurableOnly) {
            val hosts = ArrayList<String>()
            for (entry in this.hosts.entries) {
                if (entry.value == HOST_TYPE_CONFIGURABLE) {
                    hosts.add(entry.key!!)
                }
            }
            return hosts
        } else {
            return hosts.keys.mapTo(ArrayList()) { it!! }
        }
    }

    fun isChanHost(host: String): Boolean {
        if (isEmpty(host)) {
            return false
        }
        return hosts.containsKey(host) || host == getDomainUnhandled(get())
                || getHostTransition(this.preferredHost, host) != null
    }

    @Public
    fun isChanHostOrRelative(uri: Uri?): Boolean {
        if (uri != null) {
            if (uri.isRelative()) {
                return true
            }
            val host = uri.getHost()
            return host != null && isChanHost(host)
        }
        return false
    }

    fun isConvertableChanHost(host: String): Boolean {
        if (isEmpty(host)) {
            return false
        }
        if (host == getDomainUnhandled(get())) {
            return true
        }
        val hostType = hosts.get(host)
        return hostType != null && (hostType == HOST_TYPE_CONFIGURABLE || hostType == HOST_TYPE_CONVERTABLE)
    }

    fun convert(uri: Uri?): Uri? {
        if (uri != null) {
            val preferredScheme = this.preferredScheme
            val host = uri.getHost()
            val preferredHost = this.preferredHost
            val relative = uri.isRelative()
            val webScheme = isWebScheme(uri)
            var builder: Uri.Builder? = null
            if (relative || webScheme && isConvertableChanHost(host!!)) {
                if (!equals(host, preferredHost)) {
                    if (builder == null) {
                        builder = uri.buildUpon().scheme(preferredScheme)
                    }
                    builder.authority(preferredHost)
                }
            } else if (webScheme) {
                val hostTransition = getHostTransition(preferredHost, host)
                if (hostTransition != null) {
                    if (builder == null) {
                        builder = uri.buildUpon().scheme(preferredScheme)
                    }
                    builder.authority(hostTransition)
                }
            }
            if (isEmpty(uri.getScheme()) ||
                webScheme && (preferredScheme != uri.getScheme()) && isChanHost(host!!)
            ) {
                if (builder == null) {
                    builder = uri.buildUpon().scheme(preferredScheme)
                }
                builder.scheme(preferredScheme)
            }
            if (builder != null) {
                return builder.build()
            }
        }
        return uri
    }

    fun makeRelative(uri: Uri): Uri {
        var uri = uri
        if (isWebScheme(uri)) {
            val host = uri.getHost()
            if (isConvertableChanHost(host!!)) {
                uri = uri.buildUpon().scheme(null).authority(null).build()
            }
        }
        return uri
    }

    fun fixRelativeFileUri(uri: Uri?): Uri? {
        if (uri == null) {
            return null
        }
        var uriString = uri.toString()
        var index = uriString.indexOf("//")
        if (index >= 0) {
            index = uriString.indexOf('/', index + 2)
            if (index >= 0) {
                index++
            } else {
                return uri
            }
        }
        if (index < 0) {
            index = 0
        }
        if (uriString.indexOf(':', index) >= 0) {
            uriString =
                uriString.substring(0, index) + uriString.substring(index).replace(":", "%3A")
        }
        return Uri.parse(uriString)
    }

    @Extendable
    protected open fun getHostTransition(chanHost: String?, requiredHost: String?): String? {
        return null
    }

    @Extendable
    open fun isBoardUri(uri: Uri?): Boolean {
        throw UnsupportedOperationException()
    }

    @Extendable
    open fun isThreadUri(uri: Uri?): Boolean {
        throw UnsupportedOperationException()
    }

    @Extendable
    protected open fun isAttachmentUri(uri: Uri?): Boolean {
        throw UnsupportedOperationException()
    }

    fun isImageUri(uri: Uri?): Boolean {
        return uri != null && isImageExtension(uri.getPath()) && safe.isAttachmentUri(uri)
    }

    fun isAudioUri(uri: Uri?): Boolean {
        return uri != null && isAudioExtension(uri.getPath()) && safe.isAttachmentUri(uri)
    }

    fun isVideoUri(uri: Uri?): Boolean {
        return uri != null && isVideoExtension(uri.getPath()) && safe.isAttachmentUri(uri)
    }

    @Extendable
    open fun getBoardName(uri: Uri?): String? {
        throw UnsupportedOperationException()
    }

    @Extendable
    open fun getThreadNumber(uri: Uri?): String? {
        throw UnsupportedOperationException()
    }

    @Extendable
    open fun getPostNumber(uri: Uri?): String? {
        throw UnsupportedOperationException()
    }

    @Extendable
    protected open fun createBoardUri(boardName: String?, pageNumber: Int): Uri? {
        throw UnsupportedOperationException()
    }

    @Extendable
    protected open fun createThreadUri(boardName: String?, threadNumber: String?): Uri? {
        throw UnsupportedOperationException()
    }

    @Extendable
    protected open fun createPostUri(
        boardName: String?,
        threadNumber: String?,
        postNumber: String?
    ): Uri? {
        throw UnsupportedOperationException()
    }

    @Extendable
    protected open fun createAttachmentForcedName(fileUri: Uri?): String? {
        return null
    }

    @JvmOverloads
    fun createAttachmentFileName(
        fileUri: Uri,
        forcedName: String? = safe.createAttachmentForcedName(fileUri)
    ): String {
        val fileName: String?
        if (isEmpty(forcedName)) {
            val fileUriString = fileUri.getPath()
            val start = fileUriString!!.lastIndexOf('/') + 1
            fileName = fileUriString.substring(start)
        } else {
            fileName = forcedName
        }
        return if (isEmpty(fileName)) "" else emptyIfNull(escapeFile(fileName, false))
    }

    fun validateClickedUriString(
        uriString: String?,
        boardName: String?,
        threadNumber: String?
    ): Uri? {
        val uri = if (uriString != null) Uri.parse(uriString) else null
        if (uri != null && uri.isRelative()) {
            val baseUri = safe.createThreadUri(boardName, threadNumber)
            if (baseUri != null) {
                val query = nullIfEmpty(uri.getQuery())
                val fragment = nullIfEmpty(uri.getFragment())
                val builder = baseUri.buildUpon().encodedQuery(query).encodedFragment(fragment)
                val path = uri.getPath()
                if (!isEmpty(path)) {
                    builder.encodedPath(path)
                }
                return builder.build()
            }
        }
        return uri
    }

    @Extendable
    protected open fun handleUriClickSpecial(uri: Uri?): NavigationData? {
        return null
    }

    fun isWebScheme(uri: Uri): Boolean {
        val scheme = uri.getScheme()
        return "http" == scheme || "https" == scheme
    }

    @Public
    fun isImageExtension(path: String?): Boolean {
        return C.IMAGE_EXTENSIONS.contains(getFileExtension(path))
    }

    @Public
    fun isAudioExtension(path: String?): Boolean {
        return C.AUDIO_EXTENSIONS.contains(getFileExtension(path))
    }

    @Public
    fun isVideoExtension(path: String?): Boolean {
        return C.VIDEO_EXTENSIONS.contains(getFileExtension(path))
    }

    @Public
    fun getFileExtension(path: String?): String? {
        return StringUtils.getFileExtension(path)
    }

    var preferredHost: String?
        get() {
            var host =
                getDomainUnhandled(get())
            if (isEmpty(host)) {
                for (entry in hosts.entries) {
                    if (entry.value == HOST_TYPE_CONFIGURABLE) {
                        host = entry.key
                        break
                    }
                }
            }
            return host
        }
        set(host) {
            var host = host
            if (host == null || getChanHosts(true).get(0) == host) {
                host = ""
            }
            setDomainUnhandled(get(), host)
        }

    private val preferredScheme: String
        get() = getPreferredScheme(isUseHttps())

    @Public
    fun buildPath(vararg segments: String?): Uri? {
        return buildPathWithHost(this.preferredHost, *segments)
    }

    @Public
    fun buildPathWithHost(host: String?, vararg segments: String?): Uri? {
        return buildPathWithSchemeHost(isUseHttps(), host, *segments)
    }

    @Public
    fun buildPathWithSchemeHost(useHttps: Boolean, host: String?, vararg segments: String?): Uri? {
        val builder = Uri.Builder().scheme(getPreferredScheme(useHttps)).authority(host)
        for (i in segments.indices) {
            val segment: String? = segments[i]
            if (segment != null) {
                builder.appendEncodedPath(segment.replaceFirst("^/+".toRegex(), ""))
            }
        }
        return builder.build()
    }

    @Public
    fun buildQuery(path: String?, vararg alternation: String?): Uri? {
        return buildQueryWithHost(this.preferredHost, path, *alternation)
    }

    @Public
    fun buildQueryWithHost(host: String?, path: String?, vararg alternation: String?): Uri? {
        return buildQueryWithSchemeHost(isUseHttps(), host, path, *alternation)
    }

    @Public
    fun buildQueryWithSchemeHost(
        useHttps: Boolean,
        host: String?,
        path: String?,
        vararg alternation: String?
    ): Uri? {
        val builder = Uri.Builder().scheme(getPreferredScheme(useHttps)).authority(host)
        if (path != null) {
            builder.appendEncodedPath(path.replaceFirst("^/+".toRegex(), ""))
        }
        require(alternation.size % 2 == 0) { "Length of alternation must be a multiple of 2." }
        var i = 0
        while (i < alternation.size) {
            builder.appendQueryParameter(alternation[i], alternation[i + 1])
            i += 2
        }
        return builder.build()
    }

    fun setSchemeIfEmpty(uri: Uri?, fallback: String?): Uri? {
        if (uri != null && isEmpty(uri.getScheme())) {
            return uri.buildUpon().scheme(if (fallback != null) fallback else this.preferredScheme)
                .build()
        }
        return uri
    }

    @Public
    fun isPathMatches(uri: Uri?, pattern: Pattern): Boolean {
        if (uri != null) {
            val path = uri.getPath()
            if (path != null) {
                return pattern.matcher(path).matches()
            }
        }
        return false
    }

    @Public
    fun getGroupValue(from: String?, pattern: Pattern, groupIndex: Int): String? {
        if (from == null) {
            return null
        }
        val matcher = pattern.matcher(from)
        if (matcher.find() && matcher.groupCount() > 0) {
            return matcher.group(groupIndex)
        }
        return null
    }

    fun getUniqueGroupValues(from: String?, pattern: Pattern, groupIndex: Int): Array<String?>? {
        if (from == null) {
            return null
        }
        val matcher = pattern.matcher(from)
        val data = LinkedHashSet<String?>()
        while (matcher.find() && matcher.groupCount() > 0) {
            data.add(matcher.group(groupIndex))
        }
        @Suppress("UNCHECKED_CAST")
        return CommonUtils.toArray(data, String::class.java as Class<String?>)
    }

    class Safe internal constructor(
        private val locator: ChanLocator,
        private val showToastOnError: Boolean
    ) {
        fun isBoardUri(uri: Uri?): Boolean {
            try {
                return locator.isBoardUri(uri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return false
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return false
            }
        }

        fun isThreadUri(uri: Uri?): Boolean {
            try {
                return locator.isThreadUri(uri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return false
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return false
            }
        }

        fun isAttachmentUri(uri: Uri?): Boolean {
            try {
                return locator.isAttachmentUri(uri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return false
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return false
            }
        }

        fun getBoardName(uri: Uri?): String? {
            try {
                return locator.getBoardName(uri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun getThreadNumber(uri: Uri?): String? {
            try {
                val threadNumber = locator.getThreadNumber(uri)
                validateThreadNumber(threadNumber, true)
                return threadNumber
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun getPostNumber(uri: Uri?): PostNumber? {
            try {
                val postNumber = locator.getPostNumber(uri)
                return if (postNumber != null) parseOrThrow(postNumber) else null
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun createBoardUri(boardName: String?, pageNumber: Int): Uri? {
            try {
                return locator.createBoardUri(boardName, pageNumber)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun createThreadUri(boardName: String?, threadNumber: String?): Uri? {
            try {
                return locator.createThreadUri(boardName, threadNumber)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun createPostUri(
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?
        ): Uri? {
            try {
                return locator.createPostUri(
                    boardName, threadNumber,
                    if (postNumber != null) postNumber.toString() else null
                )
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun createAttachmentForcedName(fileUri: Uri?): String? {
            try {
                return locator.createAttachmentForcedName(fileUri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }

        fun handleUriClickSpecial(uri: Uri?): NavigationData? {
            try {
                return locator.handleUriClickSpecial(uri)
            } catch (e: LinkageError) {
                logException(e, showToastOnError)
                return null
            } catch (e: RuntimeException) {
                logException(e, showToastOnError)
                return null
            }
        }
    }

    private val safeToast = ChanLocator.Safe(this, true)
    private val safe = ChanLocator.Safe(this, false)

    init {
        if (chanProvider == null) {
            val holder: ChanManager.Initializer.Holder = INITIALIZER.consume()
            this.chanProvider = holder.chanProvider
        } else {
            this.chanProvider = chanProvider
            setHttpsMode(HttpsMode.CONFIGURABLE)
        }
    }

    fun safe(showToastOnError: Boolean): Safe {
        return if (showToastOnError) safeToast else safe
    }

    companion object {
        private const val HOST_TYPE_CONFIGURABLE = 0
        private const val HOST_TYPE_CONVERTABLE = 1
        private const val HOST_TYPE_SPECIAL = 2

        val INITIALIZER: ChanManager.Initializer = ChanManager.Initializer()

        @Public
        @JvmStatic
        fun get(`object`: Any): ChanLocator {
            return (`object` as Chan.Linked).get().locator
        }

        private fun getPreferredScheme(useHttps: Boolean): String {
            return if (useHttps) "https" else "http"
        }
    }
}
