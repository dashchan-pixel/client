package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import chan.util.StringUtils.emptyIfNull
import com.mishiranu.dashchan.content.database.CommonDatabase.ExecuteCallback
import com.mishiranu.dashchan.content.database.CommonDatabase.Migration
import com.mishiranu.dashchan.content.database.CommonDatabase.QueryCallback
import com.mishiranu.dashchan.content.model.PostItem.HideState
import com.mishiranu.dashchan.util.FlagUtils.get
import java.util.Objects
import kotlin.math.min

class ThreadsDatabase internal constructor(private val database: CommonDatabase) :
    CommonDatabase.Instance {
    private interface Schema {
        interface Threads {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val TIME: String = "time"
                    const val FLAGS: String = "flags"
                    const val STATE: String = "state"
                    const val EXTRA: String = "extra"
                }
            }

            interface Flags {
                companion object {
                    const val HIDDEN: Int = 0x00000001
                    const val SHOWN: Int = 0x00000002
                }
            }

            companion object {
                const val TABLE_NAME: String = "threads"
                const val MAX_COUNT: Int = 2000
                const val MAX_COUNT_FACTOR: Float = 0.75f
            }
        }
    }

    class StateExtra(@JvmField val state: ByteArray?, @JvmField val extra: ByteArray?) {
        companion object {
            internal val EMPTY = StateExtra(null, null)
        }
    }

    override fun create(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE " + Schema.Threads.Companion.TABLE_NAME + " (" +
                    Schema.Threads.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Threads.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Threads.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                    Schema.Threads.Columns.Companion.TIME + " INTEGER NOT NULL, " +
                    Schema.Threads.Columns.Companion.FLAGS + " INTEGER NOT NULL DEFAULT 0, " +
                    Schema.Threads.Columns.Companion.STATE + " BLOB, " +
                    Schema.Threads.Columns.Companion.EXTRA + " BLOB, " +
                    "PRIMARY KEY (" + Schema.Threads.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Threads.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Threads.Columns.Companion.THREAD_NUMBER + "))"
        )
    }

    override fun upgrade(database: SQLiteDatabase, migration: Migration) {
        when (migration) {
            Migration.FROM_8_TO_9 -> {
                // Change "hidden_threads" table structure and rename to "threads"
                database.execSQL(
                    "CREATE TABLE threads (chan_name TEXT NOT NULL, board_name TEXT NOT NULL, " +
                            "thread_number TEXT NOT NULL, time INTEGER NOT NULL, flags INTEGER NOT NULL DEFAULT 0, " +
                            "state BLOB, extra BLOB, PRIMARY KEY (chan_name, board_name, thread_number))"
                )
                val time = System.currentTimeMillis()
                database.execSQL(
                    "INSERT INTO threads " +
                            "SELECT chan_name, COALESCE(board_name, ''), thread_number, " + time + ", " +
                            "CASE COALESCE(hidden, 0) WHEN 0 THEN 2 else 1 END, NULL, NULL " +
                            "FROM hidden_threads WHERE chan_name IS NOT NULL AND thread_number IS NOT NULL " +
                            "GROUP BY chan_name, COALESCE(board_name, ''), thread_number"
                )
                database.execSQL("DROP TABLE hidden_threads")
            }

            else -> {
                throw UnsupportedOperationException()
            }
        }
    }

    override fun open(database: SQLiteDatabase) {
        val clean: Boolean
        database.rawQuery("SELECT COUNT(*) FROM " + Schema.Threads.Companion.TABLE_NAME, null)
            .use { cursor ->
                clean =
                    cursor.moveToFirst() && cursor.getInt(0) > Schema.Threads.Companion.MAX_COUNT
            }
        if (clean) {
            val time: Long?
            val projection = arrayOf<String?>(Schema.Threads.Columns.Companion.TIME)
            database.query(
                Schema.Threads.Companion.TABLE_NAME,
                projection, null, null, null, null, Schema.Threads.Columns.Companion.TIME + " DESC",
                (Schema.Threads.Companion.MAX_COUNT_FACTOR * Schema.Threads.Companion.MAX_COUNT).toInt()
                    .toString() + ", 1"
            ).use { cursor ->
                time = if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (time != null) {
                database.delete(
                    Schema.Threads.Companion.TABLE_NAME,
                    Schema.Threads.Columns.Companion.TIME + " <= " + time,
                    null
                )
            }
        }
    }

    private fun upsert(
        database: SQLiteDatabase, chanName: String, boardName: String?,
        threadNumber: String, values: ContentValues?
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        database.beginTransaction()
        try {
            val filter = Expression.filter()
                .equals(Schema.Threads.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Threads.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Schema.Threads.Columns.Companion.THREAD_NUMBER, threadNumber)
                .build()
            if (database.update(
                    Schema.Threads.Companion.TABLE_NAME,
                    values,
                    filter.value,
                    filter.args
                ) <= 0
            ) {
                val newValues = ContentValues()
                newValues.put(Schema.Threads.Columns.Companion.CHAN_NAME, chanName)
                newValues.put(Schema.Threads.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                newValues.put(Schema.Threads.Columns.Companion.THREAD_NUMBER, threadNumber)
                newValues.putAll(values)
                database.insert(Schema.Threads.Companion.TABLE_NAME, null, newValues)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun setFlagsAsync(
        chanName: String, boardName: String?, threadNumber: String,
        hideState: HideState?
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        database.enqueue(ExecuteCallback { database: SQLiteDatabase? ->
            var flags = 0
            if (hideState == HideState.HIDDEN) {
                flags = flags or Schema.Threads.Flags.Companion.HIDDEN
            } else if (hideState == HideState.SHOWN) {
                flags = flags or Schema.Threads.Flags.Companion.SHOWN
            }
            val values = ContentValues()
            values.put(Schema.Threads.Columns.Companion.TIME, System.currentTimeMillis())
            values.put(Schema.Threads.Columns.Companion.FLAGS, flags)
            upsert(database!!, chanName, boardName, threadNumber, values)
            null
        })
    }

    internal fun getFlags(
        chanName: String, boardName: String?,
        threadNumbers: MutableList<String?>, hiddenThreads: HideState.Map<String?>
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<MutableList<String?>?>(threadNumbers)
        var update = false
        val projection = arrayOf<String?>(
            Schema.Threads.Columns.Companion.THREAD_NUMBER,
            Schema.Threads.Columns.Companion.FLAGS
        )
        val filter = Expression.filter()
            .equals(Schema.Threads.Columns.Companion.CHAN_NAME, chanName)
            .equals(Schema.Threads.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
            .`in`(Schema.Threads.Columns.Companion.THREAD_NUMBER, threadNumbers)
            .raw(Schema.Threads.Columns.Companion.FLAGS)
            .build()
        database.query(QueryCallback { database: SQLiteDatabase? ->
            database!!
                .query(
                    Schema.Threads.Companion.TABLE_NAME,
                    projection,
                    filter.value,
                    filter.args,
                    null,
                    null,
                    null
                )
        }).use { cursor ->
            while (cursor!!.moveToNext()) {
                val threadNumber = cursor!!.getString(0)
                val flags = cursor!!.getInt(1)
                if (get(flags, Schema.Threads.Flags.Companion.HIDDEN)) {
                    update = true
                    hiddenThreads.set(threadNumber, HideState.HIDDEN)
                } else if (get(flags, Schema.Threads.Flags.Companion.SHOWN)) {
                    update = true
                    hiddenThreads.set(threadNumber, HideState.SHOWN)
                }
            }
        }
        if (update) {
            database.execute<Any?>(ExecuteCallback { database: SQLiteDatabase? ->
                val values = ContentValues()
                values.put(Schema.Threads.Columns.Companion.TIME, System.currentTimeMillis())
                database!!.update(
                    Schema.Threads.Companion.TABLE_NAME,
                    values,
                    filter.value,
                    filter.args
                )
                null
            })
        }
    }

    fun getFlags(
        chanName: String, boardName: String?,
        threadNumbers: MutableList<String?>
    ): HideState.Map<String?>? {
        return database.execute<HideState.Map<String?>?>(ExecuteCallback { database: SQLiteDatabase? ->
            database!!.beginTransaction()
            try {
                val hiddenThreads = HideState.Map<String?>()
                val maxCount = 50
                var i = 0
                while (i < threadNumbers.size) {
                    val subList = threadNumbers.subList(i, min(i + maxCount, threadNumbers.size))
                    getFlags(chanName, boardName, subList, hiddenThreads)
                    i += maxCount
                }
                database.setTransactionSuccessful()
                return@ExecuteCallback hiddenThreads
            } finally {
                database.endTransaction()
            }
        })
    }

    fun setStateExtra(
        async: Boolean, chanName: String, boardName: String?, threadNumber: String,
        hasState: Boolean, state: ByteArray?, hasExtra: Boolean, extra: ByteArray?
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        val callback: ExecuteCallback<Void?> = ExecuteCallback { database: SQLiteDatabase? ->
            val values = ContentValues()
            values.put(Schema.Threads.Columns.Companion.TIME, System.currentTimeMillis())
            if (hasState) {
                values.put(Schema.Threads.Columns.Companion.STATE, state)
            }
            if (hasExtra) {
                values.put(Schema.Threads.Columns.Companion.EXTRA, extra)
            }
            upsert(database!!, chanName, boardName, threadNumber, values)
            null
        }
        if (async) {
            database.enqueue(callback)
        } else {
            database.execute<Void?>(callback)
        }
    }

    fun getStateExtra(chanName: String, boardName: String?, threadNumber: String): StateExtra {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        val projection = arrayOf<String?>(
            Schema.Threads.Columns.Companion.STATE,
            Schema.Threads.Columns.Companion.EXTRA
        )
        val filter = Expression.filter()
            .equals(Schema.Threads.Columns.Companion.CHAN_NAME, chanName)
            .equals(Schema.Threads.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
            .equals(Schema.Threads.Columns.Companion.THREAD_NUMBER, threadNumber)
            .build()
        var stateExtra: StateExtra? = null
        database.query(QueryCallback { database: SQLiteDatabase? ->
            database!!
                .query(
                    Schema.Threads.Companion.TABLE_NAME,
                    projection,
                    filter.value,
                    filter.args,
                    null,
                    null,
                    null
                )
        }).use { cursor ->
            if (cursor!!.moveToFirst()) {
                stateExtra = StateExtra(cursor!!.getBlob(0), cursor!!.getBlob(1))
            }
        }
        if (stateExtra != null) {
            database.execute<Any?>(ExecuteCallback { database: SQLiteDatabase? ->
                val values = ContentValues()
                values.put(Schema.Threads.Columns.Companion.TIME, System.currentTimeMillis())
                database!!.update(
                    Schema.Threads.Companion.TABLE_NAME,
                    values,
                    filter.value,
                    filter.args
                )
                null
            })
            return stateExtra
        }
        return StateExtra.Companion.EMPTY
    }
}
