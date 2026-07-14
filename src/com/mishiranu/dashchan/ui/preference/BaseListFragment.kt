package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chan.util.StringUtils
import com.mishiranu.dashchan.ui.ContentFragment
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.ViewFactory

abstract class BaseListFragment : ContentFragment() {
    private var recyclerView: RecyclerView? = null
    private var errorHolder: ViewFactory.ErrorHolder? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layout = ExpandedLayout(container!!.context, true)
        val recyclerView = PaddedRecyclerView(layout.context)
        this.recyclerView = recyclerView
        recyclerView.id = android.R.id.list
        recyclerView.isMotionEventSplittingEnabled = false
        recyclerView.isVerticalScrollBarEnabled = true
        recyclerView.clipToPadding = false
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, this::configureDivider))
        layout.addView(
            recyclerView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val errorHolder = ViewFactory.createErrorLayout(layout)
        this.errorHolder = errorHolder
        errorHolder.layout.visibility = View.GONE
        layout.addView(errorHolder.layout)
        setListPadding(recyclerView)
        return layout
    }

    override fun onDestroyView() {
        super.onDestroyView()

        recyclerView = null
        errorHolder = null
    }

    fun getRecyclerView(): RecyclerView? = recyclerView

    fun setErrorText(text: CharSequence?) {
        val errorHolder = errorHolder!!
        errorHolder.layout.visibility = if (StringUtils.isEmpty(text)) View.GONE else View.VISIBLE
        errorHolder.text.text = text
    }

    protected open fun setListPadding(recyclerView: RecyclerView) {
    }

    protected open fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(true)
}
