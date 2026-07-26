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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.util.CommonUtils
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.async.ReadUpdateTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.CheckPreference
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
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

class ThemesFragment : BaseListFragment() {
    private var availableJsonThemes: List<JSONObject>? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.themes), null)
        val recyclerView = getRecyclerView()!!
        recyclerView.adapter =
            Adapter(
                recyclerView.context,
                object : Adapter.Callback {
                    override fun onThemeClick(
                        theme: ThemeEngine.Theme,
                        installed: Boolean,
                        longClick: Boolean,
                    ): Boolean {
                        if (longClick) {
                            showContextMenu(theme, installed)
                        } else {
                            installTheme(theme, installed)
                        }
                        return true
                    }

                    override fun onFollowSystemClick(): Boolean {
                        toggleFollowSystem()
                        return true
                    }
                },
            )
        updateThemes()

        val viewModel = ViewModelProvider(this).get(ThemesViewModel::class.java)
        val availableThemes =
            if (savedInstanceState != null) {
                savedInstanceState.getStringArrayList(EXTRA_AVAILABLE_THEMES)
            } else {
                null
            }
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

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_theme, 0, R.string.new_theme)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, R.id.menu_add_theme, 0, R.string.add_theme)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_new_theme) {
            (requireActivity() as FragmentHandler).pushFragment(ThemeEditorFragment(null, null))
            return true
        }
        if (item.itemId == R.id.menu_add_theme) {
            // Check Android supports "application/json" MIME-type
            var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("json")
            if (StringUtils.isEmpty(mimeType) || "application/octet-stream" == mimeType) {
                mimeType = "*/*"
            }
            // SHOW_ADVANCED to show folder navigation
            val intent =
                Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
            addThemeLauncher.launch(intent)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private val addThemeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val uri = data.data
                val fileHolder = if (uri != null) FileHolder.obtain(uri) else null
                if (fileHolder != null) {
                    val output = ByteArrayOutputStream()
                    val success =
                        try {
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
                        val jsonObject =
                            try {
                                JSONObject(String(array))
                            } catch (e: JSONException) {
                                null
                            }
                        val theme =
                            if (jsonObject != null) {
                                ThemeEngine.parseTheme(requireContext(), jsonObject)
                            } else {
                                null
                            }
                        if (theme != null) {
                            installTheme(theme, false)
                        } else {
                            ClickableToast.show(R.string.invalid_data_format)
                        }
                    }
                }
            }
        }

    override fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration = (getRecyclerView()!!.adapter as Adapter).configureDivider(configuration, position)

    private fun updateThemes() {
        val listItems = ArrayList<ListItem>()
        listItems.add(ListItem(null, false, null, true))
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
        adapter.followSystem = Preferences.isThemeFollowSystem
        adapter.dayThemeName = Preferences.theme
        adapter.nightThemeName = Preferences.themeNight
        adapter.notifyDataSetChanged()
    }

    private fun showContextMenu(
        theme: ThemeEngine.Theme,
        installed: Boolean,
    ) {
        val json: String
        try {
            json = theme.toJsonObject().toString(4)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        val editable = installed && !theme.builtIn
        ContextMenuDialog(theme.name, json, editable)
            .show(childFragmentManager, ContextMenuDialog::class.java.name)
    }

    /**
     * Opens the theme editor. With no [originalName] the theme is being duplicated rather than edited,
     * so it needs a name of its own — saving under an existing name would overwrite that theme.
     */
    internal fun openEditor(
        json: String,
        originalName: String?,
    ) {
        val jsonObject =
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                e.printStackTrace()
                ClickableToast.show(R.string.invalid_data_format)
                return
            }
        if (originalName == null) {
            jsonObject.put("name", uniqueName(jsonObject.optString("name")))
        }
        (requireActivity() as FragmentHandler).pushFragment(ThemeEditorFragment(jsonObject, originalName))
    }

    private fun uniqueName(name: String): String {
        val existing = ThemeEngine.getThemes().mapTo(HashSet()) { it.name }
        if (!existing.contains(name)) {
            return name
        }
        var index = 2
        while (existing.contains("$name ($index)")) {
            index++
        }
        return "$name ($index)"
    }

    private fun installTheme(
        theme: ThemeEngine.Theme,
        installed: Boolean,
    ) {
        if (!installed) {
            if (ThemeEngine.addTheme(theme)) {
                updateThemes()
            } else {
                ClickableToast.show(R.string.no_access)
                return
            }
        }
        if (Preferences.isThemeFollowSystem) {
            // Two slots to fill, so the tap alone is ambiguous: let the user say which.
            SelectSlotDialog(theme.name, !installed)
                .show(childFragmentManager, SelectSlotDialog::class.java.name)
        } else {
            selectTheme(theme.name, false, !installed)
        }
    }

    internal fun selectTheme(
        name: String,
        night: Boolean,
        force: Boolean,
    ) {
        val current = if (night) Preferences.themeNight else Preferences.theme
        if (force || name != current) {
            if (night) {
                Preferences.themeNight = name
            } else {
                Preferences.theme = name
            }
            // Recreate only when the slot just changed is the one currently in effect;
            // otherwise a list refresh is enough to move the Day/Night label.
            val inEffect =
                !Preferences.isThemeFollowSystem || night == ThemeEngine.isNightMode(requireContext())
            if (inEffect) {
                requireActivity().recreate()
            } else {
                updateThemes()
            }
        }
    }

    private fun toggleFollowSystem() {
        val enabled = !Preferences.isThemeFollowSystem
        Preferences.isThemeFollowSystem = enabled
        if (enabled && Preferences.themeNight == null) {
            // Without a night theme the switch would be a no-op, which reads as broken.
            // Seed it with the first dark theme so enabling it does something visible.
            val darkTheme = ThemeEngine.getThemes().firstOrNull { it.base == ThemeEngine.Theme.Base.DARK }
            if (darkTheme != null) {
                Preferences.themeNight = darkTheme.name
            }
        }
        if (ThemeEngine.isNightMode(requireContext())) {
            requireActivity().recreate()
        } else {
            updateThemes()
        }
    }

    internal fun deleteTheme(name: String) {
        if (ThemeEngine.deleteTheme(name)) {
            val wasNight = name == Preferences.themeNight
            if (wasNight) {
                Preferences.themeNight = null
            }
            updateThemes()
            if (name == Preferences.theme || wasNight) {
                requireActivity().recreate()
            }
        }
    }

    private class ListItem(
        val theme: ThemeEngine.Theme?,
        val installed: Boolean,
        val title: String?,
        val followSystemCheck: Boolean = false,
    )

    private class Adapter(
        private val context: Context,
        private val callback: Callback,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ListViewUtils.ClickCallback<Unit, RecyclerView.ViewHolder> {
        private enum class ViewType { ITEM, HEADER, CHECK }

        interface Callback {
            fun onThemeClick(
                theme: ThemeEngine.Theme,
                installed: Boolean,
                longClick: Boolean,
            ): Boolean

            fun onFollowSystemClick(): Boolean
        }

        private class ItemViewHolder(
            val holder: Preference.Runtime.IconViewHolder,
        ) : RecyclerView.ViewHolder(holder.view) {
            init {
                ViewUtils.setSelectableItemBackground(itemView)
            }
        }

        private class CheckViewHolder(
            val holder: CheckPreference.CheckViewHolder,
        ) : RecyclerView.ViewHolder(holder.view) {
            init {
                ViewUtils.setSelectableItemBackground(itemView)
            }
        }

        private val iconPreference: Preference.Runtime<Any?> =
            Preference.Runtime(context, "", null, "title") { null }

        private val followSystemPreference =
            CheckPreference(
                context,
                Preferences.KEY_THEME_FOLLOW_SYSTEM,
                Preferences.DEFAULT_THEME_FOLLOW_SYSTEM,
                context.getString(R.string.follow_system_day_night),
                context.getString(R.string.follow_system_day_night__summary),
            )

        internal var listItems: List<ListItem> = emptyList()
        internal var followSystem: Boolean = false
        internal var dayThemeName: String? = null
        internal var nightThemeName: String? = null

        fun configureDivider(
            configuration: DividerItemDecoration.Configuration,
            position: Int,
        ): DividerItemDecoration.Configuration {
            val next = if (listItems.size > position + 1) listItems[position + 1] else null
            return configuration.need(
                listItems[position].followSystemCheck || (next != null && next.title != null),
            )
        }

        override fun getItemCount(): Int = listItems.size

        override fun getItemViewType(position: Int): Int {
            val listItem = listItems[position]
            return (
                when {
                    listItem.followSystemCheck -> ViewType.CHECK
                    listItem.title != null -> ViewType.HEADER
                    else -> ViewType.ITEM
                }
            ).ordinal
        }

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            val listItem = listItems[position]
            if (listItem.followSystemCheck) {
                return !longClick && callback.onFollowSystemClick()
            }
            return callback.onThemeClick(listItem.theme!!, listItem.installed, longClick)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            when (ViewType.values()[viewType]) {
                ViewType.ITEM -> {
                    ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                        ItemViewHolder(iconPreference.createIconViewHolder(parent)),
                        true,
                        null,
                        this,
                    )
                }

                ViewType.HEADER -> {
                    SimpleViewHolder(ViewFactory.makeListTextHeader(parent))
                }

                ViewType.CHECK -> {
                    ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                        CheckViewHolder(followSystemPreference.createViewHolder(parent)),
                        true,
                        null,
                        this,
                    )
                }
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            val listItem = listItems[position]
            when (ViewType.values()[holder.itemViewType]) {
                ViewType.ITEM -> {
                    val viewHolder = (holder as ItemViewHolder).holder
                    val theme = listItem.theme!!
                    viewHolder.icon!!.setImageDrawable(theme.createThemeChoiceDrawable())
                    viewHolder.title!!.text = theme.name
                    val summary = getSlotSummary(theme, listItem.installed)
                    viewHolder.summary!!.text = summary
                    viewHolder.summary.visibility = if (summary != null) View.VISIBLE else View.GONE
                }

                ViewType.HEADER -> {
                    (holder.itemView as TextView).text = listItem.title
                }

                ViewType.CHECK -> {
                    followSystemPreference.value = followSystem
                    followSystemPreference.bindViewHolder((holder as CheckViewHolder).holder)
                }
            }
        }

        // Which day/night slot a theme occupies -- only meaningful while the switch is on.
        private fun getSlotSummary(
            theme: ThemeEngine.Theme,
            installed: Boolean,
        ): CharSequence? {
            if (!followSystem || !installed) {
                return null
            }
            val day = theme.name == dayThemeName
            val night = theme.name == nightThemeName
            return when {
                day && night -> context.getString(R.string.day_and_night_theme)
                day -> context.getString(R.string.day_theme)
                night -> context.getString(R.string.night_theme)
                else -> null
            }
        }
    }

    class SelectSlotDialog : DialogFragment {
        constructor()

        constructor(name: String, force: Boolean) {
            val args = Bundle()
            args.putString(EXTRA_NAME, name)
            args.putBoolean(EXTRA_FORCE, force)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val dialogMenu = DialogMenu(requireContext())
            dialogMenu.add(R.string.day_theme) { select(false) }
            dialogMenu.add(R.string.night_theme) { select(true) }
            return dialogMenu.create()
        }

        private fun select(night: Boolean) {
            val name = requireArguments().getString(EXTRA_NAME)!!
            val force = requireArguments().getBoolean(EXTRA_FORCE)
            val themesFragment = parentFragment as ThemesFragment
            themesFragment.requireView().post { themesFragment.selectTheme(name, night, force) }
        }

        companion object {
            private const val EXTRA_NAME = "name"
            private const val EXTRA_FORCE = "force"
        }
    }

    class ContextMenuDialog : DialogFragment {
        constructor()

        constructor(name: String?, json: String, editable: Boolean) {
            val args = Bundle()
            args.putString(EXTRA_NAME, name)
            args.putString(EXTRA_JSON, json)
            args.putBoolean(EXTRA_EDITABLE, editable)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val name = requireArguments().getString(EXTRA_NAME)
            val dialogMenu = DialogMenu(requireContext())
            if (requireArguments().getBoolean(EXTRA_EDITABLE)) {
                dialogMenu.add(R.string.edit) { openEditor(name) }
            }
            // A built-in or not yet installed theme can't be edited in place, but it makes a fine
            // starting point: duplicating it hands the editor a renamed copy.
            dialogMenu.add(R.string.duplicate) { openEditor(null) }
            dialogMenu.add(R.string.copy) {
                val json = requireArguments().getString(EXTRA_JSON)
                if (!json.isNullOrEmpty()) {
                    StringUtils.copyToClipboard(requireContext(), json)
                    ClickableToast.show(R.string.copied_to_clipboard)
                }
            }
            dialogMenu.add(R.string.save) {
                val binder = (requireActivity() as FragmentHandler).getDownloadBinder()
                if (binder != null) {
                    val json = requireArguments().getString(EXTRA_JSON)
                    binder.downloadStorage(
                        ByteArrayInputStream(json!!.toByteArray()),
                        null,
                        null,
                        null,
                        null,
                        "$name.json",
                        false,
                        true,
                    )
                }
            }
            if (requireArguments().getBoolean(EXTRA_EDITABLE)) {
                dialogMenu.add(R.string.delete) {
                    val themesFragment = parentFragment as ThemesFragment
                    themesFragment.requireView().post {
                        themesFragment.deleteTheme(requireArguments().getString(EXTRA_NAME)!!)
                    }
                }
            }
            return dialogMenu.create()
        }

        private fun openEditor(originalName: String?) {
            val json = requireArguments().getString(EXTRA_JSON) ?: return
            val themesFragment = parentFragment as ThemesFragment
            themesFragment.requireView().post { themesFragment.openEditor(json, originalName) }
        }

        companion object {
            private const val EXTRA_NAME = "name"
            private const val EXTRA_JSON = "json"
            private const val EXTRA_EDITABLE = "editable"
        }
    }

    class ThemesViewModel : TaskViewModel<ReadThemesTask, Pair<ErrorItem, List<JSONObject>>>()

    class ReadThemesTask(
        private val viewModel: ThemesViewModel,
    ) : HttpHolderTask<Unit, Pair<ErrorItem, List<JSONObject>>>(Chan.getFallback()) {
        override fun run(holder: HttpHolder): Pair<ErrorItem, List<JSONObject>> {
            try {
                var uri = Chan.getFallback().locator.setSchemeIfEmpty(Uri.parse(Preferences.uriThemes), null)
                var redirects = 0
                while (redirects++ < 5) {
                    val responseString =
                        HttpRequest(uri, holder).perform()!!.readString()
                            ?: return Pair(ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null)
                    val jsonObject = JSONObject(responseString)
                    val redirect = CommonUtils.optJsonString(jsonObject, "redirect")
                    if (redirect != null) {
                        uri = ReadUpdateTask.normalizeRelativeUri(uri!!, redirect)
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
