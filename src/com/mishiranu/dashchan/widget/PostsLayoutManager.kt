package com.mishiranu.dashchan.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PostsLayoutManager(context: Context) : LinearLayoutManager(context) {
	override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View,
			rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
		// Don't scroll RecyclerView when text selection starts/ends
		return child is PostLinearLayout ||
				super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
	}
}
