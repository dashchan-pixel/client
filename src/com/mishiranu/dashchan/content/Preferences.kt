package com.mishiranu.dashchan.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanManager
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.isEmptyOrWhitespace
import chan.util.StringUtils.nullIfEmpty
import chan.util.StringUtils.validateBoardName
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ClickableToast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Arrays
import java.util.Collections
import java.util.Random
import kotlin.math.max
import kotlin.math.min

object Preferences {
    private const val PREFERENCES_NAME = "preferences"
    private const val PREFERENCES_RESTORE_NAME = "preferences.restore"

    @JvmField
    val PREFERENCES: SharedPreferences? =
        run {
            val application = MainApplication.getInstance()
            if (application.isMainProcess()) {
                val newFile = getPreferencesFile(application, PREFERENCES_NAME)
                val newBackupFile = File(newFile.getParentFile(), newFile.getName() + ".bak")
                if (!newFile.exists() && !newBackupFile.exists()) {
                    // Rename preferences file
                    val oldFile =
                        getPreferencesFile(application, application.getPackageName() + "_preferences")
                    val oldBackupFile = File(oldFile.getParentFile(), oldFile.getName() + ".bak")
                    if (oldBackupFile.exists()) {
                        oldBackupFile.renameTo(newBackupFile)
                    }
                    if (oldFile.exists()) {
                        oldFile.renameTo(newFile)
                    }
                }
                val restoreFile = getPreferencesFile(application, PREFERENCES_RESTORE_NAME)
                if (restoreFile.exists()) {
                    newBackupFile.delete()
                    restoreFile.renameTo(newFile)
                }
                SharedPreferences(application, PREFERENCES_NAME)
            } else {
                null
            }
        }

    /**
     * [PREFERENCES] as seen by the main process, where every getter and setter below is used.
     *
     * [PREFERENCES] is null only in the `:webview` process (see the `WebViewService` entry in the
     * manifest), which never touches preferences. The four `PREFERENCES != null` guards on the
     * migration `init` blocks are the only code in this object that runs in *both* processes, and
     * they are the reason the field itself has to stay nullable.
     *
     * Also the accessor for the `PreferenceFragment.getPreferences` implementors, which all run in
     * the main process: it gives them this reason instead of a bare non-null assertion.
     */
    val prefs: SharedPreferences
        get() =
            checkNotNull(PREFERENCES) {
                "Preferences are unavailable outside the main process"
            }

    private const val SPECIAL_CHAN_NAME_GENERAL = "general"
    private const val SPECIAL_CHAN_NAME_CLOUDFLARE = "cloudflare"

    @JvmField
    val SPECIAL_EXTENSION_NAMES: Array<String?> =
        arrayOf<String?>(
            SPECIAL_CHAN_NAME_GENERAL,
            SPECIAL_CHAN_NAME_CLOUDFLARE,
        )

    private fun getPreferencesFile(
        application: MainApplication,
        name: String?,
    ): File = File(application.getSharedPrefsDir(), name + ".xml")

    val filesForBackup: android.util.Pair<File, File>
        get() {
            val preferences =
                getPreferencesFile(
                    MainApplication.getInstance(),
                    PREFERENCES_NAME,
                )
            val restore =
                getPreferencesFile(
                    MainApplication.getInstance(),
                    PREFERENCES_RESTORE_NAME,
                )
            return android.util.Pair(preferences, restore)
        }

    val fileForRestore: File
        get() =
            getPreferencesFile(
                MainApplication.getInstance(),
                PREFERENCES_RESTORE_NAME,
            )

    @JvmStatic
    fun unpackOrCastMultipleValues(
        value: String?,
        count: Int,
    ): MutableList<String?> {
        val values = java.util.ArrayList<String?>(count)
        for (i in 0..<count) {
            values.add(null)
        }
        if (value != null) {
            try {
                val jsonArray = JSONArray(value)
                val length = min(count, jsonArray.length())
                for (i in 0..<length) {
                    values[i] =
                        if (jsonArray.isNull(i)) null else nullIfEmpty(jsonArray.getString(i))
                }
                return values
            } catch (e: JSONException) {
                // Backward compatibility
                if (value.length > 0 && !value.startsWith("[")) {
                    values[0] = value
                }
            }
        }
        return values
    }

    fun unpackOrCastMultipleValues(
        value: String?,
        keys: List<String>,
    ): MutableMap<String?, String?> {
        val values = HashMap<String?, String?>(keys.size)
        if (value != null) {
            try {
                val jsonObject = JSONObject(value)
                for (key in keys) {
                    val stringValue = jsonObject.optString(key)
                    if (!isEmpty(stringValue)) {
                        values[key] = stringValue
                    }
                }
            } catch (e: JSONException) {
                // Migration
                val list = unpackOrCastMultipleValues(value, keys.size)
                if (list.size == keys.size) {
                    var i = 0
                    while (i < keys.size) {
                        values[keys[i]] = list[i]
                        i++
                    }
                }
            }
        }
        return values
    }

    fun checkHasMultipleValues(values: List<String?>?): Boolean {
        var hasValues = false
        if (values != null) {
            for (value in values) {
                if (value != null) {
                    hasValues = true
                    break
                }
            }
        }
        return hasValues
    }

    private fun <T : Enum<T>> getEnumValue(
        key: String,
        values: Array<T>,
        defaultValue: T?,
        enumValueProvider: EnumValueProvider<T>,
    ): T? {
        val stringValue = prefs.getString(key, enumValueProvider.getValue(defaultValue))
        for (value in values) {
            if (enumValueProvider.getValue(value) == stringValue) {
                return value
            }
        }
        return defaultValue
    }

    // getEnumValue falls back to defaultValue, so with a non-null default this can never be null;
    // the `?: defaultValue` only re-states that for the compiler (same idiom as downloadSubdirMode).
    private fun getNetworkModeGeneric(
        key: String,
        defaultValue: NetworkMode,
    ): NetworkMode =
        getEnumValue(
            key,
            NetworkMode.entries.toTypedArray(),
            defaultValue,
            NetworkMode.Companion.VALUE_PROVIDER,
        ) ?: defaultValue

    val KEY_HIDE_AI_POSTS: ChanKey = ChanKey("hide_ai_posts")
    const val DEFAULT_HIDE_AI_POSTS: Boolean = false

    fun isHideAIPosts(chan: Chan): Boolean {
        if (chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING)) {
            return prefs.getBoolean(
                KEY_HIDE_AI_POSTS.bind(chan.name),
                DEFAULT_HIDE_AI_POSTS,
            )
        } else {
            return false
        }
    }

    const val KEY_ACTIVE_SCROLLBAR: String = "active_scrollbar"
    const val DEFAULT_ACTIVE_SCROLLBAR: Boolean = true

    @JvmStatic
    val isActiveScrollbar: Boolean
        get() =
            prefs.getBoolean(
                KEY_ACTIVE_SCROLLBAR,
                DEFAULT_ACTIVE_SCROLLBAR,
            )

    const val KEY_HIGHLIGHT_USER_POSTS: String = "highlight_user_posts"
    const val DEFAULT_HIGHLIGHT_USER_POSTS: Boolean = true

    @JvmStatic
    val isHighlightUserPosts: Boolean
        get() =
            prefs.getBoolean(
                KEY_HIGHLIGHT_USER_POSTS,
                DEFAULT_HIGHLIGHT_USER_POSTS,
            )

    const val KEY_ADVANCED_SEARCH: String = "advanced_search"
    const val DEFAULT_ADVANCED_SEARCH: Boolean = false

    @JvmStatic
    val isAdvancedSearch: Boolean
        get() =
            prefs.getBoolean(
                KEY_ADVANCED_SEARCH,
                DEFAULT_ADVANCED_SEARCH,
            )

    const val KEY_ALL_ATTACHMENTS: String = "all_attachments"
    const val DEFAULT_ALL_ATTACHMENTS: Boolean = false

    @JvmStatic
    val isAllAttachments: Boolean
        get() =
            prefs.getBoolean(
                KEY_ALL_ATTACHMENTS,
                DEFAULT_ALL_ATTACHMENTS,
            )

    const val KEY_AUTO_REFRESH_INTERVAL: String = "auto_refresh_interval"
    const val DISABLED_AUTO_REFRESH_INTERVAL: Int = 0
    const val MIN_AUTO_REFRESH_INTERVAL: Int = 15
    const val MAX_AUTO_REFRESH_INTERVAL: Int = 90
    const val STEP_AUTO_REFRESH_INTERVAL: Int = 5
    val DEFAULT_AUTO_REFRESH_INTERVAL: Int = DISABLED_AUTO_REFRESH_INTERVAL

    @JvmStatic
    val autoRefreshInterval: Int
        get() {
            val value =
                prefs.getInt(
                    KEY_AUTO_REFRESH_INTERVAL,
                    DEFAULT_AUTO_REFRESH_INTERVAL,
                )
            return if (value > MAX_AUTO_REFRESH_INTERVAL) {
                MAX_AUTO_REFRESH_INTERVAL
            } else {
                if (value < MIN_AUTO_REFRESH_INTERVAL) {
                    DISABLED_AUTO_REFRESH_INTERVAL
                } else {
                    value
                }
            }
        }

    init {
        if (PREFERENCES != null) {
            val key = "auto_refresh_mode"
            val value = PREFERENCES.getString(key, null)
            if (value != null) {
                val enabled = "enabled" == value
                PREFERENCES.edit().use { editor ->
                    editor.remove(value)
                    if (!enabled) {
                        editor.put(KEY_AUTO_REFRESH_INTERVAL, DISABLED_AUTO_REFRESH_INTERVAL)
                    }
                }
            }
        }
    }

    const val KEY_CACHE_SIZE: String = "cache_size"
    const val MIN_CACHE_SIZE: Int = 100
    const val MAX_CACHE_SIZE: Int = 4000
    const val STEP_CACHE_SIZE: Int = 50
    const val DEFAULT_CACHE_SIZE: Int = 1000

    val cacheSize: Int
        get() =
            prefs.getInt(
                KEY_CACHE_SIZE,
                DEFAULT_CACHE_SIZE,
            )

    val KEY_CAPTCHA: ChanKey = ChanKey("captcha")
    private const val VALUE_CAPTCHA_START = "captcha_"

    @JvmStatic
    fun getCaptchaTypeForChan(chan: Chan): String? {
        val supportedCaptchaTypes = chan.configuration.getSupportedCaptchaTypes()
        if (supportedCaptchaTypes.isNullOrEmpty()) {
            return null
        }
        val defaultCaptchaType = supportedCaptchaTypes.iterator().next()
        val captchaTypeValue =
            prefs.getString(
                KEY_CAPTCHA.bind(chan.name),
                transformCaptchaTypeToValue(defaultCaptchaType),
            )
        val captchaType: String?
        if (captchaTypeValue != null && captchaTypeValue.startsWith(VALUE_CAPTCHA_START)) {
            captchaType = captchaTypeValue.substring(VALUE_CAPTCHA_START.length)
            for (supportedCaptchaType in supportedCaptchaTypes) {
                if (supportedCaptchaType == captchaType) {
                    return captchaType
                }
            }
        }
        return defaultCaptchaType
    }

    fun getCaptchaTypeValues(captchaTypes: Collection<String?>): MutableList<String> {
        val values = java.util.ArrayList<String>()
        for (captchaType in captchaTypes) {
            values.add(transformCaptchaTypeToValue(captchaType))
        }
        return values
    }

    fun getCaptchaTypeEntries(
        chan: Chan,
        captchaTypes: Collection<String?>,
    ): MutableList<CharSequence> {
        val entries = java.util.ArrayList<CharSequence>()
        for (captchaType in captchaTypes) {
            entries.add(
                chan.configuration
                    .safe()
                    .obtainCaptcha(captchaType)
                    .title!!,
            )
        }
        return entries
    }

    fun getCaptchaTypeDefaultValue(chan: Chan): String? {
        val supportedCaptchaTypes = chan.configuration.getSupportedCaptchaTypes()
        if (supportedCaptchaTypes.isNullOrEmpty()) {
            return null
        }
        return transformCaptchaTypeToValue(supportedCaptchaTypes.iterator().next())
    }

    private fun transformCaptchaTypeToValue(captchaType: String?): String = VALUE_CAPTCHA_START + captchaType

    val KEY_CAPTCHA_PASS: ChanKey = ChanKey("captcha_pass")

    @JvmStatic
    fun getCaptchaPass(chan: Chan): List<String>? {
        val authorization = chan.configuration.safe().obtainCaptchaPass()
        if (authorization.fieldsCount > 0) {
            val value = prefs.getString(KEY_CAPTCHA_PASS.bind(chan.name), null)
            @Suppress("UNCHECKED_CAST")
            return unpackOrCastMultipleValues(value, authorization.fieldsCount) as List<String>?
        } else {
            return null
        }
    }

    const val KEY_CAPTCHA_SOLVING: String = "captcha_solving"
    const val SUB_KEY_CAPTCHA_SOLVING_ENDPOINT: String = "endpoint"
    const val SUB_KEY_CAPTCHA_SOLVING_TOKEN: String = "token"
    const val SUB_KEY_CAPTCHA_SOLVING_TIMEOUT: String = "timeout"
    val KEYS_CAPTCHA_SOLVING: List<String> =
        listOf(
            SUB_KEY_CAPTCHA_SOLVING_ENDPOINT,
            SUB_KEY_CAPTCHA_SOLVING_TOKEN,
            SUB_KEY_CAPTCHA_SOLVING_TIMEOUT,
        )

    val captchaSolving: MutableMap<String?, String?>
        get() {
            val value =
                prefs.getString(
                    KEY_CAPTCHA_SOLVING,
                    null,
                )
            return unpackOrCastMultipleValues(
                value,
                KEYS_CAPTCHA_SOLVING,
            )
        }

    const val KEY_CAPTCHA_SOLVING_CHANS: String = "captcha_solving_chans"

    var captchaSolvingChans: MutableCollection<String>
        get() {
            val value =
                prefs.getString(
                    KEY_CAPTCHA_SOLVING_CHANS,
                    null,
                )
            if (isEmpty(value)) {
                return mutableSetOf()
            }
            try {
                val jsonArray = JSONArray(value)
                val chanNames = HashSet<String>(jsonArray.length())
                for (i in 0..<jsonArray.length()) {
                    val chanName = jsonArray.optString(i)
                    if (!isEmpty(chanName)) {
                        chanNames.add(chanName)
                    }
                }
                return chanNames
            } catch (e: JSONException) {
                return mutableSetOf()
            }
        }
        set(chanNames) {
            if (chanNames.isEmpty()) {
                prefs
                    .edit()
                    .remove(KEY_CAPTCHA_SOLVING_CHANS)
                    .close()
            } else {
                val jsonArray = JSONArray()
                for (chanName in chanNames) {
                    jsonArray.put(chanName)
                }
                prefs
                    .edit()
                    .put(
                        KEY_CAPTCHA_SOLVING_CHANS,
                        jsonArray.toString(),
                    ).close()
            }
        }

    const val KEY_CATALOG_SORT: String = "catalog_sort"
    val DEFAULT_CATALOG_SORT: CatalogSort = CatalogSort.UNSORTED

    var catalogSort: CatalogSort?
        get() =
            getEnumValue(
                KEY_CATALOG_SORT,
                CatalogSort.entries.toTypedArray(),
                DEFAULT_CATALOG_SORT,
                CatalogSort.Companion.VALUE_PROVIDER,
            )
        set(catalogSort) {
            prefs
                .edit()
                .put(
                    KEY_CATALOG_SORT,
                    if (catalogSort != null) catalogSort.value else null,
                ).close()
        }

    const val KEY_CHANS_ORDER: String = "chans_order"

    @JvmStatic
    var chansOrder: ArrayList<String?>?
        get() {
            val data =
                PREFERENCES!!.getString(
                    KEY_CHANS_ORDER,
                    null,
                )
            if (data != null) {
                try {
                    val jsonArray = JSONArray(data)
                    val chanNames =
                        java.util.ArrayList<String?>()
                    for (i in 0..<jsonArray.length()) {
                        chanNames.add(jsonArray.getString(i))
                    }
                    return chanNames
                } catch (e: JSONException) {
                    // Invalid or unspecified data, ignore exception
                }
            }
            return null
        }
        set(chanNames) {
            val jsonArray = JSONArray()
            for (chanName in chanNames!!) {
                jsonArray.put(chanName)
            }
            PREFERENCES!!
                .edit()
                .put(
                    KEY_CHANS_ORDER,
                    jsonArray.toString(),
                ).close()
        }

    const val KEY_CHECK_UPDATES_ON_START: String = "check_updates_on_start"
    const val DEFAULT_CHECK_UPDATES_ON_START: Boolean = true

    @JvmStatic
    var isCheckUpdatesOnStart: Boolean
        get() =
            prefs.getBoolean(
                KEY_CHECK_UPDATES_ON_START,
                DEFAULT_CHECK_UPDATES_ON_START,
            )
        set(checkUpdatesOnStart) {
            prefs
                .edit()
                .put(
                    KEY_CHECK_UPDATES_ON_START,
                    checkUpdatesOnStart,
                ).close()
        }

    const val KEY_CLOSE_ON_BACK: String = "close_on_back"
    const val DEFAULT_CLOSE_ON_BACK: Boolean = false

    @JvmStatic
    val isCloseOnBack: Boolean
        get() =
            prefs.getBoolean(
                KEY_CLOSE_ON_BACK,
                DEFAULT_CLOSE_ON_BACK,
            )

    const val KEY_CUT_THUMBNAILS: String = "cut_thumbnails"
    const val DEFAULT_CUT_THUMBNAILS: Boolean = true

    @JvmStatic
    val isCutThumbnails: Boolean
        get() =
            prefs.getBoolean(
                KEY_CUT_THUMBNAILS,
                DEFAULT_CUT_THUMBNAILS,
            )

    const val KEY_CYCLICAL_REFRESH: String = "cyclical_refresh"
    val DEFAULT_CYCLICAL_REFRESH: CyclicalRefreshMode = CyclicalRefreshMode.DEFAULT

    @JvmStatic
    val cyclicalRefreshMode: CyclicalRefreshMode?
        get() =
            getEnumValue(
                KEY_CYCLICAL_REFRESH,
                CyclicalRefreshMode.entries.toTypedArray(),
                DEFAULT_CYCLICAL_REFRESH,
                CyclicalRefreshMode.Companion.VALUE_PROVIDER,
            )

    val KEY_DEFAULT_BOARD_NAME: ChanKey = ChanKey("default_board_name")

    @JvmStatic
    fun getDefaultBoardName(chan: Chan): String? =
        if (chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
            chan.configuration.getSingleBoardName()
        } else {
            validateBoardName(
                prefs.getString(
                    KEY_DEFAULT_BOARD_NAME.bind(
                        chan.name,
                    ),
                    null,
                ),
            )
        }

    fun setDefaultBoardName(
        chanName: String?,
        boardName: String?,
    ) {
        prefs.edit().put(KEY_DEFAULT_BOARD_NAME.bind(chanName), boardName).close()
    }

    const val KEY_DISPLAY_HIDDEN_THREADS: String = "display_hidden_threads"
    const val DEFAULT_DISPLAY_HIDDEN_THREADS: Boolean = true

    @JvmStatic
    val isDisplayHiddenThreads: Boolean
        get() =
            prefs.getBoolean(
                KEY_DISPLAY_HIDDEN_THREADS,
                DEFAULT_DISPLAY_HIDDEN_THREADS,
            )

    const val KEY_DISPLAY_ICONS: String = "display_icons"
    const val DEFAULT_DISPLAY_ICONS: Boolean = true

    @JvmStatic
    val isDisplayIcons: Boolean
        get() =
            prefs.getBoolean(
                KEY_DISPLAY_ICONS,
                DEFAULT_DISPLAY_ICONS,
            )

    val KEY_DOMAIN: ChanKey = ChanKey("domain")

    @JvmStatic
    fun getDomainUnhandled(chan: Chan): String? = prefs.getString(KEY_DOMAIN.bind(chan.name), "")

    @JvmStatic
    fun setDomainUnhandled(
        chan: Chan,
        domain: String?,
    ) {
        prefs.edit().put(KEY_DOMAIN.bind(chan.name), domain).close()
    }

    const val KEY_DOWNLOAD_DETAIL_NAME: String = "download_detail_name"
    const val DEFAULT_DOWNLOAD_DETAIL_NAME: Boolean = false

    @JvmStatic
    val isDownloadDetailName: Boolean
        get() =
            prefs.getBoolean(
                KEY_DOWNLOAD_DETAIL_NAME,
                DEFAULT_DOWNLOAD_DETAIL_NAME,
            )

    const val KEY_DOWNLOAD_ORIGINAL_NAME: String = "download_original_name"
    const val DEFAULT_DOWNLOAD_ORIGINAL_NAME: Boolean = false

    @JvmStatic
    val isDownloadOriginalName: Boolean
        get() =
            prefs.getBoolean(
                KEY_DOWNLOAD_ORIGINAL_NAME,
                DEFAULT_DOWNLOAD_ORIGINAL_NAME,
            )

    const val KEY_DOWNLOAD_PATH: String = "download_path"

    // Never null: falls back to the default path, exactly as the Java did.
    private val downloadPathLegacy: String
        get() {
            val path =
                prefs.getString(
                    KEY_DOWNLOAD_PATH,
                    null,
                )
            return path?.takeIf { !isEmptyOrWhitespace(it) } ?: C.DEFAULT_DOWNLOAD_PATH
        }

    private var externalStorageDirectory: File? = null

    @get:Suppress("deprecation")
    val downloadDirectoryLegacy: File
        // Environment.getExternalStorageDirectory has no replacement for resolving the legacy
        get() {
            val path: String = downloadPathLegacy
            var dir = File(path)
            var absolute = false
            val uri = Uri.fromFile(dir)
            val pathSegments = uri.getPathSegments()
            if (pathSegments.size > 0) {
                val first = File("/" + uri.getPathSegments()[0])
                if (first.exists() && first.isDirectory()) {
                    absolute = true
                }
            }
            if (!absolute) {
                var file =
                    externalStorageDirectory
                if (file == null) {
                    // Cache for faster calls
                    file = Environment.getExternalStorageDirectory()
                    externalStorageDirectory = file
                }
                dir = File(file, path)
            }
            dir.mkdirs()
            return dir
        }

    @JvmStatic
    fun getDownloadUriTree(context: Context): Uri? {
        val contentResolver = context.getContentResolver()
        // getPersistedUriPermissions() is declared @NonNull by the framework.
        val uriPermissions = contentResolver.getPersistedUriPermissions()
        for (uriPermission in uriPermissions) {
            if (uriPermission.isReadPermission() && uriPermission.isWritePermission()) {
                val treeUri = uriPermission.getUri()
                val uri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    )
                try {
                    contentResolver.query(uri, null, null, null, null).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            return treeUri
                        }
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    @JvmStatic
    fun setDownloadUriTree(
        context: Context,
        uri: Uri?,
        uriFlags: Int,
    ) {
        val contentResolver = context.getContentResolver()
        for (uriPermission in contentResolver.getPersistedUriPermissions()) {
            if (uri == null || uri != uriPermission.getUri()) {
                val flags =
                    (if (uriPermission.isReadPermission()) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (uriPermission.isWritePermission()) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                contentResolver.releasePersistableUriPermission(uriPermission.getUri(), flags)
            }
        }
        if (uri == null || "com.android.providers.downloads.documents" == uri.getAuthority()) {
            // Downloads provider fails when ".nomedia" files present
            ClickableToast.show(R.string.no_access_to_memory)
        } else {
            contentResolver.takePersistableUriPermission(
                uri,
                uriFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            )
        }
    }

    const val KEY_DOWNLOAD_SUBDIR: String = "download_subdir"
    val DEFAULT_DOWNLOAD_SUBDIR: DownloadSubdirMode = DownloadSubdirMode.DISABLED

    @JvmStatic
    val downloadSubdirMode: DownloadSubdirMode
        get() =
            getEnumValue(
                KEY_DOWNLOAD_SUBDIR,
                DownloadSubdirMode.entries.toTypedArray(),
                DEFAULT_DOWNLOAD_SUBDIR,
                DownloadSubdirMode.Companion.VALUE_PROVIDER,
            ) ?: DEFAULT_DOWNLOAD_SUBDIR

    const val KEY_DRAWER_INITIAL_POSITION: String = "drawer_initial_position"
    val DEFAULT_DRAWER_INITIAL_POSITION: DrawerInitialPosition = DrawerInitialPosition.CLOSED

    @JvmStatic
    val drawerInitialPosition: DrawerInitialPosition?
        get() =
            getEnumValue(
                KEY_DRAWER_INITIAL_POSITION,
                DrawerInitialPosition.entries.toTypedArray(),
                DEFAULT_DRAWER_INITIAL_POSITION,
                DrawerInitialPosition.VALUE_PROVIDER,
            )

    const val KEY_EXPANDED_SCREEN: String = "expanded_screen"
    const val DEFAULT_EXPANDED_SCREEN: Boolean = false

    @JvmStatic
    var isExpandedScreen: Boolean
        get() =
            prefs.getBoolean(
                KEY_EXPANDED_SCREEN,
                DEFAULT_EXPANDED_SCREEN,
            )
        set(expandedScreen) {
            prefs
                .edit()
                .put(KEY_EXPANDED_SCREEN, expandedScreen)
                .close()
        }

    const val KEY_FAVORITE_ON_REPLY: String = "favorite_on_reply"
    val DEFAULT_FAVORITE_ON_REPLY: FavoriteOnReplyMode = FavoriteOnReplyMode.DISABLED

    // getEnumValue falls back to defaultValue, so with a non-null default this can never be null;
    // the `?: DEFAULT_FAVORITE_ON_REPLY` only re-states that for the compiler.
    @JvmStatic
    val favoriteOnReply: FavoriteOnReplyMode
        get() =
            getEnumValue(
                KEY_FAVORITE_ON_REPLY,
                FavoriteOnReplyMode.entries.toTypedArray(),
                DEFAULT_FAVORITE_ON_REPLY,
                FavoriteOnReplyMode.Companion.VALUE_PROVIDER,
            ) ?: DEFAULT_FAVORITE_ON_REPLY

    init {
        if (PREFERENCES != null) {
            val key = "favorite_on_reply"
            val value = PREFERENCES.getAll()[key]
            if (value is Boolean) {
                val favoriteOnReplyMode =
                    if (value) {
                        FavoriteOnReplyMode.ENABLED
                    } else {
                        FavoriteOnReplyMode.DISABLED
                    }
                PREFERENCES
                    .edit()
                    .remove(key)
                    .put(KEY_FAVORITE_ON_REPLY, favoriteOnReplyMode.value)
                    .close()
            }
        }
    }

    const val KEY_FAVORITES_ORDER: String = "favorites_order"
    val DEFAULT_FAVORITES_ORDER: FavoritesOrder = FavoritesOrder.DATE_DESC

    val favoritesOrder: FavoritesOrder?
        get() =
            getEnumValue(
                KEY_FAVORITES_ORDER,
                FavoritesOrder.entries.toTypedArray(),
                DEFAULT_FAVORITES_ORDER,
                FavoritesOrder.Companion.VALUE_PROVIDER,
            )

    const val KEY_FAVORITES_HIDED_ALL: String = "favorites_hided_all"
    const val DEFAULT_FAVORITES_HIDED_ALL: Boolean = false

    @JvmStatic
    val isFavoritesHidedAll: Boolean
        get() =
            prefs.getBoolean(
                KEY_FAVORITES_HIDED_ALL,
                DEFAULT_FAVORITES_HIDED_ALL,
            )

    @JvmStatic
    fun setFavoritesHideAll(flag: Boolean) {
        prefs.edit().put(KEY_FAVORITES_HIDED_ALL, flag).close()
    }

    const val KEY_FAVORITES_HIDED_DELETED: String = "favorites_hided_deleted"
    const val DEFAULT_FAVORITES_HIDED_DELETED: Boolean = false

    @JvmStatic
    val isFavoritesHidedDeleted: Boolean
        get() =
            prefs.getBoolean(
                KEY_FAVORITES_HIDED_DELETED,
                DEFAULT_FAVORITES_HIDED_DELETED,
            )

    @JvmStatic
    fun setFavoritesHideDeleted(flag: Boolean) {
        prefs.edit().put(KEY_FAVORITES_HIDED_DELETED, flag).close()
    }

    const val KEY_HIDE_PERSONAL_DATA: String = "hide_personal_data"
    const val DEFAULT_HIDE_PERSONAL_DATA: Boolean = false

    @JvmStatic
    val isHidePersonalData: Boolean
        get() =
            prefs.getBoolean(
                KEY_HIDE_PERSONAL_DATA,
                DEFAULT_HIDE_PERSONAL_DATA,
            )

    const val KEY_HIGHLIGHT_UNREAD: String = "highlight_unread_posts"
    val DEFAULT_HIGHLIGHT_UNREAD: HighlightUnreadMode = HighlightUnreadMode.AUTOMATICALLY

    @JvmStatic
    val highlightUnreadMode: HighlightUnreadMode?
        get() =
            getEnumValue(
                KEY_HIGHLIGHT_UNREAD,
                HighlightUnreadMode.entries.toTypedArray(),
                DEFAULT_HIGHLIGHT_UNREAD,
                HighlightUnreadMode.Companion.VALUE_PROVIDER,
            )

    const val KEY_HUGE_CAPTCHA: String = "huge_captcha"
    const val DEFAULT_HUGE_CAPTCHA: Boolean = true

    @JvmStatic
    val isHugeCaptcha: Boolean
        get() =
            prefs.getBoolean(
                KEY_HUGE_CAPTCHA,
                DEFAULT_HUGE_CAPTCHA,
            )

    const val KEY_CAPTCHA_TIMER: String = "captcha_timer"
    const val DEFAULT_CAPTCHA_TIMER: Boolean = true

    @JvmStatic
    val isCaptchaTimer: Boolean
        get() =
            prefs.getBoolean(
                KEY_CAPTCHA_TIMER,
                DEFAULT_CAPTCHA_TIMER,
            )

    const val KEY_CAPTCHA_AUTO_RELOAD: String = "captcha_auto_reload"
    const val DEFAULT_CAPTCHA_AUTO_RELOAD: Boolean = true

    @JvmStatic
    val isCaptchaAutoReload: Boolean
        get() =
            prefs.getBoolean(
                KEY_CAPTCHA_AUTO_RELOAD,
                DEFAULT_CAPTCHA_AUTO_RELOAD,
            )

    const val KEY_INTERNAL_BROWSER: String = "internal_browser"
    const val DEFAULT_INTERNAL_BROWSER: Boolean = true

    @JvmStatic
    val isUseInternalBrowser: Boolean
        get() =
            prefs.getBoolean(
                KEY_INTERNAL_BROWSER,
                DEFAULT_INTERNAL_BROWSER,
            )

    const val KEY_EPHEMERAL_BROWSING: String = "ephemeral_browsing"
    const val DEFAULT_EPHEMERAL_BROWSING: Boolean = true

    @JvmStatic
    val isEphemeralBrowsing: Boolean
        get() =
            prefs.getBoolean(
                KEY_EPHEMERAL_BROWSING,
                DEFAULT_EPHEMERAL_BROWSING,
            )

    const val KEY_LAST_UPDATE_CHECK: String = "last_update_check"

    @JvmStatic
    var lastUpdateCheck: Long
        get() =
            prefs.getLong(
                KEY_LAST_UPDATE_CHECK,
                0L,
            )
        set(lastUpdateCheck) {
            prefs
                .edit()
                .put(
                    KEY_LAST_UPDATE_CHECK,
                    lastUpdateCheck,
                ).close()
        }

    val KEY_LOAD_CATALOG: ChanKey = ChanKey("load_catalog")
    const val DEFAULT_LOAD_CATALOG: Boolean = false

    fun isLoadCatalog(chan: Chan): Boolean = prefs.getBoolean(KEY_LOAD_CATALOG.bind(chan.name), DEFAULT_LOAD_CATALOG)

    const val KEY_LOAD_NEAREST_IMAGE: String = "load_nearest_image"
    val DEFAULT_LOAD_NEAREST_IMAGE: NetworkMode = NetworkMode.NEVER

    @JvmStatic
    val loadNearestImage: NetworkMode
        get() =
            getNetworkModeGeneric(
                KEY_LOAD_NEAREST_IMAGE,
                DEFAULT_LOAD_NEAREST_IMAGE,
            )

    const val KEY_LOAD_THUMBNAILS: String = "load_thumbnails"
    val DEFAULT_LOAD_THUMBNAILS: NetworkMode = NetworkMode.ALWAYS

    @JvmStatic
    val loadThumbnails: NetworkMode
        get() =
            getNetworkModeGeneric(
                KEY_LOAD_THUMBNAILS,
                DEFAULT_LOAD_THUMBNAILS,
            )

    const val KEY_LOCALE: String = "locale"

    val locale: String?
        get() =
            prefs.getString(
                KEY_LOCALE,
                LocaleManager.DEFAULT_LOCALE,
            )

    // Repository sources. An empty stored value means "use the built-in default" (BuildConfig).
    const val KEY_URI_UPDATES: String = "uri_updates"
    const val KEY_URI_UPDATES_EXTENSIONS: String = "uri_updates_extensions"
    const val KEY_URI_THEMES: String = "uri_themes"

    private fun getRepositoryUri(
        key: String,
        defaultValue: String,
    ): String {
        val value = prefs.getString(key, "")
        return if (value != null && !value.isEmpty()) value else defaultValue
    }

    val uriUpdates: String
        get() =
            getRepositoryUri(
                KEY_URI_UPDATES,
                BuildConfig.URI_UPDATES,
            )

    val uriUpdatesExtensions: MutableList<String?>
        // The stored value is a whitespace-separated list, so updates from any number of
        get() {
            val value =
                getRepositoryUri(
                    KEY_URI_UPDATES_EXTENSIONS,
                    BuildConfig.URI_UPDATES_EXTENSIONS,
                )
            val uris = java.util.ArrayList<String?>()
            for (uri in value
                .split("\\s+".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()) {
                if (!uri.isEmpty()) {
                    uris.add(uri)
                }
            }
            return uris
        }

    val uriThemes: String
        get() =
            getRepositoryUri(
                KEY_URI_THEMES,
                BuildConfig.URI_THEMES,
            )

    const val KEY_LOCK_DRAWER: String = "lock_drawer"
    const val DEFAULT_LOCK_DRAWER: Boolean = false

    @JvmStatic
    var isDrawerLocked: Boolean
        get() =
            prefs.getBoolean(
                KEY_LOCK_DRAWER,
                DEFAULT_LOCK_DRAWER,
            )
        set(locked) {
            prefs
                .edit()
                .put(KEY_LOCK_DRAWER, locked)
                .close()
        }

    const val KEY_MERGE_CHANS: String = "merge_chans"
    const val DEFAULT_MERGE_CHANS: Boolean = false

    @JvmStatic
    val isMergeChans: Boolean
        get() =
            prefs.getBoolean(
                KEY_MERGE_CHANS,
                DEFAULT_MERGE_CHANS,
            ) &&
                ChanManager.getInstance().hasMultipleAvailableChans()

    const val KEY_NOTIFY_DOWNLOAD_COMPLETE: String = "notify_download_complete"
    const val DEFAULT_NOTIFY_DOWNLOAD_COMPLETE: Boolean = true

    @JvmStatic
    val isNotifyDownloadComplete: Boolean
        get() =
            prefs.getBoolean(
                KEY_NOTIFY_DOWNLOAD_COMPLETE,
                DEFAULT_NOTIFY_DOWNLOAD_COMPLETE,
            )

    const val KEY_PAGE_BY_PAGE: String = "page_by_page"
    const val DEFAULT_PAGE_BY_PAGE: Boolean = false

    val isPageByPage: Boolean
        get() =
            prefs.getBoolean(
                KEY_PAGE_BY_PAGE,
                DEFAULT_PAGE_BY_PAGE,
            )

    const val KEY_APP_ICON: String = "app_icon"
    val DEFAULT_APP_ICON: AppIcon = AppIcon.GRADIENT

    val appIcon: AppIcon?
        get() =
            getEnumValue(
                KEY_APP_ICON,
                AppIcon.entries.toTypedArray(),
                DEFAULT_APP_ICON,
                AppIcon.VALUE_PROVIDER,
            )

    const val KEY_PAGES_LIST: String = "pages_list"
    val DEFAULT_PAGES_LIST: PagesListMode = PagesListMode.PAGES_FIRST

    @JvmStatic
    val pagesListMode: PagesListMode?
        get() =
            getEnumValue(
                KEY_PAGES_LIST,
                PagesListMode.entries.toTypedArray(),
                DEFAULT_PAGES_LIST,
                PagesListMode.VALUE_PROVIDER,
            )

    val KEY_PARTIAL_THREAD_LOADING: ChanKey = ChanKey("partial_thread_loading")
    const val DEFAULT_PARTIAL_THREAD_LOADING: Boolean = true

    fun isPartialThreadLoading(chan: Chan): Boolean {
        if (chan.configuration.getOption(ChanConfiguration.OPTION_READ_THREAD_PARTIALLY)) {
            return prefs.getBoolean(
                KEY_PARTIAL_THREAD_LOADING.bind(chan.name),
                DEFAULT_PARTIAL_THREAD_LOADING,
            )
        } else {
            return false
        }
    }

    val KEY_PASSWORD: ChanKey = ChanKey("password")

    private fun generatePassword(): String {
        val password = StringBuilder()
        val random = Random(System.currentTimeMillis())
        var i = 0
        val count = 10 + random.nextInt(6)
        while (i < count) {
            var value = random.nextInt(26 + 26 + 10)
            if (value < 26) {
                value = 0x41 + value
            } else if (value < 26 + 26) {
                value = 0x61 + value - 26
            } else {
                value = 0x30 + value - 26 - 26
            }
            password.append(value.toChar())
            i++
        }
        return password.toString()
    }

    @JvmStatic
    fun getPassword(chan: Chan): String? {
        val key = KEY_PASSWORD.bind(chan.name)
        var password = prefs.getString(key, null)
        if (isEmpty(password)) {
            password = generatePassword()
            prefs.edit().put(key, password).close()
        }
        return password
    }

    const val KEY_POST_MAX_LINES: String = "post_max_lines"
    const val DEFAULT_POST_MAX_LINES: String = "20"

    @JvmStatic
    val postMaxLines: Int
        get() {
            try {
                return prefs
                    .getString(
                        com.mishiranu.dashchan.content.Preferences.KEY_POST_MAX_LINES,
                        com.mishiranu.dashchan.content.Preferences.DEFAULT_POST_MAX_LINES,
                    )!!
                    .toInt()
            } catch (e: Exception) {
                return DEFAULT_POST_MAX_LINES.toInt()
            }
        }

    val KEY_PROXY: ChanKey = ChanKey("proxy")
    const val SUB_KEY_PROXY_HOST: String = "host"
    const val SUB_KEY_PROXY_PORT: String = "port"
    const val SUB_KEY_PROXY_TYPE: String = "type"
    val KEYS_PROXY: List<String> = listOf(SUB_KEY_PROXY_HOST, SUB_KEY_PROXY_PORT, SUB_KEY_PROXY_TYPE)
    const val VALUE_PROXY_TYPE_HTTP: String = "http"
    const val VALUE_PROXY_TYPE_SOCKS: String = "socks"
    val ENTRIES_PROXY_TYPE: MutableList<CharSequence?> =
        mutableListOf<CharSequence?>("HTTP", "SOCKS")
    val VALUES_PROXY_TYPE: List<String> = listOf(VALUE_PROXY_TYPE_HTTP, VALUE_PROXY_TYPE_SOCKS)

    @JvmStatic
    fun getProxy(chan: Chan): Map<String, String>? {
        if (chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE)) {
            return null
        }
        val value = prefs.getString(KEY_PROXY.bind(chan.name), null)
        @Suppress("UNCHECKED_CAST")
        return unpackOrCastMultipleValues(value, KEYS_PROXY) as Map<String, String>?
    }

    const val KEY_RECAPTCHA_JAVASCRIPT: String = "recaptcha_javascript"
    const val DEFAULT_RECAPTCHA_JAVASCRIPT: Boolean = true

    @JvmStatic
    val isRecaptchaJavascript: Boolean
        get() =
            prefs.getBoolean(
                KEY_RECAPTCHA_JAVASCRIPT,
                DEFAULT_RECAPTCHA_JAVASCRIPT,
            )

    const val KEY_REMEMBER_HISTORY: String = "remember_history"
    const val DEFAULT_REMEMBER_HISTORY: Boolean = true

    @JvmStatic
    val isRememberHistory: Boolean
        get() =
            prefs.getBoolean(
                KEY_REMEMBER_HISTORY,
                DEFAULT_REMEMBER_HISTORY,
            )

    const val KEY_SCROLL_THREAD_GALLERY: String = "scroll_thread_gallery"
    const val DEFAULT_SCROLL_THREAD_GALLERY: Boolean = false

    @JvmStatic
    val isScrollThreadGallery: Boolean
        get() =
            prefs.getBoolean(
                KEY_SCROLL_THREAD_GALLERY,
                DEFAULT_SCROLL_THREAD_GALLERY,
            )

    const val KEY_SHOWCASE_GALLERY: String = "showcase_gallery"

    @JvmStatic
    fun consumeShowcaseGallery() {
        prefs.edit().put(KEY_SHOWCASE_GALLERY, false).close()
    }

    @JvmStatic
    val isShowcaseGalleryEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_SHOWCASE_GALLERY,
                true,
            )

    const val KEY_SFW_MODE: String = "sfw_mode"
    const val DEFAULT_SFW_MODE: Boolean = false

    @JvmStatic
    var isSfwMode: Boolean
        get() =
            prefs.getBoolean(
                KEY_SFW_MODE,
                DEFAULT_SFW_MODE,
            )
        set(sfwMode) {
            prefs
                .edit()
                .put(KEY_SFW_MODE, sfwMode)
                .close()
        }

    const val KEY_SHOW_MY_POSTS: String = "show_my_posts"
    const val DEFAULT_SHOW_MY_POSTS: Boolean = true

    @JvmStatic
    var isShowMyPosts: Boolean
        get() =
            prefs.getBoolean(
                KEY_SHOW_MY_POSTS,
                DEFAULT_SHOW_MY_POSTS,
            )
        set(showMyPosts) {
            prefs
                .edit()
                .put(KEY_SHOW_MY_POSTS, showMyPosts)
                .close()
        }

    const val KEY_SHOW_SPOILERS: String = "show_spoilers"
    const val DEFAULT_SHOW_SPOILERS: Boolean = false

    @JvmStatic
    var isShowSpoilers: Boolean
        get() =
            prefs.getBoolean(
                KEY_SHOW_SPOILERS,
                DEFAULT_SHOW_SPOILERS,
            )
        set(showSpoilers) {
            prefs
                .edit()
                .put(KEY_SHOW_SPOILERS, showSpoilers)
                .close()
        }

    const val KEY_SUBDIR_PATTERN: String = "subdir_pattern"
    const val DEFAULT_SUBDIR_PATTERN: String = "\\c-<\\b->\\t"

    @JvmStatic
    fun getSubdir(
        chanName: String?,
        chanTitle: String?,
        boardName: String?,
        threadNumber: String?,
        threadTitle: String?,
    ): String? {
        if (threadNumber == null) {
            return null
        }
        val pattern =
            emptyIfNull(prefs.getString(KEY_SUBDIR_PATTERN, DEFAULT_SUBDIR_PATTERN))
        return formatSubdir(pattern, chanName, chanTitle, boardName, threadNumber, threadTitle)
    }

    @JvmStatic
    fun formatSubdir(
        pattern: String,
        chanName: String?,
        chanTitle: String?,
        boardName: String?,
        threadNumber: String?,
        threadTitle: String?,
    ): String {
        val builder = StringBuilder(pattern)
        var i = builder.length - 2
        while (i >= 0) {
            val c = builder.get(i)
            if (c == '\\') {
                val cn = builder.get(i + 1)
                var replace = false
                var replaceTo: String? = null
                when (cn) {
                    'c' -> {
                        replace = true
                        replaceTo = chanName
                    }

                    'd' -> {
                        replace = true
                        replaceTo = chanTitle
                    }

                    'b' -> {
                        replace = true
                        replaceTo = boardName
                    }

                    't' -> {
                        replace = true
                        replaceTo = threadNumber
                    }

                    'e' -> {
                        replace = true
                        replaceTo = threadTitle
                    }
                }
                if (!replace) {
                    i--
                    continue
                }
                replaceTo = nullIfEmpty(replaceTo)
                if (replaceTo == null) {
                    var optStart = -1
                    var optEnd = -1
                    for (j in i - 1 downTo 0) {
                        val cj = builder.get(j)
                        if (cj == '<') {
                            optStart = j
                            break
                        } else if (cj == '\\') {
                            break
                        }
                    }
                    for (j in i + 2..<builder.length) {
                        val cj = builder.get(j)
                        if (cj == '>') {
                            optEnd = j + 1
                            break
                        } else if (cj == '\\') {
                            break
                        }
                    }
                    if (optEnd > optStart && optStart >= 0) {
                        builder.replace(optStart, optEnd, "")
                        i = optStart - 1
                    } else {
                        replaceTo = "null"
                    }
                }
                if (replaceTo != null) {
                    builder.replace(i, i + 2, replaceTo)
                }
            }
            i--
        }
        return builder.toString().replace("[:\\\\*?|]".toRegex(), "_").replace("[<>]".toRegex(), "")
    }

    const val KEY_TEXT_SCALE: String = "text_scale"
    const val MIN_TEXT_SCALE: Int = 75
    const val MAX_TEXT_SCALE: Int = 200
    const val STEP_TEXT_SCALE: Int = 5
    const val DEFAULT_TEXT_SCALE: Int = 100

    @JvmStatic
    val textScale: Float
        get() =
            max(
                MIN_TEXT_SCALE,
                min(
                    prefs.getInt(
                        KEY_TEXT_SCALE,
                        DEFAULT_TEXT_SCALE,
                    ),
                    MAX_TEXT_SCALE,
                ),
            ) / 100f

    const val KEY_THEME: String = "theme"

    @JvmStatic
    var theme: String?
        get() =
            prefs.getString(
                KEY_THEME,
                null,
            )
        set(value) {
            prefs
                .edit()
                .put(KEY_THEME, value)
                .close()
        }

    const val KEY_THEME_NIGHT: String = "theme_night"

    // The theme used while the OS reports night mode, but only when [isThemeFollowSystem].
    // Null until the user picks one; ThemeEngine then falls back to [theme].
    @JvmStatic
    var themeNight: String?
        get() =
            prefs.getString(
                KEY_THEME_NIGHT,
                null,
            )
        set(value) {
            prefs
                .edit()
                .put(KEY_THEME_NIGHT, value)
                .close()
        }

    const val KEY_THEME_FOLLOW_SYSTEM: String = "theme_follow_system"
    const val DEFAULT_THEME_FOLLOW_SYSTEM: Boolean = false

    @JvmStatic
    var isThemeFollowSystem: Boolean
        get() =
            prefs.getBoolean(
                KEY_THEME_FOLLOW_SYSTEM,
                DEFAULT_THEME_FOLLOW_SYSTEM,
            )
        set(value) {
            prefs
                .edit()
                .put(KEY_THEME_FOLLOW_SYSTEM, value)
                .close()
        }

    const val KEY_THREADS_VIEW: String = "threads_view"
    val DEFAULT_THREADS_VIEW: ThreadsView = ThreadsView.CARDS

    var threadsView: ThreadsView?
        get() =
            getEnumValue(
                KEY_THREADS_VIEW,
                ThreadsView.entries.toTypedArray(),
                DEFAULT_THREADS_VIEW,
                ThreadsView.Companion.VALUE_PROVIDER,
            )
        set(threadsView) {
            prefs
                .edit()
                .put(
                    KEY_THREADS_VIEW,
                    if (threadsView != null) threadsView.value else null,
                ).close()
        }

    init {
        if (PREFERENCES != null) {
            val threadsGridMode = PREFERENCES.getAll()["threads_grid_mode"]
            if (threadsGridMode is Boolean) {
                val value =
                    if (threadsGridMode) ThreadsView.LARGE_GRID.value else ThreadsView.CARDS.value
                PREFERENCES
                    .edit()
                    .remove("threads_grid_mode")
                    .put(KEY_THREADS_VIEW, value)
                    .close()
            }
        }
    }

    const val KEY_THUMBNAILS_SCALE: String = "thumbnails_scale"
    const val MIN_THUMBNAILS_SCALE: Int = 100
    const val MAX_THUMBNAILS_SCALE: Int = 200
    const val STEP_THUMBNAILS_SCALE: Int = 10
    const val DEFAULT_THUMBNAILS_SCALE: Int = 100

    @JvmStatic
    val thumbnailsScale: Float
        get() =
            max(
                MIN_THUMBNAILS_SCALE,
                min(
                    prefs.getInt(
                        KEY_THUMBNAILS_SCALE,
                        DEFAULT_THUMBNAILS_SCALE,
                    ),
                    MAX_THUMBNAILS_SCALE,
                ),
            ) / 100f

    const val KEY_TRUSTED_EXSTENSIONS: String = "trusted_extensions"

    @JvmStatic
    fun isExtensionTrusted(
        packageName: String?,
        fingerprint: String?,
    ): Boolean {
        val packageNameFingerprint = packageName + ":" + fingerprint
        val packageNameFingerprints =
            prefs.getStringSet(
                KEY_TRUSTED_EXSTENSIONS,
                null,
            )
        return packageNameFingerprints != null &&
            packageNameFingerprints.contains(
                packageNameFingerprint,
            )
    }

    @JvmStatic
    fun setExtensionTrusted(
        packageName: String?,
        fingerprint: String?,
    ) {
        val packageNameFingerprint = packageName + ":" + fingerprint
        val packageNameFingerprints: MutableSet<String> =
            prefs
                .getStringSet(
                    KEY_TRUSTED_EXSTENSIONS,
                    null,
                )?.let { HashSet(it) } ?: HashSet()
        packageNameFingerprints.add(packageNameFingerprint)
        prefs.edit().put(KEY_TRUSTED_EXSTENSIONS, packageNameFingerprints).close()
    }

    val KEY_USE_HTTPS: ChanKey = ChanKey("use_https")
    val KEY_USE_HTTPS_GENERAL: String = KEY_USE_HTTPS.bind(SPECIAL_CHAN_NAME_GENERAL)
    const val DEFAULT_USE_HTTPS: Boolean = true

    @JvmStatic
    fun isUseHttps(chan: Chan): Boolean = prefs.getBoolean(KEY_USE_HTTPS.bind(chan.name), DEFAULT_USE_HTTPS)

    fun setUseHttps(
        chan: Chan,
        useHttps: Boolean,
    ) {
        prefs.edit().put(KEY_USE_HTTPS.bind(chan.name), useHttps).close()
    }

    @JvmStatic
    val isUseHttpsGeneral: Boolean
        get() =
            prefs.getBoolean(
                KEY_USE_HTTPS_GENERAL,
                DEFAULT_USE_HTTPS,
            )

    const val KEY_USE_VIDEO_PLAYER: String = "use_video_player"
    const val DEFAULT_USE_VIDEO_PLAYER: Boolean = false

    val isUseVideoPlayer: Boolean
        get() =
            prefs.getBoolean(
                KEY_USE_VIDEO_PLAYER,
                DEFAULT_USE_VIDEO_PLAYER,
            )

    val KEY_USER_AUTHORIZATION: ChanKey = ChanKey("user_authorization")

    @JvmStatic
    fun getUserAuthorizationData(chan: Chan): MutableList<String?>? {
        val authorization = chan.configuration.safe().obtainUserAuthorization()
        if (authorization.fieldsCount > 0) {
            val value = prefs.getString(KEY_USER_AUTHORIZATION.bind(chan.name), null)
            return unpackOrCastMultipleValues(value, authorization.fieldsCount)
        } else {
            return null
        }
    }

    const val KEY_VERIFY_CERTIFICATE: String = "verify_certificate"
    const val DEFAULT_VERIFY_CERTIFICATE: Boolean = true

    @JvmStatic
    val isVerifyCertificate: Boolean
        get() =
            prefs.getBoolean(
                KEY_VERIFY_CERTIFICATE,
                DEFAULT_VERIFY_CERTIFICATE,
            )

    const val KEY_VIDEO_COMPLETION: String = "video_completion"
    val DEFAULT_VIDEO_COMPLETION: VideoCompletionMode = VideoCompletionMode.NOTHING

    @JvmStatic
    val videoCompletionMode: VideoCompletionMode?
        get() =
            getEnumValue(
                KEY_VIDEO_COMPLETION,
                VideoCompletionMode.entries.toTypedArray(),
                DEFAULT_VIDEO_COMPLETION,
                VideoCompletionMode.Companion.VALUE_PROVIDER,
            )

    const val KEY_VIDEO_PLAY_AFTER_SCROLL: String = "video_play_after_scroll"
    const val DEFAULT_VIDEO_PLAY_AFTER_SCROLL: Boolean = false

    @JvmStatic
    val isVideoPlayAfterScroll: Boolean
        get() =
            prefs.getBoolean(
                KEY_VIDEO_PLAY_AFTER_SCROLL,
                DEFAULT_VIDEO_PLAY_AFTER_SCROLL,
            )

    const val KEY_VIDEO_SEEK_ANY_FRAME: String = "video_seek_any_frame"
    const val DEFAULT_VIDEO_SEEK_ANY_FRAME: Boolean = false

    @JvmStatic
    val isVideoSeekAnyFrame: Boolean
        get() =
            prefs.getBoolean(
                KEY_VIDEO_SEEK_ANY_FRAME,
                DEFAULT_VIDEO_SEEK_ANY_FRAME,
            )

    const val KEY_VIDEO_MUTED: String = "video_muted"
    const val DEFAULT_VIDEO_MUTED: Boolean = false

    // Global mute state for the video players; a muted video keeps subsequent videos muted.
    @JvmStatic
    var isVideoMuted: Boolean
        get() =
            prefs.getBoolean(
                KEY_VIDEO_MUTED,
                DEFAULT_VIDEO_MUTED,
            )
        set(value) {
            prefs.edit().put(KEY_VIDEO_MUTED, value).close()
        }

    const val KEY_VIDEO_PLAYBACK_SPEED: String = "video_playback_speed"
    const val DEFAULT_VIDEO_PLAYBACK_SPEED: Float = 1f

    // Global playback-speed multiplier, shared across the video players like the mute state.
    @JvmStatic
    var videoPlaybackSpeed: Float
        get() =
            prefs.getFloat(
                KEY_VIDEO_PLAYBACK_SPEED,
                DEFAULT_VIDEO_PLAYBACK_SPEED,
            )
        set(value) {
            prefs.edit().put(KEY_VIDEO_PLAYBACK_SPEED, value).close()
        }

    const val KEY_VIDEO_MULTI_TAP_SEEK: String = "video_multi_tap_seek"
    const val DEFAULT_VIDEO_MULTI_TAP_SEEK: Boolean = false

    // Multi-tap fast-forward/rewind gesture; off by default (the double tap otherwise zooms).
    @JvmStatic
    val isVideoMultiTapSeek: Boolean
        get() =
            prefs.getBoolean(
                KEY_VIDEO_MULTI_TAP_SEEK,
                DEFAULT_VIDEO_MULTI_TAP_SEEK,
            )

    // The playback speeds the user can toggle on/off in settings. 1x is always available and is
    // never stored as a toggle; the rest default to a compact set that keeps the button useful.
    val VIDEO_SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2f)

    @JvmStatic
    fun videoSpeedKey(speed: Float): String = "video_speed_" + Math.round(speed * 100)

    @JvmStatic
    fun videoSpeedDefaultEnabled(speed: Float): Boolean = speed == 0.5f || speed == 1.5f || speed == 2f

    @JvmStatic
    fun isVideoSpeedEnabled(speed: Float): Boolean =
        speed == 1f ||
            (speed in VIDEO_SPEED_OPTIONS && prefs.getBoolean(videoSpeedKey(speed), videoSpeedDefaultEnabled(speed)))

    /** Enabled playback speeds in ascending order, always including 1x. */
    @JvmStatic
    val enabledVideoSpeeds: List<Float>
        get() {
            val list = mutableListOf(1f)
            for (speed in VIDEO_SPEED_OPTIONS) {
                if (isVideoSpeedEnabled(speed)) {
                    list.add(speed)
                }
            }
            list.sort()
            return list
        }

    /** The stored speed, or 1x when it has since been disabled in settings. */
    @JvmStatic
    val effectiveVideoPlaybackSpeed: Float
        get() = videoPlaybackSpeed.takeIf { isVideoSpeedEnabled(it) } ?: 1f

    const val KEY_WATCHER_REFRESH_INTERVAL: String = "watcher_refresh_interval"
    const val DISABLED_WATCHER_REFRESH_INTERVAL: Int = 0
    const val MIN_WATCHER_REFRESH_INTERVAL: Int = 15
    const val MAX_WATCHER_REFRESH_INTERVAL: Int = 60
    const val STEP_WATCHER_REFRESH_INTERVAL: Int = 5
    const val DEFAULT_WATCHER_REFRESH_INTERVAL: Int = 30

    @JvmStatic
    val watcherRefreshInterval: Int
        get() {
            val value =
                prefs.getInt(
                    KEY_WATCHER_REFRESH_INTERVAL,
                    DEFAULT_WATCHER_REFRESH_INTERVAL,
                )
            return if (value > MAX_WATCHER_REFRESH_INTERVAL) {
                MAX_WATCHER_REFRESH_INTERVAL
            } else {
                if (value < MIN_WATCHER_REFRESH_INTERVAL) {
                    DISABLED_WATCHER_REFRESH_INTERVAL
                } else {
                    value
                }
            }
        }

    init {
        if (PREFERENCES != null) {
            val key = "watcher_refresh_periodically"
            val value = PREFERENCES.getAll()[key]
            if (value is Boolean) {
                PREFERENCES.edit().use { editor ->
                    editor.remove(key)
                    if (!value) {
                        editor.put(KEY_WATCHER_REFRESH_INTERVAL, DISABLED_WATCHER_REFRESH_INTERVAL)
                    }
                }
            }
        }
    }

    const val KEY_WATCHER_NOTIFICATIONS: String = "watcher_notifications"
    val DEFAULT_WATCHER_NOTIFICATIONS: MutableSet<NotificationFeature?> =
        Collections
            .unmodifiableSet<NotificationFeature?>(
                HashSet<NotificationFeature?>(
                    Arrays.asList<NotificationFeature?>(
                        NotificationFeature.IMPORTANT,
                        NotificationFeature.SOUND,
                        NotificationFeature.VIBRATION,
                    ),
                ),
            )

    @JvmStatic
    val watcherNotifications: MutableSet<NotificationFeature?>
        get() {
            val strings =
                prefs.getStringSet(
                    KEY_WATCHER_NOTIFICATIONS,
                    null,
                )
            if (strings == null) {
                return DEFAULT_WATCHER_NOTIFICATIONS
            } else {
                val notificationFeatures =
                    HashSet<NotificationFeature?>(strings.size)
                for (value in strings) {
                    val notificationFeature =
                        NotificationFeature.Companion.find(value)
                    if (notificationFeature != null) {
                        notificationFeatures.add(notificationFeature)
                    }
                }
                return notificationFeatures
            }
        }

    fun setWatcherNotifications(notificationFeatures: Collection<NotificationFeature>?) {
        val strings: MutableSet<String>
        if (notificationFeatures.isNullOrEmpty()) {
            strings = mutableSetOf()
        } else {
            strings = HashSet()
            for (notificationFeature in notificationFeatures) {
                strings.add(notificationFeature.value)
            }
        }
        prefs.edit().put(KEY_WATCHER_NOTIFICATIONS, strings).close()
    }

    const val KEY_WATCHER_WATCH_INITIALLY: String = "watcher_watch_initially"
    const val DEFAULT_WATCHER_WATCH_INITIALLY: Boolean = false

    val isWatcherWatchInitially: Boolean
        get() =
            prefs.getBoolean(
                KEY_WATCHER_WATCH_INITIALLY,
                DEFAULT_WATCHER_WATCH_INITIALLY,
            )

    const val KEY_WATCHER_WIFI_ONLY: String = "watcher_wifi_only"
    const val DEFAULT_WATCHER_WIFI_ONLY: Boolean = false

    @JvmStatic
    val isWatcherWifiOnly: Boolean
        get() =
            prefs.getBoolean(
                KEY_WATCHER_WIFI_ONLY,
                DEFAULT_WATCHER_WIFI_ONLY,
            )

    const val KEY_SWIPE_TO_HIDE_THREAD: String = "swipe_to_hide_thread"
    const val DEFAULT_SWIPE_TO_HIDE_THREAD: Boolean = false

    val isSwipeToHideThreadEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_SWIPE_TO_HIDE_THREAD,
                DEFAULT_SWIPE_TO_HIDE_THREAD,
            )

    const val KEY_DISPLAY_HIDDEN_POSTS: String = "display_hidden_posts"
    const val DEFAULT_DISPLAY_HIDDEN_POSTS: Boolean = true

    @JvmStatic
    val isDisplayHiddenPostsEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_DISPLAY_HIDDEN_POSTS,
                DEFAULT_DISPLAY_HIDDEN_POSTS,
            )

    const val KEY_FIREWALL_RESOLUTION_METHOD: String = "firewall_resolution_method"
    val DEFAULT_FIREWALL_RESOLUTION_METHOD: FirewallResolutionMethod =
        FirewallResolutionMethod.MANUAL

    @JvmStatic
    val firewallResolutionMethod: FirewallResolutionMethod?
        get() =
            getEnumValue(
                KEY_FIREWALL_RESOLUTION_METHOD,
                FirewallResolutionMethod.entries.toTypedArray(),
                DEFAULT_FIREWALL_RESOLUTION_METHOD,
                FirewallResolutionMethod.Companion.VALUE_PROVIDER,
            )

    const val KEY_ALWAYS_UNIQUE_HASH: String = "always_unique_hash"
    const val DEFAULT_ALWAYS_UNIQUE_HASH: Boolean = false

    @JvmStatic
    val isAlwaysUniqueHash: Boolean
        get() =
            prefs.getBoolean(
                KEY_ALWAYS_UNIQUE_HASH,
                DEFAULT_ALWAYS_UNIQUE_HASH,
            )

    const val KEY_ALWAYS_CLEAR_METADATA: String = "always_clear_metadata"
    const val DEFAULT_ALWAYS_CLEAR_METADATA: Boolean = false

    @JvmStatic
    val isAlwaysClearMetadata: Boolean
        get() =
            prefs.getBoolean(
                KEY_ALWAYS_CLEAR_METADATA,
                DEFAULT_ALWAYS_CLEAR_METADATA,
            )

    const val KEY_ALWAYS_REMOVE_FILENAME: String = "always_remove_filename"
    const val DEFAULT_ALWAYS_REMOVE_FILENAME: Boolean = false

    @JvmStatic
    val isAlwaysRemoveFilename: Boolean
        get() =
            prefs.getBoolean(
                KEY_ALWAYS_REMOVE_FILENAME,
                DEFAULT_ALWAYS_REMOVE_FILENAME,
            )

    const val KEY_ALWAYS_RENAME_FILENAME: String = "always_rename_filename"
    const val DEFAULT_ALWAYS_RENAME_FILENAME: Boolean = false

    @JvmStatic
    val isAlwaysRenameFilename: Boolean
        get() =
            prefs.getBoolean(
                KEY_ALWAYS_RENAME_FILENAME,
                DEFAULT_ALWAYS_RENAME_FILENAME,
            )

    const val KEY_FILE_NEWNAME: String = "file_newname"
    const val DEFAULT_FILE_NEWNAME: String = "image"

    @JvmStatic
    val configuredFileNewname: String?
        get() =
            prefs.getString(
                KEY_FILE_NEWNAME,
                DEFAULT_FILE_NEWNAME,
            )

    const val KEY_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR: String =
        "important_posts_on_fastscroll_bar"
    const val DEFAULT_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR: Boolean = true

    @JvmStatic
    val isShowImportantPostsOnFastScrollBar: Boolean
        get() =
            prefs.getBoolean(
                KEY_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR,
                DEFAULT_SHOW_IMPORTANT_POSTS_ON_FASTSCROLL_BAR,
            )

    const val KEY_SHOW_POSTS_BORDERS: String = "posts_borders"
    const val DEFAULT_SHOW_POSTS_BORDERS: Boolean = true

    @JvmStatic
    val isShowPostsBorders: Boolean
        get() =
            prefs.getBoolean(
                KEY_SHOW_POSTS_BORDERS,
                DEFAULT_SHOW_POSTS_BORDERS,
            )

    const val KEY_SPACE_AFTER_QUOTE: String = "space_after_quote"
    const val DEFAULT_SPACE_AFTER_QUOTE: Boolean = true

    @JvmStatic
    val isAddSpaceAfterQuote: Boolean
        get() =
            prefs.getBoolean(
                KEY_SPACE_AFTER_QUOTE,
                DEFAULT_SPACE_AFTER_QUOTE,
            )

    const val KEY_MEDIA_LOADING_ACTION: String = "media_loading_action"
    val DEFAULT_MEDIA_LOADING_ACTION: MediaLoadingAction = MediaLoadingAction.MANUALLY

    @JvmStatic
    var mediaLoadingAction: MediaLoadingAction?
        get() =
            getEnumValue(
                KEY_MEDIA_LOADING_ACTION,
                MediaLoadingAction.entries.toTypedArray(),
                DEFAULT_MEDIA_LOADING_ACTION,
                MediaLoadingAction.Companion.VALUE_PROVIDER,
            )
        set(mediaLoadingAction) {
            prefs
                .edit()
                .put(
                    KEY_MEDIA_LOADING_ACTION,
                    if (mediaLoadingAction != null) mediaLoadingAction.value else null,
                ).close()
        }

    class ChanKey internal constructor(
        private val key: String?,
    ) {
        fun bind(chanName: String?): String = chanName + "_" + key
    }

    internal fun interface EnumValueProvider<T : Enum<T>> {
        fun getValue(enumValue: T?): String?
    }

    enum class NetworkMode(
        value: String,
        titleResId: Int,
        check: Check,
    ) {
        ALWAYS("always", R.string.always, NetworkMode.Check { o: NetworkObserver -> true }),
        WIFI(
            "wifi",
            R.string.wifi_only,
            NetworkMode.Check { obj: NetworkObserver -> obj.isWifiConnected() },
        ),
        NEVER("never", R.string.never, NetworkMode.Check { o: NetworkObserver -> false }),
        ;

        private fun interface Check {
            fun isNetworkAvailable(networkObserver: NetworkObserver): Boolean
        }

        val value: String?
        val titleResId: Int
        private val check: Check

        init {
            this.value = value
            this.titleResId = titleResId
            this.check = check
        }

        fun isNetworkAvailable(networkObserver: NetworkObserver): Boolean = check.isNetworkAvailable(networkObserver)

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<NetworkMode> { o -> o?.value }
        }
    }

    enum class CatalogSort(
        value: String,
        menuItemId: Int,
        titleResId: Int,
        comparator: Comparator<Comparable>?,
    ) {
        UNSORTED("unsorted", R.id.menu_unsorted, R.string.unsorted, null),
        CREATED(
            "created",
            R.id.menu_date_created,
            R.string.date_created,
            Comparator { lhs: Comparable, rhs: Comparable ->
                java.lang.Long.compare(
                    rhs.getTimestamp(),
                    lhs.getTimestamp(),
                )
            },
        ),
        REPLIES(
            "replies",
            R.id.menu_replies,
            R.string.replies_count,
            Comparator { lhs: Comparable, rhs: Comparable ->
                Integer.compare(
                    rhs.getThreadPostsCount(),
                    lhs.getThreadPostsCount(),
                )
            },
        ),
        ;

        interface Comparable {
            fun getTimestamp(): Long

            fun getThreadPostsCount(): Int
        }

        internal val value: String?
        val menuItemId: Int
        val titleResId: Int

        @JvmField
        val comparator: Comparator<Comparable>?

        init {
            this.value = value
            this.menuItemId = menuItemId
            this.titleResId = titleResId
            this.comparator = comparator
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<CatalogSort> { o -> o?.value }
        }
    }

    enum class CyclicalRefreshMode(
        value: String,
        titleResId: Int,
    ) {
        DEFAULT("default", R.string.use_forum_settings),
        FULL_LOAD("full_load", R.string.load_full_thread),
        FULL_LOAD_CLEANUP("full_load_cleanup", R.string.load_full_thread_and_clear_old_posts),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<CyclicalRefreshMode> { o -> o?.value }
        }
    }

    enum class DownloadSubdirMode(
        value: String,
        titleResId: Int,
        check: Check,
    ) {
        DISABLED(
            "disabled",
            R.string.never,
            DownloadSubdirMode.Check { multiple: Boolean -> false },
        ),
        MULTIPLE_ONLY(
            "multiple_only",
            R.string.on_multiple_downloading,
            DownloadSubdirMode.Check { multiple: Boolean -> multiple },
        ),
        ENABLED("enabled", R.string.always, DownloadSubdirMode.Check { multiple: Boolean -> true }),
        ;

        private fun interface Check {
            fun isEnabled(multiple: Boolean): Boolean
        }

        val value: String?
        val titleResId: Int
        private val check: Check

        init {
            this.value = value
            this.titleResId = titleResId
            this.check = check
        }

        fun isEnabled(multiple: Boolean): Boolean = check.isEnabled(multiple)

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<DownloadSubdirMode> { o -> o?.value }
        }
    }

    enum class DrawerInitialPosition(
        value: String,
        titleResId: Int,
    ) {
        CLOSED("closed", R.string.closed),
        FAVORITES("favorites", R.string.favorites),
        FORUMS("forums", R.string.forums),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER: EnumValueProvider<DrawerInitialPosition> =
                EnumValueProvider<DrawerInitialPosition> { o -> o?.value }
        }
    }

    enum class FavoriteOnReplyMode(
        value: String,
        titleResId: Int,
        check: Check,
    ) {
        DISABLED(
            "disabled",
            R.string.disabled,
            FavoriteOnReplyMode.Check { sage: Boolean -> false },
        ),
        ENABLED("enabled", R.string.enabled, FavoriteOnReplyMode.Check { sage: Boolean -> true }),
        WITHOUT_SAGE(
            "without_sage",
            R.string.only_if_without_sage,
            FavoriteOnReplyMode.Check { sage: Boolean -> !sage },
        ),
        ;

        private fun interface Check {
            fun isEnabled(sage: Boolean): Boolean
        }

        val value: String?
        val titleResId: Int
        private val check: Check

        init {
            this.value = value
            this.titleResId = titleResId
            this.check = check
        }

        fun isEnabled(sage: Boolean): Boolean = check.isEnabled(sage)

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<FavoriteOnReplyMode> { o -> o?.value }
        }
    }

    enum class FavoritesOrder(
        value: String,
        titleResId: Int,
    ) {
        DATE_DESC("date_desc", R.string.add_to_top__imperfective),
        DATE_ASC("date_asc", R.string.add_to_bottom__imperfective),
        TITLE("title", R.string.order_by_title),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<FavoritesOrder> { o -> o?.value }
        }
    }

    enum class HighlightUnreadMode(
        value: String,
        titleResId: Int,
    ) {
        AUTOMATICALLY("automatically", R.string.hide_eventually__imperfective),
        MANUALLY("manually", R.string.hide_on_tap__imperfective),
        NEVER("never", R.string.never_highlight),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<HighlightUnreadMode> { o -> o?.value }
        }
    }

    enum class AppIcon(
        value: String,
        titleResId: Int,
        componentSuffix: String,
    ) {
        GRADIENT("gradient", R.string.gradient, "MainActivityGradient"),
        STRIPED("striped", R.string.stripes, "MainActivityStriped"),
        ;

        val value: String?
        val titleResId: Int

        // Suffix of the launcher activity-alias in com.mishiranu.dashchan.ui carrying this icon.
        val componentSuffix: String?

        init {
            this.value = value
            this.titleResId = titleResId
            this.componentSuffix = componentSuffix
        }

        companion object {
            internal val VALUE_PROVIDER: EnumValueProvider<AppIcon> =
                EnumValueProvider<AppIcon> { o -> o?.value }
        }
    }

    enum class PagesListMode(
        value: String,
        titleResId: Int,
    ) {
        PAGES_FIRST("pages_first", R.string.pages_first),
        FAVORITES_FIRST("favorites_first", R.string.favorites_first),
        HIDE_PAGES("hide_pages", R.string.hide_pages),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER: EnumValueProvider<PagesListMode> =
                EnumValueProvider<PagesListMode> { o -> o?.value }
        }
    }

    enum class ThreadsView(
        value: String,
        menuItemId: Int,
        titleResId: Int,
    ) {
        LIST("list", R.id.menu_list, R.string.list),
        CARDS("cards", R.id.menu_cards, R.string.cards),
        LARGE_GRID("large_grid", R.id.menu_large_grid, R.string.large_grid),
        SMALL_GRID("small_grid", R.id.menu_small_grid, R.string.small_grid),
        ;

        internal val value: String?
        val menuItemId: Int
        val titleResId: Int

        init {
            this.value = value
            this.menuItemId = menuItemId
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<ThreadsView> { o -> o?.value }
        }
    }

    // The post context menu entry a right-to-left swipe on a post triggers. Only a subset of the
    // menu is offered here; the rest stays long-press only. COPY and SHARE open their submenu
    // just as the menu does, while COPY_TEXT..SHARE_LINK skip it and run the leaf directly.
    // Entries the menu doesn't offer for a given post (reporting on a board that disallows it,
    // copying the text of an empty post) simply don't fire -- InteractionUnit builds the same
    // list the menu itself shows.
    enum class PostSwipeAction(
        value: String,
        titleResId: Int,
    ) {
        DISABLED("disabled", R.string.do_nothing),
        REPLY("reply", R.string.reply),
        COPY("copy", R.string.copy),
        COPY_TEXT("copy_text", R.string.copy_text),
        COPY_MARKUP("copy_markup", R.string.copy_markup),
        COPY_LINK("copy_link", R.string.copy_link),
        SHARE("share", R.string.share),
        SHARE_TEXT("share_text", R.string.share_text),
        SHARE_LINK("share_link", R.string.share_link),
        HIDE("hide", R.string.hide),
        MY_POST("my_post", R.string.my_post),
        REPORT("report", R.string.report),
        ;

        val value: String
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<PostSwipeAction> { o -> o?.value }
        }
    }

    const val KEY_POST_SWIPE_ACTION: String = "post_swipe_action"
    val DEFAULT_POST_SWIPE_ACTION: PostSwipeAction = PostSwipeAction.REPLY

    @JvmStatic
    val postSwipeAction: PostSwipeAction
        get() =
            getEnumValue(
                KEY_POST_SWIPE_ACTION,
                PostSwipeAction.entries.toTypedArray(),
                DEFAULT_POST_SWIPE_ACTION,
                PostSwipeAction.VALUE_PROVIDER,
            )!!

    enum class VideoCompletionMode(
        value: String,
        titleResId: Int,
    ) {
        NOTHING("nothing", R.string.do_nothing),
        LOOP("loop", R.string.play_again),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<VideoCompletionMode> { o -> o?.value }
        }
    }

    enum class NotificationFeature(
        val value: String,
        val titleResId: Int,
    ) {
        ENABLED("enabled", R.string.enabled),
        IMPORTANT("important", R.string.important__plural),
        SOUND("sound", R.string.sound),
        VIBRATION("vibration", R.string.vibration),
        ;

        companion object {
            internal fun find(value: String?): NotificationFeature? {
                for (notificationFeature in entries) {
                    if (notificationFeature.value == value) {
                        return notificationFeature
                    }
                }
                return null
            }
        }
    }

    enum class FirewallResolutionMethod(
        value: String,
        titleResId: Int,
    ) {
        MANUAL("manual", R.string.firewall_resolution_method_manual),
        AUTO("auto", R.string.firewall_resolution_method_auto),
        AUTO_THEN_MANUAL("auto_then_manual", R.string.firewall_resolution_method_auto_then_manual),
        ;

        val value: String?

        @StringRes
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER =
                EnumValueProvider<FirewallResolutionMethod> { o -> o?.value }
        }
    }

    enum class MediaLoadingAction(
        value: String,
        titleResId: Int,
    ) {
        MANUALLY("manually", R.string.manually),
        REPLACE("replace", R.string.replace),
        KEEP_ALL("keep_all", R.string.keep_all),
        SKIP("skip", R.string.skip),
        ;

        val value: String?
        val titleResId: Int

        init {
            this.value = value
            this.titleResId = titleResId
        }

        companion object {
            internal val VALUE_PROVIDER = EnumValueProvider<MediaLoadingAction> { o -> o?.value }
        }
    }
}
