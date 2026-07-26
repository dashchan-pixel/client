package com.mishiranu.dashchan.ui.navigator.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.database.EchoDatabase
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import java.util.Calendar

class EchoAdapter(
    context: Context,
    private val callback: Callback,
    private val chanName: String?,
) : CursorAdapter<EchoDatabase.EchoCursor, RecyclerView.ViewHolder>() {
    interface Callback : ListViewUtils.SimpleCallback<EchoDatabase.EchoItem>

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

    /** Holds the tint of an unread row, drawn under the row's own touch feedback. */
    private class EchoViewHolder(
        val twoLines: ViewFactory.TwoLinesViewHolder,
        val unreadBackground: ColorDrawable,
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView)

    private val postDateFormatter = PostDateFormatter(context)

    private val highlightBackgroundColor = ThemeEngine.getColorScheme(context).highlightBackgroundColor

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

    private fun getItem(position: Int): EchoDatabase.EchoItem = EchoDatabase.EchoItem(moveTo(position))

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val twoLines = ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_TEXT2_END)
        val unreadBackground = ColorDrawable(Color.TRANSPARENT)
        val frameLayout = android.widget.FrameLayout(parent.context)
        frameLayout.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        frameLayout.background = unreadBackground
        frameLayout.addView(twoLines.view)
        return ListViewUtils.bind(
            EchoViewHolder(twoLines, unreadBackground, frameLayout),
            twoLines.view,
            true,
            this::getItem,
            callback,
        )
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val echoItem = getItem(position)
        val echoViewHolder = holder as EchoViewHolder
        val viewHolder = echoViewHolder.twoLines
        viewHolder.text1.text = formatComment(echoItem.comment)
        // Unread replies get the tint a thread gives an unread post, until the post is opened
        // or the Echo is marked as read
        echoViewHolder.unreadBackground.color =
            if (echoItem.unread) highlightBackgroundColor else Color.TRANSPARENT
        var title = StringUtils.nullIfEmpty(echoItem.title)
        if (title == null) {
            title =
                StringUtils.nullIfEmpty(
                    FavoritesStorage
                        .getInstance()
                        .getFavorite(echoItem.chanName, echoItem.boardName, echoItem.threadNumber)
                        ?.title,
                )
        }
        if (title == null) {
            title =
                StringUtils.nullIfEmpty(
                    CommonDatabase
                        .getInstance()
                        .history
                        .getTitle(echoItem.chanName, echoItem.boardName, echoItem.threadNumber),
                )
        }
        if (title == null) {
            title =
                StringUtils.formatThreadTitle(
                    echoItem.chanName,
                    echoItem.boardName,
                    echoItem.threadNumber,
                )
        }
        if (chanName == null) {
            title = Chan.get(echoItem.chanName).configuration.getTitle() + " — " + title
        }
        viewHolder.text2.text = title
        viewHolder.text2End?.text = postDateFormatter.formatDateTime(echoItem.time)
    }

    private fun getItemHeader(position: Int): Header? {
        val cursor = getCursor()
        return if (cursor != null && cursor.filtered) {
            null
        } else if (position == 0) {
            Header.find(queryDayStart, getItem(0).time)
        } else {
            val previous = Header.find(queryDayStart, getItem(position - 1).time)
            val current = Header.find(queryDayStart, getItem(position).time)
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

    companion object {
        /** Collapses the reply into a single line, the same way its notification does. */
        private fun formatComment(comment: String?): String =
            StringUtils
                .clearHtml(comment)
                .replace('\n', ' ')
                .replace(" {2,}".toRegex(), " ")
                .trim()
    }
}
