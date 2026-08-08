package com.mishiranu.dashchan.ui.preference

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
        val header = createHeaderView(layout.context)
        if (header != null) {
            // The bar sits above the list rather than over it, so the two share a column and the list
            // takes whatever the bar leaves. The insets stay the list's (see setInsetsTarget).
            val column = LinearLayout(layout.context)
            column.orientation = LinearLayout.VERTICAL
            column.addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            column.addView(
                recyclerView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            layout.addView(
                column,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            layout.setInsetsTarget(recyclerView)
        } else {
            layout.addView(
                recyclerView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
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

    /**
     * A bar pinned above the list, or `null` for a plain list. Called while the view is being built,
     * i.e. before the list has anything in it — a header whose content depends on the items builds
     * itself empty here and fills in from `onViewCreated`.
     */
    protected open fun createHeaderView(context: Context): View? = null

    protected open fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = configuration.need(true)
}
