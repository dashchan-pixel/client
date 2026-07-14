package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.LongSparseArray
import android.util.Pair
import chan.http.HttpValidator
import chan.http.HttpValidator.Companion.deserialize
import chan.text.JsonSerial
import chan.text.JsonSerial.reader
import chan.text.JsonSerial.writer
import chan.text.ParseException
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.content.HidePerformer
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.MainApplication.Companion.getInstance
import com.mishiranu.dashchan.content.database.Expression.BindBatchInsertArgs
import com.mishiranu.dashchan.content.database.Expression.CreateBatchInsertStatement
import com.mishiranu.dashchan.content.database.Expression.KeyLock
import com.mishiranu.dashchan.content.database.PagesDatabase.InsertResult.Reply
import com.mishiranu.dashchan.content.database.PagesDatabase.Legacy.Post.InternalFlags
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.Post.Attachment.Embedded
import com.mishiranu.dashchan.content.model.Post.Attachment.Embedded.Companion.createExternal
import com.mishiranu.dashchan.content.model.Post.Attachment.File.Companion.createExternal
import com.mishiranu.dashchan.content.model.Post.Companion.deserialize
import com.mishiranu.dashchan.content.model.Post.Icon.Companion.createExternal
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostItem.HideState
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseOrThrow
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.util.ConcurrentUtils.mainGet
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.FlagUtils.set
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.util.Arrays
import java.util.Collections
import java.util.Objects
import java.util.UUID
import java.util.concurrent.Callable

class PagesDatabase private constructor() {
    private interface Schema {
        interface Meta {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val TIME: String = "time"
                    const val FLAGS: String = "flags"
                    const val DATA: String = "data"
                }
            }

            interface Flags {
                companion object {
                    const val DELETED: Int = 0x00000001
                    const val ERROR: Int = 0x00000002
                }
            }

            companion object {
                const val TABLE_NAME: String = "meta"
            }
        }

        interface Posts {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val POST_NUMBER_MAJOR: String = "post_number_major"
                    const val POST_NUMBER_MINOR: String = "post_number_minor"
                    const val FLAGS: String = "flags"
                    const val DATA: String = "data"
                    const val HASH: String = "hash"
                }
            }

            interface Flags {
                companion object {
                    const val DELETED: Int = 0x00000001
                    const val MARK_NEW: Int = 0x00000002
                    const val MARK_DELETED: Int = 0x00000004
                    const val MARK_EDITED: Int = 0x00000008
                    const val MARK_REPLY: Int = 0x00000010
                }
            }

            companion object {
                const val TABLE_NAME: String = "posts"
                const val MAX_COUNT: Int = 250000
                const val MAX_COUNT_FACTOR: Float = 0.75f
            }
        }
    }

    class Meta(
        val validator: HttpValidator?,
        val archivedThreadUri: Uri?,
        val uniquePosters: Int,
        val deleted: Boolean,
        val error: Boolean,
    ) {
        @Throws(IOException::class)
        fun serialize(writer: JsonSerial.Writer) {
            writer.startObject()
            if (validator != null) {
                writer.name("validator")
                validator.serialize(writer)
            }
            if (archivedThreadUri != null) {
                writer.name("archivedThreadUri")
                writer.value(archivedThreadUri.toString())
            }
            if (uniquePosters > 0) {
                writer.name("uniquePosters")
                writer.value(uniquePosters)
            }
            writer.endObject()
        }

        companion object {
            @Throws(IOException::class, ParseException::class)
            fun deserialize(
                reader: JsonSerial.Reader,
                deleted: Boolean,
                error: Boolean,
            ): Meta {
                var validator: HttpValidator? = null
                var archivedThreadUri: Uri? = null
                var uniquePosters = 0
                reader.startObject()
                while (!reader.endStruct()) {
                    when (reader.nextName()) {
                        "validator" -> {
                            validator = deserialize(reader)
                        }

                        "archivedThreadUri" -> {
                            archivedThreadUri = Uri.parse(reader.nextString())
                        }

                        "uniquePosters" -> {
                            uniquePosters = reader.nextInt()
                        }

                        else -> {
                            reader.skip()
                        }
                    }
                }
                return Meta(validator, archivedThreadUri, uniquePosters, deleted, error)
            }
        }
    }

    class WatcherState(
        @JvmField val newCount: Int,
        @JvmField val deleted: Boolean,
        @JvmField val error: Boolean,
        @JvmField val time: Long,
    )

    class ThreadKey(
        chanName: String,
        boardName: String?,
        threadNumber: String,
    ) {
        val chanName: String
        val boardName: String
        val threadNumber: String

        init {
            Objects.requireNonNull<String?>(chanName)
            Objects.requireNonNull<String?>(threadNumber)
            this.chanName = chanName
            this.boardName = emptyIfNull(boardName)
            this.threadNumber = threadNumber
        }

        internal fun filterMeta(): Expression.Filter.Builder? =
            Expression
                .filter()
                .equals(Schema.Meta.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Meta.Columns.Companion.BOARD_NAME, boardName)
                .equals(Schema.Meta.Columns.Companion.THREAD_NUMBER, threadNumber)

        internal fun filterPosts(): Expression.Filter.Builder? =
            Expression
                .filter()
                .equals(Schema.Posts.Columns.Companion.CHAN_NAME, chanName)
                .equals(Schema.Posts.Columns.Companion.BOARD_NAME, boardName)
                .equals(Schema.Posts.Columns.Companion.THREAD_NUMBER, threadNumber)

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is ThreadKey) {
                return chanName == other.chanName &&
                    boardName == other.boardName &&
                    threadNumber == other.threadNumber
            }
            return false
        }

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + chanName.hashCode()
            result = prime * result + boardName.hashCode()
            result = prime * result + threadNumber.hashCode()
            return result
        }
    }

    private class Serialized(
        val post: Post,
        val data: ByteArray?,
        val hash: ByteArray?,
        newThread: Boolean,
    ) {
        var flags: Int

        init {
            flags = if (newThread) 0 else Schema.Posts.Flags.Companion.MARK_NEW
        }
    }

    internal class DiffItem(
        val hash: ByteArray?,
        val deleted: Boolean,
    )

    private class Extracted(
        val data: ByteArray,
        val postNumber: PostNumber,
        val deleted: Boolean,
    )

    class Cache internal constructor(
        internal val diffItems: MutableMap<PostNumber, DiffItem>,
        val originalPostNumber: PostNumber?,
        @JvmField val state: State,
    ) {
        class State internal constructor(
            private val id: UUID,
            private var newThread: Boolean,
        ) {
            internal val isNewThreadOnce: Boolean
                get() {
                    if (newThread) {
                        synchronized(this) {
                            if (newThread) {
                                newThread = false
                                return true
                            }
                        }
                    }
                    return false
                }

            override fun equals(other: Any?): Boolean {
                if (other === this) {
                    return true
                }
                if (other is State) {
                    return other.id == id
                }
                return false
            }

            override fun hashCode(): Int = id.hashCode()
        }

        val isEmpty: Boolean
            get() = diffItems.isEmpty()

        fun isChanged(oldCache: Cache?): Boolean {
            // Compare references
            return oldCache == null || oldCache.diffItems !== diffItems
        }

        val isNewThreadOnce: Boolean
            get() = state.isNewThreadOnce
    }

    class InsertResult(
        val cacheState: Cache.State?,
        val replies: List<Reply>?,
        val newCount: Int,
    ) {
        class Reply(
            val postNumber: PostNumber?,
            val comment: String?,
            val timestamp: Long,
        )
    }

    class Diff(
        val cache: Cache,
        val changed: Collection<Post>,
        val removed: Collection<PostNumber>,
        val newPosts: Set<PostNumber>,
        val deletedPosts: Set<PostNumber>,
        val editedPosts: Set<PostNumber>,
        val replyPosts: Set<PostNumber>,
    )

    enum class Cleanup {
        NONE,
        ERASE,
        OLD,
        DELETED,
    }

    private enum class MigrationRequest {
        GET_META,
        COLLECT_DIFF_POSTS,
    }

    private val helper = Helper()
    private val database: SQLiteDatabase = helper.getWritableDatabase()

    private class Helper : SQLiteOpenHelper(MainApplication.getInstance(), DATABASE_NAME, null, DATABASE_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE " + Schema.Meta.Companion.TABLE_NAME + " (" +
                    Schema.Meta.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Meta.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Meta.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                    Schema.Meta.Columns.Companion.TIME + " INTEGER NOT NULL, " +
                    Schema.Meta.Columns.Companion.FLAGS + " INTEGER NOT NULL DEFAULT 0, " +
                    Schema.Meta.Columns.Companion.DATA + " BLOB NOT NULL, " +
                    "PRIMARY KEY (" + Schema.Meta.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Meta.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Meta.Columns.Companion.THREAD_NUMBER + "))",
            )
            db.execSQL(
                "CREATE INDEX " + Schema.Meta.Companion.TABLE_NAME + "_order " +
                    "ON " + Schema.Meta.Companion.TABLE_NAME + " (" +
                    Schema.Meta.Columns.Companion.TIME + ")",
            )
            db.execSQL(
                "CREATE TABLE " + Schema.Posts.Companion.TABLE_NAME + " (" +
                    Schema.Posts.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " INTEGER NOT NULL, " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + " INTEGER NOT NULL, " +
                    Schema.Posts.Columns.Companion.FLAGS + " INTEGER NOT NULL DEFAULT 0, " +
                    Schema.Posts.Columns.Companion.DATA + " BLOB NOT NULL, " +
                    Schema.Posts.Columns.Companion.HASH + " BLOB NOT NULL, " +
                    "PRIMARY KEY (" + Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Posts.Columns.Companion.THREAD_NUMBER + ", " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + "), " +
                    "FOREIGN KEY (" + Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Posts.Columns.Companion.THREAD_NUMBER + ") " +
                    "REFERENCES " + Schema.Meta.Companion.TABLE_NAME + " (" +
                    Schema.Meta.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Meta.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Meta.Columns.Companion.THREAD_NUMBER + ") " +
                    "ON DELETE CASCADE ON UPDATE CASCADE)",
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase?,
            oldVersion: Int,
            newVersion: Int,
        ) {}

        override fun onOpen(db: SQLiteDatabase?) {}

        companion object {
            private const val DATABASE_NAME = "pages.db"
            private const val DATABASE_VERSION = 1
        }
    }

    private fun cleanup(
        excludeThreads: MutableSet<ThreadKey?>,
        force: Boolean,
    ) {
        var removeThreads: HashSet<ThreadKey>? = null
        var shouldRemove = false
        database
            .rawQuery(
                "SELECT " +
                    "m." + Schema.Meta.Columns.Companion.CHAN_NAME + ", " +
                    "m." + Schema.Meta.Columns.Companion.BOARD_NAME + ", " +
                    "m." + Schema.Meta.Columns.Companion.THREAD_NUMBER + ", " +
                    "COUNT(p." + Schema.Meta.Columns.Companion.CHAN_NAME + ") " +
                    "FROM " + Schema.Meta.Companion.TABLE_NAME + " AS m " +
                    "JOIN " + Schema.Posts.Companion.TABLE_NAME + " AS p " +
                    "ON m." + Schema.Meta.Columns.Companion.CHAN_NAME + " = p." + Schema.Posts.Columns.Companion.CHAN_NAME + " AND " +
                    "m." + Schema.Meta.Columns.Companion.BOARD_NAME + " = p." + Schema.Posts.Columns.Companion.BOARD_NAME + " AND " +
                    "m." + Schema.Meta.Columns.Companion.THREAD_NUMBER + " = p." + Schema.Posts.Columns.Companion.THREAD_NUMBER + " " +
                    "GROUP BY p." + Schema.Meta.Columns.Companion.CHAN_NAME + ", " +
                    "p." + Schema.Meta.Columns.Companion.BOARD_NAME + ", " +
                    "p." + Schema.Meta.Columns.Companion.THREAD_NUMBER + " " +
                    "ORDER BY m." + Schema.Meta.Columns.Companion.TIME + " DESC",
                null,
            ).use { cursor ->
                var postCount = 0
                while (cursor.moveToNext()) {
                    val chanName = cursor.getString(0)
                    val boardName = cursor.getString(1)
                    val threadNumber = cursor.getString(2)
                    val threadKey = ThreadKey(chanName, boardName, threadNumber)
                    if (!excludeThreads.contains(threadKey)) {
                        postCount += cursor.getInt(3)
                        if (postCount > Schema.Posts.Companion.MAX_COUNT * Schema.Posts.Companion.MAX_COUNT_FACTOR || force) {
                            if (removeThreads == null) {
                                removeThreads = HashSet<ThreadKey>()
                            }
                            removeThreads.add(threadKey)
                        }
                        if (postCount > Schema.Posts.Companion.MAX_COUNT || force) {
                            shouldRemove = true
                        }
                    }
                }
            }
        if (removeThreads != null && !removeThreads.isEmpty() && shouldRemove) {
            database.beginTransaction()
            try {
                for (threadKey in removeThreads) {
                    val filter = threadKey.filterMeta()!!.build()
                    database.delete(Schema.Meta.Companion.TABLE_NAME, filter.value, filter.args)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            checkpoint()
        }
    }

    fun erase(keepThreads: Collection<ThreadKey>?) {
        val mainExcludeThreads =
            mainGet<HashSet<ThreadKey?>?>(
                Callable {
                    val excludeThreads = HashSet<ThreadKey?>()
                    for (favoriteItem in FavoritesStorage.getInstance().getThreads(null)) {
                        excludeThreads.add(
                            PagesDatabase.ThreadKey(
                                favoriteItem.chanName,
                                emptyIfNull(favoriteItem.boardName),
                                favoriteItem.threadNumber!!,
                            ),
                        )
                    }
                    excludeThreads
                },
            )
        if (keepThreads != null) {
            mainExcludeThreads!!.addAll(keepThreads)
        }
        cleanup(mainExcludeThreads!!, true)
    }

    fun eraseAll() {
        database.delete(Schema.Meta.Companion.TABLE_NAME, null, null)
        checkpoint()
    }

    private fun checkpoint() {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            cursor.moveToFirst()
        }
    }

    val size: Long
        get() {
            val file =
                MainApplication.getInstance().getDatabasePath(helper.getDatabaseName())
            return file.length() +
                File(file.getParentFile(), file.getName() + "-wal")
                    .length()
        }

    fun getMeta(
        threadKey: ThreadKey,
        temporary: Boolean,
    ): Meta? {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val projection =
            arrayOf<String?>(
                Schema.Meta.Columns.Companion.FLAGS,
                Schema.Meta.Columns.Companion.DATA,
            )
        val filter = threadKey.filterMeta()!!.build()
        var meta: Meta? = null
        database
            .query(
                Schema.Meta.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val flags = cursor.getInt(0)
                    val deleted = get(flags, Schema.Meta.Flags.Companion.DELETED)
                    val error = get(flags, Schema.Meta.Flags.Companion.ERROR)
                    try {
                        reader(cursor.getBlob(1)).use { reader ->
                            meta = Meta.Companion.deserialize(reader, deleted, error)
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return null
                    } catch (e: ParseException) {
                        e.printStackTrace()
                        return null
                    }
                }
            }
        if (meta != null) {
            if (!temporary) {
                val values = ContentValues()
                values.put(Schema.Meta.Columns.Companion.TIME, System.currentTimeMillis())
                database.update(Schema.Meta.Companion.TABLE_NAME, values, filter.value, filter.args)
            }
            return meta
        } else if (migratePosts(threadKey, MigrationRequest.GET_META)) {
            return getMeta(threadKey, temporary)
        } else {
            return null
        }
    }

    fun setMetaFlags(
        threadKey: ThreadKey,
        deleted: Boolean,
        error: Boolean,
    ) {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val filter = threadKey.filterMeta()!!.build()
        val clearFlags: Int =
            Schema.Meta.Flags.Companion.DELETED or Schema.Meta.Flags.Companion.ERROR
        val setFlags =
            (if (deleted) Schema.Meta.Flags.Companion.DELETED else 0) or (if (error) Schema.Meta.Flags.Companion.ERROR else 0)
        database.execSQL(
            "UPDATE " + Schema.Meta.Companion.TABLE_NAME + " " +
                "SET " + Schema.Meta.Columns.Companion.FLAGS + " = " +
                Schema.Meta.Columns.Companion.FLAGS + " & " + clearFlags.inv() + " | " + setFlags + " " +
                "WHERE " + filter.value,
            filter.args as Array<out Any?>,
        )
    }

    fun getLastExistingPostNumber(threadKey: ThreadKey): PostNumber? {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val projection =
            arrayOf<String?>(
                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
            )
        val filter =
            threadKey
                .filterPosts()!!
                .raw("NOT (" + Schema.Posts.Columns.Companion.FLAGS + " & " + Schema.Posts.Flags.Companion.DELETED + ")")
                .build()
        database
            .query(
                Schema.Posts.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                orderByPostNumber(true),
                "1",
            ).use { cursor ->
                return if (cursor.moveToFirst()) {
                    PostNumber(
                        cursor.getInt(0),
                        cursor.getInt(1),
                    )
                } else {
                    null
                }
            }
    }

    fun getOriginalPost(threadKey: ThreadKey): Post? {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val projection =
            arrayOf<String?>(
                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
                Schema.Posts.Columns.Companion.DATA,
            )
        val filter = threadKey.filterPosts()!!.build()
        database
            .query(
                Schema.Posts.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                orderByPostNumber(false),
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val postNumber = PostNumber(cursor.getInt(0), cursor.getInt(1))
                    try {
                        reader(cursor.getBlob(2)).use { reader ->
                            return deserialize(postNumber, false, reader)
                        }
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    } catch (e: ParseException) {
                        // Ignore
                    }
                }
            }
        return null
    }

    fun getPostNumbers(threadKey: ThreadKey): MutableList<PostNumber?> {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val projection =
            arrayOf<String?>(
                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
            )
        val filter = threadKey.filterPosts()!!.build()
        val postNumbers: ArrayList<PostNumber?>
        database
            .query(
                Schema.Posts.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                orderByPostNumber(false),
            ).use { cursor ->
                postNumbers = ArrayList<PostNumber?>(cursor.getCount())
                while (cursor.moveToNext()) {
                    postNumbers.add(PostNumber(cursor.getInt(0), cursor.getInt(1)))
                }
            }
        return postNumbers
    }

    fun getWatcherState(threadKey: ThreadKey): WatcherState {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        val newCount: Int
        var time: Long = 0
        var flags: Int = Schema.Meta.Flags.Companion.DELETED
        val newPostsFilter =
            threadKey
                .filterPosts()!!
                .raw(Schema.Posts.Columns.Companion.FLAGS + " & " + Schema.Posts.Flags.Companion.MARK_NEW)
                .build()
        database
            .rawQuery(
                "SELECT COUNT(*) " +
                    "FROM " + Schema.Posts.Companion.TABLE_NAME + " " +
                    "WHERE " + newPostsFilter.value,
                newPostsFilter.args,
            ).use { cursor ->
                newCount = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        val metaFilter = threadKey.filterMeta()!!.build()
        val metaProjection =
            arrayOf<String?>(
                Schema.Meta.Columns.Companion.TIME,
                Schema.Meta.Columns.Companion.FLAGS,
            )
        database
            .query(
                Schema.Meta.Companion.TABLE_NAME,
                metaProjection,
                metaFilter.value,
                metaFilter.args,
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    time = cursor.getLong(0)
                    flags = cursor.getInt(1)
                }
            }
        return WatcherState(
            newCount,
            get(flags, Schema.Meta.Flags.Companion.DELETED),
            get(flags, Schema.Meta.Flags.Companion.ERROR),
            time,
        )
    }

    private val cacheStates = HashMap<ThreadKey?, Cache.State?>()

    fun getCacheState(threadKey: ThreadKey?): Cache.State {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        synchronized(cacheStates) {
            var state = cacheStates[threadKey]
            if (state == null) {
                state = Cache.State(UUID.randomUUID(), false)
                cacheStates[threadKey] = state
            }
            return state
        }
    }

    private fun updateFlags(
        threadKey: ThreadKey,
        iterator: Expression.LongIterator,
        transform: String?,
    ) {
        // Use filter to properly handle reused rowid
        val filter = threadKey.filterPosts()!!.build()
        Expression.updateById(
            database,
            iterator,
            Schema.Posts.Companion.TABLE_NAME,
            "rowid",
            Schema.Posts.Columns.Companion.FLAGS + " = " + Schema.Posts.Columns.Companion.FLAGS + " " + transform,
            filter,
        )
    }

    @Throws(IOException::class)
    private fun upsertMeta(
        threadKey: ThreadKey,
        time: Long,
        meta: Meta,
    ) {
        check(database.inTransaction())
        val filter = threadKey.filterMeta()!!.build()
        val values = ContentValues()
        values.put(Schema.Meta.Columns.Companion.TIME, time)
        val flags =
            (if (meta.deleted) Schema.Meta.Flags.Companion.DELETED else 0) or (if (meta.error) Schema.Meta.Flags.Companion.ERROR else 0)
        values.put(Schema.Meta.Columns.Companion.FLAGS, flags)
        writer().use { writer ->
            meta.serialize(writer)
            values.put(Schema.Meta.Columns.Companion.DATA, writer.build())
        }
        if (database.update(
                Schema.Meta.Companion.TABLE_NAME,
                values,
                filter.value,
                filter.args,
            ) <= 0
        ) {
            values.put(Schema.Meta.Columns.Companion.CHAN_NAME, threadKey.chanName)
            values.put(Schema.Meta.Columns.Companion.BOARD_NAME, threadKey.boardName)
            values.put(Schema.Meta.Columns.Companion.THREAD_NUMBER, threadKey.threadNumber)
            database.insert(Schema.Meta.Companion.TABLE_NAME, null, values)
        }
    }

    private val insertLocks = KeyLock<ThreadKey?>()

    @Throws(IOException::class)
    fun insertNewPosts(
        threadKey: ThreadKey,
        posts: List<Post>,
        meta: Meta,
        temporary: Boolean,
        newThread: Boolean,
        partial: Boolean,
    ): InsertResult? {
        val dataArray: Array<ByteArray?> = arrayOfNulls(posts.size)
        for (i in posts.indices) {
            writer().use { writer ->
                posts[i].serialize(writer)
                dataArray[i] = writer.build()
            }
        }
        val serializedMap = HashMap<PostNumber?, Serialized>(dataArray.size)
        val hasher = getInstanceSha256()
        for (i in dataArray.indices) {
            val post = posts[i]
            val data = dataArray[i]!!
            val hash = hasher.calculate(data)
            serializedMap[post.number] = Serialized(post, data, hash, newThread)
        }
        val userPosts: MutableSet<PostNumber> =
            CommonDatabase.Companion
                .getInstance()
                .posts
                .getFlags(threadKey.chanName, threadKey.boardName, threadKey.threadNumber)
                .userPosts!!
        return insertLocks.lock<InsertResult?, IOException?>(
            threadKey,
            KeyLock.Callback {
                insertNewPostsLocked(
                    threadKey,
                    meta,
                    temporary,
                    newThread,
                    partial,
                    serializedMap,
                    userPosts,
                )
            },
        )
    }

    @Throws(IOException::class)
    private fun insertNewPostsLocked(
        threadKey: ThreadKey,
        meta: Meta,
        temporary: Boolean,
        newThread: Boolean,
        partial: Boolean,
        serializedMap: HashMap<PostNumber?, Serialized>,
        userPosts: MutableSet<PostNumber>,
    ): InsertResult {
        var deleted: LongSparseArray<Unit?>? = null
        var restored: LongSparseArray<Unit?>? = null
        var newCount = 0
        val projection =
            arrayOf<String?>(
                "rowid",
                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
                Schema.Posts.Columns.Companion.FLAGS,
                Schema.Posts.Columns.Companion.HASH,
            )
        val filter = threadKey.filterPosts()!!.build()
        database
            .query(
                Schema.Posts.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val postNumber = PostNumber(cursor.getInt(1), cursor.getInt(2))
                    var flags = cursor.getInt(3)
                    val hash = cursor.getBlob(4)
                    var serialized = serializedMap[postNumber]
                    if (serialized != null) {
                        if (serialized.hash.contentEquals(hash)) {
                            serializedMap.remove(postNumber)
                            serialized = null
                            if (get(flags, Schema.Posts.Flags.Companion.DELETED)) {
                                if (restored == null) {
                                    restored = LongSparseArray<Unit?>()
                                }
                                restored.put(id, null)
                            }
                        } else {
                            flags =
                                set(
                                    flags,
                                    Schema.Posts.Flags.Companion.DELETED or
                                        Schema.Posts.Flags.Companion.MARK_DELETED,
                                    false,
                                )
                            flags = set(flags, Schema.Posts.Flags.Companion.MARK_EDITED, true)
                            serialized.flags = flags
                        }
                    } else if (!partial && !get(flags, Schema.Posts.Flags.Companion.DELETED)) {
                        if (deleted == null) {
                            deleted = LongSparseArray<Unit?>()
                        }
                        deleted.put(id, null)
                    }
                    if (serialized == null && get(flags, Schema.Posts.Flags.Companion.MARK_NEW)) {
                        newCount++
                    }
                }
            }
        for (serialized in serializedMap.values) {
            if (get(serialized.flags, Schema.Posts.Flags.Companion.MARK_NEW)) {
                newCount++
            }
        }

        val replies = ArrayList<Reply>()
        database.beginTransaction()
        try {
            upsertMeta(threadKey, if (temporary) 0 else System.currentTimeMillis(), meta)
            if (deleted != null) {
                updateFlags(
                    threadKey,
                    Expression.LongIterator.Companion.create(deleted),
                    "| " +
                        (Schema.Posts.Flags.Companion.DELETED or Schema.Posts.Flags.Companion.MARK_DELETED),
                )
            }
            if (restored != null) {
                updateFlags(
                    threadKey,
                    Expression.LongIterator.Companion.create(restored),
                    "& " +
                        (Schema.Posts.Flags.Companion.DELETED or Schema.Posts.Flags.Companion.MARK_DELETED).inv() + " | " +
                        Schema.Posts.Flags.Companion.MARK_EDITED,
                )
            }
            if (!serializedMap.isEmpty()) {
                val iterator = serializedMap.values.iterator()
                val referencesTo = if (userPosts.isEmpty()) null else HashSet<PostNumber>()
                Expression.batchInsert(
                    serializedMap.size,
                    10,
                    8,
                    CreateBatchInsertStatement { values: String? ->
                        database.compileStatement(
                            "INSERT OR REPLACE " +
                                "INTO " + Schema.Posts.Companion.TABLE_NAME + " (" +
                                Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                                Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                                Schema.Posts.Columns.Companion.THREAD_NUMBER + ", " +
                                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + ", " +
                                Schema.Posts.Columns.Companion.FLAGS + ", " +
                                Schema.Posts.Columns.Companion.DATA + ", " +
                                Schema.Posts.Columns.Companion.HASH + ") " +
                                "VALUES " + values,
                        )
                    },
                    BindBatchInsertArgs { statement: SQLiteStatement?, start: Int ->
                        val serialized = iterator.next()
                        var flags = serialized.flags
                        if (referencesTo != null &&
                            get(
                                flags,
                                Schema.Posts.Flags.Companion.MARK_NEW,
                            )
                        ) {
                            referencesTo.clear()
                            PostItem.collectReferences(referencesTo, serialized.post.comment)
                            for (reference in referencesTo) {
                                if (userPosts.contains(reference)) {
                                    flags = flags or Schema.Posts.Flags.Companion.MARK_REPLY
                                    replies.add(
                                        Reply(
                                            serialized.post.number,
                                            serialized.post.comment,
                                            serialized.post.timestamp,
                                        ),
                                    )
                                    break
                                }
                            }
                        }
                        statement!!.bindString(start + 1, threadKey.chanName)
                        statement.bindString(start + 2, threadKey.boardName)
                        statement.bindString(start + 3, threadKey.threadNumber)
                        statement.bindLong(
                            start + 4,
                            serialized.post.number.major
                                .toLong(),
                        )
                        statement.bindLong(
                            start + 5,
                            serialized.post.number.minor
                                .toLong(),
                        )
                        statement.bindLong(start + 6, flags.toLong())
                        statement.bindBlob(start + 7, serialized.data)
                        statement.bindBlob(start + 8, serialized.hash)
                    },
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        val state = Cache.State(UUID.randomUUID(), newThread)
        synchronized(cacheStates) {
            cacheStates[threadKey] = state
        }
        return InsertResult(state, replies, newCount)
    }

    private val collectLocks = KeyLock<ThreadKey?>()

    @Throws(ParseException::class, OperationCanceledException::class)
    fun collectDiffPosts(
        threadKey: ThreadKey,
        cache: Cache?,
        cleanup: Cleanup,
        signal: CancellationSignal?,
    ): Diff? {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        Objects.requireNonNull<Cleanup?>(cleanup)
        val diff =
            collectLocks.lock<Diff, ParseException?>(
                threadKey,
                KeyLock.Callback { collectDiffPostsLocked(threadKey, cache, cleanup, signal) },
            )
        if (cache == null &&
            diff!!.cache.isEmpty &&
            migratePosts(
                threadKey,
                MigrationRequest.COLLECT_DIFF_POSTS,
            )
        ) {
            return collectDiffPosts(threadKey, cache, cleanup, signal)
        }
        return diff
    }

    @Throws(ParseException::class, OperationCanceledException::class)
    private fun collectDiffPostsLocked(
        threadKey: ThreadKey,
        cache: Cache?,
        cleanup: Cleanup,
        signal: CancellationSignal?,
    ): Diff {
        when (cleanup) {
            Cleanup.NONE -> {}

            Cleanup.ERASE -> {
                val filter = threadKey.filterMeta()!!.build()
                database.delete(Schema.Meta.Companion.TABLE_NAME, filter.value, filter.args)
            }

            Cleanup.OLD -> {
                if (cache != null && !cache.diffItems.isEmpty() && cache.originalPostNumber != null) {
                    var firstExistingPostNumber: PostNumber? = null
                    for (entry in cache.diffItems.entries) {
                        if (!entry.value.deleted) {
                            val postNumber: PostNumber = entry.key
                            if (!postNumber.equals(cache.originalPostNumber) &&
                                (
                                    firstExistingPostNumber == null ||
                                        postNumber.compareTo(firstExistingPostNumber) < 0
                                )
                            ) {
                                firstExistingPostNumber = postNumber
                            }
                        }
                    }
                    if (firstExistingPostNumber != null) {
                        val filter =
                            threadKey
                                .filterPosts()!!
                                .append(
                                    Expression
                                        .filterOr()
                                        .raw(
                                            Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " < " +
                                                firstExistingPostNumber.major,
                                        ).append(
                                            Expression
                                                .filter()
                                                .raw(
                                                    Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " = " +
                                                        firstExistingPostNumber.major,
                                                ).raw(
                                                    Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + " < " +
                                                        firstExistingPostNumber.minor,
                                                ),
                                        ),
                                ).raw(Schema.Posts.Columns.Companion.FLAGS + " & " + Schema.Posts.Flags.Companion.DELETED)
                                .build()
                        database.delete(
                            Schema.Posts.Companion.TABLE_NAME,
                            filter.value,
                            filter.args,
                        )
                    }
                }
            }

            Cleanup.DELETED -> {
                val filter =
                    threadKey
                        .filterPosts()!!
                        .raw(Schema.Posts.Columns.Companion.FLAGS + " & " + Schema.Posts.Flags.Companion.DELETED)
                        .build()
                database.delete(Schema.Posts.Companion.TABLE_NAME, filter.value, filter.args)
            }
        }

        var extractedList: MutableList<Extracted>? = null
        var newItems: MutableMap<PostNumber, DiffItem>? = null
        var originalPostNumber = if (cache != null) cache.originalPostNumber else null
        val oldItems: MutableMap<PostNumber, DiffItem> =
            if (cache != null) cache.diffItems else mutableMapOf()
        var existing: ArrayList<PostNumber>? = null
        var newPosts: MutableMap<PostNumber, Long>? = null
        var deletedPosts: MutableMap<PostNumber, Long>? = null
        var editedPosts: MutableMap<PostNumber, Long>? = null
        var replyPosts: MutableMap<PostNumber, Long>? = null
        val state = getCacheState(threadKey)

        val projection =
            arrayOf<String?>(
                "rowid",
                Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
                Schema.Posts.Columns.Companion.FLAGS,
                Schema.Posts.Columns.Companion.DATA,
                Schema.Posts.Columns.Companion.HASH,
            )
        val filter = threadKey.filterPosts()!!.build()
        database
            .query(
                false,
                Schema.Posts.Companion.TABLE_NAME,
                projection,
                filter.value,
                filter.args,
                null,
                null,
                null,
                null,
                signal,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val postNumber = PostNumber(cursor.getInt(1), cursor.getInt(2))
                    if (existing == null) {
                        existing = ArrayList(cursor.getCount())
                    }
                    existing.add(postNumber)
                    val flags = cursor.getInt(3)
                    if (get(flags, Schema.Posts.Flags.Companion.MARK_NEW)) {
                        if (newPosts == null) {
                            newPosts = HashMap()
                        }
                        newPosts[postNumber] = id
                    }
                    if (get(flags, Schema.Posts.Flags.Companion.MARK_DELETED)) {
                        if (deletedPosts == null) {
                            deletedPosts = HashMap()
                        }
                        deletedPosts[postNumber] = id
                    }
                    if (get(flags, Schema.Posts.Flags.Companion.MARK_EDITED)) {
                        if (editedPosts == null) {
                            editedPosts = HashMap()
                        }
                        editedPosts[postNumber] = id
                    }
                    if (get(flags, Schema.Posts.Flags.Companion.MARK_REPLY)) {
                        if (replyPosts == null) {
                            replyPosts = HashMap()
                        }
                        replyPosts[postNumber] = id
                    }
                    val deleted = get(flags, Schema.Posts.Flags.Companion.DELETED)
                    val hash = cursor.getBlob(5)
                    val oldItem = oldItems[postNumber]
                    if (oldItem == null ||
                        oldItem.deleted != deleted ||
                        !oldItem.hash.contentEquals(
                            hash,
                        )
                    ) {
                        if (originalPostNumber == null || postNumber.compareTo(originalPostNumber) < 0) {
                            originalPostNumber = postNumber
                        }
                        if (extractedList == null) {
                            extractedList = ArrayList<Extracted>()
                        }
                        if (newItems == null) {
                            newItems = HashMap(oldItems)
                        }
                        extractedList.add(Extracted(cursor.getBlob(4), postNumber, deleted))
                        newItems[postNumber] = DiffItem(hash, deleted)
                    }
                }
            }
        var changed: MutableList<Post>? = null
        if (extractedList != null) {
            @Suppress("UNCHECKED_CAST")
            val unsafeChanged = extractedList as MutableList<*> as MutableList<Post>
            for (i in extractedList.indices) {
                val extracted = extractedList[i]
                val post: Post?
                try {
                    reader(extracted.data).use { reader ->
                        post = deserialize(extracted.postNumber, extracted.deleted, reader)
                    }
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
                unsafeChanged[i] = post!!
            }
            changed = unsafeChanged
        }
        var removed: MutableCollection<PostNumber> = mutableListOf()
        if (existing != null) {
            val cacheSize = (if (newItems != null) newItems else oldItems).size
            if (cacheSize > existing.size) {
                existing.sort()
                removed = ArrayList(cacheSize - existing.size)
                if (newItems == null) {
                    newItems = HashMap(oldItems)
                }
                val iterator: MutableIterator<PostNumber> = newItems!!.keys.iterator()
                while (iterator.hasNext()) {
                    val postNumber = iterator.next()
                    if (Collections.binarySearch(existing, postNumber) < 0) {
                        iterator.remove()
                        removed.add(postNumber)
                    }
                }
            }
        } else if (!oldItems.isEmpty()) {
            newItems = mutableMapOf()
            removed = oldItems.keys
        }

        if (newPosts != null &&
            !newPosts.isEmpty() ||
            deletedPosts != null &&
            !deletedPosts.isEmpty() ||
            editedPosts != null &&
            !editedPosts.isEmpty() ||
            replyPosts != null &&
            !replyPosts.isEmpty()
        ) {
            database.beginTransaction()
            try {
                if (newPosts != null && !newPosts.isEmpty()) {
                    updateFlags(
                        threadKey,
                        Expression.LongIterator.Companion.create(newPosts.values.iterator()),
                        "& " +
                            Schema.Posts.Flags.Companion.MARK_NEW
                                .inv(),
                    )
                }
                if (deletedPosts != null && !deletedPosts.isEmpty()) {
                    updateFlags(
                        threadKey,
                        Expression.LongIterator.Companion.create(deletedPosts.values.iterator()),
                        "& " +
                            Schema.Posts.Flags.Companion.MARK_DELETED
                                .inv(),
                    )
                }
                if (editedPosts != null && !editedPosts.isEmpty()) {
                    updateFlags(
                        threadKey,
                        Expression.LongIterator.Companion.create(editedPosts.values.iterator()),
                        "& " +
                            Schema.Posts.Flags.Companion.MARK_EDITED
                                .inv(),
                    )
                }
                if (replyPosts != null && !replyPosts.isEmpty()) {
                    updateFlags(
                        threadKey,
                        Expression.LongIterator.Companion.create(replyPosts.values.iterator()),
                        "& " +
                            Schema.Posts.Flags.Companion.MARK_REPLY
                                .inv(),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

        val newCache =
            PagesDatabase.Cache(
                if (newItems != null) newItems else oldItems,
                originalPostNumber,
                state,
            )
        return Diff(
            newCache,
            if (changed != null) changed else mutableListOf(),
            removed,
            if (newPosts != null) newPosts.keys else mutableSetOf(),
            if (deletedPosts != null) deletedPosts.keys else mutableSetOf(),
            if (editedPosts != null) editedPosts.keys else mutableSetOf(),
            if (replyPosts != null) replyPosts.keys else mutableSetOf(),
        )
    }

    @Suppress("unused")
    private class Legacy {
        class HttpValidator : Serializable {
            var eTag: String? = null
            var lastModified: String? = null

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        class Posts : Serializable {
            var mPosts: Array<Post>? = null
            var mHttpValidator: HttpValidator? = null
            var mArchivedThreadUriString: String? = null
            var mUniquePosters: Int = 0
            var mPostsCount: Int = 0
            var mFilesCount: Int = 0
            var mPostsWithFilesCount: Int = 0
            var mLocalAutohide: Array<Array<String?>?>? = null
            var mAutoRefreshEnabled: Boolean = false
            var mAutoRefreshInterval: Int = 0

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        class Post : Serializable {
            interface Flags {
                companion object {
                    const val SAGE: Int = 0x00000001
                    const val STICKY: Int = 0x00000002
                    const val CLOSED: Int = 0x00000004
                    const val ARCHIVED: Int = 0x00000008
                    const val CYCLICAL: Int = 0x00000010
                    const val POSTER_WARNED: Int = 0x00000020
                    const val POSTER_BANNED: Int = 0x00000040
                    const val ORIGINAL_POSTER: Int = 0x00000080
                    const val DEFAULT_NAME: Int = 0x00000100
                    const val BUMP_LIMIT_REACHED: Int = 0x00000200
                }
            }

            interface InternalFlags {
                companion object {
                    const val HIDDEN: Int = 0x00010000
                    const val SHOWN: Int = 0x00020000
                    const val DELETED: Int = 0x00040000
                    const val USER_POST: Int = 0x00080000
                }
            }

            var mFlags: Int = 0
            var mThreadNumber: String? = null
            var mParentPostNumber: String? = null
            var mPostNumber: String? = null
            var mTimestamp: Long = 0
            var mSubject: String? = null
            var mComment: String? = null
            var mEditedComment: String? = null
            var mCommentMarkup: String? = null
            var mName: String? = null
            var mIdentifier: String? = null
            var mTripcode: String? = null
            var mCapcode: String? = null
            var mEmail: String? = null
            var mAttachments: Array<Any?>? = null
            var mIcons: Array<Icon?>? = null

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        class FileAttachment : Serializable {
            var mFileUriString: String? = null
            var mThumbnailUriString: String? = null
            var mOriginalName: String? = null
            var mSize: Int = 0
            var mWidth: Int = 0
            var mHeight: Int = 0
            var mSpoiler: Boolean = false

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        class EmbeddedAttachment : Serializable {
            enum class ContentType {
                AUDIO,
                VIDEO,
            }

            var mFileUriString: String? = null
            var mThumbnailUriString: String? = null
            var mEmbeddedType: String? = null
            var mContentType: ContentType? = null
            var mCanDownload: Boolean = false
            var mForcedName: String? = null
            var mTitle: String? = null

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        class Icon : Serializable {
            var mUriString: String? = null
            var mTitle: String? = null

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }
    }

    private class LegacyObjectInputStream(
        `in`: InputStream?,
    ) : ObjectInputStream(`in`) {
        @Throws(ClassNotFoundException::class, IOException::class)
        override fun readClassDescriptor(): ObjectStreamClass? {
            var objectStreamClass = super.readClassDescriptor()
            if (objectStreamClass != null) {
                val newClass = TRANSFORM[objectStreamClass.getName()]
                if (newClass != null) {
                    objectStreamClass = ObjectStreamClass.lookup(newClass)
                }
            }
            return objectStreamClass
        }

        companion object {
            private val TRANSFORM: MutableMap<String?, Class<*>?>

            init {
                TRANSFORM = HashMap<String?, Class<*>?>()
                TRANSFORM["HttpValidator"] = Legacy.HttpValidator::class.java
                TRANSFORM["chan.content.model.Posts"] = Legacy.Posts::class.java
                TRANSFORM["chan.content.model.Post"] = Legacy.Post::class.java
                TRANSFORM["[Lchan.content.model.Post;"] = Array<Legacy.Post>::class.java
                TRANSFORM["chan.content.model.Attachment"] = Any::class.java
                TRANSFORM["[Lchan.content.model.Attachment;"] = Array<Any>::class.java
                TRANSFORM["chan.content.model.FileAttachment"] =
                    Legacy.FileAttachment::class.java
                TRANSFORM["[Lchan.content.model.FileAttachment;"] =
                    Array<Legacy.FileAttachment>::class.java
                TRANSFORM["chan.content.model.EmbeddedAttachment"] =
                    Legacy.EmbeddedAttachment::class.java
                TRANSFORM["[Lchan.content.model.EmbeddedAttachment;"] =
                    Array<Legacy.EmbeddedAttachment>::class.java
                TRANSFORM["chan.content.model.EmbeddedAttachment\$ContentType"] =
                    Legacy.EmbeddedAttachment.ContentType::class.java
                TRANSFORM["chan.content.model.Icon"] = Legacy.Icon::class.java
                TRANSFORM["[Lchan.content.model.Icon;"] = Array<Legacy.Icon>::class.java
            }
        }
    }

    private val legacyCacheDirectory: File?
        get() {
            var directory = MainApplication.getInstance().getExternalCacheDir()
            directory = if (directory != null) File(directory, "pages") else null
            return if (directory != null && directory.isDirectory()) directory else null
        }

    private val migrateLocks = KeyLock<ThreadKey?>()
    private val migrated: HashMap<ThreadKey?, MutableSet<MigrationRequest?>?> =
        HashMap<ThreadKey?, MutableSet<MigrationRequest?>?>()

    init {
        val directory = this.legacyCacheDirectory
        if (directory != null) {
            val forceMigrate = File(directory, "migrate")
            if (forceMigrate.exists()) {
                forceMigrate.delete()
                val files = directory.list()
                for (name in files!!) {
                    var entryName = name
                    if (entryName.startsWith("posts_")) {
                        entryName = entryName.substring(6)
                        val index1 = entryName.indexOf('_')
                        val index2 = entryName.lastIndexOf('_')
                        if (index2 > index1 && index1 >= 0) {
                            val chanName = entryName.substring(0, index1)
                            var boardName: String? = entryName.substring(index1 + 1, index2)
                            if ("null" == boardName) {
                                boardName = null
                            }
                            val threadNumber = entryName.substring(index2 + 1)
                            migratePosts(ThreadKey(chanName, boardName, threadNumber), null)
                        }
                    }
                }
            }
        }

        if (MainApplication.getInstance().isMainProcess()) {
            val excludeThreads = HashSet<ThreadKey?>()
            for (favoriteItem in FavoritesStorage.getInstance().getThreads(null)) {
                excludeThreads.add(
                    PagesDatabase.ThreadKey(
                        favoriteItem.chanName,
                        emptyIfNull(favoriteItem.boardName),
                        favoriteItem.threadNumber!!,
                    ),
                )
            }
            Thread(Runnable { cleanup(excludeThreads, false) }).start()
        }
    }

    private fun migratePosts(
        threadKey: ThreadKey,
        request: MigrationRequest?,
    ): Boolean {
        Objects.requireNonNull<ThreadKey?>(threadKey)
        synchronized(migrated) {
            val requests = migrated[threadKey]
            if (requests != null && requests.isEmpty()) {
                return false
            }
        }
        return migrateLocks.lock<Boolean, RuntimeException?>(
            threadKey,
            KeyLock.Callback {
                synchronized(migrated) {
                    val requests = migrated[threadKey]
                    if (requests != null) {
                        if (request != null && requests.contains(request)) {
                            if (requests.size == 1) {
                                migrated[threadKey] = mutableSetOf<MigrationRequest?>()
                            } else {
                                val newRequests: HashSet<MigrationRequest?> =
                                    HashSet<MigrationRequest?>(requests)
                                newRequests.remove(request)
                                migrated[threadKey] =
                                    Collections.unmodifiableSet<MigrationRequest?>(newRequests)
                            }
                            return@Callback true
                        } else {
                            return@Callback false
                        }
                    }
                }
                val success = migratePostsLocked(threadKey)
                synchronized(migrated) {
                    if (success && request != null) {
                        val newRequests: HashSet<MigrationRequest?> =
                            HashSet<MigrationRequest?>(Arrays.asList<MigrationRequest?>(*MigrationRequest.entries.toTypedArray()))
                        newRequests.remove(request)
                        migrated[threadKey] =
                            Collections.unmodifiableSet<MigrationRequest?>(newRequests)
                    } else {
                        migrated[threadKey] = mutableSetOf<MigrationRequest?>()
                    }
                }
                success
            },
        )!!
    }

    private fun getPostsFile(
        directory: File?,
        chanName: String,
        boardName: String?,
        threadNumber: String?,
    ): File? {
        val fileName = "posts_" + chanName + "_" + boardName + "_" + threadNumber
        val postsFile = File(directory, fileName)
        val tempFile = File(directory, "temp_" + fileName)
        if (tempFile.exists()) {
            if ((!postsFile.exists() || !postsFile.delete()) && !tempFile.renameTo(postsFile)) {
                return null
            }
        }
        return if (postsFile.exists()) postsFile else null
    }

    private fun migratePostsLocked(threadKey: ThreadKey): Boolean {
        val directory = this.legacyCacheDirectory
        if (directory == null) {
            return false
        }
        var postsFile =
            getPostsFile(directory, threadKey.chanName, threadKey.boardName, threadKey.threadNumber)
        if (postsFile == null) {
            postsFile =
                getPostsFile(
                    directory,
                    threadKey.chanName,
                    nullIfEmpty(threadKey.boardName),
                    threadKey.threadNumber,
                )
        }
        if (postsFile == null) {
            return false
        }

        var legacyPosts: Legacy.Posts?
        val time = postsFile.lastModified()
        try {
            LegacyObjectInputStream(FileInputStream(postsFile)).use { input ->
                legacyPosts = input.readObject() as Legacy.Posts?
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            postsFile.delete()
        }
        if (legacyPosts == null || legacyPosts.mPosts == null || legacyPosts.mPosts!!.size == 0) {
            return false
        }

        val validator =
            if (legacyPosts.mHttpValidator != null) {
                HttpValidator(
                    legacyPosts.mHttpValidator!!.eTag,
                    legacyPosts.mHttpValidator!!.lastModified,
                )
            } else {
                null
            }
        val archivedThreadUri =
            if (legacyPosts.mArchivedThreadUriString != null) {
                Uri.parse(legacyPosts.mArchivedThreadUriString)
            } else {
                null
            }
        val meta = Meta(validator, archivedThreadUri, legacyPosts.mUniquePosters, false, false)
        val posts = ArrayList<Post>(legacyPosts.mPosts!!.size)
        val flags = HashMap<PostNumber?, Pair<HideState?, Boolean?>?>()
        for (legacyPost in legacyPosts.mPosts) {
            val builder = Post.Builder()
            try {
                builder.number = parseOrThrow(legacyPost.mPostNumber)
            } catch (e: Exception) {
                if (posts.isEmpty()) {
                    return false
                }
                continue
            }
            builder.isSage = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.SAGE)
            builder.isSticky = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.STICKY)
            builder.isClosed = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.CLOSED)
            builder.isArchived = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.ARCHIVED)
            builder.isCyclical = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.CYCLICAL)
            builder.isPosterWarned =
                get(legacyPost.mFlags, Legacy.Post.Flags.Companion.POSTER_WARNED)
            builder.isPosterBanned =
                get(legacyPost.mFlags, Legacy.Post.Flags.Companion.POSTER_BANNED)
            builder.isOriginalPoster =
                get(legacyPost.mFlags, Legacy.Post.Flags.Companion.ORIGINAL_POSTER)
            builder.isDefaultName = get(legacyPost.mFlags, Legacy.Post.Flags.Companion.DEFAULT_NAME)
            builder.isBumpLimitReached =
                get(legacyPost.mFlags, Legacy.Post.Flags.Companion.BUMP_LIMIT_REACHED)
            val hidden = get(legacyPost.mFlags, InternalFlags.Companion.HIDDEN)
            val shown = get(legacyPost.mFlags, InternalFlags.Companion.SHOWN)
            val deleted = get(legacyPost.mFlags, InternalFlags.Companion.DELETED)
            val userPost = get(legacyPost.mFlags, InternalFlags.Companion.USER_POST)
            if (hidden || shown || userPost) {
                val hideState =
                    if (hidden) {
                        HideState.HIDDEN
                    } else {
                        if (shown) {
                            HideState.SHOWN
                        } else {
                            HideState.UNDEFINED
                        }
                    }
                flags[builder.number] = Pair<HideState?, Boolean?>(hideState, userPost)
            }
            builder.timestamp = legacyPost.mTimestamp
            builder.subject = legacyPost.mSubject
            builder.comment = legacyPost.mComment
            builder.commentMarkup = legacyPost.mCommentMarkup
            builder.name = legacyPost.mName
            builder.identifier = legacyPost.mIdentifier
            builder.tripcode = legacyPost.mTripcode
            builder.capcode = legacyPost.mCapcode
            builder.email = legacyPost.mEmail
            if (legacyPost.mAttachments != null && legacyPost.mAttachments!!.size > 0) {
                builder.attachments = ArrayList<Post.Attachment>(legacyPost.mAttachments!!.size)
                for (legacyAttachment in legacyPost.mAttachments) {
                    if (legacyAttachment is Legacy.FileAttachment) {
                        val legacyFile = legacyAttachment
                        val fileUri =
                            if (isEmpty(legacyFile.mFileUriString)) {
                                null
                            } else {
                                Uri.parse(legacyFile.mFileUriString)
                            }
                        val thumbnailUri =
                            if (isEmpty(legacyFile.mThumbnailUriString)) {
                                null
                            } else {
                                Uri.parse(legacyFile.mThumbnailUriString)
                            }
                        val file =
                            createExternal(
                                fileUri,
                                thumbnailUri,
                                legacyFile.mOriginalName,
                                legacyFile.mSize,
                                legacyFile.mWidth,
                                legacyFile.mHeight,
                                legacyFile.mSpoiler,
                            )
                        if (file != null) {
                            builder.attachments!!.add(file)
                        }
                    } else if (legacyAttachment is Legacy.EmbeddedAttachment) {
                        val legacyEmbedded = legacyAttachment
                        val fileUri =
                            if (isEmpty(legacyEmbedded.mFileUriString)) {
                                null
                            } else {
                                Uri.parse(legacyEmbedded.mFileUriString)
                            }
                        val thumbnailUri =
                            if (isEmpty(legacyEmbedded.mThumbnailUriString)) {
                                null
                            } else {
                                Uri.parse(legacyEmbedded.mThumbnailUriString)
                            }
                        val contentType: Embedded.ContentType?
                        when (legacyEmbedded.mContentType) {
                            Legacy.EmbeddedAttachment.ContentType.AUDIO -> {
                                contentType = Embedded.ContentType.AUDIO
                            }

                            Legacy.EmbeddedAttachment.ContentType.VIDEO -> {
                                contentType = Embedded.ContentType.VIDEO
                            }

                            else -> {
                                contentType = null
                            }
                        }
                        val embedded =
                            createExternal(
                                false,
                                fileUri,
                                thumbnailUri,
                                legacyEmbedded.mEmbeddedType,
                                contentType,
                                legacyEmbedded.mCanDownload,
                                legacyEmbedded.mForcedName,
                            )
                        if (embedded != null) {
                            builder.attachments!!.add(embedded)
                        }
                    }
                }
            }
            if (legacyPost.mIcons != null && legacyPost.mIcons!!.size > 0) {
                builder.icons = ArrayList<Post.Icon>(legacyPost.mIcons!!.size)
                for (legacyIcon in legacyPost.mIcons) {
                    if (legacyIcon != null) {
                        val uri =
                            if (isEmpty(legacyIcon.mUriString)) {
                                null
                            } else {
                                Uri.parse(legacyIcon.mUriString)
                            }
                        val icon = createExternal(uri, legacyIcon.mTitle)
                        if (icon != null) {
                            builder.icons!!.add(icon)
                        }
                    }
                }
            }
            posts.add(builder.build(deleted))
        }

        val data = arrayOfNulls<ByteArray>(posts.size)
        for (i in posts.indices) {
            try {
                writer().use { writer ->
                    posts[i].serialize(writer)
                    data[i] = writer.build()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            }
        }
        if (legacyPosts.mLocalAutohide != null) {
            val hidePerformer = HidePerformer(null)
            hidePerformer.decodeLocalFiltersLegacy(legacyPosts.mLocalAutohide)
            if (hidePerformer.hasLocalFilters()) {
                var extra: ByteArray? = null
                try {
                    writer().use { writer ->
                        writer.startObject()
                        writer.name("filters")
                        hidePerformer.encodeLocalFilters(writer)
                        writer.endObject()
                        extra = writer.build()
                    }
                } catch (e: IOException) {
                    // Ignore exception
                }
                if (extra != null) {
                    CommonDatabase.Companion.getInstance().threads.setStateExtra(
                        false,
                        threadKey.chanName,
                        threadKey.boardName,
                        threadKey.threadNumber,
                        false,
                        null,
                        true,
                        extra,
                    )
                }
            }
        }
        if (!flags.isEmpty()) {
            CommonDatabase.Companion.getInstance().posts.setFlagsMigration(
                threadKey.chanName,
                threadKey.boardName,
                threadKey.threadNumber,
                flags,
                time,
            )
        }
        database.beginTransaction()
        try {
            try {
                upsertMeta(threadKey, time, meta)
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            }
            val index = intArrayOf(0)
            val hasher = getInstanceSha256()
            Expression.batchInsert(
                posts.size,
                10,
                8,
                CreateBatchInsertStatement { values: String? ->
                    database.compileStatement(
                        "INSERT OR REPLACE " +
                            "INTO " + Schema.Posts.Companion.TABLE_NAME + " (" +
                            Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                            Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                            Schema.Posts.Columns.Companion.THREAD_NUMBER + ", " +
                            Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                            Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + ", " +
                            Schema.Posts.Columns.Companion.FLAGS + ", " +
                            Schema.Posts.Columns.Companion.DATA + ", " +
                            Schema.Posts.Columns.Companion.HASH + ") " +
                            "VALUES " + values,
                    )
                },
                BindBatchInsertArgs { statement: SQLiteStatement?, start: Int ->
                    val i = index[0]
                    val post = posts[i]
                    statement!!.bindString(start + 1, threadKey.chanName)
                    statement.bindString(start + 2, threadKey.boardName)
                    statement.bindString(start + 3, threadKey.threadNumber)
                    statement.bindLong(start + 4, post.number.major.toLong())
                    statement.bindLong(start + 5, post.number.minor.toLong())
                    statement.bindLong(
                        start + 6,
                        (if (post.deleted) Schema.Posts.Flags.Companion.DELETED else 0).toLong(),
                    )
                    statement.bindBlob(start + 7, data[i])
                    statement.bindBlob(start + 8, hasher.calculate(data[i]!!))
                    index[0]++
                },
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return true
    }

    companion object {
        private val INSTANCE = PagesDatabase()

        @JvmStatic
        fun getInstance(): PagesDatabase = INSTANCE

        private fun orderByPostNumber(desc: Boolean): String {
            val order = (if (desc) "DESC" else "ASC")
            return Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " " + order + ", " +
                Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + " " + order
        }
    }
}
