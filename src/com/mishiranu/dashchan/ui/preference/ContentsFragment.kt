package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Pair
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.PagesDatabase
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.CheckPreference
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ProgressDialog

class ContentsFragment : PreferenceFragment() {
    private lateinit var replyNotifications: CheckPreference
    internal var clearCachePreference: Preference<Void?>? = null

    companion object {
        private const val REQUEST_UPDATE_CACHE_SIZE = "contentsUpdateCacheSize"
    }

    override fun getPreferences(): SharedPreferences = Preferences.PREFERENCES!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(REQUEST_UPDATE_CACHE_SIZE, this) { _, _ ->
            clearCachePreference?.invalidate()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        addHeader(R.string.threads)
        addSeek(
            Preferences.KEY_AUTO_REFRESH_INTERVAL,
            Preferences.DEFAULT_AUTO_REFRESH_INTERVAL,
            R.string.refresh_open_thread,
            R.string.every_number_sec__format,
            Pair(Preferences.DISABLED_AUTO_REFRESH_INTERVAL, R.string.disabled),
            Preferences.MIN_AUTO_REFRESH_INTERVAL,
            Preferences.MAX_AUTO_REFRESH_INTERVAL,
            Preferences.STEP_AUTO_REFRESH_INTERVAL,
        )
        addList(
            Preferences.KEY_CYCLICAL_REFRESH,
            enumList(Preferences.CyclicalRefreshMode.values()) { v -> v.value },
            Preferences.DEFAULT_CYCLICAL_REFRESH.value,
            R.string.cyclical_threads_refresh_mode,
            enumResList(Preferences.CyclicalRefreshMode.values()) { v -> v.titleResId },
        )

        addHeader(R.string.favorites)
        addList(
            Preferences.KEY_FAVORITES_ORDER,
            enumList(Preferences.FavoritesOrder.values()) { o -> o.value },
            Preferences.DEFAULT_FAVORITES_ORDER.value,
            R.string.favorite_threads_order,
            enumResList(Preferences.FavoritesOrder.values()) { o -> o.titleResId },
        ).setOnAfterChangeListener { FavoritesStorage.getInstance().sortIfNeeded() }
        addList(
            Preferences.KEY_FAVORITE_ON_REPLY,
            enumList(Preferences.FavoriteOnReplyMode.values()) { o -> o.value },
            Preferences.DEFAULT_FAVORITE_ON_REPLY.value,
            R.string.add_thread_on_reply,
            enumResList(Preferences.FavoriteOnReplyMode.values()) { o -> o.titleResId },
        )
        addCheck(
            true,
            Preferences.KEY_WATCHER_WATCH_INITIALLY,
            Preferences.DEFAULT_WATCHER_WATCH_INITIALLY,
            R.string.watch_initially,
            R.string.watch_initially__summary,
        )

        addHeader(R.string.favorites_watcher)
        addSeek(
            Preferences.KEY_WATCHER_REFRESH_INTERVAL,
            Preferences.DEFAULT_WATCHER_REFRESH_INTERVAL,
            R.string.refresh_favorites,
            R.string.every_number_sec__format,
            Pair(Preferences.DISABLED_WATCHER_REFRESH_INTERVAL, R.string.disabled),
            Preferences.MIN_WATCHER_REFRESH_INTERVAL,
            Preferences.MAX_WATCHER_REFRESH_INTERVAL,
            Preferences.STEP_WATCHER_REFRESH_INTERVAL,
        )
        addCheck(true, Preferences.KEY_WATCHER_WIFI_ONLY, Preferences.DEFAULT_WATCHER_WIFI_ONLY, R.string.wifi_only, 0)
        replyNotifications =
            addCheck(
                false,
                "reply_notifications",
                false,
                R.string.reply_notifications,
                R.string.reply_notifications__format,
            )
        replyNotifications.setOnClickListener { p ->
            Preferences.setWatcherNotifications(
                if (p!!.value!!) {
                    emptySet()
                } else {
                    setOf(Preferences.NotificationFeature.ENABLED)
                },
            )
            invalidateReplyNotifications()
        }
        invalidateReplyNotifications()

        addHeader(R.string.additional)
        clearCachePreference =
            addButton(getString(R.string.clear_cache)) {
                StringUtils.formatFileSizeMegabytes(PagesDatabase.getInstance().size)
            }
        clearCachePreference!!.setOnClickListener {
            val dialog = ClearCacheDialog()
            dialog.show(childFragmentManager, ClearCacheDialog::class.java.name)
        }
        clearCachePreference!!.invalidate()

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.contents), null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearCachePreference = null
    }

    internal fun invalidateReplyNotifications() {
        replyNotifications.value =
            Preferences.watcherNotifications
                .contains(Preferences.NotificationFeature.ENABLED)
    }

    class WatcherNotificationsDialog : DialogFragment() {
        private var checkedItems: BooleanArray? = null

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val items = arrayOfNulls<String>(Preferences.NotificationFeature.values().size)
            for (i in items.indices) {
                items[i] = getString(Preferences.NotificationFeature.values()[i].titleResId)
            }
            if (savedInstanceState != null) {
                checkedItems = savedInstanceState.getBooleanArray(EXTRA_CHECKED_ITEMS)
            } else {
                checkedItems = BooleanArray(Preferences.NotificationFeature.values().size)
                val notificationFeatures = Preferences.watcherNotifications
                for (i in checkedItems!!.indices) {
                    checkedItems!![i] = notificationFeatures.contains(Preferences.NotificationFeature.values()[i])
                }
            }
            return AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.reply_notifications)
                .setMultiChoiceItems(items, checkedItems) { _, which, isChecked -> checkedItems!![which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val notificationFeatures = HashSet<Preferences.NotificationFeature>()
                    for (i in checkedItems!!.indices) {
                        if (checkedItems!![i]) {
                            notificationFeatures.add(Preferences.NotificationFeature.values()[i])
                        }
                    }
                    Preferences.setWatcherNotifications(notificationFeatures)
                    (parentFragment as ContentsFragment).invalidateReplyNotifications()
                }.setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBooleanArray(EXTRA_CHECKED_ITEMS, checkedItems)
        }

        companion object {
            private const val EXTRA_CHECKED_ITEMS = "checkedItems"
        }
    }

    class ClearCacheDialog : DialogFragment() {
        private var checkedIndex = 0

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            checkedIndex = savedInstanceState?.getInt(EXTRA_CHECKED_INDEX) ?: 0
            val items = arrayOf(getString(R.string.old_threads), getString(R.string.all_threads))
            return AlertDialog
                .Builder(requireContext())
                .setTitle(getString(R.string.clear_cache))
                .setSingleChoiceItems(items, checkedIndex) { _, which -> checkedIndex = which }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val clearingDialog = ClearingDialog(checkedIndex == 1)
                    clearingDialog.show(parentFragment!!.parentFragmentManager, ClearingDialog::class.java.name)
                }.setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putInt(EXTRA_CHECKED_INDEX, checkedIndex)
        }

        companion object {
            private const val EXTRA_CHECKED_INDEX = "checkedIndex"
        }
    }

    class ClearingDialog : DialogFragment {
        constructor()

        constructor(allPages: Boolean) {
            val args = Bundle()
            args.putBoolean(EXTRA_ALL_PAGES, allPages)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): ProgressDialog {
            val dialog = ProgressDialog(requireContext(), null)
            dialog.setMessage(getString(R.string.clearing__ellipsis))
            return dialog
        }

        // View-less DialogFragment: onViewStateRestored never runs, so start the task from onStart.
        private var dialogInitialized = false

        override fun onStart() {
            if (!dialogInitialized) {
                dialogInitialized = true
                initializeDialog()
            }
            super.onStart()
        }

        private fun initializeDialog() {
            val viewModel = ViewModelProvider(this).get(ClearCacheViewModel::class.java)
            if (!viewModel.hasTaskOrValue()) {
                val args = requireArguments()
                val allPages = args.getBoolean(EXTRA_ALL_PAGES)
                var openThreads: Collection<PagesDatabase.ThreadKey> = emptyList()
                if (!allPages) {
                    val drawerPages = (requireActivity() as FragmentHandler).obtainDrawerPages()
                    val list = ArrayList<PagesDatabase.ThreadKey>(drawerPages.size)
                    for (page in drawerPages) {
                        if (page.threadNumber != null) {
                            list.add(PagesDatabase.ThreadKey(page.chanName, page.boardName, page.threadNumber))
                        }
                    }
                    openThreads = list
                }
                val task = ClearCacheTask(viewModel, allPages, openThreads)
                task.execute(ConcurrentUtils.SEPARATE_EXECUTOR)
                viewModel.attach(task)
            }
            viewModel.observe(this) {
                dismiss()
                sendUpdateCacheSize()
            }
        }

        private fun sendUpdateCacheSize() {
            parentFragmentManager.setFragmentResult(REQUEST_UPDATE_CACHE_SIZE, Bundle())
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            sendUpdateCacheSize()
        }

        companion object {
            private const val EXTRA_ALL_PAGES = "allPages"
        }
    }

    class ClearCacheViewModel : TaskViewModel<ClearCacheTask, Any?>()

    class ClearCacheTask(
        private val viewModel: ClearCacheViewModel,
        private val allPages: Boolean,
        private val openThreads: Collection<PagesDatabase.ThreadKey>,
    ) : ExecutorTask<Unit, Any?>() {
        override fun run(): Any? {
            if (allPages) {
                PagesDatabase.getInstance().eraseAll()
            } else {
                PagesDatabase.getInstance().erase(openThreads)
            }
            return null
        }

        override fun onComplete(result: Any?) {
            viewModel.handleResult(this)
        }
    }
}
