package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.Preferences.isRememberHistory
import com.mishiranu.dashchan.content.database.CommonDatabase.ExecuteCallback
import com.mishiranu.dashchan.content.database.CommonDatabase.Migration
import com.mishiranu.dashchan.content.database.CommonDatabase.QueryCallback
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.WeakObservable
import java.util.Objects

class HistoryDatabase internal constructor(
    private val database: CommonDatabase,
) : CommonDatabase.Instance {
    private interface Schema {
        interface History {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val TIME: String = "time"
                    const val TITLE: String = "title"
                }
            }

            companion object {
                const val TABLE_NAME: String = "history"
            }
        }
    }

    class HistoryCursor internal constructor(
        cursor: Cursor,
        val hasItems: Boolean,
        val filtered: Boolean,
    ) : CursorWrapper(cursor) {
        internal val chanNameIndex: Int
        internal val boardNameIndex: Int
        internal val threadNumberIndex: Int
        internal val timeIndex: Int
        internal val titleIndex: Int

        init {
            chanNameIndex = cursor.getColumnIndex(Schema.History.Columns.Companion.CHAN_NAME)
            boardNameIndex = cursor.getColumnIndex(Schema.History.Columns.Companion.BOARD_NAME)
            threadNumberIndex =
                cursor.getColumnIndex(Schema.History.Columns.Companion.THREAD_NUMBER)
            timeIndex = cursor.getColumnIndex(Schema.History.Columns.Companion.TIME)
            titleIndex = cursor.getColumnIndex(Schema.History.Columns.Companion.TITLE)
        }
    }

    class HistoryItem {
        var chanName: String? = null
        var boardName: String? = null
        var threadNumber: String? = null
        var time: Long = 0
        var title: String? = null

        fun update(cursor: HistoryCursor): HistoryItem {
            chanName = cursor.getString(cursor.chanNameIndex)
            boardName = cursor.getString(cursor.boardNameIndex)
            threadNumber = cursor.getString(cursor.threadNumberIndex)
            time = cursor.getLong(cursor.timeIndex)
            title = cursor.getString(cursor.titleIndex)
            return this
        }

        fun copy(): HistoryItem {
            val historyItem = HistoryItem()
            historyItem.chanName = chanName
            historyItem.boardName = boardName
            historyItem.threadNumber = threadNumber
            historyItem.time = time
            historyItem.title = title
            return historyItem
        }
    }

    override fun create(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE " + Schema.History.Companion.TABLE_NAME + " (" +
                Schema.History.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                Schema.History.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                Schema.History.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                Schema.History.Columns.Companion.TIME + " INTEGER NOT NULL, " +
                Schema.History.Columns.Companion.TITLE + " TEXT, " +
                "PRIMARY KEY (" + Schema.History.Columns.Companion.CHAN_NAME + ", " +
                Schema.History.Columns.Companion.BOARD_NAME + ", " +
                Schema.History.Columns.Companion.THREAD_NUMBER + "))",
        )
        database.execSQL(
            "CREATE INDEX " + Schema.History.Companion.TABLE_NAME + "_order " +
                "ON " + Schema.History.Companion.TABLE_NAME + " (" +
                Schema.History.Columns.Companion.CHAN_NAME + ", " +
                Schema.History.Columns.Companion.TIME + ")",
        )
    }

    override fun upgrade(
        database: SQLiteDatabase,
        migration: Migration,
    ) {
        when (migration) {
            Migration.FROM_8_TO_9 -> {
                // Change "history" table structure
                database.execSQL("ALTER TABLE history RENAME TO history_old")
                database.execSQL(
                    "CREATE TABLE history (chan_name TEXT NOT NULL, board_name TEXT NOT NULL, " +
                        "thread_number TEXT NOT NULL, time INTEGER NOT NULL, title TEXT, " +
                        "PRIMARY KEY (chan_name, board_name, thread_number))",
                )
                database.execSQL(
                    "INSERT INTO history " +
                        "SELECT chan_name, COALESCE(board_name, ''), thread_number, COALESCE(created, 0), title " +
                        "FROM history_old WHERE chan_name IS NOT NULL AND thread_number IS NOT NULL " +
                        "GROUP BY chan_name, COALESCE(board_name, ''), thread_number",
                )
                database.execSQL("CREATE INDEX history_order ON history (chan_name, time)")
                database.execSQL("DROP TABLE history_old")
            }
        }
    }

    private val observable = WeakObservable<Runnable>()

    fun registerObserver(runnable: Runnable) {
        observable.register(runnable)
    }

    fun unregisterObserver(runnable: Runnable) {
        observable.unregister(runnable)
    }

    private val onChanged =
        Runnable {
            for (runnable in observable) {
                runnable.run()
            }
        }

    fun addHistoryAsync(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        title: String?,
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        if (isRememberHistory) {
            database.enqueue(
                ExecuteCallback { database: SQLiteDatabase ->
                    val values = ContentValues()
                    values.put(Schema.History.Columns.Companion.CHAN_NAME, chanName)
                    values.put(Schema.History.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                    values.put(Schema.History.Columns.Companion.THREAD_NUMBER, threadNumber)
                    values.put(Schema.History.Columns.Companion.TIME, System.currentTimeMillis())
                    values.put(Schema.History.Columns.Companion.TITLE, title)
                    database.replace(Schema.History.Companion.TABLE_NAME, null, values)
                    ConcurrentUtils.HANDLER.post(onChanged)
                    null
                },
            )
        }
    }

    fun updateTitleAsync(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        title: String?,
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        if (!isEmpty(title)) {
            database.enqueue(
                ExecuteCallback { database: SQLiteDatabase ->
                    val filter =
                        Expression
                            .filter()
                            .equals(Schema.History.Columns.Companion.CHAN_NAME, chanName)
                            .equals(Schema.History.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                            .equals(Schema.History.Columns.Companion.THREAD_NUMBER, threadNumber)
                            .build()
                    val values = ContentValues()
                    values.put(Schema.History.Columns.Companion.TITLE, title)
                    database.update(
                        Schema.History.Companion.TABLE_NAME,
                        values,
                        filter.value,
                        filter.args,
                    )
                    ConcurrentUtils.HANDLER.post(onChanged)
                    null
                },
            )
        }
    }

    @Throws(OperationCanceledException::class)
    fun getHistory(
        chanName: String?,
        searchQuery: String?,
        signal: CancellationSignal?,
    ): HistoryCursor {
        val count =
            database.execute<Int?>(
                ExecuteCallback { database: SQLiteDatabase ->
                    val projection = arrayOf<String?>("COUNT(*)")
                    val filterBuilder = Expression.filter()
                    if (chanName != null) {
                        filterBuilder.equals(Schema.History.Columns.Companion.CHAN_NAME, chanName)
                    }
                    val filter = filterBuilder.build()
                    database
                        .query(
                            false,
                            Schema.History.Companion.TABLE_NAME,
                            projection,
                            filter.value,
                            filter.args,
                            null,
                            null,
                            null,
                            null,
                            signal,
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                return@ExecuteCallback cursor.getInt(0)
                            }
                        }
                    0
                },
            )
        val projection = arrayOf<String?>("rowid", "*")
        val filterBuilder = Expression.filter()
        if (chanName != null) {
            filterBuilder.equals(Schema.History.Columns.Companion.CHAN_NAME, chanName)
        }
        var filtered = false
        if (!isEmpty(searchQuery)) {
            filterBuilder.like(Schema.History.Columns.Companion.TITLE, "%" + searchQuery + "%")
            filtered = true
        }
        val filter = filterBuilder.build()
        val cursor =
            database.query(
                QueryCallback { database: SQLiteDatabase ->
                    database.query(
                        false,
                        Schema.History.Companion.TABLE_NAME,
                        projection,
                        filter.value,
                        filter.args,
                        null,
                        null,
                        Schema.History.Columns.Companion.TIME + " DESC",
                        null,
                        signal,
                    )
                },
            )
        return HistoryCursor(cursor!!, count!! > 0, filtered)
    }

    fun remove(
        chanName: String,
        boardName: String?,
        threadNumber: String,
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        val filter =
            Expression
                .filter()
                .equals(Schema.History.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.History.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Schema.History.Columns.Companion.THREAD_NUMBER, threadNumber)
                .build()
        database.execute<Int?>(
            ExecuteCallback { database: SQLiteDatabase ->
                database.delete(
                    Schema.History.Companion.TABLE_NAME,
                    filter.value,
                    filter.args,
                )
            },
        )
        ConcurrentUtils.HANDLER.post(onChanged)
    }

    fun clearHistory(chanName: String?) {
        val filterBuilder = Expression.filter()
        if (chanName != null) {
            filterBuilder.equals(Schema.History.Columns.Companion.CHAN_NAME, chanName)
        }
        val filter = filterBuilder.build()
        database.execute<Int?>(
            ExecuteCallback { database: SQLiteDatabase ->
                database.delete(
                    Schema.History.Companion.TABLE_NAME,
                    filter.value,
                    filter.args,
                )
            },
        )
        ConcurrentUtils.HANDLER.post(onChanged)
    }
}
