package chan.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import chan.content.ChanManager
import chan.content.ChanManager.ExtensionItem
import chan.content.ChanManager.ExtensionItem.TrustState
import chan.content.ChanManager.ExtensionsIterable.FilterMap
import chan.util.StringUtils.formatHex
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.chansOrder
import com.mishiranu.dashchan.content.Preferences.isExtensionTrusted
import com.mishiranu.dashchan.content.Preferences.setExtensionTrusted
import com.mishiranu.dashchan.graphics.ChanIconDrawable
import com.mishiranu.dashchan.util.AndroidUtils.OnReceiveListener
import com.mishiranu.dashchan.util.AndroidUtils.createReceiver
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.util.WeakObservable
import dalvik.system.DelegateLastClassLoader
import java.io.File
import java.util.Arrays
import java.util.Collections
import java.util.regex.Pattern

class ChanManager private constructor() {
    val fallbackChan: Chan
    val applicationFingerprints: Fingerprints

    // Read-only Map, not MutableMap: this field is assigned Collections.unmodifiableMap()
    // below, so a MutableMap type promised mutability the object does not have. Writing
    // `extensions!![k] = v` would have compiled cleanly and thrown UnsupportedOperationException
    // at runtime. Every read of this field is read-only; the one call site that mutates
    // works on a LinkedHashMap copy.
    private var extensions: Map<String?, Extension>? = null
    private var sortedExtensionNames: MutableList<String?>? = null
    private var archiveMap: Map<String?, MutableList<String?>> = mutableMapOf()

    class Fingerprints(
        val fingerprints: Set<String>,
    ) : Parcelable {
        override fun equals(other: Any?): Boolean = other is Fingerprints && other.fingerprints == fingerprints

        override fun hashCode(): Int = fingerprints.hashCode()

        override fun toString(): String {
            val list = ArrayList(fingerprints)
            list.sort()
            val builder = StringBuilder()
            for (fingerprint in list) {
                if (builder.length > 0) {
                    builder.append('.')
                }
                builder.append(fingerprint)
            }
            return builder.toString()
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeInt(fingerprints.size)
            for (fingerprint in fingerprints) {
                dest.writeString(fingerprint)
            }
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Fingerprints?> =
                object : Parcelable.Creator<Fingerprints?> {
                    override fun createFromParcel(source: Parcel): Fingerprints {
                        val fingerprintsSize = source.readInt()
                        val fingerprints = HashSet<String>()
                        repeat(fingerprintsSize) {
                            val fingerprint = source.readString()
                            if (fingerprint != null) {
                                fingerprints.add(fingerprint)
                            }
                        }
                        return Fingerprints(Collections.unmodifiableSet(fingerprints))
                    }

                    override fun newArray(size: Int): Array<Fingerprints?> = arrayOfNulls<Fingerprints>(size)
                }
        }
    }

    class ExtensionItem private constructor(
        val type: Type?,
        val name: String?,
        val title: String?,
        val trustState: TrustState?,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        internal val applicationInfo: ApplicationInfo,
        val fingerprints: Fingerprints,
        val apiVersion: Int,
        val supported: Boolean,
        val iconResId: Int,
        val updateUri: Uri?,
        val classConfiguration: String?,
        val classPerformer: String?,
        val classLocator: String?,
        val classMarkup: String?,
    ) {
        enum class Type {
            CHAN,
            LIBRARY,
        }

        enum class TrustState {
            UNTRUSTED,
            TRUSTED,
            DISCARDED,
        }

        val nativeLibraryDir: String?
            get() = applicationInfo.nativeLibraryDir

        constructor(
            name: String?,
            title: String?,
            packageName: String,
            versionName: String?,
            versionCode: Long,
            applicationInfo: ApplicationInfo,
            fingerprints: Fingerprints,
            apiVersion: Int,
            supported: Boolean,
            iconResId: Int,
            updateUri: Uri?,
            classConfiguration: String,
            classPerformer: String,
            classLocator: String,
            classMarkup: String,
        ) : this(
            Type.CHAN,
            name,
            title,
            TrustState.UNTRUSTED,
            packageName,
            versionName,
            versionCode,
            applicationInfo,
            fingerprints,
            apiVersion,
            supported,
            iconResId,
            updateUri,
            classConfiguration,
            classPerformer,
            classLocator,
            classMarkup,
        )

        constructor(
            name: String?,
            title: String?,
            packageName: String,
            versionName: String?,
            versionCode: Long,
            applicationInfo: ApplicationInfo,
            fingerprints: Fingerprints,
            updateUri: Uri?,
        ) : this(
            Type.LIBRARY,
            name,
            title,
            TrustState.UNTRUSTED,
            packageName,
            versionName,
            versionCode,
            applicationInfo,
            fingerprints,
            0,
            true,
            0,
            updateUri,
            null,
            null,
            null,
            null,
        )

        fun changeTrustState(trusted: Boolean): ExtensionItem {
            check(this.trustState == TrustState.UNTRUSTED)
            val trustState = if (trusted) TrustState.TRUSTED else TrustState.DISCARDED
            return ExtensionItem(
                type,
                name,
                title,
                trustState,
                packageName,
                versionName,
                versionCode,
                applicationInfo,
                fingerprints,
                apiVersion,
                supported,
                iconResId,
                updateUri,
                classConfiguration,
                classPerformer,
                classLocator,
                classMarkup,
            )
        }
    }

    interface Callback {
        fun onRestartRequiredChanged()

        fun onUntrustedExtensionInstalled()

        fun onChanInstalled(chan: Chan)

        fun onChanUninstalled(chan: Chan)
    }

    private class Extension(
        val item: ExtensionItem,
        val chan: Chan?,
    )

    private fun registerReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")
        MainApplication.getInstance().registerReceiver(
            createReceiver(
                OnReceiveListener { receiver: BroadcastReceiver?, context: Context?, intent: Intent? ->
                    val uri = intent!!.getData()
                    if (uri == null) {
                        return@OnReceiveListener
                    }
                    val packageName = uri.getSchemeSpecificPart()
                    if (packageName == null) {
                        return@OnReceiveListener
                    }
                    if (Intent.ACTION_PACKAGE_ADDED == intent.getAction()) {
                        val packageInfo: PackageInfo
                        try {
                            packageInfo =
                                context!!.getPackageManager().getPackageInfo(
                                    packageName,
                                    PackageManager.GET_CONFIGURATIONS or PACKAGE_MANAGER_SIGNATURE_FLAGS,
                                )
                        } catch (e: PackageManager.NameNotFoundException) {
                            return@OnReceiveListener
                        }
                        val chanExtension = isExtension(packageInfo, FEATURE_CHAN_EXTENSION)
                        val libExtension = isExtension(packageInfo, FEATURE_LIB_EXTENSION)
                        if (chanExtension) {
                            val extensions = this.extensions!!
                            val newExtension: Extension? =
                                loadExtension(
                                    packageInfo,
                                    true,
                                    false,
                                    applicationFingerprints,
                                    mutableSetOf<String?>(),
                                    extensions,
                                )
                            if (newExtension != null) {
                                val newTrusted = newExtension.item.trustState == TrustState.TRUSTED
                                val oldExtension = extensions[newExtension.item.name]
                                if (oldExtension == null) {
                                    updateExtensions(newExtension, null, true)
                                    for (callback in observable) {
                                        if (newTrusted) {
                                            callback.onChanInstalled(newExtension.chan!!)
                                        } else {
                                            callback.onUntrustedExtensionInstalled()
                                        }
                                    }
                                } else {
                                    val oldTrusted = oldExtension.item.trustState == TrustState.TRUSTED
                                    updateExtensions(newExtension, null, newTrusted || oldTrusted)
                                    for (callback in observable) {
                                        if (newTrusted) {
                                            callback.onChanInstalled(newExtension.chan!!)
                                        } else {
                                            if (oldTrusted) {
                                                callback.onChanUninstalled(oldExtension.chan!!)
                                            }
                                            callback.onUntrustedExtensionInstalled()
                                        }
                                    }
                                }
                            }
                        } else if (libExtension && !this.isRestartRequired) {
                            this.isRestartRequired = true
                            for (callback in observable) {
                                callback.onRestartRequiredChanged()
                            }
                        }
                    } else if (Intent.ACTION_PACKAGE_REMOVED == intent.getAction()) {
                        for (extension in extensions!!.values) {
                            if (packageName == extension.item.packageName) {
                                if (extension.item.type == ExtensionItem.Type.CHAN) {
                                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                        updateExtensions(
                                            null,
                                            extension.item.name,
                                            extension.item.trustState == TrustState.TRUSTED,
                                        )
                                        if (extension.chan != null) {
                                            for (callback in observable) {
                                                callback.onChanUninstalled(extension.chan)
                                            }
                                        }
                                    }
                                } else if (extension.item.type == ExtensionItem.Type.LIBRARY && !this.isRestartRequired) {
                                    this.isRestartRequired = true
                                    for (callback in observable) {
                                        callback.onRestartRequiredChanged()
                                    }
                                }
                                break
                            }
                        }
                    }
                },
            ),
            filter,
        )
    }

    private fun updateExtensions(
        newExtension: Extension?,
        deleteExtensionName: String?,
        updateArchiveMap: Boolean,
    ) {
        var orderedChanNames: MutableList<String?>? = chansOrder
        if (orderedChanNames == null) {
            orderedChanNames = mutableListOf<String?>()
        }
        val extensions = LinkedHashMap(this.extensions!!)
        if (newExtension != null) {
            extensions[newExtension.item.name] = newExtension
        }
        if (deleteExtensionName != null) {
            extensions.remove(deleteExtensionName)
        }
        val ordered = LinkedHashMap<String?, Extension>()
        for (chanName in orderedChanNames) {
            val extension = extensions[chanName]
            if (extension != null) {
                ordered[extension.item.name] = extension
            }
        }
        extensions.keys.removeAll(orderedChanNames)
        ordered.putAll(extensions)
        this.extensions = Collections.unmodifiableMap<String?, Extension>(ordered)
        sortedExtensionNames = null

        if (updateArchiveMap) {
            val archiveMap: MutableMap<String?, MutableList<String?>> =
                HashMap<String?, MutableList<String?>>()
            for (extension in ordered.values) {
                val archivation =
                    if (extension.chan != null) {
                        extension.chan.configuration
                            .safe()
                            .obtainArchivation()
                    } else {
                        null
                    }
                if (archivation != null) {
                    for (host in archivation.hosts) {
                        val chanName = getChanNameByHost(host)
                        if (chanName != null) {
                            var archiveChanNames = archiveMap[chanName]
                            if (archiveChanNames == null) {
                                archiveChanNames = ArrayList<String?>()
                                archiveMap[chanName] = archiveChanNames
                            }
                            archiveChanNames.add(extension.item.name)
                        }
                    }
                }
            }
            this.archiveMap =
                Collections.unmodifiableMap<String?, MutableList<String?>>(archiveMap)
        }
    }

    private fun isExtension(
        packageInfo: PackageInfo,
        feature: String,
    ): Boolean {
        val features = packageInfo.reqFeatures
        if (features != null) {
            for (featureInfo in features) {
                if (feature == featureInfo.name) {
                    return true
                }
            }
        }
        return false
    }

    fun changeUntrustedExtensionState(
        extensionName: String?,
        trusted: Boolean,
    ) {
        val extension = extensions!![extensionName]
        if (extension == null || extension.item.trustState != TrustState.UNTRUSTED) {
            return
        }
        if (trusted) {
            setExtensionTrusted(extension.item.packageName, extension.item.fingerprints.toString())
            if (extension.item.type == ExtensionItem.Type.CHAN) {
                val chan: Chan? = loadChan(extension.item, MainApplication.getInstance().getPackageManager())
                val newExtension = Extension(extension.item.changeTrustState(chan != null), chan)
                updateExtensions(newExtension, null, chan != null)
                if (chan != null) {
                    for (callback in observable) {
                        callback.onChanInstalled(chan)
                    }
                }
            } else if (extension.item.type == ExtensionItem.Type.LIBRARY) {
                val newExtension = Extension(extension.item.changeTrustState(true), null)
                updateExtensions(newExtension, null, false)
            }
        } else {
            val newExtension = Extension(extension.item.changeTrustState(false), null)
            updateExtensions(newExtension, null, false)
        }
    }

    class Initializer {
        class Holder internal constructor(
            val chanName: String?,
            internal val chanProvider: Chan.Provider?,
            val resources: Resources?,
        )

        private var holder: Holder? = null

        @Throws(LinkageError::class, Exception::class)
        internal fun <T : Chan.Linked> initialize(
            classLoader: ClassLoader?,
            className: String,
            chanName: String?,
            chanProvider: Chan.Provider?,
            resources: Resources?,
        ): T {
            synchronized(this) {
                holder = Holder(chanName, chanProvider, resources)
                val result: T
                try {
                    @Suppress("UNCHECKED_CAST")
                    result =
                        Class
                            .forName(className, false, classLoader)
                            .getDeclaredConstructor()
                            .newInstance() as T
                } finally {
                    holder = null
                }
                result.init()
                return result
            }
        }

        fun consume(): Holder {
            if (holder != null) {
                val holder = this.holder
                this.holder = null
                return holder!!
            } else {
                error("You can't initiate instance of this object by yourself.")
            }
        }
    }

    private class ExtensionsIterable<T : Any>(
        private val extensions: Collection<Extension>,
        private val filterMap: FilterMap<T>,
    ) : Iterable<T> {
        fun interface FilterMap<T : Any> {
            fun filterMap(extension: Extension): T?
        }

        override fun iterator(): Iterator<T> {
            val iterator = extensions.iterator()
            return object : Iterator<T> {
                private var next: T? = null

                fun findNext(): Boolean {
                    while (iterator.hasNext()) {
                        val next = filterMap.filterMap(iterator.next())
                        if (next != null) {
                            this.next = next
                            return true
                        }
                    }
                    return false
                }

                override fun hasNext(): Boolean = next != null || findNext()

                override fun next(): T {
                    if (next == null && !findNext()) {
                        throw NoSuchElementException()
                    }
                    val next = this.next!!
                    this.next = null
                    return next
                }
            }
        }
    }

    val extensionItems: Iterable<ExtensionItem>
        get() =
            ExtensionsIterable(
                extensions!!.values,
                FILTER_MAP_EXTENSION_ITEMS,
            )

    val firstUntrustedExtension: ExtensionItem?
        get() {
            for (extension in extensions!!.values) {
                if (extension.item.trustState == TrustState.UNTRUSTED) {
                    return extension.item
                }
            }
            return null
        }

    fun getLibraryExtension(libraryName: String?): ExtensionItem? {
        val extension = extensions!![libraryName]
        return if (extension != null && extension.item.type == ExtensionItem.Type.LIBRARY) extension.item else null
    }

    fun getArchiveChanNames(chanName: String?): MutableList<String?> {
        val list = archiveMap[chanName]
        return if (list != null) Collections.unmodifiableList<String?>(list) else mutableListOf<String?>()
    }

    fun isExistingChanName(chanName: String?): Boolean {
        val extension = extensions!![chanName]
        return extension != null && extension.item.type == ExtensionItem.Type.CHAN
    }

    val availableChans: Iterable<Chan>
        get() =
            ExtensionsIterable(
                extensions!!.values,
                FILTER_MAP_AVAILABLE_CHAN_NAMES,
            )

    fun hasMultipleAvailableChans(): Boolean {
        var count = 0
        for (extension in extensions!!.values) {
            if (extension.chan != null && ++count >= 2) {
                return true
            }
        }
        return false
    }

    fun compareChanNames(
        lhs: String?,
        rhs: String?,
    ): Int {
        if (sortedExtensionNames == null) {
            sortedExtensionNames =
                Collections.unmodifiableList<String?>(ArrayList<String?>(extensions!!.keys))
        }
        return sortedExtensionNames!!.indexOf(lhs) - sortedExtensionNames!!.indexOf(rhs)
    }

    val defaultChan: Chan?
        get() {
            for (extension in extensions!!.values) {
                if (extension.chan != null) {
                    return extension.chan
                }
            }
            return null
        }

    fun getChanNameByHost(host: String?): String? {
        if (host != null) {
            for (extension in extensions!!.values) {
                if (extension.chan != null && extension.chan.locator.isChanHost(host)) {
                    return extension.item.name
                }
            }
        }
        return null
    }

    fun getIcon(chan: Chan?): ChanIconDrawable? {
        if (chan != null) {
            var drawable = chan.icon
            if (drawable == null) {
                drawable = MainApplication.getInstance().getDrawable(R.drawable.ic_extension)
            }
            return ChanIconDrawable(drawable!!.getConstantState()!!.newDrawable().mutate())
        }
        return null
    }

    // Resources.updateConfiguration is the only way to update an already-created Resources instance;
    // extension Resources objects are cached and shared, so they can't be recreated per configuration.
    @Suppress("deprecation")
    fun updateConfiguration(
        newConfig: Configuration?,
        metrics: DisplayMetrics?,
    ) {
        for (extension in extensions!!.values) {
            if (extension.chan != null) {
                extension.chan.configuration
                    .getResources()!!
                    .updateConfiguration(newConfig, metrics)
            }
        }
    }

    fun isExtensionPackage(packageName: String): Boolean {
        for (extension in extensions!!.values) {
            if (packageName == extension.item.packageName) {
                return true
            }
        }
        return false
    }

    fun getFingerprints(file: File): Fingerprints? {
        val packageManager = MainApplication.getInstance().getPackageManager()
        val packageInfo: PackageInfo?
        try {
            packageInfo =
                packageManager.getPackageArchiveInfo(
                    file.getPath(),
                    PACKAGE_MANAGER_SIGNATURE_FLAGS,
                )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        if (packageInfo == null) {
            Log.e("ChanManager", "Invalid package file: " + file.getName())
            return null
        }
        return extractFingerprints(packageInfo)
    }

    fun getChan(chanName: String?): Chan {
        val extension = extensions!![chanName]
        val chan = if (extension != null) extension.chan else null
        return if (chan != null) chan else fallbackChan
    }

    var isRestartRequired: Boolean = false
        private set

    val observable: WeakObservable<Callback> = WeakObservable<Callback>()

    init {
        val packageName = MainApplication.getInstance().getPackageName()
        val fallbackChanProvider = Chan.Provider(null)
        val fallbackChan =
            Chan(
                null,
                packageName,
                ChanConfiguration(fallbackChanProvider),
                ChanPerformer(fallbackChanProvider),
                ChanLocator(fallbackChanProvider),
                ChanMarkup(fallbackChanProvider),
                null,
            )
        fallbackChanProvider.set(fallbackChan)
        this.fallbackChan = fallbackChan

        if (MainApplication.getInstance().isMainProcess()) {
            val packageManager = MainApplication.getInstance().getPackageManager()
            try {
                applicationFingerprints =
                    extractFingerprints(
                        packageManager
                            .getPackageInfo(packageName, PACKAGE_MANAGER_SIGNATURE_FLAGS),
                    )
                if (applicationFingerprints.fingerprints.isEmpty()) {
                    throw RuntimeException()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                throw RuntimeException(e)
            }
            val packages: List<PackageInfo> =
                packageManager.getInstalledPackages(
                    PackageManager.GET_CONFIGURATIONS
                        or PACKAGE_MANAGER_SIGNATURE_FLAGS,
                )

            val extensions = ArrayList<Extension>()
            val usedExtensionNames = HashSet<String?>()
            for (packageInfo in packages) {
                val chanExtension = isExtension(packageInfo, FEATURE_CHAN_EXTENSION)
                val libExtension = isExtension(packageInfo, FEATURE_LIB_EXTENSION)
                if (chanExtension || libExtension) {
                    val extension: Extension? =
                        Companion.loadExtension(
                            packageInfo,
                            chanExtension,
                            libExtension,
                            applicationFingerprints,
                            usedExtensionNames,
                            mutableMapOf(),
                        )
                    if (extension != null) {
                        extensions.add(extension)
                        usedExtensionNames.add(extension.item.name)
                    }
                }
            }

            this.extensions = extensionsMap(extensions)
            updateExtensions(null, null, true)
            registerReceiver()

            Preferences.PREFERENCES!!.register(
                SharedPreferences.Listener { key: String? ->
                    if (Preferences.KEY_CHANS_ORDER == key) {
                        updateExtensions(null, null, true)
                    }
                },
            )
        } else {
            this.applicationFingerprints = Fingerprints(setOf())
            this.extensions = mutableMapOf()
        }
    }

    companion object {
        const val MAX_VERSION: Int = 1
        const val MIN_VERSION: Int = 1

        const val EXTENSION_NAME_CLIENT: String = "client"
        const val EXTENSION_NAME_META: String = "meta"
        const val EXTENSION_NAME_LIB_WEBM: String = "webm"

        private val RESERVED_EXTENSION_NAMES: Set<String?> =
            run {
                val reservedExtensionNames = HashSet<String?>()
                reservedExtensionNames.add(EXTENSION_NAME_CLIENT)
                reservedExtensionNames.add(EXTENSION_NAME_META)
                Collections.addAll<String?>(
                    reservedExtensionNames,
                    *Preferences.SPECIAL_EXTENSION_NAMES,
                )
                Collections.unmodifiableSet<String?>(reservedExtensionNames)
            }
        private val RESERVED_CHAN_NAMES: Set<String?> =
            run {
                val reservedChanNames = HashSet<String?>(RESERVED_EXTENSION_NAMES)
                reservedChanNames.add(EXTENSION_NAME_LIB_WEBM)
                Collections.unmodifiableSet<String?>(reservedChanNames)
            }

        private const val FEATURE_CHAN_EXTENSION = "chan.extension"
        private const val META_CHAN_EXTENSION_NAME = "chan.extension.name"
        private const val META_CHAN_EXTENSION_TITLE = "chan.extension.title"
        private const val META_CHAN_EXTENSION_VERSION = "chan.extension.version"
        private const val META_CHAN_EXTENSION_ICON = "chan.extension.icon"
        private const val META_CHAN_EXTENSION_SOURCE = "chan.extension.source"
        private const val META_CHAN_EXTENSION_CLASS_CONFIGURATION =
            "chan.extension.class.configuration"
        private const val META_CHAN_EXTENSION_CLASS_PERFORMER = "chan.extension.class.performer"
        private const val META_CHAN_EXTENSION_CLASS_LOCATOR = "chan.extension.class.locator"
        private const val META_CHAN_EXTENSION_CLASS_MARKUP = "chan.extension.class.markup"

        private const val FEATURE_LIB_EXTENSION = "lib.extension"
        private const val META_LIB_EXTENSION_NAME = "lib.extension.name"
        private const val META_LIB_EXTENSION_TITLE = "lib.extension.title"
        private const val META_LIB_EXTENSION_SOURCE = "lib.extension.source"

        private val PACKAGE_MANAGER_SIGNATURE_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES

        private val VALID_EXTENSION_NAME: Pattern = Pattern.compile("[a-z][a-z0-9]{3,14}")

        private val INSTANCE = ChanManager()

        @JvmStatic
        fun getInstance(): ChanManager = INSTANCE

        private fun extendClassName(
            className: String,
            packageName: String?,
        ): String {
            var extended = className
            if (extended.startsWith(".")) {
                extended = packageName + extended
            }
            return extended
        }

        private fun extensionsMap(extensions: MutableList<Extension>): MutableMap<String?, Extension> {
            val map = LinkedHashMap<String?, Extension>()
            for (extension in extensions) {
                map[extension.item.name] = extension
            }
            return Collections.unmodifiableMap<String?, Extension>(map)
        }

        private fun loadExtension(
            packageInfo: PackageInfo,
            chanExtension: Boolean,
            libExtension: Boolean,
            applicationFingerprints: Fingerprints?,
            usedExtensionNames: MutableCollection<String?>,
            // Read-only: the body only looks names up. Widening this from MutableMap lets
            // the `extensions` field carry the read-only type it actually holds.
            extensions: Map<String?, Extension>,
        ): Extension? {
            require(!(!chanExtension && !libExtension))
            val packageManager = MainApplication.getInstance().getPackageManager()
            val applicationInfo: ApplicationInfo
            try {
                applicationInfo =
                    packageManager.getApplicationInfo(
                        packageInfo.packageName,
                        PackageManager.GET_META_DATA,
                    )
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            val data = applicationInfo.metaData
            val name =
                data.getString(
                    if (chanExtension) {
                        META_CHAN_EXTENSION_NAME
                    } else {
                        if (libExtension) {
                            META_LIB_EXTENSION_NAME
                        } else {
                            null
                        }
                    },
                )
            if (name == null ||
                !VALID_EXTENSION_NAME.matcher(name).matches() ||
                (if (chanExtension) RESERVED_CHAN_NAMES else RESERVED_EXTENSION_NAMES).contains(
                    name,
                )
            ) {
                Log.e("ChanManager", "Invalid extension name: " + name)
                return null
            }
            val nameConflict: Boolean
            if (usedExtensionNames.contains(name)) {
                nameConflict = true
            } else {
                val extension = extensions[name]
                nameConflict =
                    extension != null &&
                    extension.item.packageName != packageInfo.packageName
            }
            if (nameConflict) {
                Log.e("ChanManager", "Extension name conflict: " + name + " already exists")
                return null
            }
            var title =
                data.getString(
                    if (chanExtension) {
                        META_CHAN_EXTENSION_TITLE
                    } else {
                        if (libExtension) {
                            META_LIB_EXTENSION_TITLE
                        } else {
                            null
                        }
                    },
                )
            if (title == null) {
                title = name
            }
            val fingerprints: Fingerprints = extractFingerprints(packageInfo)
            var extensionItem: ExtensionItem
            if (chanExtension) {
                val invalidVersion = Int.MIN_VALUE
                val apiVersion = data.getInt(META_CHAN_EXTENSION_VERSION, invalidVersion)
                if (apiVersion == invalidVersion) {
                    Log.e("ChanManager", "Invalid extension version")
                    return null
                }
                val iconResId = data.getInt(META_CHAN_EXTENSION_ICON)
                val source = data.getString(META_CHAN_EXTENSION_SOURCE)
                val updateUri = if (source != null) Uri.parse(source) else null
                var classConfiguration = data.getString(META_CHAN_EXTENSION_CLASS_CONFIGURATION)
                var classPerformer = data.getString(META_CHAN_EXTENSION_CLASS_PERFORMER)
                var classLocator = data.getString(META_CHAN_EXTENSION_CLASS_LOCATOR)
                var classMarkup = data.getString(META_CHAN_EXTENSION_CLASS_MARKUP)
                if (classConfiguration == null || classPerformer == null || classLocator == null || classMarkup == null) {
                    Log.e("ChanManager", "Undefined extension class")
                    return null
                }
                classConfiguration = extendClassName(classConfiguration, packageInfo.packageName)
                classPerformer = extendClassName(classPerformer, packageInfo.packageName)
                classLocator = extendClassName(classLocator, packageInfo.packageName)
                classMarkup = extendClassName(classMarkup, packageInfo.packageName)
                val supported = apiVersion >= MIN_VERSION && apiVersion <= MAX_VERSION
                extensionItem =
                    ExtensionItem(
                        name,
                        title,
                        packageInfo.packageName,
                        packageInfo.versionName,
                        versionCode,
                        applicationInfo,
                        fingerprints,
                        apiVersion,
                        supported,
                        iconResId,
                        updateUri,
                        classConfiguration,
                        classPerformer,
                        classLocator,
                        classMarkup,
                    )
            } else if (libExtension) {
                val source = data.getString(META_LIB_EXTENSION_SOURCE)
                val updateUri = if (source != null) Uri.parse(source) else null
                extensionItem =
                    ExtensionItem(
                        name,
                        title,
                        packageInfo.packageName,
                        packageInfo.versionName,
                        versionCode,
                        applicationInfo,
                        fingerprints,
                        updateUri,
                    )
            } else {
                throw RuntimeException()
            }
            var chan: Chan? = null
            if (fingerprints == applicationFingerprints ||
                isExtensionTrusted(packageInfo.packageName, fingerprints.toString())
            ) {
                if (extensionItem.type == ExtensionItem.Type.CHAN) {
                    chan = loadChan(extensionItem, packageManager)
                    extensionItem = extensionItem.changeTrustState(chan != null)
                } else {
                    extensionItem = extensionItem.changeTrustState(true)
                }
            }
            return Extension(extensionItem, chan)
        }

        private fun loadChan(
            chanItem: ExtensionItem,
            packageManager: PackageManager,
        ): Chan? {
            if (chanItem.supported) {
                val chanName = chanItem.name
                try {
                    var nativeLibraryDir = chanItem.applicationInfo.nativeLibraryDir
                    if (nativeLibraryDir != null && !File(nativeLibraryDir).exists()) {
                        nativeLibraryDir = null
                    }
                    val classLoader: ClassLoader?
                    val dexPath = chanItem.applicationInfo.sourceDir
                    val parent = ChanManager::class.java.getClassLoader()
                    classLoader = DelegateLastClassLoader(dexPath, nativeLibraryDir, parent)

                    val resources =
                        packageManager.getResourcesForApplication(chanItem.applicationInfo)
                    val chanProvider = Chan.Provider(null)
                    val configuration: ChanConfiguration =
                        ChanConfiguration.INITIALIZER.initialize(
                            classLoader,
                            chanItem.classConfiguration!!,
                            chanName,
                            chanProvider,
                            resources,
                        )
                    val performer: ChanPerformer =
                        ChanPerformer.INITIALIZER.initialize(
                            classLoader,
                            chanItem.classPerformer!!,
                            chanName,
                            chanProvider,
                            resources,
                        )
                    val locator: ChanLocator =
                        ChanLocator.INITIALIZER.initialize(
                            classLoader,
                            chanItem.classLocator!!,
                            chanName,
                            chanProvider,
                            resources,
                        )
                    val markup: ChanMarkup =
                        ChanMarkup.INITIALIZER.initialize(
                            classLoader,
                            chanItem.classMarkup!!,
                            chanName,
                            chanProvider,
                            resources,
                        )
                    val icon =
                        if (chanItem.iconResId != 0) {
                            resources.getDrawable(chanItem.iconResId, null)
                        } else {
                            null
                        }
                    val chan =
                        Chan(
                            chanName,
                            chanItem.packageName,
                            configuration,
                            performer,
                            locator,
                            markup,
                            icon,
                        )
                    chanProvider.set(chan)
                    return chan
                } catch (e: Exception) {
                    e.printStackTrace()
                } catch (e: LinkageError) {
                    e.printStackTrace()
                }
            }
            return null
        }

        private val FILTER_MAP_EXTENSION_ITEMS =
            FilterMap { extension: Extension -> extension.item }
        private val FILTER_MAP_AVAILABLE_CHAN_NAMES =
            FilterMap { extension: Extension -> extension.chan }

        private fun extractFingerprints(packageInfo: PackageInfo): Fingerprints {
            val fingerprints = HashSet<String>()
            val signatures: MutableList<Signature?>?
            val signaturesArray =
                if (packageInfo.signingInfo != null) {
                    packageInfo.signingInfo!!.getApkContentsSigners()
                } else {
                    null
                }
            signatures =
                if (signaturesArray != null) Arrays.asList<Signature?>(*signaturesArray) else mutableListOf<Signature?>()

            for (signature in signatures) {
                if (signature != null) {
                    fingerprints.add(formatHex(getInstanceSha256().calculate(signature.toByteArray()))!!)
                }
            }
            return Fingerprints(Collections.unmodifiableSet<String>(fingerprints))
        }
    }
}
