package com.mishiranu.dashchan.ui.preference

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import chan.content.ChanManager
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.CustomSearchView
import com.mishiranu.dashchan.widget.MenuExpandListener

class CategoriesFragment : PreferenceFragment() {
    private var searchView: CustomSearchView? = null
    private var searchMenuItem: MenuItem? = null

    /** Null while the search is collapsed, which is also what [isBackHandled] reads. */
    private var searchQuery: String? = null
    private var searchFocused = false

    override fun getPreferences(): SharedPreferences = Preferences.prefs

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
        searchView!!.setHint(getString(R.string.search))
        searchView.setOnChangeListener { query ->
            if (searchQuery != null) {
                searchQuery = query
            }
            updatePreferences()
        }

        updatePreferences()
        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.preferences), null)
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
        val searchMenuItem =
            menu
                .add(0, R.id.menu_search, 0, R.string.search)
                .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionSearch))
                .setShowAsActionFlags(
                    MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW,
                )
        if (primary) {
            this.searchMenuItem = searchMenuItem
            searchMenuItem.actionView = searchView
            searchMenuItem.setOnActionExpandListener(
                MenuExpandListener { _, expand ->
                    if (expand) {
                        searchView?.setFocusOnExpand(searchFocused)
                        val searchQuery = this.searchQuery
                        if (searchQuery != null) {
                            searchView?.setQuery(searchQuery)
                        } else {
                            this.searchQuery = ""
                        }
                    } else {
                        searchQuery = null
                    }
                    updatePreferences()
                    notifyBackHandledChanged()
                    true
                },
            )
            if (searchQuery != null) {
                searchMenuItem.expandActionView()
            }
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_search) {
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
        return super.onMenuItemSelected(item)
    }

    private fun updatePreferences() {
        removeAllPreferences()
        val searchQuery = this.searchQuery
        if (searchQuery.isNullOrEmpty()) {
            addCategories()
        } else {
            addSearchResults(searchQuery)
        }
    }

    private fun addCategories() {
        val chans = ChanManager.getInstance().availableChans.iterator()
        val hasChan = chans.hasNext()
        val singleChanName = if (hasChan) chans.next().name else null
        val hasMultipleChans = hasChan && chans.hasNext()
        addCategory(R.string.general, R.drawable.ic_map)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(GeneralFragment()) }
        if (hasMultipleChans) {
            addCategory(R.string.forums, R.drawable.ic_public)
                .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(ChansFragment()) }
        } else if (hasChan) {
            addCategory(R.string.forum, R.drawable.ic_public)
                .setOnClickListener {
                    (requireActivity() as FragmentHandler).pushFragment(ChanFragment(singleChanName))
                }
        }
        addCategory(R.string.user_interface, R.drawable.ic_color_lens)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(InterfaceFragment()) }
        addCategory(R.string.contents, R.drawable.ic_local_library)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(ContentsFragment()) }
        addCategory(R.string.media, R.drawable.ic_save)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(MediaFragment()) }
        addCategory(R.string.autohide, R.drawable.ic_custom_fork)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(AutohideFragment()) }
        addCategory(R.string.commands, R.drawable.ic_command)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(CommandsFragment()) }
        addCategory(R.string.about, R.drawable.ic_info)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(AboutFragment()) }
    }

    /** Each hit shows the setting's title over the screen it lives on, and navigates to it. */
    private fun addSearchResults(query: String) {
        val results = PreferenceSearch.search(requireContext(), query)
        if (results.isEmpty()) {
            addButton(null, getString(R.string.not_found)).setSelectable(false)
        } else {
            for (result in results) {
                addButton(result.title, result.breadcrumb).setOnClickListener {
                    (requireActivity() as FragmentHandler).pushFragment(result.createFragment())
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SEARCH_QUERY = "searchQuery"
        private const val EXTRA_SEARCH_FOCUSED = "searchFocused"
    }
}
