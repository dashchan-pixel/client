package com.mishiranu.dashchan.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.CustomSearchView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

abstract class ContentFragment :
    Fragment(),
    MenuProvider {
    private class MenuState {
        var created: Boolean = false
    }

    private val menuStates: WeakHashMap<Menu?, MenuState?> = WeakHashMap<Menu?, MenuState?>()

    val isSearchMode: Boolean
        get() = false

    open fun onSearchRequested(): Boolean = false

    open fun onHomePressed(): Boolean = onBackPressed()

    open fun onBackPressed(): Boolean = false

    open val isBackHandled: Boolean
        /**
         * Whether [.onBackPressed] would currently handle a back press. Drives the enabled
         * state of the activity's back callback: the predictive back-to-home animation only plays
         * when no fragment claims the gesture. Override together with [.onBackPressed] and
         * call [.notifyBackHandledChanged] whenever the returned value may have changed.
         */
        get() = false

    protected fun notifyBackHandledChanged() {
        val activity = getActivity()
        if (activity is FragmentHandler) {
            (activity as FragmentHandler).updateBackHandling()
        }
    }

    private fun clearOptionMenus() {
        for (entry in menuStates.entries) {
            if (entry.value!!.created) {
                val menu: Menu = entry.key!!
                val size = menu.size()
                for (i in 0..<size) {
                    val menuItem = menu.getItem(i)
                    if (menuItem.getActionView() != null) {
                        menuItem.setOnActionExpandListener(null)
                        if (menuItem.isActionViewExpanded()) {
                            menuItem.collapseActionView()
                        }
                    }
                }
            }
        }
        menuStates.clear()
    }

    open fun onTerminate() {
        clearOptionMenus()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        clearOptionMenus()
        val viewHolder = this.viewHolder
        if (viewHolder != null) {
            viewHolder.resetSearchView(this)
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            // Block hardware menu button if menu is empty. This fixes menu issues on some Android 5 devices
            // and ensures empty hardware menu will never appear.
            var hasMenuItems = false
            for (entry in menuStates.entries) {
                hasMenuItems = entry.value!!.created && entry.key!!.hasVisibleItems()
            }
            if (!hasMenuItems) {
                return true
            }
        }
        return false
    }

    override fun onCreateAnimator(
        transit: Int,
        enter: Boolean,
        nextAnim: Int,
    ): Animator? {
        if (transit == FragmentTransaction.TRANSIT_FRAGMENT_OPEN) {
            return createAnimator(getView(), enter)
        } else {
            return null
        }
    }

    protected fun createAnimator(
        view: View?,
        enter: Boolean,
    ): Animator {
        if (enter) {
            val alphaAnimator = ObjectAnimator.ofFloat<View?>(view, View.ALPHA, 0f, 1f)
            alphaAnimator.setDuration(150)
            val scaleXAnimator = ObjectAnimator.ofFloat<View?>(view, View.SCALE_X, 0.925f, 1f)
            scaleXAnimator.setDuration(150)
            val scaleYAnimator = ObjectAnimator.ofFloat<View?>(view, View.SCALE_Y, 0.925f, 1f)
            scaleYAnimator.setDuration(150)
            val set = AnimatorSet()
            set.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator)
            return set
        } else {
            val animator = ObjectAnimator.ofFloat<View?>(view, View.ALPHA, 1f, 0f)
            animator.setDuration(100)
            animator.setInterpolator(DecelerateInterpolator())
            return animator
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, getViewLifecycleOwner())
    }

    override fun onResume() {
        super.onResume()
        // Menu can be requested too early on some Android 4.x
        invalidateMenuInternal(true)
        // Fragment transactions commit asynchronously - re-sync back interception
        // once the new fragment is actually current.
        notifyBackHandledChanged()
    }

    private fun obtainMenuState(menu: Menu?): MenuState {
        var menuState = menuStates.get(menu)
        if (menuState == null) {
            menuState = MenuState()
            menuStates[menu] = menuState
        }
        return menuState
    }

    override fun onCreateMenu(
        menu: Menu,
        inflater: MenuInflater,
    ) {
        val menuState = obtainMenuState(menu)
        if (isAdded() && this.isValidOptionsMenuState) {
            menuState.created = true
            onCreateOptionsMenu(menu, isPrimaryMenu(menu))
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        if (isAdded() && this.isValidOptionsMenuState) {
            val primary = isPrimaryMenu(menu)
            val menuState = obtainMenuState(menu)
            if (!menuState.created) {
                // onPrepareOptionsMenu can be called when onCreateOptionsMenu was called in
                // invalid state (when isValidOptionsMenuState returned false) or wasn't called at all
                // (this is the case for devices with hardware menu button which have 2 Menu instances)
                menuState.created = true
                menu.clear()
                onCreateOptionsMenu(menu, primary)
            }
            onPrepareOptionsMenu(menu, primary)
        }
    }

    open val isValidOptionsMenuState: Boolean
        get() = true

    open fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {}

    open fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {}

    override fun onMenuItemSelected(item: MenuItem): Boolean = false

    fun invalidateOptionsMenu() {
        invalidateMenuInternal(false)
    }

    private fun invalidateMenuInternal(prepareOnly: Boolean) {
        for (entry in menuStates.entries) {
            if (!prepareOnly || !entry.value!!.created) {
                onPrepareMenu(entry.key!!)
            }
        }
    }

    private fun isPrimaryMenu(menu: Menu?): Boolean {
        val toolbar = (requireActivity() as FragmentHandler).getToolbarView() as Toolbar
        return toolbar.getMenu() === menu
    }

    private val viewHolder: ViewHolderFragment?
        get() {
            val fragmentManager = getParentFragmentManager()
            return fragmentManager.findFragmentByTag(ViewHolderFragment.TAG) as ViewHolderFragment?
        }

    internal fun obtainSearchView(): CustomSearchView? {
        val viewHolder = this.viewHolder ?: return null
        return viewHolder.obtainSearchView(this)
    }

    class ViewHolderFragment : Fragment() {
        private var searchView: CustomSearchView? = null
        private var searchViewOwner: WeakReference<ContentFragment?>? = null

        internal fun resetSearchView(fragment: ContentFragment?) {
            if (searchView != null) {
                val reset: Boolean
                if (fragment != null && searchViewOwner != null) {
                    val ownerFragment = searchViewOwner?.get()
                    reset = ownerFragment == null || ownerFragment === fragment
                } else {
                    reset = true
                }
                if (reset) {
                    searchView?.setOnSubmitListener(null)
                    searchView?.setOnChangeListener(null)
                }
            }
        }

        internal fun obtainSearchView(fragment: ContentFragment?): CustomSearchView? {
            resetSearchView(null)
            var searchView = this.searchView
            if (searchView == null) {
                searchView =
                    CustomSearchView(
                        ContextThemeWrapper(
                            requireContext(),
                            R.style.Theme_Special_White,
                        ),
                    )
                this.searchView = searchView
            }
            searchViewOwner = WeakReference<ContentFragment?>(fragment)
            ViewUtils.removeFromParent(searchView)
            searchView.setQuery("")
            return searchView
        }

        companion object {
            val TAG: String = ViewHolderFragment::class.java.getName()
        }
    }

    companion object {
        fun prepare(activity: FragmentActivity) {
            val fragmentManager = activity.getSupportFragmentManager()
            var viewHolder =
                fragmentManager.findFragmentByTag(ViewHolderFragment.TAG) as ViewHolderFragment?
            if (viewHolder == null) {
                viewHolder = ViewHolderFragment()
                fragmentManager
                    .beginTransaction()
                    .add(viewHolder, ViewHolderFragment.TAG)
                    .commitNow()
            }
        }
    }
}
