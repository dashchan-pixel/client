package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.StatisticsStorage
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.util.ResourceUtils

class StatisticsFragment : BaseListFragment() {
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val startTime = StatisticsStorage.getInstance().getStartTime()
        (requireActivity() as FragmentHandler).setTitleSubtitle(
            getString(R.string.statistics),
            if (startTime > 0) {
                getString(
                    R.string.since_date__format,
                    PostDateFormatter(requireContext()).formatDateTime(startTime),
                )
            } else {
                null
            },
        )

        val listItems = ArrayList<Adapter.ListItem>()
        listItems.add(
            Adapter.ListItem(
                null,
                getString(R.string.views),
                getString(R.string.posts),
                getString(R.string.threads),
            ),
        )

        val statisticsItems = StatisticsStorage.getInstance().getItems()
        var totalThreadsViewed = 0
        var totalPostsSent = 0
        var totalThreadsCreated = 0
        for (entry in statisticsItems.entries) {
            val statistics =
                Chan
                    .get(entry.key)
                    .configuration
                    .safe()
                    .obtainStatistics()
            val statisticsItem = entry.value
            if (statistics!!.threadsViewed && statisticsItem.threadsViewed > 0) {
                totalThreadsViewed += statisticsItem.threadsViewed
            }
            if (statistics.postsSent && statisticsItem.postsSent > 0) {
                totalPostsSent += statisticsItem.postsSent
            }
            if (statistics.threadsCreated && statisticsItem.threadsCreated > 0) {
                totalThreadsCreated += statisticsItem.threadsCreated
            }
        }
        listItems.add(
            Adapter.ListItem(
                getString(R.string.total),
                totalThreadsViewed.toString(),
                totalPostsSent.toString(),
                totalThreadsCreated.toString(),
            ),
        )

        for (chan in ChanManager.getInstance().availableChans) {
            val statisticsItem = statisticsItems[chan.name]
            if (statisticsItem != null) {
                val statistics = chan.configuration.safe().obtainStatistics()
                if (statistics!!.threadsViewed && statistics.postsSent && statistics.threadsCreated) {
                    val threadsViewed = if (statistics.threadsViewed) statisticsItem.threadsViewed else -1
                    val postsSent = if (statistics.postsSent) statisticsItem.postsSent else -1
                    val threadsCreated = if (statistics.threadsCreated) statisticsItem.threadsCreated else -1
                    var title = chan.configuration.getTitle()
                    if (StringUtils.isEmpty(title)) {
                        title = chan.name
                    }
                    listItems.add(
                        Adapter.ListItem(
                            title,
                            threadsViewed.toString(),
                            postsSent.toString(),
                            threadsCreated.toString(),
                        ),
                    )
                }
            }
        }

        getRecyclerView()!!.adapter = Adapter(listItems)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_clear, 0, R.string.clear)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionDelete))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_clear) {
            StatisticsStorage.getInstance().clear()
            (requireActivity() as FragmentHandler).removeFragment()
        }
        return super.onMenuItemSelected(item)
    }

    private class Adapter(
        private val listItems: List<ListItem>,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        class ListItem(
            val text1: String?,
            val text2: String?,
            val text3: String?,
            val text4: String?,
        )

        private class ViewHolder(
            itemView: View,
            val text1: TextView,
            val text2: TextView,
            val text3: TextView,
            val text4: TextView,
        ) : RecyclerView.ViewHolder(itemView)

        override fun getItemCount(): Int = listItems.size

        private fun addTextView(
            parent: LinearLayout,
            end: Boolean,
            weight: Float,
            padding: Int,
        ): TextView {
            val textView = TextView(parent.context)
            TextViewCompat.setTextAppearance(
                textView,
                ResourceUtils.getResourceId(
                    textView.context,
                    android.R.attr.textAppearanceListItem,
                    android.R.style.TextAppearance_Medium,
                ),
            )
            textView.setSingleLine(true)
            textView.ellipsize = TextUtils.TruncateAt.END
            textView.gravity = Gravity.CENTER_VERTICAL or (if (end) Gravity.END else Gravity.START)
            val layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            layoutParams.leftMargin = padding
            parent.addView(textView, layoutParams)
            return textView
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val linearLayout = LinearLayout(parent.context)
            linearLayout.orientation = LinearLayout.HORIZONTAL
            val density = ResourceUtils.obtainDensity(linearLayout)
            val outerPadding = (16f * density).toInt()
            val innerPadding = (8f * density).toInt()
            linearLayout.setPadding(outerPadding - innerPadding, 0, outerPadding, 0)
            val text1 = addTextView(linearLayout, false, 3f, innerPadding)
            val text2 = addTextView(linearLayout, true, 2f, innerPadding)
            val text3 = addTextView(linearLayout, true, 2f, innerPadding)
            val text4 = addTextView(linearLayout, true, 2f, innerPadding)
            linearLayout.layoutParams =
                RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (48f * density).toInt(),
                )
            return ViewHolder(linearLayout, text1, text2, text3, text4)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val listItem = listItems[position]
            holder.text1.text = listItem.text1
            holder.text2.text = listItem.text2
            holder.text3.text = listItem.text3
            holder.text4.text = listItem.text4
        }
    }
}
