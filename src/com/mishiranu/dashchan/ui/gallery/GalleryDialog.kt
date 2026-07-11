package com.mishiranu.dashchan.ui.gallery

import android.app.ActionBar
import android.app.Dialog
import android.media.AudioManager
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toolbar
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.fragment.app.Fragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ViewUtils.setNewMargin
import com.mishiranu.dashchan.util.ViewUtils.setNewPadding
import com.mishiranu.dashchan.widget.ViewFactory.ToolbarHolder
import com.mishiranu.dashchan.widget.ViewFactory.addToolbarTitle
import kotlin.math.max

class GalleryDialog(private val fragment: Fragment) : Dialog(
    fragment.requireContext(), R.style.Theme_Gallery
) {
    interface Callback {
        fun onBackPressed(): Boolean
        fun onCreateActionContextBarView()
        fun onCreateDialogMenu(menu: Menu?)
        fun onPrepareDialogMenu(menu: Menu?)
        fun onDialogMenuItemSelected(item: MenuItem?): Boolean
    }

    private var toolbarHolder: ToolbarHolder? = null
    private var actionBar: View? = null
    private var actionContextBar: View? = null

    private var actionBarAnimationsFixed = false

    fun setTitleSubtitle(title: CharSequence?, subtitle: CharSequence?) {
        toolbarHolder!!.update(title, subtitle)
    }

    override fun getActionBar(): ActionBar? {
        val actionBar = super.getActionBar()
        if (actionBar != null && !actionBarAnimationsFixed) {
            actionBarAnimationsFixed = true
            // Action bar animations are enabled only after onStart
            // which is called first time before action bar created
            onStop()
            onStart()
        }
        if (toolbarHolder == null) {
            val toolbar = this.actionBarView as Toolbar
            toolbarHolder = addToolbarTitle(toolbar)
            val layoutParams = getWindow()!!.getAttributes()
            val title = layoutParams.getTitle()
            setTitle(null)
            layoutParams.setTitle(title)
            getWindow()!!.setAttributes(layoutParams)
        }
        return actionBar
    }

    private fun getPhoneWindowView(resourceName: String?): View? {
        val id = fragment.getResources().getIdentifier(resourceName, "id", "android")
        return if (id != 0) getWindow()!!.getDecorView().findViewById<View?>(id) else null
    }

    val actionBarView: View?
        get() {
            if (actionBar == null) {
                actionBar = getPhoneWindowView("action_bar")
            }
            return actionBar
        }

    val actionContextBarView: View?
        get() {
            if (actionContextBar == null) {
                actionContextBar = getPhoneWindowView("action_context_bar")
                if (actionContextBar != null && fragment is Callback) {
                    (fragment as Callback).onCreateActionContextBarView()
                }
            }
            return actionContextBar
        }

    // With predictive back enabled the framework no longer calls Dialog.onBackPressed();
    // an explicit OnBackInvokedCallback replicates the old behavior (fragment first, then cancel).
    private val backInvokedCallback = OnBackInvokedCallback { this.handleBackInvoked() }

    init {
        getWindow()!!.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val layoutParams = getWindow()!!.getAttributes()
        layoutParams.setTitle(getContext().getPackageName() + "/" + javaClass.getName())
        getWindow()!!.setAttributes(layoutParams)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        // ActionBarOverlayLayout relies on SYSTEM_UI_FLAG_LAYOUT_STABLE and uses deprecated
        // getSystemWindowInsetsAsRect instead of getInsetsIgnoringVisibility
        val decorView = getWindow()!!.getDecorView()
        val overlay = decorView.findViewById<View?>(
            fragment.getResources()
                .getIdentifier("decor_content_parent", "id", "android")
        )
        val container = decorView.findViewById<View?>(
            fragment.getResources()
                .getIdentifier("action_bar_container", "id", "android")
        )
        if (overlay != null && container != null) {
            overlay.setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener { v: View?, insets: WindowInsets? ->
                val systemInsets =
                    insets!!.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                setNewMargin(
                    container,
                    systemInsets.left,
                    systemInsets.top,
                    systemInsets.right,
                    null
                )
                val actionBar = this.actionBarView
                if (actionBar != null) {
                    val cutoutInsets =
                        insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                    setNewPadding(
                        actionBar, max(0, cutoutInsets.left - systemInsets.left),
                        max(0, cutoutInsets.top - systemInsets.top),
                        max(0, cutoutInsets.right - systemInsets.right), null
                    )
                }
                insets
            })
        }
    }

    private fun handleBackInvoked() {
        if (fragment !is Callback || !(fragment as Callback).onBackPressed()) {
            cancel()
        }
    }

    override fun onStart() {
        super.onStart()
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback
        )
    }

    override fun onStop() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback)
        super.onStop()
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        super.onPreparePanel(featureId, view, menu)
        // Dialog removes the menu completely if menu becomes once empty.
        // This logic is different from Activity and causes unwanted behavior.
        // Return "true" here to always keep menu existing.
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (fragment.isAdded() && fragment is Callback) {
            (fragment as Callback).onCreateDialogMenu(menu)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (fragment.isAdded() && fragment is Callback) {
            (fragment as Callback).onPrepareDialogMenu(menu)
        }
        return true
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            return onOptionsItemSelected(item)
        } else {
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return fragment.isAdded() && fragment is Callback
                && (fragment as Callback).onDialogMenuItemSelected(item)
    }

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        this.actionContextBarView
    }
}
