package com.mishiranu.dashchan.ui.preference

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.async.ReadUpdateTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

class ThemesFragment : BaseListFragment() {
	private var availableJsonThemes: List<JSONObject>? = null

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		super.onActivityCreated(savedInstanceState)

		(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.themes), null)
		val recyclerView = getRecyclerView()!!
		recyclerView.adapter = Adapter(recyclerView.context) { theme, installed, longClick ->
			if (longClick) {
				val json: String
				try {
					json = theme.toJsonObject().toString(4)
				} catch (e: JSONException) {
					throw RuntimeException(e)
				}
				ContextMenuDialog(theme.name, json, installed && !theme.builtIn)
						.show(childFragmentManager, ContextMenuDialog::class.java.name)
			} else {
				installTheme(theme, installed)
			}
			true
		}
		updateThemes()

		val viewModel = ViewModelProvider(this).get(ThemesViewModel::class.java)
		val availableThemes = if (savedInstanceState != null)
				savedInstanceState.getStringArrayList(EXTRA_AVAILABLE_THEMES) else null
		if (availableThemes != null) {
			val themes = ArrayList<JSONObject>()
			for (string in availableThemes) {
				try {
					themes.add(JSONObject(string))
				} catch (e: JSONException) {
					throw RuntimeException(e)
				}
			}
			availableJsonThemes = themes
			updateThemes()
		} else {
			if (!viewModel.hasTaskOrValue()) {
				val task = ReadThemesTask(viewModel)
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
				viewModel.attach(task)
			}
			viewModel.observe(viewLifecycleOwner) { result ->
				if (result.second != null) {
					availableJsonThemes = result.second
					updateThemes()
				} else {
					availableJsonThemes = emptyList()
					ClickableToast.show(result.first)
				}
			}
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)

		val availableJsonThemes = this.availableJsonThemes
		if (availableJsonThemes != null) {
			val availableThemes = ArrayList<String>()
			for (jsonObject in availableJsonThemes) {
				availableThemes.add(jsonObject.toString())
			}
			outState.putStringArrayList(EXTRA_AVAILABLE_THEMES, availableThemes)
		}
	}

	override fun onCreateOptionsMenu(menu: Menu, primary: Boolean) {
		menu.add(0, R.id.menu_add_theme, 0, R.string.add_theme)
				.setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
				.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
	}

	@Suppress("DEPRECATION")
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId == R.id.menu_add_theme) {
			// Check Android supports "application/json" MIME-type
			var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("json")
			if (StringUtils.isEmpty(mimeType) || "application/octet-stream" == mimeType) {
				mimeType = "*/*"
			}
			// SHOW_ADVANCED to show folder navigation
			val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
					.setType(mimeType).putExtra("android.content.extra.SHOW_ADVANCED", true)
			startActivityForResult(intent, C.REQUEST_CODE_ATTACH)
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	@Suppress("DEPRECATION")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		if (resultCode == Activity.RESULT_OK) {
			if (requestCode == C.REQUEST_CODE_ATTACH) {
				val uri = data!!.data
				val fileHolder = if (uri != null) FileHolder.obtain(uri) else null
				if (fileHolder != null) {
					val output = ByteArrayOutputStream()
					val success = try {
						fileHolder.openInputStream().use { input ->
							IOUtils.copyStream(input, output)
						}
						true
					} catch (e: IOException) {
						e.printStackTrace()
						false
					}
					val array = output.toByteArray()
					if (success && array.isNotEmpty()) {
						val jsonObject = try {
							JSONObject(String(array))
						} catch (e: JSONException) {
							null
						}
						val theme = if (jsonObject != null)
								ThemeEngine.parseTheme(requireContext(), jsonObject) else null
						if (theme != null) {
							installTheme(theme, false)
						} else {
							ClickableToast.show(R.string.invalid_data_format)
						}
					}
				}
			}
		}
	}

	override fun configureDivider(configuration: DividerItemDecoration.Configuration,
			position: Int): DividerItemDecoration.Configuration {
		return (getRecyclerView()!!.adapter as Adapter).configureDivider(configuration, position)
	}

	private fun updateThemes() {
		val listItems = ArrayList<ListItem>()
		var installedAdded = false
		for (theme in ThemeEngine.getThemes()) {
			if (!theme.builtIn && !installedAdded) {
				listItems.add(ListItem(null, false, getString(R.string.installed__plural)))
				installedAdded = true
			}
			listItems.add(ListItem(theme, true, null))
		}
		val availableThemes = ArrayList<ThemeEngine.Theme>()
		val availableJsonThemes = this.availableJsonThemes
		if (availableJsonThemes != null) {
			for (jsonObject in availableJsonThemes) {
				val theme = ThemeEngine.parseTheme(requireContext(), jsonObject)
				if (theme != null) {
					availableThemes.add(theme)
				}
			}
			Collections.sort(availableThemes)
		}
		if (!availableThemes.isEmpty()) {
			listItems.add(ListItem(null, false, getString(R.string.available__plural)))
			for (theme in availableThemes) {
				listItems.add(ListItem(theme, false, null))
			}
		}
		val adapter = getRecyclerView()!!.adapter as Adapter
		adapter.listItems = listItems
		adapter.notifyDataSetChanged()
	}

	private fun installTheme(theme: ThemeEngine.Theme, installed: Boolean) {
		if (!installed) {
			if (ThemeEngine.addTheme(theme)) {
				updateThemes()
			} else {
				ClickableToast.show(R.string.no_access)
				return
			}
		}
		if (!installed || theme.name != Preferences.getTheme()) {
			Preferences.setTheme(theme.name)
			requireActivity().recreate()
		}
	}

	internal fun deleteTheme(name: String) {
		if (ThemeEngine.deleteTheme(name)) {
			updateThemes()
			if (name == Preferences.getTheme()) {
				requireActivity().recreate()
			}
		}
	}

	private class ListItem(val theme: ThemeEngine.Theme?, val installed: Boolean, val title: String?)

	private class Adapter(context: Context, private val callback: Callback) :
			RecyclerView.Adapter<RecyclerView.ViewHolder>(),
			ListViewUtils.ClickCallback<Void, RecyclerView.ViewHolder> {
		private enum class ViewType { ITEM, HEADER }

		fun interface Callback {
			fun onThemeClick(theme: ThemeEngine.Theme, installed: Boolean, longClick: Boolean): Boolean
		}

		private class ItemViewHolder(val holder: Preference.Runtime.IconViewHolder) :
				RecyclerView.ViewHolder(holder.view) {
			init {
				ViewUtils.setSelectableItemBackground(itemView)
				holder.summary.visibility = View.GONE
			}
		}

		private val iconPreference: Preference.Runtime<Any?> =
				Preference.Runtime(context, "", null, "title") { null }

		internal var listItems: List<ListItem> = emptyList()

		fun configureDivider(configuration: DividerItemDecoration.Configuration,
				position: Int): DividerItemDecoration.Configuration {
			val next = if (listItems.size > position + 1) listItems[position + 1] else null
			return configuration.need(next != null && next.title != null)
		}

		override fun getItemCount(): Int = listItems.size

		override fun getItemViewType(position: Int): Int =
				(if (listItems[position].title != null) ViewType.HEADER else ViewType.ITEM).ordinal

		override fun onItemClick(holder: RecyclerView.ViewHolder, position: Int,
				nothing: Void?, longClick: Boolean): Boolean {
			val listItem = listItems[position]
			return callback.onThemeClick(listItem.theme!!, listItem.installed, longClick)
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			return when (ViewType.values()[viewType]) {
				ViewType.ITEM -> ListViewUtils.bind<Void, RecyclerView.ViewHolder>(
						ItemViewHolder(iconPreference.createIconViewHolder(parent)), true, null, this)
				ViewType.HEADER -> SimpleViewHolder(ViewFactory.makeListTextHeader(parent))
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val listItem = listItems[position]
			when (ViewType.values()[holder.itemViewType]) {
				ViewType.ITEM -> {
					val viewHolder = (holder as ItemViewHolder).holder
					viewHolder.icon.setImageDrawable(listItem.theme!!.createThemeChoiceDrawable())
					viewHolder.title.text = listItem.theme.name
				}
				ViewType.HEADER -> (holder.itemView as TextView).text = listItem.title
			}
		}
	}

	class ContextMenuDialog : DialogFragment {
		constructor()

		constructor(name: String?, json: String, canDelete: Boolean) {
			val args = Bundle()
			args.putString(EXTRA_NAME, name)
			args.putString(EXTRA_JSON, json)
			args.putBoolean(EXTRA_CAN_DELETE, canDelete)
			arguments = args
		}

		override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
			val name = requireArguments().getString(EXTRA_NAME)
			val dialogMenu = DialogMenu(requireContext())
			dialogMenu.add(R.string.save) {
				val binder = (requireActivity() as FragmentHandler).getDownloadBinder()
				if (binder != null) {
					val json = requireArguments().getString(EXTRA_JSON)
					binder.downloadStorage(ByteArrayInputStream(json!!.toByteArray()),
							null, null, null, null, "$name.json", false, true)
				}
			}
			if (requireArguments().getBoolean(EXTRA_CAN_DELETE)) {
				dialogMenu.add(R.string.delete) {
					val themesFragment = parentFragment as ThemesFragment
					themesFragment.view!!.post {
						themesFragment.deleteTheme(requireArguments().getString(EXTRA_NAME)!!)
					}
				}
			}
			return dialogMenu.create()
		}

		companion object {
			private const val EXTRA_NAME = "name"
			private const val EXTRA_JSON = "json"
			private const val EXTRA_CAN_DELETE = "canDelete"
		}
	}

	class ThemesViewModel : TaskViewModel<ReadThemesTask, Pair<ErrorItem, List<JSONObject>>>()

	class ReadThemesTask(private val viewModel: ThemesViewModel) :
			HttpHolderTask<Void, Pair<ErrorItem, List<JSONObject>>>(Chan.getFallback()) {
		override fun run(holder: HttpHolder): Pair<ErrorItem, List<JSONObject>> {
			try {
				var uri = Chan.getFallback().locator.setSchemeIfEmpty(Uri.parse(BuildConfig.URI_THEMES), null)
				var redirects = 0
				while (redirects++ < 5) {
					val jsonObject = JSONObject(HttpRequest(uri, holder).perform().readString())
					val redirect = CommonUtils.optJsonString(jsonObject, "redirect")
					if (redirect != null) {
						uri = ReadUpdateTask.normalizeRelativeUri(uri, redirect)
						continue
					}
					val jsonArray = jsonObject.getJSONArray("themes")
					val themes = ArrayList<JSONObject>()
					for (i in 0 until jsonArray.length()) {
						themes.add(jsonArray.getJSONObject(i))
					}
					return Pair(null, themes)
				}
				return Pair(ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), null)
			} catch (e: HttpException) {
				return Pair(e.getErrorItemAndHandle(), null)
			} catch (e: JSONException) {
				return Pair(ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null)
			}
		}

		override fun onComplete(result: Pair<ErrorItem, List<JSONObject>>) {
			viewModel.handleResult(result)
		}
	}

	companion object {
		private const val EXTRA_AVAILABLE_THEMES = "availableThemes"
	}
}
