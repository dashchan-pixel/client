package com.mishiranu.dashchan.ui.navigator.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.HistoryDatabase
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory
import java.util.Calendar

class HistoryAdapter(
    context: Context,
    private val callback: Callback,
    private val chanName: String?,
) : CursorAdapter<HistoryDatabase.HistoryCursor, RecyclerView.ViewHolder>() {
    interface Callback : ListViewUtils.SimpleCallback<HistoryDatabase.HistoryItem>

    private enum class Header(
        val titleResId: Int,
        val threshold: Long,
    ) {
        TODAY(R.string.today, 0L),
        YESTERDAY(R.string.yesterday, 24L * 60 * 60 * 1000),
        WEEK(R.string.this_week, 7L * 24 * 60 * 60 * 1000),
        OLD(R.string.older_than_seven_days, Long.MAX_VALUE),
        ;

        companion object {
            fun find(
                dayStart: Long,
                time: Long,
            ): Header {
                val delta = dayStart - time
                for (header in entries) {
                    if (delta <= header.threshold) {
                        return header
                    }
                }
                return OLD
            }
        }
    }

    private val postDateFormatter = PostDateFormatter(context)
    private val historyItem = HistoryDatabase.HistoryItem()

    private var queryDayStart: Long = 0

    init {
        setHasStableIds(true)
    }

    override fun onCursorChanged() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        queryDayStart = calendar.timeInMillis
    }

    private fun copyItem(position: Int): HistoryDatabase.HistoryItem = historyItem.update(moveTo(position)).copy()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        ListViewUtils.bind(
            SimpleViewHolder(
                ViewFactory
                    .makeTwoLinesListItem(
                        parent,
                        ViewFactory.FEATURE_TEXT2_END,
                    ).view,
            ),
            true,
            this::copyItem,
            callback,
        )

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val historyItem = this.historyItem.update(moveTo(position))
        val viewHolder = holder.itemView.tag as ViewFactory.TwoLinesViewHolder
        viewHolder.text1.text =
            if (StringUtils.isEmpty(historyItem.title)) {
                StringUtils.formatThreadTitle(historyItem.chanName!!, historyItem.boardName, historyItem.threadNumber!!)
            } else {
                historyItem.title
            }
        val chan = Chan.get(historyItem.chanName)
        var title = chan.configuration.getBoardTitle(historyItem.boardName)
        title =
            if (StringUtils.isEmpty(historyItem.boardName)) {
                title
            } else {
                StringUtils.formatBoardTitle(historyItem.chanName!!, historyItem.boardName, title)
            }
        if (chanName == null) {
            title = chan.configuration.getTitle() + " — " + title
        }
        viewHolder.text2.text = title
        viewHolder.text2End!!.text = postDateFormatter.formatDate(historyItem.time)
    }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(true)

    private fun getItemHeader(position: Int): Header? {
        val cursor = getCursor()
        return if (cursor != null && cursor.filtered) {
            null
        } else if (position == 0) {
            Header.find(queryDayStart, historyItem.update(moveTo(0)).time)
        } else {
            val previous = Header.find(queryDayStart, historyItem.update(moveTo(position - 1)).time)
            val current = Header.find(queryDayStart, historyItem.update(moveTo(position)).time)
            if (previous != current) current else null
        }
    }

    fun getItemHeader(
        context: Context,
        position: Int,
    ): String? {
        val header = getItemHeader(position)
        return if (header != null) context.getString(header.titleResId) else null
    }
}
