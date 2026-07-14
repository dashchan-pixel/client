package com.mishiranu.dashchan.content.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Pair
import chan.util.StringUtils.emptyIfNull
import com.mishiranu.dashchan.content.database.CommonDatabase.ExecuteCallback
import com.mishiranu.dashchan.content.database.CommonDatabase.Migration
import com.mishiranu.dashchan.content.database.CommonDatabase.QueryCallback
import com.mishiranu.dashchan.content.model.PostItem.HideState
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.util.FlagUtils.get
import java.util.Objects

class PostsDatabase internal constructor(private val database: CommonDatabase) :
    CommonDatabase.Instance {
    private interface Schema {
        interface Posts {
            interface Columns {
                companion object {
                    const val CHAN_NAME: String = "chan_name"
                    const val BOARD_NAME: String = "board_name"
                    const val THREAD_NUMBER: String = "thread_number"
                    const val POST_NUMBER_MAJOR: String = "post_number_major"
                    const val POST_NUMBER_MINOR: String = "post_number_minor"
                    const val TIME: String = "time"
                    const val FLAGS: String = "flags"
                }
            }

            interface Flags {
                companion object {
                    const val HIDDEN: Int = 0x00000001
                    const val SHOWN: Int = 0x00000002
                    const val USER: Int = 0x00000004
                }
            }

            companion object {
                const val TABLE_NAME: String = "posts"
                const val MAX_COUNT: Int = 10000
                const val MAX_COUNT_FACTOR: Float = 0.75f
            }
        }
    }

    class Flags(@JvmField val hiddenPosts: HideState.Map<PostNumber?>?, @JvmField val userPosts: HashSet<PostNumber>?)

    override fun create(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE " + Schema.Posts.Companion.TABLE_NAME + " (" +
                    Schema.Posts.Columns.Companion.CHAN_NAME + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.BOARD_NAME + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.THREAD_NUMBER + " TEXT NOT NULL, " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " INTEGER NOT NULL, " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + " INTEGER NOT NULL, " +
                    Schema.Posts.Columns.Companion.TIME + " INTEGER NOT NULL, " +
                    Schema.Posts.Columns.Companion.FLAGS + " INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (" + Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                    Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                    Schema.Posts.Columns.Companion.THREAD_NUMBER + ", " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                    Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + "))"
        )
    }

    override fun upgrade(database: SQLiteDatabase, migration: Migration) {
        when (migration) {
            Migration.FROM_8_TO_9 -> {
                // Add "posts" table
                database.execSQL(
                    "CREATE TABLE posts (chan_name TEXT NOT NULL, " +
                            "board_name TEXT NOT NULL, thread_number TEXT NOT NULL, " +
                            "post_number_major INTEGER NOT NULL, post_number_minor INTEGER NOT NULL, " +
                            "time INTEGER NOT NULL, flags INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (chan_name, board_name, thread_number, " +
                            "post_number_major, post_number_minor))"
                )
            }
        }
    }

    override fun open(database: SQLiteDatabase) {
        val clean: Boolean
        database.rawQuery("SELECT COUNT(*) FROM " + Schema.Posts.Companion.TABLE_NAME, null)
            .use { cursor ->
                clean = cursor.moveToFirst() && cursor.getInt(0) > Schema.Posts.Companion.MAX_COUNT
            }
        if (clean) {
            val time: Long?
            val projection = arrayOf<String?>(Schema.Posts.Columns.Companion.TIME)
            database.query(
                Schema.Posts.Companion.TABLE_NAME,
                projection, null, null, null, null, Schema.Posts.Columns.Companion.TIME + " DESC",
                (Schema.Posts.Companion.MAX_COUNT_FACTOR * Schema.Posts.Companion.MAX_COUNT).toInt()
                    .toString() + ", 1"
            ).use { cursor ->
                time = if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (time != null) {
                database.delete(
                    Schema.Posts.Companion.TABLE_NAME,
                    Schema.Posts.Columns.Companion.TIME + " <= " + time,
                    null
                )
            }
        }
    }

    fun setFlagsMigration(
        chanName: String, boardName: String?, threadNumber: String,
        flagsMap: MutableMap<PostNumber?, Pair<HideState?, Boolean?>?>, time: Long
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        Objects.requireNonNull<MutableMap<PostNumber?, Pair<HideState?, Boolean?>?>?>(flagsMap)
        database.execute<Any?>(ExecuteCallback { database: SQLiteDatabase? ->
            database!!.beginTransaction()
            try {
                val statement = database.compileStatement(
                    "INSERT OR REPLACE " +
                            "INTO " + Schema.Posts.Companion.TABLE_NAME + " (" +
                            Schema.Posts.Columns.Companion.CHAN_NAME + ", " +
                            Schema.Posts.Columns.Companion.BOARD_NAME + ", " +
                            Schema.Posts.Columns.Companion.THREAD_NUMBER + ", " +
                            Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + ", " +
                            Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + ", " +
                            Schema.Posts.Columns.Companion.TIME + ", " +
                            Schema.Posts.Columns.Companion.FLAGS + ") " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)"
                )
                statement.bindString(1, chanName)
                statement.bindString(2, emptyIfNull(boardName))
                statement.bindString(3, threadNumber)
                statement.bindLong(6, time)
                for (entry in flagsMap.entries) {
                    val postNumber: PostNumber = entry.key!!
                    val hideState = entry.value!!.first
                    val userPost: Boolean = entry.value!!.second!!
                    var flags = 0
                    if (hideState == HideState.HIDDEN) {
                        flags = flags or Schema.Posts.Flags.Companion.HIDDEN
                    } else if (hideState == HideState.SHOWN) {
                        flags = flags or Schema.Posts.Flags.Companion.SHOWN
                    }
                    if (userPost) {
                        flags = flags or Schema.Posts.Flags.Companion.USER
                    }
                    if (flags != 0) {
                        statement.bindLong(4, postNumber.major.toLong())
                        statement.bindLong(5, postNumber.minor.toLong())
                        statement.bindLong(7, flags.toLong())
                        statement.execute()
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            null
        })
    }

    fun setFlags(
        async: Boolean, chanName: String, boardName: String?, threadNumber: String,
        postNumber: PostNumber, hideState: HideState?, userPost: Boolean
    ) {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        Objects.requireNonNull<PostNumber?>(postNumber)
        val callback: ExecuteCallback<Void?> = ExecuteCallback { database: SQLiteDatabase? ->
            var flags = 0
            if (hideState == HideState.HIDDEN) {
                flags = flags or Schema.Posts.Flags.Companion.HIDDEN
            } else if (hideState == HideState.SHOWN) {
                flags = flags or Schema.Posts.Flags.Companion.SHOWN
            }
            if (userPost) {
                flags = flags or Schema.Posts.Flags.Companion.USER
            }
            if (flags != 0) {
                val values = ContentValues()
                values.put(Schema.Posts.Columns.Companion.CHAN_NAME, chanName)
                values.put(Schema.Posts.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                values.put(Schema.Posts.Columns.Companion.THREAD_NUMBER, threadNumber)
                values.put(Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR, postNumber.major)
                values.put(Schema.Posts.Columns.Companion.POST_NUMBER_MINOR, postNumber.minor)
                values.put(Schema.Posts.Columns.Companion.TIME, System.currentTimeMillis())
                values.put(Schema.Posts.Columns.Companion.FLAGS, flags)
                database!!.replace(Schema.Posts.Companion.TABLE_NAME, null, values)
            } else {
                val filter = Expression.filter()
                    .equals(Schema.Posts.Columns.Companion.CHAN_NAME, chanName)
                    .equals(Schema.Posts.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
                    .equals(Schema.Posts.Columns.Companion.THREAD_NUMBER, threadNumber)
                    .raw(Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR + " = " + postNumber.major)
                    .raw(Schema.Posts.Columns.Companion.POST_NUMBER_MINOR + " = " + postNumber.minor)
                    .build()
                database!!.delete(Schema.Posts.Companion.TABLE_NAME, filter.value, filter.args)
            }
            null
        }
        if (async) {
            database.enqueue(callback)
        } else {
            database.execute<Void?>(callback)
        }
    }

    fun getFlags(chanName: String, boardName: String?, threadNumber: String): Flags {
        Objects.requireNonNull<String?>(chanName)
        Objects.requireNonNull<String?>(threadNumber)
        val projection = arrayOf<String?>(
            Schema.Posts.Columns.Companion.POST_NUMBER_MAJOR,
            Schema.Posts.Columns.Companion.POST_NUMBER_MINOR,
            Schema.Posts.Columns.Companion.FLAGS
        )
        val filter = Expression.filter()
            .equals(Schema.Posts.Columns.Companion.CHAN_NAME, chanName)
            .equals(Schema.Posts.Columns.Companion.BOARD_NAME, emptyIfNull(boardName))
            .equals(Schema.Posts.Columns.Companion.THREAD_NUMBER, threadNumber)
            .raw(Schema.Posts.Columns.Companion.FLAGS)
            .build()
        val hiddenPosts = HideState.Map<PostNumber?>()
        val userPosts = HashSet<PostNumber>()
        database.query(QueryCallback { database: SQLiteDatabase? ->
            database!!
                .query(
                    Schema.Posts.Companion.TABLE_NAME,
                    projection,
                    filter.value,
                    filter.args,
                    null,
                    null,
                    null
                )
        }).use { cursor ->
            while (cursor!!.moveToNext()) {
                val postNumber = PostNumber(cursor!!.getInt(0), cursor!!.getInt(1))
                val flags = cursor!!.getInt(2)
                if (get(flags, Schema.Posts.Flags.Companion.HIDDEN)) {
                    hiddenPosts.set(postNumber, HideState.HIDDEN)
                } else if (get(flags, Schema.Posts.Flags.Companion.SHOWN)) {
                    hiddenPosts.set(postNumber, HideState.SHOWN)
                }
                if (get(flags, Schema.Posts.Flags.Companion.USER)) {
                    userPosts.add(postNumber)
                }
            }
        }
        if (hiddenPosts.size() > 0 || !userPosts.isEmpty()) {
            database.execute<Any?>(ExecuteCallback { database: SQLiteDatabase? ->
                val values = ContentValues()
                values.put(Schema.Posts.Columns.Companion.TIME, System.currentTimeMillis())
                database!!.update(
                    Schema.Posts.Companion.TABLE_NAME,
                    values,
                    filter.value,
                    filter.args
                )
                null
            })
        }
        return Flags(hiddenPosts, userPosts)
    }
}
