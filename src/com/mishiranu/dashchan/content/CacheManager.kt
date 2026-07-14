package com.mishiranu.dashchan.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.util.Pair
import chan.content.Chan.Companion.getPreferred
import chan.util.StringUtils.formatHex
import chan.util.StringUtils.getFileExtension
import com.mishiranu.dashchan.util.AndroidUtils.OnReceiveListener
import com.mishiranu.dashchan.util.AndroidUtils.createReceiver
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import com.mishiranu.dashchan.util.IOUtils.copyInternalFile
import com.mishiranu.dashchan.util.LruCache
import com.mishiranu.dashchan.util.MimeTypes.forExtension
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.Volatile

class CacheManager private constructor() : Runnable {
    private val cacheItemsToDelete = LinkedBlockingQueue<CacheItem>()

    override fun run() {
        while (true) {
            val cacheItem: CacheItem
            try {
                cacheItem = cacheItemsToDelete.take()
            } catch (e: InterruptedException) {
                return
            }
            var file: File? = null
            when (cacheItem.type) {
                CacheItem.Type.THUMBNAILS -> {
                    file = File(this.thumbnailsDirectory, cacheItem.name)
                }

                CacheItem.Type.MEDIA -> {
                    file = File(this.mediaDirectory, cacheItem.name)
                }
            }
            file.delete()
        }
    }

    @Volatile
    private var cacheBuildingLatch: CountDownLatch? = null

    private class CacheItem(file: File, val type: Type) {
        enum class Type {
            THUMBNAILS, MEDIA
        }

        val name: String
        val nameLc: String
        val length: Long
        var lastModified: Long

        init {
            name = file.getName()
            nameLc = name.lowercase()
            length = file.length()
            lastModified = file.lastModified()
        }

        override fun toString(): String {
            return "CacheItem [\"" + name + "\", " + length + "]"
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is CacheItem) {
                return type == other.type && nameLc == other.nameLc
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + type.hashCode()
            result = prime * result + nameLc.hashCode()
            return result
        }
    }

    private val thumbnailsCache = LinkedHashMap<String?, CacheItem?>()
    private val mediaCache = LinkedHashMap<String?, CacheItem?>()

    private var thumbnailsCacheSize: Long = 0
    private var mediaCacheSize: Long = 0

    private fun fillCache(
        cacheItems: LinkedHashMap<String?, CacheItem?>,
        directory: File?,
        type: CacheItem.Type
    ): Long {
        cacheItems.clear()
        if (directory == null) {
            return 0L
        }
        val cacheItemsList = ArrayList<CacheItem>()
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                cacheItemsList.add(CacheItem(file, type))
            }
        }
        Collections.sort<CacheItem?>(cacheItemsList, SORT_BY_DATE_COMPARATOR)
        var size = 0L
        for (cacheItem in cacheItemsList) {
            cacheItems.put(cacheItem.nameLc, cacheItem)
            size += cacheItem.length
        }
        return size
    }

    private fun syncCache() {
        val latch = CountDownLatch(1)
        cacheBuildingLatch = latch
        Thread(Runnable {
            try {
                synchronized(thumbnailsCache) {
                    thumbnailsCacheSize = fillCache(
                        thumbnailsCache,
                        this.thumbnailsDirectory,
                        CacheItem.Type.THUMBNAILS
                    )
                }
                synchronized(mediaCache) {
                    mediaCacheSize = fillCache(
                        mediaCache,
                        this.mediaDirectory, CacheItem.Type.MEDIA
                    )
                }
                cleanupAsync(true, true)
            } finally {
                latch.countDown()
            }
        }).start()
    }

    private fun cleanupAsync(thumbnails: Boolean, media: Boolean) {
        val maxCache: Int = MAX_THUMBNAILS_PART + MAX_MEDIA_PART
        val maxCacheSize = Preferences.cacheSize * 1000L * 1000L
        var cleanupCacheItems: ArrayList<CacheItem?>? = null
        if (thumbnails) {
            synchronized(thumbnailsCache) {
                val maxSize: Long = MAX_THUMBNAILS_PART * maxCacheSize / maxCache
                if (thumbnailsCacheSize > maxSize) {
                    if (cleanupCacheItems == null) {
                        cleanupCacheItems = ArrayList<CacheItem?>()
                    }
                    thumbnailsCacheSize = obtainCacheItemsToCleanup(
                        cleanupCacheItems, thumbnailsCache,
                        thumbnailsCacheSize, maxSize, null
                    )
                }
            }
        }
        if (media) {
            synchronized(mediaCache) {
                val maxSize: Long = MAX_MEDIA_PART * maxCacheSize / maxCache
                if (mediaCacheSize > maxSize) {
                    if (cleanupCacheItems == null) {
                        cleanupCacheItems = ArrayList<CacheItem?>()
                    }
                    mediaCacheSize = obtainCacheItemsToCleanup(
                        cleanupCacheItems, mediaCache,
                        mediaCacheSize, maxSize, null
                    )
                }
            }
        }
        if (cleanupCacheItems != null && cleanupCacheItems.size > 0) {
            // Start handling
            cacheItemsToDelete.addAll(cleanupCacheItems)
        }
    }

    private fun obtainCacheItemsToCleanup(
        cleanupCacheItems: ArrayList<CacheItem?>,
        cacheItems: LinkedHashMap<String?, CacheItem?>,
        size: Long,
        maxSize: Long,
        deleteCondition: DeleteCondition?
    ): Long {
        var size = size
        val trimAmount = (TRIM_FACTOR * maxSize).toLong()
        var deleteAmount = size - maxSize + trimAmount
        val iterator: MutableIterator<CacheItem?> = cacheItems.values.iterator()
        while (iterator.hasNext() && deleteAmount > 0) {
            val cacheItem = iterator.next()!!
            if (deleteCondition == null || deleteCondition.allowDeleteCacheItem(cacheItem)) {
                deleteAmount -= cacheItem.length
                size -= cacheItem.length
                iterator.remove()
                cleanupCacheItems.add(cacheItem)
            }
        }
        return size
    }

    private interface DeleteCondition {
        fun allowDeleteCacheItem(cacheItem: CacheItem?): Boolean
    }

    private fun waitCacheSync(): Boolean {
        val latch = cacheBuildingLatch
        if (latch != null) {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return false
    }

    private fun getCacheItems(type: CacheItem.Type): LinkedHashMap<String?, CacheItem?> {
        when (type) {
            CacheItem.Type.THUMBNAILS -> {
                return thumbnailsCache
            }

            CacheItem.Type.MEDIA -> {
                return mediaCache
            }
        }
        throw RuntimeException("Unknown cache type")
    }

    private fun modifyCacheSize(type: CacheItem.Type, lengthDelta: Long) {
        when (type) {
            CacheItem.Type.THUMBNAILS -> {
                thumbnailsCacheSize += lengthDelta
            }

            CacheItem.Type.MEDIA -> {
                mediaCacheSize += lengthDelta
            }
        }
    }

    private fun isFileExistsInCache(file: File, fileName: String, type: CacheItem.Type): Boolean {
        if (waitCacheSync()) {
            return false
        }
        val cacheItems = getCacheItems(type)
        synchronized(cacheItems) {
            var cacheItem = cacheItems.get(fileName.lowercase())
            if (cacheItem != null && !file.exists()) {
                cacheItems.remove(cacheItem.nameLc)
                modifyCacheSize(type, -cacheItem.length)
                cacheItem = null
            }
            return cacheItem != null
        }
    }

    private fun updateCachedFileLastModified(file: File, fileName: String, type: CacheItem.Type) {
        if (waitCacheSync()) {
            return
        }
        val cacheItems = getCacheItems(type)
        synchronized(cacheItems) {
            val fileNameLc = fileName.lowercase()
            val cacheItem = cacheItems.remove(fileNameLc)
            if (cacheItem != null) {
                if (file.exists()) {
                    val lastModified = System.currentTimeMillis()
                    file.setLastModified(lastModified)
                    cacheItem.lastModified = lastModified
                    cacheItems.put(fileNameLc, cacheItem)
                } else {
                    modifyCacheSize(type, -cacheItem.length)
                }
            }
        }
    }

    private fun validateNewCachedFile(
        file: File,
        fileName: String,
        type: CacheItem.Type,
        success: Boolean
    ) {
        if (waitCacheSync()) {
            return
        }
        val cacheItems = getCacheItems(type)
        synchronized(cacheItems) {
            var lengthDelta = 0L
            var cacheItem = cacheItems.remove(fileName.lowercase())
            if (cacheItem != null) {
                lengthDelta = -cacheItem.length
            }
            if (success) {
                cacheItem = CacheItem(file, type)
                cacheItems.put(cacheItem.nameLc, cacheItem)
                lengthDelta += cacheItem.length
            }
            modifyCacheSize(type, lengthDelta)
            if (success) {
                cleanupAsync(type == CacheItem.Type.THUMBNAILS, type == CacheItem.Type.MEDIA)
            }
        }
    }

    val isCacheAvailable: Boolean
        get() = Preferences.isUseInternalStorageForCache || Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

    val cacheSize: Long
        get() {
            if (waitCacheSync()) {
                return 0L
            }
            return thumbnailsCacheSize + mediaCacheSize
        }

    private val thumbnailsDirectory: File?
        get() = getCacheDirectory("thumbnails")

    val mediaDirectory: File?
        get() = getCacheDirectory("media")

    private fun getCacheDirectory(name: String): File? {
        val directory = getCacheDirectory()
        if (directory == null) {
            return null
        }
        val file = File(directory, name)
        if (this.isCacheAvailable && !file.exists()) {
            file.mkdirs()
        }
        return file
    }

    private fun getMediaFile(fileName: String, touch: Boolean): File? {
        val directory = this.mediaDirectory
        if (directory == null) {
            return null
        }
        val file = File(directory, fileName)
        if (touch) {
            updateCachedFileLastModified(file, fileName, CacheItem.Type.MEDIA)
        }
        return file
    }

    fun getMediaFile(uri: Uri?, touch: Boolean): File? {
        return getMediaFile(getCachedFileKey(uri)!!, touch)
    }

    fun getPartialMediaFile(uri: Uri?): File? {
        return getMediaFile(getCachedFileKey(uri) + ".part", false)
    }

    @Throws(InterruptedException::class)
    private fun eraseCache(
        cacheItems: LinkedHashMap<String?, CacheItem?>, directory: File?,
        deleteCondition: DeleteCondition?
    ): Long {
        if (directory == null) {
            return 0L
        }
        var deleted = 0L
        val iterator: MutableIterator<CacheItem?> = cacheItems.values.iterator()
        while (iterator.hasNext()) {
            if (Thread.interrupted()) {
                throw InterruptedException()
            }
            val cacheItem = iterator.next()!!
            if (deleteCondition == null || deleteCondition.allowDeleteCacheItem(cacheItem)) {
                deleted += cacheItem.length
                File(directory, cacheItem.name).delete()
                iterator.remove()
            }
        }
        return deleted
    }

    @Throws(InterruptedException::class)
    fun eraseThumbnailsCache() {
        synchronized(thumbnailsCache) {
            eraseCache(thumbnailsCache, this.thumbnailsDirectory, null)
            thumbnailsCacheSize = 0L
        }
    }

    @Throws(InterruptedException::class)
    fun eraseMediaCache() {
        synchronized(mediaCache) {
            eraseCache(mediaCache, this.mediaDirectory, null)
            mediaCacheSize = 0L
        }
    }

    private val cachedFileKeys = LruCache<String?, String?>(1000)

    fun getCachedFileKey(uri: Uri?): String? {
        if (uri != null) {
            val data: String?
            val scheme = uri.getScheme()
            if ("data" == scheme) {
                val uriString = uri.toString()
                val index = uriString.indexOf("base64,")
                if (index >= 0) {
                    data = uriString.substring(index + 7)
                } else {
                    data = uriString
                }
            } else if ("chan" == scheme) {
                data = uri.toString()
            } else {
                val chan = getPreferred(null, uri)
                val path = uri.getPath()
                val query = uri.getQuery()
                val dataBuilder = StringBuilder()
                if (chan.name != null) {
                    dataBuilder.append(chan.name)
                }
                dataBuilder.append(path)
                if (query != null) {
                    dataBuilder.append('?').append(query)
                }
                data = dataBuilder.toString()
            }
            val cachedFileKeys = this.cachedFileKeys
            synchronized(cachedFileKeys) {
                val hash = cachedFileKeys.get(data)
                if (hash != null) {
                    return hash
                }
            }
            val hash = formatHex(getInstanceSha256().calculate(data))
            synchronized(cachedFileKeys) {
                cachedFileKeys.put(data, hash)
            }
            return hash
        }
        return null
    }

    fun cancelCachedMediaBusy(file: File): Boolean {
        if (cacheItemsToDelete.remove(CacheItem(file, CacheItem.Type.MEDIA))) {
            file.delete()
            return true
        }
        return false
    }

    fun handleDownloadedFile(file: File, success: Boolean) {
        val directory = file.getParentFile()
        if (directory != null) {
            var type: CacheItem.Type? = null
            if (directory == this.thumbnailsDirectory) {
                type = CacheItem.Type.THUMBNAILS
            } else if (directory == this.mediaDirectory) {
                type = CacheItem.Type.MEDIA
            }
            if (type != null) {
                validateNewCachedFile(file, file.getName(), type, success)
            }
        }
    }

    fun getThumbnailFile(thumbnailKey: String): File? {
        val directory = this.thumbnailsDirectory
        if (directory == null) {
            return null
        }
        return File(directory, thumbnailKey)
    }

    fun loadThumbnailExternal(thumbnailKey: String): Bitmap? {
        if (!this.isCacheAvailable) {
            return null
        }
        val file = getThumbnailFile(thumbnailKey)
        if (file == null) {
            return null
        }
        if (!isFileExistsInCache(file, thumbnailKey, CacheItem.Type.THUMBNAILS)) {
            return null
        }
        val bitmap: Bitmap?
        try {
            // The default allocator yields a HARDWARE bitmap (GPU memory, zero-copy drawing)
            // with an automatic software fallback when hardware allocation is not possible.
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
            updateCachedFileLastModified(file, thumbnailKey, CacheItem.Type.THUMBNAILS)
            return bitmap
        } catch (e: ImageDecoder.DecodeException) {
            file.delete()
            return null
        } catch (e: IOException) {
            return null
        }
    }

    fun storeThumbnailExternal(thumbnailKey: String, data: Bitmap) {
        if (!this.isCacheAvailable) {
            return
        }
        val directory = this.thumbnailsDirectory
        if (directory == null) {
            return
        }
        var success = false
        val file = File(directory, thumbnailKey)
        try {
            FileOutputStream(file).use { output ->
                data.compress(Bitmap.CompressFormat.PNG, 100, output)
                success = true
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            validateNewCachedFile(file, thumbnailKey, CacheItem.Type.THUMBNAILS, success)
        }
    }

    private val directoryLocker = Any()

    @Volatile
    private var cacheDirectory: File? = null

    @Volatile
    private var tempDirectory: File? = null

    fun rebuildCache() {
        waitCacheSync()
        cacheDirectory = null
        tempDirectory = null
        syncCache()
    }

    // May be null: getExternalCacheDir() returns null while external storage is unmounted.
    fun getCacheDirectory(): File? {
        if (cacheDirectory == null) {
            synchronized(directoryLocker) {
                if (cacheDirectory == null) {
                    val application = MainApplication.getInstance()
                    cacheDirectory =
                        if (Preferences.isUseInternalStorageForCache) application.getCacheDir() else application.getExternalCacheDir()
                }
            }
        }
        return cacheDirectory
    }

    private fun getTempDirectory(): File? {
        if (tempDirectory == null) {
            synchronized(directoryLocker) {
                if (tempDirectory == null) {
                    val application = MainApplication.getInstance()
                    tempDirectory =
                        if (Preferences.isUseInternalStorageForCache) application.getCacheDir() else application.getExternalCacheDir()
                }
            }
        }
        return tempDirectory
    }

    fun getInternalCacheFile(fileName: String): File? {
        // Context.getCacheDir() is declared @NonNull by the framework, so the
        // defensive null check the Java had here can never fire.
        val cacheDirectory = MainApplication.getInstance().getCacheDir()
        return File(cacheDirectory, fileName)
    }

    init {
        if (MainApplication.getInstance().isMainProcess()) {
            cleanupTemporaryFiles()
            syncCache()
            val intentFilter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED)
            intentFilter.addDataScheme("file")
            MainApplication.getInstance()
                .registerReceiver(
                    createReceiver(OnReceiveListener { r: BroadcastReceiver?, c: Context?, i: Intent? -> syncCache() }),
                    intentFilter
                )
            Thread(this, "CacheManagerWorker").start()
        }
    }

    private fun cleanupTemporaryFiles() {
        val tempDirectory = getTempDirectory()
        if (tempDirectory == null) {
            return
        }
        val time = System.currentTimeMillis()
        val files = tempDirectory.listFiles()
        if (files != null) {
            for (tempFile in files) {
                val tempFileName = tempFile.getName()
                if (tempFileName.startsWith(CLIPBOARD_FILE_NAME_START) || tempFileName.startsWith(
                        GALLERY_SHARE_FILE_NAME_START
                    )
                ) {
                    val delete = tempFile.lastModified() + 60 * 60 * 1000 < time // 1 hour
                    if (delete) {
                        tempFile.delete()
                    }
                }
            }
        }
    }

    fun prepareFileForShare(file: File, fileName: String?): Pair<Uri?, String?>? {
        return createTemporaryFileForExternalApplication(
            file,
            fileName,
            GALLERY_SHARE_FILE_NAME_START + System.currentTimeMillis(),
            FileUriProvider { directory: File?, file: File?, type: String? ->
                FileProvider.Companion.convertShareFile(
                    directory!!,
                    file!!,
                    type
                )
            })
    }

    fun prepareFileForClipboard(file: File, originalFileName: String?): Uri? {
        val data = createTemporaryFileForExternalApplication(
            file,
            originalFileName,
            CLIPBOARD_FILE_NAME_START + System.currentTimeMillis(),
            FileUriProvider { directory: File?, file: File?, type: String? ->
                FileProvider.Companion.convertClipboardFile(
                    directory!!,
                    file!!,
                    type
                )
            })
        if (data != null) {
            return data.first
        } else {
            return null
        }
    }

    private fun createTemporaryFileForExternalApplication(
        originalFile: File,
        originalFileName: String?,
        fileName: String?,
        fileURIProvider: FileUriProvider
    ): Pair<Uri?, String?>? {
        var fileName = fileName
        val tempDirectory = getTempDirectory()
        if (tempDirectory == null) {
            return null
        }

        cleanupTemporaryFiles()

        var extension = getFileExtension(originalFileName)
        var mimeType = forExtension(extension)
        if (mimeType == null) {
            mimeType = "image/jpeg"
            extension = "jpg"
        }

        fileName = fileName + "." + extension

        val temporaryFile = File(tempDirectory, fileName)
        copyInternalFile(originalFile, temporaryFile)
        val uri = fileURIProvider.getUri(tempDirectory, temporaryFile, mimeType)
        return Pair<Uri?, String?>(uri, mimeType)
    }

    private fun interface FileUriProvider {
        fun getUri(directory: File?, file: File?, mimeType: String?): Uri?
    }

    class CacheException(errorMessage: String?) : Exception(errorMessage)

    @Throws(CacheException::class)
    fun getMediaFileOrThrow(uri: Uri?, touch: Boolean): File {
        val directory = this.mediaDirectoryOrThrow
        // getCachedFileKey() only returns null for a null uri, on which the Java
        // blew up inside new File(parent, null). Report it as a CacheException
        // instead: callers of this method already handle that.
        val fileName = getCachedFileKey(uri) ?: throw CacheException("Cache file key is not available")
        val file = File(directory, fileName)
        if (touch) {
            updateCachedFileLastModified(file, fileName, CacheItem.Type.MEDIA)
        }
        return file
    }

    @get:Throws(CacheException::class)
    val mediaDirectoryOrThrow: File
        get() {
            if (!this.isCacheAvailable) {
                throw CacheException("Cache storage is not available")
            }

            val cacheDirectory = getCacheDirectory()
            if (cacheDirectory == null) {
                throw CacheException("Cache directory is not available")
            }
            if (!cacheDirectory.exists()) {
                throw CacheException("Cache directory doesn't exist")
            }

            val mediaCacheDirectory = File(cacheDirectory, "media")
            if (!mediaCacheDirectory.exists()) {
                val mediaCacheDirectoryCreated = mediaCacheDirectory.mkdirs()
                if (!mediaCacheDirectoryCreated) {
                    throw CacheException("Cannot create media cache directory")
                }
            }

            return mediaCacheDirectory
        }

    companion object {
        private const val MAX_THUMBNAILS_PART = 1
        private const val MAX_MEDIA_PART = 2

        private const val TRIM_FACTOR = 0.3f

        private val INSTANCE = CacheManager()

        @JvmStatic
        fun getInstance(): CacheManager = INSTANCE

        private val SORT_BY_DATE_COMPARATOR =
            Comparator { lhs: CacheItem?, rhs: CacheItem? -> lhs!!.lastModified.compareTo(rhs!!.lastModified) }

        private const val GALLERY_SHARE_FILE_NAME_START = "gallery-share-"
        private const val CLIPBOARD_FILE_NAME_START = "clipboard-"
    }
}
