package chan.content

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.CancellationSignal
import android.util.Pair
import android.util.SparseArray
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.ExtensionException.Companion.logException
import chan.content.model.BoardCategory
import chan.util.CommonUtils
import chan.util.DataFile
import chan.util.DataFile.Companion.obtain
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.isEmptyOrWhitespace
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.getCaptchaTypeForChan
import com.mishiranu.dashchan.content.Preferences.getUserAuthorizationData
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.database.ChanDatabase.BoardCursor
import com.mishiranu.dashchan.content.database.ChanDatabase.BoardExtraFallbackProvider
import com.mishiranu.dashchan.util.IOUtils.copyStream
import java.io.IOException
import java.io.OutputStream

@Extendable
open class ChanConfiguration internal constructor(chanProvider: Chan.Provider?) : Chan.Linked {
    private val chanProvider: Chan.Provider?
    private val resources: Resources?
    private val editData: HashMap<ChanDatabase.DataKey?, Any?>?

    private var isInitialized = false

    @Public
    constructor() : this(null)

    override fun init() {
        isInitialized = true
    }

    override fun get(): Chan {
        return chanProvider!!.get()
    }

    @Public
    enum class BumpLimitMode {
        @Public
        AFTER_POST,

        @Public
        AFTER_REPLY,

        @Public
        BEFORE_POST
    }

    @Public
    class Board @Public constructor() {
        @Public
        @JvmField
        var allowSearch: Boolean = false

        @Public
        @JvmField
        var allowCatalog: Boolean = false

        @Public
        @JvmField
        var allowArchive: Boolean = false

        @JvmField
        @Public
        var allowPosting: Boolean = false

        @JvmField
        @Public
        var allowDeleting: Boolean = false

        @JvmField
        @Public
        var allowReporting: Boolean = false

        @Public
        @JvmField
        var allowVotes: Boolean = false
    }

    @Public
    class Captcha @Public constructor() {
        @Public
        enum class Input {
            @Public
            ALL,

            @Public
            LATIN,

            @Public
            NUMERIC
        }

        @Public
        enum class Validity {
            @Public
            SHORT_LIFETIME,

            @Public
            IN_THREAD,

            @Public
            IN_BOARD_SEPARATELY,

            @Public
            IN_BOARD,

            @Public
            LONG_LIFETIME
        }

        @Public
        @JvmField
        var title: String? = null

        @JvmField
        @Public
        var input: Input = Input.ALL

        @JvmField
        @Public
        var validity: Validity = Validity.LONG_LIFETIME

        @JvmField
        @Public
        var ttl: Int = -1
    }

    @Public
    class Posting @Public constructor() {
        @JvmField
        @Public
        var allowName: Boolean = false

        @JvmField
        @Public
        var allowTripcode: Boolean = false

        @JvmField
        @Public
        var allowEmail: Boolean = false

        @JvmField
        @Public
        var allowSubject: Boolean = false

        @JvmField
        @Public
        var optionSage: Boolean = false

        @JvmField
        @Public
        var optionSpoiler: Boolean = false

        @JvmField
        @Public
        var optionOriginalPoster: Boolean = false

        @JvmField
        @Public
        var maxCommentLength: Int = 0

        @JvmField
        @Public
        var maxCommentLengthEncoding: String? = null

        @JvmField
        @Public
        var attachmentCount: Int = 0

        @JvmField
        @Public
        val attachmentMimeTypes: MutableSet<String> = HashSet()

        @JvmField
        @Public
        val attachmentRatings: MutableList<Pair<String, String>> =
            ArrayList()

        @JvmField
        @Public
        var attachmentSpoiler: Boolean = false

        @JvmField
        @Public
        val userIcons: MutableList<Pair<String, String>> = ArrayList()

        @Public
        @JvmField
        var hasCountryFlags: Boolean = false
    }

    @Public
    class Deleting @Public constructor() {
        @JvmField
        @Public
        var password: Boolean = false

        @JvmField
        @Public
        var multiplePosts: Boolean = false

        @Public
        @JvmField
        var optionFilesOnly: Boolean = false
    }

    @Public
    class Reporting @Public constructor() {
        @Public
        @JvmField
        var comment: Boolean = false

        @JvmField
        @Public
        var multiplePosts: Boolean = false

        @Public
        @JvmField
        val types: MutableList<Pair<String, String>> = ArrayList()

        @Public
        @JvmField
        val options: MutableList<Pair<String, String>> = ArrayList()
    }

    @Public
    class Voting @Public constructor() {
        @Public
        @JvmField
        var allowLike: Boolean = true

        @Public
        @JvmField
        var allowDislike: Boolean = true
    }

    @Public
    class Authorization @Public constructor() {
        @Public
        @JvmField
        var fieldsCount: Int = 0

        @Public
        @JvmField
        var hints: Array<String?>? = null
    }

    @Public
    class Archivation @Public constructor() {
        @Public
        @JvmField
        val hosts: MutableList<String?> = ArrayList<String?>()

        @Public
        @JvmField
        val options: MutableList<Pair<String, String>> = ArrayList()

        @Public
        @JvmField
        var queryOnly: Boolean = false
    }

    @Public
    class Statistics @Public constructor() {
        @Public
        @JvmField
        var threadsViewed: Boolean = true

        @Public
        @JvmField
        var postsSent: Boolean = true

        @Public
        @JvmField
        var threadsCreated: Boolean = true
    }

    @Public
    class CustomPreference @Public constructor() {
        @Public
        @JvmField
        var title: String? = null

        @Public
        @JvmField
        var summary: String? = null
    }

    fun commit() {
        if (editData != null) {
            synchronized(editData) {
                ChanDatabase.getInstance().setData(get().name!!, editData)
                editData.clear()
            }
        }
    }

    @Public
    fun get(boardName: String?, key: String?, defaultValue: Boolean): Boolean {
        if (editData == null) {
            return defaultValue
        }
        val dataKey = ChanDatabase.DataKey(boardName, key)
        synchronized(editData) {
            val result = editData.get(dataKey)
            if (result != null) {
                return if (result is Boolean) result else defaultValue
            }
        }
        val value: String? =
            ChanDatabase.getInstance().getData(get().name!!, dataKey.boardName, dataKey.name)
        return if (value != null) ("0" != value) else defaultValue
    }

    @Public
    fun get(boardName: String?, key: String?, defaultValue: Int): Int {
        if (editData == null) {
            return defaultValue
        }
        val dataKey = ChanDatabase.DataKey(boardName, key)
        synchronized(editData) {
            val result = editData.get(dataKey)
            if (result != null) {
                return if (result is Int) result else defaultValue
            }
        }
        val value: String? =
            ChanDatabase.getInstance().getData(get().name!!, dataKey.boardName, dataKey.name)
        try {
            return value!!.toInt()
        } catch (e: Exception) {
            return defaultValue
        }
    }

    @Public
    fun get(boardName: String?, key: String?, defaultValue: String?): String? {
        if (editData == null) {
            return defaultValue
        }
        val dataKey = ChanDatabase.DataKey(boardName, key)
        synchronized(editData) {
            val result = editData.get(dataKey)
            if (result != null) {
                return if (result is String) result else defaultValue
            }
        }
        val value: String? =
            ChanDatabase.getInstance().getData(get().name!!, dataKey.boardName, dataKey.name)
        return if (value != null) value else defaultValue
    }

    private fun set(boardName: String?, key: String?, value: Any?) {
        if (editData != null) {
            val dataKey = ChanDatabase.DataKey(boardName, key)
            synchronized(editData) {
                editData.put(dataKey, value)
            }
        }
    }

    @Public
    fun set(boardName: String?, key: String?, value: Boolean) {
        set(boardName, key, value as Any?)
    }

    @Public
    fun set(boardName: String?, key: String?, value: Int) {
        set(boardName, key, value as Any?)
    }

    @Public
    fun set(boardName: String?, key: String?, value: String?) {
        set(boardName, key, value as Any?)
    }

    private var title: String? = null

    @Public
    fun getTitle(): String? {
        if (get().name != null) {
            if (title == null) {
                var title: String? = null
                val hosts = get().locator.getChanHosts(false)
                if (hosts.size > 0) {
                    title = hosts.get(0)
                }
                if (title == null) {
                    title = ""
                }
                this.title = title
            }
            return title
        }
        return null
    }

    private fun checkInit() {
        check(!isInitialized) { "This method available only from constructor" }
    }

    private val options = HashSet<String?>()

    @Public
    fun request(option: String?) {
        checkInit()
        options.add(option)
    }

    fun getOption(option: String?): Boolean {
        return options.contains(option)
    }

    private var singleBoardName: String? = null

    @Public
    fun setSingleBoardName(boardName: String?) {
        checkInit()
        singleBoardName = boardName
    }

    fun getSingleBoardName(): String? {
        return singleBoardName
    }

    private var boardTitlesMap: HashMap<String?, String?>? = null
    private var boardDescriptionsMap: HashMap<String?, String?>? = null

    @Public
    fun setBoardTitle(boardName: String?, title: String?) {
        checkInit()
        if (boardTitlesMap == null) {
            boardTitlesMap = HashMap<String?, String?>()
        }
        boardTitlesMap!!.put(boardName, title)
    }

    @Public
    fun storeBoardTitle(boardName: String?, title: String?) {
        set(boardName, KEY_TITLE, title)
    }

    @Public
    fun setBoardDescription(boardName: String?, description: String?) {
        checkInit()
        if (boardDescriptionsMap == null) {
            boardDescriptionsMap = HashMap<String?, String?>()
        }
        boardDescriptionsMap!!.put(boardName, description)
    }

    @Public
    fun storeBoardDescription(boardName: String?, description: String?) {
        set(boardName, KEY_DESCRIPTION, description)
    }

    private val titleFallbackProvider: BoardExtraFallbackProvider =
        BoardExtraFallbackProvider { boardName: String? ->
            if (boardTitlesMap != null) boardTitlesMap!!.get(boardName) else null
        }
    private val descriptionFallbackProvider: BoardExtraFallbackProvider =
        BoardExtraFallbackProvider { boardName: String? ->
            if (boardDescriptionsMap != null) boardDescriptionsMap!!.get(boardName) else null
        }

    fun getBoardTitle(boardName: String?): String? {
        val title = titleFallbackProvider.getExtra(boardName)
        return if (title != null) title else get(boardName, KEY_TITLE, null)
    }

    fun getBoardDescription(boardName: String?): String? {
        val description = descriptionFallbackProvider.getExtra(boardName)
        return if (description != null) description else get(boardName, KEY_DESCRIPTION, null)
    }

    fun getBoards(searchQuery: String?, signal: CancellationSignal?): BoardCursor {
        return ChanDatabase.getInstance()
            .getBoards(get().name!!, searchQuery, KEY_TITLE, titleFallbackProvider, signal)
    }

    fun getUserBoards(
        boardNames: List<String>?,
        searchQuery: String?, signal: CancellationSignal?
    ): BoardCursor {
        return ChanDatabase.getInstance().getBoards(
            get().name!!, boardNames!!, searchQuery,
            KEY_TITLE, KEY_DESCRIPTION, titleFallbackProvider, descriptionFallbackProvider, signal
        )
    }

    private var defaultName: String? = null
    private var defaultNameMap: HashMap<String?, String?>? = null

    @Public
    fun setDefaultName(defaultName: String?) {
        checkInit()
        this.defaultName = defaultName
    }

    @Public
    fun setDefaultName(boardName: String?, defaultName: String?) {
        checkInit()
        if (defaultNameMap == null) {
            defaultNameMap = HashMap<String?, String?>()
        }
        defaultNameMap!!.put(boardName, defaultName)
    }

    @Public
    fun storeDefaultName(boardName: String?, defaultName: String?) {
        set(boardName, KEY_DEFAULT_NAME, defaultName)
    }

    fun getDefaultName(boardName: String?): String? {
        if (defaultNameMap != null) {
            val defaultName = defaultNameMap!!.get(boardName)
            if (defaultName != null) {
                return defaultName
            }
        }
        val defaultName = get(boardName, KEY_DEFAULT_NAME, null)
        if (!isEmpty(defaultName)) {
            return defaultName
        }
        if (!isEmpty(this.defaultName)) {
            return this.defaultName
        }
        return "Anonymous"
    }

    private var bumpLimit: Int = BUMP_LIMIT_INVALID
    private var bumpLimitMap: HashMap<String?, Int?>? = null

    @Public
    fun setBumpLimit(bumpLimit: Int) {
        checkInit()
        this.bumpLimit = bumpLimit
    }

    @Public
    fun setBumpLimit(boardName: String?, bumpLimit: Int) {
        checkInit()
        if (bumpLimitMap == null) {
            bumpLimitMap = HashMap<String?, Int?>()
        }
        bumpLimitMap!!.put(boardName, bumpLimit)
    }

    @Public
    fun storeBumpLimit(boardName: String?, bumpLimit: Int) {
        set(boardName, KEY_BUMP_LIMIT, bumpLimit)
    }

    fun getBumpLimit(boardName: String?): Int {
        var bumpLimit: Int = BUMP_LIMIT_INVALID
        if (bumpLimitMap != null) {
            val bumpLimitValue = bumpLimitMap!!.get(boardName)
            if (bumpLimitValue != null) {
                bumpLimit = bumpLimitValue
            }
        }
        if (bumpLimit == BUMP_LIMIT_INVALID) {
            bumpLimit = get(boardName, KEY_BUMP_LIMIT, BUMP_LIMIT_INVALID)
        }
        if (bumpLimit == BUMP_LIMIT_INVALID) {
            bumpLimit = this.bumpLimit
        }
        return if (bumpLimit > 0) bumpLimit else BUMP_LIMIT_INVALID
    }

    fun getBumpLimitWithMode(boardName: String?): Int {
        var bumpLimit = getBumpLimit(boardName)
        if (bumpLimit != BUMP_LIMIT_INVALID) {
            when (bumpLimitMode) {
                BumpLimitMode.AFTER_POST -> {}
                BumpLimitMode.AFTER_REPLY -> {
                    bumpLimit++
                }

                BumpLimitMode.BEFORE_POST -> {
                    bumpLimit--
                }
            }
        }
        return bumpLimit
    }

    private var bumpLimitMode = BumpLimitMode.AFTER_POST

    @Public
    fun setBumpLimitMode(mode: BumpLimitMode) {
        checkInit()
        if (mode == null) {
            throw NullPointerException()
        }
        bumpLimitMode = mode
    }

    private var pagesCountMap: HashMap<String?, Int?>? = null

    @Public
    fun setPagesCount(boardName: String?, pagesCount: Int) {
        checkInit()
        if (pagesCountMap == null) {
            pagesCountMap = HashMap<String?, Int?>()
        }
        pagesCountMap!!.put(boardName, pagesCount)
    }

    @Public
    fun storePagesCount(boardName: String?, pagesCount: Int) {
        set(boardName, KEY_PAGES_COUNT, pagesCount)
    }

    fun getPagesCount(boardName: String?): Int {
        if (pagesCountMap != null) {
            val pagesCount = pagesCountMap!!.get(boardName)
            if (pagesCount != null) {
                return pagesCount
            }
        }
        return get(boardName, KEY_PAGES_COUNT, PAGES_COUNT_INVALID)
    }

    private var supportedCaptchaTypes: LinkedHashSet<String?>? = null

    @Public
    fun addCaptchaType(captchaType: String) {
        checkInit()
        if (captchaType == null) {
            throw NullPointerException()
        }
        if (supportedCaptchaTypes == null) {
            supportedCaptchaTypes = LinkedHashSet<String?>()
        }
        supportedCaptchaTypes!!.add(captchaType)
    }

    fun getSupportedCaptchaTypes(): MutableCollection<String?>? {
        return supportedCaptchaTypes
    }

    val captchaType: String?
        get() = getCaptchaTypeForChan(get())

    var customPreferences: LinkedHashMap<String?, Boolean?>? = null
        private set

    @Public
    fun addCustomPreference(key: String?, defaultValue: Boolean) {
        if (customPreferences == null) {
            customPreferences = LinkedHashMap<String?, Boolean?>()
        }
        customPreferences!!.put(key, defaultValue)
    }

    @Extendable
    protected open fun obtainBoardConfiguration(boardName: String?): Board? {
        return null
    }

    private fun obtainCaptchaConfigurationSafe(captchaType: String?): Captcha? {
        if (CAPTCHA_TYPE_RECAPTCHA_2 == captchaType) {
            val captcha = Captcha()
            captcha.title = "reCAPTCHA 2"
            captcha.input = Captcha.Input.LATIN
            captcha.validity = Captcha.Validity.SHORT_LIFETIME
            return captcha
        } else if (CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE == captchaType) {
            val captcha = Captcha()
            captcha.title = "reCAPTCHA 2 Invisible"
            captcha.input = Captcha.Input.ALL
            captcha.validity = Captcha.Validity.SHORT_LIFETIME
            return captcha
        } else if (CAPTCHA_TYPE_HCAPTCHA == captchaType) {
            val captcha = Captcha()
            captcha.title = "hCaptcha"
            captcha.input = Captcha.Input.ALL
            captcha.validity = Captcha.Validity.SHORT_LIFETIME
            return captcha
        } else if (captchaType != null) {
            try {
                return obtainCustomCaptchaConfiguration(captchaType)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
        }
        return null
    }

    @Extendable
    protected open fun obtainCustomCaptchaConfiguration(captchaType: String?): Captcha? {
        return null
    }

    @Extendable
    protected open fun obtainPostingConfiguration(boardName: String?, newThread: Boolean): Posting? {
        return null
    }

    @Extendable
    protected open fun obtainDeletingConfiguration(boardName: String?): Deleting? {
        return null
    }

    @Extendable
    protected open fun obtainReportingConfiguration(boardName: String?): Reporting? {
        return null
    }

    @Extendable
    protected open fun obtainVotingConfiguration(boardName: String?): Voting? {
        return null
    }

    @Extendable
    protected open fun obtainCaptchaPassConfiguration(): Authorization {
        val authorization = Authorization()
        authorization.fieldsCount = 1
        authorization.hints = arrayOf<String?>(getInstance().getString(R.string.password))
        return authorization
    }

    @Extendable
    protected open fun obtainUserAuthorizationConfiguration(): Authorization {
        val authorization = Authorization()
        authorization.fieldsCount = 1
        authorization.hints = arrayOf<String?>(getInstance().getString(R.string.password))
        return authorization
    }

    @Extendable
    protected open fun obtainArchivationConfiguration(): Archivation? {
        return null
    }

    @Extendable
    protected open fun obtainStatisticsConfiguration(): Statistics {
        return Statistics()
    }

    @Extendable
    protected open fun obtainCustomPreferenceConfiguration(key: String?): CustomPreference? {
        return null
    }

    @Public
    fun getContext(): Context {
        return getInstance()
    }

    @Public
    fun getResources(): Resources? {
        return resources
    }

    private val resourceUris = SparseArray<Uri?>()

    @Public
    fun getResourceUri(resId: Int): Uri? {
        var uri: Uri?
        synchronized(resourceUris) {
            uri = resourceUris.get(resId)
        }
        if (uri == null) {
            val packageName = resources!!.getResourcePackageName(resId)
            if (get().packageName == packageName) {
                val type = resources.getResourceTypeName(resId)
                val name = resources.getResourceEntryName(resId)
                uri = Uri.parse(SCHEME_CHAN + ":///res/" + type + "/" + name)
                if (uri != null) {
                    synchronized(resourceUris) {
                        resourceUris.put(resId, uri)
                    }
                }
            }
        }
        return uri
    }

    @Throws(IOException::class)
    fun readResourceUri(uri: Uri, output: OutputStream): Boolean {
        val chan = get()
        if (chan.name == null) {
            return false
        }
        val chanName = uri.getAuthority()
        if (!isEmpty(chanName) && chanName != chan.name) {
            return false
        }
        val pathSegments = uri.getPathSegments()
        if (pathSegments == null || pathSegments.size != 3 || ("res" != pathSegments.get(0))) {
            return false
        }
        val type = pathSegments.get(1)
        val name = pathSegments.get(2)
        val id = resources!!.getIdentifier(name, type, chan.packageName)
        if (id == 0) {
            return false
        }
        resources.openRawResource(id).use { input ->
            copyStream(input, output)
            return true
        }
    }

    @Public
    fun getCookie(cookie: String?): String? {
        return if (editData == null || cookie == null)
            null
        else
            ChanDatabase.getInstance().getCookieChecked(get().name!!, cookie)
    }

    @Public
    fun storeCookie(cookie: String, value: String?, displayName: String?) {
        if (editData != null) {
            if (cookie == null) {
                throw NullPointerException("Сookie must not be null")
            }
            ChanDatabase.getInstance().setCookie(
                get().name!!, cookie, value,
                if (isEmptyOrWhitespace(displayName)) null else displayName
            )
        }
    }

    @Public
    fun getUserAuthorizationData(): Array<String?>? {
        @Suppress("UNCHECKED_CAST")
        return CommonUtils.toArray(getUserAuthorizationData(get()), String::class.java as Class<String?>)
    }

    @Public
    fun getDownloadDirectory(): DataFile {
        return obtain(DataFile.Target.DOWNLOADS, null)
    }

    fun updateFromBoards(boardCategories: Array<BoardCategory>) {
        for (boardCategory in boardCategories) {
            updateFromBoards(boardCategory.getBoards())
        }
    }

    fun updateFromBoards(boards: Array<chan.content.model.Board?>?) {
        for (board in boards.orEmpty()) {
            if (board == null) {
                continue
            }
            val boardName = board.getBoardName()
            val title = board.getTitle()
            val description = board.getDescription()
            storeBoardTitle(boardName, title)
            storeBoardDescription(boardName, description)
        }
    }

    class Safe internal constructor(private val configuration: ChanConfiguration) {
        fun obtainBoard(boardName: String?): Board {
            var board: Board? = null
            try {
                board = configuration.obtainBoardConfiguration(boardName)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            if (board == null) {
                board = DEFAULT_BOARD
            }
            return board
        }

        fun obtainCaptcha(captchaType: String?): Captcha {
            var captcha = configuration.obtainCaptchaConfigurationSafe(captchaType)
            if (captcha == null) {
                captcha = DEFAULT_CAPTCHA
            }
            return captcha
        }

        fun obtainPosting(boardName: String?, newThread: Boolean): Posting? {
            var posting: Posting? = null
            try {
                posting = configuration.obtainPostingConfiguration(boardName, newThread)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return posting
        }

        fun obtainDeleting(boardName: String?): Deleting? {
            var deleting: Deleting? = null
            try {
                deleting = configuration.obtainDeletingConfiguration(boardName)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return deleting
        }

        fun obtainReporting(boardName: String?): Reporting? {
            var reporting: Reporting? = null
            try {
                reporting = configuration.obtainReportingConfiguration(boardName)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return reporting
        }

        fun obtainVoting(boardName: String?): Voting? {
            var voting: Voting? = null
            try {
                voting = configuration.obtainVotingConfiguration(boardName)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return voting
        }

        fun obtainCaptchaPass(): Authorization {
            var authorization: Authorization? = null
            try {
                authorization = configuration.obtainCaptchaPassConfiguration()
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            if (authorization == null) {
                authorization = DEFAULT_AUTHORIZATION
            }
            return authorization
        }

        fun obtainUserAuthorization(): Authorization {
            var authorization: Authorization? = null
            try {
                authorization = configuration.obtainUserAuthorizationConfiguration()
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            if (authorization == null) {
                authorization = DEFAULT_AUTHORIZATION
            }
            return authorization
        }

        fun obtainArchivation(): Archivation? {
            var archivation: Archivation? = null
            try {
                archivation = configuration.obtainArchivationConfiguration()
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return archivation
        }

        fun obtainStatistics(): Statistics? {
            var statistics: Statistics? = null
            try {
                statistics = configuration.obtainStatisticsConfiguration()
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return statistics
        }

        fun obtainCustomPreference(key: String?): CustomPreference? {
            var customPreference: CustomPreference? = null
            try {
                customPreference = configuration.obtainCustomPreferenceConfiguration(key)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
            return customPreference
        }

        companion object {
            private val DEFAULT_BOARD = Board()
            private val DEFAULT_CAPTCHA = Captcha()
            private val DEFAULT_AUTHORIZATION = Authorization()

            init {
                DEFAULT_CAPTCHA.validity = Captcha.Validity.LONG_LIFETIME
                DEFAULT_CAPTCHA.input = Captcha.Input.ALL
            }
        }
    }

    private val safe = ChanConfiguration.Safe(this)

    init {
        if (chanProvider == null) {
            val holder: ChanManager.Initializer.Holder = INITIALIZER.consume()
            this.chanProvider = holder.chanProvider
            resources = holder.resources
            editData = HashMap<ChanDatabase.DataKey?, Any?>()
        } else {
            this.chanProvider = chanProvider
            resources = null
            editData = null
        }
    }

    fun safe(): Safe {
        return safe
    }

    companion object {
        val INITIALIZER: ChanManager.Initializer = ChanManager.Initializer()

        const val SCHEME_CHAN: String = "chan"

        @Public
        const val OPTION_SINGLE_BOARD_MODE: String = "single_board_mode"

        @Public
        const val OPTION_READ_THREAD_PARTIALLY: String = "read_thread_partially"

        @Public
        const val OPTION_READ_SINGLE_POST: String = "read_single_post"

        @Public
        const val OPTION_READ_POSTS_COUNT: String = "read_posts_count"

        @Public
        const val OPTION_READ_USER_BOARDS: String = "read_user_boards"

        @Public
        const val OPTION_ALLOW_CAPTCHA_PASS: String = "allow_captcha_pass"

        @Public
        const val OPTION_ALLOW_USER_AUTHORIZATION: String = "allow_user_authorization"

        @Public
        const val OPTION_LOCAL_MODE: String = "local_mode"

        @Public
        const val OPTION_AI_POSTING: String = "ai_posting"

        private const val KEY_TITLE = "title"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_DEFAULT_NAME = "default_name"
        private const val KEY_BUMP_LIMIT = "bump_limit"
        private const val KEY_PAGES_COUNT = "pages_count"

        val PAGES_COUNT_INVALID: Int = Int.MAX_VALUE
        val BUMP_LIMIT_INVALID: Int = Int.MAX_VALUE

        @Public
        const val CAPTCHA_TYPE_RECAPTCHA_2: String = "recaptcha_2"

        @Public
        const val CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE: String = "recaptcha_2_invisible"

        @Public
        const val CAPTCHA_TYPE_HCAPTCHA: String = "hcaptcha"

        @Public
        @JvmStatic
        fun get(`object`: Any): ChanConfiguration {
            return (`object` as Chan.Linked).get().configuration
        }
    }
}
