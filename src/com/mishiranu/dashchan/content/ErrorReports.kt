package com.mishiranu.dashchan.content

import android.content.Context
import com.mishiranu.dashchan.util.Logger
import java.io.File
import java.io.IOException

/**
 * The crash reports [Logger]'s uncaught exception handler leaves behind, one "error-<millis>.txt"
 * per crash. Nothing in the app produces them but that handler, and nothing else reads them.
 */
object ErrorReports {
    private const val NAME_PREFIX = "error-"
    private const val NAME_SUFFIX = ".txt"

    // Reports are stack traces of a few kilobytes; the cap only guards against a file that somehow
    // grew out of hand, since the whole text goes into a single TextView.
    private const val MAX_REPORT_SIZE = 1024 * 1024

    /** Newest first: the report the user came for is the one that just crashed the app. */
    fun getFiles(context: Context): List<File> {
        val files = Logger.getErrorsDirectory(context)?.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith(NAME_PREFIX) }
            .sortedByDescending { getTime(it) }
    }

    fun getFile(
        context: Context,
        name: String,
    ): File? {
        val directory = Logger.getErrorsDirectory(context) ?: return null
        return File(directory, name)
    }

    /** When the crash happened, taken from the file name and falling back to the file itself. */
    fun getTime(file: File): Long {
        val name = file.name
        if (name.startsWith(NAME_PREFIX) && name.endsWith(NAME_SUFFIX)) {
            val time =
                name
                    .substring(NAME_PREFIX.length, name.length - NAME_SUFFIX.length)
                    .toLongOrNull()
            if (time != null) {
                return time
            }
        }
        return file.lastModified()
    }

    /**
     * The first line of the stack trace, i.e. the exception that killed the app: the report starts
     * with the technical data block, which is the same for every report of a build.
     */
    fun readSummary(file: File): String? {
        try {
            file.bufferedReader().use { reader ->
                var afterDivider = false
                var line = reader.readLine()
                while (line != null) {
                    if (afterDivider) {
                        if (line.isNotBlank()) {
                            return line.trim()
                        }
                    } else if (line.length >= 3 && line.all { it == '-' }) {
                        afterDivider = true
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    fun readReport(file: File): String? =
        try {
            val bytes = file.inputStream().use { it.readNBytes(MAX_REPORT_SIZE) }
            String(bytes) + if (file.length() > MAX_REPORT_SIZE) "\n…" else ""
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
}
