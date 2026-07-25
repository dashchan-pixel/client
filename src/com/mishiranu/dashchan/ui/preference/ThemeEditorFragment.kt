package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.graphics.ColorSwatchDrawable
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ColorPickerView
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ThemePreviewView
import com.mishiranu.dashchan.widget.ViewFactory
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Builds a theme by hand instead of importing one: every colour slot the theme format understands,
 * each with a picker, over a live mock of a thread.
 *
 * The edited state is kept as name + base + explicitly set colours rather than as a
 * [ThemeEngine.Theme], because a Theme has already had its optional slots filled in from the base
 * theme's defaults — the editor has to keep "not set" distinct from "happens to equal the default"
 * so an untouched slot keeps following the base theme.
 */
class ThemeEditorFragment : BaseListFragment {
    constructor()

    constructor(json: JSONObject?, originalName: String?) {
        val args = Bundle()
        args.putString(EXTRA_JSON, json?.toString())
        args.putString(EXTRA_ORIGINAL_NAME, originalName)
        arguments = args
    }

    private var themeName = ""
    private var base = ThemeEngine.Theme.Base.LIGHT
    private val colors = LinkedHashMap<String, Int>()
    private var changed = false

    /** The edited state as a Theme: what the preview draws and what the swatches read. */
    private var resolvedTheme: ThemeEngine.Theme? = null

    /** The installed theme this editor replaces on save, or `null` when creating a new one. */
    private val originalName: String?
        get() = arguments?.getString(EXTRA_ORIGINAL_NAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeName = getString(R.string.new_theme)
        changed = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_CHANGED)
        val json = savedInstanceState?.getString(EXTRA_JSON) ?: arguments?.getString(EXTRA_JSON)
        if (json != null) {
            load(json)
        }
    }

    private fun load(json: String) {
        val jsonObject =
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                e.printStackTrace()
                return
            }
        // Parsing resolves "@slot" references and the per-base defaults, so the editor starts from
        // concrete colours rather than from indirection it has no way to show or edit.
        val theme = ThemeEngine.parseTheme(requireContext(), jsonObject) ?: return
        themeName = theme.name
        base = theme.base ?: base
        colors.clear()
        for (slot in SLOTS) {
            if (!StringUtils.isEmpty(jsonObject.optString(slot.key))) {
                colors[slot.key] = theme.getColor(slot.key)
            }
        }
    }

    private fun buildJson(): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put(KEY_BASE, if (base == ThemeEngine.Theme.Base.DARK) VALUE_DARK else VALUE_LIGHT)
        jsonObject.put(KEY_NAME, themeName)
        for (slot in SLOTS) {
            val color = colors[slot.key]
            if (color != null) {
                jsonObject.put(slot.key, formatColor(color))
            }
        }
        return jsonObject
    }

    private fun resolveTheme(): ThemeEngine.Theme? {
        var resolvedTheme = this.resolvedTheme
        if (resolvedTheme == null) {
            resolvedTheme = ThemeEngine.parseTheme(requireContext(), buildJson())
            this.resolvedTheme = resolvedTheme
        }
        return resolvedTheme
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(EXTRA_JSON, buildJson().toString())
        outState.putBoolean(EXTRA_CHANGED, changed)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        updateTitle()
        val recyclerView = getRecyclerView() ?: return
        recyclerView.adapter = Adapter()
    }

    private fun updateTitle() {
        val titleResId = if (originalName != null) R.string.edit_theme else R.string.new_theme
        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(titleResId), themeName)
    }

    override fun configureDivider(
        configuration: DividerItemDecoration.Configuration,
        position: Int,
    ): DividerItemDecoration.Configuration {
        val next = if (position + 1 < LIST_ITEMS.size) LIST_ITEMS[position + 1] else null
        return configuration.need(next != null && next.viewType == ViewType.HEADER)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_save, 0, R.string.save)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionSave))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_save) {
            save()
            return true
        }
        return super.onMenuItemSelected(item)
    }

    override val isBackHandled: Boolean
        get() = changed

    override fun onBackPressed(): Boolean {
        if (changed) {
            DiscardDialog().show(childFragmentManager, DiscardDialog::class.java.name)
            return true
        }
        return false
    }

    private fun markChanged() {
        resolvedTheme = null
        if (!changed) {
            changed = true
            notifyBackHandledChanged()
        }
        getRecyclerView()?.adapter?.notifyDataSetChanged()
    }

    internal fun setName(name: String) {
        themeName = name
        updateTitle()
        markChanged()
    }

    internal fun setBase(base: ThemeEngine.Theme.Base) {
        this.base = base
        markChanged()
    }

    internal fun setColor(
        slot: String,
        color: Int?,
    ) {
        if (color != null) {
            colors[slot] = color
        } else {
            colors.remove(slot)
        }
        markChanged()
    }

    internal fun discard() {
        changed = false
        notifyBackHandledChanged()
        (requireActivity() as FragmentHandler).removeFragment()
    }

    private fun save() {
        if (StringUtils.isEmpty(themeName.trim())) {
            ClickableToast.show(R.string.enter_valid_data)
            return
        }
        val theme = resolveTheme()
        if (theme == null) {
            ClickableToast.show(R.string.invalid_data_format)
            return
        }
        // Checked before anything is written: a built-in theme can't be replaced, and finding that
        // out after deleting the theme being renamed would lose it.
        if (ThemeEngine.getThemes().any { it.name == theme.name && it.builtIn }) {
            ClickableToast.show(R.string.no_access)
            return
        }
        val originalName = this.originalName
        val renamed = originalName != null && originalName != theme.name
        if (renamed) {
            ThemeEngine.deleteTheme(originalName)
        }
        if (!ThemeEngine.addTheme(theme)) {
            ClickableToast.show(R.string.no_access)
            return
        }
        if (renamed) {
            // A renamed theme must take its day/night slots with it, or the rename would silently
            // reset whichever slot pointed at the old name.
            if (Preferences.theme == originalName) {
                Preferences.theme = theme.name
            }
            if (Preferences.themeNight == originalName) {
                Preferences.themeNight = theme.name
            }
        }
        changed = false
        notifyBackHandledChanged()
        val activity = requireActivity()
        (activity as FragmentHandler).removeFragment()
        if (theme.name == currentThemeName()) {
            activity.recreate()
        }
    }

    /** The name of the theme currently on screen — the night slot only when it is in effect. */
    private fun currentThemeName(): String? =
        if (Preferences.isThemeFollowSystem && ThemeEngine.isNightMode(requireContext())) {
            Preferences.themeNight
        } else {
            Preferences.theme
        }

    private enum class ViewType { PREVIEW, HEADER, TEXT, COLOR }

    private class ListItem(
        val viewType: ViewType,
        val key: String?,
        val titleResId: Int,
    )

    private class PreviewViewHolder(
        val preview: ThemePreviewView,
    ) : RecyclerView.ViewHolder(preview) {
        init {
            // LinearLayoutManager's default params are WRAP_CONTENT in both directions, which would
            // leave the mock as narrow as its longest label instead of spanning the list.
            preview.layoutParams =
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
    }

    private class TextViewHolder(
        val holder: Preference.ViewHolder,
    ) : RecyclerView.ViewHolder(holder.view) {
        init {
            ViewUtils.setSelectableItemBackground(itemView)
        }
    }

    private class ColorViewHolder(
        val holder: Preference.Runtime.IconViewHolder,
    ) : RecyclerView.ViewHolder(holder.view) {
        init {
            ViewUtils.setSelectableItemBackground(itemView)
        }
    }

    private inner class Adapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ListViewUtils.ClickCallback<Unit, RecyclerView.ViewHolder> {
        private val iconPreference: Preference.Runtime<Any?> =
            Preference.Runtime(requireContext(), "", null, "title") { null }

        override fun getItemCount(): Int = LIST_ITEMS.size

        override fun getItemViewType(position: Int): Int = LIST_ITEMS[position].viewType.ordinal

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            if (longClick) {
                return false
            }
            onListItemClick(LIST_ITEMS[position])
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            when (ViewType.entries[viewType]) {
                ViewType.PREVIEW -> PreviewViewHolder(ThemePreviewView(parent.context))

                ViewType.HEADER -> SimpleViewHolder(ViewFactory.makeListTextHeader(parent))

                ViewType.TEXT ->
                    ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                        TextViewHolder(iconPreference.createViewHolder(parent)),
                        false,
                        null,
                        this,
                    )

                ViewType.COLOR ->
                    ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                        ColorViewHolder(iconPreference.createIconViewHolder(parent)),
                        false,
                        null,
                        this,
                    )
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            val listItem = LIST_ITEMS[position]
            when (ViewType.entries[holder.itemViewType]) {
                ViewType.PREVIEW -> resolveTheme()?.let { (holder as PreviewViewHolder).preview.setTheme(it) }

                ViewType.HEADER -> (holder.itemView as TextView).setText(listItem.titleResId)

                ViewType.TEXT -> bindText((holder as TextViewHolder).holder, listItem)

                ViewType.COLOR -> bindColor((holder as ColorViewHolder).holder, listItem)
            }
        }

        private fun bindText(
            holder: Preference.ViewHolder,
            listItem: ListItem,
        ) {
            holder.title?.setText(listItem.titleResId)
            val summary =
                if (listItem.key == KEY_NAME) {
                    themeName
                } else {
                    getString(if (base == ThemeEngine.Theme.Base.DARK) R.string.theme_base_dark else R.string.theme_base_light)
                }
            holder.summary?.text = summary
            holder.summary?.visibility = View.VISIBLE
        }

        private fun bindColor(
            holder: Preference.Runtime.IconViewHolder,
            listItem: ListItem,
        ) {
            val slot = listItem.key ?: return
            val color = colors[slot]
            val effective = color ?: resolveTheme()?.getColor(slot)
            holder.icon?.setImageDrawable(if (effective != null) ColorSwatchDrawable(effective) else null)
            holder.title?.setText(listItem.titleResId)
            holder.summary?.text =
                if (color != null) formatColor(color).uppercase(Locale.US) else getString(R.string.theme_color_default)
            holder.summary?.visibility = View.VISIBLE
        }
    }

    private fun onListItemClick(listItem: ListItem) {
        when (listItem.viewType) {
            ViewType.TEXT ->
                if (listItem.key == KEY_NAME) {
                    NameDialog(themeName).show(childFragmentManager, NameDialog::class.java.name)
                } else {
                    BaseDialog().show(childFragmentManager, BaseDialog::class.java.name)
                }

            ViewType.COLOR -> {
                val slot = listItem.key ?: return
                val color = colors[slot] ?: resolveTheme()?.getColor(slot) ?: return
                ColorDialog(slot, listItem.titleResId, color, colors.containsKey(slot))
                    .show(childFragmentManager, ColorDialog::class.java.name)
            }

            else -> Unit
        }
    }

    class NameDialog : DialogFragment {
        constructor()

        constructor(name: String) {
            val args = Bundle()
            args.putString(EXTRA_NAME, name)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val context = requireContext()
            val editText = EditText(context)
            editText.isSingleLine = true
            editText.setText(requireArguments().getString(EXTRA_NAME))
            editText.setSelection(editText.text.length)
            ThemeEngine.applyStyle(editText)
            val container = LinearLayout(context)
            val padding = (20f * ResourceUtils.obtainDensity(context)).toInt()
            container.setPadding(padding, padding / 2, padding, 0)
            container.addView(
                editText,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            return AlertDialog
                .Builder(context)
                .setTitle(R.string.name)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> apply(editText.text.toString()) }
                .create()
        }

        private fun apply(name: String) {
            if (StringUtils.isEmpty(name.trim())) {
                ClickableToast.show(R.string.enter_valid_data)
                return
            }
            val fragment = parentFragment as ThemeEditorFragment
            fragment.requireView().post { fragment.setName(name.trim()) }
        }

        companion object {
            private const val EXTRA_NAME = "name"
        }
    }

    class BaseDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val dialogMenu = DialogMenu(requireContext())
            dialogMenu.setTitle(getString(R.string.theme_base))
            dialogMenu.add(R.string.theme_base_light) { apply(ThemeEngine.Theme.Base.LIGHT) }
            dialogMenu.add(R.string.theme_base_dark) { apply(ThemeEngine.Theme.Base.DARK) }
            return dialogMenu.create()
        }

        private fun apply(base: ThemeEngine.Theme.Base) {
            val fragment = parentFragment as ThemeEditorFragment
            fragment.requireView().post { fragment.setBase(base) }
        }
    }

    class ColorDialog : DialogFragment {
        constructor()

        constructor(slot: String, titleResId: Int, color: Int, hasValue: Boolean) {
            val args = Bundle()
            args.putString(EXTRA_SLOT, slot)
            args.putInt(EXTRA_TITLE, titleResId)
            args.putInt(EXTRA_COLOR, color)
            args.putBoolean(EXTRA_HAS_VALUE, hasValue)
            arguments = args
        }

        private var picker: ColorPickerView? = null

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val context = requireContext()
            val picker = ColorPickerView(context)
            this.picker = picker
            val hexView = EditText(context)
            hexView.isSingleLine = true
            hexView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hexView.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(9))
            ThemeEngine.applyStyle(hexView)
            picker.color =
                if (savedInstanceState != null) {
                    savedInstanceState.getInt(EXTRA_COLOR)
                } else {
                    requireArguments().getInt(EXTRA_COLOR)
                }
            hexView.setText(formatColor(picker.color).uppercase(Locale.US))
            link(picker, hexView)
            val builder =
                AlertDialog
                    .Builder(context)
                    .setTitle(requireArguments().getInt(EXTRA_TITLE))
                    .setView(createContent(picker, hexView))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> apply(picker.color) }
            if (requireArguments().getBoolean(EXTRA_HAS_VALUE)) {
                builder.setNeutralButton(R.string.theme_color_default) { _, _ -> apply(null) }
            }
            return builder.create()
        }

        private fun createContent(
            picker: ColorPickerView,
            hexView: EditText,
        ): View {
            val context = requireContext()
            val layout = LinearLayout(context)
            layout.orientation = LinearLayout.VERTICAL
            val padding = (20f * ResourceUtils.obtainDensity(context)).toInt()
            layout.setPadding(padding, padding / 2, padding, 0)
            layout.addView(
                picker,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layout.addView(
                hexView,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            return layout
        }

        /** Two-way binding between the picker and the hex field, guarded against feeding back. */
        private fun link(
            picker: ColorPickerView,
            hexView: EditText,
        ) {
            var updating = false
            picker.onColorChangedListener =
                ColorPickerView.OnColorChangedListener { color ->
                    if (!updating) {
                        updating = true
                        hexView.setText(formatColor(color).uppercase(Locale.US))
                        updating = false
                    }
                }
            hexView.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {}

                    override fun afterTextChanged(s: Editable?) {
                        val color = parseColor(s?.toString())
                        if (color != null && !updating) {
                            updating = true
                            picker.color = color
                            updating = false
                        }
                    }
                },
            )
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            picker?.let { outState.putInt(EXTRA_COLOR, it.color) }
        }

        override fun onDestroyView() {
            super.onDestroyView()

            picker = null
        }

        private fun apply(color: Int?) {
            val slot = requireArguments().getString(EXTRA_SLOT) ?: return
            val fragment = parentFragment as ThemeEditorFragment
            fragment.requireView().post { fragment.setColor(slot, color) }
        }

        companion object {
            private const val EXTRA_SLOT = "slot"
            private const val EXTRA_TITLE = "title"
            private const val EXTRA_COLOR = "color"
            private const val EXTRA_HAS_VALUE = "hasValue"
        }
    }

    class DiscardDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
            AlertDialog
                .Builder(requireContext())
                .setMessage(R.string.discard_changes__sentence)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val fragment = parentFragment as ThemeEditorFragment
                    fragment.requireView().post { fragment.discard() }
                }.create()
    }

    private class Slot(
        val key: String,
        val titleResId: Int,
    )

    companion object {
        private const val EXTRA_JSON = "json"
        private const val EXTRA_ORIGINAL_NAME = "originalName"
        private const val EXTRA_CHANGED = "changed"

        private const val KEY_NAME = "name"
        private const val KEY_BASE = "base"
        private const val VALUE_LIGHT = "light"
        private const val VALUE_DARK = "dark"

        /**
         * Every colour the theme format understands, in the order the editor lists them: surfaces
         * first, then text. "thread" is deliberately absent — it is derived from "post" and the base
         * theme's alpha, not stored.
         */
        private val SLOTS =
            listOf(
                Slot("window", R.string.theme_color_window),
                Slot("primary", R.string.theme_color_primary),
                Slot("accent", R.string.theme_color_accent),
                Slot("card", R.string.theme_color_card),
                Slot("highlight", R.string.theme_color_highlight),
                Slot("spoiler", R.string.theme_color_spoiler),
                Slot("post", R.string.theme_color_post),
                Slot("meta", R.string.theme_color_meta),
                Slot("link", R.string.theme_color_link),
                Slot("quote", R.string.theme_color_quote),
                Slot("tripcode", R.string.theme_color_tripcode),
                Slot("capcode", R.string.theme_color_capcode),
                Slot("neuroslop", R.string.theme_color_neuroslop),
                Slot("neuroslopQuote", R.string.theme_color_neuroslop_quote),
            )

        private val LIST_ITEMS =
            listOf(
                ListItem(ViewType.PREVIEW, null, 0),
                ListItem(ViewType.TEXT, KEY_NAME, R.string.name),
                ListItem(ViewType.TEXT, KEY_BASE, R.string.theme_base),
                ListItem(ViewType.HEADER, null, R.string.colors),
            ) + SLOTS.map { ListItem(ViewType.COLOR, it.key, it.titleResId) }

        private fun formatColor(color: Int): String = String.format(Locale.US, "#%08x", color)

        /** Accepts what the theme format writes: `#RRGGBB` or `#AARRGGBB`, with or without the hash. */
        private fun parseColor(text: String?): Int? {
            val value = text?.trim()?.removePrefix("#") ?: return null
            if (value.length != 6 && value.length != 8) {
                return null
            }
            val parsed = value.toLongOrNull(16) ?: return null
            return if (value.length == 8) parsed.toInt() else parsed.toInt() or 0xff000000.toInt()
        }
    }
}
