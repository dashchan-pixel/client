package com.mishiranu.dashchan.ui

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Pair
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.OnHierarchyChangeListener
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toolbar
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanConfiguration
import chan.content.ChanLocator.NavigationData
import chan.content.ChanManager
import chan.util.CommonUtils.equals
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.LocaleManager.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.DrawerInitialPosition
import com.mishiranu.dashchan.content.Preferences.drawerInitialPosition
import com.mishiranu.dashchan.content.Preferences.getDefaultBoardName
import com.mishiranu.dashchan.content.Preferences.getDownloadUriTree
import com.mishiranu.dashchan.content.Preferences.isCheckUpdatesOnStart
import com.mishiranu.dashchan.content.Preferences.isCloseOnBack
import com.mishiranu.dashchan.content.Preferences.isDrawerLocked
import com.mishiranu.dashchan.content.Preferences.isExpandedScreen
import com.mishiranu.dashchan.content.Preferences.isMergeChans
import com.mishiranu.dashchan.content.Preferences.isSfwMode
import com.mishiranu.dashchan.content.Preferences.isShowMyPosts
import com.mishiranu.dashchan.content.Preferences.isShowSpoilers
import com.mishiranu.dashchan.content.Preferences.isUseInternalBrowser
import com.mishiranu.dashchan.content.Preferences.lastUpdateCheck
import com.mishiranu.dashchan.content.Preferences.setDownloadUriTree
import com.mishiranu.dashchan.content.async.ReadUpdateTask
import com.mishiranu.dashchan.content.async.ReadUpdateTask.UpdateDataMap
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseNullable
import com.mishiranu.dashchan.content.service.AudioPlayerService.Companion.start
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.service.DownloadService.ChoiceRequest
import com.mishiranu.dashchan.content.service.DownloadService.DirectRequest
import com.mishiranu.dashchan.content.service.DownloadService.PrepareRequest
import com.mishiranu.dashchan.content.service.DownloadService.ReplaceRequest
import com.mishiranu.dashchan.content.service.PostingService
import com.mishiranu.dashchan.content.service.PostingService.Companion.clearNewThreadData
import com.mishiranu.dashchan.content.service.PostingService.FailResult
import com.mishiranu.dashchan.content.service.PostingService.GlobalCallback
import com.mishiranu.dashchan.content.service.WatcherService
import com.mishiranu.dashchan.content.service.WatcherService.Companion.getClient
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem
import com.mishiranu.dashchan.ui.EasterEgg.maybeShow
import com.mishiranu.dashchan.ui.ExtensionsTrustLoop.handleUntrustedExtensions
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay.NavigatePostMode
import com.mishiranu.dashchan.ui.gallery.VideoPipActivity.Companion.reopenInApp
import com.mishiranu.dashchan.ui.navigator.Page
import com.mishiranu.dashchan.ui.navigator.PageFragment
import com.mishiranu.dashchan.ui.navigator.PageItem
import com.mishiranu.dashchan.ui.navigator.SavedPageItem
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.LocalNavigator
import com.mishiranu.dashchan.ui.navigator.page.ListPage.InitRequest
import com.mishiranu.dashchan.ui.navigator.page.ListPage.Retainable
import com.mishiranu.dashchan.ui.posting.PostingFragment
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.ui.preference.CategoriesFragment
import com.mishiranu.dashchan.ui.preference.CommandsFragment
import com.mishiranu.dashchan.ui.preference.ThemesFragment
import com.mishiranu.dashchan.ui.preference.UpdateFragment
import com.mishiranu.dashchan.ui.preference.UpdateFragment.Companion.checkNewVersions
import com.mishiranu.dashchan.util.AndroidUtils.createHeadsUpNotificationChannel
import com.mishiranu.dashchan.util.AndroidUtils.getApplicationLabel
import com.mishiranu.dashchan.util.ConcatIterable
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.DrawerToggle
import com.mishiranu.dashchan.util.FlagUtils.get
import com.mishiranu.dashchan.util.FlagUtils.set
import com.mishiranu.dashchan.util.IOUtils.copyStream
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.NavigationUtils.handleUri
import com.mishiranu.dashchan.util.NavigationUtils.isOpenableVideoPath
import com.mishiranu.dashchan.util.NavigationUtils.restartApplication
import com.mishiranu.dashchan.util.ResourceUtils.getColonString
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.isDrawerLockable
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ClickableToast.Companion.cancel
import com.mishiranu.dashchan.widget.ClickableToast.Companion.register
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.CustomDrawerLayout
import com.mishiranu.dashchan.widget.ExpandedScreen
import com.mishiranu.dashchan.widget.ExpandedScreen.PreThemeInit
import com.mishiranu.dashchan.widget.PredictiveBackTransform
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.addTheme
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.applyTheme
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.attach
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import com.mishiranu.dashchan.widget.ViewFactory.ToolbarHolder
import com.mishiranu.dashchan.widget.ViewFactory.addToolbarTitle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.Collections
import java.util.UUID
import kotlin.math.min

class MainActivity :
    StateActivity(),
    DrawerForm.Callback,
    ThemeDialog.Callback,
    FavoritesStorage.Observer,
    WatcherService.Client.Callback,
    UiManager.Callback,
    LocalNavigator,
    FragmentHandler,
    PageFragment.Callback {
    private enum class StorageRequestState {
        NONE,
        INSTRUCTIONS,
        PICKER,
    }

    private val fragments = ArrayList<StackItem>()
    private val stackPageItems = ArrayList<SavedPageItem>()
    private val preservedPageItems = ArrayList<SavedPageItem>()
    private var currentPageItem: PageItem? = null

    override lateinit var uiManager: UiManager
    private lateinit var instanceViewModel: InstanceViewModel
    private lateinit var watcherServiceClient: WatcherService.Client
    private val extensionsTrustLoopState = ExtensionsTrustLoop.State()
    private lateinit var downloadDialog: DownloadDialog

    private lateinit var drawerForm: DrawerForm
    private lateinit var drawerParent: FrameLayout
    private lateinit var drawerLayout: CustomDrawerLayout
    private lateinit var drawerToggle: DrawerToggle
    private val navigationAreaLockers = HashSet<String?>()

    private lateinit var expandedScreen: ExpandedScreen
    private var toolbarHolder: ToolbarHolder? = null
    private lateinit var toolbarExtra: FrameLayout

    private lateinit var backTransform: PredictiveBackTransform

    private lateinit var drawerCommon: ViewGroup
    private lateinit var drawerWide: ViewGroup
    private var wideMode = false

    private var navigateIntentOnResume: Intent? = null
    private var storageRequestState: StorageRequestState = StorageRequestState.NONE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(attach(LocaleManager.getInstance().apply(newBase)))
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        var savedState = savedInstanceState
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        requestWindowFeature(Window.FEATURE_ACTION_MODE_OVERLAY)

        val expandedScreenPreThemeInit = PreThemeInit(this, isExpandedScreen)
        applyTheme(this)
        val expandedScreenInit = expandedScreenPreThemeInit.initAfterTheme()
        super.onCreate(savedState)
        // ExpandedScreen should handle this for R+
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val density = obtainDensity(this)
        setContentView(R.layout.activity_main)
        register(this)
        ForegroundManager.Companion.getInstance().register(this)
        FavoritesStorage.getInstance().getObservable().register(this)
        Preferences.prefs.register(preferencesListener)
        ChanManager.getInstance().observable.register(chanManagerCallback)
        watcherServiceClient = getClient(this)
        watcherServiceClient.callback = this
        drawerCommon = findViewById(R.id.drawer_common)
        drawerWide = findViewById(R.id.drawer_wide)
        val theme = getTheme(this)
        val drawerContext: Context?
        val drawerBackground: Int
        drawerContext = this
        drawerBackground = theme.card

        drawerCommon.setBackgroundColor(drawerBackground)
        drawerWide.setBackgroundColor(drawerBackground)
        drawerForm =
            DrawerForm(drawerContext, this, getSupportFragmentManager(), watcherServiceClient)
        drawerParent = FrameLayout(this)
        drawerParent.addView(drawerForm.contentView)
        drawerCommon.addView(drawerParent)
        drawerLayout = findViewById(R.id.drawer_layout)
        drawerLayout.setSaveEnabled(false)
        val drawerInterlayer = findViewById<FrameLayout>(R.id.drawer_interlayer)
        getLayoutInflater().inflate(R.layout.widget_toolbar, drawerInterlayer)
        // The toolbar ids come from widget_toolbar, not from the activity layout,
        // so they are resolved against the container it was just inflated into.
        val toolbar = drawerInterlayer.findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        setTitle(null)
        // Allow CustomSearchView to ignore content inset
        toolbar.setClipChildren(false)
        toolbarHolder = addToolbarTitle(toolbar)
        setupToolbarTitleToggle(toolbar)
        toolbarExtra = drawerInterlayer.findViewById(R.id.toolbar_extra)
        val layoutTransition = LayoutTransition()
        layoutTransition.setStartDelay(LayoutTransition.APPEARING, 0)
        layoutTransition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
        layoutTransition.setDuration(100)
        toolbarExtra.setLayoutTransition(layoutTransition)

        val toolbarLayout = drawerInterlayer.findViewById<View>(R.id.toolbar_layout)

        drawerToggle =
            DrawerToggle(
                this,
                toolbarHolder?.toolbar?.getContext(),
                drawerLayout,
            )
        drawerCommon.setElevation(4f * density)
        drawerWide.setElevation(4f * density)

        drawerLayout.addDrawerListener(drawerToggle)
        drawerLayout.addDrawerListener(drawerForm)
        if (toolbarHolder == null) {
            drawerLayout.addDrawerListener(ExpandedScreenDrawerLocker())
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        drawerLayout.addDrawerListener(
            object : SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    updateBackHandling()
                }

                override fun onDrawerClosed(drawerView: View) {
                    updateBackHandling()
                }
            },
        )

        downloadDialog =
            DownloadDialog(
                this,
                object : DownloadDialog.Callback {
                    override fun resolve(
                        choiceRequest: ChoiceRequest,
                        directRequest: DirectRequest?,
                    ) {
                        if (downloadBinderField != null) {
                            downloadBinderField?.resolve(choiceRequest, directRequest)
                        }
                    }

                    override fun resolve(
                        replaceRequest: ReplaceRequest,
                        action: ReplaceRequest.Action?,
                    ) {
                        if (downloadBinderField != null) {
                            downloadBinderField?.resolve(replaceRequest, action)
                        }
                    }

                    override fun cancel(prepareRequest: PrepareRequest) {
                        if (downloadBinderField != null) {
                            downloadBinderField?.cancel(prepareRequest)
                        }
                    }
                },
            )

        updateWideConfiguration(true)
        expandedScreen =
            ExpandedScreen(
                expandedScreenInit,
                drawerLayout,
                toolbarLayout,
                drawerInterlayer,
                drawerParent,
                drawerForm.contentView,
                drawerForm.headerView,
            )
        expandedScreen.setDrawerOverToolbarEnabled(!wideMode)
        uiManager = UiManager(this, this, this)
        uiManager.attach(this)
        ContentFragment.Companion.prepare(this)
        val contentFragment = findViewById<ViewGroup>(R.id.content_fragment)
        backTransform = PredictiveBackTransform(contentFragment)
        contentFragment.setOnHierarchyChangeListener(
            object : OnHierarchyChangeListener {
                override fun onChildViewAdded(
                    parent: View?,
                    child: View?,
                ) {
                    expandedScreen.addContentView(child)
                }

                override fun onChildViewRemoved(
                    parent: View?,
                    child: View?,
                ) {
                    expandedScreen.removeContentView(child)
                }
            },
        )
        bindService(Intent(this, PostingService::class.java), postingConnection, BIND_AUTO_CREATE)
        bindService(Intent(this, DownloadService::class.java), downloadConnection, BIND_AUTO_CREATE)
        val allowSelectChan = ChanManager.getInstance().hasMultipleAvailableChans()
        if (savedState == null) {
            maybeShow(this)
            val drawerInitialPosition = drawerInitialPosition
            if (drawerInitialPosition != DrawerInitialPosition.CLOSED) {
                if (!wideMode) {
                    drawerLayout.post(Runnable { drawerLayout.openDrawer(GravityCompat.START) })
                }
                if (drawerInitialPosition == DrawerInitialPosition.FORUMS) {
                    drawerForm.setChanSelectMode(allowSelectChan)
                }
            }
        } else {
            if (!wideMode && savedState.getBoolean(EXTRA_DRAWER_EXPANDED)) {
                drawerLayout.openDrawer(GravityCompat.START)
            }
            drawerForm.setChanSelectMode(
                allowSelectChan &&
                    savedState.getBoolean(EXTRA_DRAWER_CHAN_SELECT_MODE),
            )
        }

        instanceViewModel =
            ViewModelProvider(this).get<InstanceViewModel>(InstanceViewModel::class.java)
        storageRequestState =
            if (savedState != null) {
                StorageRequestState.valueOf(
                    savedState.getString(
                        EXTRA_STORAGE_REQUEST_STATE,
                    )!!,
                )
            } else {
                StorageRequestState.NONE
            }

        var currentFragmentFromSaved: ContentFragment? = null
        if (savedState == null) {
            val file = this.savedPagesFile
            if (file != null && file.exists()) {
                val parcel = Parcel.obtain()
                val output = ByteArrayOutputStream()
                try {
                    FileInputStream(file).use { input ->
                        copyStream(input, output)
                        val data = output.toByteArray()
                        parcel.unmarshall(data, 0, data.size)
                        parcel.setDataPosition(0)
                        val bundle = Bundle()
                        bundle.setClassLoader(javaClass.getClassLoader())
                        bundle.readFromParcel(parcel)
                        savedState = bundle
                    }
                } catch (e: IOException) {
                    // Ignore
                } finally {
                    parcel.recycle()
                    file.delete()
                }
            }
            if (savedState != null) {
                currentFragmentFromSaved =
                    BundleCompat
                        .getParcelable(
                            savedState,
                            MainActivity.Companion.EXTRA_CURRENT_FRAGMENT,
                            StackItem::class.java,
                        )?.create(null) as? ContentFragment
                if (currentFragmentFromSaved == null) {
                    savedState = null
                }
            }
        }

        if (savedState != null) {
            fragments.addAll(
                BundleCompat.getParcelableArrayList(
                    savedState,
                    MainActivity.Companion.EXTRA_FRAGMENTS,
                    StackItem::class.java,
                )!!,
            )
            stackPageItems.addAll(
                BundleCompat.getParcelableArrayList(
                    savedState,
                    MainActivity.Companion.EXTRA_STACK_PAGE_ITEMS,
                    SavedPageItem::class.java,
                )!!,
            )
            preservedPageItems.addAll(
                BundleCompat.getParcelableArrayList(
                    savedState,
                    MainActivity.Companion.EXTRA_PRESERVED_PAGE_ITEMS,
                    SavedPageItem::class.java,
                )!!,
            )
            currentPageItem =
                BundleCompat.getParcelable<PageItem?>(
                    savedState,
                    EXTRA_CURRENT_PAGE_ITEM,
                    PageItem::class.java,
                )
        }
        val iterator: MutableIterator<SavedPageItem> =
            ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
        while (iterator.hasNext()) {
            if (get(getSavedPage(iterator.next()).chanName).name == null) {
                iterator.remove()
            }
        }
        if (currentFragmentFromSaved != null) {
            if (currentFragmentFromSaved is PageFragment &&
                get(currentFragmentFromSaved.page.chanName).name == null
            ) {
                currentFragmentFromSaved = null
                currentPageItem = null
            }
            if (currentFragmentFromSaved == null && !stackPageItems.isEmpty()) {
                val pair =
                    stackPageItems.removeAt(stackPageItems.size - 1).create()
                currentFragmentFromSaved = pair.first
                currentPageItem = pair.second
            }
            if (currentFragmentFromSaved != null) {
                getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content_fragment, currentFragmentFromSaved)
                    .commit()
                updatePostFragmentConfiguration()
            }
        } else {
            var currentFragment = this.currentFragment
            if (currentFragment is PageFragment &&
                get(currentFragment.page.chanName).name == null
            ) {
                currentFragment = null
                currentPageItem = null
            }
            if (currentFragment != null) {
                updatePostFragmentConfiguration()
            }
        }

        if (!get(getIntent().getFlags(), Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) &&
            savedState == null
        ) {
            navigateIntent(getIntent(), false)
        }
        if (this.currentFragment == null) {
            if (!navigateInitial(false)) {
                show(
                    getString(R.string.no_extensions_installed),
                    null,
                    ClickableToast.Button(
                        R.string.install,
                        false,
                        Runnable {
                            if (this.currentFragment is UpdateFragment) {
                                navigateFragment(UpdateFragment(), null, true)
                            } else {
                                pushFragment(UpdateFragment())
                            }
                        },
                    ),
                )
            }
        }

        startUpdateTask(savedState == null)
        handleUntrustedExtensions(this, extensionsTrustLoopState)
        if (storageRequestState == StorageRequestState.INSTRUCTIONS) {
            showStorageInstructionsDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        navigateIntent(intent, true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        writePagesState(outState)
        outState.putBoolean(EXTRA_DRAWER_EXPANDED, drawerLayout.isDrawerOpen(GravityCompat.START))
        outState.putBoolean(EXTRA_DRAWER_CHAN_SELECT_MODE, drawerForm.isChanSelectMode())
        outState.putString(EXTRA_STORAGE_REQUEST_STATE, storageRequestState.name)
    }

    private fun writePagesState(outState: Bundle) {
        outState.putParcelableArrayList(EXTRA_FRAGMENTS, fragments)
        outState.putParcelableArrayList(EXTRA_STACK_PAGE_ITEMS, stackPageItems)
        outState.putParcelableArrayList(EXTRA_PRESERVED_PAGE_ITEMS, preservedPageItems)
        outState.putParcelable(EXTRA_CURRENT_PAGE_ITEM, currentPageItem)
    }

    private val savedPagesFile: File?
        get() = CacheManager.getInstance().getInternalCacheFile("saved-pages")

    private val openUriTreeLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartActivityForResult(),
        ) { result ->
            val cancel = result.resultCode != RESULT_OK
            storageRequestState = StorageRequestState.NONE
            val data: Intent? = result.data
            if (!cancel && data != null) {
                setDownloadUriTree(this, data.getData(), data.getFlags())
            }
            handleStorageRequestResult(cancel)
        }

    private val currentFragment: ContentFragment?
        get() {
            val fragmentManager = getSupportFragmentManager()
            try {
                fragmentManager.executePendingTransactions()
            } catch (e: IllegalStateException) {
                // Ignore
            }
            return fragmentManager.findFragmentById(R.id.content_fragment) as ContentFragment?
        }

    override fun setTitleSubtitle(
        title: CharSequence?,
        subtitle: CharSequence?,
    ) {
        setTitleSubtitle(title, subtitle, false)
    }

    private fun setTitleSubtitle(
        title: CharSequence?,
        subtitle: CharSequence?,
        fromPage: Boolean,
    ) {
        toolbarTitleFromPage = fromPage
        toolbarHolder!!.update(title, subtitle)
        updateToolbarTitleVisibility()
    }

    // The title of a board or thread page can be hidden by a double tap on the toolbar.
    private var toolbarTitleFromPage = false

    private fun updateToolbarTitleVisibility() {
        val toolbarHolder = this.toolbarHolder ?: return
        val hide = toolbarTitleFromPage && Preferences.isHideToolbarTitle
        toolbarHolder.layout.setVisibility(if (hide) View.GONE else View.VISIBLE)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupToolbarTitleToggle(toolbar: Toolbar) {
        val gestureDetector =
            GestureDetector(
                toolbar.getContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (!toolbarTitleFromPage) {
                            return false
                        }
                        Preferences.isHideToolbarTitle = !Preferences.isHideToolbarTitle
                        updateToolbarTitleVisibility()
                        return true
                    }
                },
            )
        // Children (navigation button, menu items, search view) handle their own touches,
        // so only taps on the toolbar background and on the title itself arrive here.
        toolbar.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    override fun getToolbarView(): ViewGroup = checkNotNull(toolbarHolder).toolbar

    override fun getToolbarExtra(): FrameLayout = toolbarExtra

    override fun getToolbarContext(): Context = toolbarHolder?.toolbar?.getContext() ?: this

    override fun navigateBoardsOrThreads(
        chanName: String?,
        boardName: String?,
    ) {
        navigateBoardsOrThreads(chanName, boardName, false, false)
    }

    private fun navigateBoardsOrThreads(
        chanName: String?,
        boardName: String?,
        fromCache: Boolean,
        allowReturn: Boolean,
    ) {
        navigateData(
            chanName,
            boardName,
            null,
            null,
            null,
            null,
            FLAG_DATA_CLOSE_OVERLAYS or
                (if (fromCache) FLAG_DATA_FROM_CACHE else 0) or (if (allowReturn) FLAG_DATA_ALLOW_RETURN else 0),
        )
    }

    override fun navigatePosts(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        threadTitle: String?,
    ) {
        navigatePosts(chanName, boardName, threadNumber, postNumber, threadTitle, false, false)
    }

    private fun navigatePosts(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        threadTitle: String?,
        fromCache: Boolean,
        allowReturn: Boolean,
    ) {
        navigateData(
            chanName,
            boardName,
            threadNumber,
            postNumber,
            threadTitle,
            null,
            FLAG_DATA_CLOSE_OVERLAYS or
                (if (fromCache) FLAG_DATA_FROM_CACHE else 0) or (if (allowReturn) FLAG_DATA_ALLOW_RETURN else 0),
        )
    }

    override fun navigateSearch(
        chanName: String?,
        boardName: String?,
        searchQuery: String?,
    ) {
        navigateSearch(chanName, boardName, searchQuery, false)
    }

    private fun navigateSearch(
        chanName: String?,
        boardName: String?,
        searchQuery: String?,
        allowReturn: Boolean,
    ) {
        navigateData(
            chanName,
            boardName,
            null,
            null,
            null,
            searchQuery,
            FLAG_DATA_CLOSE_OVERLAYS or
                (if (allowReturn) FLAG_DATA_ALLOW_RETURN else 0),
        )
    }

    override fun navigateArchive(
        chanName: String?,
        boardName: String?,
    ) {
        navigatePage(
            Page.Content.ARCHIVE,
            chanName,
            boardName,
            null,
            null,
            null,
            null,
            FLAG_PAGE_CLOSE_OVERLAYS,
        )
    }

    override fun navigateTargetAllowReturn(
        chanName: String?,
        navigationData: NavigationData,
    ) {
        when (navigationData.target) {
            NavigationData.Target.THREADS -> {
                navigateBoardsOrThreads(chanName, navigationData.boardName, false, true)
            }

            NavigationData.Target.POSTS -> {
                navigatePosts(
                    chanName,
                    navigationData.boardName,
                    navigationData.threadNumber,
                    navigationData.postNumber,
                    null,
                    false,
                    true,
                )
            }

            NavigationData.Target.SEARCH -> {
                navigateSearch(chanName, navigationData.boardName, navigationData.searchQuery, true)
            }
        }
    }

    override fun navigatePosting(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        vararg data: ReplyData?,
    ) {
        fragments.clear()
        navigateFragment(
            PostingFragment(
                chanName,
                boardName,
                threadNumber,
                Arrays.asList<ReplyData?>(*data),
            ),
            null,
            true,
        )
    }

    override fun navigateGallery(
        chanName: String?,
        gallerySet: GalleryItem.Set,
        imageIndex: Int,
        view: View?,
        navigatePostMode: NavigatePostMode?,
        galleryMode: Boolean,
    ) {
        val galleryItems = gallerySet.createList()
        val effectiveNavigatePostMode =
            if (gallerySet.isNavigatePostSupported()) {
                navigatePostMode
            } else {
                NavigatePostMode.DISABLED
            }
        navigateOrCloseGallery(
            GalleryOverlay(
                chanName,
                galleryItems,
                imageIndex,
                gallerySet.getThreadTitle(),
                view,
                effectiveNavigatePostMode!!,
                galleryMode,
            ),
        )
    }

    private fun navigateGalleryUri(uri: Uri?) {
        navigateOrCloseGallery(GalleryOverlay(uri))
    }

    private fun navigateOrCloseGallery(galleryOverlay: GalleryOverlay?) {
        val fragmentManager = getSupportFragmentManager()
        val tag = GalleryOverlay::class.java.getName()
        val currentGalleryOverlay = fragmentManager.findFragmentByTag(tag) as GalleryOverlay?
        if (currentGalleryOverlay != null) {
            currentGalleryOverlay.dismiss()
        }
        if (galleryOverlay != null) {
            galleryOverlay.show(fragmentManager, tag)
        }
    }

    override fun navigateSetTheme(theme: ThemeEngine.Theme) {
        ConcurrentUtils.HANDLER.post(
            Runnable {
                if (addTheme(theme)) {
                    ThemeEngine.setCurrentTheme(this, theme.name)
                    recreate()
                }
            },
        )
    }

    override fun navigateAddCommand(import: CommandsStorage.Import) {
        val commands = import.commands
        if (commands.isEmpty()) {
            return
        }
        val storage = CommandsStorage.getInstance()
        // The libraries first: a command is stored referencing them by name, so they have to exist
        // before it runs. One the user already has is kept as it is.
        storage.addMissingLibraries(import.libraries)
        for (command in commands) {
            storage.add(command)
        }
        // Open the Commands screen so the added command is visible for review/edit (its parsed scope may
        // need adjusting); a single command opens straight into its editor.
        val fragment =
            if (commands.size == 1) CommandsFragment(commands[0].id) else CommandsFragment()
        navigateFragment(fragment, null, true)
    }

    override fun navigateCommands() {
        navigateFragment(CommandsFragment(), null, true)
    }

    override fun scrollToPost(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        navigateIfNeeded: Boolean,
    ) {
        val fragment = this.currentFragment
        if (fragment is PageFragment) {
            val page = fragment.page
            if (page.content == Page.Content.POSTS &&
                page.chanName == chanName &&
                equals(page.boardName, boardName) &&
                page.threadNumber == threadNumber
            ) {
                fragment.scrollToPost(postNumber)
                return
            }
        }
        if (navigateIfNeeded) {
            navigatePosts(chanName, boardName, threadNumber, postNumber, null, true, false)
        }
    }

    private fun navigateIntent(
        intent: Intent,
        newIntent: Boolean,
    ) {
        if (newIntent) {
            navigateIntentOnResume = intent
        } else {
            navigateIntentUnchecked(intent)
        }
    }

    private fun navigateIntentUnchecked(intent: Intent) {
        val updateDataMap =
            IntentCompat.getParcelableExtra<UpdateDataMap?>(
                intent,
                C.EXTRA_UPDATE_DATA_MAP,
                UpdateDataMap::class.java,
            )
        if (updateDataMap != null) {
            fragments.clear()
            navigateFragment(UpdateFragment(updateDataMap), null, true)
        } else if (C.ACTION_POSTING == intent.getAction()) {
            val chanName = intent.getStringExtra(C.EXTRA_CHAN_NAME)
            val boardName = intent.getStringExtra(C.EXTRA_BOARD_NAME)
            val threadNumber = intent.getStringExtra(C.EXTRA_THREAD_NUMBER)
            val failResult =
                IntentCompat.getParcelableExtra<FailResult?>(
                    intent,
                    C.EXTRA_FAIL_RESULT,
                    FailResult::class.java,
                )
            var currentFragment = this.currentFragment
            var replace = true
            if (currentFragment is PostingFragment &&
                currentFragment.check(chanName, boardName, threadNumber)
            ) {
                replace = false
            }
            if (replace) {
                fragments.clear()
                navigateFragment(
                    PostingFragment(
                        chanName,
                        boardName,
                        threadNumber,
                        mutableListOf<ReplyData?>(),
                    ),
                    null,
                    true,
                )
                currentFragment = this.currentFragment
            }
            if (failResult != null) {
                (currentFragment as PostingFragment).handleFailResult(failResult)
            }
        } else if (C.ACTION_GALLERY == intent.getAction()) {
            navigateGalleryUri(intent.getData())
        } else if (C.ACTION_PLAYER == intent.getAction()) {
            val fragmentManager = getSupportFragmentManager()
            val tag = AudioPlayerDialog::class.java.getName()
            if (fragmentManager.findFragmentByTag(tag) == null) {
                AudioPlayerDialog().show(fragmentManager, tag)
            }
        } else if (C.ACTION_VIDEO_PIP == intent.getAction()) {
            // An expanded picture-in-picture window handing playback back to its origin
            reopenInApp(this)
        } else if (intent.getBooleanExtra(C.EXTRA_OPEN_ECHO, false) && Preferences.isEcho) {
            // A reply notification: the reply itself is collected in the Echo
            navigateEcho(intent.getStringExtra(C.EXTRA_CHAN_NAME))
        } else {
            val uri = intent.getData()
            if (uri != null) {
                if (intent.getBooleanExtra(C.EXTRA_FROM_CLIENT, false) || !navigateUri(uri)) {
                    show(R.string.unknown_address)
                }
            } else {
                val chanName = intent.getStringExtra(C.EXTRA_CHAN_NAME)
                val boardName = intent.getStringExtra(C.EXTRA_BOARD_NAME)
                val threadNumber = intent.getStringExtra(C.EXTRA_THREAD_NUMBER)
                val postNumber = parseNullable(intent.getStringExtra(C.EXTRA_POST_NUMBER))
                navigateData(
                    chanName,
                    boardName,
                    threadNumber,
                    postNumber,
                    null,
                    null,
                    FLAG_DATA_CLOSE_OVERLAYS,
                )
            }
        }
    }

    private fun navigateEcho(chanName: String?) {
        // The chan of the reply may have been uninstalled since the notification was posted
        var targetChanName = get(chanName).name
        if (targetChanName == null) {
            targetChanName = ChanManager.getInstance().defaultChan?.name
        }
        if (targetChanName != null) {
            navigatePage(
                Page.Content.ECHO,
                targetChanName,
                null,
                null,
                null,
                null,
                null,
                FLAG_PAGE_CLOSE_OVERLAYS or FLAG_PAGE_RESET_SCROLL,
            )
        }
    }

    private fun navigateUri(uri: Uri?): Boolean {
        val chan = getPreferred(null, uri)
        if (chan.name != null) {
            val boardUri = chan.locator.safe(false).isBoardUri(uri)
            val threadUri = chan.locator.safe(false).isThreadUri(uri)
            val boardName =
                if (boardUri || threadUri) chan.locator.safe(false).getBoardName(uri) else null
            val threadNumber =
                if (threadUri) chan.locator.safe(false).getThreadNumber(uri) else null
            val postNumber = if (threadUri) chan.locator.safe(false).getPostNumber(uri) else null
            if (boardUri) {
                navigateData(
                    chan.name,
                    boardName,
                    null,
                    null,
                    null,
                    null,
                    FLAG_DATA_CLOSE_OVERLAYS or FLAG_DATA_ALLOW_RETURN,
                )
                return true
            } else if (threadUri) {
                navigateData(
                    chan.name,
                    boardName,
                    threadNumber,
                    postNumber,
                    null,
                    null,
                    FLAG_DATA_CLOSE_OVERLAYS or FLAG_DATA_ALLOW_RETURN,
                )
                return true
            } else if (chan.locator.isImageUri(uri)) {
                navigateGalleryUri(uri)
                return true
            } else if (uri != null && chan.locator.isAudioUri(uri)) {
                // isAudioUri/isVideoUri already imply uri != null; the check is spelled out so
                // the compiler can smart-cast it for createAttachmentFileName.
                start(this, chan.name, uri, chan.locator.createAttachmentFileName(uri))
                return true
            } else if (uri != null && chan.locator.isVideoUri(uri)) {
                val fileName = chan.locator.createAttachmentFileName(uri)
                if (isOpenableVideoPath(fileName)) {
                    navigateGalleryUri(chan.locator.convert(uri))
                } else {
                    handleUri(
                        this,
                        chan.name,
                        chan.locator.convert(uri)!!,
                        NavigationUtils.BrowserType.EXTERNAL,
                    )
                }
                return true
            } else if (isUseInternalBrowser) {
                handleUri(
                    this,
                    chan.name,
                    chan.locator.convert(uri)!!,
                    NavigationUtils.BrowserType.INTERNAL,
                )
                return true
            }
        }
        return false
    }

    private fun getSavedPage(savedPageItem: SavedPageItem): Page {
        REFERENCE_FRAGMENT.setArguments(savedPageItem.stackItem!!.arguments)
        return REFERENCE_FRAGMENT.page
    }

    private fun getPagesStackSize(chanName: String?): Int {
        val mergeChans = isMergeChans
        var size = 0
        val currentFragment = this.currentFragment
        if (currentFragment is PageFragment &&
            currentPageItem != null &&
            (mergeChans || (currentFragment.page.chanName == chanName))
        ) {
            size++
        }
        for (savedPageItem in stackPageItems) {
            if (mergeChans || getSavedPage(savedPageItem).chanName == chanName) {
                size++
            }
        }
        return size
    }

    private fun prepareTargetPreviousPage(allowForeignChan: Boolean): SavedPageItem? {
        if (allowForeignChan && currentPageItem!!.allowReturn && !stackPageItems.isEmpty()) {
            return stackPageItems.removeAt(stackPageItems.size - 1)
        }
        val currentFragment = this.currentFragment
        val chanName = (currentFragment as PageFragment).page.chanName
        val mergeChans = isMergeChans
        for (i in stackPageItems.indices.reversed()) {
            val savedPageItem = stackPageItems[i]
            if (mergeChans || getSavedPage(savedPageItem).chanName == chanName) {
                stackPageItems.removeAt(i)
                return savedPageItem
            }
        }
        return null
    }

    private fun clearStackAndCurrent() {
        val currentFragment = this.currentFragment
        val mergeChans = isMergeChans
        val closeOnBack = isCloseOnBack
        val chanName = (currentFragment as PageFragment).page.chanName
        val iterator = stackPageItems.iterator()
        while (iterator.hasNext()) {
            val savedPageItem = iterator.next()
            val page = getSavedPage(savedPageItem)
            if (mergeChans || page.chanName == chanName) {
                iterator.remove()
                if (!(page.canDestroyIfNotInStack() || (closeOnBack && page.isThreadsOrPosts))) {
                    preservedPageItems.add(savedPageItem)
                }
            }
        }
        val page = currentFragment.page
        if (mergeChans || page.chanName == chanName) {
            if (!(page.canDestroyIfNotInStack() || (closeOnBack && page.isThreadsOrPosts))) {
                preservedPageItems.add(
                    currentPageItem!!.toSaved(
                        getSupportFragmentManager(),
                        currentFragment,
                    ),
                )
            }
            currentPageItem = null
        }
    }

    private fun navigateInitial(closeOverlays: Boolean): Boolean {
        currentPageItem = null
        val chan = ChanManager.getInstance().defaultChan
        if (chan != null) {
            navigateData(
                chan.name,
                getDefaultBoardName(chan),
                null,
                null,
                null,
                null,
                if (closeOverlays) FLAG_DATA_CLOSE_OVERLAYS else 0,
            )
            return true
        } else {
            navigateFragment(CategoriesFragment(), null, closeOverlays)
            return false
        }
    }

    private fun navigateData(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        threadTitle: String?,
        searchQuery: String?,
        dataFlags: Int,
    ) {
        var targetBoardName = boardName
        val chan = get(chanName)
        if (chan.name == null) {
            return
        }
        var forceBoardPage = false
        if (isSingleBoardMode(chan)) {
            targetBoardName = getSingleBoardName(chan)
            forceBoardPage = true
        }
        var pageFlags = 0
        pageFlags =
            set(
                pageFlags,
                FLAG_PAGE_CLOSE_OVERLAYS,
                get(dataFlags, FLAG_DATA_CLOSE_OVERLAYS),
            )
        pageFlags =
            set(
                pageFlags,
                FLAG_PAGE_ALLOW_RETURN,
                get(dataFlags, FLAG_DATA_ALLOW_RETURN),
            )
        if (targetBoardName != null || threadNumber != null || forceBoardPage) {
            pageFlags =
                set(
                    pageFlags,
                    FLAG_PAGE_FROM_CACHE,
                    get(dataFlags, FLAG_DATA_FROM_CACHE),
                )
            val content =
                if (searchQuery != null) {
                    Page.Content.SEARCH
                } else {
                    if (threadNumber == null) {
                        Page.Content.THREADS
                    } else {
                        Page.Content.POSTS
                    }
                }
            navigatePage(
                content,
                chan.name,
                targetBoardName,
                threadNumber,
                postNumber,
                threadTitle,
                searchQuery,
                pageFlags,
            )
        } else {
            var currentChanName: String? = null
            val currentFragment = this.currentFragment
            if (currentFragment is PageFragment) {
                currentChanName = currentFragment.page.chanName
            }
            if (getPagesStackSize(chan.name) == 0 || chan.name != currentChanName) {
                navigatePage(
                    Page.Content.BOARDS,
                    chan.name,
                    null,
                    null,
                    null,
                    null,
                    null,
                    pageFlags,
                )
            }
        }
    }

    private fun prepareAddPage(
        content: Page.Content?,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        searchQuery: String?,
        initRequest: InitRequest?,
    ): Pair<PageFragment, PageItem> {
        var targetSavedPageItem: SavedPageItem? = null
        val iterator: MutableIterator<SavedPageItem> =
            ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
        while (iterator.hasNext()) {
            val savedPageItem = iterator.next()
            if (getSavedPage(savedPageItem).`is`(content, chanName, boardName, threadNumber)) {
                targetSavedPageItem = savedPageItem
                iterator.remove()
                break
            }
        }

        val page = Page(content!!, chanName, boardName, threadNumber, searchQuery)
        val pair: Pair<PageFragment, PageItem>
        if (targetSavedPageItem != null) {
            val savedPage = getSavedPage(targetSavedPageItem)
            if (savedPage == page) {
                pair = targetSavedPageItem.create()
            } else {
                pair = targetSavedPageItem.createWithNewPage(page)
            }
        } else {
            val pageFragment = PageFragment(page, UUID.randomUUID().toString())
            val pageItem = PageItem()
            pair = Pair(pageFragment, pageItem)
        }
        if (initRequest != null) {
            pair.first.setInitRequest(initRequest)
        }

        val mergeChans = isMergeChans
        var depth = 0
        // Remove deep search, boards, etc pages if they are deep in stack
        for (i in stackPageItems.indices.reversed()) {
            val savedPageItem = stackPageItems[i]
            val savedPage = getSavedPage(savedPageItem)
            if (mergeChans || savedPage.chanName == chanName) {
                if (depth++ >= 2 && savedPage.canRemoveFromStackIfDeep()) {
                    stackPageItems.removeAt(i)
                    if (!savedPage.canDestroyIfNotInStack()) {
                        preservedPageItems.add(savedPageItem)
                    }
                }
            }
        }
        return pair
    }

    private fun navigatePage(
        content: Page.Content,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        threadTitle: String?,
        searchQuery: String?,
        pageFlags: Int,
    ) {
        val currentFragment = this.currentFragment
        val currentPage =
            if (currentFragment is PageFragment) {
                currentFragment.page
            } else {
                null
            }
        if (currentPage != null &&
            currentPage.`is`(
                content,
                chanName,
                boardName,
                threadNumber,
            ) &&
            searchQuery == null
        ) {
            if (currentPageItem == null && (content == Page.Content.BOARDS || content == Page.Content.THREADS)) {
                // Was removed from stack during clearStackAndCurrent
                val iterator: MutableIterator<SavedPageItem> =
                    ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
                while (iterator.hasNext()) {
                    if (getSavedPage(iterator.next()).`is`(content, chanName, boardName, null)) {
                        iterator.remove()
                        break
                    }
                }
                val pageItem = PageItem()
                pageItem.createdRealtime = SystemClock.elapsedRealtime()
                currentPageItem = pageItem
            }
            val currentPageItem = this.currentPageItem
            if (currentPageItem != null) {
                if (get(pageFlags, FLAG_PAGE_CLOSE_OVERLAYS)) {
                    closeOverlaysForNavigation()
                }
                currentPageItem.allowReturn =
                    currentPageItem.allowReturn and get(pageFlags, FLAG_PAGE_ALLOW_RETURN)
                (currentFragment as PageFragment).updatePageConfiguration(postNumber)
                invalidateHomeUpState()
                return
            }
        }
        val fromCache = get(pageFlags, FLAG_PAGE_FROM_CACHE)
        val pair: Pair<PageFragment, PageItem> =
            when (content) {
                Page.Content.THREADS -> {
                    prepareAddPage(
                        content,
                        chanName,
                        boardName,
                        null,
                        null,
                        InitRequest(!fromCache, null, null),
                    )
                }

                Page.Content.POSTS -> {
                    prepareAddPage(
                        content,
                        chanName,
                        boardName,
                        threadNumber,
                        null,
                        InitRequest(!fromCache, postNumber, threadTitle),
                    )
                }

                Page.Content.SEARCH -> {
                    prepareAddPage(
                        content,
                        chanName,
                        boardName,
                        null,
                        searchQuery,
                        InitRequest(!fromCache, null, null),
                    )
                }

                Page.Content.ARCHIVE, Page.Content.BOARDS, Page.Content.USER_BOARDS,
                Page.Content.HISTORY, Page.Content.ECHO,
                -> {
                    prepareAddPage(content, chanName, boardName, null, null, null)
                }
            }
        pair.second.allowReturn = get(pageFlags, FLAG_PAGE_ALLOW_RETURN)
        if (get(pageFlags, FLAG_PAGE_RESET_SCROLL)) {
            pair.first.requestResetScroll()
        }
        navigateFragment(pair.first, pair.second, get(pageFlags, FLAG_PAGE_CLOSE_OVERLAYS))
    }

    private fun navigateSavedPage(
        savedPageItem: SavedPageItem,
        closeOverlays: Boolean,
    ) {
        val pair = savedPageItem.create()
        navigateFragment(pair.first, pair.second, closeOverlays)
    }

    override fun pushFragment(fragment: ContentFragment) {
        val currentFragment = this.currentFragment
        if (currentFragment !is PageFragment) {
            val stackItem = StackItem(getSupportFragmentManager(), currentFragment!!, null)
            fragments.add(stackItem)
        }
        navigateFragment(fragment, null, true)
    }

    private fun navigateFragment(
        fragment: ContentFragment,
        pageItem: PageItem?,
        closeOverlays: Boolean,
    ) {
        if (closeOverlays) {
            closeOverlaysForNavigation()
        }
        val fragmentManager = getSupportFragmentManager()
        val currentFragment = this.currentFragment
        if (currentFragment is PageFragment) {
            // currentPageItem == null means page was deleted
            val currentPageItem = this.currentPageItem
            if (currentPageItem != null) {
                stackPageItems.add(
                    currentPageItem.toSaved(
                        fragmentManager,
                        currentFragment,
                    ),
                )
            }
            if (fragment is PageFragment) {
                clearNewThreadData()
            }
        } else if (fragment is PageFragment) {
            fragments.clear()
        }

        if (currentFragment != null) {
            currentFragment.onTerminate()
        }
        cancel()
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?
        if (inputMethodManager != null) {
            val view = getCurrentFocus()
            inputMethodManager.hideSoftInputFromWindow(
                (
                    if (view != null) {
                        view
                    } else {
                        getWindow().getDecorView()
                    }
                ).getWindowToken(),
                0,
            )
        }
        if (pageItem != null) {
            pageItem.createdRealtime = SystemClock.elapsedRealtime()
        }
        currentPageItem = pageItem
        fragmentManager
            .beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(R.id.content_fragment, fragment)
            .commit()
        updatePostFragmentConfiguration()
        updateBackHandling()

        if (currentFragment is PageFragment || fragment is PageFragment) {
            val retainIds = HashSet<String?>(1 + stackPageItems.size + preservedPageItems.size)
            if (fragment is PageFragment) {
                retainIds.add(fragment.retainId)
            }
            for (savedPageItem in ConcatIterable<SavedPageItem>(
                preservedPageItems,
                stackPageItems,
            )) {
                REFERENCE_FRAGMENT.setArguments(savedPageItem.stackItem!!.arguments)
                val retainId: String? = REFERENCE_FRAGMENT.retainId
                retainIds.add(retainId)
            }
            val iterator =
                instanceViewModel.extras.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!retainIds.contains(entry.key)) {
                    entry.value.clear()
                    iterator.remove()
                }
            }
        }
    }

    private fun closeOverlaysForNavigation() {
        navigateOrCloseGallery(null)
        if (!wideMode) {
            drawerLayout.closeDrawers()
        }
    }

    private fun updatePostFragmentConfiguration() {
        val currentFragment = this.currentFragment
        val chanName: String?
        if (currentFragment is PageFragment) {
            chanName = currentFragment.page.chanName
        } else if (!stackPageItems.isEmpty()) {
            chanName = getSavedPage(stackPageItems[stackPageItems.size - 1]).chanName
        } else {
            val chan = ChanManager.getInstance().defaultChan
            chanName = if (chan != null) chan.name else null
        }
        if (currentFragment is PageFragment) {
            expandedScreen.removeLocker(LOCKER_NON_PAGE)
        } else {
            expandedScreen.addLocker(LOCKER_NON_PAGE)
        }
        watcherServiceClient.updateConfiguration(chanName)
        drawerForm.updateConfiguration(chanName)
        invalidateHomeUpState()
    }

    override fun onDialogStackOpen() {
        if (!wideMode) {
            drawerLayout.closeDrawers()
        }
    }

    override fun getDownloadBinder(): DownloadService.Binder? = downloadBinderField

    override val watcherClient: WatcherService.Client
        get() = watcherServiceClient

    override fun getRetainableExtra(retainId: String?): Retainable? = instanceViewModel.extras[retainId]

    override fun storeRetainableExtra(
        retainId: String?,
        extra: Retainable?,
    ) {
        if (extra != null) {
            instanceViewModel.extras[retainId] = extra
        } else {
            instanceViewModel.extras.remove(retainId)
        }
    }

    override fun invalidateHomeUpState() {
        val currentFragment = this.currentFragment
        if (currentFragment != null && currentFragment.isSearchMode) {
            drawerToggle.setDrawerIndicatorMode(DrawerToggle.Mode.UP)
        } else {
            val displayUp: Boolean
            if (currentFragment is PageFragment) {
                val page = currentFragment.page
                displayUp =
                    when (page.content) {
                        Page.Content.THREADS -> {
                            getPagesStackSize(page.chanName) > 1
                        }

                        Page.Content.POSTS, Page.Content.SEARCH, Page.Content.ARCHIVE -> {
                            true
                        }

                        Page.Content.BOARDS, Page.Content.USER_BOARDS, Page.Content.HISTORY,
                        Page.Content.ECHO,
                        -> {
                            page.boardName != null || getPagesStackSize(page.chanName) > 1
                        }
                    }
            } else {
                displayUp = !stackPageItems.isEmpty() || !fragments.isEmpty()
            }
            drawerToggle.setDrawerIndicatorMode(
                if (displayUp) {
                    DrawerToggle.Mode.UP
                } else if (wideMode) {
                    DrawerToggle.Mode.DISABLED
                } else {
                    DrawerToggle.Mode.DRAWER
                },
            )
        }
    }

    private fun updateWideConfiguration(forced: Boolean) {
        val configuration = getResources().getConfiguration()
        val newWideMode = isDrawerLockable(configuration) && isDrawerLocked
        if (wideMode != newWideMode || forced) {
            wideMode = newWideMode
            if (!forced) {
                expandedScreen.setDrawerOverToolbarEnabled(!wideMode)
            }
            drawerLayout.setDrawerLockMode(
                if (wideMode) {
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                } else {
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                },
            )
            drawerWide.setVisibility(if (wideMode) View.VISIBLE else View.GONE)
            ViewUtils.removeFromParent(drawerParent)
            (if (wideMode) drawerWide else drawerCommon).addView(drawerParent)
            invalidateHomeUpState()
            updateBackHandling()
        }
        val density = obtainDensity(this)
        val actionBarSize =
            getResources().getDimensionPixelSize(
                getResourceId(
                    this,
                    android.R.attr.actionBarSize,
                    0,
                ),
            )
        val drawerWidth =
            min(
                (configuration.screenWidthDp * density + 0.5f).toInt() - actionBarSize,
                (320 * density + 0.5f).toInt(),
            )
        drawerWide.getLayoutParams().width = drawerWidth
        drawerCommon.getLayoutParams().width = drawerWide.getLayoutParams().width
    }

    override fun requestStorage(): Boolean {
        if (storageRequestState == StorageRequestState.NONE) {
            storageRequestState = StorageRequestState.INSTRUCTIONS
            showStorageInstructionsDialog()
            return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()

        handleChansChangedDelayed()
        watcherServiceClient.notifyForeground()
    }

    override fun onResume() {
        super.onResume()

        drawerForm.updateRestartViewVisibility()
        drawerForm.updateItems(true, true)
        updateWideConfiguration(false)
        handleChansChangedDelayed()
        register(this)
        ForegroundManager.Companion.getInstance().register(this)

        val navigateIntentOnResume = this.navigateIntentOnResume
        this.navigateIntentOnResume = null
        if (navigateIntentOnResume != null) {
            navigateIntentUnchecked(navigateIntentOnResume)
        }
        // Expanding a PiP window returns this task to the front on the system's initiative,
        // possibly before the window's activity managed to send C.ACTION_VIDEO_PIP: consume a
        // pending hand-back directly as well (no-op when there is none, or if the intent won).
        reopenInApp(this)

        downloadBinderField?.notifyReadyToHandleRequests()
    }

    protected override fun onStop() {
        super.onStop()

        // Intent is valid only for onNewIntent -> onResume behavior
        navigateIntentOnResume = null
        // Persist the open pages (current board/thread plus the back stack) so a cold start
        // after a swipe-away, low-memory kill or reboot can restore them. The file is read
        // and deleted on the next launch that has no saved instance state; a configuration
        // change keeps using the instance-state bundle instead, so skip writing for one.
        if (!isChangingConfigurations) {
            writeSavedPagesFile()
        }
    }

    override fun onFinish() {
        super.onFinish()

        // Decorator actions outlive the post that started them, so drop the ones still running
        // instead of letting them come back to a dead activity.
        uiManager.decorator().cancelAll()
        postingBinder?.unregister(postingGlobalCallback)
        postingBinder = null
        downloadBinderField?.unregister(downloadCallback)
        downloadBinderField = null
        unbindService(postingConnection)
        unbindService(downloadConnection)
        watcherServiceClient.callback = null
        FavoritesStorage.getInstance().getObservable().unregister(this)
        Preferences.prefs.unregister(preferencesListener)
        ChanManager.getInstance().observable.unregister(chanManagerCallback)
        for (chan in ChanManager.getInstance().availableChans) {
            chan.configuration.commit()
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(C.NOTIFICATION_ID_UPDATES)
        FavoritesStorage.getInstance().await(true)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onSearchRequested(): Boolean {
        val currentFragment = this.currentFragment
        return currentFragment!!.onSearchRequested()
    }

    override fun removeFragment() {
        handleBackPress(true, Runnable { navigateInitial(true) })
    }

    /** What a back press would do right now, in the priority order [handleBackPress] applies. */
    private enum class BackKind {
        /** Nothing in the app claims the gesture, so the system plays back-to-home. */
        NONE,

        /** Closes the open navigation drawer. */
        DRAWER,

        /** Handled inside the current fragment, e.g. closing an open search field. */
        INTERNAL,

        /** Pops a page or a fragment off the stack, replacing the content. */
        NAVIGATE,
    }

    private val backKind: BackKind
        get() {
            if (!wideMode && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                return BackKind.DRAWER
            }
            val currentFragment = this.currentFragment
            if (currentFragment == null) {
                return BackKind.NONE
            }
            if (currentFragment.isBackHandled) {
                return BackKind.INTERNAL
            }
            if (currentFragment is PageFragment) {
                return if (hasTargetPreviousPage()) BackKind.NAVIGATE else BackKind.NONE
            }
            return if (!fragments.isEmpty() || !stackPageItems.isEmpty()) {
                BackKind.NAVIGATE
            } else {
                BackKind.NONE
            }
        }

    // Predictive back: the callback is enabled only while something in the app claims the back
    // gesture (open drawer, fragment-internal state, page/fragment back stack), so the system
    // back-to-home animation plays whenever a back gesture would leave the app.
    //
    // Only NAVIGATE is animated. A drawer close plays DrawerLayout's own animation on commit, and
    // an INTERNAL back only closes a search field or the like: shrinking the whole content away for
    // either would promise a page change that isn't coming.
    private val backPressedCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (backKind == BackKind.NAVIGATE) {
                    backTransform.start(backEvent)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                backTransform.progress(backEvent)
            }

            override fun handleOnBackCancelled() {
                backTransform.settle()
            }

            override fun handleOnBackPressed() {
                // The enabled state was stale if nothing handles the press: swallow this
                // press and let the system take the next one.
                handleBackPress(false, Runnable { isEnabled = false })
                // The container keeps the gesture's transform across the fragment swap and springs
                // back from under it, so the destination grows into place instead of the shrunken
                // content snapping back to full size.
                backTransform.settle()
            }
        }

    override fun updateBackHandling() {
        backPressedCallback.isEnabled = backKind != BackKind.NONE
    }

    // Side-effect-free version of prepareTargetPreviousPage(true).
    private fun hasTargetPreviousPage(): Boolean {
        val currentPageItem = this.currentPageItem
        if (currentPageItem != null && currentPageItem.allowReturn && !stackPageItems.isEmpty()) {
            return true
        }
        val currentFragment = this.currentFragment
        if (currentFragment !is PageFragment) {
            return false
        }
        val chanName = currentFragment.page.chanName
        val mergeChans = isMergeChans
        for (i in stackPageItems.indices.reversed()) {
            if (mergeChans || getSavedPage(stackPageItems[i]).chanName == chanName) {
                return true
            }
        }
        return false
    }

    private fun handleBackPress(
        homeHandled: Boolean,
        close: Runnable,
    ) {
        if (!wideMode && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawers()
        } else {
            val currentFragment = this.currentFragment
            if (!homeHandled && currentFragment!!.onBackPressed()) {
                updateBackHandling()
                return
            }
            var handled = false
            if (currentFragment is PageFragment) {
                val savedPageItem = prepareTargetPreviousPage(true)
                if (savedPageItem != null) {
                    val page = currentFragment.page
                    if (!(page.isThreadsOrPosts && isCloseOnBack)) {
                        preservedPageItems.add(
                            currentPageItem!!.toSaved(
                                getSupportFragmentManager(),
                                currentFragment,
                            ),
                        )
                    }
                    currentPageItem = null
                    navigateSavedPage(savedPageItem, true)
                    handled = true
                }
            } else if (!fragments.isEmpty()) {
                val fragment =
                    fragments.removeAt(fragments.size - 1).create(null) as ContentFragment
                navigateFragment(fragment, null, true)
                handled = true
            } else if (!stackPageItems.isEmpty()) {
                navigateSavedPage(stackPageItems.removeAt(stackPageItems.size - 1), true)
                handled = true
            }
            if (!handled) {
                close.run()
            }
        }
        updateBackHandling()
    }

    private var currentActionMode: WeakReference<ActionMode?>? = null

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        expandedScreen.setActionModeState(true)
        if (currentActionMode == null) {
            setNavigationAreaLocked(LOCKER_ACTION_MODE, true)
        }
        currentActionMode = WeakReference<ActionMode?>(mode)
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        if (currentActionMode != null) {
            if (currentActionMode?.get() === mode) {
                currentActionMode = null
                setNavigationAreaLocked(LOCKER_ACTION_MODE, false)
            }
        }
        super.onActionModeFinished(mode)
        expandedScreen.setActionModeState(false)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val result = super.onPrepareOptionsMenu(menu)
        val appearanceOptionsItem = menu.findItem(R.id.menu_appearance)
        if (appearanceOptionsItem != null) {
            val appearanceOptionsMenu = appearanceOptionsItem.getSubMenu()!!
            if (appearanceOptionsMenu.size() == 0) {
                appearanceOptionsMenu.add(
                    0,
                    R.id.menu_change_theme,
                    0,
                    R.string.change_theme,
                )
                appearanceOptionsMenu
                    .add(
                        0,
                        R.id.menu_expanded_screen,
                        0,
                        R.string.expanded_screen,
                    ).setCheckable(true)
                appearanceOptionsMenu
                    .add(
                        0,
                        R.id.menu_spoilers,
                        0,
                        R.string.spoilers,
                    ).setCheckable(true)
                appearanceOptionsMenu
                    .add(
                        0,
                        R.id.menu_my_posts,
                        0,
                        R.string.my_posts,
                    ).setCheckable(true)
                appearanceOptionsMenu
                    .add(
                        0,
                        R.id.menu_drawer,
                        0,
                        R.string.lock_navigation,
                    ).setCheckable(true)
                appearanceOptionsMenu
                    .add(
                        0,
                        R.id.menu_sfw_mode,
                        0,
                        R.string.sfw_mode,
                    ).setCheckable(true)
            }
            appearanceOptionsMenu
                .findItem(R.id.menu_expanded_screen)
                .setChecked(isExpandedScreen)
            appearanceOptionsMenu
                .findItem(R.id.menu_spoilers)
                .setChecked(isShowSpoilers)
            appearanceOptionsMenu
                .findItem(R.id.menu_my_posts)
                .setChecked(isShowMyPosts)
            appearanceOptionsMenu
                .findItem(R.id.menu_drawer)
                .setVisible(isDrawerLockable(getResources().getConfiguration()))
                .setChecked(isDrawerLocked)
            appearanceOptionsMenu
                .findItem(R.id.menu_sfw_mode)
                .setChecked(isSfwMode)
        }
        return result
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        val currentFragment = this.currentFragment
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == android.R.id.home) {
            if (currentFragment!!.onHomePressed()) {
                return true
            }
            drawerLayout.closeDrawers()
            if (currentFragment is PageFragment) {
                val page = currentFragment.page
                var newChanName = page.chanName
                var newBoardName = page.boardName
                if (page.content == Page.Content.THREADS) {
                    // Up button must navigate to main page in threads list
                    newBoardName = getDefaultBoardName(get(page.chanName))
                    if (isMergeChans && equals(page.boardName, newBoardName)) {
                        val chan = ChanManager.getInstance().defaultChan
                        newChanName = chan!!.name
                        newBoardName = getDefaultBoardName(chan)
                    }
                }
                clearStackAndCurrent()
                var fromCache = false
                for (savedPageItem in ConcatIterable<SavedPageItem>(
                    preservedPageItems,
                    stackPageItems,
                )) {
                    if (getSavedPage(savedPageItem).`is`(
                            Page.Content.THREADS,
                            newChanName,
                            newBoardName,
                            null,
                        )
                    ) {
                        fromCache = true
                        break
                    }
                }
                navigateData(
                    newChanName,
                    newBoardName,
                    null,
                    null,
                    null,
                    null,
                    FLAG_DATA_CLOSE_OVERLAYS or (if (fromCache) FLAG_DATA_FROM_CACHE else 0),
                )
            } else {
                fragments.clear()
                removeFragment()
            }
            return true
        } else if (switchItemId0 == R.id.menu_change_theme ||
            switchItemId0 == R.id.menu_expanded_screen ||
            switchItemId0 == R.id.menu_spoilers ||
            switchItemId0 == R.id.menu_my_posts ||
            switchItemId0 == R.id.menu_drawer ||
            switchItemId0 == R.id.menu_sfw_mode
        ) {
            try {
                if (switchItemId0 == R.id.menu_change_theme) {
                    ThemeDialog().show(
                        getSupportFragmentManager(),
                        ThemeDialog::class.java.getName(),
                    )
                    return true
                } else if (switchItemId0 == R.id.menu_expanded_screen) {
                    isExpandedScreen = !item.isChecked()
                    recreate()
                    return true
                } else if (switchItemId0 == R.id.menu_spoilers) {
                    isShowSpoilers = !item.isChecked()
                    return true
                } else if (switchItemId0 == R.id.menu_my_posts) {
                    isShowMyPosts = !item.isChecked()
                    return true
                } else if (switchItemId0 == R.id.menu_drawer) {
                    isDrawerLocked = !item.isChecked()
                    updateWideConfiguration(false)
                    return true
                } else if (switchItemId0 == R.id.menu_sfw_mode) {
                    isSfwMode = !item.isChecked()
                    return true
                }
            } finally {
                if (currentFragment is PageFragment) {
                    currentFragment.onAppearanceOptionChanged(item.getItemId())
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // androidx.core's ComponentActivity marks dispatchKeyEvent @RestrictTo(LIBRARY_GROUP_PREFIX)
    // even though overriding it in an app is the only way to see key events before the window does.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = this.currentFragment
        return fragment!!.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onThemeSelected(theme: ThemeEngine.Theme?) {
        if (theme != null) {
            ThemeEngine.setCurrentTheme(this, theme.name)
            recreate()
        } else {
            fragments.clear()
            navigateFragment(ThemesFragment(), null, true)
        }
    }

    private val preferencesListener =
        SharedPreferences.Listener { key: String? -> drawerForm.updatePreferences() }

    override fun onSelectChan(chanName: String?) {
        val currentFragment = this.currentFragment
        val page = if (currentFragment is PageFragment) currentFragment.page else null
        if (page == null || page.chanName != chanName) {
            val chan = get(chanName)
            if (!isMergeChans) {
                // Find chan page and open it. Open root page if nothing was found.
                var lastSavedPageItem: SavedPageItem? = null
                for (i in stackPageItems.indices.reversed()) {
                    val savedPageItem = stackPageItems[i]
                    if (getSavedPage(savedPageItem).chanName == chanName) {
                        stackPageItems.remove(savedPageItem)
                        lastSavedPageItem = savedPageItem
                        break
                    }
                }
                if (lastSavedPageItem != null) {
                    if (page != null) {
                        stackPageItems.add(
                            currentPageItem!!.toSaved(
                                getSupportFragmentManager(),
                                currentFragment as PageFragment,
                            ),
                        )
                        currentPageItem = null
                    }
                    navigateSavedPage(lastSavedPageItem, true)
                } else {
                    navigateBoardsOrThreads(chanName, getDefaultBoardName(chan), false, false)
                }
            } else {
                // Open root page. If page is already opened, load it from cache.
                var fromCache = false
                val boardName = getDefaultBoardName(chan)
                for (savedPageItem in ConcatIterable<SavedPageItem>(
                    preservedPageItems,
                    stackPageItems,
                )) {
                    if (getSavedPage(savedPageItem).`is`(
                            Page.Content.THREADS,
                            chanName,
                            boardName,
                            null,
                        )
                    ) {
                        fromCache = true
                        break
                    }
                }
                navigateBoardsOrThreads(chanName, boardName, fromCache, false)
            }
            drawerForm.updateConfiguration(chanName)
        } else {
            closeOverlaysForNavigation()
        }
    }

    override fun onSelectBoard(
        chanName: String?,
        boardName: String?,
        fromCache: Boolean,
    ) {
        var targetBoardName = boardName
        val currentFragment = this.currentFragment
        val page = if (currentFragment is PageFragment) currentFragment.page else null
        val chan = get(chanName)
        if (isSingleBoardMode(chan)) {
            targetBoardName = getSingleBoardName(chan)
        }
        if (page == null || !page.`is`(Page.Content.THREADS, chanName, targetBoardName, null)) {
            navigateBoardsOrThreads(chanName, targetBoardName, fromCache, false)
        } else {
            closeOverlaysForNavigation()
        }
    }

    override fun onSelectThread(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        threadTitle: String?,
        fromCache: Boolean,
    ): Boolean {
        var targetBoardName = boardName
        val currentFragment = this.currentFragment
        val page = if (currentFragment is PageFragment) currentFragment.page else null
        val chan = get(chanName)
        if (isSingleBoardMode(chan)) {
            targetBoardName = getSingleBoardName(chan)
        } else if (targetBoardName == null) {
            if (page == null) {
                return false
            } else {
                when (page.content) {
                    Page.Content.BOARDS, Page.Content.USER_BOARDS, Page.Content.HISTORY,
                    Page.Content.ECHO,
                    -> {
                        return false
                    }

                    else -> {}
                }
                targetBoardName = page.boardName
            }
        }
        if (page == null || !page.`is`(Page.Content.POSTS, chanName, targetBoardName, threadNumber)) {
            navigatePosts(
                chanName,
                targetBoardName,
                threadNumber,
                postNumber,
                threadTitle,
                fromCache,
                false,
            )
        } else {
            closeOverlaysForNavigation()
        }
        return true
    }

    override fun onClosePage(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ) {
        val currentFragment = this.currentFragment
        val page = if (currentFragment is PageFragment) currentFragment.page else null
        if (page != null && page.isThreadsOrPosts(chanName, boardName, threadNumber)) {
            val savedPageItem = prepareTargetPreviousPage(false)
            currentPageItem = null
            if (savedPageItem != null) {
                navigateSavedPage(savedPageItem, false)
            } else {
                val chan = get(chanName)
                if (isSingleBoardMode(chan)) {
                    navigatePage(
                        Page.Content.THREADS,
                        chanName,
                        getSingleBoardName(chan),
                        null,
                        null,
                        null,
                        null,
                        FLAG_PAGE_FROM_CACHE,
                    )
                } else {
                    navigatePage(
                        Page.Content.BOARDS,
                        chanName,
                        null,
                        null,
                        null,
                        null,
                        null,
                        FLAG_PAGE_FROM_CACHE,
                    )
                }
            }
        } else {
            var iterator = stackPageItems.iterator()
            while (iterator.hasNext()) {
                if (getSavedPage(iterator.next()).isThreadsOrPosts(
                        chanName,
                        boardName,
                        threadNumber,
                    )
                ) {
                    iterator.remove()
                    break
                }
            }
            iterator = preservedPageItems.iterator()
            while (iterator.hasNext()) {
                if (getSavedPage(iterator.next()).isThreadsOrPosts(
                        chanName,
                        boardName,
                        threadNumber,
                    )
                ) {
                    iterator.remove()
                    break
                }
            }
            drawerForm.updateItems(true, false)
            invalidateHomeUpState()
        }
    }

    private fun isCloseAllTarget(
        page: Page,
        chanName: String?,
        boardName: String?,
        singleBoardMode: Boolean,
        singleBoardName: String?,
    ): Boolean {
        if (!singleBoardMode && boardName == null) {
            return page.`is`(Page.Content.BOARDS, chanName, null, null)
        } else if (singleBoardMode) {
            return page.`is`(Page.Content.THREADS, chanName, singleBoardName, null)
        } else {
            return page.`is`(Page.Content.THREADS, chanName, boardName, null)
        }
    }

    override fun onCloseAllPages() {
        val currentFragment = this.currentFragment
        val page = if (currentFragment is PageFragment) currentFragment.page else null
        var chanName = if (page != null) page.chanName else null
        if (chanName == null && !stackPageItems.isEmpty()) {
            chanName = getSavedPage(stackPageItems[stackPageItems.size - 1]).chanName
        }
        if (chanName != null) {
            val chan = get(chanName)
            val boardName = getDefaultBoardName(chan)
            val singleBoardMode: Boolean = isSingleBoardMode(chan)
            val singleBoardName: String? = getSingleBoardName(chan)
            var cached =
                page != null &&
                    isCloseAllTarget(
                        page,
                        chanName,
                        boardName,
                        singleBoardMode,
                        singleBoardName,
                    )
            val mergeChans = isMergeChans
            val addPreserved = ArrayList<SavedPageItem>()
            val iterator: MutableIterator<SavedPageItem> =
                ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
            while (iterator.hasNext()) {
                val savedPageItem = iterator.next()
                val savedPage = getSavedPage(savedPageItem)
                if (mergeChans || savedPage.chanName == chanName) {
                    cached = cached or
                        isCloseAllTarget(
                            savedPage,
                            chanName,
                            boardName,
                            singleBoardMode,
                            singleBoardName,
                        )
                    iterator.remove()
                    if (!(savedPage.isThreadsOrPosts || savedPage.canDestroyIfNotInStack())) {
                        addPreserved.add(savedPageItem)
                    }
                }
            }
            preservedPageItems.addAll(addPreserved)
            if (page != null) {
                if (!(page.isThreadsOrPosts || page.canDestroyIfNotInStack())) {
                    preservedPageItems.add(
                        currentPageItem!!.toSaved(
                            getSupportFragmentManager(),
                            currentFragment as PageFragment,
                        ),
                    )
                }
                currentPageItem = null
                navigateData(
                    chanName,
                    boardName,
                    null,
                    null,
                    null,
                    null,
                    if (cached) FLAG_DATA_FROM_CACHE else 0,
                )
            }
        } else {
            val addPreserved = ArrayList<SavedPageItem>()
            val iterator: MutableIterator<SavedPageItem> =
                ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
            while (iterator.hasNext()) {
                val savedPageItem = iterator.next()
                val savedPage = getSavedPage(savedPageItem)
                iterator.remove()
                if (!(savedPage.isThreadsOrPosts || savedPage.canDestroyIfNotInStack())) {
                    addPreserved.add(savedPageItem)
                }
            }
            preservedPageItems.addAll(addPreserved)
        }
        drawerForm.updateItems(true, false)
        invalidateHomeUpState()
    }

    override fun onEnterNumber(number: Int): Int {
        var result = 0
        val currentFragment = this.currentFragment
        if (currentFragment is PageFragment) {
            result = currentFragment.onDrawerNumberEntered(number)
        }
        if (!wideMode && get(result, DrawerForm.Companion.RESULT_SUCCESS)) {
            drawerLayout.closeDrawers()
        }
        return result
    }

    override fun onSelectDrawerMenuItem(item: Int) {
        var content: Page.Content? = null
        when (item) {
            DrawerForm.Companion.MENU_ITEM_BOARDS -> {
                content = Page.Content.BOARDS
            }

            DrawerForm.Companion.MENU_ITEM_USER_BOARDS -> {
                content = Page.Content.USER_BOARDS
            }

            DrawerForm.Companion.MENU_ITEM_HISTORY -> {
                content = Page.Content.HISTORY
            }

            DrawerForm.Companion.MENU_ITEM_ECHO -> {
                content = Page.Content.ECHO
            }

            DrawerForm.Companion.MENU_ITEM_PREFERENCES -> {
                if (this.currentFragment !is CategoriesFragment) {
                    fragments.clear()
                    navigateFragment(CategoriesFragment(), null, true)
                }
            }
        }
        var success = false
        if (content != null) {
            val currentFragment = this.currentFragment
            var page = if (currentFragment is PageFragment) currentFragment.page else null
            if (page == null || page.content != content) {
                if (page == null && !stackPageItems.isEmpty()) {
                    page = getSavedPage(stackPageItems[stackPageItems.size - 1])
                }
                var chanName = if (page != null) page.chanName else null
                var boardName = if (page != null) page.boardName else null
                if (chanName == null) {
                    val chan = ChanManager.getInstance().defaultChan
                    chanName = chan!!.name
                    boardName = getDefaultBoardName(chan)
                }
                if (chanName != null) {
                    navigatePage(
                        content,
                        chanName,
                        boardName,
                        null,
                        null,
                        null,
                        null,
                        FLAG_PAGE_CLOSE_OVERLAYS or FLAG_PAGE_RESET_SCROLL,
                    )
                    success = true
                }
            }
        }
        if (!success) {
            closeOverlaysForNavigation()
        }
    }

    override fun onDraggingStateChanged(dragging: Boolean) {
        if (!wideMode) {
            drawerLayout.setDrawerLockMode(
                if (dragging) {
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_OPEN
                } else {
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                },
            )
        }
    }

    override fun obtainDrawerPages(): Collection<DrawerForm.Page> {
        val drawerPages =
            ArrayList<DrawerForm.Page>(
                1 +
                    stackPageItems.size + preservedPageItems.size,
            )
        for (savedPageItem in ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems)) {
            val page = getSavedPage(savedPageItem)
            if (page.isThreadsOrPosts) {
                drawerPages.add(
                    DrawerForm.Page(
                        page.chanName!!,
                        page.boardName,
                        page.threadNumber,
                        savedPageItem.threadTitle,
                        savedPageItem.createdRealtime,
                    ),
                )
            }
        }
        val currentFragment = this.currentFragment
        if (currentFragment is PageFragment) {
            val page = currentFragment.page
            if (page.isThreadsOrPosts) {
                drawerPages.add(
                    DrawerForm.Page(
                        page.chanName!!,
                        page.boardName,
                        page.threadNumber,
                        currentPageItem!!.threadTitle,
                        currentPageItem!!.createdRealtime,
                    ),
                )
            }
        }
        return drawerPages
    }

    private val changedChanNames = HashSet<String?>()
    private val removedChanNames = HashSet<String?>()

    private fun handleChansChangedDelayed() {
        if (removedChanNames.isEmpty()) {
            if (!changedChanNames.isEmpty()) {
                val currentFragment = this.currentFragment
                if (currentFragment is FragmentHandler.Callback) {
                    (currentFragment as FragmentHandler.Callback)
                        .onChansChanged(
                            Collections.unmodifiableSet<String>(changedChanNames),
                            mutableSetOf<String>(),
                        )
                }
            }
        } else {
            val iterator: MutableIterator<SavedPageItem> =
                ConcatIterable<SavedPageItem>(preservedPageItems, stackPageItems).iterator()
            while (iterator.hasNext()) {
                if (removedChanNames.contains(getSavedPage(iterator.next()).chanName)) {
                    iterator.remove()
                }
            }
            val currentFragment = this.currentFragment
            if (currentFragment is PageFragment &&
                removedChanNames.contains(currentFragment.page.chanName)
            ) {
                if (!stackPageItems.isEmpty()) {
                    currentPageItem = null
                    navigateSavedPage(stackPageItems.removeAt(stackPageItems.size - 1), true)
                } else {
                    navigateInitial(true)
                }
            } else if (currentFragment is FragmentHandler.Callback) {
                (currentFragment as FragmentHandler.Callback)
                    .onChansChanged(
                        Collections.unmodifiableSet<String>(changedChanNames),
                        Collections.unmodifiableSet<String>(removedChanNames),
                    )
            }
            val galleryTag = GalleryOverlay::class.java.getName()
            val currentGalleryOverlay =
                getSupportFragmentManager()
                    .findFragmentByTag(galleryTag) as GalleryOverlay?
            if (currentGalleryOverlay != null && removedChanNames.contains(currentGalleryOverlay.chanName)) {
                currentGalleryOverlay.dismiss()
            }
        }
        if (!changedChanNames.isEmpty() || !removedChanNames.isEmpty()) {
            changedChanNames.clear()
            removedChanNames.clear()
            drawerForm.updateChans()
            updatePostFragmentConfiguration()
        }
    }

    private val chanManagerCallback: ChanManager.Callback =
        object : ChanManager.Callback {
            override fun onRestartRequiredChanged() {
                drawerForm.updateRestartViewVisibility()
            }

            override fun onUntrustedExtensionInstalled() {
                handleUntrustedExtensions(this@MainActivity, extensionsTrustLoopState)
            }

            override fun onChanInstalled(chan: Chan) {
                changedChanNames.add(chan.name)
                removedChanNames.remove(chan.name)
                if (!getSupportFragmentManager().isStateSaved()) {
                    handleChansChangedDelayed()
                }
            }

            override fun onChanUninstalled(chan: Chan) {
                changedChanNames.remove(chan.name)
                removedChanNames.add(chan.name)
                if (!getSupportFragmentManager().isStateSaved()) {
                    handleChansChangedDelayed()
                }
            }
        }

    /**
     * Serializes the current page stack (the visible board/thread, the back stack and the
     * preserved pages) to the [savedPagesFile] so the next cold start can restore it. The
     * current fragment anchors the restore, so when there is none any stale file is removed
     * and this returns false. Returns true when the file was written and now exists.
     */
    private fun writeSavedPagesFile(): Boolean {
        val file = this.savedPagesFile ?: return false
        val currentFragment = this.currentFragment
        if (currentFragment == null) {
            file.delete()
            return false
        }
        val outState = Bundle()
        writePagesState(outState)
        outState.putParcelable(
            EXTRA_CURRENT_FRAGMENT,
            StackItem(getSupportFragmentManager(), currentFragment, null),
        )
        val parcel = Parcel.obtain()
        try {
            FileOutputStream(file).use { output ->
                outState.writeToParcel(parcel, 0)
                val data = parcel.marshall()
                copyStream(ByteArrayInputStream(data), output)
            }
        } catch (e: IOException) {
            file.delete()
        } finally {
            parcel.recycle()
        }
        return file.exists()
    }

    override fun restartApplication() {
        if (writeSavedPagesFile()) {
            restartApplication(this)
        }
    }

    private val postingGlobalCallback: GlobalCallback =
        GlobalCallback {
            val currentFragment = this.currentFragment
            if (currentFragment is PageFragment) {
                currentFragment.handleNewPostDataListNow()
            }
        }

    private var postingBinder: PostingService.Binder? = null
    private val postingConnection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                componentName: ComponentName?,
                binder: IBinder?,
            ) {
                val postingBinder = binder as PostingService.Binder
                this@MainActivity.postingBinder = postingBinder
                postingBinder.register(postingGlobalCallback)
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                postingBinder?.unregister(postingGlobalCallback)
                postingBinder = null
            }
        }

    private fun updateHandleDownloadRequests() {
        val binder = downloadBinderField
        downloadDialog.handleRequest(if (binder != null) binder.getPrimaryRequest() else null)
    }

    private val downloadCallback: DownloadService.Callback =
        object : DownloadService.Callback {
            override fun requestHandleRequest() {
                updateHandleDownloadRequests()
            }

            override fun requestPermission() {
                if (storageRequestState == StorageRequestState.NONE) {
                    if (getDownloadUriTree(this@MainActivity) != null) {
                        downloadBinderField!!.onPermissionResult(DownloadService.PermissionResult.SUCCESS)
                    } else {
                        storageRequestState = StorageRequestState.INSTRUCTIONS
                        showStorageInstructionsDialog()
                    }
                }
            }
        }

    private var downloadBinderField: DownloadService.Binder? = null
    private var lastStorageRequestResult: Boolean? = null
    private val downloadConnection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                componentName: ComponentName?,
                binder: IBinder?,
            ) {
                val downloadBinder = binder as DownloadService.Binder
                downloadBinderField = downloadBinder
                downloadBinder.register(downloadCallback)
                val cancel = lastStorageRequestResult
                if (cancel != null) {
                    lastStorageRequestResult = null
                    notifyDownloadServiceStorageRequestResult(cancel)
                }
                downloadBinder.notifyReadyToHandleRequests()
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                downloadBinderField?.unregister(downloadCallback)
                downloadBinderField = null
                updateHandleDownloadRequests()
            }
        }

    private fun showStorageInstructionsDialog() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.download_directory)
            .setMessage(R.string.saf_instructions__sentence)
            .setPositiveButton(
                R.string.proceed,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    storageRequestState = StorageRequestState.PICKER
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .putExtra("android.provider.extra.SHOW_ADVANCED", true)
                            .putExtra("android.content.extra.SHOW_ADVANCED", true)
                            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    intent.putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract
                            .buildRootUri("com.android.externalstorage.documents", "primary"),
                    )
                    try {
                        openUriTreeLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        show(R.string.unknown_address)
                        storageRequestState = StorageRequestState.NONE
                        handleStorageRequestResult(true)
                    }
                },
            ).setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    storageRequestState = StorageRequestState.NONE
                    handleStorageRequestResult(true)
                },
            ).setOnCancelListener(
                DialogInterface.OnCancelListener { d: DialogInterface? ->
                    storageRequestState = StorageRequestState.NONE
                    handleStorageRequestResult(true)
                },
            ).show()
    }

    private fun handleStorageRequestResult(cancel: Boolean) {
        notifyDownloadServiceStorageRequestResult(cancel)
        val currentFragment = this.currentFragment
        if (currentFragment is FragmentHandler.Callback) {
            (currentFragment as FragmentHandler.Callback).onStorageRequestResult()
        }
    }

    private fun notifyDownloadServiceStorageRequestResult(cancel: Boolean) {
        if (downloadBinderField != null) {
            val uri = getDownloadUriTree(this)
            downloadBinderField?.onPermissionResult(
                if (uri != null) {
                    DownloadService.PermissionResult.SUCCESS
                } else {
                    if (cancel) {
                        DownloadService.PermissionResult.CANCEL
                    } else {
                        DownloadService.PermissionResult.FAIL
                    }
                },
            )
        } else {
            lastStorageRequestResult = cancel
        }
    }

    class UpdateViewModel : TaskViewModel.Proxy<ReadUpdateTask, ReadUpdateTask.Callback?>()

    private fun startUpdateTask(allowStart: Boolean) {
        // Check for updates once per 12 hours
        val viewModel = ViewModelProvider(this).get<UpdateViewModel>(UpdateViewModel::class.java)
        if (allowStart &&
            !viewModel.hasTaskOrValue() &&
            isCheckUpdatesOnStart &&
            System.currentTimeMillis() - lastUpdateCheck >= 12 * 60 * 60 * 1000
        ) {
            val task = ReadUpdateTask(this, viewModel.callback!!)
            task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
            viewModel.attach(task)
        }
        viewModel.observe(
            this,
            ReadUpdateTask.Callback { updateDataMap: UpdateDataMap?, errorItem: ErrorItem? ->
                lastUpdateCheck = System.currentTimeMillis()
                if (updateDataMap != null) {
                    val count = checkNewVersions(updateDataMap)
                    if (count > 0) {
                        handleUpdateData(updateDataMap, count)
                    }
                }
            },
        )
    }

    private fun handleUpdateData(
        updateDataMap: UpdateDataMap?,
        count: Int,
    ) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            createHeadsUpNotificationChannel(
                C.NOTIFICATION_CHANNEL_UPDATES,
                getString(R.string.updates),
            ),
        )

        val builder = NotificationCompat.Builder(this, C.NOTIFICATION_CHANNEL_UPDATES)
        builder.setSmallIcon(R.drawable.ic_new_releases_white_24dp)
        val text = getColonString(getResources(), R.string.updates_available__genitive, count)
        builder.setColor(getTheme(this).accent)
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        builder.setVibrate(LongArray(0))

        builder.setContentTitle(
            getString(
                R.string.application_name_update__format,
                getApplicationLabel(this),
            ),
        )
        builder.setContentText(text)
        // Set action to ensure unique pending intent
        val intent =
            Intent(this, MainActivity::class.java)
                .setAction("updates")
                .putExtra(C.EXTRA_UPDATE_DATA_MAP, updateDataMap)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        builder.setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        builder.setAutoCancel(true)
        notificationManager.notify(C.NOTIFICATION_ID_UPDATES, builder.build())
    }

    override fun onFavoritesUpdate(
        favoriteItem: FavoritesStorage.FavoriteItem,
        action: FavoritesStorage.Action,
    ) {
        when (action) {
            FavoritesStorage.Action.ADD, FavoritesStorage.Action.REMOVE, FavoritesStorage.Action.MODIFY_TITLE -> {
                drawerForm.updateItems(false, true)
            }

            else -> {}
        }
    }

    override val isWatcherClientForeground: Boolean
        get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onWatcherUpdate(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        counter: WatcherService.Counter,
    ) {
        drawerForm.onWatcherUpdate(chanName!!, boardName, threadNumber, counter)
    }

    override fun setPageTitle(
        title: String?,
        subtitle: String?,
    ) {
        val page = (this.currentFragment as PageFragment).page
        setTitleSubtitle(title, subtitle, page.isThreadsOrPosts)
        if (page.content == Page.Content.POSTS) {
            currentPageItem!!.threadTitle = title
        }
        drawerForm.updateItems(true, false)
    }

    override fun handleRedirect(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
    ) {
        val page = (this.currentFragment as PageFragment).page
        if (page.isThreadsOrPosts) {
            currentPageItem = null
            if (threadNumber == null) {
                navigateBoardsOrThreads(chanName, boardName, false, false)
            } else {
                navigatePosts(chanName, boardName, threadNumber, postNumber, null, false, false)
            }
        }
    }

    override fun closeCurrentPage() {
        val page = (this.currentFragment as PageFragment).page
        val savedPageItem = prepareTargetPreviousPage(true)
        currentPageItem = null
        if (savedPageItem != null) {
            navigateSavedPage(savedPageItem, false)
        } else {
            val chan = get(page.chanName)
            if (isSingleBoardMode(chan)) {
                navigatePage(
                    Page.Content.THREADS,
                    page.chanName,
                    getSingleBoardName(chan),
                    null,
                    null,
                    null,
                    null,
                    0,
                )
            } else {
                navigatePage(
                    Page.Content.BOARDS,
                    page.chanName,
                    null,
                    null,
                    null,
                    null,
                    null,
                    FLAG_PAGE_FROM_CACHE,
                )
            }
        }
    }

    override fun setActionBarLocked(
        locker: String,
        locked: Boolean,
    ) {
        if (locked) {
            expandedScreen.addLocker(locker)
        } else {
            expandedScreen.removeLocker(locker)
        }
    }

    override fun setNavigationAreaLocked(
        locker: String,
        locked: Boolean,
    ) {
        if (locked) {
            navigationAreaLockers.add(locker)
        } else {
            navigationAreaLockers.remove(locker)
        }
        drawerLayout.setExpandableFromAnyPoint(navigationAreaLockers.isEmpty())
    }

    private inner class ExpandedScreenDrawerLocker : DrawerListener {
        override fun onDrawerSlide(
            drawerView: View,
            slideOffset: Float,
        ) {}

        override fun onDrawerOpened(drawerView: View) {
            setActionBarLocked(LOCKER_DRAWER, true)
        }

        override fun onDrawerClosed(drawerView: View) {
            setActionBarLocked(LOCKER_DRAWER, false)
        }

        override fun onDrawerStateChanged(newState: Int) {}
    }

    class InstanceViewModel : ViewModel() {
        internal val extras = HashMap<String?, Retainable>()
        private val cookiesRequirement: Runnable = ChanDatabase.getInstance().requireCookies()

        override fun onCleared() {
            for (retainable in extras.values) {
                retainable.clear()
            }
            extras.clear()
            cookiesRequirement.run()
        }
    }

    companion object {
        private const val EXTRA_FRAGMENTS = "fragments"
        private const val EXTRA_STACK_PAGE_ITEMS = "stackPageItems"
        private const val EXTRA_PRESERVED_PAGE_ITEMS = "preservedPageItems"
        private const val EXTRA_CURRENT_FRAGMENT = "currentFragment"
        private const val EXTRA_CURRENT_PAGE_ITEM = "currentPageItem"
        private const val EXTRA_DRAWER_EXPANDED = "drawerExpanded"
        private const val EXTRA_DRAWER_CHAN_SELECT_MODE = "drawerChanSelectMode"
        private const val EXTRA_STORAGE_REQUEST_STATE = "storageRequestState"

        private val REFERENCE_FRAGMENT = PageFragment()

        private const val LOCKER_DRAWER = "drawer"
        private const val LOCKER_NON_PAGE = "nonPage"
        private const val LOCKER_ACTION_MODE = "actionMode"

        private fun isSingleBoardMode(chan: Chan): Boolean = chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)

        private fun getSingleBoardName(chan: Chan): String? = chan.configuration.getSingleBoardName()

        private const val FLAG_DATA_CLOSE_OVERLAYS = 0x00000001
        private const val FLAG_DATA_FROM_CACHE = 0x00000002
        private const val FLAG_DATA_ALLOW_RETURN = 0x00000004

        private const val FLAG_PAGE_CLOSE_OVERLAYS = 0x00000001
        private const val FLAG_PAGE_FROM_CACHE = 0x00000002
        private const val FLAG_PAGE_ALLOW_RETURN = 0x00000004
        private const val FLAG_PAGE_RESET_SCROLL = 0x00000008
    }
}
