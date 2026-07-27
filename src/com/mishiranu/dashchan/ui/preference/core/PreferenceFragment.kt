package com.mishiranu.dashchan.ui.preference.core

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.ContentFragment
import com.mishiranu.dashchan.ui.preference.PreferenceSearch
import com.mishiranu.dashchan.ui.preference.core.MultipleEditPreference.ValueCodec
import com.mishiranu.dashchan.ui.preference.core.Preference.SummaryProvider
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.setRoundedSelectableItemBackground
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory.makeListTextHeader
import java.util.Collections

abstract class PreferenceFragment : ContentFragment() {
    private val preferences = ArrayList<Preference<*>>()
    private val persistent = HashSet<Preference<*>?>()
    private val dependencies = ArrayList<Dependency>()

    private var recyclerView: RecyclerView? = null

    private abstract class Dependency(
        val key: String,
        val dependencyKey: String,
        val positive: Boolean,
    ) {
        abstract fun checkDependency(dependencyPreference: Preference<*>?): Boolean
    }

    private class BooleanDependency(
        key: String,
        dependencyKey: String,
        positive: Boolean,
    ) : Dependency(key, dependencyKey, positive) {
        override fun checkDependency(dependencyPreference: Preference<*>?): Boolean {
            if (dependencyPreference is CheckPreference) {
                return dependencyPreference.value == positive
            }
            return false
        }
    }

    private class StringDependency(
        key: String,
        dependencyKey: String,
        positive: Boolean,
        vararg values: String?,
    ) : Dependency(key, dependencyKey, positive) {
        private val values = HashSet<String?>()

        init {
            Collections.addAll<String?>(this.values, *values)
        }

        override fun checkDependency(dependencyPreference: Preference<*>?): Boolean {
            val value: String?
            if (dependencyPreference is EditPreference) {
                value = dependencyPreference.value
            } else if (dependencyPreference is ListPreference) {
                value = dependencyPreference.value
            } else {
                return false
            }
            return values.contains(value) == positive
        }
    }

    protected abstract fun getPreferences(): SharedPreferences

    /**
     * The list adapter, which [onCreateView] sets and [onDestroyView] drops together with
     * [recyclerView]. Every caller runs between the two, so both fields are non-null there.
     */
    private val listAdapter: RecyclerView.Adapter<*>
        get() = recyclerView!!.getAdapter()!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val recyclerView = PaddedRecyclerView(container!!.getContext())
        this.recyclerView = recyclerView
        recyclerView.setId(android.R.id.list)
        recyclerView.setMotionEventSplittingEnabled(false)
        recyclerView.setVerticalScrollBarEnabled(true)
        recyclerView.setClipToPadding(false)
        recyclerView.setLayoutManager(LinearLayoutManager(container.getContext()))
        recyclerView.setAdapter(Adapter())
        recyclerView.addItemDecoration(
            DividerItemDecoration(
                recyclerView.getContext(),
                DividerItemDecoration.Callback { c: DividerItemDecoration.Configuration?, position: Int ->
                    val current = preferences[position]
                    val next =
                        if (preferences.size > position + 1) preferences[position + 1] else null
                    var need =
                        current !is HeaderPreference &&
                            (next !is HeaderPreference || true)
                    if (need) {
                        need = current !is CategoryPreference && next !is CategoryPreference
                    }
                    c!!.need(need)
                },
            ),
        )
        val layout = ExpandedLayout(container.getContext(), true)
        layout.addView(
            recyclerView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return layout
    }

    public override fun onDestroyView() {
        super.onDestroyView()

        preferences.clear()
        persistent.clear()
        dependencies.clear()
        recyclerView = null
    }

    override fun onStart() {
        super.onStart()

        // Subclasses add their rows in onViewCreated, which has run by now.
        val revealTitle = arguments?.getString(EXTRA_REVEAL_TITLE)
        if (revealTitle != null) {
            // One-shot: dropping the argument keeps a rotation, or a return from a child screen,
            // from flashing the row all over again.
            requireArguments().remove(EXTRA_REVEAL_TITLE)
            revealPreference(revealTitle)
        }
        if (BuildConfig.DEBUG) {
            // The framework knowing about the app-level index is a deliberate shortcut: onStart is
            // the only hook every preference screen shares, and this check exists to fail during
            // development rather than to ship.
            PreferenceSearch.checkIndexed(this, searchableTitles())
        }
    }

    /**
     * Titles of the rows a user could search for -- settings and action buttons, but not the section
     * headers or the navigation categories, which are structure rather than settings.
     */
    internal fun searchableTitles(): List<CharSequence> =
        preferences
            .filter {
                val viewType = it.getViewType()
                viewType != Preference.ViewType.HEADER && viewType != Preference.ViewType.CATEGORY
            }.mapNotNull { it.title }

    /** Scrolls the row titled [title] into view and pulses it, so the search hit is obvious. */
    private fun revealPreference(title: String) {
        val recyclerView = this.recyclerView ?: return
        // Deferred, and the row is looked up again on each step: a screen can still drop rows after
        // onStart -- ChanFragment removes its "Manage cookies" row in onResume -- so an index taken
        // here would point at the wrong row by the time it is used.
        recyclerView.post {
            val index = indexOfTitle(title)
            if (index >= 0) {
                (recyclerView.getLayoutManager() as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
                flashPreference(recyclerView, title, FLASH_ATTEMPTS)
            }
        }
    }

    private fun flashPreference(
        recyclerView: RecyclerView,
        title: String,
        attemptsLeft: Int,
    ) {
        recyclerView.post {
            val index = indexOfTitle(title)
            val itemView =
                if (index >= 0) recyclerView.findViewHolderForAdapterPosition(index)?.itemView else null
            if (itemView != null) {
                ViewUtils.flashRoundedHighlight(itemView, Preferences.uiCornerRadius)
            } else if (attemptsLeft > 1) {
                // scrollToPositionWithOffset only requests a layout pass; until it has run there is
                // no holder for the target row yet.
                flashPreference(recyclerView, title, attemptsLeft - 1)
            }
        }
    }

    private fun indexOfTitle(title: String): Int = preferences.indexOfFirst { it.title?.toString() == title }

    private fun <T> onChange(
        preference: Preference<T>,
        newValue: Boolean,
    ) {
        if (newValue) {
            if (persistent.contains(preference)) {
                preference.persist(getPreferences())
            }
            onPreferenceAfterChange(preference)
            preference.notifyAfterChange()
        }
        val index = preferences.indexOf(preference)
        if (index >= 0) {
            listAdapter.notifyItemChanged(index, SimpleViewHolder.EMPTY_PAYLOAD)
        }
    }

    fun <T> addPreference(
        preference: Preference<T>,
        persistent: Boolean,
    ) {
        val index = preferences.size
        preferences.add(preference)
        if (preference.key != null && persistent) {
            preference.extract(getPreferences())
            this.persistent.add(preference)
        }
        preference.setOnChangeListener { newValue -> onChange(preference, newValue) }
        if (recyclerView != null) {
            listAdapter.notifyItemInserted(index)
        }
    }

    fun movePreference(
        which: Preference<*>?,
        after: Preference<*>?,
    ) {
        val removeIndex = preferences.indexOf(which)
        check(removeIndex >= 0)
        preferences.removeAt(removeIndex)
        listAdapter.notifyItemRemoved(removeIndex)
        val index = preferences.indexOf(after) + 1
        preferences.add(index, which!!)
        listAdapter.notifyItemInserted(index)
    }

    fun <T> addDialogPreference(preference: Preference<T>) {
        addPreference(preference, true)
        preference.setOnClickListener { p ->
            PreferenceDialog(p.key).show(
                getChildFragmentManager(),
                PreferenceDialog::class.java.getName(),
            )
        }
    }

    fun removeAllPreferences() {
        preferences.clear()
        persistent.clear()
        listAdapter.notifyDataSetChanged()
    }

    fun removePreference(preference: Preference<*>?): Int {
        val index = preferences.indexOf(preference)
        if (index >= 0) {
            preferences.removeAt(index)
            persistent.remove(preference)
            listAdapter.notifyItemRemoved(index)
        }
        return preferences.size
    }

    fun addHeader(titleResId: Int): Preference<Void?> {
        val preference: Preference<Void?> =
            HeaderPreference(requireContext(), getString(titleResId))
        addPreference(preference, false)
        return preference
    }

    fun addButton(
        titleResId: Int,
        summaryResId: Int,
    ): Preference<Void?> =
        addButton(
            if (titleResId != 0) getString(titleResId) else null,
            if (summaryResId != 0) getString(summaryResId) else null,
        )

    fun addButton(
        title: CharSequence?,
        summary: CharSequence?,
    ): Preference<Void?> = addButton(title, SummaryProvider { p: Preference<Void?> -> summary })

    fun addButton(
        title: CharSequence?,
        summaryProvider: SummaryProvider<Void?>?,
    ): Preference<Void?> {
        val preference: Preference<Void?> =
            ButtonPreference(requireContext(), title, summaryProvider)
        addPreference(preference, false)
        return preference
    }

    fun addCategory(titleResId: Int): Preference<Void?> = addCategory(getString(titleResId), null)

    fun addCategory(
        titleResId: Int,
        iconResId: Int,
    ): Preference<Void?> =
        addCategory(
            getString(titleResId),
            ContextCompat.getDrawable(requireContext(), iconResId),
        )

    fun addCategory(
        title: CharSequence?,
        icon: Drawable?,
    ): Preference<Void?> {
        val preference: Preference<Void?> = CategoryPreference(requireContext(), title, icon)
        addPreference(preference, false)
        return preference
    }

    fun setCategoryTint(
        preference: Preference<Void?>?,
        tintList: ColorStateList?,
    ) {
        require(preference is CategoryPreference)
        preference.setTint(tintList)
    }

    fun addCheck(
        persistent: Boolean,
        key: String,
        defaultValue: Boolean,
        titleResId: Int,
        summaryResId: Int,
    ): CheckPreference =
        addCheck(
            persistent,
            key,
            defaultValue,
            if (titleResId != 0) getString(titleResId) else null,
            if (summaryResId != 0) getString(summaryResId) else null,
        )

    fun addCheck(
        persistent: Boolean,
        key: String,
        defaultValue: Boolean,
        title: CharSequence?,
        summary: CharSequence?,
    ): CheckPreference {
        val preference = CheckPreference(requireContext(), key, defaultValue, title, summary)
        addPreference(preference, persistent)
        preference.setOnClickListener { p ->
            p.value = !p.value!!
        }
        return preference
    }

    fun addEdit(
        key: String,
        defaultValue: String?,
        titleResId: Int,
        hint: CharSequence?,
        inputType: Int,
    ): EditPreference =
        addEdit(
            key,
            defaultValue,
            titleResId,
            SummaryProvider { p: Preference<String> ->
                var summary: CharSequence? = p.value
                if (summary == null || summary.length == 0) {
                    summary = (p as EditPreference).hint
                }
                summary
            },
            hint,
            inputType,
        )

    fun addEdit(
        key: String,
        defaultValue: String?,
        titleResId: Int,
        summaryResId: Int,
        hint: CharSequence?,
        inputType: Int,
    ): EditPreference =
        addEdit(
            key,
            defaultValue,
            titleResId,
            SummaryProvider { p: Preference<String> ->
                if (summaryResId != 0) {
                    getString(
                        summaryResId,
                    )
                } else {
                    null
                }
            },
            hint,
            inputType,
        )

    fun addEdit(
        key: String,
        defaultValue: String?,
        titleResId: Int,
        summaryProvider: SummaryProvider<String>?,
        hint: CharSequence?,
        inputType: Int,
    ): EditPreference {
        val preference =
            EditPreference(
                requireContext(),
                key,
                defaultValue,
                getString(titleResId),
                summaryProvider,
                hint,
                inputType,
            )
        addDialogPreference(preference)
        return preference
    }

    fun createInputTypes(
        count: Int,
        inputType: Int,
    ): MutableList<Int> {
        val inputTypes = ArrayList<Int>(count)
        repeat(count) {
            inputTypes.add(inputType)
        }
        return inputTypes
    }

    fun <T> addMultipleEdit(
        key: String?,
        titleResId: Int,
        summaryResId: Int,
        hints: List<CharSequence?>?,
        inputTypes: List<Int>?,
        valueCodec: ValueCodec<T>,
    ): MultipleEditPreference<T> =
        addMultipleEdit(
            key,
            titleResId,
            SummaryProvider { p: Preference<T> -> if (summaryResId != 0) getString(summaryResId) else null },
            hints,
            inputTypes,
            valueCodec,
        )

    fun <T> addMultipleEdit(
        key: String?,
        titleResId: Int,
        summaryPattern: String?,
        hints: List<CharSequence?>?,
        inputTypes: List<Int>?,
        valueCodec: ValueCodec<T>,
    ): MultipleEditPreference<T> =
        addMultipleEdit(
            key,
            titleResId,
            SummaryProvider { p: Preference<T> ->
                MultipleEditPreference.formatValues(
                    valueCodec,
                    summaryPattern,
                    p.value,
                )
            },
            hints,
            inputTypes,
            valueCodec,
        )

    fun <T> addMultipleEdit(
        key: String?,
        titleResId: Int,
        summaryProvider: SummaryProvider<T>?,
        hints: List<CharSequence?>?,
        inputTypes: List<Int>?,
        valueCodec: ValueCodec<T>,
    ): MultipleEditPreference<T> {
        val preference =
            MultipleEditPreference<T>(
                requireContext(),
                key!!,
                getString(titleResId),
                summaryProvider,
                hints,
                inputTypes,
                valueCodec,
            )
        addDialogPreference(preference)
        return preference
    }

    fun addList(
        key: String,
        values: List<String>,
        defaultValue: String?,
        titleResId: Int,
        entries: List<CharSequence>,
    ): ListPreference {
        val preference =
            ListPreference(
                requireContext(),
                key,
                defaultValue,
                getString(titleResId),
                entries,
                values,
            )
        addDialogPreference(preference)
        return preference
    }

    fun addSeek(
        key: String,
        defaultValue: Int,
        titleResId: Int,
        summaryFormatResId: Int,
        specialValue: Pair<Int, Int>?,
        minValue: Int,
        maxValue: Int,
        step: Int,
    ): SeekPreference =
        addSeek(
            key,
            defaultValue,
            if (titleResId != 0) getString(titleResId) else null,
            if (summaryFormatResId != 0) getString(summaryFormatResId) else null,
            if (specialValue != null) {
                Pair(
                    specialValue.first,
                    getString(specialValue.second),
                )
            } else {
                null
            },
            minValue,
            maxValue,
            step,
        )

    fun addSeek(
        key: String,
        defaultValue: Int,
        title: String?,
        summaryFormat: String?,
        specialValue: Pair<Int, String>?,
        minValue: Int,
        maxValue: Int,
        step: Int,
    ): SeekPreference {
        val preference =
            SeekPreference(
                requireContext(),
                key,
                defaultValue,
                title,
                summaryFormat,
                specialValue,
                minValue,
                maxValue,
                step,
            )
        addDialogPreference(preference)
        return preference
    }

    fun addDependency(
        key: String,
        dependencyKey: String,
        positive: Boolean,
    ) {
        val dependency: Dependency = BooleanDependency(key, dependencyKey, positive)
        dependencies.add(dependency)
        updateDependency(dependency)
    }

    fun addDependency(
        key: String,
        dependencyKey: String,
        positive: Boolean,
        vararg values: String?,
    ) {
        val dependency: Dependency = StringDependency(key, dependencyKey, positive, *values)
        dependencies.add(dependency)
        updateDependency(dependency)
    }

    fun interface EnumString<T : Enum<T>> {
        fun getString(value: T): String?
    }

    fun interface EnumStringResource<T : Enum<T>> {
        fun getResourceId(value: T): Int
    }

    fun <T : Enum<T>> enumList(
        enumValues: Array<T>,
        callback: EnumString<T>,
    ): MutableList<String> {
        val list = ArrayList<String>(enumValues.size)
        for (value in enumValues) {
            list.add(callback.getString(value)!!)
        }
        return list
    }

    fun <T : Enum<T>> enumResList(
        enumValues: Array<T>,
        callback: EnumStringResource<T>,
    ): MutableList<CharSequence> {
        val list = ArrayList<CharSequence>(enumValues.size)
        for (value in enumValues) {
            list.add(getString(callback.getResourceId(value)))
        }
        return list
    }

    fun findPreference(key: String): Preference<*>? {
        for (preference in preferences) {
            if (key == preference.key) {
                return preference
            }
        }
        return null
    }

    private fun updateDependency(dependency: Dependency) {
        val dependencyPreference = findPreference(dependency.dependencyKey)
        if (dependencyPreference != null) {
            updateDependency(dependency, dependencyPreference)
        }
    }

    private fun updateDependency(
        dependency: Dependency,
        dependencyPreference: Preference<*>?,
    ) {
        val preference = findPreference(dependency.key)
        if (preference != null) {
            preference.setEnabled(dependency.checkDependency(dependencyPreference))
        }
    }

    private fun onPreferenceAfterChange(preference: Preference<*>) {
        for (dependency in dependencies) {
            if (preference.key == dependency.dependencyKey) {
                updateDependency(dependency, preference)
            }
        }
    }

    fun getDialog(preference: Preference<*>?): AlertDialog? {
        getChildFragmentManager().executePendingTransactions()
        val preferenceDialog =
            getChildFragmentManager()
                .findFragmentByTag(PreferenceDialog::class.java.getName()) as PreferenceDialog?
        return if (preferenceDialog != null && preferenceDialog.preference === preference) {
            preferenceDialog.getDialog() as AlertDialog?
        } else {
            null
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.ViewHolder?>() {
        private inner class ViewHolder(
            itemView: View,
            internal val viewHolder: Preference.ViewHolder,
        ) : RecyclerView.ViewHolder(itemView),
            ClickCallback<Unit, ViewHolder> {
            init {
                ListViewUtils.bind(this, false, null, this)
                if (itemView.getBackground() == null) {
                    // Rounded to Preferences.uiCornerRadius so a row's press-state highlight reads
                    // as a Material 3 list item rather than the stock edge-to-edge ripple.
                    setRoundedSelectableItemBackground(itemView, Preferences.uiCornerRadius)
                }
            }

            override fun onItemClick(
                holder: ViewHolder,
                position: Int,
                item: Unit?,
                longClick: Boolean,
            ): Boolean {
                preferences[position].performClick()
                return true
            }
        }

        private val viewProviders = HashMap<Preference.ViewType?, Preference<*>?>()

        override fun getItemCount(): Int = preferences.size

        override fun getItemViewType(position: Int): Int {
            val preference = preferences[position]
            viewProviders[preference.getViewType()] = preference
            return preference.getViewType().ordinal
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val preferenceViewHolder =
                viewProviders[
                    com.mishiranu.dashchan.ui.preference.core.Preference.ViewType.entries[viewType],
                ]!!.createViewHolder(parent)
            return ViewHolder(preferenceViewHolder.view, preferenceViewHolder)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            preferences[position].bindViewHolder(holder.viewHolder)
        }
    }

    private class HeaderPreference(
        context: Context?,
        title: CharSequence?,
    ) : Preference.Runtime<Void?>(context, null, null, title, null) {
        init {
            setSelectable(false)
        }

        override fun getViewType(): ViewType = ViewType.HEADER

        override fun createViewHolder(parent: ViewGroup): ViewHolder {
            val layout = FrameLayout(parent.getContext())
            val header = makeListTextHeader(layout)
            layout.addView(header)
            layout.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            return ViewHolder(layout, header, null, null)
        }
    }

    private open class ButtonPreference(
        context: Context?,
        title: CharSequence?,
        summaryProvider: SummaryProvider<Void?>?,
    ) : Preference.Runtime<Void?>(context, null, null, title, summaryProvider)

    private class CategoryPreference(
        context: Context?,
        title: CharSequence?,
        private val icon: Drawable?,
    ) : ButtonPreference(context, title, null) {
        private var tintList: ColorStateList? = null

        override fun getViewType(): ViewType = ViewType.CATEGORY

        fun setTint(tintList: ColorStateList?) {
            this.tintList = tintList
            invalidate()
        }

        override fun createViewHolder(parent: ViewGroup): ViewHolder = createIconViewHolder(parent)

        override fun bindViewHolder(viewHolder: ViewHolder) {
            super.bindViewHolder(viewHolder)

            var textColors = viewHolder.title!!.getTag(R.id.tag_text_colors) as ColorStateList?
            if (textColors == null) {
                textColors = viewHolder.title.getTextColors()
                viewHolder.title.setTag(R.id.tag_text_colors, textColors)
            }
            viewHolder.title.setTextColor(if (tintList != null) tintList else textColors)

            if (viewHolder is IconViewHolder) {
                val iconViewHolder = viewHolder
                iconViewHolder.icon!!.setImageDrawable(icon)
                iconViewHolder.icon.setVisibility(if (icon != null) View.VISIBLE else View.GONE)
                iconViewHolder.icon.setImageTintList(
                    if (tintList != null) {
                        tintList
                    } else {
                        ColorStateList.valueOf(
                            getColor(viewHolder.view.getContext(), android.R.attr.textColorSecondary),
                        )
                    },
                )
            }
        }
    }

    companion object {
        /**
         * Fragment argument: title of the row to scroll to and pulse once the screen is built. Set by
         * [PreferenceSearch.Result.createFragment] when a search hit is opened.
         */
        internal const val EXTRA_REVEAL_TITLE = "revealTitle"

        private const val FLASH_ATTEMPTS = 3
    }

    class PreferenceDialog : DialogFragment {
        constructor()

        constructor(key: String?) {
            val args = Bundle()
            args.putString(EXTRA_KEY, key)
            setArguments(args)
        }

        internal val preference: DialogPreference<*>?
            get() {
                val key =
                    requireArguments().getString(EXTRA_KEY)
                return (getParentFragment() as PreferenceFragment).findPreference(
                    key!!,
                ) as DialogPreference<*>?
            }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog = this.preference!!.createDialog(savedInstanceState)

        override fun onStart() {
            super.onStart()
            this.preference!!.startDialog(getDialog() as AlertDialog)
        }

        override fun onStop() {
            this.preference!!.stopDialog(getDialog() as AlertDialog)
            super.onStop()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            this.preference!!.saveState(getDialog() as AlertDialog, outState)
        }

        companion object {
            private const val EXTRA_KEY = "key"
        }
    }
}
