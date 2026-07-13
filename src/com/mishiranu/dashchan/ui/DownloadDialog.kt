package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Pair
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import chan.content.Chan.Companion.get
import chan.util.CommonUtils.equals
import chan.util.DataFile
import chan.util.DataFile.Companion.isValidSegment
import chan.util.DataFile.Companion.obtain
import chan.util.StringUtils.cutIfLongerToLine
import chan.util.StringUtils.escapeFile
import chan.util.StringUtils.getFileExtension
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.FileProvider
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.Preferences.MediaLoadingAction
import com.mishiranu.dashchan.content.Preferences.formatSubdir
import com.mishiranu.dashchan.content.Preferences.getSubdir
import com.mishiranu.dashchan.content.Preferences.isDownloadDetailName
import com.mishiranu.dashchan.content.Preferences.isDownloadOriginalName
import com.mishiranu.dashchan.content.Preferences.mediaLoadingAction
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.service.DownloadService.ChoiceRequest
import com.mishiranu.dashchan.content.service.DownloadService.DirectRequest
import com.mishiranu.dashchan.content.service.DownloadService.PrepareRequest
import com.mishiranu.dashchan.content.service.DownloadService.ReplaceRequest
import com.mishiranu.dashchan.util.ConcurrentUtils.newSingleThreadPool
import com.mishiranu.dashchan.util.MimeTypes.forExtension
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.ProgressDialog
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.Any
import kotlin.Boolean
import kotlin.CharSequence
import kotlin.Comparable
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.String
import kotlin.intArrayOf
import kotlin.synchronized

class DownloadDialog(context: Context?, callback: Callback) {
    interface Callback {
        fun resolve(choiceRequest: ChoiceRequest, directRequest: DirectRequest?)
        fun resolve(replaceRequest: ReplaceRequest, action: ReplaceRequest.Action?)
        fun cancel(prepareRequest: DownloadService.PrepareRequest)
    }

    private val context: Context
    private val callback: Callback

    private class DialogHolder<Request> {
        private var request: Request? = null
        internal var dialog: AlertDialog? = null

        fun dismissIfNotEqual(request: Request?) {
            if (dialog != null && (request == null || this.request !== request)) {
                this.request = null
                dialog!!.dismiss()
                dialog = null
            }
        }

        fun onDismiss(dialog: DialogInterface?) {
            if (this.dialog === dialog) {
                request = null
                this.dialog = null
            }
        }

        fun install(request: Request?, dialog: AlertDialog?) {
            this.request = request
            this.dialog = dialog
        }
    }

    private val choiceDialog = DialogHolder<ChoiceRequest?>()
    private val replaceDialog = DialogHolder<ReplaceRequest?>()
    private val prepareDialog = DialogHolder<PrepareRequest?>()

    init {
        this.context = ContextThemeWrapper(context, R.style.Theme_Gallery)
        this.callback = callback
    }

    fun handleRequest(request: DownloadService.Request?) {
        if (request is ChoiceRequest) {
            val choiceRequest = request
            choiceDialog.dismissIfNotEqual(choiceRequest)
            replaceDialog.dismissIfNotEqual(null)
            prepareDialog.dismissIfNotEqual(null)
            if (choiceDialog.dialog == null) {
                choiceDialog.install(
                    choiceRequest,
                    createChoice(
                        choiceRequest,
                        DialogInterface.OnDismissListener { dialog: DialogInterface? ->
                            choiceDialog.onDismiss(
                                dialog
                            )
                        })
                )
            }
        } else if (request is ReplaceRequest) {
            val replaceRequest = request
            choiceDialog.dismissIfNotEqual(null)
            replaceDialog.dismissIfNotEqual(replaceRequest)
            prepareDialog.dismissIfNotEqual(null)
            if (replaceDialog.dialog == null) {
                when (mediaLoadingAction) {
                    MediaLoadingAction.REPLACE -> {
                        callback.resolve(replaceRequest, ReplaceRequest.Action.REPLACE)
                    }

                    MediaLoadingAction.SKIP -> {
                        callback.resolve(replaceRequest, ReplaceRequest.Action.SKIP)
                    }

                    MediaLoadingAction.KEEP_ALL -> {
                        callback.resolve(replaceRequest, ReplaceRequest.Action.KEEP_ALL)
                    }

                    else -> {
                        replaceDialog.install(
                            replaceRequest,
                            createReplace(
                                replaceRequest,
                                DialogInterface.OnDismissListener { dialog: DialogInterface? ->
                                    choiceDialog.onDismiss(
                                        dialog
                                    )
                                })
                        )
                    }
                }
            }
        } else if (request is PrepareRequest) {
            val prepareRequest = request
            choiceDialog.dismissIfNotEqual(null)
            replaceDialog.dismissIfNotEqual(null)
            prepareDialog.dismissIfNotEqual(prepareRequest)
            if (prepareDialog.dialog == null) {
                prepareDialog.install(
                    prepareRequest,
                    createPrepare(
                        prepareRequest,
                        DialogInterface.OnDismissListener { dialog: DialogInterface? ->
                            choiceDialog.onDismiss(
                                dialog
                            )
                        })
                )
            }
        } else if (request == null) {
            choiceDialog.dismissIfNotEqual(null)
            replaceDialog.dismissIfNotEqual(null)
            prepareDialog.dismissIfNotEqual(null)
        } else {
            throw IllegalArgumentException("Request is not supported")
        }
    }

    private class ChoiceState {
        var subdirectory: Boolean = false
        var detailedName: Boolean = false
        var originalName: Boolean = false
        var path: String? = null
    }

    private fun createChoice(
        choiceRequest: ChoiceRequest,
        onDismissListener: DialogInterface.OnDismissListener
    ): AlertDialog {
        var newState = false
        if (choiceRequest.state !is ChoiceState) {
            choiceRequest.state = ChoiceState()
            newState = true
        }
        val state: ChoiceState = choiceRequest.state as ChoiceState
        if (newState) {
            state.detailedName = isDownloadDetailName
            state.originalName = isDownloadOriginalName
        }

        val root = obtain(DataFile.Target.DOWNLOADS, null)
        val inputMethodManager = context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_download_choice, null)

        var allowDetailName = choiceRequest.allowDetailName()
        val allowOriginalName = choiceRequest.allowOriginalName()
        val detailNameCheckBox = view.findViewById<CheckBox>(R.id.download_detail_name)
        val originalNameCheckBox = view.findViewById<CheckBox>(R.id.download_original_name)
        if (choiceRequest.chanName == null && choiceRequest.boardName == null && choiceRequest.threadNumber == null) {
            allowDetailName = false
        }
        if (allowDetailName) {
            detailNameCheckBox.setChecked(state.detailedName)
            detailNameCheckBox.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b: CompoundButton?, isChecked: Boolean ->
                state.detailedName = isChecked
            })
        } else {
            detailNameCheckBox.setVisibility(View.GONE)
        }
        if (allowOriginalName) {
            originalNameCheckBox.setChecked(state.originalName)
            originalNameCheckBox.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b: CompoundButton?, isChecked: Boolean ->
                state.originalName = isChecked
            })
        } else {
            originalNameCheckBox.setVisibility(View.GONE)
        }

        val editText = view.findViewById<AutoCompleteTextView>(android.R.id.text1)
        if (!allowDetailName && !allowOriginalName) {
            (editText.getLayoutParams() as MarginLayoutParams).topMargin = 0
        }

        if (choiceRequest.chanName != null && choiceRequest.threadNumber != null) {
            val chanTitle = get(choiceRequest.chanName).configuration.getTitle()
            var threadTitle = choiceRequest.threadTitle
            if (threadTitle != null) {
                threadTitle = escapeFile(cutIfLongerToLine(threadTitle, 50, false), false)
            }
            var text = getSubdir(
                choiceRequest.chanName, chanTitle,
                choiceRequest.boardName, choiceRequest.threadNumber, threadTitle
            )
            if (newState) {
                state.path = text
            }
            editText.setText(state.path)
            editText.setSelection(editText.getText().length)
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable) {
                    state.path = s.toString()
                }
            })
            if (isEmpty(text)) {
                text = formatSubdir(
                    Preferences.DEFAULT_SUBDIR_PATTERN, choiceRequest.chanName,
                    chanTitle, choiceRequest.boardName, choiceRequest.threadNumber, threadTitle
                )
            }
            editText.setHint(text)
        }

        editText.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>, v: View, position: Int, id: Long ->
            v.post(
                Runnable {
                    val adapter = editText.getAdapter() as Adapter
                    adapter.items = mutableListOf<DialogDirectory>()
                    adapter.notifyDataSetChanged()
                    refreshDropDownContents(editText)
                    editText.showDropDown()
                })
        })
        val dropDownRunnable = Runnable { editText.showDropDown() }

        val radioGroup = view.findViewById<RadioGroup>(R.id.download_choice)
        radioGroup.setOnCheckedChangeListener(RadioGroup.OnCheckedChangeListener { rg: RadioGroup?, checkedId: Int ->
            val enabled = checkedId == R.id.download_subdirectory
            editText.setEnabled(enabled)
            state.subdirectory = enabled
            if (enabled) {
                editText.dismissDropDown()
                refreshDropDownContents(editText)
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    editText.postDelayed(dropDownRunnable, 250)
                } else {
                    dropDownRunnable.run()
                }
            } else {
                editText.removeCallbacks(dropDownRunnable)
            }
        })
        radioGroup.check(if (state.subdirectory) R.id.download_subdirectory else R.id.download_common)
        view.findViewById<RadioButton?>(R.id.download_common)
            .setText(context.getString(R.string.save_to_directory__format, root.getName()))

        val adapter = Adapter(root, Runnable {
            if (editText.isEnabled()) {
                refreshDropDownContents(editText)
            }
        })
        editText.setAdapter<Adapter?>(adapter)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.select_where_to_save)
            .setView(view)
            .setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    callback.resolve(
                        choiceRequest,
                        null
                    )
                })
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    handleChoiceResolve(
                        choiceRequest,
                        editText, detailNameCheckBox, originalNameCheckBox
                    )
                })
            .setOnCancelListener(DialogInterface.OnCancelListener { d: DialogInterface? ->
                callback.resolve(
                    choiceRequest,
                    null
                )
            })
            .create()
        dialog.getWindow()!!.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    (WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        )
        editText.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            handleChoiceResolve(choiceRequest, editText, detailNameCheckBox, originalNameCheckBox)
            dialog.dismiss()
            true
        })
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
            adapter.shutdown()
            onDismissListener.onDismiss(d)
        })
        dialog.show()
        return dialog
    }

    private fun refreshDropDownContents(editText: AutoCompleteTextView) {
        val editable = editText.getEditableText()
        val watchers = editable.getSpans<TextWatcher?>(0, editable.length, TextWatcher::class.java)
        if (watchers != null) {
            for (watcher in watchers) {
                watcher.beforeTextChanged(editable, 0, 0, 0)
                watcher.onTextChanged(editable, 0, 0, 0)
                watcher.afterTextChanged(editable)
            }
        }
    }

    private fun handleChoiceResolve(
        choiceRequest: ChoiceRequest,
        editText: AutoCompleteTextView, detailNameCheckBox: CheckBox, originalNameCheckBox: CheckBox
    ) {
        val pathCandidate = if (editText.isEnabled()) escapeFile(
            editText.getText().toString(),
            true
        )!!.trim { it <= ' ' } else ""
        val segments = ArrayList<String?>()
        for (segment in pathCandidate.split("/".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            if (isValidSegment(segment)) {
                segments.add(segment)
            }
        }
        val path = nullIfEmpty(Adapter.Companion.buildPath(segments, 0))
        val directRequest = choiceRequest.complete(
            path,
            detailNameCheckBox.isChecked(), originalNameCheckBox.isChecked()
        )
        callback.resolve(choiceRequest, directRequest)
    }

    private class ReplaceState {
        var selectedId: Int = 0
    }

    private fun createReplace(
        replaceRequest: ReplaceRequest,
        onDismissListener: DialogInterface.OnDismissListener?
    ): AlertDialog {
        if (replaceRequest.state !is ReplaceState) {
            replaceRequest.state = ReplaceState()
        }
        val state = replaceRequest.state as ReplaceState

        val count = replaceRequest.queued + replaceRequest.exists
        val density = obtainDensity(context)
        val padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view)
        val linearLayout = LinearLayout(context)
        linearLayout.setOrientation(LinearLayout.VERTICAL)
        linearLayout.setPadding(padding, padding, padding, (8f * density).toInt())
        val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
        textView.setText(
            context.getResources().getQuantityString(
                R.plurals.number_files_already_exist__sentence_format,
                count,
                count
            )
        )
        linearLayout.addView(textView)

        val radioGroup = RadioGroup(context)
        radioGroup.setOrientation(RadioGroup.VERTICAL)
        val options = intArrayOf(R.string.replace, R.string.keep_all, R.string.skip)
        val ids = intArrayOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)
        for (i in options.indices) {
            val radioButton = RadioButton(context)
            radioButton.setText(options[i])
            radioButton.setId(ids[i])
            radioGroup.addView(radioButton)
        }
        if (state.selectedId == 0) {
            state.selectedId = ids[0]
        }
        radioGroup.check(state.selectedId)
        radioGroup.setPadding(0, (12f * density).toInt(), 0, 0)
        radioGroup.setOnCheckedChangeListener(RadioGroup.OnCheckedChangeListener { g: RadioGroup?, id: Int ->
            state.selectedId = id
        })
        linearLayout.addView(radioGroup)

        val builder = AlertDialog.Builder(context)
            .setView(linearLayout)
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    when (radioGroup.getCheckedRadioButtonId()) {
                        android.R.id.button1 -> {
                            callback.resolve(replaceRequest, ReplaceRequest.Action.REPLACE)
                        }

                        android.R.id.button2 -> {
                            callback.resolve(replaceRequest, ReplaceRequest.Action.KEEP_ALL)
                        }

                        android.R.id.button3 -> {
                            callback.resolve(replaceRequest, ReplaceRequest.Action.SKIP)
                        }
                    }
                })
            .setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    callback.resolve(
                        replaceRequest,
                        null
                    )
                })
            .setOnCancelListener(DialogInterface.OnCancelListener { d: DialogInterface? ->
                callback.resolve(
                    replaceRequest,
                    null
                )
            })

        val dialog: AlertDialog
        if (replaceRequest.exists == 1) {
            builder.setNeutralButton(R.string.view__verb, null)
            dialog = builder.create()
            val singleFile = replaceRequest.lastExistingFile
            dialog.setOnShowListener(OnShowListener { d: DialogInterface? ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    View.OnClickListener { v: View? ->
                        val extension = getFileExtension(singleFile!!.getName())
                        val type = forExtension(extension, "image/jpeg")
                        val fileOrUri = singleFile.getFileOrUri()
                        val uri: Uri?
                        if (fileOrUri.first != null) {
                            uri = FileProvider.convertDownloadsLegacyFile(fileOrUri.first!!, type)
                        } else if (fileOrUri.second != null) {
                            uri = fileOrUri.second
                        } else {
                            uri = null
                        }
                        if (uri != null) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            } catch (e: ActivityNotFoundException) {
                                show(R.string.unknown_address)
                            }
                        }
                    })
            })
        } else {
            dialog = builder.create()
        }
        dialog.setOnDismissListener(onDismissListener)
        dialog.show()
        return dialog
    }

    private fun createPrepare(
        prepareRequest: PrepareRequest?,
        onDismissListener: DialogInterface.OnDismissListener?
    ): ProgressDialog {
        val dialog = ProgressDialog(context, null)
        dialog.setMessage(context.getString(R.string.processing_data__ellipsis))
        dialog.setButton(
            DialogInterface.BUTTON_NEGATIVE, context.getString(android.R.string.cancel),
            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                callback.cancel(
                    prepareRequest!!
                )
            })
        dialog.setOnCancelListener(DialogInterface.OnCancelListener { d: DialogInterface? ->
            callback.cancel(
                prepareRequest!!
            )
        })
        dialog.setOnDismissListener(onDismissListener)
        dialog.show()
        return dialog
    }

    private class Adapter(private val root: DataFile, private val refresh: Runnable) :
        BaseAdapter(), Filterable {
        internal var items: MutableList<DialogDirectory> = mutableListOf<DialogDirectory>()

        override fun getCount(): Int {
            return items.size
        }

        override fun getItem(position: Int): DialogDirectory {
            return items.get(position)
        }

        override fun getItemId(position: Int): Long {
            return 0L
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var convertView = convertView
            val dialogDirectory = getItem(position)
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(
                    android.R.layout
                        .simple_spinner_dropdown_item, parent, false
                )
                (convertView as TextView).setEllipsize(TextUtils.TruncateAt.START)
            }
            (convertView as TextView).setText(dialogDirectory.displayName)
            return convertView
        }

        private val lastDirectoryLock = Any()
        private var lastDirectoryCancel = false
        private var lastDirectoryPath: String? = null
        private val cachedDirectories = HashMap<String?, DataFile?>()
        private var lastDirectoryItems: MutableList<DialogDirectory>? = null
        private var lastDirectoryTask: ExecutorTask<*, *>? = null

        private val filter: Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence): FilterResults {
                val constraintString = constraint.toString()
                val separatorIndex = constraintString.lastIndexOf('/')
                val enterDirectoryPath =
                    if (separatorIndex >= 0) constraintString.substring(0, separatorIndex) else ""
                val segments: MutableList<String?> = ArrayList<String?>()
                for (segment in enterDirectoryPath.split("/".toRegex())
                    .dropLastWhile { it.isEmpty() }.toTypedArray()) {
                    if (isValidSegment(segment)) {
                        segments.add(segment)
                    }
                }

                val items: MutableList<DialogDirectory>
                synchronized(lastDirectoryLock) {
                    val directoryPath: String = buildPath(segments, 0)
                    if (lastDirectoryPath == null || !equals(lastDirectoryPath, directoryPath)) {
                        lastDirectoryPath = directoryPath
                        lastDirectoryItems = mutableListOf<DialogDirectory>()

                        if (lastDirectoryTask != null) {
                            lastDirectoryTask!!.cancel()
                            lastDirectoryTask = null
                        }

                        if (!lastDirectoryCancel) {
                            var cachedDirectory: Pair<DataFile?, String?>? = null
                            // Optimize deep traversal
                            for (i in segments.indices) {
                                val path: String = buildPath(segments, i)
                                val directory = cachedDirectories.get(path)
                                if (directory != null) {
                                    cachedDirectory = Pair<DataFile?, String?>(
                                        directory, if (i == 0)
                                            ""
                                        else
                                            directoryPath.substring(path.length + 1)
                                    )
                                }
                            }
                            val cachedDirectoryFinal = cachedDirectory
                            lastDirectoryTask = object :
                                ExecutorTask<Void?, Pair<MutableList<DataFile>, MutableList<DialogDirectory>>>() {
                                override fun run(): Pair<MutableList<DataFile>, MutableList<DialogDirectory>> {
                                    val directory = if (cachedDirectoryFinal != null)
                                        cachedDirectoryFinal.first!!.getChild(cachedDirectoryFinal.second)
                                    else
                                        if (isEmpty(directoryPath)) root else root.getChild(
                                            directoryPath
                                        )
                                    val cachedFiles = ArrayList<DataFile>()
                                    val items: ArrayList<DialogDirectory> =
                                        ArrayList<DialogDirectory>()
                                    val files = directory.getChildren()
                                    if (files != null) {
                                        for (file in files) {
                                            if (isCancelled()) {
                                                break
                                            }
                                            if (file.isDirectory()) {
                                                val childSegments: MutableList<String?> =
                                                    ArrayList<String?>(segments)
                                                childSegments.add(file.getName())
                                                items.add(
                                                    DialogDirectory(
                                                        childSegments,
                                                        file.getLastModified()
                                                    )
                                                )
                                                cachedFiles.add(file)
                                            }
                                        }
                                    }
                                    if (!isCancelled()) {
                                        items.sort()
                                    }
                                    return Pair(cachedFiles, items)
                                }

                                override fun onComplete(result: Pair<MutableList<DataFile>, MutableList<DialogDirectory>>) {
                                    val items = result
                                    synchronized(lastDirectoryLock) {
                                        if (items.first != null) {
                                            for (file in items.first) {
                                                cachedDirectories.put(file.getRelativePath(), file)
                                            }
                                        }
                                        lastDirectoryItems = items.second
                                        lastDirectoryTask = null
                                        notifyDataSetChanged()
                                        refresh.run()
                                    }
                                }
                            }
                            lastDirectoryTask!!.execute(EXECUTOR)
                        }
                    }
                    items = lastDirectoryItems!!
                }

                val name = constraintString.substring(separatorIndex + 1)
                val result: ArrayList<DialogDirectory?> = ArrayList<DialogDirectory?>()
                for (item in items) {
                    if (item.filter(name)) {
                        result.add(item)
                    }
                }

                val results = FilterResults()
                results.values = result
                results.count = result.size
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                val items: ArrayList<DialogDirectory> = results.values as ArrayList<DialogDirectory>
                this@Adapter.items = items
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter {
            return filter
        }

        fun shutdown() {
            synchronized(lastDirectoryLock) {
                lastDirectoryCancel = true
                if (lastDirectoryTask != null) {
                    lastDirectoryTask!!.cancel()
                    lastDirectoryTask = null
                }
            }
        }

        companion object {
            fun buildPath(segments: MutableList<String?>, parent: Int): String {
                val directoryPathBuilder = StringBuilder()
                for (i in 0..<segments.size - parent) {
                    if (directoryPathBuilder.length > 0) {
                        directoryPathBuilder.append('/')
                    }
                    directoryPathBuilder.append(segments.get(i))
                }
                return directoryPathBuilder.toString()
            }
        }
    }

    private class DialogDirectory(val segments: MutableList<String?>, val lastModified: Long) :
        Comparable<DialogDirectory> {
        fun filter(name: String): Boolean {
            var name = name
            val locale = Locale.getDefault()
            name = name.lowercase(locale)
            val lastSegment = segments.get(segments.size - 1)!!.lowercase(locale)
            if (lastSegment.startsWith(name)) {
                return true
            }
            val splitted =
                lastSegment.split("[\\W_]+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (part in splitted) {
                if (part.startsWith(name)) {
                    return true
                }
            }
            return false
        }

        fun convert(displayName: Boolean): String {
            val builder = StringBuilder()
            for (segment in segments) {
                if (builder.length > 0) {
                    if (displayName) {
                        builder.append(" / ")
                    } else {
                        builder.append('/')
                    }
                }
                builder.append(segment)
            }
            if (!displayName) {
                builder.append('/')
            }
            return builder.toString()
        }

        val displayName: String
            get() = convert(true)

        override fun toString(): String {
            return convert(false)
        }

        override fun compareTo(other: DialogDirectory): Int {
            return other.lastModified.compareTo(lastModified)
        }
    }

    companion object {
        private val EXECUTOR: Executor = newSingleThreadPool(2000, "DownloadDialog", null)
    }
}
