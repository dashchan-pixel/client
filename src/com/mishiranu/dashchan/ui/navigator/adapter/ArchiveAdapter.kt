package com.mishiranu.dashchan.ui.navigator.adapter

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import chan.content.model.ThreadSummary
import chan.util.StringUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory
import java.util.Locale

class ArchiveAdapter(private val callback: Callback) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
	interface Callback : ListViewUtils.SimpleCallback<String>

	private val archiveItems = ArrayList<ThreadSummary>()
	private val filteredArchiveItems = ArrayList<ThreadSummary>()

	private var filterText: String? = null

	fun applyFilter(text: String?) {
		if (StringUtils.emptyIfNull(filterText) != StringUtils.emptyIfNull(text)) {
			filterText = StringUtils.nullIfEmpty(text)
			applyCurrentFilter()
			notifyDataSetChanged()
		}
	}

	private fun applyCurrentFilter() {
		var text = filterText
		filteredArchiveItems.clear()
		if (!StringUtils.isEmpty(text)) {
			text = text!!.lowercase(Locale.getDefault())
			for (threadSummary in archiveItems) {
				val title = threadSummary.getDescription()
				if (title != null && title.lowercase(Locale.getDefault()).contains(text)) {
					filteredArchiveItems.add(threadSummary)
				}
			}
		}
	}

	fun isRealEmpty(): Boolean = archiveItems.size == 0

	override fun getItemCount(): Int =
			(if (filterText != null) filteredArchiveItems else archiveItems).size

	private fun getItem(position: Int): ThreadSummary =
			(if (filterText != null) filteredArchiveItems else archiveItems)[position]

	private fun getThreadNumber(position: Int): String = getItem(position).getThreadNumber()

	fun setItems(threadSummaries: List<ThreadSummary>?) {
		archiveItems.clear()
		if (threadSummaries != null) {
			archiveItems.addAll(threadSummaries)
		}
		applyCurrentFilter()
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		return ListViewUtils.bind(SimpleViewHolder(ViewFactory.makeSingleLineListItem(parent)),
				true, this::getThreadNumber, callback)
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		val threadSummary = getItem(position)
		(holder.itemView as TextView).text = threadSummary.getDescription()
	}

	fun configureDivider(configuration: DividerItemDecoration.Configuration,
			position: Int): DividerItemDecoration.Configuration {
		return configuration.need(false)
	}
}
