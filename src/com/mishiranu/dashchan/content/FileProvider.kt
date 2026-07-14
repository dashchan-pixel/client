package com.mishiranu.dashchan.content

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import chan.util.CommonUtils.equals
import java.io.File
import java.io.FileNotFoundException

class FileProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    private class InternalFile(val file: File?, val type: String?, val uri: Uri?)

    override fun getType(uri: Uri): String? {
        val uriMatcherCode: Int = URI_MATCHER.match(uri)
        if (uriMatcherCode == URI_MATCHER_CODE_UPDATES) {
            return "application/vnd.android.package-archive"
        } else {
            val internalFile: InternalFile? = getInternalFileForUriMatcherCode(uriMatcherCode)
            if (internalFile != null) {
                return internalFile.type
            } else {
                throw IllegalArgumentException("Unknown URI: " + uri)
            }
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val uriMatcherCode: Int = URI_MATCHER.match(uri)
        var file: File? = null
        if (uriMatcherCode == URI_MATCHER_CODE_UPDATES && "r" == mode) {
            file = Companion.getUpdatesFile(uri.getLastPathSegment()!!)
        } else {
            val internalFile: InternalFile? = getInternalFileForUriMatcherCode(uriMatcherCode)
            if (internalFile != null && uri == internalFile.uri) {
                file = internalFile.file
            }
        }

        if (file != null) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            throw FileNotFoundException()
        }
    }

    override fun query(
        uri: Uri, projection: Array<String?>?,
        selection: String?, selectionArgs: Array<String?>?, sortOrder: String?
    ): Cursor {
        var projection = projection
        val uriMatcherCode: Int = URI_MATCHER.match(uri)
        require(uriMatcherCode != UriMatcher.NO_MATCH) { "Unknown URI: " + uri }

        if (projection == null) {
            projection = ALLOWED_PROJECTION
        } else {
            OUTER@ for (column in projection) {
                for (allowedColumn in ALLOWED_PROJECTION) {
                    if (equals(column, allowedColumn)) {
                        continue@OUTER
                    }
                }
                throw SQLiteException("No such column: " + column)
            }
        }

        val cursor = MatrixCursor(projection)
        val file: File?
        if (uriMatcherCode == URI_MATCHER_CODE_UPDATES) {
            file = Companion.getUpdatesFile(uri.getLastPathSegment()!!)
        } else {
            val internalFile: InternalFile? = getInternalFileForUriMatcherCode(uriMatcherCode)
            file = if (internalFile != null) internalFile.file else null
        }
        if (file != null) {
            val values = arrayOfNulls<Any>(projection.size)
            for (i in projection.indices) {
                if (OpenableColumns.DISPLAY_NAME == projection[i]) {
                    values[i] = file.getName()
                } else if (OpenableColumns.SIZE == projection[i]) {
                    values[i] = file.length()
                }
            }
            cursor.addRow(values)
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        private const val AUTHORITY = "com.mishiranu.providers.dashchan"
        private const val PATH_UPDATES = "updates"
        private const val PATH_DOWNLOADS = "downloads"
        private const val PATH_SHARE = "share"
        private const val PATH_CLIPBOARD = "clipboard"

        private const val URI_MATCHER_CODE_UPDATES = 1
        private const val URI_MATCHER_CODE_DOWNLOADS = 2
        private const val URI_MATCHER_CODE_SHARE = 3
        private const val URI_MATCHER_CODE_CLIPBOARD = 4

        private val URI_MATCHER: UriMatcher

        init {
            URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH)
            URI_MATCHER.addURI(AUTHORITY, PATH_UPDATES + "/*", URI_MATCHER_CODE_UPDATES)
            URI_MATCHER.addURI(AUTHORITY, PATH_DOWNLOADS + "/*", URI_MATCHER_CODE_DOWNLOADS)
            URI_MATCHER.addURI(AUTHORITY, PATH_SHARE + "/*", URI_MATCHER_CODE_SHARE)
            URI_MATCHER.addURI(AUTHORITY, PATH_CLIPBOARD + "/*", URI_MATCHER_CODE_CLIPBOARD)
        }

        val updatesDirectory: File?
            get() {
                var directory = MainApplication.getInstance().getExternalCacheDir()
                if (directory == null) {
                    return null
                }
                directory = File(directory, "updates")
                directory.mkdirs()
                return directory
            }

        fun getUpdatesFile(name: String): File? {
            val directory: File? =
                updatesDirectory
            if (directory != null) {
                val file = File(directory, name)
                if (file.exists()) {
                    return file
                }
            }
            return null
        }

        fun convertUpdatesUri(uri: Uri): Uri? {
            if ("file" == uri.getScheme()) {
                // Uri.getPath() is nullable (opaque URIs); a null path simply
                // can't match the updates directory, so fall through to return uri.
                val fileParent = uri.getPath()?.let { File(it).getParentFile() }
                val directory: File? =
                    updatesDirectory
                if (fileParent != null && fileParent == directory) {
                    return Uri.Builder().scheme("content").authority(AUTHORITY)
                        .appendPath(PATH_UPDATES).appendPath(uri.getLastPathSegment()).build()
                }
            }
            return uri
        }

        private var downloadsFile: InternalFile? = null
        private var shareFile: InternalFile? = null
        private var clipboardFile: InternalFile? = null

        @JvmStatic
        fun convertDownloadsLegacyFile(file: File, type: String?): Uri? {
            return convertToInternalFile(
                Preferences.downloadDirectoryLegacy,
                file,
                type,
                PATH_DOWNLOADS,
                URI_MATCHER_CODE_DOWNLOADS
            )
        }

        fun convertShareFile(directory: File, file: File, type: String?): Uri? {
            return convertToInternalFile(directory, file, type, PATH_SHARE, URI_MATCHER_CODE_SHARE)
        }

        fun convertClipboardFile(directory: File, file: File, type: String?): Uri? {
            return convertToInternalFile(
                directory,
                file,
                type,
                PATH_CLIPBOARD,
                URI_MATCHER_CODE_CLIPBOARD
            )
        }

        private fun convertToInternalFile(
            directory: File,
            file: File,
            type: String?,
            path: String?,
            uriMatcherCode: Int
        ): Uri? {
            val internalFile: InternalFile? = createInternalFile(directory, file, type, path)
            if (internalFile != null) {
                when (uriMatcherCode) {
                    URI_MATCHER_CODE_DOWNLOADS -> {
                        downloadsFile = internalFile
                    }

                    URI_MATCHER_CODE_SHARE -> {
                        shareFile = internalFile
                    }

                    URI_MATCHER_CODE_CLIPBOARD -> {
                        clipboardFile = internalFile
                    }

                    else -> {
                        throw IllegalArgumentException("No internal file for uri matcher code: " + uriMatcherCode)
                    }
                }
                return internalFile.uri
            }
            return Uri.fromFile(file)
        }

        private fun createInternalFile(
            directory: File,
            file: File,
            type: String?,
            providerPath: String?
        ): InternalFile? {
            var filePath = file.getAbsolutePath()
            val directoryPath = directory.getAbsolutePath()
            if (filePath.startsWith(directoryPath)) {
                filePath = filePath.substring(directoryPath.length)
                if (filePath.startsWith("/")) {
                    filePath = filePath.substring(1)
                }
                val uri = Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath(providerPath).appendEncodedPath(filePath).build()
                return InternalFile(file, type, uri)
            }

            return null
        }

        private fun getInternalFileForUriMatcherCode(uriMatcherCode: Int): InternalFile? {
            val internalFile: InternalFile?
            when (uriMatcherCode) {
                URI_MATCHER_CODE_DOWNLOADS -> {
                    internalFile = downloadsFile
                }

                URI_MATCHER_CODE_SHARE -> {
                    internalFile = shareFile
                }

                URI_MATCHER_CODE_CLIPBOARD -> {
                    internalFile = clipboardFile
                }

                else -> {
                    internalFile = null
                }
            }
            return internalFile
        }

        private val ALLOWED_PROJECTION =
            arrayOf<String?>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
