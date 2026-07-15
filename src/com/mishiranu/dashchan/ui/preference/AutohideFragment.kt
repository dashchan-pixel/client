package com.mishiranu.dashchan.ui.preference

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.ChanManager
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.AutohideStorage
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.CustomSearchView
import com.mishiranu.dashchan.widget.ErrorEditTextSetter
import com.mishiranu.dashchan.widget.MenuExpandListener
import com.mishiranu.dashchan.widget.SimpleViewHolder
import com.mishiranu.dashchan.widget.ViewFactory
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class AutohideFragment : BaseListFragment() {
    private val items = ArrayList<AutohideStorage.AutohideItem>()

    private var searchView: CustomSearchView? = null
    private var searchMenuItem: MenuItem? = null

    private var searchQuery: String? = null
    private var searchFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchQuery = savedInstanceState?.getString(EXTRA_SEARCH_QUERY)
        searchFocused = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_SEARCH_FOCUSED)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val searchView = obtainSearchView()
        this.searchView = searchView
        searchView!!.setHint(getString(R.string.filter))
        searchView.setOnChangeListener { query ->
            (getRecyclerView()!!.adapter as Adapter).setSearchQuery(query)
            if (searchQuery != null) {
                searchQuery = query
            }
        }

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.autohide), null)
        items.addAll(AutohideStorage.getInstance().getItems())
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_rules_defined))
        }
        getRecyclerView()!!.adapter = Adapter()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        searchView = null
        searchMenuItem = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        searchView?.let { searchFocused = it.isSearchFocused() }
        outState.putString(EXTRA_SEARCH_QUERY, searchQuery)
        outState.putBoolean(EXTRA_SEARCH_FOCUSED, searchFocused)
    }

    override fun onBackPressed(): Boolean {
        val searchMenuItem = this.searchMenuItem
        if (searchMenuItem != null && searchMenuItem.isActionViewExpanded) {
            searchMenuItem.collapseActionView()
            return true
        }
        return false
    }

    override val isBackHandled: Boolean get() {
        // searchQuery mirrors the action view expansion and, unlike isActionViewExpanded,
        // is already updated when the expand listener notifies about the change.
        return searchQuery != null
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .add(0, R.id.menu_new_rule, 0, R.string.new_rule)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAddRule))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        val searchMenuItem =
            menu
                .add(0, R.id.menu_search, 0, R.string.filter)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        if (primary) {
            this.searchMenuItem = searchMenuItem
            searchMenuItem.actionView = searchView
            searchMenuItem.setOnActionExpandListener(
                MenuExpandListener { _, expand ->
                    if (expand) {
                        searchView!!.setFocusOnExpand(searchFocused)
                        if (searchQuery != null) {
                            searchView!!.setQuery(searchQuery)
                        } else {
                            searchQuery = ""
                        }
                    } else {
                        searchQuery = null
                    }
                    (getRecyclerView()!!.adapter as Adapter).setSearchQuery(searchQuery)
                    onPrepareMenu(menu)
                    notifyBackHandledChanged()
                    true
                },
            )
            if (searchQuery != null) {
                searchMenuItem.expandActionView()
            }
        }
    }

    override fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu.findItem(R.id.menu_new_rule).isVisible = searchQuery == null
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_rule -> {
                editRule(null, -1)
                return true
            }

            R.id.menu_search -> {
                val searchMenuItem = this.searchMenuItem
                return if (item === searchMenuItem) {
                    searchFocused = true
                    false
                } else if (searchMenuItem != null) {
                    searchFocused = true
                    searchMenuItem.expandActionView()
                    true
                } else {
                    true
                }
            }
        }
        return super.onMenuItemSelected(item)
    }

    private fun editRule(
        autohideItem: AutohideStorage.AutohideItem?,
        index: Int,
    ) {
        val dialog = AutohideDialog(autohideItem, index)
        dialog.show(childFragmentManager, AutohideDialog::class.java.name)
    }

    internal fun onEditComplete(
        autohideItem: AutohideStorage.AutohideItem,
        index: Int,
    ) {
        val adapter = getRecyclerView()!!.adapter as Adapter
        if (index == -1) {
            AutohideStorage.getInstance().add(autohideItem)
            items.add(autohideItem)
            setErrorText(null)
        } else if (index >= 0) {
            AutohideStorage.getInstance().update(index, autohideItem)
            items[index] = autohideItem
        }
        adapter.invalidate()
    }

    internal fun onDelete(index: Int) {
        AutohideStorage.getInstance().delete(index)
        items.removeAt(index)
        (getRecyclerView()!!.adapter as Adapter).invalidate()
        if (items.isEmpty()) {
            setErrorText(getString(R.string.no_rules_defined))
        }
    }

    private inner class Adapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ListViewUtils.ClickCallback<Unit, RecyclerView.ViewHolder> {
        private val filteredItems = ArrayList<AutohideStorage.AutohideItem>()
        private var searchQuery: String? = null

        fun setSearchQuery(searchQuery: String?) {
            filteredItems.clear()
            this.searchQuery = searchQuery
            if (!StringUtils.isEmpty(searchQuery)) {
                val locale = Locale.getDefault()
                for (item in items) {
                    if (!StringUtils.isEmpty(item.value) &&
                        item.value!!.lowercase(locale).contains(searchQuery!!.lowercase(locale)) ||
                        item.find(searchQuery!!) != null
                    ) {
                        filteredItems.add(item)
                    }
                }
            }
            notifyDataSetChanged()
        }

        fun invalidate() {
            setSearchQuery(searchQuery)
        }

        fun getItem(position: Int): AutohideStorage.AutohideItem = if (!StringUtils.isEmpty(searchQuery)) filteredItems[position] else items[position]

        override fun getItemCount(): Int = if (!StringUtils.isEmpty(searchQuery)) filteredItems.size else items.size

        override fun onItemClick(
            holder: RecyclerView.ViewHolder,
            position: Int,
            item: Unit?,
            longClick: Boolean,
        ): Boolean {
            val autohideItem = getItem(position)
            editRule(autohideItem, items.indexOf(autohideItem))
            return true
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            ListViewUtils.bind<Unit, RecyclerView.ViewHolder>(
                SimpleViewHolder(ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_SINGLE_LINE).view),
                false,
                null,
                this,
            )

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            val viewHolder = holder.itemView.tag as ViewFactory.TwoLinesViewHolder
            val autohideItem = getItem(position)
            viewHolder.text1.text =
                if (StringUtils.isEmpty(autohideItem.value)) {
                    getString(R.string.all_posts)
                } else {
                    autohideItem.value
                }
            val builder = StringBuilder()
            var and = false
            if (!StringUtils.isEmpty(autohideItem.boardName) ||
                autohideItem.optionOriginalPost ||
                autohideItem.optionSage
            ) {
                if (!StringUtils.isEmpty(autohideItem.boardName)) {
                    if (and) {
                        builder.append(" & ")
                    }
                    builder.append('[').append(autohideItem.boardName).append(']')
                    if (!StringUtils.isEmpty(autohideItem.threadNumber)) {
                        builder.append(" & ").append(autohideItem.threadNumber)
                    }
                    and = true
                }
                if (autohideItem.optionOriginalPost) {
                    if (and) {
                        builder.append(" & ")
                    }
                    builder.append("op")
                    and = true
                }
                if (autohideItem.optionSage) {
                    if (and) {
                        builder.append(" & ")
                    }
                    builder.append("sage")
                    and = true
                }
            }
            var orCount = 0
            if (autohideItem.optionSubject) {
                orCount++
            }
            if (autohideItem.optionComment) {
                orCount++
            }
            if (autohideItem.optionName) {
                orCount++
            }
            if (autohideItem.optionFileName) {
                orCount++
            }
            if (orCount > 0) {
                if (and) {
                    builder.append(" & ")
                    if (orCount > 1) {
                        builder.append('(')
                    }
                }
                var or = false
                if (autohideItem.optionSubject) {
                    builder.append("subject")
                    or = true
                }
                if (autohideItem.optionComment) {
                    if (or) {
                        builder.append(" | ")
                    }
                    builder.append("comment")
                    or = true
                }
                if (autohideItem.optionName) {
                    if (or) {
                        builder.append(" | ")
                    }
                    builder.append("name")
                }
                if (autohideItem.optionFileName) {
                    if (or) {
                        builder.append(" | ")
                    }
                    builder.append("file")
                }
                if (and && orCount > 1) {
                    builder.append(')')
                }
            } else {
                if (and) {
                    builder.append(" & ")
                }
                builder.append("false")
            }
            viewHolder.text2.text = builder
        }
    }

    class AutohideDialog :
        DialogFragment,
        ChanMultiChoiceDialog.Callback {
        private val selectedChanNames = HashSet<String>()

        private lateinit var scrollView: ScrollView
        private lateinit var chanNameSelector: TextView
        private lateinit var boardNameEdit: EditText
        private lateinit var threadNumberEdit: EditText
        private lateinit var autohideOriginalPost: CheckBox
        private lateinit var autohideSage: CheckBox
        private lateinit var autohideSubject: CheckBox
        private lateinit var autohideComment: CheckBox
        private lateinit var autohideName: CheckBox
        private lateinit var autohideFileName: CheckBox
        private lateinit var valueEdit: EditText
        private lateinit var errorText: TextView
        private lateinit var matcherText: TextView
        private lateinit var testStringEdit: EditText

        constructor()

        constructor(autohideItem: AutohideStorage.AutohideItem?, index: Int) {
            val args = Bundle()
            args.putParcelable(EXTRA_ITEM, autohideItem)
            args.putInt(EXTRA_INDEX, index)
            arguments = args
        }

        @SuppressLint("InflateParams")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val view =
                LayoutInflater
                    .from(requireContext())
                    .inflate(R.layout.dialog_autohide, null) as ScrollView
            scrollView = view
            chanNameSelector = view.findViewById(R.id.chan_name)
            boardNameEdit = view.findViewById(R.id.board_name)
            threadNumberEdit = view.findViewById(R.id.thread_number)
            autohideOriginalPost = view.findViewById(R.id.autohide_original_post)
            autohideSage = view.findViewById(R.id.autohide_sage)
            autohideSubject = view.findViewById(R.id.autohide_subject)
            autohideComment = view.findViewById(R.id.autohide_comment)
            autohideName = view.findViewById(R.id.autohide_name)
            autohideFileName = view.findViewById(R.id.autohide_file_name)
            valueEdit = view.findViewById(R.id.value)
            errorText = view.findViewById(R.id.error_text)
            matcherText = view.findViewById(R.id.matcher_result)
            testStringEdit = view.findViewById(R.id.test_string)
            valueEdit.addTextChangedListener(valueListener)
            testStringEdit.addTextChangedListener(testStringListener)
            chanNameSelector.setOnClickListener { ChanMultiChoiceDialog(selectedChanNames).show(this) }
            chanNameSelector.typeface = ResourceUtils.TYPEFACE_MEDIUM

            if (!ChanManager.getInstance().hasMultipleAvailableChans()) {
                chanNameSelector.visibility = View.GONE
            }
            var autohideItem: AutohideStorage.AutohideItem? = null
            if (savedInstanceState != null) {
                autohideItem = BundleCompat.getParcelable(savedInstanceState, EXTRA_ITEM, AutohideStorage.AutohideItem::class.java)
            }
            if (autohideItem == null) {
                autohideItem = BundleCompat.getParcelable(requireArguments(), EXTRA_ITEM, AutohideStorage.AutohideItem::class.java)
            }
            if (autohideItem != null) {
                autohideItem.chanNames?.let { selectedChanNames.addAll(it) }
                updateSelectedText()
                boardNameEdit.setText(autohideItem.boardName)
                threadNumberEdit.setText(autohideItem.threadNumber)
                autohideOriginalPost.isChecked = autohideItem.optionOriginalPost
                autohideSage.isChecked = autohideItem.optionSage
                autohideSubject.isChecked = autohideItem.optionSubject
                autohideComment.isChecked = autohideItem.optionComment
                autohideName.isChecked = autohideItem.optionName
                autohideFileName.isChecked = autohideItem.optionFileName
                valueEdit.setText(autohideItem.value)
            } else {
                chanNameSelector.setText(R.string.all_forums)
                boardNameEdit.setText(null)
                threadNumberEdit.setText(null)
                autohideOriginalPost.isChecked = false
                autohideSage.isChecked = false
                autohideSubject.isChecked = true
                autohideComment.isChecked = true
                autohideName.isChecked = true
                autohideFileName.isChecked = false
                valueEdit.setText(null)
            }
            updateTestResult()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putParcelable(EXTRA_ITEM, readDialogView())
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val index = requireArguments().getInt(EXTRA_INDEX)
            val builder =
                AlertDialog
                    .Builder(requireContext())
                    .setView(scrollView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save) { _, _ ->
                        (parentFragment as AutohideFragment).onEditComplete(readDialogView(), index)
                    }
            if (index >= 0) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    (parentFragment as AutohideFragment).onDelete(index)
                }
            }
            val dialog = builder.create()
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            return dialog
        }

        private fun updateSelectedText() {
            val chanNameText: String
            val size = selectedChanNames.size
            if (size == 0) {
                chanNameText = getString(R.string.all_forums)
            } else if (size > 1) {
                chanNameText = getString(R.string.multiple_forums)
            } else {
                val chanName = selectedChanNames.iterator().next()
                val chan = Chan.get(chanName)
                val title = if (chan.name != null) chan.configuration.getTitle() else chanName
                chanNameText = getString(R.string.forum_only__format, title)
            }
            chanNameSelector.text = chanNameText
        }

        private fun readDialogView(): AutohideStorage.AutohideItem {
            val boardName = boardNameEdit.text.toString()
            val threadNumber = threadNumberEdit.text.toString()
            val optionOriginalPost = autohideOriginalPost.isChecked
            val optionSage = autohideSage.isChecked
            val optionSubject = autohideSubject.isChecked
            val optionComment = autohideComment.isChecked
            val optionName = autohideName.isChecked
            val optionFileName = autohideFileName.isChecked
            val value = valueEdit.text.toString()
            return AutohideStorage.AutohideItem(
                if (selectedChanNames.size > 0) selectedChanNames else null,
                boardName,
                threadNumber,
                optionOriginalPost,
                optionSage,
                optionSubject,
                optionComment,
                optionName,
                optionFileName,
                value,
            )
        }

        override fun onChansSelected(chanNames: Collection<String>) {
            selectedChanNames.clear()
            selectedChanNames.addAll(chanNames)
            updateSelectedText()
        }

        private var errorSpan: BackgroundColorSpan? = null
        private var errorValueSetter: ErrorEditTextSetter? = null

        private fun updateError(
            index: Int,
            text: String?,
        ) {
            val error = index >= 0
            val value = valueEdit.editableText
            if (error) {
                if (errorSpan == null) {
                    errorSpan =
                        BackgroundColorSpan(
                            ResourceUtils.getColor(
                                requireContext(),
                                R.attr.colorTextError,
                            ),
                        )
                } else {
                    value.removeSpan(errorSpan)
                }
                if (index > 0) {
                    value.setSpan(errorSpan, index - 1, index, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else if (errorSpan != null) {
                value.removeSpan(errorSpan)
            }
            if (errorValueSetter == null) {
                errorValueSetter = ErrorEditTextSetter(valueEdit)
            }
            errorValueSetter!!.setError(error)

            if (StringUtils.isEmpty(text)) {
                errorText.visibility = View.GONE
            } else {
                scrollView.post {
                    val position = errorText.bottom - scrollView.height
                    if (scrollView.scrollY < position) {
                        var limit = Integer.MAX_VALUE
                        val start = valueEdit.selectionStart
                        if (start >= 0) {
                            val layout = valueEdit.layout
                            limit = layout.getLineTop(layout.getLineForOffset(start)) + valueEdit.top
                        }
                        if (limit > position) {
                            scrollView.smoothScrollTo(0, position)
                        }
                    }
                }
                errorText.visibility = View.VISIBLE
                errorText.text = text
            }
        }

        private var workPattern: Pattern? = null

        private fun updateTestResult() {
            var matchedText: String? = null
            val workPattern = this.workPattern
            if (workPattern != null) {
                val matcher: Matcher = workPattern.matcher(testStringEdit.text.toString())
                if (matcher.find()) {
                    matchedText = StringUtils.emptyIfNull(matcher.group())
                }
            }
            if (matchedText != null) {
                if (StringUtils.isEmptyOrWhitespace(matchedText)) {
                    matcherText.setText(R.string.match_found)
                } else {
                    matcherText.text =
                        ResourceUtils.getColonString(
                            resources,
                            R.string.match_found,
                            matchedText,
                        )
                }
            } else {
                matcherText.setText(R.string.no_matches_found)
            }
        }

        private val valueListener: TextWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {}

                override fun afterTextChanged(s: Editable) {
                    // Remove line breaks
                    for (i in 0 until s.length) {
                        val c = s[i]
                        // Replacing or deleting will call this callback again
                        if (c == '\n') {
                            s.replace(i, i + 1, " ")
                            return
                        } else if (c == '\r') {
                            s.delete(i, i + 1)
                            return
                        }
                    }
                    var pattern: Pattern? = null
                    try {
                        pattern = AutohideStorage.AutohideItem.makePattern(s.toString())
                        updateError(-1, null)
                    } catch (e: PatternSyntaxException) {
                        updateError(e.index, e.description)
                    }
                    workPattern = pattern
                    updateTestResult()
                }
            }

        private val testStringListener: TextWatcher =
            object : TextWatcher {
                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    updateTestResult()
                }

                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable) {}
            }

        companion object {
            private const val EXTRA_ITEM = "item"
            private const val EXTRA_INDEX = "index"
        }
    }

    companion object {
        private const val EXTRA_SEARCH_QUERY = "searchQuery"
        private const val EXTRA_SEARCH_FOCUSED = "searchFocused"
    }
}
