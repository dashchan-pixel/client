package com.mishiranu.dashchan.content.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.LongSparseArray
import chan.util.CommonUtils
import java.util.Objects
import kotlin.concurrent.Volatile

object Expression {
    fun filter(): Filter.Builder {
        return Filter.Builder(false)
    }

    fun filterOr(): Filter.Builder {
        return Filter.Builder(true)
    }

    fun batchInsert(
        totalItems: Int,
        batchSize: Int,
        args: Int,
        createBatchInsertStatement: CreateBatchInsertStatement,
        bindBatchInsertArgs: BindBatchInsertArgs
    ) {
        val lastBatchSize = totalItems % batchSize
        var index = 0
        var statement: SQLiteStatement? = null
        while (index < totalItems) {
            if (index > 0 && index % batchSize == 0) {
                statement!!.execute()
            }
            val handleLast = index == totalItems - lastBatchSize
            if (handleLast || index == 0) {
                val size = if (handleLast) lastBatchSize else batchSize
                statement = createBatchInsertStatement.create(buildInsertValues(size, args))
            }
            val start = args * (index % batchSize)
            bindBatchInsertArgs.bind(statement, start)
            index++
        }
        if (statement != null) {
            statement.execute()
        }
    }

    private fun buildInsertValues(count: Int, args: Int): String {
        val builder = StringBuilder()
        for (i in 0..<count) {
            if (i > 0) {
                builder.append(", ")
            }
            builder.append('(')
            for (j in 0..<args) {
                if (j > 0) {
                    builder.append(", ")
                }
                builder.append('?')
            }
            builder.append(')')
        }
        return builder.toString()
    }

    fun updateById(
        database: SQLiteDatabase, iterator: LongIterator,
        table: String?, idColumn: String?, set: String?, filter: Filter?
    ) {
        val builder = StringBuilder()
        val maxCount = 100
        while (iterator.hasNext()) {
            builder.setLength(0)
            builder.append(iterator.next())
            var i = 1
            while (i < maxCount && iterator.hasNext()) {
                builder.append(", ")
                builder.append(iterator.next())
                i++
            }
            database.execSQL(
                "UPDATE " + table + " SET " + set + " " +
                        "WHERE " + idColumn + " IN (" + builder + ") AND " +
                        (if (filter != null && filter.value != null) filter.value else "1"),
                (if (filter != null) filter.args else null)!!
            )
        }
    }

    class Filter private constructor(val value: String?, val args: Array<String?>?) {
        class Builder internal constructor(private val or: Boolean) {
            private val builder = StringBuilder()
            private val args = ArrayList<String?>()

            private fun append() {
                if (builder.length > 0) {
                    builder.append(if (or) " OR " else " AND ")
                }
            }

            fun equals(name: String, value: String?): Builder {
                Objects.requireNonNull<String?>(name)
                append()
                if (value != null) {
                    builder.append(name).append(" = ?")
                    args.add(value)
                } else {
                    builder.append(name).append(" IS NULL")
                }
                return this
            }

            fun like(name: String, value: String): Builder {
                Objects.requireNonNull<String?>(name)
                Objects.requireNonNull<String?>(value)
                append()
                builder.append(name).append(" LIKE ?")
                args.add(value)
                return this
            }

            fun `in`(name: String, values: Collection<*>): Builder {
                Objects.requireNonNull<String?>(name)
                Objects.requireNonNull(values)
                append()
                if (values.isEmpty()) {
                    builder.append("0")
                } else {
                    builder.append(name).append(" IN (?")
                    for (i in 1..<values.size) {
                        builder.append(", ?")
                    }
                    builder.append(")")
                    for (value in values) {
                        Objects.requireNonNull<Any?>(value)
                        args.add(value.toString())
                    }
                }
                return this
            }

            fun raw(name: String?): Builder {
                append()
                builder.append(name)
                return this
            }

            fun append(builder: Builder): Builder {
                append()
                this.builder.append('(').append(builder.builder).append(')')
                args.addAll(builder.args)
                return this
            }

            fun build(): Filter {
                if (builder.length == 0) {
                    return Filter(null, null)
                } else {
                    return Filter(
                        builder.toString(),
                        args.toTypedArray()
                    )
                }
            }
        }
    }

    fun interface CreateBatchInsertStatement {
        fun create(values: String?): SQLiteStatement?
    }

    fun interface BindBatchInsertArgs {
        fun bind(statement: SQLiteStatement?, start: Int)
    }

    interface LongIterator {
        fun hasNext(): Boolean
        fun next(): Long

        companion object {
            fun create(array: LongSparseArray<*>): LongIterator {
                return SparseArrayLongIterator(array)
            }

            fun create(iterator: MutableIterator<Long?>): LongIterator {
                return IteratorLongIterator(iterator)
            }
        }
    }

    private class SparseArrayLongIterator(private val array: LongSparseArray<*>) : LongIterator {
        private var index = 0

        override fun hasNext(): Boolean {
            return index < array.size()
        }

        override fun next(): Long {
            return array.keyAt(index++)
        }
    }

    private class IteratorLongIterator(private val iterator: MutableIterator<Long?>) :
        LongIterator {
        override fun hasNext(): Boolean {
            return iterator.hasNext()
        }

        override fun next(): Long {
            return iterator.next()!!
        }
    }

    class KeyLock<T> {
        fun interface Callback<R, E : Throwable?> {
            fun run(): R?
        }

        private class ReferenceCount {
            @Volatile
            var count: Int = 0
        }

        private val locks: HashMap<T?, ReferenceCount?> = HashMap<T?, ReferenceCount?>()

        fun <R, E : Throwable?> lock(key: T?, callback: Callback<R?, E?>): R? {
            var lock: ReferenceCount?
            synchronized(locks) {
                lock = locks.get(key)
                if (lock == null) {
                    lock = ReferenceCount()
                    locks.put(key, lock)
                }
                lock.count++
            }
            try {
                synchronized(lock!!) {
                    return callback.run()
                }
            } finally {
                synchronized(locks) {
                    if (--lock!!.count == 0) {
                        locks.remove(key)
                    }
                }
            }
        }
    }
}
