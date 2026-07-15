package com.mishiranu.dashchan.widget

import android.content.Context
import android.view.CollapsibleActionView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.Toolbar

class CustomSearchView(
    context: Context,
) : FrameLayout(context),
    CollapsibleActionView {
    fun interface OnSubmitListener {
        fun onSubmit(query: String): Boolean
    }

    fun interface OnChangeListener {
        fun onChange(query: String)
    }

    private val searchView = SearchView(context)
    private val customViewLayout = FrameLayout(context)
    private val contentInsetEnd: Int

    private var onSubmitListener: OnSubmitListener? = null
    private var onChangeListener: OnChangeListener? = null

    private var focusOnExpand = true
    private var suppressChange = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.HORIZONTAL
        addView(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchView.maxWidth = Int.MAX_VALUE
        disableSaveInstanceState(searchView)
        layout.addView(searchView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(customViewLayout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    val onSubmitListener = this@CustomSearchView.onSubmitListener
                    if (onSubmitListener == null || onSubmitListener.onSubmit(query)) {
                        searchView.clearFocus()
                        requestFocus()
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    val onChangeListener = this@CustomSearchView.onChangeListener
                    if (onChangeListener != null && !suppressChange) {
                        onChangeListener.onChange(newText)
                    }
                    return true
                }
            },
        )

        val typedArray =
            context.obtainStyledAttributes(
                null,
                intArrayOf(android.R.attr.contentInsetEnd),
                android.R.attr.actionBarStyle,
                0,
            )
        contentInsetEnd = typedArray.getDimensionPixelSize(0, 0)
        typedArray.recycle()
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)

        // Ignore Toolbar's content inset at the end if there is only free space left
        // There should be "home" button at the start, so this side is not handled at all
        if (contentInsetEnd > 0 && parent is Toolbar) {
            val layout = getChildAt(0)
            val layoutParams = layout.layoutParams as FrameLayout.LayoutParams
            val toolbar = parent as Toolbar
            var relayout = false
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                val apply = left == contentInsetEnd
                if (apply == (layoutParams.leftMargin == 0)) {
                    layoutParams.leftMargin = if (apply) -contentInsetEnd else 0
                    relayout = true
                }
            } else {
                val apply = toolbar.measuredWidth == right + contentInsetEnd
                if (apply == (layoutParams.rightMargin == 0)) {
                    layoutParams.rightMargin = if (apply) -contentInsetEnd else 0
                    relayout = true
                }
            }
            if (relayout) {
                layout.requestLayout()
            }
        }
    }

    fun setHint(hint: CharSequence?) {
        searchView.queryHint = hint
    }

    fun getQuery(): String = searchView.query.toString()

    fun setQuery(query: String?) {
        searchView.setQuery(query, false)
    }

    fun setOnSubmitListener(listener: OnSubmitListener?) {
        onSubmitListener = listener
    }

    fun setOnChangeListener(listener: OnChangeListener?) {
        onChangeListener = listener
    }

    fun setCustomView(view: View?) {
        customViewLayout.removeAllViews()
        if (view != null) {
            customViewLayout.addView(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    fun isSearchFocused(): Boolean = searchView.hasFocus()

    fun setFocusOnExpand(focusOnExpand: Boolean) {
        this.focusOnExpand = focusOnExpand
    }

    override fun onActionViewExpanded() {
        val query = getQuery()
        if (query.isNotEmpty()) {
            // Don't clear text view and don't fire "changed" action with empty text
            val suppressChange = this.suppressChange
            this.suppressChange = true
            searchView.onActionViewExpanded()
            this.suppressChange = suppressChange
            setQuery(query)
        } else {
            searchView.onActionViewExpanded()
        }
        if (!focusOnExpand) {
            searchView.clearFocus()
            requestFocus()
        }
    }

    override fun onActionViewCollapsed() {
        searchView.onActionViewCollapsed()
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        if (searchView.hasFocus() && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                searchView.clearFocus()
                requestFocus()
            }
            true
        } else {
            super.dispatchKeyEventPreIme(event)
        }

    companion object {
        private fun disableSaveInstanceState(view: View) {
            view.isSaveEnabled = false
            if (view is ViewGroup) {
                val childCount = view.childCount
                for (i in 0 until childCount) {
                    disableSaveInstanceState(view.getChildAt(i))
                }
            }
        }
    }
}
