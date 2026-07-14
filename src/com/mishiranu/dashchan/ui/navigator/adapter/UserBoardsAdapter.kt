package com.mishiranu.dashchan.ui.navigator.adapter

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import chan.util.StringUtils
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.widget.CursorAdapter
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory

class UserBoardsAdapter(
    private val callback: Callback,
) : CursorAdapter<ChanDatabase.BoardCursor, RecyclerView.ViewHolder>() {
    interface Callback : ListViewUtils.SimpleCallback<ChanDatabase.BoardItem>

    private val boardItem = ChanDatabase.BoardItem()

    private fun copyItem(position: Int): ChanDatabase.BoardItem = boardItem.update(moveTo(position)).copy()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        ListViewUtils.bind(
            SimpleViewHolder(ViewFactory.makeTwoLinesListItem(parent, 0).view),
            true,
            this::copyItem,
            callback,
        )

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val boardItem = this.boardItem.update(moveTo(position))
        val viewHolder = holder.itemView.tag as ViewFactory.TwoLinesViewHolder
        viewHolder.text1.text = StringUtils.formatBoardTitle("", boardItem.boardName, boardItem.extra1)
        if (!StringUtils.isEmpty(boardItem.extra2)) {
            viewHolder.text2.visibility = View.VISIBLE
            viewHolder.text2.text = boardItem.extra2
        } else {
            viewHolder.text2.visibility = View.GONE
        }
    }
}
