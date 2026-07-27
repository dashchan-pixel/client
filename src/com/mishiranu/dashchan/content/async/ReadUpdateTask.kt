package com.mishiranu.dashchan.content.async

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.Pair
import androidx.core.content.pm.PackageInfoCompat
import chan.content.Chan
import chan.content.ChanManager
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.FileProvider
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.util.Hasher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale

class ReadUpdateTask(
    context: Context,
    private val callback: Callback,
) : HttpHolderTask<Unit, Pair<ErrorItem?, ReadUpdateTask.UpdateDataMap?>?>(Chan.getFallback()) {
    private val context: Context = context.applicationContext

    class UpdateDataMap private constructor(
        private val update: Map<String, ApplicationItem>,
        private val install: Map<String, ApplicationItem>,
    ) : Parcelable {
        constructor(
            update: HashMap<String, ApplicationItem>,
            install: HashMap<String, ApplicationItem>,
        ) : this(update as Map<String, ApplicationItem>, install)

        fun get(
            extensionName: String?,
            installed: Boolean,
        ): ApplicationItem? = (if (installed) update else install)[extensionName]

        fun extensionNames(installed: Boolean): Collection<String> = (if (installed) update else install).keys

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            writeMap(dest, flags, update)
            writeMap(dest, flags, install)
        }

        companion object {
            private fun <T : Parcelable> writeMap(
                dest: Parcel,
                flags: Int,
                map: Map<String, T>,
            ) {
                dest.writeInt(map.size)
                for ((key, value) in map) {
                    dest.writeString(key)
                    value.writeToParcel(dest, flags)
                }
            }

            private fun <T : Parcelable> readMap(
                source: Parcel,
                creator: Parcelable.Creator<T>,
            ): Map<String, T> {
                val count = source.readInt()
                val map = HashMap<String, T>()
                for (i in 0 until count) {
                    val key = source.readString()!!
                    val value = creator.createFromParcel(source)
                    map[key] = value
                }
                return map
            }

            @JvmField
            val CREATOR =
                object : Parcelable.Creator<UpdateDataMap> {
                    override fun createFromParcel(source: Parcel): UpdateDataMap {
                        val update = readMap(source, ApplicationItem.CREATOR)
                        val install = readMap(source, ApplicationItem.CREATOR)
                        return UpdateDataMap(update, install)
                    }

                    override fun newArray(size: Int): Array<UpdateDataMap?> = arrayOfNulls(size)
                }
        }
    }

    class ApplicationItem(
        @JvmField val type: Type,
        @JvmField val name: String,
        @JvmField val title: String?,
        @JvmField val packageItems: MutableList<PackageItem>,
    ) : Parcelable {
        enum class Type { CLIENT, LIBRARY, CHAN }

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeString(type.name)
            dest.writeString(name)
            dest.writeString(title)
            dest.writeTypedList(packageItems)
        }

        companion object {
            @Throws(JSONException::class)
            internal fun fromJsonV1(jsonObject: JSONObject): ApplicationItem {
                val name = jsonObject.getString("name")
                val typeString = jsonObject.getString("type")
                val title = jsonObject.getString("title")
                val type =
                    when (typeString) {
                        "client" -> Type.CLIENT
                        "chan" -> Type.CHAN
                        "library" -> Type.LIBRARY
                        else -> throw JSONException("Invalid type")
                    }
                return ApplicationItem(type, name, title, ArrayList())
            }

            @JvmField
            val CREATOR =
                object : Parcelable.Creator<ApplicationItem> {
                    override fun createFromParcel(source: Parcel): ApplicationItem {
                        val type = Type.valueOf(source.readString()!!)
                        val name = source.readString()!!
                        val title = source.readString()
                        val packageItems = source.createTypedArrayList(PackageItem.CREATOR)!!
                        return ApplicationItem(type, name, title, packageItems)
                    }

                    override fun newArray(size: Int): Array<ApplicationItem?> = arrayOfNulls(size)
                }
        }
    }

    class PackageItem(
        @JvmField val repository: String?,
        @JvmField val title: String?,
        @JvmField val versionName: String?,
        @JvmField val versionCode: Long,
        @JvmField val minApiVersion: Int,
        @JvmField val maxApiVersion: Int,
        @JvmField val apiVersion: Int,
        @JvmField val length: Long,
        @JvmField val source: Uri?,
        @JvmField val sha256sum: ByteArray?,
        @JvmField val fingerprints: ChanManager.Fingerprints?,
    ) : Parcelable {
        override fun describeContents(): Int = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) {
            dest.writeString(repository)
            dest.writeString(title)
            dest.writeString(versionName)
            dest.writeLong(versionCode)
            dest.writeInt(minApiVersion)
            dest.writeInt(maxApiVersion)
            dest.writeInt(apiVersion)
            dest.writeLong(length)
            dest.writeString(source?.toString())
            dest.writeByteArray(sha256sum)
            dest.writeByte(if (fingerprints != null) 1 else 0)
            fingerprints?.writeToParcel(dest, flags)
        }

        companion object {
            @JvmField
            val CREATOR =
                object : Parcelable.Creator<PackageItem> {
                    override fun createFromParcel(source: Parcel): PackageItem {
                        val repository = source.readString()
                        val title = source.readString()
                        val versionName = source.readString()
                        val versionCode = source.readLong()
                        val minVersion = source.readInt()
                        val maxVersion = source.readInt()
                        val version = source.readInt()
                        val length = source.readLong()
                        val sourceString = source.readString()
                        val sourceUri = if (sourceString != null) Uri.parse(sourceString) else null
                        val sha256sum = source.createByteArray()
                        val fingerprints =
                            if (source.readByte().toInt() != 0) {
                                ChanManager.Fingerprints.CREATOR.createFromParcel(source)
                            } else {
                                null
                            }
                        return PackageItem(
                            repository,
                            title,
                            versionName,
                            versionCode,
                            minVersion,
                            maxVersion,
                            version,
                            length,
                            sourceUri,
                            sha256sum,
                            fingerprints,
                        )
                    }

                    override fun newArray(size: Int): Array<PackageItem?> = arrayOfNulls(size)
                }
        }
    }

    fun interface Callback {
        fun onReadUpdateComplete(
            updateDataMap: UpdateDataMap?,
            errorItem: ErrorItem?,
        )
    }

    private enum class DataVersion(
        val fileName: String,
    ) {
        V1("data-v1.json"),
        LEGACY("data.json"),
    }

    private class TargetUri(
        uri: Uri,
    ) {
        val uri: Uri
        val directory: Boolean

        init {
            val builder = uri.buildUpon()
            builder.scheme("")
            val name = uri.lastPathSegment
            var directory = false
            for (dataVersion in DataVersion.values()) {
                if (name == dataVersion.fileName) {
                    directory = true
                    break
                }
            }
            if (directory) {
                val path = uri.path!!
                builder.path(path.substring(0, path.lastIndexOf('/')))
            }
            this.uri = builder.build()
            this.directory = directory
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is TargetUri) {
                return uri == other.uri && directory == other.directory
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + uri.hashCode()
            result = prime * result + if (directory) 1231 else 1237
            return result
        }
    }

    /**
     * The APK installed under an extension name, to compare a manifest package against
     * by content rather than by the version it claims.
     *
     * A version cannot decide this: an APK built and installed locally carries the very
     * version the release does, while being a different build. The bytes decide it.
     *
     * Reading them is deferred to a package that could be a copy at all -- the declared
     * size is one stat against the hash's full read -- and happens at most once. An APK
     * that cannot be read counts as no match, which leaves the package on offer.
     */
    private class InstalledApk(
        private val path: String?,
    ) {
        private var sha256: ByteArray? = null
        private var hashed = false

        fun isSameBinary(
            length: Long,
            sha256sum: ByteArray?,
        ): Boolean {
            val path = this.path
            if (path == null || sha256sum == null || length <= 0 || File(path).length() != length) {
                return false
            }
            if (!hashed) {
                hashed = true
                sha256 =
                    try {
                        FileInputStream(path).use { Hasher.getInstanceSha256().calculate(it) }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        null
                    }
            }
            return sha256.contentEquals(sha256sum)
        }
    }

    private class Response(
        var uri: Uri,
        val dataVersion: DataVersion,
        val jsonObject: JSONObject,
        val extensionNames: HashSet<String>,
    ) {
        fun getRepositoryName(): String {
            val repository: String? =
                when (dataVersion) {
                    DataVersion.LEGACY -> {
                        jsonObject
                            .optJSONObject(ChanManager.EXTENSION_NAME_META)
                            ?.optString("repository")
                    }

                    DataVersion.V1 -> {
                        jsonObject.optString("title")
                    }
                }
            return if (repository.isNullOrEmpty()) "Unknown repository" else repository
        }
    }

    override fun run(holder: HttpHolder): Pair<ErrorItem?, UpdateDataMap?>? {
        val directory =
            FileProvider.updatesDirectory
                ?: return Pair(ErrorItem(ErrorItem.Type.NO_ACCESS_TO_MEMORY), null)
        val files = directory.listFiles()
        if (files != null) {
            // One week
            val timeThreshold = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
            for (file in files) {
                if (file.lastModified() < timeThreshold) {
                    file.delete()
                }
            }
        }

        val extensionItems = ChanManager.getInstance().extensionItems
        val fingerprintsMap = HashMap<String, ChanManager.Fingerprints?>()
        fingerprintsMap[ChanManager.EXTENSION_NAME_CLIENT] =
            ChanManager.getInstance().applicationFingerprints
        val installedApks = HashMap<String, InstalledApk>()
        for (extensionItem in extensionItems) {
            fingerprintsMap[extensionItem.name!!] = extensionItem.fingerprints
            installedApks[extensionItem.name] = InstalledApk(extensionItem.applicationInfo.sourceDir)
        }
        val applicationTitle: String
        val applicationVersionName: String?
        val applicationVersionCode: Long
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val applicationInfo = packageInfo.applicationInfo!!
            applicationTitle = applicationInfo.loadLabel(context.packageManager).toString()
            applicationVersionName = packageInfo.versionName
            applicationVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            installedApks[ChanManager.EXTENSION_NAME_CLIENT] = InstalledApk(applicationInfo.sourceDir)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        val updateDataMap = HashMap<String, ApplicationItem>()
        run {
            var applicationItem =
                ApplicationItem(
                    ApplicationItem.Type.CLIENT,
                    ChanManager.EXTENSION_NAME_CLIENT,
                    applicationTitle,
                    ArrayList(),
                )
            applicationItem.packageItems.add(
                PackageItem(
                    null,
                    null,
                    applicationVersionName,
                    applicationVersionCode,
                    ChanManager.MIN_VERSION,
                    ChanManager.MAX_VERSION,
                    0,
                    -1,
                    null,
                    null,
                    null,
                ),
            )
            updateDataMap[ChanManager.EXTENSION_NAME_CLIENT] = applicationItem
            for (extensionItem in extensionItems) {
                applicationItem =
                    ApplicationItem(
                        if (extensionItem.type == ChanManager.ExtensionItem.Type.LIBRARY) {
                            ApplicationItem.Type.LIBRARY
                        } else {
                            ApplicationItem.Type.CHAN
                        },
                        extensionItem.name!!,
                        extensionItem.title,
                        ArrayList(),
                    )
                applicationItem.packageItems.add(
                    PackageItem(
                        null,
                        null,
                        extensionItem.versionName,
                        extensionItem.versionCode,
                        0,
                        0,
                        extensionItem.apiVersion,
                        -1,
                        null,
                        null,
                        null,
                    ),
                )
                updateDataMap[extensionItem.name] = applicationItem
            }
        }
        if (isCancelled()) {
            return null
        }

        val responses = readData(holder, extensionItems)
        if (!responses.iterator().hasNext()) {
            return Pair(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), null)
        }
        if (isCancelled()) {
            return null
        }

        for (response in responses) {
            try {
                when (response.dataVersion) {
                    DataVersion.LEGACY -> {
                        val keys = response.jsonObject.keys()
                        while (keys.hasNext()) {
                            val extensionName = keys.next()
                            if (ChanManager.EXTENSION_NAME_META == extensionName) {
                                continue
                            }
                            if (response.extensionNames.contains(extensionName)) {
                                val packagesArray = response.jsonObject.getJSONArray(extensionName)
                                handleUpdateItems(
                                    response,
                                    extensionName,
                                    updateDataMap,
                                    fingerprintsMap,
                                    installedApks,
                                    packagesArray,
                                    null,
                                )
                            }
                        }
                    }

                    DataVersion.V1 -> {
                        val jsonArray = response.jsonObject.optJSONArray("applications")
                        if (jsonArray != null && jsonArray.length() > 0) {
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)
                                val extractedItem = ApplicationItem.fromJsonV1(jsonObject)
                                if (response.extensionNames.contains(extractedItem.name)) {
                                    val packagesArray = jsonObject.getJSONArray("packages")
                                    handleUpdateItems(
                                        response,
                                        extractedItem.name,
                                        updateDataMap,
                                        fingerprintsMap,
                                        installedApks,
                                        packagesArray,
                                        extractedItem,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            if (isCancelled()) {
                return null
            }
        }

        val installDataMap = HashMap<String, ApplicationItem>()
        for (response in responses) {
            try {
                when (response.dataVersion) {
                    DataVersion.LEGACY -> {
                        val keys = response.jsonObject.keys()
                        while (keys.hasNext()) {
                            val extensionName = keys.next()
                            if (ChanManager.EXTENSION_NAME_META == extensionName ||
                                isRetiredExtension(extensionName)
                            ) {
                                continue
                            }
                            if (!updateDataMap.containsKey(extensionName)) {
                                val packagesArray = response.jsonObject.getJSONArray(extensionName)
                                handleInstallItems(
                                    response,
                                    extensionName,
                                    installDataMap,
                                    packagesArray,
                                    null,
                                )
                            }
                        }
                    }

                    DataVersion.V1 -> {
                        val jsonArray = response.jsonObject.optJSONArray("applications")
                        if (jsonArray != null && jsonArray.length() > 0) {
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)
                                val extractedItem = ApplicationItem.fromJsonV1(jsonObject)
                                if (!updateDataMap.containsKey(extractedItem.name) &&
                                    !isRetiredExtension(extractedItem.name)
                                ) {
                                    val packagesArray = jsonObject.getJSONArray("packages")
                                    handleInstallItems(
                                        response,
                                        extractedItem.name,
                                        installDataMap,
                                        packagesArray,
                                        extractedItem,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            if (isCancelled()) {
                return null
            }
        }

        return if (updateDataMap.isNotEmpty()) {
            Pair(null, UpdateDataMap(updateDataMap, installDataMap))
        } else {
            Pair(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), null)
        }
    }

    override fun onComplete(result: Pair<ErrorItem?, UpdateDataMap?>?) {
        callback.onReadUpdateComplete(result!!.second, result.first)
    }

    companion object {
        /**
         * The Webm library extension is superseded by the built-in Media3 player and is
         * refused at load, so an update source still advertising it must not offer it for
         * install: installing it would only produce an extension the client ignores.
         */
        private fun isRetiredExtension(name: String?): Boolean = ChanManager.EXTENSION_NAME_LIB_WEBM == name

        @JvmStatic
        fun normalizeRelativeUri(
            base: Uri,
            uriOrPath: String,
        ): Uri {
            var uri = Uri.parse(uriOrPath)
            val noScheme = StringUtils.isEmpty(uri.scheme)
            val noHost = StringUtils.isEmpty(uri.host)
            val noPath = StringUtils.isEmpty(uri.path)
            if (noScheme || noHost || noPath) {
                val builder = uri.buildUpon()
                if (noScheme) {
                    builder.scheme(base.scheme)
                }
                if (noHost) {
                    builder.authority(base.host)
                }
                if (noPath) {
                    builder.path(base.path)
                } else if (noScheme && noHost && !uriOrPath.startsWith("/")) {
                    val path = uri.path
                    var basePath = base.path!!
                    val index = basePath.lastIndexOf('/')
                    basePath = if (index >= 0) basePath.substring(0, index + 1) else "/"
                    builder.path(basePath + path)
                }
                uri = builder.build()
            }
            return uri
        }

        @Throws(JSONException::class)
        private fun extractPackageItem(
            dataVersion: DataVersion,
            chanObject: JSONObject,
            extensionName: String,
            repository: String?,
            uri: Uri,
            installedCode: Long?,
            requireFingerprints: ChanManager.Fingerprints?,
        ): PackageItem? {
            val title: String?
            val versionName: String?
            val versionCode: Long
            val minSdk: Int
            val maxSdk: Int
            val minApiVersion: Int
            val maxApiVersion: Int
            val apiVersion: Int
            val length: Long
            val source: String?
            val fingerprintsArray: JSONArray?
            val fingerprint: String?
            var sha256sumString: String?
            val requireFingerprintChecksum: Boolean
            when (dataVersion) {
                DataVersion.LEGACY -> {
                    title = CommonUtils.getJsonString(chanObject, "title")
                    versionName = CommonUtils.getJsonString(chanObject, "name")
                    versionCode = chanObject.getInt("code").toLong()
                    minApiVersion = chanObject.optInt("minVersion")
                    maxApiVersion = chanObject.optInt("maxVersion")
                    apiVersion = chanObject.optInt("version")
                    minSdk = chanObject.optInt("minSdk")
                    maxSdk = chanObject.optInt("maxSdk")
                    length = chanObject.getLong("length")
                    source = CommonUtils.getJsonString(chanObject, "source")
                    fingerprintsArray = chanObject.optJSONArray("fingerprints")
                    fingerprint = CommonUtils.optJsonString(chanObject, "fingerprint")
                    sha256sumString = null
                    requireFingerprintChecksum = false
                }

                DataVersion.V1 -> {
                    title = CommonUtils.getJsonString(chanObject, "title")
                    versionName = CommonUtils.getJsonString(chanObject, "version_name")
                    versionCode = chanObject.getInt("version_code").toLong()
                    minApiVersion = chanObject.optInt("min_api_version")
                    maxApiVersion = chanObject.optInt("max_api_version")
                    apiVersion = chanObject.optInt("api_version")
                    minSdk = chanObject.optInt("min_sdk")
                    maxSdk = chanObject.optInt("max_sdk")
                    length = chanObject.getLong("length")
                    source = CommonUtils.getJsonString(chanObject, "source")
                    fingerprintsArray = chanObject.optJSONArray("fingerprints")
                    fingerprint = CommonUtils.optJsonString(chanObject, "fingerprint")
                    sha256sumString = CommonUtils.getJsonString(chanObject, "sha256sum")
                    requireFingerprintChecksum = true
                }
            }
            if ((minSdk > 0 && minSdk > Build.VERSION.SDK_INT) ||
                (maxSdk > 0 && maxSdk < Build.VERSION.SDK_INT)
            ) {
                return null
            }
            val rawFingerprints = ArrayList<String>()
            if (fingerprintsArray != null) {
                for (j in 0 until fingerprintsArray.length()) {
                    rawFingerprints.add(fingerprintsArray.optString(j))
                }
            } else if (!fingerprint.isNullOrEmpty()) {
                rawFingerprints.add(fingerprint)
            }
            val fingerprintsSet = HashSet<String>()
            for (rawFingerprint in rawFingerprints) {
                if (!StringUtils.isEmpty(rawFingerprint)) {
                    val normalized =
                        rawFingerprint
                            .replace("[^a-fA-F0-9]".toRegex(), "")
                            .lowercase(Locale.US)
                    if (normalized.length == 64) {
                        fingerprintsSet.add(normalized)
                    }
                }
            }
            if (requireFingerprintChecksum && fingerprintsSet.isEmpty()) {
                return null
            }
            val fingerprints = ChanManager.Fingerprints(fingerprintsSet)
            if (requireFingerprints != null && requireFingerprints != fingerprints) {
                return null
            }
            if (sha256sumString != null) {
                sha256sumString = sha256sumString.replace("[^a-fA-F0-9]".toRegex(), "").lowercase(Locale.US)
            }
            if ((installedCode != null && versionCode < installedCode) || source == null) {
                return null
            }
            var sha256sum: ByteArray? = null
            if (sha256sumString != null && sha256sumString.length == 64) {
                sha256sum = ByteArray(sha256sumString.length / 2)
                for (i in sha256sum.indices) {
                    var h = sha256sumString[2 * i].code
                    var l = sha256sumString[2 * i + 1].code
                    h = if (h >= 'a'.code) h - 'a'.code + 10 else h - '0'.code
                    l = if (l >= 'a'.code) l - 'a'.code + 10 else l - '0'.code
                    sha256sum[i] = ((h shl 4) or l).toByte()
                }
            } else if (requireFingerprintChecksum) {
                return null
            }
            val sourceUri = normalizeRelativeUri(uri, source)
            return if (ChanManager.EXTENSION_NAME_CLIENT == extensionName) {
                if (minApiVersion <= 0 || maxApiVersion <= 0) {
                    null
                } else {
                    PackageItem(
                        repository,
                        title,
                        versionName,
                        versionCode,
                        minApiVersion,
                        maxApiVersion,
                        0,
                        length,
                        sourceUri,
                        sha256sum,
                        fingerprints,
                    )
                }
            } else {
                PackageItem(
                    repository,
                    title,
                    versionName,
                    versionCode,
                    0,
                    0,
                    apiVersion,
                    length,
                    sourceUri,
                    sha256sum,
                    fingerprints,
                )
            }
        }

        @Throws(JSONException::class)
        private fun handleUpdateItems(
            response: Response,
            extensionName: String,
            updateDataMap: HashMap<String, ApplicationItem>,
            fingerprintsMap: HashMap<String, ChanManager.Fingerprints?>,
            installedApks: HashMap<String, InstalledApk>,
            packagesArray: JSONArray,
            updateApplicationItem: ApplicationItem?,
        ) {
            val applicationItem = updateDataMap[extensionName]!!
            if (updateApplicationItem == null || applicationItem.type == updateApplicationItem.type) {
                val installedCode = applicationItem.packageItems[0].versionCode
                val installedApk = installedApks[extensionName]
                val fingerprints = fingerprintsMap[extensionName]
                for (i in 0 until packagesArray.length()) {
                    val packageItem =
                        extractPackageItem(
                            response.dataVersion,
                            packagesArray.getJSONObject(i),
                            extensionName,
                            response.getRepositoryName(),
                            response.uri,
                            installedCode,
                            fingerprints,
                        )
                    // A package can describe the APK that is already installed: its own
                    // release, offered back to it. Nothing can come of installing it, so
                    // it is not a target, and counting it as an update is what turns
                    // metadata disagreeing over a version name into a standing prompt.
                    if (packageItem != null &&
                        installedApk?.isSameBinary(packageItem.length, packageItem.sha256sum) != true
                    ) {
                        applicationItem.packageItems.add(packageItem)
                    }
                }
            }
        }

        @Throws(JSONException::class)
        private fun handleInstallItems(
            response: Response,
            extensionName: String,
            installDataMap: HashMap<String, ApplicationItem>,
            packagesArray: JSONArray,
            installApplicationItem: ApplicationItem?,
        ) {
            for (i in 0 until packagesArray.length()) {
                val packageItem =
                    extractPackageItem(
                        response.dataVersion,
                        packagesArray.getJSONObject(i),
                        extensionName,
                        response.getRepositoryName(),
                        response.uri,
                        null,
                        null,
                    )
                if (packageItem != null) {
                    var applicationItem = installDataMap[extensionName]
                    if (applicationItem == null) {
                        applicationItem =
                            if (installApplicationItem != null) {
                                ApplicationItem(
                                    installApplicationItem.type,
                                    installApplicationItem.name,
                                    installApplicationItem.title,
                                    ArrayList(),
                                )
                            } else {
                                ApplicationItem(
                                    ApplicationItem.Type.CHAN,
                                    extensionName,
                                    extensionName,
                                    ArrayList(),
                                )
                            }
                        installDataMap[extensionName] = applicationItem
                    } else if (installApplicationItem != null &&
                        applicationItem.type != installApplicationItem.type
                    ) {
                        continue
                    }
                    applicationItem.packageItems.add(packageItem)
                }
            }
        }

        private fun readData(
            holder: HttpHolder,
            extensionItems: Iterable<ChanManager.ExtensionItem>,
        ): Iterable<Response> {
            val chan = Chan.getFallback()
            val targets = LinkedHashMap<TargetUri, HashSet<String>>()
            val requestedScheme = HashMap<TargetUri, String>()
            run {
                val uri = Uri.parse(Preferences.uriUpdates)
                val targetUri = TargetUri(uri)
                val extensionNames = HashSet<String>()
                extensionNames.add(ChanManager.EXTENSION_NAME_CLIENT)
                targets[targetUri] = extensionNames
                val scheme = uri.scheme
                if (!scheme.isNullOrEmpty()) {
                    requestedScheme[targetUri] = scheme
                }
            }
            // Separate sources merged with the client one: each covers every installed
            // extension (in addition to each extension's own updateUri) and contributes
            // install suggestions even when no extensions are installed yet.
            for (uriString in Preferences.uriUpdatesExtensions) {
                val uri = Uri.parse(uriString)
                val targetUri = TargetUri(uri)
                var extensionNames = targets[targetUri]
                if (extensionNames == null) {
                    extensionNames = HashSet()
                    targets[targetUri] = extensionNames
                }
                for (extensionItem in extensionItems) {
                    extensionNames.add(extensionItem.name!!)
                }
                val scheme = uri.scheme
                if (!scheme.isNullOrEmpty()) {
                    requestedScheme[targetUri] = scheme
                }
            }
            for (extensionItem in extensionItems) {
                if (extensionItem.updateUri != null) {
                    val targetUri = TargetUri(extensionItem.updateUri)
                    var extensionNames = targets[targetUri]
                    if (extensionNames == null) {
                        extensionNames = HashSet()
                        targets[targetUri] = extensionNames
                    }
                    extensionNames.add(extensionItem.name!!)
                    val scheme = extensionItem.updateUri.scheme
                    if (!scheme.isNullOrEmpty()) {
                        requestedScheme[targetUri] = scheme
                    }
                }
            }

            val responses = LinkedHashMap<TargetUri, Response>()
            // The same manifest is reachable under more than one URI: a renamed GitHub org
            // keeps serving the old raw path, and every extension released before the rename
            // carries that path as its own updateUri. Two URIs answering byte for byte are
            // one source, not two, so they are merged like a target already fetched below --
            // otherwise each of their extensions is offered twice, once per URI.
            val responsesByBody = HashMap<String, Response>()
            for ((key, value) in targets) {
                try {
                    var targetUri = key
                    var targetScheme = requestedScheme[targetUri]
                    var redirects = 0
                    while (redirects++ < 5) {
                        val response = responses[targetUri]
                        if (response != null) {
                            mergeResponse(response, targetScheme, value)
                            break
                        }
                        var responseUri: Uri? = null
                        var responseText: String? = null
                        var responseDataVersion: DataVersion? = null
                        if (targetUri.directory) {
                            var lastHttpException: HttpException? = null
                            val directoryUri = chan.locator.setSchemeIfEmpty(targetUri.uri, targetScheme)
                            for (dataVersion in DataVersion.values()) {
                                val uri = directoryUri!!.buildUpon().appendPath(dataVersion.fileName).build()
                                try {
                                    responseUri = uri
                                    responseText = HttpRequest(uri, holder).perform()!!.readString()
                                    responseDataVersion = dataVersion
                                    lastHttpException = null
                                    break
                                } catch (e: HttpException) {
                                    if (!e.isHttpException() ||
                                        e.getResponseCode() != HttpURLConnection.HTTP_NOT_FOUND
                                    ) {
                                        throw e
                                    } else {
                                        lastHttpException = e
                                    }
                                }
                            }
                            if (lastHttpException != null) {
                                throw lastHttpException
                            }
                        } else {
                            val uri = chan.locator.setSchemeIfEmpty(targetUri.uri, targetScheme)
                            responseUri = uri
                            responseText = HttpRequest(uri, holder).perform()!!.readString()
                            responseDataVersion = DataVersion.LEGACY
                        }
                        // An empty body is not valid JSON; JSONException is already handled
                        // below, whereas the Java's null would have thrown an NPE.
                        val jsonObject = JSONObject(responseText.orEmpty())
                        val redirect = CommonUtils.optJsonString(jsonObject, "redirect")
                        if (redirect != null) {
                            val uri = normalizeRelativeUri(responseUri!!, redirect)
                            targetUri = TargetUri(uri)
                            targetScheme = uri.scheme
                        } else {
                            val body = responseText.orEmpty()
                            val sameBody = responsesByBody[body]
                            if (sameBody != null) {
                                mergeResponse(sameBody, targetScheme, value)
                                // Both URIs now name one response, which the loop above can
                                // still short-circuit on; the duplicate is dropped on return.
                                responses[targetUri] = sameBody
                            } else {
                                val newResponse =
                                    Response(
                                        responseUri!!,
                                        responseDataVersion!!,
                                        jsonObject,
                                        HashSet(value),
                                    )
                                responses[targetUri] = newResponse
                                responsesByBody[body] = newResponse
                            }
                            break
                        }
                    }
                } catch (e: HttpException) {
                    e.printStackTrace()
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                if (Thread.currentThread().isInterrupted) {
                    return emptyList()
                }
            }
            // Response has no equals, so this drops the URIs that share one response object
            // while keeping the order they were requested in.
            return LinkedHashSet(responses.values)
        }

        /**
         * Fold a target into the response another target already produced: the extensions it
         * asked for are added to that response's set, and https is preferred when the two
         * disagree, since a package's relative source is resolved against the response URI.
         */
        private fun mergeResponse(
            response: Response,
            targetScheme: String?,
            extensionNames: Set<String>,
        ) {
            if ("http" == response.uri.scheme && "https" == targetScheme) {
                response.uri =
                    response.uri
                        .buildUpon()
                        .scheme("https")
                        .build()
            }
            response.extensionNames.addAll(extensionNames)
        }
    }
}
