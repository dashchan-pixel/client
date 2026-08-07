package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.database.MergeCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Pair
import android.util.Xml
import chan.content.model.BoardCategory
import chan.util.CommonUtils
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.database.ChanDatabase.Schema.Bans
import com.mishiranu.dashchan.content.database.ChanDatabase.Schema.Boards
import com.mishiranu.dashchan.content.database.ChanDatabase.Schema.Cookies
import com.mishiranu.dashchan.content.database.Expression.BindBatchInsertArgs
import com.mishiranu.dashchan.content.database.Expression.CreateBatchInsertStatement
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.LruCache
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FileInputStream
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class ChanDatabase private constructor() {
    private interface Schema {
        interface Boards {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val CATEGORY: String = "category"
                }
            }

            companion object {
                const val TABLE_NAME: String = "boards"
            }
        }

        interface Data {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val NAME: String = "name"
                    const val VALUE: String = "value"
                }
            }

            companion object {
                const val TABLE_NAME: String = "data"
            }
        }

        interface Cookies {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val NAME: String = "name"
                    const val VALUE: String = "value"
                    const val TITLE: String = "title"
                    const val FLAGS: String = "flags"
                }
            }

            interface Flags {
                companion object {
                    const val DELETED: Int = 0x00000001
                    const val BLOCKED: Int = 0x00000002
                    const val DELETE_ON_EXIT: Int = 0x00000004
                }
            }

            companion object {
                const val TABLE_NAME: String = "cookies"
            }
        }

        interface Bans {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val BAN_ID: String = "ban_id"
                    const val MESSAGE: String = "message"
                    const val START_DATE: String = "start_date"
                    const val EXPIRE_DATE: String = "expire_date"
                    const val ADDRESS: String = "address"
                    const val CREATED: String = "created"
                    const val UPDATED: String = "updated"
                    const val ATTEMPTS: String = "attempts"
                }
            }

            companion object {
                const val TABLE_NAME: String = "bans"
            }
        }

        interface Extra {
            interface Columns {
                companion object {
                    const val EXTRA1: String = "extra1"
                    const val EXTRA2: String = "extra2"
                }
            }
        }
    }

    fun interface BoardExtraFallbackProvider {
        fun getExtra(boardName: String?): String?
    }

    class BoardCursor internal constructor(
        cursor: Cursor,
        val hasItems: Boolean,
        val filtered: Boolean,
        internal val provider1: BoardExtraFallbackProvider?,
        internal val provider2: BoardExtraFallbackProvider?,
    ) : CursorWrapper(cursor) {
        internal val boardNameIndex: Int
        internal val categoryIndex: Int
        internal val extra1Index: Int
        internal val extra2Index: Int

        init {
            boardNameIndex = cursor.getColumnIndex(Boards.Columns.Companion.BOARD_NAME)
            categoryIndex = cursor.getColumnIndex(Boards.Columns.Companion.CATEGORY)
            extra1Index = cursor.getColumnIndex(Schema.Extra.Columns.Companion.EXTRA1)
            extra2Index = cursor.getColumnIndex(Schema.Extra.Columns.Companion.EXTRA2)
        }
    }

    class BoardItem {
        var boardName: String? = null
        var category: String? = null
        var extra1: String? = null
        var extra2: String? = null

        fun update(cursor: BoardCursor): BoardItem {
            boardName = cursor.getString(cursor.boardNameIndex)
            category =
                if (cursor.categoryIndex >= 0) cursor.getString(cursor.categoryIndex) else null
            extra1 = if (cursor.extra1Index >= 0) cursor.getString(cursor.extra1Index) else null
            extra2 = if (cursor.extra2Index >= 0) cursor.getString(cursor.extra2Index) else null
            if (isEmpty(extra1) && cursor.provider1 != null) {
                extra1 = cursor.provider1.getExtra(boardName)
            }
            if (isEmpty(extra2) && cursor.provider2 != null) {
                extra2 = cursor.provider2.getExtra(boardName)
            }
            return this
        }

        fun copy(): BoardItem {
            val boardItem = BoardItem()
            boardItem.boardName = boardName
            boardItem.category = category
            boardItem.extra1 = extra1
            boardItem.extra2 = extra2
            return boardItem
        }
    }

    class DataKey(
        boardName: String?,
        name: String?,
    ) {
        @JvmField
        val boardName: String

        @JvmField
        val name: String

        init {
            this.boardName = emptyIfNull(boardName)
            this.name = emptyIfNull(name)
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is DataKey) {
                return boardName == other.boardName && name == other.name
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + boardName.hashCode()
            result = prime * result + name.hashCode()
            return result
        }
    }

    class CookieCursor internal constructor(
        cursor: Cursor,
    ) : CursorWrapper(cursor) {
        internal val nameIndex: Int
        internal val valueIndex: Int
        internal val titleIndex: Int
        internal val flagsIndex: Int

        init {
            nameIndex = cursor.getColumnIndex(Cookies.Columns.Companion.NAME)
            valueIndex = cursor.getColumnIndex(Cookies.Columns.Companion.VALUE)
            titleIndex = cursor.getColumnIndex(Cookies.Columns.Companion.TITLE)
            flagsIndex = cursor.getColumnIndex(Cookies.Columns.Companion.FLAGS)
        }
    }

    class CookieItem {
        var name: String? = null
        var value: String? = null
        var title: String? = null
        var blocked: Boolean = false
        var deleteOnExit: Boolean = false

        fun update(cursor: CookieCursor): CookieItem {
            name = cursor.getString(cursor.nameIndex)
            value = cursor.getString(cursor.valueIndex)
            title = cursor.getString(cursor.titleIndex)
            blocked = get(cursor.getInt(cursor.flagsIndex), Cookies.Flags.Companion.BLOCKED)
            deleteOnExit =
                get(cursor.getInt(cursor.flagsIndex), Cookies.Flags.Companion.DELETE_ON_EXIT)
            return this
        }

        fun copy(): CookieItem {
            val cookieItem = CookieItem()
            cookieItem.name = name
            cookieItem.value = value
            cookieItem.title = title
            cookieItem.blocked = blocked
            cookieItem.deleteOnExit = deleteOnExit
            return cookieItem
        }
    }

    class BanCursor internal constructor(
        cursor: Cursor,
    ) : CursorWrapper(cursor) {
        internal val rowIdIndex: Int = cursor.getColumnIndex("rowid")
        internal val boardNameIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.BOARD_NAME)
        internal val threadNumberIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.THREAD_NUMBER)
        internal val banIdIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.BAN_ID)
        internal val messageIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.MESSAGE)
        internal val startDateIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.START_DATE)
        internal val expireDateIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.EXPIRE_DATE)
        internal val addressIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.ADDRESS)
        internal val createdIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.CREATED)
        internal val updatedIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.UPDATED)
        internal val attemptsIndex: Int = cursor.getColumnIndex(Bans.Columns.Companion.ATTEMPTS)
    }

    /**
     * One ban the forum answered a post with. [expireDate] follows [chan.content.ApiException.BanExtra]:
     * 0 when the forum didn't say, [Long.MAX_VALUE] when the ban never expires.
     */
    class BanItem {
        var rowId: Long = 0
        var boardName: String? = null
        var threadNumber: String? = null
        var banId: String? = null
        var message: String? = null
        var startDate: Long = 0
        var expireDate: Long = 0
        var address: String? = null
        var created: Long = 0
        var updated: Long = 0
        var attempts: Int = 0

        fun update(cursor: BanCursor): BanItem {
            rowId = if (cursor.rowIdIndex >= 0) cursor.getLong(cursor.rowIdIndex) else 0
            boardName = cursor.getString(cursor.boardNameIndex)
            threadNumber = cursor.getString(cursor.threadNumberIndex)
            banId = cursor.getString(cursor.banIdIndex)
            message = cursor.getString(cursor.messageIndex)
            startDate = cursor.getLong(cursor.startDateIndex)
            expireDate = cursor.getLong(cursor.expireDateIndex)
            address = cursor.getString(cursor.addressIndex)
            created = cursor.getLong(cursor.createdIndex)
            updated = cursor.getLong(cursor.updatedIndex)
            attempts = cursor.getInt(cursor.attemptsIndex)
            return this
        }

        fun copy(): BanItem {
            val banItem = BanItem()
            banItem.rowId = rowId
            banItem.boardName = boardName
            banItem.threadNumber = threadNumber
            banItem.banId = banId
            banItem.message = message
            banItem.startDate = startDate
            banItem.expireDate = expireDate
            banItem.address = address
            banItem.created = created
            banItem.updated = updated
            banItem.attempts = attempts
            return banItem
        }
    }

    private val helper = Helper()
    private val database: SQLiteDatabase = helper.getWritableDatabase()
    private val supportsCte: Boolean

    private class Helper : SQLiteOpenHelper(MainApplication.getInstance(), DATABASE_NAME, null, DATABASE_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE " + Boards.Companion.TABLE_NAME + " (" +
                    Boards.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Boards.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Boards.Columns.Companion.CATEGORY + " TEXT NOT NULL, " +
                    "PRIMARY KEY (" + Boards.Columns.Companion.CHAN_NAME + ", " +
                    Boards.Columns.Companion.BOARD_NAME + "))",
            )
            db.execSQL(
                "CREATE TABLE " + Schema.Data.Companion.TABLE_NAME + " (" +
                    Schema.Data.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Data.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Data.Columns.Companion.NAME + " TEXT NOT NULL, " +
                    Schema.Data.Columns.Companion.VALUE + " TEXT, " +
                    "PRIMARY KEY (" + Schema.Data.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Data.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Data.Columns.Companion.NAME + "))",
            )
            db.execSQL(
                "CREATE TABLE " + Cookies.Companion.TABLE_NAME + " (" +
                    Cookies.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Cookies.Columns.Companion.NAME + " TEXT NOT NULL, " +
                    Cookies.Columns.Companion.VALUE + " TEXT NOT NULL, " +
                    Cookies.Columns.Companion.TITLE + " TEXT NOT NULL, " +
                    Cookies.Columns.Companion.FLAGS + " INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (" + Cookies.Columns.Companion.CHAN_NAME + ", " +
                    Cookies.Columns.Companion.NAME + "))",
            )
            createBans(db)
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            if (oldVersion < 2) {
                createBans(db)
            }
        }

        companion object {
            private const val DATABASE_NAME = "chan.db"
            private const val DATABASE_VERSION = 2

            /**
             * The unique key is what the extension told us about the ban, so the same ban blocking
             * another post updates its row instead of piling up a duplicate. The thread it was hit
             * in is context of the first sighting only: a ban is not thread-scoped.
             */
            private fun createBans(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE " + Bans.Companion.TABLE_NAME + " (" +
                        Bans.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                        Bans.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                        Bans.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                        Bans.Columns.Companion.BAN_ID + " TEXT NOT NULL, " +
                        Bans.Columns.Companion.MESSAGE + " TEXT NOT NULL, " +
                        Bans.Columns.Companion.START_DATE + " INTEGER NOT NULL DEFAULT 0, " +
                        Bans.Columns.Companion.EXPIRE_DATE + " INTEGER NOT NULL DEFAULT 0, " +
                        Bans.Columns.Companion.ADDRESS + " TEXT NOT NULL DEFAULT '', " +
                        Bans.Columns.Companion.CREATED + " INTEGER NOT NULL DEFAULT 0, " +
                        Bans.Columns.Companion.UPDATED + " INTEGER NOT NULL DEFAULT 0, " +
                        Bans.Columns.Companion.ATTEMPTS + " INTEGER NOT NULL DEFAULT 1, " +
                        "UNIQUE (" + Bans.Columns.Companion.CHAN_NAME + ", " +
                        Bans.Columns.Companion.BOARD_NAME + ", " +
                        Bans.Columns.Companion.BAN_ID + ", " +
                        Bans.Columns.Companion.MESSAGE + ", " +
                        Bans.Columns.Companion.START_DATE + ", " +
                        Bans.Columns.Companion.EXPIRE_DATE + "))",
                )
            }
        }
    }

    fun setBoards(
        chanName: String,
        boardCategories: Array<out BoardCategory?>?,
    ): Boolean {
        Objects.requireNonNull<String?>(chanName)
        val boardsList = ArrayList<Pair<String?, String?>?>()
        if (boardCategories != null) {
            for (boardCategory in boardCategories) {
                if (boardCategory != null) {
                    val category = emptyIfNull(boardCategory.getTitle())
                    val boards = boardCategory.getBoards()
                    if (boards != null) {
                        for (board in boards) {
                            if (board != null) {
                                val boardName = board.getBoardName()
                                if (!isEmpty(boardName)) {
                                    boardsList.add(Pair<String?, String?>(boardName, category))
                                }
                            }
                        }
                    }
                }
            }
        }
        database.beginTransaction()
        try {
            val filter =
                Expression
                    .filter()
                    .equals(Boards.Columns.Companion.CHAN_NAME, chanName)
                    .build()
            database.delete(Boards.Companion.TABLE_NAME, filter.value, filter.args)
            val iterator: MutableIterator<Pair<String?, String?>?> = boardsList.iterator()
            Expression.batchInsert(
                boardsList.size,
                100,
                3,
                CreateBatchInsertStatement { values: String? ->
                    database.compileStatement(
                        "INSERT OR REPLACE " +
                            "INTO " + Boards.Companion.TABLE_NAME + " (" +
                            Boards.Columns.Companion.CHAN_NAME + ", " +
                            Boards.Columns.Companion.BOARD_NAME + ", " +
                            Boards.Columns.Companion.CATEGORY + ") " +
                            "VALUES " + values,
                    )
                },
                BindBatchInsertArgs { statement: SQLiteStatement?, start: Int ->
                    val pair = iterator.next()!!
                    statement!!.bindString(start + 1, chanName)
                    statement.bindString(start + 2, pair.first)
                    statement.bindString(start + 3, pair.second)
                },
            )
            database.setTransactionSuccessful()
            return !boardsList.isEmpty()
        } finally {
            database.endTransaction()
        }
    }

    @Throws(OperationCanceledException::class)
    fun getBoards(
        chanName: String,
        searchQuery: String?,
        extraName: String,
        provider: BoardExtraFallbackProvider?,
        signal: CancellationSignal?,
    ): BoardCursor {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(extraName)
        val projection = arrayOf<String?>("COUNT(*)")
        val countFilter =
            Expression
                .filter()
                .equals(Boards.Columns.Companion.CHAN_NAME, chanName)
                .build()
        val count: Int
        database
            .query(
                false,
                Boards.Companion.TABLE_NAME,
                projection,
                countFilter.value,
                countFilter.args,
                null,
                null,
                null,
                null,
                signal,
            ).use { cursor ->
                count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        val filterBuilder =
            Expression
                .filter()
                .equals("b." + Boards.Columns.Companion.CHAN_NAME, chanName)
        var filtered = false
        if (!isEmpty(searchQuery)) {
            filterBuilder.append(
                Expression
                    .filterOr()
                    .like("b." + Boards.Columns.Companion.BOARD_NAME, "%" + searchQuery + "%")
                    .like("d." + Schema.Data.Columns.Companion.VALUE, "%" + searchQuery + "%"),
            )
            filtered = true
        }
        val filter = filterBuilder.build()
        val args = arrayOfNulls<String>(1 + filter.args!!.size)
        args[0] = extraName
        System.arraycopy(filter.args, 0, args, 1, filter.args.size)
        val cursor =
            database.rawQuery(
                "SELECT b.rowid, " +
                    "b." + Boards.Columns.Companion.BOARD_NAME + ", " +
                    "b." + Boards.Columns.Companion.CATEGORY + ", " +
                    "d." + Schema.Data.Columns.Companion.VALUE + " AS " + Schema.Extra.Columns.Companion.EXTRA1 + " " +
                    "FROM " + Boards.Companion.TABLE_NAME + " AS b " +
                    "LEFT JOIN " + Schema.Data.Companion.TABLE_NAME + " AS d " +
                    "ON b." + Boards.Columns.Companion.CHAN_NAME + " = d." + Schema.Data.Columns.Companion.CHAN_NAME + " AND " +
                    "b." + Boards.Columns.Companion.BOARD_NAME + " = d." + Schema.Data.Columns.Companion.BOARD_NAME + " AND " +
                    "d." + Schema.Data.Columns.Companion.NAME + " = ? " +
                    "WHERE " + filter.value + " " +
                    "ORDER BY b.rowid ASC",
                args,
                signal,
            )
        return BoardCursor(cursor, count > 0, filtered, provider, null)
    }

    private fun getBoards(
        chanName: String,
        boardNames: List<String>,
        filter: Expression.Filter,
        extra1Name: String,
        extra2Name: String,
        signal: CancellationSignal?,
    ): Cursor? {
        val args =
            arrayOfNulls<String>(boardNames.size + 4 + (if (filter.args != null) filter.args.size else 0))
        val valuesBuilder = StringBuilder()
        for (i in boardNames.indices) {
            val first = valuesBuilder.length == 0
            if (supportsCte) {
                if (first) {
                    valuesBuilder.append("VALUES")
                } else {
                    valuesBuilder.append(',')
                }
                valuesBuilder.append(" (?)")
            } else {
                if (!first) {
                    valuesBuilder.append(" UNION ALL ")
                }
                valuesBuilder.append("SELECT ?")
                if (first) {
                    valuesBuilder.append(" AS ").append(Boards.Columns.Companion.BOARD_NAME)
                }
            }
            args[i] = boardNames[i]
        }
        args[boardNames.size] = chanName
        args[boardNames.size + 1] = extra1Name
        args[boardNames.size + 2] = chanName
        args[boardNames.size + 3] = extra2Name
        if (filter.args != null) {
            System.arraycopy(filter.args, 0, args, boardNames.size + 4, filter.args.size)
        }
        val cursor =
            database.rawQuery(
                (
                    if (supportsCte) {
                        (
                            "WITH " + Boards.Companion.TABLE_NAME + " (" +
                                Boards.Columns.Companion.BOARD_NAME + ") AS (" + valuesBuilder + ") "
                        )
                    } else {
                        ""
                    }
                ) +
                    "SELECT b." + Boards.Columns.Companion.BOARD_NAME + ", " +
                    "d1." + Schema.Data.Columns.Companion.VALUE + " AS " + Schema.Extra.Columns.Companion.EXTRA1 + ", " +
                    "d2." + Schema.Data.Columns.Companion.VALUE + " AS " + Schema.Extra.Columns.Companion.EXTRA2 + " " +
                    "FROM " + (if (supportsCte) Boards.Companion.TABLE_NAME else "(" + valuesBuilder + ")") + " AS b " +
                    "LEFT JOIN " + Schema.Data.Companion.TABLE_NAME + " AS d1 " +
                    "ON d1." + Schema.Data.Columns.Companion.CHAN_NAME + " = ? AND " +
                    "b." + Boards.Columns.Companion.BOARD_NAME + " = d1." + Schema.Data.Columns.Companion.BOARD_NAME + " AND " +
                    "d1." + Schema.Data.Columns.Companion.NAME + " = ? " +
                    "LEFT JOIN " + Schema.Data.Companion.TABLE_NAME + " AS d2 " +
                    "ON d2." + Schema.Data.Columns.Companion.CHAN_NAME + " = ? AND " +
                    "b." + Boards.Columns.Companion.BOARD_NAME + " = d2." + Schema.Data.Columns.Companion.BOARD_NAME + " AND " +
                    "d2." + Schema.Data.Columns.Companion.NAME + " = ? " +
                    "WHERE " + (if (filter.value != null) filter.value else "1"),
                args,
                signal,
            )
        if (cursor.getCount() > 0) {
            return cursor
        } else {
            cursor.close()
            return null
        }
    }

    @Throws(OperationCanceledException::class)
    fun getBoards(
        chanName: String,
        boardNames: List<String>,
        searchQuery: String?,
        extra1Name: String,
        extra2Name: String,
        provider1: BoardExtraFallbackProvider?,
        provider2: BoardExtraFallbackProvider?,
        signal: CancellationSignal?,
    ): BoardCursor {
        val filterBuilder = Expression.filterOr()
        var filtered = false
        if (!isEmpty(searchQuery)) {
            filterBuilder.like("b." + Boards.Columns.Companion.BOARD_NAME, "%" + searchQuery + "%")
            filterBuilder.like("d1." + Schema.Data.Columns.Companion.VALUE, "%" + searchQuery + "%")
            filtered = true
        }
        val filter = filterBuilder.build()
        val limit = 500
        val cursors = ArrayList<Cursor>()
        var success = false
        try {
            var i = 0
            while (i < boardNames.size) {
                val cursor =
                    getBoards(
                        chanName,
                        boardNames.subList(i, min(i + limit, boardNames.size)),
                        filter,
                        extra1Name,
                        extra2Name,
                        signal,
                    )
                if (cursor != null) {
                    cursors.add(cursor)
                }
                i += limit
            }
            success = true
        } finally {
            if (!success) {
                for (cursor in cursors) {
                    cursor.close()
                }
            }
        }
        return BoardCursor(
            if (cursors.isEmpty()) {
                MatrixCursor(arrayOfNulls<String>(0), 0)
            } else {
                MergeCursor(
                    CommonUtils.toArray(cursors, Cursor::class.java),
                )
            },
            !boardNames.isEmpty(),
            filtered,
            provider1,
            provider2,
        )
    }

    private val dataCacheMap = HashMap<String?, LruCache<DataKey?, String?>?>()

    fun setData(
        chanName: String,
        map: MutableMap<DataKey?, Any?>?,
    ) {
        Objects.requireNonNull<String?>(chanName)
        if (map != null && !map.isEmpty()) {
            val filter =
                Expression
                    .filter()
                    .equals(Schema.Data.Columns.Companion.CHAN_NAME, chanName)
                    .equals(Schema.Data.Columns.Companion.BOARD_NAME, "")
                    .equals(Schema.Data.Columns.Companion.NAME, "")
                    .build()
            database.beginTransaction()
            try {
                var totalReplace = 0
                for (value in map.values) {
                    if (value != null) {
                        totalReplace++
                    }
                }
                val replaceIterator: MutableIterator<MutableMap.MutableEntry<DataKey?, Any?>> =
                    map.entries.iterator()
                Expression.batchInsert(
                    totalReplace,
                    100,
                    4,
                    CreateBatchInsertStatement { values: String? ->
                        database.compileStatement(
                            "INSERT OR REPLACE " +
                                "INTO " + Schema.Data.Companion.TABLE_NAME + " (" +
                                Schema.Data.Columns.Companion.CHAN_NAME + ", " +
                                Schema.Data.Columns.Companion.BOARD_NAME + ", " +
                                Schema.Data.Columns.Companion.NAME + ", " +
                                Schema.Data.Columns.Companion.VALUE + ") " +
                                "VALUES " + values,
                        )
                    },
                    BindBatchInsertArgs { statement: SQLiteStatement?, start: Int ->
                        var entry: MutableMap.MutableEntry<DataKey?, Any?>
                        do {
                            entry = replaceIterator.next()
                        } while (entry.value == null)
                        val dataKey = entry.key!!
                        val value = entry.value!!
                        statement!!.bindString(start + 1, chanName)
                        statement.bindString(start + 2, dataKey.boardName)
                        statement.bindString(start + 3, dataKey.name)
                        if (value is Boolean) {
                            statement.bindLong(start + 4, (if (value) 1 else 0).toLong())
                        } else if (value is Int) {
                            statement.bindLong(start + 4, value.toLong())
                        } else {
                            statement.bindString(start + 4, value.toString())
                        }
                    },
                )
                for (entry in map.entries) {
                    if (entry.value == null) {
                        val dataKey: DataKey = entry.key!!
                        filter.args!![1] = dataKey.boardName
                        filter.args[2] = dataKey.name
                        database.delete(Schema.Data.Companion.TABLE_NAME, filter.value, filter.args)
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
                synchronized(dataCacheMap) {
                    val dataCache = dataCacheMap[chanName]
                    if (dataCache != null) {
                        dataCache.keys.removeAll(map.keys)
                    }
                }
            }
        }
    }

    fun getData(
        chanName: String,
        boardName: String,
        name: String,
    ): String? {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(boardName)
        Objects.requireNonNull<String?>(name)
        val dataKey = DataKey(boardName, name)
        synchronized(dataCacheMap) {
            val dataCache = dataCacheMap[chanName]
            if (dataCache != null && dataCache.containsKey(dataKey)) {
                return dataCache[dataKey]
            }
        }
        val filter =
            Expression
                .filter()
                .equals(Schema.Data.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Data.Columns.Companion.BOARD_NAME, boardName)
                .equals(Schema.Data.Columns.Companion.NAME, name)
                .build()
        val value: String?
        val projection = arrayOf<String?>(Schema.Data.Columns.Companion.VALUE)
        database
            .query(
                Schema.Data.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ).use { cursor ->
                value = if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        synchronized(dataCacheMap) {
            var dataCache = dataCacheMap[chanName]
            if (dataCache == null) {
                dataCache =
                    LruCache<DataKey?, String?>(if (MainApplication.getInstance().isLowRam) 20 else 50)
                dataCacheMap[chanName] = dataCache
            }
            dataCache[dataKey] = value
        }
        return value
    }

    fun setCookie(
        chanName: String,
        name: String,
        value: String?,
        title: String?,
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(name)
        val filter =
            Expression
                .filter()
                .equals(Cookies.Columns.Companion.CHAN_NAME, chanName)
                .equals(Cookies.Columns.Companion.NAME, name)
                .build()
        database.beginTransaction()
        try {
            if (value != null) {
                val args = arrayOfNulls<String>(filter.args!!.size + (if (title != null) 2 else 1))
                args[0] = value
                if (title != null) {
                    args[1] = title
                }
                System.arraycopy(
                    filter.args,
                    0,
                    args,
                    args.size - filter.args.size,
                    filter.args.size,
                )
                database.execSQL(
                    "UPDATE " + Cookies.Companion.TABLE_NAME + " " +
                        "SET " + Cookies.Columns.Companion.VALUE + " = ?, " +
                        (if (title != null) Cookies.Columns.Companion.TITLE + " = ?, " else "") +
                        Cookies.Columns.Companion.FLAGS + " = " +
                        Cookies.Columns.Companion.FLAGS + " & " +
                        Cookies.Flags.Companion.DELETED
                            .inv() + " " +
                        "WHERE " + filter.value,
                    args,
                )
                val updated: Boolean
                database.rawQuery("SELECT CHANGES()", null).use { cursor ->
                    updated = cursor.moveToFirst() && cursor.getInt(0) > 0
                }
                if (!updated) {
                    val values = ContentValues()
                    values.put(Cookies.Columns.Companion.CHAN_NAME, chanName)
                    values.put(Cookies.Columns.Companion.NAME, name)
                    values.put(Cookies.Columns.Companion.VALUE, value)
                    values.put(Cookies.Columns.Companion.TITLE, emptyIfNull(title))
                    database.insert(Cookies.Companion.TABLE_NAME, null, values)
                }
            } else {
                database.execSQL(
                    "UPDATE " + Cookies.Companion.TABLE_NAME + " " +
                        "SET " + Cookies.Columns.Companion.VALUE + " = '', " +
                        Cookies.Columns.Companion.FLAGS + " = " +
                        Cookies.Columns.Companion.FLAGS + " | " + Cookies.Flags.Companion.DELETED + " " +
                        "WHERE " + filter.value,
                    filter.args as Array<out Any?>,
                )
                database.delete(
                    Cookies.Companion.TABLE_NAME,
                    "(" + filter.value + ") AND " +
                        Cookies.Columns.Companion.FLAGS + " = " + Cookies.Flags.Companion.DELETED,
                    filter.args,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun setCookieState(
        chanName: String,
        name: String,
        blocked: Boolean?,
        deleteOnExit: Boolean?,
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(name)
        if (blocked == null && deleteOnExit == null) {
            return
        }
        val filter =
            Expression
                .filter()
                .equals(Cookies.Columns.Companion.CHAN_NAME, chanName)
                .equals(Cookies.Columns.Companion.NAME, name)
                .build()
        database.beginTransaction()
        try {
            val clearFlags =
                (if (blocked != null) Cookies.Flags.Companion.BLOCKED else 0) or
                    (if (deleteOnExit != null) Cookies.Flags.Companion.DELETE_ON_EXIT else 0)
            val setFlags =
                (if (blocked != null && blocked) Cookies.Flags.Companion.BLOCKED else 0) or
                    (if (deleteOnExit != null && deleteOnExit) Cookies.Flags.Companion.DELETE_ON_EXIT else 0)
            database.execSQL(
                "UPDATE " + Cookies.Companion.TABLE_NAME + " " +
                    "SET " + Cookies.Columns.Companion.FLAGS + " = " +
                    Cookies.Columns.Companion.FLAGS + " & " + clearFlags.inv() + " | " + setFlags + " " +
                    "WHERE " + filter.value,
                filter.args as Array<out Any?>,
            )
            if (setFlags == 0) {
                val delete: Boolean
                val projection = arrayOf<String?>(Cookies.Columns.Companion.FLAGS)
                database
                    .query(
                        Cookies.Companion.TABLE_NAME,
                        projection,
                        filter.value,
                        filter.args,
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        delete =
                            cursor.moveToFirst() &&
                            cursor.getInt(0) == Cookies.Flags.Companion.DELETED
                    }
                if (delete) {
                    database.delete(Cookies.Companion.TABLE_NAME, filter.value, filter.args)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun deleteCookiesOnExit() {
        database.execSQL(
            "UPDATE " + Cookies.Companion.TABLE_NAME + " " +
                "SET " + Cookies.Columns.Companion.VALUE + " = '', " +
                Cookies.Columns.Companion.FLAGS + " = " +
                Cookies.Columns.Companion.FLAGS + " | " + Cookies.Flags.Companion.DELETED + " " +
                "WHERE " + Cookies.Columns.Companion.FLAGS + " & " + Cookies.Flags.Companion.DELETE_ON_EXIT,
        )
    }

    private val requireCookiesReferenceCount = AtomicInteger(0)

    init {
        // Since SQLite 3.8.3
        var supportsCte: Boolean
        try {
            database.rawQuery("WITH x AS (SELECT 1) SELECT 1", null).use { ignored ->
                supportsCte = true
            }
        } catch (e: Exception) {
            supportsCte = false
        }
        this.supportsCte = supportsCte

        // Migrate old cookies
        val sharedPrefs = MainApplication.getInstance().getSharedPrefsDir().listFiles()
        if (sharedPrefs != null) {
            for (file in sharedPrefs) {
                val fileName = file.getName()
                if (fileName.startsWith("chan.") && fileName.endsWith(".xml")) {
                    val chanName = file.getName().substring(5, fileName.length - 4)
                    var cookiesJson: String? = null
                    try {
                        FileInputStream(file).use { input ->
                            val parser = Xml.newPullParser()
                            parser.setInput(input, "UTF-8")
                            var eventType: Int
                            do {
                                eventType = parser.next()
                                if (eventType == XmlPullParser.START_TAG && "string" == parser.getName()) {
                                    val count = parser.getAttributeCount()
                                    var name: String? = null
                                    for (i in 0..<count) {
                                        if ("name" == parser.getAttributeName(i)) {
                                            name = parser.getAttributeValue(i)
                                            break
                                        }
                                    }
                                    if ("cookies" == name) {
                                        eventType = parser.next()
                                        if (eventType == XmlPullParser.TEXT) {
                                            cookiesJson = parser.getText()
                                            break
                                        }
                                    }
                                }
                            } while (eventType != XmlPullParser.END_DOCUMENT)
                        }
                    } catch (e: IOException) {
                        // Ignore
                    } catch (e: XmlPullParserException) {
                    }
                    var cookiesObject: JSONObject? = null
                    if (cookiesJson != null) {
                        try {
                            cookiesObject = JSONObject(cookiesJson)
                        } catch (e: JSONException) {
                            // Ignore exception
                        }
                    }
                    if (cookiesObject != null) {
                        val iterator = cookiesObject.keys()
                        while (iterator.hasNext()) {
                            val name = iterator.next()
                            val jsonObject = cookiesObject.optJSONObject(name)
                            if (jsonObject != null) {
                                val value = jsonObject.optString("value")
                                if (!isEmpty(value)) {
                                    val title = jsonObject.optString("displayName")
                                    val blocked = jsonObject.optBoolean("blocked")
                                    setCookie(chanName, name, value, title)
                                    setCookieState(chanName, name, blocked, null)
                                }
                            }
                        }
                    }
                    file.delete()
                }
            }
        }
        deleteCookiesOnExit()
    }

    fun requireCookies(): Runnable {
        requireCookiesReferenceCount.incrementAndGet()
        return Runnable {
            if (requireCookiesReferenceCount.decrementAndGet() == 0) {
                deleteCookiesOnExit()
            }
        }
    }

    fun getCookieChecked(
        chanName: String,
        name: String,
    ): String? {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(name)
        val filter =
            Expression
                .filter()
                .equals(Cookies.Columns.Companion.CHAN_NAME, chanName)
                .equals(Cookies.Columns.Companion.NAME, name)
                .raw("NOT (" + Cookies.Columns.Companion.FLAGS + " & " + Cookies.Flags.Companion.DELETED + ")")
                .raw("NOT (" + Cookies.Columns.Companion.FLAGS + " & " + Cookies.Flags.Companion.BLOCKED + ")")
                .build()
        val projection = arrayOf<String?>(Cookies.Columns.Companion.VALUE)
        database
            .query(
                Cookies.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        return null
    }

    fun hasCookies(chanName: String): Boolean {
        val projection = arrayOf<String?>("1")
        val filter =
            Expression
                .filter()
                .equals(Cookies.Columns.Companion.CHAN_NAME, chanName)
                .build()
        database
            .query(
                Cookies.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return cursor.moveToFirst()
            }
    }

    fun getCookies(chanName: String): CookieCursor {
        val projection = arrayOf<String?>("rowid", "*")
        val filter =
            Expression
                .filter()
                .equals(Cookies.Columns.Companion.CHAN_NAME, chanName)
                .build()
        return CookieCursor(
            database.query(
                Cookies.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ),
        )
    }

    /**
     * Stores a ban the forum answered a post with, or refreshes the row of a ban already known.
     * Returns its rowid, so the address the forum saw can be filled in later by [setBanAddress]
     * once it has been resolved.
     */
    fun addBan(
        chanName: String,
        boardName: String?,
        threadNumber: String?,
        banId: String?,
        message: String?,
        startDate: Long,
        expireDate: Long,
    ): Long {
        Objects.requireNonNull<String?>(chanName)
        val time = System.currentTimeMillis()
        val filter =
            Expression
                .filter()
                .equals(Bans.Columns.Companion.CHAN_NAME, chanName)
                .equals(Bans.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Bans.Columns.Companion.BAN_ID, emptyIfNull(banId))
                .equals(Bans.Columns.Companion.MESSAGE, emptyIfNull(message))
                .equals(Bans.Columns.Companion.START_DATE, startDate.toString())
                .equals(Bans.Columns.Companion.EXPIRE_DATE, expireDate.toString())
                .build()
        database.beginTransaction()
        try {
            var rowId = findRowId(Bans.Companion.TABLE_NAME, filter)
            if (rowId >= 0) {
                database.execSQL(
                    "UPDATE " + Bans.Companion.TABLE_NAME + " " +
                        "SET " + Bans.Columns.Companion.UPDATED + " = ?, " +
                        Bans.Columns.Companion.ATTEMPTS + " = " +
                        Bans.Columns.Companion.ATTEMPTS + " + 1 " +
                        "WHERE rowid = ?",
                    arrayOf<Any>(time, rowId),
                )
            } else {
                val values = ContentValues()
                values.put(Bans.Columns.Companion.CHAN_NAME, chanName)
                values.put(Bans.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                values.put(Bans.Columns.Companion.THREAD_NUMBER, emptyIfNull(threadNumber))
                values.put(Bans.Columns.Companion.BAN_ID, emptyIfNull(banId))
                values.put(Bans.Columns.Companion.MESSAGE, emptyIfNull(message))
                values.put(Bans.Columns.Companion.START_DATE, startDate)
                values.put(Bans.Columns.Companion.EXPIRE_DATE, expireDate)
                values.put(Bans.Columns.Companion.ADDRESS, "")
                values.put(Bans.Columns.Companion.CREATED, time)
                values.put(Bans.Columns.Companion.UPDATED, time)
                values.put(Bans.Columns.Companion.ATTEMPTS, 1)
                rowId = database.insert(Bans.Companion.TABLE_NAME, null, values)
            }
            database.setTransactionSuccessful()
            return rowId
        } finally {
            database.endTransaction()
        }
    }

    /** The rowid of the first row [filter] matches, or -1 when it matches none. */
    private fun findRowId(
        tableName: String,
        filter: Expression.Filter,
    ): Long {
        val projection = arrayOf<String?>("rowid")
        database
            .query(
                tableName,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
    }

    /** Only fills an address in: a ban whose address is already known keeps the one it was caught with. */
    fun setBanAddress(
        rowId: Long,
        address: String,
    ) {
        if (rowId < 0 || isEmpty(address)) {
            return
        }
        database.execSQL(
            "UPDATE " + Bans.Companion.TABLE_NAME + " " +
                "SET " + Bans.Columns.Companion.ADDRESS + " = ? " +
                "WHERE rowid = ? AND " + Bans.Columns.Companion.ADDRESS + " = ''",
            arrayOf<Any>(address, rowId),
        )
    }

    fun getBans(chanName: String): BanCursor {
        val projection = arrayOf<String?>("rowid", "*")
        val filter =
            Expression
                .filter()
                .equals(Bans.Columns.Companion.CHAN_NAME, chanName)
                .build()
        return BanCursor(
            database.query(
                Bans.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                Bans.Columns.Companion.UPDATED + " DESC",
            ),
        )
    }

    /**
     * A ban counts as active until its expiration date passes; one the forum gave no date for is
     * never known to be over.
     */
    class BanCounts(
        val total: Int,
        val active: Int,
    )

    fun getBanCounts(chanName: String): BanCounts {
        var total = 0
        var active = 0
        val time = System.currentTimeMillis()
        val projection = arrayOf<String?>(Bans.Columns.Companion.EXPIRE_DATE)
        val filter =
            Expression
                .filter()
                .equals(Bans.Columns.Companion.CHAN_NAME, chanName)
                .build()
        database
            .query(
                Bans.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    total++
                    val expireDate = cursor.getLong(0)
                    if (expireDate <= 0 || expireDate > time) {
                        active++
                    }
                }
            }
        return BanCounts(total, active)
    }

    /**
     * The active ban the board has on record against [address], or null -- asked with the address
     * the forum currently sees, before a post is attempted from it. Bans are per-board (the board is
     * part of a ban's identity, see [addBan]), so a ban on another board of the same forum does not
     * count here. Active follows [getBanCounts]. An empty address never matches: a ban whose address
     * was never resolved is no evidence about one. The most recently seen one is returned, since
     * that is the one worth showing when several stand.
     */
    fun getActiveBanForAddress(
        chanName: String,
        boardName: String?,
        address: String,
    ): BanItem? {
        if (isEmpty(address)) {
            return null
        }
        val time = System.currentTimeMillis()
        val projection = arrayOf<String?>("rowid", "*")
        val filter =
            Expression
                .filter()
                .equals(Bans.Columns.Companion.CHAN_NAME, chanName)
                .equals(Bans.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                .equals(Bans.Columns.Companion.ADDRESS, address)
                .build()
        BanCursor(
            database.query(
                Bans.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                Bans.Columns.Companion.UPDATED + " DESC",
            ),
        ).use { cursor ->
            val banItem = BanItem()
            while (cursor.moveToNext()) {
                banItem.update(cursor)
                if (banItem.expireDate <= 0 || banItem.expireDate > time) {
                    return banItem
                }
            }
        }
        return null
    }

    fun deleteBan(rowId: Long) {
        database.delete(Bans.Companion.TABLE_NAME, "rowid = ?", arrayOf<String?>(rowId.toString()))
    }

    fun deleteBans(chanName: String) {
        val filter =
            Expression
                .filter()
                .equals(Bans.Columns.Companion.CHAN_NAME, chanName)
                .build()
        database.delete(Bans.Companion.TABLE_NAME, filter.value, filter.args)
    }

    companion object {
        private val INSTANCE = ChanDatabase()

        @JvmStatic
        fun getInstance(): ChanDatabase = INSTANCE
    }
}
