package com.mishiranu.dashchan.content.storage

import chan.content.Chan
import chan.text.JsonSerial
import chan.text.ParseException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class StatisticsStorage private constructor() : StorageManager.Storage<Map<String, StatisticsStorage.StatisticsItem>>("statistics", 1000, 10000) {
    private val statisticsItems = HashMap<String, StatisticsItem>()
    private var startTime = 0L

    init {
        startRead()
    }

    override fun onClone(): Map<String, StatisticsItem> = HashMap(statisticsItems)

    @Throws(IOException::class)
    override fun onRead(input: InputStream) {
        try {
            val reader = JsonSerial.reader(input)
            reader.startObject()
            while (!reader.endStruct()) {
                when (reader.nextName()) {
                    KEY_DATA -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            var chanName: String? = null
                            var threadsViewed = 0
                            var postsSent = 0
                            var threadsCreated = 0
                            reader.startObject()
                            while (!reader.endStruct()) {
                                when (reader.nextName()) {
                                    KEY_CHAN_NAME -> chanName = reader.nextString()
                                    KEY_THREADS_VIEWED -> threadsViewed = reader.nextInt()
                                    KEY_POSTS_SENT -> postsSent = reader.nextInt()
                                    KEY_THREADS_CREATED -> threadsCreated = reader.nextInt()
                                    else -> reader.skip()
                                }
                            }
                            if (chanName != null) {
                                statisticsItems[chanName] =
                                    StatisticsItem(
                                        threadsViewed,
                                        postsSent,
                                        threadsCreated,
                                    )
                            }
                        }
                    }

                    KEY_START_TIME -> {
                        startTime = maxOf(reader.nextLong(), 0L)
                    }

                    else -> {
                        reader.skip()
                    }
                }
            }
        } catch (e: ParseException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override fun onWrite(
        data: Map<String, StatisticsItem>,
        output: OutputStream,
    ) {
        val writer = JsonSerial.writer(output)
        writer.startObject()
        writer.name(KEY_DATA)
        writer.startArray()
        for ((key, statisticsItem) in data) {
            writer.startObject()
            writer.name(KEY_CHAN_NAME)
            writer.value(key)
            writer.name(KEY_THREADS_VIEWED)
            writer.value(statisticsItem.threadsViewed)
            writer.name(KEY_POSTS_SENT)
            writer.value(statisticsItem.postsSent)
            writer.name(KEY_THREADS_CREATED)
            writer.value(statisticsItem.threadsCreated)
            writer.endObject()
        }
        writer.endArray()
        writer.name(KEY_START_TIME)
        writer.value(startTime)
        writer.endObject()
        writer.flush()
    }

    class StatisticsItem internal constructor(
        @JvmField var threadsViewed: Int,
        @JvmField var postsSent: Int,
        @JvmField var threadsCreated: Int,
    )

    fun getItems(): HashMap<String, StatisticsItem> = statisticsItems

    fun getStartTime(): Long = startTime

    private fun obtainStatisticsItem(chanName: String): StatisticsItem {
        var statisticsItem = statisticsItems[chanName]
        if (statisticsItem == null) {
            if (statisticsItems.isEmpty()) {
                startTime = System.currentTimeMillis()
            }
            statisticsItem = StatisticsItem(0, 0, 0)
            statisticsItems[chanName] = statisticsItem
        }
        return statisticsItem
    }

    fun incrementThreadsViewed(chanName: String) {
        val chan = Chan.get(chanName)
        val statistics = chan.configuration.safe().obtainStatistics()
        if (statistics == null || !statistics.threadsViewed) {
            return
        }
        val statisticsItem = obtainStatisticsItem(chanName)
        statisticsItem.threadsViewed++
        serialize()
    }

    fun incrementPostsSent(
        chanName: String,
        newThread: Boolean,
    ) {
        val chan = Chan.get(chanName)
        val statistics = chan.configuration.safe().obtainStatistics()
        if (statistics == null || !(statistics.postsSent || (statistics.threadsCreated && newThread))) {
            return
        }
        val statisticsItem = obtainStatisticsItem(chanName)
        if (statistics.postsSent) {
            statisticsItem.postsSent++
        }
        if (statistics.threadsCreated && newThread) {
            statisticsItem.threadsCreated++
        }
        serialize()
    }

    fun clear() {
        statisticsItems.clear()
        serialize()
    }

    companion object {
        private const val KEY_START_TIME = "startTime"
        private const val KEY_DATA = "data"
        private const val KEY_CHAN_NAME = "chanName"
        private const val KEY_THREADS_VIEWED = "threadsViewed"
        private const val KEY_POSTS_SENT = "postsSent"
        private const val KEY_THREADS_CREATED = "threadsCreated"

        private val INSTANCE = StatisticsStorage()

        @JvmStatic
        fun getInstance(): StatisticsStorage = INSTANCE
    }
}
