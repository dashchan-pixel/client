package com.mishiranu.dashchan.content.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.util.ConcurrentUtils.newSingleThreadPool
import com.mishiranu.dashchan.util.IOUtils.copyStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Executor

class CommonDatabase private constructor() {
    enum class Migration {
        FROM_8_TO_9
    }

    interface Instance {
        fun create(database: SQLiteDatabase?)
        fun upgrade(database: SQLiteDatabase?, migration: Migration?)
        fun open(database: SQLiteDatabase?) {}
    }

    fun interface QueryCallback {
        fun query(db: SQLiteDatabase?): Cursor?
    }

    fun interface ExecuteCallback<T> {
        fun run(database: SQLiteDatabase?): T?
    }

    private val executor: Executor = newSingleThreadPool(10000, "CommonDatabase", null)

    val history: HistoryDatabase
    val threads: ThreadsDatabase
    val posts: PostsDatabase

    private val helper: Helper

    init {
        // Rename "dashchan.db" to "common.db"
        val oldFile = MainApplication.getInstance().getDatabasePath("dashchan.db")
        if (oldFile.exists()) {
            val parent = oldFile.getParentFile()
            val oldName = oldFile.getName()
            for (file in oldFile.getParentFile().listFiles()) {
                val name = file.getName()
                if (name.startsWith(oldName)) {
                    file.renameTo(File(parent, "common.db" + name.substring(oldName.length)))
                }
            }
        }
        this.history = HistoryDatabase(this)
        this.threads = ThreadsDatabase(this)
        this.posts = PostsDatabase(this)
        helper = Helper(
            Arrays.asList<Instance?>(
                this.history,
                this.threads,
                this.posts
            )
        )
    }

    fun query(callback: QueryCallback): Cursor? {
        return callback.query(helper.database)
    }

    fun <T> execute(callback: ExecuteCallback<T?>): T? {
        return callback.run(helper.database)
    }

    fun enqueue(callback: ExecuteCallback<*>) {
        executor.execute(Runnable { execute(callback) })
    }

    @Throws(IOException::class)
    fun writeBackup(output: OutputStream) {
        val backupFile =
            MainApplication.getInstance().getDatabasePath(Helper.Companion.DATABASE_BACKUP_NAME)
        try {
            val database = helper.database
            database.execSQL("ATTACH DATABASE ? AS backup", arrayOf<Any>(backupFile.getPath()))
            database.beginTransaction()
            try {
                database.execSQL("PRAGMA backup.user_version=" + Helper.Companion.DATABASE_VERSION)
                copyDatabase(database, "", "backup.")
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
                database.execSQL("DETACH DATABASE backup")
            }
            FileInputStream(backupFile).use { input ->
                copyStream(input, output)
            }
        } finally {
            for (file in backupFile.getParentFile().listFiles()) {
                if (file.getName().startsWith(backupFile.getName())) {
                    file.delete()
                }
            }
        }
    }

    @Throws(IOException::class)
    fun readBackup(input: InputStream) {
        val restoreFile =
            MainApplication.getInstance().getDatabasePath(Helper.Companion.DATABASE_RESTORE_NAME)
        val files = restoreFile.getParentFile().listFiles()
        if (files != null) {
            for (file in files) {
                if (file.getName().startsWith(restoreFile.getName())) {
                    file.delete()
                }
            }
        }
        restoreFile.getParentFile().mkdirs()
        FileOutputStream(restoreFile).use { output ->
            copyStream(input, output)
        }
    }

    private class Helper(instances: MutableCollection<Instance>) :
        SQLiteOpenHelper(MainApplication.getInstance(), DATABASE_NAME, null, DATABASE_VERSION) {
        private val instances: MutableCollection<Instance>
        internal val database: SQLiteDatabase

        init {
            setWriteAheadLoggingEnabled(false)
            this.instances = instances
            database = getWritableDatabase()
        }

        override fun onConfigure(db: SQLiteDatabase) {
            val restoreFile = MainApplication.getInstance().getDatabasePath(DATABASE_RESTORE_NAME)
            if (restoreFile.exists()) {
                try {
                    db.execSQL("ATTACH DATABASE ? AS restore", arrayOf<Any>(restoreFile.getPath()))
                    db.beginTransaction()
                    try {
                        val version: Int
                        db.rawQuery("PRAGMA restore.user_version", null).use { cursor ->
                            version = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                        if (version > 0) {
                            dropAllTables(db)
                            db.execSQL("PRAGMA user_version=" + version)
                            copyDatabase(db, "restore.", "")
                            db.setTransactionSuccessful()
                        }
                    } finally {
                        db.endTransaction()
                        db.execSQL("DETACH DATABASE restore")
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    for (file in restoreFile.getParentFile().listFiles()) {
                        if (file.getName().startsWith(restoreFile.getName())) {
                            file.delete()
                        }
                    }
                }
            }
        }

        override fun onCreate(db: SQLiteDatabase?) {
            for (instance in instances) {
                instance.create(db)
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion <= 7) {
                dropAllTables(db)
                onCreate(db)
            } else {
                when (oldVersion) {
                    8 -> {
                        for (instance in instances) {
                            instance.upgrade(db, Migration.FROM_8_TO_9)
                        }
                    }
                }
            }
        }

        override fun onOpen(db: SQLiteDatabase?) {
            for (instance in instances) {
                instance.open(db)
            }
        }

        companion object {
            private const val DATABASE_NAME = "common.db"
            internal const val DATABASE_BACKUP_NAME = "common.backup.db"
            internal const val DATABASE_RESTORE_NAME = "common.restore.db"
            internal const val DATABASE_VERSION = 9
        }
    }

    companion object {
        private val IGNORE_TABLES: MutableSet<String?> = Collections.unmodifiableSet<String?>(
            HashSet<String?>(
                mutableListOf<String?>(
                    "sqlite_sequence",
                    "sqlite_master",
                    "android_metadata"
                )
            )
        )

        private val INSTANCE = CommonDatabase()

        @JvmStatic
        fun getInstance(): CommonDatabase = INSTANCE

        @Throws(IOException::class)
        private fun copyDatabase(
            database: SQLiteDatabase,
            fromPrefix: String?, toPrefix: String
        ) {
            val tablesProjection = arrayOf<String?>("name", "sql")
            val tablesFilter = Expression.filter().equals("type", "table").build()
            database.query(
                fromPrefix + "sqlite_master", tablesProjection,
                tablesFilter.value, tablesFilter.args, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (!IGNORE_TABLES.contains(name)) {
                        var sql = cursor.getString(1)
                        val index = sql.indexOf(name)
                        if (index < 0) {
                            throw IOException()
                        }
                        sql = sql.substring(0, index) + toPrefix + sql.substring(index)
                        database.execSQL(sql)
                        database.execSQL("INSERT INTO " + toPrefix + name + " SELECT * FROM " + fromPrefix + name)
                    }
                }
            }
            val indexesProjection = arrayOf<String?>("name", "sql")
            val indexesFilter = Expression.filter().equals("type", "index").build()
            database.query(
                fromPrefix + "sqlite_master", indexesProjection,
                indexesFilter.value, indexesFilter.args, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    var sql = cursor.getString(1)
                    if (sql != null) {
                        val index = sql.indexOf(name)
                        if (index < 0) {
                            throw IOException()
                        }
                        sql = sql.substring(0, index) + toPrefix + sql.substring(index)
                        database.execSQL(sql)
                    }
                }
            }
        }

        private fun dropAllTables(database: SQLiteDatabase) {
            val projection = arrayOf<String?>("name")
            val filter = Expression.filter().equals("type", "table").build()
            val names = ArrayList<String?>()
            database.query(
                "sqlite_master",
                projection, filter.value, filter.args, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(0))
                }
            }
            for (name in names) {
                if (!IGNORE_TABLES.contains(name)) {
                    database.execSQL("DROP TABLE IF EXISTS " + name)
                }
            }
        }
    }
}
