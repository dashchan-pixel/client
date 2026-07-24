package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.database.CommonDatabase.ExecuteCallback
import com.mishiranu.dashchan.content.database.CommonDatabase.Migration
import com.mishiranu.dashchan.content.database.CommonDatabase.QueryCallback
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.WeakObservable

/**
 * Collects the replies to the user's own posts, so they can be reviewed later in the Inbox
 * regardless of whether their notification was seen or dismissed.
 */
class InboxDatabase internal constructor(
    private val database: CommonDatabase,
) : CommonDatabase.Instance {
    private interface Schema {
        interface Inbox {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val POST_NUMBER_MAJOR: String = "post_number_major"
                    const val POST_NUMBER_MINOR: String = "post_number_minor"
                    const val TIME: String = "time"
                    const val COMMENT: String = "comment"
                    const val TITLE: String = "title"
                    const val UNREAD: String = "unread"
                }
            }

            companion object {
                const val TABLE_NAME: String = "inbox"
                const val MAX_COUNT: Int = 1000
                const val MAX_COUNT_FACTOR: Float = 0.75f
            }
        }
    }

    class InboxCursor internal constructor(
        cursor: Cursor,
        val hasItems: Boolean,
        val filtered: Boolean,
    ) : CursorWrapper(cursor) {
        internal val chanNameIndex: Int
        internal val boardNameIndex: Int
        internal val threadNumberIndex: Int
        internal val postNumberMajorIndex: Int
        internal val postNumberMinorIndex: Int
        internal val timeIndex: Int
        internal val commentIndex: Int
        internal val titleIndex: Int
        internal val unreadIndex: Int

        init {
            chanNameIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.CHAN_NAME)
            boardNameIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.BOARD_NAME)
            threadNumberIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.THREAD_NUMBER)
            postNumberMajorIndex =
                cursor.getColumnIndex(Schema.Inbox.Columns.Companion.POST_NUMBER_MAJOR)
            postNumberMinorIndex =
                cursor.getColumnIndex(Schema.Inbox.Columns.Companion.POST_NUMBER_MINOR)
            timeIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.TIME)
            commentIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.COMMENT)
            titleIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.TITLE)
            unreadIndex = cursor.getColumnIndex(Schema.Inbox.Columns.Companion.UNREAD)
        }
    }

    /** A snapshot of the row the cursor currently points at. */
    class InboxItem(
        cursor: InboxCursor,
    ) {
        val chanName: String = cursor.getString(cursor.chanNameIndex)
        val boardName: String = cursor.getString(cursor.boardNameIndex)
        val threadNumber: String = cursor.getString(cursor.threadNumberIndex)
        val postNumber: PostNumber =
            PostNumber(
                cursor.getInt(cursor.postNumberMajorIndex),
                cursor.getInt(cursor.postNumberMinorIndex),
            )
        val time: Long = cursor.getLong(cursor.timeIndex)
        val comment: String? = cursor.getString(cursor.commentIndex)
        val title: String? = cursor.getString(cursor.titleIndex)
        val unread: Boolean = cursor.getInt(cursor.unreadIndex) != 0
    }

    override fun create(database: SQLiteDatabase) {
        createTable(database)
    }

    override fun upgrade(
        database: SQLiteDatabase,
        migration: Migration,
    ) {
        when (migration) {
            Migration.FROM_8_TO_9 -> {}

            Migration.FROM_9_TO_10 -> {
                // Add "inbox" table
                createTable(database)
            }
        }
    }

    override fun open(database: SQLiteDatabase) {
        val clean: Boolean
        database
            .rawQuery("SELECT COUNT(*) FROM " + Schema.Inbox.Companion.TABLE_NAME, null)
            .use { cursor ->
                clean = cursor.moveToFirst() && cursor.getInt(0) > Schema.Inbox.Companion.MAX_COUNT
            }
        if (clean) {
            val time: Long?
            val projection = arrayOf<String?>(Schema.Inbox.Columns.Companion.TIME)
            database
                .query(
                    Schema.Inbox.Companion.TABLE_NAME,
                    projection,
                    null,
                    null,
                    null,
                    null,
                    Schema.Inbox.Columns.Companion.TIME + " DESC",
                    (Schema.Inbox.Companion.MAX_COUNT_FACTOR * Schema.Inbox.Companion.MAX_COUNT)
                        .toInt()
                        .toString() + ", 1",
                ).use { cursor ->
                    time = if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            if (time != null) {
                database.delete(
                    Schema.Inbox.Companion.TABLE_NAME,
                    Schema.Inbox.Columns.Companion.TIME + " <= " + time,
                    null,
                )
            }
        }
        updateUnreadCount(database)
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

    /**
     * The number of unread replies, kept in memory so the drawer can show it without a query.
     */
    @Volatile
    var unreadCount: Int = 0
        private set

    private fun updateUnreadCount(database: SQLiteDatabase) {
        database
            .rawQuery(
                "SELECT COUNT(*) FROM " + Schema.Inbox.Companion.TABLE_NAME +
                    " WHERE " + Schema.Inbox.Columns.Companion.UNREAD + " != 0",
                null,
            ).use { cursor ->
                unreadCount = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
    }

    private fun notifyChanged(database: SQLiteDatabase) {
        updateUnreadCount(database)
        ConcurrentUtils.HANDLER.post(onChanged)
    }

    /**
     * Stores replies to the user's posts. Replies already known are left untouched, so an entry
     * already marked as read doesn't become unread again.
     */
    fun addRepliesAsync(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        title: String?,
        replies: List<PagesDatabase.InsertResult.Reply>,
    ) {
        if (replies.isEmpty()) {
            return
        }
        database.enqueue<Any?>(
            ExecuteCallback { database: SQLiteDatabase ->
                var inserted = false
                database.beginTransaction()
                try {
                    for (reply in replies) {
                        val postNumber = reply.postNumber ?: continue
                        val rowId =
                            database.insertWithOnConflict(
                                Schema.Inbox.Companion.TABLE_NAME,
                                null,
                                replyValues(chanName, boardName, threadNumber, title, reply, postNumber),
                                SQLiteDatabase.CONFLICT_IGNORE,
                            )
                        inserted = inserted or (rowId >= 0)
                    }
                    if (inserted && !isEmpty(title)) {
                        // A better title may have appeared since the older replies were stored
                        val filter = threadFilter(chanName, boardName, threadNumber)
                        val values = ContentValues()
                        values.put(Schema.Inbox.Columns.Companion.TITLE, title)
                        database.update(
                            Schema.Inbox.Companion.TABLE_NAME,
                            values,
                            filter.value,
                            filter.args,
                        )
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                if (inserted) {
                    notifyChanged(database)
                }
                null
            },
        )
    }

    @Throws(OperationCanceledException::class)
    private fun countInbox(
        chanName: String?,
        signal: CancellationSignal?,
    ): Int? =
        database.execute<Int?>(
            ExecuteCallback { database: SQLiteDatabase ->
                val projection = arrayOf<String?>("COUNT(*)")
                val filterBuilder = Expression.filter()
                if (chanName != null) {
                    filterBuilder.equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
                }
                val filter = filterBuilder.build()
                database
                    .query(
                        false,
                        Schema.Inbox.Companion.TABLE_NAME,
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

    @Throws(OperationCanceledException::class)
    fun getInbox(
        chanName: String?,
        searchQuery: String?,
        signal: CancellationSignal?,
    ): InboxCursor {
        val count = countInbox(chanName, signal) ?: 0
        val projection = arrayOf<String?>("rowid", "*")
        val filterBuilder = Expression.filter()
        if (chanName != null) {
            filterBuilder.equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
        }
        var filtered = false
        if (!isEmpty(searchQuery)) {
            filterBuilder.append(
                Expression
                    .filterOr()
                    .like(Schema.Inbox.Columns.Companion.COMMENT, "%" + searchQuery + "%")
                    .like(Schema.Inbox.Columns.Companion.TITLE, "%" + searchQuery + "%"),
            )
            filtered = true
        }
        val filter = filterBuilder.build()
        val cursor =
            database.query(
                QueryCallback { database: SQLiteDatabase ->
                    database.query(
                        false,
                        Schema.Inbox.Companion.TABLE_NAME,
                        projection,
                        filter.value,
                        filter.args,
                        null,
                        null,
                        // Newest first. Chans that report no post time leave every row tied on
                        // TIME, so fall back to insertion order to keep it reverse chronological.
                        Schema.Inbox.Columns.Companion.TIME + " DESC, rowid DESC",
                        null,
                        signal,
                    )
                },
            )
        return InboxCursor(checkNotNull(cursor), count > 0, filtered)
    }

    fun markReadAsync(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        postNumbers: Collection<PostNumber>,
    ) {
        if (postNumbers.isEmpty()) {
            return
        }
        database.enqueue<Any?>(
            ExecuteCallback { database: SQLiteDatabase ->
                var changed = 0
                val values = ContentValues()
                values.put(Schema.Inbox.Columns.Companion.UNREAD, 0)
                database.beginTransaction()
                try {
                    for (postNumber in postNumbers) {
                        val filter = postFilter(chanName, boardName, threadNumber, postNumber)
                        changed +=
                            database.update(
                                Schema.Inbox.Companion.TABLE_NAME,
                                values,
                                filter.value,
                                filter.args,
                            )
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                if (changed > 0) {
                    notifyChanged(database)
                }
                null
            },
        )
    }

    fun markAllRead(chanName: String?) {
        val filterBuilder = Expression.filter()
        if (chanName != null) {
            filterBuilder.equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
        }
        filterBuilder.raw(Schema.Inbox.Columns.Companion.UNREAD + " != 0")
        val filter = filterBuilder.build()
        val values = ContentValues()
        values.put(Schema.Inbox.Columns.Companion.UNREAD, 0)
        database.execute<Any?>(
            ExecuteCallback { database: SQLiteDatabase ->
                database.update(
                    Schema.Inbox.Companion.TABLE_NAME,
                    values,
                    filter.value,
                    filter.args,
                )
                notifyChanged(database)
                null
            },
        )
    }

    fun remove(
        chanName: String,
        boardName: String?,
        threadNumber: String,
        postNumber: PostNumber,
    ) {
        val filter = postFilter(chanName, boardName, threadNumber, postNumber)
        database.execute<Any?>(
            ExecuteCallback { database: SQLiteDatabase ->
                database.delete(
                    Schema.Inbox.Companion.TABLE_NAME,
                    filter.value,
                    filter.args,
                )
                notifyChanged(database)
                null
            },
        )
    }

    fun clearInbox(chanName: String?) {
        val filterBuilder = Expression.filter()
        if (chanName != null) {
            filterBuilder.equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
        }
        val filter = filterBuilder.build()
        database.execute<Any?>(
            ExecuteCallback { database: SQLiteDatabase ->
                database.delete(
                    Schema.Inbox.Companion.TABLE_NAME,
                    filter.value,
                    filter.args,
                )
                notifyChanged(database)
                null
            },
        )
    }

    companion object {
        private fun createTable(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE " + Schema.Inbox.Companion.TABLE_NAME + " (" +
                    Schema.Inbox.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Inbox.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Inbox.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MAJOR + " INTEGER NOT NULL, " +
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MINOR + " INTEGER NOT NULL, " +
                    Schema.Inbox.Columns.Companion.TIME + " INTEGER NOT NULL, " +
                    Schema.Inbox.Columns.Companion.COMMENT + " TEXT, " +
                    Schema.Inbox.Columns.Companion.TITLE + " TEXT, " +
                    Schema.Inbox.Columns.Companion.UNREAD + " INTEGER NOT NULL DEFAULT 1, " +
                    "PRIMARY KEY (" + Schema.Inbox.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Inbox.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Inbox.Columns.Companion.THREAD_NUMBER + ", " +
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MINOR + "))",
            )
            database.execSQL(
                "CREATE INDEX " + Schema.Inbox.Companion.TABLE_NAME + "_order " +
                    "ON " + Schema.Inbox.Companion.TABLE_NAME + " (" +
                    Schema.Inbox.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Inbox.Columns.Companion.TIME + ")",
            )
        }

        private fun replyValues(
            chanName: String,
            boardName: String?,
            threadNumber: String,
            title: String?,
            reply: PagesDatabase.InsertResult.Reply,
            postNumber: PostNumber,
        ): ContentValues {
            val values = ContentValues()
            values.put(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
            values.put(Schema.Inbox.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
            values.put(Schema.Inbox.Columns.Companion.THREAD_NUMBER, threadNumber)
            values.put(Schema.Inbox.Columns.Companion.POST_NUMBER_MAJOR, postNumber.major)
            values.put(Schema.Inbox.Columns.Companion.POST_NUMBER_MINOR, postNumber.minor)
            // A post without a time would sort to 1970 and land under "older than 7 days"
            values.put(
                Schema.Inbox.Columns.Companion.TIME,
                if (reply.timestamp > 0) reply.timestamp else System.currentTimeMillis(),
            )
            values.put(Schema.Inbox.Columns.Companion.COMMENT, reply.comment)
            values.put(Schema.Inbox.Columns.Companion.TITLE, title)
            values.put(Schema.Inbox.Columns.Companion.UNREAD, 1)
            return values
        }

        private fun threadFilter(
            chanName: String,
            boardName: String?,
            threadNumber: String,
        ): Expression.Filter =
            Expression
                .filter()
                .equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Inbox.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Schema.Inbox.Columns.Companion.THREAD_NUMBER, threadNumber)
                .build()

        private fun postFilter(
            chanName: String,
            boardName: String?,
            threadNumber: String,
            postNumber: PostNumber,
        ): Expression.Filter =
            Expression
                .filter()
                .equals(Schema.Inbox.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Inbox.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Schema.Inbox.Columns.Companion.THREAD_NUMBER, threadNumber)
                .equals(
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MAJOR,
                    postNumber.major.toString(),
                ).equals(
                    Schema.Inbox.Columns.Companion.POST_NUMBER_MINOR,
                    postNumber.minor.toString(),
                ).build()
    }
}
