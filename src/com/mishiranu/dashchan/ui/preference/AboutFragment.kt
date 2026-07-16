package com.mishiranu.dashchan.ui.preference

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import androidx.fragment.app.DialogFragment
import chan.util.CommonUtils
import chan.util.DataFile
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.BackupManager
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.SharedPreferences
import com.mishiranu.dashchan.widget.ClickableToast

class AboutFragment :
    PreferenceFragment(),
    FragmentHandler.Callback {
    private var inStorageRequest = false

    override fun getPreferences(): SharedPreferences = Preferences.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inStorageRequest = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_IN_STORAGE_REQUEST)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        addButton(R.string.statistics, 0)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(StatisticsFragment()) }
        addButton(R.string.backup_data, R.string.backup_data__summary)
            .setOnClickListener { BackupDialog().show(childFragmentManager, BackupDialog::class.java.name) }
        addButton(R.string.changelog, 0)
            .setOnClickListener {
                (requireActivity() as FragmentHandler).pushFragment(TextFragment(TextFragment.Type.CHANGELOG))
            }
        addButton(R.string.check_for_updates, 0)
            .setOnClickListener { (requireActivity() as FragmentHandler).pushFragment(UpdateFragment()) }
        addButton(R.string.foss_licenses, R.string.foss_licenses__summary)
            .setOnClickListener {
                (requireActivity() as FragmentHandler).pushFragment(TextFragment(TextFragment.Type.LICENSES))
            }
        val versionDate =
            TextFragment.formatChangelogDate(DateFormat.getDateFormat(requireContext()), BuildConfig.VERSION_DATE)
        addButton(
            getString(R.string.version),
            BuildConfig.VERSION_NAME +
                (if (versionDate != null) " $versionDate" else ""),
        )

        (requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.about), null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(EXTRA_IN_STORAGE_REQUEST, inStorageRequest)
    }

    override fun onStorageRequestResult() {
        if (inStorageRequest) {
            inStorageRequest = false
            if (Preferences.getDownloadUriTree(requireContext()) != null) {
                restoreBackup()
            }
        }
    }

    internal fun restoreBackup() {
        if (Preferences.getDownloadUriTree(requireContext()) == null) {
            if ((requireActivity() as FragmentHandler).requestStorage()) {
                inStorageRequest = true
            }
        } else {
            val backupFiles = BackupManager.getAvailableBackups(requireContext())
            if (backupFiles.isNotEmpty()) {
                val dialog = RestoreListDialog(backupFiles)
                dialog.show(childFragmentManager, RestoreListDialog::class.java.name)
            } else {
                ClickableToast.show(R.string.backups_not_found)
            }
        }
    }

    class BackupDialog :
        DialogFragment(),
        DialogInterface.OnClickListener {
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val items = arrayOf(getString(R.string.save_data), getString(R.string.restore_data))
            return AlertDialog
                .Builder(requireContext())
                .setItems(items, this)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        override fun onClick(
            dialog: DialogInterface,
            which: Int,
        ) {
            if (which == 0) {
                val binder = (requireActivity() as FragmentHandler).getDownloadBinder()
                if (binder != null) {
                    BackupManager.makeBackup(binder, requireContext())
                }
            } else if (which == 1) {
                (parentFragment as AboutFragment).restoreBackup()
            }
        }
    }

    class RestoreListDialog : DialogFragment {
        constructor()

        constructor(backupFiles: List<BackupManager.BackupFile>) {
            val args = Bundle()
            val files = ArrayList<String>(backupFiles.size)
            val names = ArrayList<String>(backupFiles.size)
            for (backupFile in backupFiles) {
                files.add(backupFile.file!!.getRelativePath())
                names.add(backupFile.name!!)
            }
            args.putStringArrayList(EXTRA_FILES, files)
            args.putStringArrayList(EXTRA_NAMES, names)
            arguments = args
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val names = requireArguments().getStringArrayList(EXTRA_NAMES)
            val items = CommonUtils.toArray(names, String::class.java)
            return AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.restore_data)
                .setItems(items) { _, which ->
                    val path = requireArguments().getStringArrayList(EXTRA_FILES)!![which]
                    val file = DataFile.obtain(DataFile.Target.DOWNLOADS, path)
                    val entries = BackupManager.readBackupEntries(file)
                    if (entries.isEmpty()) {
                        ClickableToast.show(R.string.invalid_data_format)
                    } else {
                        val dialog = RestoreEntriesDialog(file, entries)
                        dialog.show(parentFragmentManager, RestoreEntriesDialog::class.java.name)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        companion object {
            private const val EXTRA_FILES = "files"
            private const val EXTRA_NAMES = "names"
        }
    }

    class RestoreEntriesDialog : DialogFragment {
        constructor()

        constructor(file: DataFile, entries: List<BackupManager.Entry>) {
            val args = Bundle()
            val entryNames = ArrayList<String>()
            for (entry in entries) {
                entryNames.add(entry.name)
            }
            args.putString(EXTRA_FILE, file.getRelativePath())
            args.putStringArrayList(EXTRA_ENTRIES, entryNames)
            arguments = args
        }

        private lateinit var checkedItems: BooleanArray

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES)!!
            val items = arrayOfNulls<String>(entryNames.size)
            for (i in items.indices) {
                items[i] = getString(BackupManager.Entry.valueOf(entryNames[i]).titleResId)
            }
            checkedItems = BooleanArray(items.size)
            val checked =
                if (savedInstanceState != null) {
                    savedInstanceState.getStringArrayList(EXTRA_CHECKED)
                } else {
                    null
                }
            for (i in checkedItems.indices) {
                checkedItems[i] = checked == null || checked.contains(entryNames[i])
            }
            return AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.restore_data)
                .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }.setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> loadBackup() }
                .create()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            val entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES)!!
            val checked = ArrayList<String>()
            for (i in checkedItems.indices) {
                if (checkedItems[i]) {
                    checked.add(entryNames[i])
                }
            }
            outState.putStringArrayList(EXTRA_CHECKED, checked)
        }

        private fun loadBackup() {
            val entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES)!!
            val checked = HashSet<BackupManager.Entry>()
            for (i in checkedItems.indices) {
                if (checkedItems[i]) {
                    checked.add(BackupManager.Entry.valueOf(entryNames[i]))
                }
            }
            if (!checked.isEmpty()) {
                val path = requireArguments().getString(EXTRA_FILE)
                val file = DataFile.obtain(DataFile.Target.DOWNLOADS, path)
                if (BackupManager.loadBackup(file, checked)) {
                    NavigationUtils.restartApplication(requireContext())
                } else {
                    ClickableToast.show(R.string.unknown_error)
                }
            }
        }

        companion object {
            private const val EXTRA_FILE = "file"
            private const val EXTRA_ENTRIES = "entries"
            private const val EXTRA_CHECKED = "checked"
        }
    }

    companion object {
        private const val EXTRA_IN_STORAGE_REQUEST = "inStorageRequest"
    }
}
