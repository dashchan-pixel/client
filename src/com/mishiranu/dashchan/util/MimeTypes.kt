package com.mishiranu.dashchan.util

import android.webkit.MimeTypeMap
import java.util.Locale

object MimeTypes {
    private val PAIRS =
        listOf(
            "json" to "application/json",
            "ogg" to "application/ogg",
            "pdf" to "application/pdf",
            "apk" to "application/vnd.android.package-archive",
            "xhtml" to "application/xhtml+xml",
            "swf" to "application/x-shockwave-flash",
            "tar" to "application/x-tar",
            "zip" to "application/zip",
            "aac" to "audio/aac",
            "flac" to "audio/flac",
            "mid" to "audio/midi",
            "midi" to "audio/midi",
            "m4a" to "audio/mp4",
            "mp3" to "audio/mpeg",
            "oga" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/x-wav",
            "gif" to "image/gif",
            "jpeg" to "image/jpeg",
            "jpe" to "image/jpeg",
            "jpg" to "image/jpeg",
            "png" to "image/png",
            "apng" to "image/png",
            "svg" to "image/svg+xml",
            "svgz" to "image/svg+xml",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "bmp" to "image/x-ms-bmp",
            "css" to "text/css",
            "html" to "text/html",
            "htm" to "text/html",
            "txt" to "text/plain",
            "xml" to "text/xml",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
        )

    private val MIME_TYPE_MAP = PAIRS.toMap()
    private val EXTENSION_MAP =
        HashMap<String, String>().apply {
            for ((extension, mimeType) in PAIRS) {
                if (!containsKey(mimeType)) {
                    put(mimeType, extension)
                }
            }
        }

    @JvmStatic
    @JvmOverloads
    fun forExtension(
        extension: String?,
        defaultMimeType: String? = null,
    ): String? {
        if (!extension.isNullOrEmpty()) {
            val lower = extension.lowercase(Locale.US)
            val mimeType =
                MIME_TYPE_MAP[lower]
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower)
            if (mimeType != null) {
                return mimeType
            }
        }
        return defaultMimeType
    }

    @JvmStatic
    @JvmOverloads
    fun toExtension(
        mimeType: String?,
        defaultExtension: String? = null,
    ): String? {
        if (!mimeType.isNullOrEmpty()) {
            val extension =
                EXTENSION_MAP[mimeType]
                    ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null) {
                return extension
            }
        }
        return defaultExtension
    }
}
