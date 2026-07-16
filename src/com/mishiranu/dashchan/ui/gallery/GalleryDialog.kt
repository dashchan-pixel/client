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
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.fragment.app.Fragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ViewUtils.setNewMargin
import com.mishiranu.dashchan.util.ViewUtils.setNewPadding
import com.mishiranu.dashchan.widget.PredictiveBackTransform
import com.mishiranu.dashchan.widget.ViewFactory.ToolbarHolder
import com.mishiranu.dashchan.widget.ViewFactory.addToolbarTitle
import kotlin.math.max

class GalleryDialog(
    private val fragment: Fragment,
) : Dialog(
        fragment.requireContext(),
        R.style.Theme_Gallery,
    ) {
    interface Callback {
        fun onBackPressed(): Boolean

        /**
         * Whether [onBackPressed] would currently consume a back press instead of letting the
         * dialog close. Must stay free of side effects: it is polled when a back gesture starts,
         * to decide whether to animate the dialog away. Override together with [onBackPressed].
         */
        val isBackHandled: Boolean

        fun onCreateActionContextBarView()

        fun onCreateDialogMenu(menu: Menu)

        fun onPrepareDialogMenu(menu: Menu)

        fun onDialogMenuItemSelected(item: MenuItem): Boolean
    }

    private var toolbarHolder: ToolbarHolder? = null
    private var actionBar: View? = null
    private var actionContextBar: View? = null

    private var actionBarAnimationsFixed = false

    // Dialog.getWindow() is only null once the dialog has been dismissed; every use below runs while
    // it is alive, exactly as the pre-J2K Java dereferenced getWindow() unconditionally.
    private val dialogWindow: Window
        get() = getWindow()!!

    fun setTitleSubtitle(
        title: CharSequence?,
        subtitle: CharSequence?,
    ) {
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
            val layoutParams = dialogWindow.getAttributes()
            val title = layoutParams.getTitle()
            setTitle(null)
            layoutParams.setTitle(title)
            dialogWindow.setAttributes(layoutParams)
        }
        return actionBar
    }

    private fun getPhoneWindowView(resourceName: String?): View? {
        val id = fragment.getResources().getIdentifier(resourceName, "id", "android")
        return if (id != 0) dialogWindow.getDecorView().findViewById<View?>(id) else null
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
    // an explicit callback replicates the old behavior (fragment first, then cancel).
    //
    // The gesture animates the whole dialog window away, but only when the back would actually
    // close it. While the fragment still claims back -- an open showcase, a pager that can return
    // to the grid -- the dialog is staying put, so nothing moves.
    private val backTransform = PredictiveBackTransform(dialogWindow.getDecorView())

    private val backInvokedCallback =
        object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) {
                if (!isBackHandledByFragment) {
                    backTransform.start(BackEventCompat(backEvent))
                }
            }

            override fun onBackProgressed(backEvent: BackEvent) {
                backTransform.progress(BackEventCompat(backEvent))
            }

            override fun onBackCancelled() {
                backTransform.settle()
            }

            override fun onBackInvoked() {
                handleBackInvoked()
            }
        }

    private val isBackHandledByFragment: Boolean
        get() = fragment is Callback && (fragment as Callback).isBackHandled

    init {
        dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val layoutParams = dialogWindow.getAttributes()
        layoutParams.setTitle(getContext().getPackageName() + "/" + javaClass.getName())
        dialogWindow.setAttributes(layoutParams)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        // ActionBarOverlayLayout relies on SYSTEM_UI_FLAG_LAYOUT_STABLE and uses deprecated
        // getSystemWindowInsetsAsRect instead of getInsetsIgnoringVisibility
        val decorView = dialogWindow.getDecorView()
        val overlay =
            decorView.findViewById<View?>(
                fragment
                    .getResources()
                    .getIdentifier("decor_content_parent", "id", "android"),
            )
        val container =
            decorView.findViewById<View?>(
                fragment
                    .getResources()
                    .getIdentifier("action_bar_container", "id", "android"),
            )
        if (overlay != null && container != null) {
            overlay.setOnApplyWindowInsetsListener(
                View.OnApplyWindowInsetsListener { _: View, insets: WindowInsets ->
                    val systemInsets =
                        insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                    setNewMargin(
                        container,
                        systemInsets.left,
                        systemInsets.top,
                        systemInsets.right,
                        null,
                    )
                    val actionBar = this.actionBarView
                    if (actionBar != null) {
                        val cutoutInsets =
                            insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                        setNewPadding(
                            actionBar,
                            max(0, cutoutInsets.left - systemInsets.left),
                            max(0, cutoutInsets.top - systemInsets.top),
                            max(0, cutoutInsets.right - systemInsets.right),
                            null,
                        )
                    }
                    insets
                },
            )
        }
    }

    private fun handleBackInvoked() {
        if (fragment !is Callback || !(fragment as Callback).onBackPressed()) {
            // The window is going away, so any transform the gesture applied goes with it.
            cancel()
        } else {
            // Only reachable if the fragment took back between the gesture starting and landing,
            // which is also the only way the dialog can be left holding a transform it must undo.
            backTransform.settle()
        }
    }

    override fun onStart() {
        super.onStart()
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            backInvokedCallback,
        )
    }

    override fun onStop() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback)
        // No cancel arrives for a gesture interrupted by the dialog stopping.
        backTransform.reset()
        super.onStop()
    }

    override fun onPreparePanel(
        featureId: Int,
        view: View?,
        menu: Menu,
    ): Boolean {
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

    override fun onMenuItemSelected(
        featureId: Int,
        item: MenuItem,
    ): Boolean {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            return onOptionsItemSelected(item)
        } else {
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        fragment.isAdded() &&
            fragment is Callback &&
            (fragment as Callback).onDialogMenuItemSelected(item)

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        this.actionContextBarView
    }
}
