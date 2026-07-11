package com.mishiranu.dashchan.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.ThemeEngine
import java.util.Collections

class ThemeDialog : DialogFragment() {
	interface Callback {
		fun onThemeSelected(theme: ThemeEngine.Theme?)
	}

	private interface InnerCallback : Callback,
			ListViewUtils.ClickCallback<ThemeEngine.Theme, RecyclerView.ViewHolder> {
		override fun onItemClick(holder: RecyclerView.ViewHolder, position: Int,
				item: ThemeEngine.Theme?, longClick: Boolean): Boolean {
			onThemeSelected(item)
			return true
		}
	}

	private fun notifyThemeSelected(theme: ThemeEngine.Theme?) {
		val callback = requireActivity() as Callback
		ConcurrentUtils.HANDLER.post { callback.onThemeSelected(theme) }
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val context = requireContext()
		val recyclerView = PaddedRecyclerView(context)
		recyclerView.layoutManager = LinearLayoutManager(context)
		val density = ResourceUtils.obtainDensity(context)
		recyclerView.setPadding(0, (12f * density).toInt(), 0, 0)

		val dialog = android.app.AlertDialog.Builder(context)
				.setTitle(R.string.change_theme)
				.setView(recyclerView)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.more_themes) { _, _ -> notifyThemeSelected(null) }
				.create()
		val themes = ThemeEngine.getThemes()
		Collections.sort(themes)
		recyclerView.adapter = Adapter(context, themes, object : InnerCallback {
			override fun onThemeSelected(theme: ThemeEngine.Theme?) {
				dialog.dismiss()
				if (theme != ThemeEngine.getTheme(context)) {
					notifyThemeSelected(theme)
				}
			}
		})
		return dialog
	}

	private class Adapter(context: Context, private val themes: List<ThemeEngine.Theme>,
			private val callback: InnerCallback) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
		private class ItemViewHolder(val holder: Preference.Runtime.IconViewHolder) :
				RecyclerView.ViewHolder(holder.view) {
			init {
				ViewUtils.setSelectableItemBackground(itemView)
				holder.summary!!.visibility = View.GONE
				val density = ResourceUtils.obtainDensity(itemView)
				itemView.setPadding(itemView.paddingLeft + (8f * density).toInt(), itemView.paddingTop,
						itemView.paddingRight + (8f * density).toInt(), itemView.paddingBottom)
			}
		}

		private val iconPreference: Preference.Runtime<*> =
				Preference.Runtime<Any?>(context, "", null, "title") { null }

		override fun getItemCount(): Int = themes.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			return ListViewUtils.bind(ItemViewHolder(iconPreference.createIconViewHolder(parent)),
					false, themes::get, callback)
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val viewHolder = (holder as ItemViewHolder).holder
			val theme = themes[position]
			viewHolder.icon!!.setImageDrawable(theme.createThemeChoiceDrawable())
			viewHolder.title!!.text = theme.name
		}
	}
}
