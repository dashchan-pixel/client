package com.mishiranu.dashchan.ui.navigator.adapter

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory

class BoardsAdapter(
    private val callback: Callback,
) : CursorAdapter<ChanDatabase.BoardCursor, RecyclerView.ViewHolder>() {
    interface Callback : ListViewUtils.SimpleCallback<ChanDatabase.BoardItem>

    private val boardItem = ChanDatabase.BoardItem()

    fun isRealEmpty(): Boolean {
        val cursor = getCursor()
        return cursor == null || !cursor.hasItems
    }

    private fun copyItem(position: Int): ChanDatabase.BoardItem = boardItem.update(moveTo(position)).copy()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        ListViewUtils.bind(
            SimpleViewHolder(ViewFactory.makeSingleLineListItem(parent)),
            true,
            this::copyItem,
            callback,
        )

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val boardItem = this.boardItem.update(moveTo(position))
        (holder.itemView as TextView).text =
            StringUtils.formatBoardTitle("", boardItem.boardName, boardItem.extra1)
    }

    fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration {
        val header = if (position + 1 < itemCount) getItemHeader(position + 1) else null
        return configuration.need(header != null)
    }

    fun getItemHeader(position: Int): String? {
        val cursor = getCursor()
        return if (cursor != null && cursor.filtered) {
            null
        } else if (position == 0) {
            StringUtils.nullIfEmpty(boardItem.update(moveTo(0)).category)
        } else {
            val previous = boardItem.update(moveTo(position - 1)).category
            val current = boardItem.update(moveTo(position)).category
            if (CommonUtils.equals(previous, current)) null else current
        }
    }
}
