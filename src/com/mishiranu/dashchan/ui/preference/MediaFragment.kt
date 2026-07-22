package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import chan.content.ChanMarkup
import chan.util.DataFile
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.gallery.VideoSideControls
import com.mishiranu.dashchan.ui.preference.core.Preference
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.FilenameUtils
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ProgressDialog

class MediaFragment :
    PreferenceFragment(),
    FragmentHandler.Callback {
    private var downloadUriTreePreference: Preference<Void?>? = null
    internal var clearCachePreference: Preference<Void?>? = null

    private var inStorageRequest = false

    override fun getPreferences(): SharedPreferences = Preferences.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inStorageRequest = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_IN_STORAGE_REQUEST)
        parentFragmentManager.setFragmentResultListener(REQUEST_UPDATE_CACHE_SIZE, this) { _, _ ->
            clearCachePreference?.invalidate()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        addHeader(R.string.images)
        addList(
            Preferences.KEY_LOAD_THUMBNAILS,
            enumList(Preferences.NetworkMode.values()) { v -> v.value },
            Preferences.DEFAULT_LOAD_THUMBNAILS.value,
            R.string.load_thumbnails,
            enumResList(Preferences.NetworkMode.values()) { v -> v.titleResId },
        )
        addList(
            Preferences.KEY_LOAD_NEAREST_IMAGE,
            enumList(Preferences.NetworkMode.values()) { v -> v.value },
            Preferences.DEFAULT_LOAD_NEAREST_IMAGE.value,
            R.string.load_nearest_image,
            enumResList(Preferences.NetworkMode.values()) { v -> v.titleResId },
        )

        addHeader(R.string.posting_media)
        addCheck(
            true,
            Preferences.KEY_ALWAYS_UNIQUE_HASH,
            Preferences.DEFAULT_ALWAYS_UNIQUE_HASH,
            R.string.always_unique_hash,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_ALWAYS_CLEAR_METADATA,
            Preferences.DEFAULT_ALWAYS_CLEAR_METADATA,
            R.string.always_clear_metadata,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_ALWAYS_REMOVE_FILENAME,
            Preferences.DEFAULT_ALWAYS_REMOVE_FILENAME,
            R.string.always_remove_filename,
            0,
        )
        addCheck(
            true,
            Preferences.KEY_ALWAYS_RENAME_FILENAME,
            Preferences.DEFAULT_ALWAYS_RENAME_FILENAME,
            R.string.always_rename_files,
            0,
        )
        addDependency(Preferences.KEY_ALWAYS_RENAME_FILENAME, Preferences.KEY_ALWAYS_REMOVE_FILENAME, false)
        addEdit(
            Preferences.KEY_FILE_NEWNAME,
            Preferences.DEFAULT_FILE_NEWNAME,
            R.string.new_filename,
            Preferences.DEFAULT_FILE_NEWNAME,
            InputType.TYPE_CLASS_TEXT,
        ).addFilter { source, start, end, _, _, _ ->
            for (i in start until end) {
                if (!FilenameUtils.isValidCharacter(source!![i])) {
                    return@addFilter ""
                }
            }
            null
        }.addFilter(InputFilter.LengthFilter(FilenameUtils.getFilenameMaxCharacterCount()))
        addDependency(Preferences.KEY_FILE_NEWNAME, Preferences.KEY_ALWAYS_REMOVE_FILENAME, false)

        addHeader(R.string.downloads)
        addCheck(
            true,
            Preferences.KEY_DOWNLOAD_DETAIL_NAME,
            Preferences.DEFAULT_DOWNLOAD_DETAIL_NAME,
            R.string.detailed_file_name,
            R.string.detailed_file_name__summary,
        )
        addCheck(
            true,
            Preferences.KEY_DOWNLOAD_ORIGINAL_NAME,
            Preferences.DEFAULT_DOWNLOAD_ORIGINAL_NAME,
            R.string.original_file_name,
            R.string.original_file_name__summary,
        )
        val downloadUriTreePreference =
            addButton(getString(R.string.download_directory)) {
                DataFile.obtain(DataFile.Target.DOWNLOADS, null).getName()
            }
        this.downloadUriTreePreference = downloadUriTreePreference
        downloadUriTreePreference.setOnClickListener {
            if ((requireActivity() as FragmentHandler).requestStorage()) {
                inStorageRequest = true
            }
        }

        addList(
            Preferences.KEY_DOWNLOAD_SUBDIR,
            enumList(Preferences.DownloadSubdirMode.values()) { v -> v.value },
            Preferences.DEFAULT_DOWNLOAD_SUBDIR.value,
            R.string.show_download_configuration_dialog,
            enumResList(Preferences.DownloadSubdirMode.values()) { v -> v.titleResId },
        )
        val subdirectoryPreference =
            addEdit(
                Preferences.KEY_SUBDIR_PATTERN,
                Preferences.DEFAULT_SUBDIR_PATTERN,
                R.string.subdirectory_pattern,
                Preferences.DEFAULT_SUBDIR_PATTERN,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            )
        val subdirectoryHtml = IOUtils.readRawResourceString(resources, R.raw.markup_subdirectory)
        subdirectoryPreference.setDescription(BUILDER_SUBDIRECTORY.fromHtmlReduced(subdirectoryHtml))
        subdirectoryPreference.setNeutralButton(getString(R.string.more_info)) {
            showSubdirectoryInfoDialog(childFragmentManager)
        }
        addCheck(
            true,
            Preferences.KEY_NOTIFY_DOWNLOAD_COMPLETE,
            Preferences.DEFAULT_NOTIFY_DOWNLOAD_COMPLETE,
            R.string.notify_when_download_is_completed,
            R.string.notify_when_download_is_completed__summary,
        )

        addList(
            Preferences.KEY_MEDIA_LOADING_ACTION,
            enumList(Preferences.MediaLoadingAction.values()) { v -> v.value },
            Preferences.DEFAULT_MEDIA_LOADING_ACTION.value,
            R.string.standard_action_when_loading_media,
            enumResList(Preferences.MediaLoadingAction.values()) { v -> v.titleResId },
        )

        addHeader(R.string.video_player)
        addCheck(
            true,
            Preferences.KEY_USE_VIDEO_PLAYER,
            Preferences.DEFAULT_USE_VIDEO_PLAYER,
            R.string.use_built_in_video_player,
            R.string.use_built_in_video_player__summary,
        )
        addList(
            Preferences.KEY_VIDEO_COMPLETION,
            enumList(Preferences.VideoCompletionMode.values()) { o -> o.value },
            Preferences.DEFAULT_VIDEO_COMPLETION.value,
            R.string.action_on_playback_completion,
            enumResList(Preferences.VideoCompletionMode.values()) { o -> o.titleResId },
        )
        addCheck(
            true,
            Preferences.KEY_VIDEO_PLAY_AFTER_SCROLL,
            Preferences.DEFAULT_VIDEO_PLAY_AFTER_SCROLL,
            R.string.play_after_scroll,
            R.string.play_after_scroll__summary,
        )
        addCheck(
            true,
            Preferences.KEY_VIDEO_SEEK_ANY_FRAME,
            Preferences.DEFAULT_VIDEO_SEEK_ANY_FRAME,
            R.string.seek_any_frame,
            R.string.seek_any_frame__summary,
        )
        addCheck(
            true,
            Preferences.KEY_VIDEO_MULTI_TAP_SEEK,
            Preferences.DEFAULT_VIDEO_MULTI_TAP_SEEK,
            R.string.multi_tap_seek,
            R.string.multi_tap_seek__summary,
        )
        addDependency(Preferences.KEY_VIDEO_COMPLETION, Preferences.KEY_USE_VIDEO_PLAYER, true)
        addDependency(Preferences.KEY_VIDEO_PLAY_AFTER_SCROLL, Preferences.KEY_USE_VIDEO_PLAYER, true)
        addDependency(Preferences.KEY_VIDEO_SEEK_ANY_FRAME, Preferences.KEY_USE_VIDEO_PLAYER, true)
        addDependency(Preferences.KEY_VIDEO_MULTI_TAP_SEEK, Preferences.KEY_USE_VIDEO_PLAYER, true)

        // Configurable playback speeds for the player's side-column button (1x is always available;
        // if none of these are enabled the button is hidden).
        addHeader(R.string.playback_speed_options)
        for (speed in Preferences.VIDEO_SPEED_OPTIONS) {
            val key = Preferences.videoSpeedKey(speed)
            addCheck(
                true,
                key,
                Preferences.videoSpeedDefaultEnabled(speed),
                VideoSideControls.formatSpeed(speed),
                null,
            )
            addDependency(key, Preferences.KEY_USE_VIDEO_PLAYER, true)
        }

        addHeader(R.string.additional)
        addSeek(
            Preferences.KEY_CACHE_SIZE,
            Preferences.DEFAULT_CACHE_SIZE,
            getString(R.string.cache_size),
            "%d MB",
            null,
            Preferences.MIN_CACHE_SIZE,
            Preferences.MAX_CACHE_SIZE,
            Preferences.STEP_CACHE_SIZE,
        )
        val clearCachePreference =
            addButton(getString(R.string.clear_cache)) {
                StringUtils.formatFileSizeMegabytes(CacheManager.getInstance().cacheSize)
            }
        this.clearCachePreference = clearCachePreference
        clearCachePreference.setOnClickListener {
            val dialog = ClearCacheDialog()
            dialog.show(childFragmentManager, ClearCacheDialog::class.java.name)
        }
        clearCachePreference.invalidate()

        addDependency(
            Preferences.KEY_SUBDIR_PATTERN,
            Preferences.KEY_DOWNLOAD_SUBDIR,
            false,
            Preferences.DownloadSubdirMode.DISABLED.value,
        )
        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.media), null)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        downloadUriTreePreference = null
        clearCachePreference = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(EXTRA_IN_STORAGE_REQUEST, inStorageRequest)
    }

    override fun onStorageRequestResult() {
        if (inStorageRequest) {
            inStorageRequest = false
            downloadUriTreePreference!!.invalidate()
        }
    }

    class ClearCacheDialog : DialogFragment() {
        private var checkedItems: BooleanArray? = null

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val checkedItems =
                savedInstanceState?.getBooleanArray(EXTRA_CHECKED_ITEMS) ?: booleanArrayOf(true, true)
            this.checkedItems = checkedItems
            val items = arrayOf(getString(R.string.thumbnails), getString(R.string.cached_files))
            return AlertDialog
                .Builder(requireContext())
                .setTitle(getString(R.string.clear_cache))
                .setMultiChoiceItems(items, checkedItems) { _, which, isChecked -> checkedItems[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val clearingDialog = ClearingDialog(checkedItems[0], checkedItems[1])
                    clearingDialog.show(parentFragment!!.parentFragmentManager, ClearingDialog::class.java.name)
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

    class ClearingDialog : DialogFragment {
        constructor()

        constructor(thumbnails: Boolean, media: Boolean) {
            val args = Bundle()
            args.putBoolean(EXTRA_THUMBNAILS, thumbnails)
            args.putBoolean(EXTRA_MEDIA, media)
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
                val thumbnails = args.getBoolean(EXTRA_THUMBNAILS)
                val media = args.getBoolean(EXTRA_MEDIA)
                val task = ClearCacheTask(viewModel, thumbnails, media)
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
            private const val EXTRA_THUMBNAILS = "thumbnails"
            private const val EXTRA_MEDIA = "media"
        }
    }

    class ClearCacheViewModel : TaskViewModel<ClearCacheTask, Any?>()

    class ClearCacheTask(
        private val viewModel: ClearCacheViewModel,
        private val thumbnails: Boolean,
        private val media: Boolean,
    ) : ExecutorTask<Unit?, Any?>() {
        override fun run(): Any? {
            if (thumbnails) {
                CacheManager.getInstance().eraseThumbnailsCache()
            }
            if (isCancelled()) {
                return null
            }
            if (media) {
                CacheManager.getInstance().eraseMediaCache()
            }
            return null
        }

        override fun onComplete(result: Any?) {
            viewModel.handleResult(this)
        }
    }

    companion object {
        private const val EXTRA_IN_STORAGE_REQUEST = "inStorageRequest"
        private const val REQUEST_UPDATE_CACHE_SIZE = "mediaUpdateCacheSize"

        private val BUILDER_SUBDIRECTORY =
            ChanMarkup.MarkupBuilder { markup ->
                markup!!.addTag("b", ChanMarkup.TAG_BOLD)
            }

        private fun showSubdirectoryInfoDialog(fragmentManager: FragmentManager) {
            InstanceDialog(fragmentManager, null) { provider ->
                val context: Context = provider.context
                val html = IOUtils.readRawResourceString(context.resources, R.raw.markup_subdirectory_info)
                AlertDialog
                    .Builder(context)
                    .setTitle(R.string.subdirectory_pattern)
                    .setMessage(BUILDER_SUBDIRECTORY.fromHtmlReduced(html))
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            }
        }
    }
}
