package com.mishiranu.dashchan.content

import android.content.Context
import android.util.Pair
import chan.util.DataFile
import chan.util.DataFile.Companion.obtain
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.storage.AutohideStorage
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.StatisticsStorage
import com.mishiranu.dashchan.content.storage.ThemesStorage
import com.mishiranu.dashchan.util.IOUtils.copyStream
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.Arrays
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparable
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.String
import kotlin.Throws
import kotlin.also

object BackupManager {
    private const val FILE_NAME_PREFIX = "backup-"
    private const val FILE_NAME_SUFFIX = ".zip"

    private const val BACKUP_VERSION_0 = "dashchan:0"
    private const val BACKUP_VERSION_1 = "dashchan:1"

    // Anything filling the buffer is not a version marker
    private const val MAX_VERSION_LENGTH = 1024

    fun getAvailableBackups(context: Context?): MutableList<BackupFile> {
        val root = obtain(DataFile.Target.DOWNLOADS, null)
        val files = root.getChildren()
        val backupFiles: MutableList<BackupFile> = ArrayList()
        if (files != null) {
            val timeFormat =
                android.text.format.DateFormat
                    .getTimeFormat(context)
            val dateFormat =
                android.text.format.DateFormat
                    .getDateFormat(context)
            for (file in files) {
                var name = file.getName()
                if (name!!.startsWith(FILE_NAME_PREFIX) && name.endsWith(FILE_NAME_SUFFIX)) {
                    name =
                        name.substring(
                            FILE_NAME_PREFIX.length,
                            name.length - FILE_NAME_SUFFIX.length,
                        )
                    var date: Long
                    try {
                        date = name.toLong()
                    } catch (e: NumberFormatException) {
                        date = -1L
                    }
                    if (date >= 0) {
                        name = dateFormat.format(date) + " " + timeFormat.format(date)
                        backupFiles.add(BackupFile(file, name, date))
                    }
                }
            }
        }
        backupFiles.sort()
        return backupFiles
    }

    fun makeBackup(
        binder: DownloadService.Binder,
        context: Context,
    ) {
        val backupFile = File(context.getCacheDir(), "backup-" + UUID.randomUUID())
        var success = false
        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                var hasEntries = false
                for (entry in Entry.entries) {
                    val writer = entry.writer
                    // An entry with nothing to write must be omitted entirely: a zero length entry
                    // would overwrite the live storage with nothing on restore
                    if (writer != null && writer.hasContent()) {
                        zip.putNextEntry(ZipEntry(entry.entryName))
                        try {
                            writer.write(zip)
                        } finally {
                            zip.closeEntry()
                        }
                        hasEntries = true
                    }
                }
                success = hasEntries
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (!success) {
                backupFile.delete()
            }
        }
        var input: FileInputStream? = null
        if (success) {
            try {
                input = FileInputStream(backupFile)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        // FileInputStream holds a file descriptor
        backupFile.delete()
        if (success) {
            binder.downloadStorage(
                input,
                null,
                null,
                null,
                null,
                FILE_NAME_PREFIX + System.currentTimeMillis() + FILE_NAME_SUFFIX,
                false,
                false,
            )
        } else {
            ClickableToast.show(R.string.no_access)
        }
    }

    /**
     * Wraps the current zip entry so its reader can be handed a stream, or returns null when the
     * entry carries no data at all. Backups written before [Writer.hasContent] existed can hold
     * such empty entries, and restoring one would wipe the live storage.
     */
    @Throws(IOException::class)
    private fun openEntry(zip: ZipInputStream): InputStream? {
        val input = PushbackInputStream(zip, 1)
        val first = input.read()
        if (first < 0) {
            return null
        }
        input.unread(first)
        return input
    }

    fun readBackupEntries(file: DataFile): MutableList<Entry> {
        var version: String? = BACKUP_VERSION_0
        val entries = HashSet<Entry>()
        try {
            ZipInputStream(file.openInputStream()).use { zip ->
                var zipEntry: ZipEntry?
                while ((zip.getNextEntry().also { zipEntry = it }) != null) {
                    try {
                        val entry: Entry? = Entry.Companion.find(zipEntry!!.getName())
                        if (entry != null) {
                            val input = openEntry(zip)
                            if (input != null) {
                                try {
                                    val restore = Restore(true, input)
                                    restore.version = version
                                    entry.reader.read(restore)
                                    version = restore.version
                                    entries.add(entry)
                                } catch (e: IOException) {
                                    // A damaged entry must not hide the rest of the backup
                                    e.printStackTrace()
                                }
                            }
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            entries.clear()
        }
        val result = ArrayList<Entry>()
        for (entry in Entry.entries) {
            if (entries.contains(entry)) {
                if (entry.versions.contains(version)) {
                    result.add(entry)
                }
            }
        }
        return result
    }

    fun loadBackup(
        file: DataFile,
        entries: Collection<Entry>,
    ): Boolean {
        var success = false
        try {
            ZipInputStream(file.openInputStream()).use { zip ->
                var zipEntry: ZipEntry?
                while ((zip.getNextEntry().also { zipEntry = it }) != null) {
                    try {
                        val entry: Entry? = Entry.Companion.find(zipEntry!!.getName())
                        if (entry != null && entries.contains(entry)) {
                            val input = openEntry(zip)
                            if (input != null) {
                                entry.reader.read(Restore(false, input))
                                success = true
                            }
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            success = false
        }
        return success
    }

    class BackupFile(
        val file: DataFile?,
        val name: String?,
        val date: Long,
    ) : Comparable<BackupFile> {
        override fun compareTo(other: BackupFile): Int = other.date.compareTo(date)
    }

    internal class Restore(
        val test: Boolean,
        val input: InputStream,
    ) {
        var version: String? = null
    }

    internal fun interface Writer {
        @Throws(IOException::class)
        fun write(output: OutputStream)

        /** Whether [write] would produce anything; an entry that would stay empty is not written. */
        fun hasContent(): Boolean = true
    }

    internal fun interface Reader {
        @Throws(IOException::class)
        fun read(restore: Restore)
    }

    private class FileWriter(
        private val file: File,
    ) : Writer {
        @Throws(IOException::class)
        override fun write(output: OutputStream) {
            if (file.exists()) {
                FileInputStream(file).use { input ->
                    copyStream(input, output)
                }
            }
        }

        // A storage serializing to null leaves the file in place but empty, so length matters too
        override fun hasContent(): Boolean = file.exists() && file.length() > 0
    }

    private class FileReader(
        private val file: File?,
    ) : Reader {
        @Throws(IOException::class)
        override fun read(restore: Restore) {
            if (!restore.test) {
                FileOutputStream(file).use { output ->
                    copyStream(restore.input, output)
                    output.getFD().sync()
                }
            }
        }
    }

    enum class Entry(
        val titleResId: Int,
        internal val entryName: String,
        versions: MutableCollection<String?>,
        internal val writer: Writer?,
        internal val reader: Reader,
    ) {
        VERSION(
            0,
            "version",
            mutableListOf<String?>(),
            BackupManager.Writer { output: OutputStream -> output.write((BACKUP_VERSION_1 + "\n").toByteArray()) },
            BackupManager.Reader { restore: Restore ->
                restore.version = null
                val data = ByteArray(MAX_VERSION_LENGTH)
                // A single read may come up short, and a short read would yield a bogus version
                var count = 0
                while (count < data.size) {
                    val read = restore.input.read(data, count, data.size - count)
                    if (read < 0) {
                        break
                    }
                    count += read
                }
                if (count <= 0 || count == data.size) {
                    throw IOException("Invalid version file")
                }
                restore.version = String(data, 0, count, Charsets.UTF_8).trim { it <= ' ' }
            },
        ),
        DATABASE(
            R.string.database,
            "common.db",
            mutableListOf<String?>(BACKUP_VERSION_1),
            BackupManager.Writer { output: OutputStream ->
                CommonDatabase.getInstance().writeBackup(output)
            },
            BackupManager.Reader { restore: Restore ->
                if (!restore.test) {
                    CommonDatabase.getInstance().readBackup(restore.input)
                }
            },
        ),
        PREFERENCES_0(
            R.string.preferences,
            "com.mishiranu.dashchan_preferences.xml",
            Preferences.fileForRestore,
            mutableListOf<String?>(BACKUP_VERSION_0),
        ),
        PREFERENCES_1(
            R.string.preferences,
            Preferences.filesForBackup,
            mutableListOf<String?>(BACKUP_VERSION_1),
        ),
        FAVORITES(
            R.string.favorites,
            FavoritesStorage.getInstance().getFilesForBackup(),
            Arrays.asList<String?>(BACKUP_VERSION_0, BACKUP_VERSION_1),
        ),
        AUTOHIDE(
            R.string.autohide,
            AutohideStorage.getInstance().getFilesForBackup(),
            Arrays.asList<String?>(BACKUP_VERSION_0, BACKUP_VERSION_1),
        ),
        COMMANDS(
            R.string.commands,
            CommandsStorage.getInstance().getFilesForBackup(),
            Arrays.asList<String?>(BACKUP_VERSION_0, BACKUP_VERSION_1),
        ),
        STATISTICS(
            R.string.statistics,
            StatisticsStorage.getInstance().getFilesForBackup(),
            Arrays.asList<String?>(BACKUP_VERSION_0, BACKUP_VERSION_1),
        ),
        THEMES(
            R.string.themes,
            ThemesStorage.getInstance().getFilesForBackup(),
            Arrays.asList<String?>(BACKUP_VERSION_0, BACKUP_VERSION_1),
        ),
        ;

        internal val versions: MutableSet<String?>

        constructor(
            titleResId: Int,
            backupFiles: Pair<File, File>,
            versions: MutableCollection<String?>,
        ) : this(
            titleResId,
            backupFiles.first.getName(),
            versions,
            BackupManager.FileWriter(backupFiles.first!!),
            FileReader(backupFiles.second),
        )

        constructor(
            titleResId: Int,
            name: String,
            restoreFile: File?,
            versions: MutableCollection<String?>,
        ) : this(titleResId, name, versions, null, FileReader(restoreFile))

        init {
            this.versions = Collections.unmodifiableSet(HashSet(versions))
        }

        companion object {
            internal fun find(name: String?): Entry? {
                for (entry in entries) {
                    if (entry.entryName == name) {
                        return entry
                    }
                }
                return null
            }
        }
    }
}
