package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Pair
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.util.CommonUtils.equals
import chan.util.StringUtils.formatFileSize
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.consumeShowcaseGallery
import com.mishiranu.dashchan.content.Preferences.isScrollThreadGallery
import com.mishiranu.dashchan.content.Preferences.isShowcaseGalleryEnabled
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.service.DownloadService.RequestItem
import com.mishiranu.dashchan.graphics.GalleryBackgroundDrawable
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.gallery.FlowDialog.Companion.show
import com.mishiranu.dashchan.ui.gallery.PagerInstance.MediaSummary
import com.mishiranu.dashchan.util.AnimationUtils.lerp
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.FlagUtils.set
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getActionBarIcon
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.setWindowLayoutFullscreen
import com.mishiranu.dashchan.widget.InsetsLayout
import com.mishiranu.dashchan.widget.InsetsLayout.Apply
import com.mishiranu.dashchan.widget.InsetsLayout.OnApplyInsetsListener
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.attach
import com.mishiranu.dashchan.widget.ViewFactory
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

class GalleryOverlay : DialogFragment, GalleryDialog.Callback, GalleryInstance.Callback {
    enum class NavigatePostMode {
        DISABLED, MANUALLY, ENABLED
    }

    internal var queuedGalleryItems: MutableList<GalleryItem>? = null
    private var queuedFromView: WeakReference<View?>? = null

    internal var rootView: InsetsLayout? = null
    internal var instance: GalleryInstance? = null
    internal var pagerUnit: PagerUnit? = null
    internal var listUnit: ListUnit? = null

    internal var galleryWindow = false
    internal var galleryMode = false
    private var cornerAnimator: CornerAnimator? = null
    private val scrollThread = isScrollThreadGallery

    internal var titleSubtitle: Pair<CharSequence?, CharSequence?>? = null
    internal var screenOnFixed = false
    internal var systemUiVisibilityFlags = GalleryInstance.Flags.LOCKED_USER

    // Replaces setRetainInstance: everything that must survive a configuration change
    // (the view tree, the gallery instance and its units) is kept here, owned by a
    // ViewModel, and re-adopted by the recreated fragment in onCreate. "current" points
    // at the live fragment so listeners installed on the retained rootView never call
    // into a destroyed fragment instance.
    internal class Retained {
        internal var current: GalleryOverlay? = null

        internal var queuedGalleryItems: MutableList<GalleryItem>? = null

        internal var rootView: InsetsLayout? = null
        internal var instance: GalleryInstance? = null
        internal var pagerUnit: PagerUnit? = null
        internal var listUnit: ListUnit? = null

        internal var galleryWindow = false
        internal var galleryMode = false

        internal var titleSubtitle: Pair<CharSequence?, CharSequence?>? = null
        internal var screenOnFixed = false
        internal var systemUiVisibilityFlags = GalleryInstance.Flags.LOCKED_USER
    }

    class RetainedViewModel : ViewModel() {
        internal var retained: Retained? = null

        override fun onCleared() {
            val retained = this.retained
            this.retained = null
            if (retained != null && retained.pagerUnit != null) {
                retained.pagerUnit!!.onFinish()
            }
        }
    }

    internal var retained: Retained? = null

    constructor()

    constructor(uri: Uri?) : this(uri, null, null, 0, null, null, NavigatePostMode.DISABLED, false)

    constructor(
        chanName: String?,
        galleryItems: MutableList<GalleryItem?>?,
        imageIndex: Int,
        threadTitle: String?,
        fromView: View?,
        navigatePostMode: NavigatePostMode,
        initialGalleryMode: Boolean
    ) : this(
        null, chanName, galleryItems, imageIndex, threadTitle, fromView,
        navigatePostMode, initialGalleryMode
    )

    val chanName: String?
        get() = requireArguments().getString(EXTRA_CHAN_NAME)

    private constructor(
        uri: Uri?,
        chanName: String?,
        galleryItems: MutableList<GalleryItem?>?,
        imageIndex: Int,
        threadTitle: String?,
        fromView: View?,
        navigatePostMode: NavigatePostMode,
        initialGalleryMode: Boolean
    ) {
        val args = Bundle()
        args.putParcelable(EXTRA_URI, uri)
        args.putString(EXTRA_CHAN_NAME, chanName)
        args.putInt(EXTRA_IMAGE_INDEX, imageIndex)
        args.putString(EXTRA_THREAD_TITLE, threadTitle)
        args.putString(EXTRA_NAVIGATE_POST_MODE, navigatePostMode.name)
        args.putBoolean(EXTRA_INITIAL_GALLERY_MODE, initialGalleryMode)
        setArguments(args)
        this.queuedGalleryItems = galleryItems
        this.queuedFromView = if (fromView != null) WeakReference<View?>(fromView) else null
    }

    private val navigatePostMode: NavigatePostMode
        get() {
            val name =
                requireArguments().getString(EXTRA_NAVIGATE_POST_MODE)
            return if (name != null) NavigatePostMode.valueOf(
                name
            ) else NavigatePostMode.DISABLED
        }

    /** Start the opening video [position] ms in (a PiP window handing playback back).  */
    fun setInitialVideoPosition(position: Long): GalleryOverlay {
        requireArguments().putLong(EXTRA_INITIAL_VIDEO_POSITION, position)
        return this
    }

    private val threadTitle: String?
        get() = requireArguments().getString(EXTRA_THREAD_TITLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.savedInstanceState = savedInstanceState
        val viewModel =
            ViewModelProvider(this).get<RetainedViewModel>(RetainedViewModel::class.java)
        var retained = viewModel.retained
        if (retained == null && savedInstanceState != null) {
            // Restoring after process death: the gallery content lived only in memory
            dismiss()
            return
        }
        if (retained == null) {
            retained = Retained()
            viewModel.retained = retained
        }
        this.retained = retained
        retained.current = this
        if (queuedGalleryItems != null) {
            retained.queuedGalleryItems = queuedGalleryItems
            queuedGalleryItems = null
        }
        rootView = retained.rootView
        instance = retained.instance
        pagerUnit = retained.pagerUnit
        listUnit = retained.listUnit
        galleryWindow = retained.galleryWindow
        galleryMode = retained.galleryMode
        titleSubtitle = retained.titleSubtitle
        screenOnFixed = retained.screenOnFixed
        systemUiVisibilityFlags = retained.systemUiVisibilityFlags
        if (instance != null) {
            instance!!.callback = this
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): GalleryDialog {
        return GalleryDialog(this)
    }

    override fun getDialog(): GalleryDialog? {
        return super.getDialog() as GalleryDialog?
    }

    override fun onDestroyView() {
        super.onDestroyView()
        destroyShowcase(false)
        if (cornerAnimator != null) {
            cornerAnimator!!.cancel()
            cornerAnimator = null
        }
        if (rootView != null) {
            rootView!!.removeCallbacks(returnToGalleryRunnable)
        }
    }

    // GalleryOverlay has no fragment view, so view-bound callbacks like onViewStateRestored
    // never run; the dialog content is set up once per fragment instance from onStart,
    // before DialogFragment.onStart shows the dialog (same point onActivityCreated used to run).
    private var savedInstanceState: Bundle? = null
    private var dialogInitialized = false

    override fun onStart() {
        if (!dialogInitialized) {
            dialogInitialized = true
            val savedInstanceState = this.savedInstanceState
            this.savedInstanceState = null
            initializeDialog(savedInstanceState)
        }
        super.onStart()
    }

    private fun initializeDialog(savedInstanceState: Bundle?) {
        val retained = this.retained
        if (retained == null) {
            // Dismissing after process death
            return
        }
        val queuedFromView = if (this.queuedFromView != null) this.queuedFromView!!.get() else null
        this.queuedFromView = null
        var imageViewPosition: IntArray? = null
        if (queuedFromView != null) {
            val location = IntArray(2)
            queuedFromView.getLocationOnScreen(location)
            imageViewPosition = intArrayOf(
                location[0], location[1],
                queuedFromView.getWidth(), queuedFromView.getHeight()
            )
        }
        val attributes = getWindow().getAttributes()
        attributes.windowAnimations = if (imageViewPosition == null)
            R.style.Animation_Gallery_Full
        else
            R.style.Animation_Gallery_Partial
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams
            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES


        if (rootView == null) {
            val context =
                attach(ContextThemeWrapper(getInstance().localizedContext, R.style.Theme_Gallery))
            rootView = InsetsLayout(context)
            // The listeners below live as long as the retained rootView: route them through
            // retained.current so they always talk to the fragment instance that is alive.
            rootView!!.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val current = retained.current
                    if (current != null && !current.galleryMode) {
                        current.displayShowcase()
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
            rootView!!.setOnApplyInsetsListener(OnApplyInsetsListener { apply: Apply? ->
                val insets = apply!!.get()
                val current = retained.current
                if (current == null) {
                    return@OnApplyInsetsListener
                }
                if (current.listUnit != null) {
                    val invalidate = current.listUnit!!.onApplyWindowInsets(insets)
                    if (invalidate) {
                        current.postInvalidateSystemUIVisibility()
                    }
                }
                if (current.pagerUnit != null) {
                    current.pagerUnit!!.onApplyWindowInsets(insets)
                }
            })
            rootView!!.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            rootView!!.setBackground(
                GalleryBackgroundDrawable(
                    rootView!!,
                    imageViewPosition,
                    BACKGROUND_COLOR
                )
            )
            retained.rootView = rootView
        }
        val dialog = getDialog()
        ViewUtils.removeFromParent(rootView!!)
        dialog!!.setContentView(rootView!!)
        dialog.show()
        dialog.getActionBar()!!.setDisplayHomeAsUpEnabled(true)
        val invalidateSystemUiFlags = Runnable {
            if (dialog.isShowing()) {
                invalidateSystemUiFlags()
            }
        }
        ViewUtils.addWindowFocusListener(
            rootView!!,
            OnFocusChangeListener { v: View?, hasFocus: Boolean ->
                if (pagerUnit != null) {
                    // Block touch events when dialogs are opened
                    pagerUnit!!.setHasFocus(hasFocus)
                }
                ConcurrentUtils.HANDLER.removeCallbacks(invalidateSystemUiFlags)
                if (hasFocus) {
                    // Re-apply visibility flags after dialogs closed
                    ConcurrentUtils.HANDLER.postDelayed(invalidateSystemUiFlags, 100)
                }
            })

        var newImagePosition: Int? = null
        if (instance == null) {
            val uri =
                BundleCompat.getParcelable<Uri?>(requireArguments(), EXTRA_URI, Uri::class.java)
            val chanNameFromArguments = requireArguments().getString(EXTRA_CHAN_NAME)
            val chan = if (chanNameFromArguments == null && uri != null)
                getPreferred(null, uri)
            else
                get(chanNameFromArguments)
            val defaultLocator = chan.name == null

            val galleryItems: MutableList<GalleryItem?>?
            val imagePosition: Int
            if (uri != null) {
                var boardName: String? = null
                var threadNumber: String? = null
                if (!defaultLocator) {
                    boardName = chan.locator.safe(true).getBoardName(uri)
                    threadNumber = chan.locator.safe(true).getThreadNumber(uri)
                }
                galleryItems =
                    mutableListOf(GalleryItem(uri, boardName, threadNumber))
                imagePosition = 0
            } else {
                galleryItems = retained.queuedGalleryItems
                retained.queuedGalleryItems = null
                imagePosition = if (savedInstanceState != null)
                    savedInstanceState.getInt(EXTRA_POSITION)
                else
                    requireArguments().getInt(EXTRA_IMAGE_INDEX)
            }
            instance = GalleryInstance(
                rootView!!.getContext(), this, ACTION_BAR_COLOR, chan.name,
                galleryItems ?: mutableListOf()
            )
            retained.instance = instance!!
            if (!instance!!.galleryItems.isEmpty()) {
                listUnit = ListUnit(instance)
                pagerUnit = PagerUnit(instance)
                val initialVideoPosition = requireArguments().getLong(EXTRA_INITIAL_VIDEO_POSITION)
                if (initialVideoPosition > 0) {
                    requireArguments().remove(EXTRA_INITIAL_VIDEO_POSITION)
                    pagerUnit!!.setInitialVideoSeek(initialVideoPosition)
                }
                retained.listUnit = listUnit
                retained.pagerUnit = pagerUnit
                rootView!!.addView(
                    listUnit!!.getRecyclerView(), ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                rootView!!.addView(
                    pagerUnit!!.view, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                pagerUnit!!.addAndInitViews(rootView, imagePosition)
            }
            newImagePosition = imagePosition
        }

        if (instance!!.galleryItems.isEmpty()) {
            val errorHolder = ViewFactory.createErrorLayout(rootView!!)
            errorHolder.text.setText(R.string.gallery_is_empty)
            rootView!!.addView(errorHolder.layout)
        } else {
            if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_GALLERY_MODE)) {
                galleryMode = savedInstanceState.getBoolean(EXTRA_GALLERY_MODE)
                galleryWindow = savedInstanceState.getBoolean(EXTRA_GALLERY_WINDOW)
                switchMode(galleryMode, false)
                modifySystemUiVisibility(
                    GalleryInstance.Flags.LOCKED_USER,
                    savedInstanceState.getBoolean(EXTRA_SYSTEM_UI_VISIBILITY)
                )
            } else if (newImagePosition != null) {
                val imagePosition = newImagePosition
                galleryWindow = imagePosition < 0 || requireArguments().getBoolean(
                    EXTRA_INITIAL_GALLERY_MODE
                )
                if (galleryWindow && imagePosition >= 0) {
                    listUnit!!.scrollListToPosition(imagePosition, false)
                }
                switchMode(galleryWindow, false)
            }
            if (newImagePosition != null) {
                pagerUnit!!.onViewsCreated(imageViewPosition)
            }
            if (!galleryMode) {
                displayShowcase()
            }
        }
        val selected =
            if (savedInstanceState != null) savedInstanceState.getIntArray(EXTRA_SELECTED) else null
        if (selected != null && galleryMode && listUnit!!.areItemsSelectable()) {
            listUnit!!.startSelectionMode(selected)
        }

        if (newImagePosition == null) {
            val configuration = getResources().getConfiguration()
            if (listUnit != null) {
                listUnit!!.onConfigurationChanged(configuration)
            }
            if (pagerUnit != null) {
                pagerUnit!!.onConfigurationChanged(configuration)
            }
        }
        if (titleSubtitle != null) {
            dialog.setTitleSubtitle(titleSubtitle!!.first, titleSubtitle!!.second)
        }
        val window = getWindow()
        if (window != null) {
            setWindowLayoutFullscreen(window)
        }

        setScreenOnFixed(screenOnFixed)
        invalidateSystemUiVisibility()
    }

    override fun onResume() {
        super.onResume()

        if (pagerUnit != null) {
            pagerUnit!!.onResume()
        }
    }

    override fun onPause() {
        super.onPause()

        if (pagerUnit != null) {
            pagerUnit!!.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Final cleanup (PagerUnit.onFinish) happens in RetainedViewModel.onCleared;
        // here the mutable state is stored for the fragment recreated after a
        // configuration change.
        val retained = this.retained
        if (retained != null) {
            if (retained.current === this) {
                retained.current = null
            }
            retained.galleryWindow = galleryWindow
            retained.galleryMode = galleryMode
            retained.titleSubtitle = titleSubtitle
            retained.screenOnFixed = screenOnFixed
            retained.systemUiVisibilityFlags = systemUiVisibilityFlags
        }
    }

    private fun invalidateListPosition() {
        listUnit!!.scrollListToPosition(pagerUnit!!.currentIndex, true)
    }

    private val returnToGalleryRunnable = Runnable {
        switchMode(true, true)
        invalidateListPosition()
    }

    private fun returnToGallery(): Boolean {
        if (galleryWindow && !galleryMode) {
            pagerUnit!!.onBackToGallery()
            rootView!!.post(returnToGalleryRunnable)
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        if (destroyShowcase(true)) {
            return true
        }
        return returnToGallery()
    }

    override fun onCreateDialogMenu(menu: Menu) {
        if (instance != null) {
            menu.add(0, R.id.menu_save, 0, R.string.save)
                .setIcon(getActionBarIcon(instance!!.context, R.attr.iconActionSave))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
                .setIcon(getActionBarIcon(instance!!.context, R.attr.iconActionRefresh))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, R.id.menu_select, 0, R.string.select)
                .setIcon(getActionBarIcon(instance!!.context, R.attr.iconActionSelect))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    override fun onPrepareDialogMenu(menu: Menu) {
        for (i in 0..<menu.size()) {
            menu.getItem(i).setVisible(false)
        }
        if (!galleryMode) {
            val capabilities = if (pagerUnit != null)
                pagerUnit!!.obtainOptionsMenuCapabilities()
            else
                null
            if (capabilities != null && capabilities.available) {
                menu.findItem(R.id.menu_save).setVisible(capabilities.save)
                menu.findItem(R.id.menu_refresh).setVisible(capabilities.refresh)
            }
            if (pagerUnit != null) {
                pagerUnit!!.invalidatePopupMenu()
            }
        } else {
            menu.findItem(R.id.menu_select).setVisible(listUnit!!.areItemsSelectable())
        }
    }

    override fun onDialogMenuItemSelected(item: MenuItem): Boolean {
        val holder: PagerInstance.ViewHolder =
            (if (pagerUnit != null) pagerUnit!!.currentHolder else null)!!
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == android.R.id.home) {
            dismiss()
        } else if (switchItemId0 == R.id.menu_save) {
            downloadGalleryItem(holder.galleryItem!!)
        } else if (switchItemId0 == R.id.menu_refresh) {
            pagerUnit!!.refreshCurrent()
        } else if (switchItemId0 == R.id.menu_select) {
            listUnit!!.startSelectionMode(null)
        }
        return true
    }

    override fun switchToFlow() {
        val holder = if (pagerUnit != null) pagerUnit!!.currentHolder else null
        if (holder == null || holder.galleryItem == null || instance == null) {
            return
        }
        // Open the video feed at the same attachment, then close the gallery so nothing keeps playing.
        show(
            requireActivity().getSupportFragmentManager(), get(instance!!.chanName),
            instance!!.galleryItems, holder.galleryItem, this.threadTitle
        )
        dismiss()
    }

    override fun switchToPip() {
        val holder = if (pagerUnit != null) pagerUnit!!.currentHolder else null
        if (holder == null || holder.galleryItem == null || instance == null) {
            return
        }
        // Hand playback over to the floating window (it downloads and plays on its own), then
        // close the gallery so the thread is visible behind it and nothing else keeps playing.
        // The item list and navigate mode let an expanded window reopen this gallery later.
        VideoPipActivity.start(
            requireActivity(), get(instance!!.chanName), holder.galleryItem!!,
            null, instance!!.galleryItems, requireArguments().getString(EXTRA_NAVIGATE_POST_MODE),
            this.threadTitle, pagerUnit!!.videoPosition, pagerUnit!!.isVideoPlaying,
            pagerUnit!!.videoDimensions
        )
        dismiss()
    }

    override fun getWindow(): Window {
        val dialog = getDialog()
        return (if (dialog != null) dialog.getWindow() else null)!!
    }

    override fun downloadGalleryItem(galleryItem: GalleryItem) {
        val binder = (requireActivity() as FragmentHandler).getDownloadBinder()
        if (binder != null) {
            galleryItem.downloadStorage(binder, get(instance!!.chanName), this.threadTitle)
        }
    }

    override fun downloadGalleryItems(galleryItems: List<GalleryItem>) {
        var boardName: String? = null
        var threadNumber: String? = null
        val chan = get(instance!!.chanName)
        val requestItems = ArrayList<RequestItem>()
        for (galleryItem in galleryItems) {
            if (requestItems.size == 0) {
                boardName = galleryItem.boardName
                threadNumber = galleryItem.threadNumber
            } else if (boardName != null || threadNumber != null) {
                if (!equals(boardName, galleryItem.boardName) ||
                    !equals(threadNumber, galleryItem.threadNumber)
                ) {
                    // Images from different threads, so don't use them to mark files and folders
                    boardName = null
                    threadNumber = null
                }
            }
            requestItems.add(
                RequestItem(
                    galleryItem.getFileUri(chan),
                    galleryItem.getFileName(chan)!!, galleryItem.originalName
                )
            )
        }
        if (requestItems.size > 0) {
            val binder = (requireActivity() as FragmentHandler).getDownloadBinder()
            if (binder != null) {
                binder.downloadStorage(
                    requestItems, true, instance!!.chanName,
                    boardName, threadNumber, this.threadTitle
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (pagerUnit != null) {
            outState.putInt(EXTRA_POSITION, pagerUnit!!.currentIndex)
        }
        if (listUnit != null) {
            outState.putIntArray(EXTRA_SELECTED, listUnit!!.selectedPositions)
        }
        outState.putBoolean(EXTRA_GALLERY_WINDOW, galleryWindow)
        outState.putBoolean(EXTRA_GALLERY_MODE, galleryMode)
        outState.putBoolean(
            EXTRA_SYSTEM_UI_VISIBILITY,
            get(systemUiVisibilityFlags, GalleryInstance.Flags.LOCKED_USER)
        )
    }

    private fun switchMode(galleryMode: Boolean, animated: Boolean) {
        val duration = if (animated) GALLERY_TRANSITION_DURATION else 0
        pagerUnit!!.switchMode(galleryMode, duration)
        listUnit!!.switchMode(galleryMode, duration)
        if (galleryMode) {
            val count = instance!!.galleryItems.size
            getDialog()!!.setTitleSubtitle(
                getString(R.string.gallery), getResources()
                    .getQuantityString(R.plurals.number_files__format, count, count)
            )
            titleSubtitle = null
        }
        modifySystemUiVisibility(GalleryInstance.Flags.LOCKED_GRID, galleryMode)
        this.galleryMode = galleryMode
        if (galleryMode) {
            CornerAnimator(0xa0, 0xc0)
        } else {
            val alpha = Color.alpha(ACTION_BAR_COLOR)
            CornerAnimator(alpha, alpha)
        }
        invalidateOptionsMenu()
        if (!galleryMode) {
            displayShowcase()
        }
    }

    private inner class CornerAnimator(actionBarAlpha: Int, fallbackAlpha: Int) : Runnable {
        private val startTime = SystemClock.elapsedRealtime()

        private val fromActionBarAlpha: Int
        private val toActionBarAlpha: Int

        init {
            if (cornerAnimator != null) {
                cornerAnimator!!.cancel()
            }
            val drawable = getDialog()!!.actionBarView!!.getBackground()
            fromActionBarAlpha = Color.alpha(
                if (drawable is ColorDrawable)
                    drawable.getColor()
                else
                    fallbackAlpha
            )
            toActionBarAlpha = actionBarAlpha
            if (fromActionBarAlpha != toActionBarAlpha) {
                cornerAnimator = this
                run()
            }
        }

        override fun run() {
            val t = min((SystemClock.elapsedRealtime() - startTime).toFloat() / INTERVAL, 1f)
            val actionBarColorAlpha =
                lerp(fromActionBarAlpha.toFloat(), toActionBarAlpha.toFloat(), t).toInt()
            val dialog = getDialog()
            if (dialog != null) {
                val actionBarColor =
                    (actionBarColorAlpha shl 24) or (0x00ffffff and ACTION_BAR_COLOR)
                dialog.actionBarView!!.setBackgroundColor(actionBarColor)
                val actionContextBar = dialog.actionContextBarView
                if (actionContextBar != null) {
                    actionContextBar.setBackgroundColor(actionBarColor)
                }
                if (t < 1f) {
                    rootView!!.postOnAnimation(this)
                } else if (cornerAnimator === this) {
                    cornerAnimator = null
                }
            }
        }

        fun cancel() {
            rootView!!.removeCallbacks(this)
            if (cornerAnimator === this) {
                cornerAnimator = null
            }
        }

        private val INTERVAL = 200
    }

    override fun onCreateActionContextBarView() {
        val dialog = getDialog()
        val drawable = dialog!!.actionBarView!!.getBackground()
        if (drawable is ColorDrawable) {
            dialog.actionContextBarView!!.setBackgroundColor(drawable.getColor())
        }
    }

    override fun modifyVerticalSwipeState(ignoreIfGallery: Boolean, value: Float) {
        var value = value
        if (ignoreIfGallery || galleryWindow) {
            value = 0f
        }
        rootView!!.getBackground().setAlpha((0xff * (1f - value)).toInt())
    }

    override fun updateTitle() {
        val holder = pagerUnit!!.currentHolder
        if (holder != null && holder.galleryItem != null) {
            setTitle(holder.galleryItem!!, holder.mediaSummary!!, pagerUnit!!.currentIndex)
        }
    }

    private fun setTitle(galleryItem: GalleryItem, mediaSummary: MediaSummary, position: Int) {
        var fileName = galleryItem.getFileName(get(instance!!.chanName))
        if (!isEmpty(galleryItem.originalName)) {
            fileName = galleryItem.originalName
        }
        val count = instance!!.galleryItems.size
        val builder = StringBuilder().append(position + 1).append('/').append(count)
        if (mediaSummary.width > 0 && mediaSummary.height > 0) {
            builder.append(", ").append(mediaSummary.width).append('×').append(mediaSummary.height)
        }
        if (mediaSummary.size > 0) {
            builder.append(", ").append(formatFileSize(mediaSummary.size, false))
        }
        titleSubtitle = Pair<CharSequence?, CharSequence?>(fileName, builder)
        val dialog = getDialog()
        if (dialog != null) {
            dialog.setTitleSubtitle(fileName, builder)
        }
    }

    override fun navigateGalleryOrFinish(enableGalleryMode: Boolean) {
        if (enableGalleryMode && !galleryWindow) {
            galleryWindow = true
        }
        if (!returnToGallery()) {
            getWindow().getDecorView().post(Runnable { this.dismiss() })
        }
    }

    override fun navigatePageFromList(position: Int) {
        switchMode(false, true)
        pagerUnit!!.navigatePageFromList(position, GALLERY_TRANSITION_DURATION)
    }

    private fun checkAllowNavigatePost(manually: Boolean): Boolean {
        val navigatePostMode = this.navigatePostMode
        return navigatePostMode == NavigatePostMode.ENABLED ||
                navigatePostMode == NavigatePostMode.MANUALLY && manually
    }

    override fun navigatePost(galleryItem: GalleryItem, manually: Boolean, force: Boolean) {
        if (checkAllowNavigatePost(manually) && (scrollThread || force)) {
            (requireActivity() as FragmentHandler).scrollToPost(
                instance!!.chanName, galleryItem.boardName,
                galleryItem.threadNumber, galleryItem.postNumber, force
            )
            if (force) {
                dismiss()
            }
        }
    }

    override fun isAllowNavigatePostManually(fromPager: Boolean): Boolean {
        // Don't allow navigate to post from pager if thread is scrolling automatically with pager
        return checkAllowNavigatePost(true) && (!(scrollThread && checkAllowNavigatePost(false)) || !fromPager)
    }

    override fun invalidateOptionsMenu() {
        val dialog = getDialog()
        if (dialog != null) {
            dialog.invalidateOptionsMenu()
        }
    }

    override fun setScreenOnFixed(fixed: Boolean) {
        screenOnFixed = fixed
        val dialog = getDialog()
        if (dialog != null) {
            val window = getWindow()
            if (fixed) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun isGalleryWindow(): Boolean {
        return galleryWindow
    }

    override fun isGalleryMode(): Boolean {
        return galleryMode
    }

    private fun postInvalidateSystemUIVisibility() {
        rootView!!.post(Runnable { this.invalidateSystemUiVisibility() })
    }

    private fun invalidateSystemUiVisibility() {
        val dialog = getDialog()
        if (dialog != null) {
            val actionBar = dialog.getActionBar()
            val visible = isSystemUiVisible()
            val changed = visible != actionBar!!.isShowing()
            if (visible) {
                actionBar.show()
            } else {
                actionBar.hide()
            }
            invalidateSystemUiFlags()
            if (pagerUnit != null) {
                pagerUnit!!.invalidateControlsVisibility()
                if (changed) {
                    pagerUnit!!.invalidatePopupMenu()
                }
            }
        }
    }

    private fun invalidateSystemUiFlags() {
        val visible = isSystemUiVisible()
        val window = getWindow()
        val controller = window.getInsetsController()
        controller!!.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
        if (visible) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun isSystemUiVisible(): Boolean {
        return systemUiVisibilityFlags != 0
    }

    override fun modifySystemUiVisibility(flag: Int, value: Boolean) {
        systemUiVisibilityFlags = set(systemUiVisibilityFlags, flag, value)
        invalidateSystemUiVisibility()
    }

    override fun toggleSystemUIVisibility(flag: Int) {
        modifySystemUiVisibility(flag, !get(systemUiVisibilityFlags, flag))
    }

    private var showcaseDestroy: Runnable? = null

    private fun destroyShowcase(consume: Boolean): Boolean {
        if (showcaseDestroy != null) {
            if (consume) {
                consumeShowcaseGallery()
            }
            showcaseDestroy!!.run()
            showcaseDestroy = null
            return true
        }
        return false
    }

    private fun displayShowcase() {
        if (showcaseDestroy != null || !isShowcaseGalleryEnabled || !rootView!!.isAttachedToWindow()) {
            return
        }

        val context = getWindow().getContext()
        val density = obtainDensity(context)
        val frameLayout = FrameLayout(context)
        frameLayout.setBackgroundColor(-0xfddddde)
        val linearLayout = LinearLayout(context)
        linearLayout.setOrientation(LinearLayout.VERTICAL)
        frameLayout.addView(
            linearLayout, FrameLayout.LayoutParams(
                (304f * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )

        val button = Button(context, null, android.R.attr.borderlessButtonStyle)
        button.setText(R.string.got_it)
        button.setMinimumWidth(0)
        button.setMinWidth(0)

        val paddingLeft = button.getPaddingLeft()
        val paddingRight = button.getPaddingRight()
        val paddingTop = button.getPaddingTop()
        val paddingBottom = max(0, (24f * density).toInt() - paddingTop)

        val titles = intArrayOf(R.string.context_menu, R.string.gallery)
        val messages = intArrayOf(
            R.string.context_menu_description__sentence,
            R.string.gallery_description__sentence
        )

        for (i in titles.indices) {
            val textView1 = TextView(context, null, android.R.attr.textAppearanceLarge)
            textView1.setText(titles[i])
            textView1.setTypeface(ResourceUtils.TYPEFACE_LIGHT)
            textView1.setPadding(paddingLeft, paddingTop, paddingRight, (4f * density).toInt())
            val textView2 = TextView(context, null, android.R.attr.textAppearanceSmall)
            textView2.setText(messages[i])
            textView2.setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            linearLayout.addView(
                textView1, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            linearLayout.addView(
                textView2, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        linearLayout.addView(
            button,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.format = PixelFormat.TRANSLUCENT
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        layoutParams.windowAnimations = R.style.Animation_Gallery_Full
        layoutParams.flags =
            layoutParams.flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        windowManager.addView(frameLayout, layoutParams)

        showcaseDestroy = Runnable { windowManager.removeViewImmediate(frameLayout) }
        button.setOnClickListener(View.OnClickListener { v: View? -> destroyShowcase(true) })
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_IMAGE_INDEX = "imageIndex"
        private const val EXTRA_THREAD_TITLE = "threadTitle"
        private const val EXTRA_NAVIGATE_POST_MODE = "navigatePostMode"
        private const val EXTRA_INITIAL_GALLERY_MODE = "initialGalleryMode"
        private const val EXTRA_INITIAL_VIDEO_POSITION = "initialVideoPosition"

        private const val EXTRA_POSITION = "position"
        private const val EXTRA_SELECTED = "selected"
        private const val EXTRA_GALLERY_WINDOW = "galleryWindow"
        private const val EXTRA_GALLERY_MODE = "galleryMode"
        private const val EXTRA_SYSTEM_UI_VISIBILITY = "systemUiVisibility"

        private const val ACTION_BAR_COLOR = -0x55dfdfe0
        private const val BACKGROUND_COLOR = -0xfefeff0

        private const val GALLERY_TRANSITION_DURATION = 150
    }
}
