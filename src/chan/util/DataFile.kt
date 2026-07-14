package chan.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Pair
import chan.annotation.Public
import chan.util.StringUtils
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.FileProvider
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.MimeTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Public
abstract class DataFile protected constructor(
    val target: Target,
    private val path: String,
) {
    enum class Target(
        internal val safTarget: SafFile.SafTarget?,
        internal val legacyDirectory: LegacyDirectory,
    ) {
        CACHE(null, LegacyDirectory { CacheManager.getInstance().mediaDirectory!! }),
        UPDATES(null, LegacyDirectory { FileProvider.updatesDirectory!! }),
        DOWNLOADS(SafFile.SafTarget.DOWNLOADS, LegacyDirectory { Preferences.downloadDirectoryLegacy }),
        ;

        internal fun interface LegacyDirectory {
            fun getLegacyDirectory(): File
        }

        val isExternal: Boolean
            get() = safTarget != null
    }

    fun getRelativePath(): String = path

    abstract fun getFileOrUri(): Pair<File?, Uri?>

    abstract fun exists(): Boolean

    @Public
    abstract fun getName(): String?

    @Public
    abstract fun isDirectory(): Boolean

    @Public
    abstract fun getLastModified(): Long

    @Public
    abstract fun getChild(path: String?): DataFile

    @Public
    abstract fun getChildren(): List<DataFile>?

    @Public
    abstract fun delete(): Boolean

    @Public
    @Throws(IOException::class)
    abstract fun openInputStream(): InputStream

    @Public
    @Throws(IOException::class)
    abstract fun openOutputStream(): OutputStream

    private class RegularFile(
        target: Target,
        path: String,
    ) : DataFile(target, path) {
        private val file: File =
            run {
                val directory = target.legacyDirectory.getLegacyDirectory()
                if (path.isEmpty()) directory else File(directory, path)
            }

        override fun getFileOrUri(): Pair<File?, Uri?> = Pair(file, null)

        override fun exists(): Boolean = file.exists()

        override fun getName(): String = file.name

        override fun isDirectory(): Boolean = file.isDirectory

        override fun getLastModified(): Long = file.lastModified()

        override fun getChild(path: String?): DataFile {
            val relativePath = getRelativePath()
            return when {
                path.isNullOrEmpty() -> this
                relativePath.isEmpty() -> RegularFile(target, validatePath(path))
                else -> RegularFile(target, validatePath("$relativePath/$path"))
            }
        }

        override fun getChildren(): List<DataFile>? {
            val names = file.list() ?: return null
            return names.map { getChild(it) }
        }

        override fun delete(): Boolean = file.delete()

        @Throws(IOException::class)
        override fun openInputStream(): InputStream = FileInputStream(file)

        @Throws(IOException::class)
        override fun openOutputStream(): OutputStream {
            file.parentFile?.mkdirs()
            return FileOutputStream(file)
        }
    }

    internal class SafFile private constructor(
        target: Target,
        path: String,
        private var resolution: Resolution,
    ) : DataFile(target, path) {
        internal enum class SafTarget(
            val uriTree: UriTree,
        ) {
            DOWNLOADS(UriTree { context -> Preferences.getDownloadUriTree(context) }),
            ;

            internal fun interface UriTree {
                fun getUriTree(context: Context): Uri?
            }
        }

        private enum class CursorExtra { LOADING, ERROR }

        private class Resolution(
            val documentUri: Uri?,
            val unresolvedPath: String?,
            val cursorExtra: CursorExtra?,
            val isDirectory: Boolean,
            val lastModified: Long,
        )

        internal constructor(target: Target, path: String) :
            this(target, path, resolveChild(getDocumentUriFromTree(target.safTarget!!), path))

        override fun getFileOrUri(): Pair<File?, Uri?> {
            val resolution = this.resolution
            return Pair(null, if (exists(resolution)) resolution.documentUri else null)
        }

        override fun exists(): Boolean = exists(resolution)

        override fun getName(): String? {
            val relativePath = getRelativePath()
            if (relativePath.isEmpty()) {
                val documentUri = getDocumentUriFromTree(target.safTarget!!) ?: return null
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                try {
                    contentResolver.query(documentUri, projection, null, null, null).use { cursor ->
                        if (cursor == null) {
                            return null
                        }
                        return if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    return null
                }
            } else {
                val index = relativePath.lastIndexOf('/')
                return if (index >= 0) relativePath.substring(index + 1) else relativePath
            }
        }

        override fun isDirectory(): Boolean = resolution.isDirectory

        override fun getLastModified(): Long = resolution.lastModified

        override fun getChild(path: String?): DataFile {
            if (path.isNullOrEmpty()) {
                return this
            }
            val relativePath = getRelativePath()
            val fullPath = validatePath(if (relativePath.isEmpty()) path else "$relativePath/$path")
            val unresolvedPath =
                validatePath(
                    resolution.unresolvedPath
                        ?.let { "$it/$path" } ?: path,
                )
            val resolution = resolveChild(this.resolution.documentUri, unresolvedPath)
            return SafFile(target, fullPath, resolution)
        }

        override fun getChildren(): List<DataFile>? {
            val resolution = this.resolution
            if (!exists(resolution) || !resolution.isDirectory) {
                return null
            }
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    resolution.documentUri,
                    DocumentsContract.getDocumentId(resolution.documentUri),
                )
            try {
                contentResolver.query(childrenUri, PROJECTION_CHILD_EXTRA, null, null, null).use { cursor ->
                    if (cursor == null) {
                        return emptyList()
                    }
                    val relativePath = getRelativePath()
                    val result = ArrayList<DataFile>(cursor.count)
                    while (cursor.moveToNext()) {
                        val documentUri =
                            DocumentsContract.buildDocumentUriUsingTree(
                                childrenUri,
                                cursor.getString(0),
                            )
                        val name = cursor.getString(1)
                        val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == cursor.getString(2)
                        val lastModified = cursor.getLong(3)
                        val path = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        result.add(
                            SafFile(
                                target,
                                path,
                                Resolution(
                                    documentUri,
                                    null,
                                    null,
                                    isDirectory,
                                    lastModified,
                                ),
                            ),
                        )
                    }
                    return result
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
                return null
            }
        }

        override fun delete(): Boolean {
            val resolution = this.resolution
            if (exists(resolution)) {
                try {
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            resolution.documentUri,
                            DocumentsContract.getTreeDocumentId(resolution.documentUri),
                        )
                    this.resolution = Resolution(documentUri, getRelativePath(), null, false, 0L)
                    return DocumentsContract.deleteDocument(contentResolver, resolution.documentUri!!)
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
            return false
        }

        @Throws(IOException::class)
        override fun openInputStream(): InputStream {
            val resolution = this.resolution
            if (!exists(resolution)) {
                throw FileNotFoundException("File not found")
            }
            try {
                return contentResolver.openInputStream(resolution.documentUri!!)
                    ?: throw FileNotFoundException("File not found")
            } catch (e: SecurityException) {
                throw IOException(e)
            }
        }

        @Throws(IOException::class)
        override fun openOutputStream(): OutputStream {
            var resolution = this.resolution
            var mayUpdateResolution = true
            if (resolution.documentUri == null) {
                val documentUri =
                    getDocumentUriFromTree(target.safTarget!!)
                        ?: throw FileNotFoundException("No access")
                resolution = resolveChild(documentUri, resolution.unresolvedPath!!)
                this.resolution = resolution
                mayUpdateResolution = false
            }
            if (exists(resolution)) {
                try {
                    return contentResolver.openOutputStream(resolution.documentUri!!)
                        ?: throw FileNotFoundException("No access")
                } catch (e: SecurityException) {
                    throw IOException(e)
                }
            }
            if (resolution.unresolvedPath.isNullOrEmpty()) {
                throw FileNotFoundException("No access")
            }
            if (mayUpdateResolution && resolution.cursorExtra != null) {
                resolution = resolveChild(resolution.documentUri, resolution.unresolvedPath!!)
                this.resolution = resolution
            }
            if (resolution.cursorExtra != null) {
                throw IOException("Tree is not ready: " + resolution.cursorExtra!!.name)
            }
            val segments = resolution.unresolvedPath!!.split("/")
            var childDocumentUri = resolution.documentUri
            for (i in 0 until segments.size - 1) {
                val displayName = segments[i]
                try {
                    childDocumentUri =
                        DocumentsContract.createDocument(
                            contentResolver,
                            childDocumentUri!!,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            displayName,
                        )
                } catch (e: SecurityException) {
                    throw IOException(e)
                }
                if (childDocumentUri == null) {
                    throw FileNotFoundException("Couldn't create a directory $displayName")
                }
            }
            val name = segments[segments.size - 1]
            val mimeType =
                MimeTypes.forExtension(
                    StringUtils.getFileExtension(name),
                    "application/octet-stream",
                )!!
            try {
                val documentUri =
                    DocumentsContract.createDocument(
                        contentResolver,
                        childDocumentUri!!,
                        mimeType,
                        name,
                    )
                        ?: throw FileNotFoundException("Couldn't create a file $name")
                this.resolution = Resolution(documentUri, null, null, false, System.currentTimeMillis())
                return contentResolver.openOutputStream(documentUri)
                    ?: throw FileNotFoundException("Couldn't create a file $name")
            } catch (e: SecurityException) {
                throw IOException(e)
            }
        }

        private class ChildExtra {
            var isDirectory = false
            var lastModified = 0L
        }

        companion object {
            private val contentResolver: ContentResolver
                get() = MainApplication.getInstance().contentResolver

            private fun getDocumentUriFromTree(safTarget: SafTarget): Uri? {
                val treeUri = safTarget.uriTree.getUriTree(MainApplication.getInstance()) ?: return null
                return DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            }

            private fun exists(resolution: Resolution): Boolean = resolution.documentUri != null && resolution.unresolvedPath == null

            private fun resolveChild(
                documentUri: Uri?,
                path: String,
            ): Resolution {
                if (path.isEmpty()) {
                    return Resolution(documentUri, null, null, true, 0L)
                }
                if (documentUri == null) {
                    return Resolution(null, path, null, false, 0L)
                }
                var parentUri = documentUri
                val segments: Array<String?> = path.split("/").toTypedArray()
                var success = true
                var cursorExtra: CursorExtra? = null
                for (i in 0 until segments.size - 1) {
                    val segment = segments[i]!!
                    val uriOrExtra = findChildDocumentUriOrExtra(parentUri!!, segment, null)
                    if (uriOrExtra is Uri) {
                        parentUri = uriOrExtra
                        segments[i] = null
                    } else {
                        success = false
                        cursorExtra = uriOrExtra as CursorExtra?
                        break
                    }
                }
                if (success) {
                    val name = segments[segments.size - 1]!!
                    val childExtra = ChildExtra()
                    val uriOrExtra = findChildDocumentUriOrExtra(parentUri!!, name, childExtra)
                    return if (uriOrExtra is Uri) {
                        Resolution(uriOrExtra, null, null, childExtra.isDirectory, childExtra.lastModified)
                    } else {
                        Resolution(parentUri, name, uriOrExtra as CursorExtra?, true, 0L)
                    }
                } else {
                    val builder = StringBuilder()
                    for (segment in segments) {
                        if (segment != null) {
                            if (builder.isNotEmpty()) {
                                builder.append('/')
                            }
                            builder.append(segment)
                        }
                    }
                    return Resolution(parentUri, builder.toString(), cursorExtra, true, 0L)
                }
            }

            private val PROJECTION_CHILD_SIMPLE =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
            private val PROJECTION_CHILD_EXTRA =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )

            /** Returns the child document [Uri], a [CursorExtra] describing why it's unavailable, or null. */
            private fun findChildDocumentUriOrExtra(
                parentDocumentUri: Uri,
                childDocumentDisplayName: String,
                childExtra: ChildExtra?,
            ): Any? {
                val parentDocumentId = DocumentsContract.getDocumentId(parentDocumentUri)
                val childDocumentId = "$parentDocumentId/$childDocumentDisplayName"
                val childUri = DocumentsContract.buildDocumentUriUsingTree(parentDocumentUri, childDocumentId)
                val projection = if (childExtra != null) PROJECTION_CHILD_EXTRA else PROJECTION_CHILD_SIMPLE

                // Try to open a document; if the document doesn't exist IllegalArgumentException is thrown
                return try {
                    contentResolver.query(childUri, projection, null, null, null).use { cursor: Cursor? ->
                        if (cursor == null) {
                            return null
                        }
                        if (cursor.count != 1) {
                            val loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                            val error = cursor.extras.getBoolean(DocumentsContract.EXTRA_ERROR)
                            return if (loading) {
                                CursorExtra.LOADING
                            } else if (error) {
                                CursorExtra.ERROR
                            } else {
                                null
                            }
                        }
                        cursor.moveToFirst()
                        if (childExtra != null) {
                            childExtra.isDirectory =
                                DocumentsContract.Document.MIME_TYPE_DIR == cursor.getString(2)
                            childExtra.lastModified = cursor.getLong(3)
                        }
                        childUri
                    }
                } catch (e: Exception) {
                    if (e !is IllegalArgumentException) {
                        e.printStackTrace()
                    }
                    null
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun isValidSegment(segment: String?): Boolean = !segment.isNullOrEmpty() && segment != "." && segment != ".."

        private fun validatePath(path: String?): String {
            if (path.isNullOrEmpty()) {
                return ""
            }
            val newPath = StringBuilder()
            for (segment in path.split("/")) {
                if (isValidSegment(segment)) {
                    if (newPath.isNotEmpty()) {
                        newPath.append('/')
                    }
                    newPath.append(segment)
                }
            }
            return newPath.toString()
        }

        @JvmStatic
        fun obtain(
            target: Target,
            path: String?,
        ): DataFile =
            if (target.safTarget != null) {
                SafFile(target, validatePath(path))
            } else {
                RegularFile(target, validatePath(path))
            }
    }
}
