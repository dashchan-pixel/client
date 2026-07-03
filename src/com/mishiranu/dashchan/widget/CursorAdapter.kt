package com.mishiranu.dashchan.widget

import android.database.Cursor
import androidx.recyclerview.widget.RecyclerView

abstract class CursorAdapter<C : Cursor, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
	private var cursor: C? = null

	final override fun getItemCount(): Int {
		return cursor?.count ?: 0
	}

	final override fun getItemId(position: Int): Long {
		val cursor = cursor!!
		cursor.moveToPosition(position)
		val index = cursor.getColumnIndex("rowid")
		return if (index >= 0) cursor.getLong(index) else RecyclerView.NO_ID
	}

	fun setCursor(cursor: C?) {
		if (this.cursor != cursor) {
			this.cursor?.close()
			this.cursor = cursor
			onCursorChanged()
			@Suppress("NotifyDataSetChanged")
			notifyDataSetChanged()
		}
	}

	fun getCursor(): C? = cursor

	protected fun moveTo(position: Int): C? {
		cursor?.moveToPosition(position)
		return cursor
	}

	protected open fun onCursorChanged() {}
}
