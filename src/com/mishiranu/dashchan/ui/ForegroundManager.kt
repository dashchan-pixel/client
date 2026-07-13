package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.ChanConfiguration
import chan.content.ChanPerformer.CaptchaData
import chan.http.HttpException
import chan.util.CommonUtils.equals
import chan.util.DataFile
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.getCaptchaPass
import com.mishiranu.dashchan.content.Preferences.isCaptchaAutoReload
import com.mishiranu.dashchan.content.async.ReadCaptchaTask
import com.mishiranu.dashchan.content.async.ReadCaptchaTask.CaptchaReader
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
import com.mishiranu.dashchan.content.net.RecaptchaReader.ChallengeExtra
import com.mishiranu.dashchan.content.net.RecaptchaReader.V2Dialog
import com.mishiranu.dashchan.content.net.firewall.FirewallResolutionDialog
import com.mishiranu.dashchan.content.net.firewall.FirewallResolutionDialogRequest
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.graphics.SelectorCheckDrawable
import com.mishiranu.dashchan.ui.CaptchaOptionsDialog.CaptchaImageDownloadParameters
import com.mishiranu.dashchan.ui.ForegroundManager.PendingDataDialog.StoreResultCallback
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.GraphicsUtils.isLight
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getDialogBackground
import com.mishiranu.dashchan.util.ResourceUtils.obtainAlertDialogLayoutResId
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.makeRoundedCorners
import com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Objects
import java.util.UUID
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.indices
import kotlin.math.min

class ForegroundManager private constructor() : Handler.Callback {
    private class DelayedMessage(val what: Int, val handlerData: HandlerData)

    private val handler = Handler(Looper.getMainLooper(), this)
    private val pendingDataMap: HashMap<String?, PendingData?> = HashMap<String?, PendingData?>()
    private val delayedMessages: ArrayList<DelayedMessage> = ArrayList()

    private var activity: WeakReference<FragmentActivity?>? = null
    private var viewModel: WeakReference<InstanceViewModel?>? = null

    private fun getActivity(): FragmentActivity? {
        val activity = if (this.activity != null) this.activity!!.get() else null
        return if (activity == null || (activity.lifecycle.currentState
                    == Lifecycle.State.DESTROYED)
        ) null else activity
    }

    private fun getPendingData(pendingDataId: String?): PendingData? {
        synchronized(pendingDataMap) {
            return pendingDataMap.get(pendingDataId)
        }
    }

    private val lifecycleObserver: LifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleStartResume(owner)
        }

        override fun onResume(owner: LifecycleOwner) {
            handleStartResume(owner)
        }

        private fun handleStartResume(owner: LifecycleOwner?) {
            val activity = getActivity()
            if (activity != null && activity === owner) {
                handleActivityResumeChecked(activity)
            }
        }
    }

    class InstanceViewModel : ViewModel() {
        override fun onCleared() {
            getInstance().handleCleared(this)
        }
    }

    private fun handleActivityResumeChecked(activity: FragmentActivity) {
        val fragmentManager = activity.getSupportFragmentManager()
        if (!fragmentManager.isStateSaved()) {
            for (fragment in fragmentManager.getFragments()) {
                if (fragment is PendingDataDialog<*>) {
                    val dialog: PendingDataDialog<*> = fragment as PendingDataDialog<*>
                    dialog.pendingDataOrDismiss
                }
            }
            if (!delayedMessages.isEmpty()) {
                val delayedMessages: ArrayList<DelayedMessage> =
                    ArrayList<DelayedMessage>(this.delayedMessages)
                this.delayedMessages.clear()
                for (delayedMessage in delayedMessages) {
                    handleMessage(
                        handler.obtainMessage(
                            delayedMessage.what,
                            delayedMessage.handlerData
                        )
                    )
                }
            }
        }
    }

    private fun handleCleared(viewModel: InstanceViewModel?) {
        Objects.requireNonNull<InstanceViewModel?>(viewModel)
        if (this.viewModel != null && this.viewModel!!.get() === viewModel) {
            this.viewModel = null
            if (!delayedMessages.isEmpty()) {
                val delayedMessages: ArrayList<DelayedMessage> =
                    ArrayList<DelayedMessage>(this.delayedMessages)
                this.delayedMessages.clear()
                for (delayedMessage in delayedMessages) {
                    val pendingData = getPendingData(delayedMessage.handlerData.pendingDataId)
                    if (pendingData != null) {
                        synchronized(pendingData) {
                            pendingData.ready = true
                            (pendingData as Object).notifyAll()
                        }
                    }
                }
            }
        }
    }

    private interface PendingDataDialog<T : PendingData?> : LifecycleObserver {
        fun interface StoreResultCallback<T> {
            fun onStoreResult(pendingData: T?)
        }

        fun show(manager: FragmentManager, tag: String?)
        fun requireArguments(): Bundle
        fun requireFragmentManager(): FragmentManager
        fun dismiss()

        fun fillArguments(args: Bundle, pendingDataId: String?) {
            args.putString(EXTRA_PENDING_DATA_ID, pendingDataId)
        }

        val pendingDataId: String?
            get() = requireArguments().getString(EXTRA_PENDING_DATA_ID)

        val pendingData: T?
            get() {
                val result =
                    getInstance().getPendingData(this.pendingDataId) as T?
                return result
            }

        val pendingDataOrDismiss: T?
            get() {
                val pendingData = this.pendingData
                if (pendingData == null && !requireFragmentManager().isStateSaved()) {
                    dismiss()
                }
                return pendingData
            }

        fun show(activity: FragmentActivity) {
            show(activity.getSupportFragmentManager(), this.pendingDataId)
        }

        fun notifyResult(callback: StoreResultCallback<T?>?) {
            val pendingData = this.pendingData
            if (pendingData != null) {
                synchronized(pendingData) {
                    if (callback != null) {
                        callback.onStoreResult(pendingData)
                    }
                    pendingData.ready = true
                    (pendingData as Object).notifyAll()
                }
            }
        }

        companion object {
            const val EXTRA_PENDING_DATA_ID: String = "pendingDataId"
        }
    }

    class CaptchaDialog : DialogFragment, PendingDataDialog<CaptchaPendingData?>,
        CaptchaForm.Callback, ReadCaptchaTask.Callback, CaptchaOptionsDialog.Callback {
        private var captchaState: ReadCaptchaTask.CaptchaState? = null
        private var loadedInput: ChanConfiguration.Captcha.Input? = null
        private var captcha: CaptchaForm.Captcha? = null
        private var captchaLifetimeSeconds = 0
        private var large = false
        private var blackAndWhite = false
        private var refreshCaptchaWhenLifetimeEnd = false


        private var captchaForm: CaptchaForm? = null

        private var positiveButton: Button? = null

        constructor()

        constructor(
            pendingDataId: String?, chanName: String?, captchaType: String?, requirement: String?,
            boardName: String?, threadNumber: String?, description: String?
        ) {
            val args = Bundle()
            fillArguments(args, pendingDataId)
            args.putString(EXTRA_CHAN_NAME, chanName)
            args.putString(EXTRA_CAPTCHA_TYPE, captchaType)
            args.putString(EXTRA_REQUIREMENT, requirement)
            args.putString(EXTRA_BOARD_NAME, boardName)
            args.putString(EXTRA_THREAD_NUMBER, threadNumber)
            args.putString(EXTRA_DESCRIPTION, description)
            setArguments(args)
        }

        // View-less DialogFragment: onViewStateRestored never runs, so initialize
        // once per instance from onStart (the point onActivityCreated used to run).
        private var savedInstanceState: Bundle? = null
        private var dialogInitialized = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            this.savedInstanceState = savedInstanceState
        }

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
            val pendingData = this.pendingDataOrDismiss
            if (pendingData == null) {
                return
            }
            var needLoad = true
            if (pendingData.captchaData != null && savedInstanceState != null) {
                val captchaStateString = savedInstanceState.getString(EXTRA_CAPTCHA_STATE)
                val captchaState = if (captchaStateString != null)
                    ReadCaptchaTask.CaptchaState.valueOf(captchaStateString)
                else
                    null
                val captcha = BundleCompat.getParcelable<CaptchaForm.Captcha?>(
                    savedInstanceState,
                    EXTRA_CAPTCHA,
                    CaptchaForm.Captcha::class.java
                )
                if (captchaState != null) {
                    val loadedInputString = savedInstanceState.getString(EXTRA_LOADED_INPUT)
                    val loadedInput = if (loadedInputString != null)
                        ChanConfiguration.Captcha.Input.valueOf(loadedInputString)
                    else
                        null
                    showCaptcha(
                        captchaState, pendingData.loadedCaptchaType, loadedInput, captcha,
                        savedInstanceState.getBoolean(EXTRA_LARGE),
                        savedInstanceState.getBoolean(EXTRA_BLACK_AND_WHITE)
                    )
                    needLoad = false
                }
            }
            if (needLoad) {
                reloadCaptcha(pendingData, false, true, false)
            }

            val viewModel =
                ViewModelProvider(this).get<CaptchaViewModel>(CaptchaViewModel::class.java)
            viewModel.observe(this, this)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putString(
                EXTRA_CAPTCHA_STATE,
                if (captchaState != null) captchaState!!.name else null
            )
            outState.putString(
                EXTRA_LOADED_INPUT,
                if (loadedInput != null) loadedInput!!.name else null
            )
            outState.putParcelable(EXTRA_CAPTCHA, captcha)
            outState.putBoolean(EXTRA_LARGE, large)
            outState.putBoolean(EXTRA_BLACK_AND_WHITE, blackAndWhite)
        }

        private fun reloadCaptcha(
            pendingData: CaptchaPendingData,
            forceCaptcha: Boolean, mayShowLoadButton: Boolean, restart: Boolean
        ) {
            pendingData.captchaData = null
            pendingData.loadedCaptchaType = null
            val allowSolveAutomatically = !forceCaptcha ||
                    captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING
            captchaState = null
            captcha = null
            large = false
            blackAndWhite = false
            updatePositiveButtonState()
            captchaForm!!.showLoading()
            val viewModel =
                ViewModelProvider(this).get<CaptchaViewModel>(CaptchaViewModel::class.java)
            if (restart || !viewModel.hasTaskOrValue()) {
                val args = requireArguments()
                val chan = get(args.getString(EXTRA_CHAN_NAME))
                val captchaPass =
                    if (forceCaptcha || chan.name == null) null else getCaptchaPass(chan)
                val task = ReadCaptchaTask(
                    viewModel.callback!!,
                    pendingData.captchaReader,
                    args.getString(EXTRA_CAPTCHA_TYPE),
                    args.getString(EXTRA_REQUIREMENT),
                    captchaPass,
                    mayShowLoadButton,
                    allowSolveAutomatically,
                    chan,
                    args.getString(EXTRA_BOARD_NAME),
                    args.getString(EXTRA_THREAD_NUMBER)
                )
                task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                viewModel.attach(task)
            }
        }

        class CaptchaViewModel : TaskViewModel.Proxy<ReadCaptchaTask, ReadCaptchaTask.Callback?>()

        override fun onReadCaptchaSuccess(result: ReadCaptchaTask.Result) {
            val pendingData = this.pendingDataOrDismiss
            if (pendingData == null) {
                return
            }
            pendingData.captchaData =
                if (result.captchaData != null) result.captchaData else CaptchaData()
            pendingData.loadedCaptchaType = result.captchaType
            showCaptcha(
                result.captchaState!!,
                result.captchaType,
                result.input,
                CaptchaForm.Captcha(result.image, captchaLifetimeSeconds),
                result.large,
                result.blackAndWhite
            )
            if (result.captchaState == ReadCaptchaTask.CaptchaState.SKIP) {
                onConfirmCaptcha()
            }
        }

        override fun onReadCaptchaError(errorItem: ErrorItem) {
            if (this.pendingDataOrDismiss != null) {
                show(errorItem)
                captchaForm!!.showError()
            }
        }

        private fun showCaptcha(
            captchaState: ReadCaptchaTask.CaptchaState,
            captchaType: String?,
            input: ChanConfiguration.Captcha.Input?,
            captcha: CaptchaForm.Captcha?,
            large: Boolean,
            blackAndWhite: Boolean
        ) {
            var input = input
            this.captchaState = captchaState
            if (captchaType != null && input == null) {
                val chan = get(requireArguments().getString(EXTRA_CHAN_NAME))
                input = chan.configuration.safe().obtainCaptcha(captchaType).input
            }
            loadedInput = input
            this.captcha = captcha
            this.large = large
            this.blackAndWhite = blackAndWhite
            val invertColors = blackAndWhite && !isLight(getDialogBackground(requireContext()))
            captchaForm!!.showCaptcha(captchaState, input, captcha, large, invertColors)
            updatePositiveButtonState()
        }

        private fun updatePositiveButtonState() {
            if (positiveButton != null) {
                positiveButton!!.setEnabled(captchaState != null && captchaState != ReadCaptchaTask.CaptchaState.NEED_LOAD && captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD && captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING)
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val args = requireArguments()
            val container =
                LayoutInflater.from(requireContext()).inflate(R.layout.dialog_captcha, null)
            val comment = container.findViewById<TextView>(R.id.comment)
            val description = args.getString(EXTRA_DESCRIPTION)
            if (!isEmpty(description)) {
                comment.setText(description)
            } else {
                comment.setVisibility(View.GONE)
            }
            val chan = get(args.getString(EXTRA_CHAN_NAME))
            val captchaConfiguration = chan.configuration
                .safe().obtainCaptcha(args.getString(EXTRA_CAPTCHA_TYPE))
            captchaLifetimeSeconds = captchaConfiguration.ttl
            refreshCaptchaWhenLifetimeEnd = isCaptchaAutoReload
            val captchaInputView = container.findViewById<EditText?>(R.id.captcha_input)
            captchaForm = CaptchaForm(
                this,
                false,
                true,
                container,
                null,
                captchaInputView,
                captchaConfiguration
            )
            val alertDialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirmation).setView(container)
                .setPositiveButton(
                    android.R.string.ok,
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> confirmCaptchaInternal() })
                .setNegativeButton(
                    android.R.string.cancel,
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> cancelInternal() })
                .create()
            alertDialog.setCanceledOnTouchOutside(false)
            alertDialog.setOnShowListener(OnShowListener { dialog: DialogInterface? ->
                positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                updatePositiveButtonState()
            })
            return alertDialog
        }

        override fun onDestroyView() {
            super.onDestroyView()
            captchaForm!!.onDestroyView()
            captchaForm = null
        }

        override fun onRefreshCaptcha(forceRefresh: Boolean) {
            val pendingData = this.pendingDataOrDismiss
            if (pendingData == null) {
                return
            }
            reloadCaptcha(pendingData, forceRefresh, false, true)
        }

        override fun onConfirmCaptcha() {
            dismiss()
            confirmCaptchaInternal()
        }

        override fun onCaptchaLifetimeEnded() {
            if (refreshCaptchaWhenLifetimeEnd) {
                onRefreshCaptcha(false)
            } else {
                showCaptcha(ReadCaptchaTask.CaptchaState.NEED_LOAD, null, null, null, false, false)
            }
        }

        override fun showCaptchaOptionsDialog(dialog: CaptchaOptionsDialog) {
            dialog.show(getChildFragmentManager(), null)
        }

        override fun attachCaptchaImageToPost(captchaImageAttachmentDataFile: DataFile) {
            val captchaImageHolder = obtain(captchaImageAttachmentDataFile)
            if (captchaImageHolder != null) {
                DraftsStorage.getInstance().storeFuture(captchaImageHolder)
                show(R.string.draft_saved)
            }
            captchaImageAttachmentDataFile.delete()
        }

        override fun getCaptchaImageDownloadParameters(): CaptchaImageDownloadParameters {
            val args = requireArguments()
            val chanName = args.getString(EXTRA_CHAN_NAME)
            val boardName = args.getString(EXTRA_BOARD_NAME)
            val threadNumber = args.getString(EXTRA_THREAD_NUMBER)
            return CaptchaImageDownloadParameters(chanName, boardName, threadNumber)
        }

        override fun refreshCaptcha() {
            onRefreshCaptcha(true)
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            cancelInternal()
        }

        private fun confirmCaptchaInternal() {
            notifyResult(StoreResultCallback { pendingData: CaptchaPendingData? ->
                if (pendingData!!.captchaData != null) {
                    pendingData.captchaData!!.put(CaptchaData.INPUT, captchaForm!!.input)
                }
            })
        }

        private fun cancelInternal() {
            notifyResult(StoreResultCallback { pendingData: CaptchaPendingData? ->
                pendingData!!.captchaData = null
                pendingData.loadedCaptchaType = null
            })
        }

        companion object {
            private const val EXTRA_CHAN_NAME = "chanName"
            private const val EXTRA_CAPTCHA_TYPE = "captchaType"
            private const val EXTRA_REQUIREMENT = "requirement"
            private const val EXTRA_BOARD_NAME = "boardName"
            private const val EXTRA_THREAD_NUMBER = "threadNumber"
            private const val EXTRA_DESCRIPTION = "description"

            private const val EXTRA_CAPTCHA_STATE = "captchaState"
            private const val EXTRA_LOADED_INPUT = "loadedInput"
            private const val EXTRA_CAPTCHA = "captcha"
            private const val EXTRA_LARGE = "large"
            private const val EXTRA_BLACK_AND_WHITE = "blackAndWhite"
        }
    }

    private class ItemsAdapter(
        context: Context,
        resource: Int,
        items: ArrayList<CharSequence?>,
        private val header: View?
    ) : ArrayAdapter<CharSequence?>(context, resource, android.R.id.text1, items) {
        override fun getItemViewType(position: Int): Int {
            if (header != null && position == 0) {
                return ListView.ITEM_VIEW_TYPE_IGNORE
            }
            return super.getItemViewType(position)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (header != null && position == 0) {
                return header
            }
            return super.getView(position, convertView, parent)
        }

        override fun areAllItemsEnabled(): Boolean {
            return false
        }

        override fun isEnabled(position: Int): Boolean {
            return header == null || position != 0
        }
    }

    class ItemChoiceDialog : DialogFragment, PendingDataDialog<ChoicePendingData?>,
        DialogInterface.OnClickListener, OnItemClickListener {
        private lateinit var selected: BooleanArray
        private var hasImage = false

        constructor()

        constructor(
            pendingDataId: String?, selected: BooleanArray?, items: Array<CharSequence?>?,
            descriptionText: String?, descriptionImage: Bitmap?, multiple: Boolean
        ) {
            val args = Bundle()
            fillArguments(args, pendingDataId)
            args.putBooleanArray(EXTRA_SELECTED, selected)
            args.putCharSequenceArray(EXTRA_ITEMS, items)
            args.putString(EXTRA_DESCRIPTION_TEXT, descriptionText)
            args.putParcelable(EXTRA_DESCRIPTION_IMAGE, descriptionImage)
            args.putBoolean(EXTRA_MULTIPLE, multiple)
            setArguments(args)
        }

        // View-less DialogFragment: onViewStateRestored never runs, so check from onStart.
        private var dialogInitialized = false

        override fun onStart() {
            if (!dialogInitialized) {
                dialogInitialized = true
                this.pendingDataOrDismiss
            }
            super.onStart()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBooleanArray(EXTRA_SELECTED, selected)
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            var items = requireArguments().getCharSequenceArray(EXTRA_ITEMS)
            if (items == null) {
                items = arrayOfNulls<CharSequence>(0)
            }
            val descriptionText = requireArguments().getString(EXTRA_DESCRIPTION_TEXT)
            val descriptionImage = BundleCompat.getParcelable<Bitmap?>(
                requireArguments(),
                EXTRA_DESCRIPTION_IMAGE,
                Bitmap::class.java
            )
            val multiple = requireArguments().getBoolean(EXTRA_MULTIPLE)
            selected = BooleanArray(items.size)
            var selected = if (savedInstanceState != null) savedInstanceState.getBooleanArray(
                EXTRA_SELECTED
            ) else null
            if (selected == null) {
                selected = requireArguments().getBooleanArray(EXTRA_SELECTED)
            }
            if (selected != null && selected.size == this.selected.size) {
                System.arraycopy(selected, 0, this.selected, 0, selected.size)
            }

            var imageLayout: FrameLayout? = null
            if (descriptionImage != null) {
                imageLayout = FrameLayout(requireContext())
                val imageView: ImageView = appendDescriptionImageView(imageLayout, descriptionImage)
                val outerPadding =
                    imageLayout.getResources().getDimensionPixelOffset(R.dimen.dialog_padding_text)
                (imageView.getLayoutParams() as FrameLayout.LayoutParams).setMargins(
                    0, outerPadding, outerPadding,
                    outerPadding / 2
                )
            }
            val itemsList = ArrayList<CharSequence?>()
            if (imageLayout != null) {
                itemsList.add(null)
            }
            Collections.addAll<CharSequence?>(itemsList, *items)
            val resId = obtainAlertDialogLayoutResId(
                requireContext(), if (multiple)
                    ResourceUtils.DialogLayout.MULTI_CHOICE
                else
                    ResourceUtils.DialogLayout.SINGLE_CHOICE
            )
            val adapter = ItemsAdapter(requireContext(), resId, itemsList, imageLayout)
            val alertDialog = AlertDialog.Builder(requireContext())
                .setTitle(descriptionText)
                .setAdapter(adapter, null)
                .setPositiveButton(android.R.string.ok, this)
                .setNegativeButton(android.R.string.cancel, this)
                .create()
            val listView = alertDialog.getListView()
            listView.setOnItemClickListener(this)
            listView.setChoiceMode(if (multiple) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE)
            var i = 0
            var j = if (imageLayout == null) 0 else 1
            while (i < this.selected.size) {
                listView.setItemChecked(j, this.selected[i])
                i++
                j++
            }
            hasImage = imageLayout != null
            return alertDialog
        }

        override fun onItemClick(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (hasImage && position == 0) {
                return
            }
            val arrayPosition = if (hasImage) position - 1 else position
            if (requireArguments().getBoolean(EXTRA_MULTIPLE)) {
                selected[arrayPosition] = !selected[arrayPosition]
                (parent as ListView).setItemChecked(position, selected[arrayPosition])
            } else {
                for (i in selected.indices) {
                    selected[i] = i == arrayPosition
                }
                dismiss()
                publishResult(true)
            }
        }

        override fun onClick(dialog: DialogInterface?, which: Int) {
            publishResult(which == AlertDialog.BUTTON_POSITIVE)
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            publishResult(false)
        }

        private fun publishResult(success: Boolean) {
            notifyResult(StoreResultCallback { pendingData: ChoicePendingData? ->
                pendingData!!.result = if (success) selected else null
            })
        }

        companion object {
            private const val EXTRA_SELECTED = "selected"
            private const val EXTRA_ITEMS = "items"
            private const val EXTRA_DESCRIPTION_TEXT = "descriptionText"
            private const val EXTRA_DESCRIPTION_IMAGE = "descriptionImage"
            private const val EXTRA_MULTIPLE = "multiple"
        }
    }

    class ImageChoiceDialog : DialogFragment, PendingDataDialog<ChoicePendingData?>,
        View.OnClickListener, DialogInterface.OnClickListener {
        private var selectionViews: Array<FrameLayout?>? = null
        private lateinit var selected: BooleanArray

        constructor()

        constructor(
            pendingDataId: String?, columns: Int, selected: BooleanArray?, images: Array<Bitmap?>?,
            descriptionText: String?, descriptionImage: Bitmap?, multiple: Boolean
        ) {
            val args = Bundle()
            fillArguments(args, pendingDataId)
            args.putInt(EXTRA_COLUMNS, columns)
            args.putBooleanArray(EXTRA_SELECTED, selected)
            args.putParcelableArray(EXTRA_IMAGES, images)
            args.putString(EXTRA_DESCRIPTION_TEXT, descriptionText)
            args.putParcelable(EXTRA_DESCRIPTION_IMAGE, descriptionImage)
            args.putBoolean(EXTRA_MULTIPLE, multiple)
            setArguments(args)
        }

        private fun ensureArrays() {
            if (selectionViews == null) {
                val columns = requireArguments().getInt(EXTRA_COLUMNS)
                val parcelables = BundleCompat.getParcelableArray(
                    requireArguments(),
                    EXTRA_IMAGES,
                    Bitmap::class.java
                )
                val count = if (parcelables != null) parcelables.size else 0
                selectionViews =
                    arrayOfNulls<FrameLayout>((count + columns - 1) / columns * columns)
                selected = BooleanArray(count)
            }
        }

        // View-less DialogFragment: onViewStateRestored never runs, so initialize
        // once per instance from onStart (the point onActivityCreated used to run).
        private var savedInstanceState: Bundle? = null
        private var dialogInitialized = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            this.savedInstanceState = savedInstanceState
        }

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
            val pendingData: PendingData? = this.pendingDataOrDismiss
            if (pendingData == null) {
                return
            }
            ensureArrays()
            var selected = if (savedInstanceState != null) savedInstanceState.getBooleanArray(
                EXTRA_SELECTED
            ) else null
            if (selected == null) {
                selected = requireArguments().getBooleanArray(EXTRA_SELECTED)
            }
            if (selected != null && selected.size == this.selected.size) {
                System.arraycopy(selected, 0, this.selected, 0, selected.size)
            }
            for (i in this.selected.indices) {
                updateSelection(i)
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBooleanArray(EXTRA_SELECTED, selected)
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val density = obtainDensity(requireContext())
            val container = LinearLayout(requireContext())
            container.setOrientation(LinearLayout.VERTICAL)
            val parcelables = BundleCompat.getParcelableArray(
                requireArguments(),
                EXTRA_IMAGES,
                Bitmap::class.java
            )
            val images = arrayOfNulls<Bitmap>(if (parcelables != null) parcelables.size else 0)
            if (images.size > 0) {
                // noinspection SuspiciousSystemArraycopy
                System.arraycopy(parcelables, 0, images, 0, images.size)
            }
            val descriptionText = requireArguments().getString(EXTRA_DESCRIPTION_TEXT)
            val descriptionImage = BundleCompat.getParcelable<Bitmap?>(
                requireArguments(),
                EXTRA_DESCRIPTION_IMAGE,
                Bitmap::class.java
            )
            val outerPadding =
                container.getResources().getDimensionPixelOffset(R.dimen.dialog_padding_text)
            container.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            val cornersRadius = (2f * density).toInt()
            if (descriptionImage != null) {
                val imageView: ImageView = appendDescriptionImageView(container, descriptionImage)
                (imageView.getLayoutParams() as LinearLayout.LayoutParams).setMargins(
                    0,
                    0,
                    0,
                    (20f * density).toInt()
                )
            }
            val innerPadding = (8f * density).toInt()
            val columns = requireArguments().getInt(EXTRA_COLUMNS)
            val rows = (images.size + columns - 1) / columns
            ensureArrays()
            for (i in 0..<rows) {
                val inner = LinearLayout(container.getContext())
                inner.setOrientation(LinearLayout.HORIZONTAL)
                container.addView(
                    inner, LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                for (j in 0..<columns) {
                    val index = columns * i + j
                    val frameLayout = FrameLayout(inner.getContext())
                    selectionViews!![index] = frameLayout
                    val layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    if (j < columns - 1) {
                        layoutParams.rightMargin = innerPadding
                    }
                    if (i < rows - 1) {
                        layoutParams.bottomMargin = innerPadding
                    }
                    inner.addView(frameLayout, layoutParams)
                    if (index < images.size) {
                        val imageView: ImageView = object : ImageView(frameLayout.getContext()) {
                            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                                setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth())
                            }
                        }
                        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
                        imageView.setImageBitmap(images[index])
                        makeRoundedCorners(imageView, cornersRadius, false)
                        frameLayout.addView(
                            imageView, FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        val view = View(frameLayout.getContext())
                        setSelectableItemBackground(view)
                        view.setTag(index)
                        view.setOnClickListener(this)
                        frameLayout.addView(
                            view, FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        frameLayout.setForeground(SelectorCheckDrawable())
                    }
                }
            }

            val futureAlertDialog = arrayOf<AlertDialog?>(null)
            val scrollView: ScrollView = object : ScrollView(container.getContext()) {
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)

                    // Ensure at least count=columns rows can fit
                    val cellSize =
                        (b - t - (columns - 1) * innerPadding - 2 * outerPadding) / columns
                    var width = min(
                        (480 * density).toInt(),
                        columns * cellSize + (columns - 1) * innerPadding + 2 * outerPadding
                    )
                    if (r - l > width) {
                        var totalPadding = 0
                        var root: View = this
                        while (true) {
                            totalPadding += root.getPaddingLeft() + root.getPaddingRight()
                            val layoutParams = root.getLayoutParams()
                            if (layoutParams is MarginLayoutParams) {
                                val marginLayoutParams = layoutParams
                                totalPadding += marginLayoutParams.leftMargin + marginLayoutParams.rightMargin
                            }
                            val parent = root.getParent()
                            if (parent is View) {
                                root = parent as View
                            } else {
                                break
                            }
                        }
                        width += totalPadding
                        futureAlertDialog[0]!!.getWindow()!!
                            .setLayout(width, LayoutParams.WRAP_CONTENT)
                    }
                }
            }
            scrollView.addView(
                container,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val alertDialog = AlertDialog.Builder(requireContext())
                .setTitle(descriptionText).setView(scrollView)
                .setPositiveButton(android.R.string.ok, this)
                .setNegativeButton(android.R.string.cancel, this)
                .create()
            futureAlertDialog[0] = alertDialog
            return alertDialog
        }

        override fun onClick(v: View) {
            val index = v.getTag() as Int
            if (requireArguments().getBoolean(EXTRA_MULTIPLE)) {
                selected[index] = !selected[index]
                updateSelection(index)
            } else {
                for (i in selected.indices) {
                    selected[i] = i == index
                }
                dismiss()
                publishResult(true)
            }
        }

        private fun updateSelection(index: Int) {
            val drawable = selectionViews!![index]!!.getForeground()
            (drawable as SelectorCheckDrawable).setSelected(selected[index], true)
        }

        override fun onClick(dialog: DialogInterface?, which: Int) {
            publishResult(which == AlertDialog.BUTTON_POSITIVE)
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            publishResult(false)
        }

        private fun publishResult(success: Boolean) {
            notifyResult(StoreResultCallback { pendingData: ChoicePendingData? ->
                pendingData!!.result = if (success) selected else null
            })
        }

        companion object {
            private const val EXTRA_COLUMNS = "columns"
            private const val EXTRA_SELECTED = "selected"
            private const val EXTRA_IMAGES = "images"
            private const val EXTRA_DESCRIPTION_TEXT = "descriptionText"
            private const val EXTRA_DESCRIPTION_IMAGE = "descriptionImage"
            private const val EXTRA_MULTIPLE = "multiple"
        }
    }

    class RecaptchaV2Dialog : V2Dialog, PendingDataDialog<RecaptchaV2PendingData?> {
        constructor()

        constructor(
            pendingDataId: String?, referer: String?, apiKey: String?,
            invisible: Boolean, hcaptcha: Boolean, challengeExtra: ChallengeExtra?
        ) : super(referer, apiKey, invisible, hcaptcha, challengeExtra) {
            fillArguments(getArguments()!!, pendingDataId)
        }

        public override fun publishResult(response: String?, exception: HttpException?) {
            notifyResult(StoreResultCallback { pendingData: RecaptchaV2PendingData? ->
                pendingData!!.response = response
                pendingData.exception = exception
            })
        }
    }

    class FirewallResolutionDialogImpl<T> : FirewallResolutionDialog<T?>,
        PendingDataDialog<FirewallResolutionPendingData<T?>?> {
        constructor()

        constructor(pendingDataId: String?, request: FirewallResolutionDialogRequest<T?>) : super(
            request
        ) {
            fillArguments(getArguments()!!, pendingDataId)
        }

        override fun onFirewallResolutionFinished(firewallResolutionResult: T?) {
            notifyResult(StoreResultCallback { pendingData: FirewallResolutionPendingData<T?>? ->
                pendingData!!.result = firewallResolutionResult
            })
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MESSAGE_INTERRUPT -> {
                val handlerData: HandlerData = msg.obj as HandlerData
                val activity = getActivity()
                if (activity != null) {
                    val fragmentManager = activity.getSupportFragmentManager()
                    if (!fragmentManager.isStateSaved()) {
                        val fragment = fragmentManager
                            .findFragmentByTag(handlerData.pendingDataId) as DialogFragment?
                        if (fragment != null) {
                            fragment.dismiss()
                        }
                    }
                }
                return true
            }

            MESSAGE_REQUIRE_USER_CAPTCHA, MESSAGE_REQUIRE_USER_CHOICE, MESSAGE_REQUIRE_USER_RECAPTCHA_V2, MESSAGE_REQUIRE_USER_RESOLVE_FIREWALL -> {
                val handlerData: HandlerData = msg.obj as HandlerData
                val activity = getActivity()
                val pendingData = getPendingData(handlerData.pendingDataId)
                if (pendingData == null) {
                    return true
                }
                if (activity == null) {
                    synchronized(pendingData) {
                        pendingData.ready = true
                        (pendingData as Object).notifyAll()
                    }
                } else if (activity.getSupportFragmentManager().isStateSaved()) {
                    delayedMessages.add(DelayedMessage(msg.what, handlerData))
                } else {
                    when (msg.what) {
                        MESSAGE_REQUIRE_USER_CAPTCHA -> {
                            val captchaHandlerData: CaptchaHandlerData =
                                handlerData as CaptchaHandlerData
                            CaptchaDialog(
                                handlerData.pendingDataId, captchaHandlerData.chanName,
                                captchaHandlerData.captchaType, captchaHandlerData.requirement,
                                captchaHandlerData.boardName, captchaHandlerData.threadNumber,
                                captchaHandlerData.description
                            ).show(activity)
                        }

                        MESSAGE_REQUIRE_USER_CHOICE -> {
                            val choiceHandlerData: ChoiceHandlerData =
                                handlerData as ChoiceHandlerData
                            if (choiceHandlerData.images != null) {
                                ImageChoiceDialog(
                                    handlerData.pendingDataId,
                                    choiceHandlerData.columns,
                                    choiceHandlerData.selected,
                                    choiceHandlerData.images,
                                    choiceHandlerData.descriptionText,
                                    choiceHandlerData.descriptionImage,
                                    choiceHandlerData.multiple
                                ).show(activity)
                            } else {
                                ItemChoiceDialog(
                                    handlerData.pendingDataId, choiceHandlerData.selected,
                                    choiceHandlerData.items, choiceHandlerData.descriptionText,
                                    choiceHandlerData.descriptionImage, choiceHandlerData.multiple
                                ).show(activity)
                            }
                        }

                        MESSAGE_REQUIRE_USER_RECAPTCHA_V2 -> {
                            val recaptchaV2HandlerData: RecaptchaV2HandlerData =
                                handlerData as RecaptchaV2HandlerData
                            RecaptchaV2Dialog(
                                handlerData.pendingDataId,
                                recaptchaV2HandlerData.referer,
                                recaptchaV2HandlerData.apiKey,
                                recaptchaV2HandlerData.invisible,
                                recaptchaV2HandlerData.hcaptcha,
                                recaptchaV2HandlerData.challengeExtra
                            )
                                .show(activity)
                        }

                        MESSAGE_REQUIRE_USER_RESOLVE_FIREWALL -> {
                            val firewallHandlerData: FirewallHandlerData<*> =
                                handlerData as FirewallHandlerData<*>
                            @Suppress("UNCHECKED_CAST")
                            FirewallResolutionDialogImpl(
                                firewallHandlerData.pendingDataId,
                                firewallHandlerData.request as FirewallResolutionDialogRequest<Any?>
                            ).show(activity)
                        }
                    }
                }
                return true
            }

            MESSAGE_SHOW_CAPTCHA_INVALID -> {
                show(R.string.captcha_is_not_valid)
                return true
            }
        }
        return false
    }

    private abstract class HandlerData(val pendingDataId: String?)

    private class CaptchaHandlerData(
        pendingDataId: String?,
        val chanName: String?,
        val captchaType: String?,
        val requirement: String?,
        val boardName: String?,
        val threadNumber: String?,
        val description: String?
    ) : HandlerData(pendingDataId)

    private class ChoiceHandlerData(
        pendingDataId: String?,
        val columns: Int,
        val selected: BooleanArray?,
        val images: Array<Bitmap?>?,
        val items: Array<CharSequence?>?,
        val descriptionText: String?,
        val descriptionImage: Bitmap?,
        val multiple: Boolean
    ) : HandlerData(pendingDataId)

    private class RecaptchaV2HandlerData(
        pendingDataId: String?, val referer: String?, val apiKey: String?,
        val invisible: Boolean, val hcaptcha: Boolean, val challengeExtra: ChallengeExtra?
    ) : HandlerData(pendingDataId)

    private class FirewallHandlerData<T>(
        pendingDataId: String?,
        internal val request: FirewallResolutionDialogRequest<T>
    ) : HandlerData(pendingDataId)

    private abstract class PendingData {
        var ready: Boolean = false

        @Throws(InterruptedException::class)
        fun await(handler: Handler, handlerData: HandlerData?): Boolean {
            synchronized(this) {
                while (!ready) {
                    try {
                        (this as Object).wait()
                    } catch (e: InterruptedException) {
                        handler.obtainMessage(MESSAGE_INTERRUPT, handlerData).sendToTarget()
                        throw e
                    }
                }
            }
            return true
        }
    }

    private class CaptchaPendingData(val captchaReader: CaptchaReader?) : PendingData() {
        var captchaData: CaptchaData? = null
        var loadedCaptchaType: String? = null
    }

    private class ChoicePendingData : PendingData() {
        var result: BooleanArray? = null
    }

    private class RecaptchaV2PendingData : PendingData() {
        var response: String? = null
        var exception: HttpException? = null
    }

    private class FirewallResolutionPendingData<T> : PendingData() {
        var result: T? = null
    }

    private fun putPendingData(pendingData: PendingData?): String {
        val id = UUID.randomUUID().toString()
        synchronized(pendingDataMap) {
            pendingDataMap.put(id, pendingData)
        }
        return id
    }

    private fun removePendingData(id: String?) {
        synchronized(pendingDataMap) {
            pendingDataMap.remove(id)
        }
    }

    @Throws(InterruptedException::class)
    fun requireUserCaptcha(
        chan: Chan, requirement: String?,
        boardName: String?, threadNumber: String?, retry: Boolean
    ): CaptchaData? {
        return requireUserCaptcha(
            null, chan.configuration.captchaType, requirement,
            chan.name, boardName, threadNumber, null, retry
        )
    }

    @Throws(InterruptedException::class)
    fun requireUserCaptcha(
        captchaReader: CaptchaReader?,
        captchaType: String?,
        requirement: String?,
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        description: String?,
        retry: Boolean
    ): CaptchaData? {
        val pendingData = CaptchaPendingData(captchaReader)
        val pendingDataId = putPendingData(pendingData)
        try {
            val handlerData = CaptchaHandlerData(
                pendingDataId, chanName, captchaType,
                requirement, boardName, threadNumber, description
            )
            handler.obtainMessage(MESSAGE_REQUIRE_USER_CAPTCHA, handlerData).sendToTarget()
            if (retry) {
                handler.sendEmptyMessage(MESSAGE_SHOW_CAPTCHA_INVALID)
            }
            if (!pendingData.await(handler, handlerData)) {
                return null
            }
            val captchaData = pendingData.captchaData
            if (captchaData != null) {
                val workCaptchaType = if (pendingData.loadedCaptchaType != null)
                    pendingData.loadedCaptchaType
                else
                    captchaType
                val apiKey = captchaData.get(CaptchaData.API_KEY)
                if (apiKey != null && (ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2 == workCaptchaType ||
                            ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE == workCaptchaType ||
                            ChanConfiguration.CAPTCHA_TYPE_HCAPTCHA == workCaptchaType)
                ) {
                    captchaData.put(
                        CaptchaData.INPUT,
                        captchaData.get(ReadCaptchaTask.RECAPTCHA_SKIP_RESPONSE)
                    )
                }
            }
            return captchaData
        } finally {
            removePendingData(pendingDataId)
        }
    }

    @Throws(InterruptedException::class)
    fun requireUserItemSingleChoice(
        selected: Int, items: Array<CharSequence?>?, descriptionText: String?,
        descriptionImage: Bitmap?
    ): Int? {
        return requireUserSingleChoice(
            0,
            selected,
            items,
            null,
            descriptionText,
            descriptionImage,
            false
        )
    }

    @Throws(InterruptedException::class)
    fun requireUserItemMultipleChoice(
        selected: BooleanArray?, items: Array<CharSequence?>, descriptionText: String?,
        descriptionImage: Bitmap?
    ): BooleanArray? {
        return requireUserChoice(
            0,
            selected,
            items,
            null,
            descriptionText,
            descriptionImage,
            true,
            false
        )
    }

    @Throws(InterruptedException::class)
    fun requireUserImageSingleChoice(
        columns: Int, selected: Int, images: Array<Bitmap?>?,
        descriptionText: String?, descriptionImage: Bitmap?
    ): Int? {
        return requireUserSingleChoice(
            columns,
            selected,
            null,
            images,
            descriptionText,
            descriptionImage,
            true
        )
    }

    @Throws(InterruptedException::class)
    fun requireUserImageMultipleChoice(
        columns: Int, selected: BooleanArray?, images: Array<Bitmap?>,
        descriptionText: String?, descriptionImage: Bitmap?
    ): BooleanArray? {
        return requireUserChoice(
            columns,
            selected,
            null,
            images,
            descriptionText,
            descriptionImage,
            true,
            true
        )
    }

    @Throws(InterruptedException::class)
    private fun requireUserSingleChoice(
        columns: Int, selected: Int, items: Array<CharSequence?>?, images: Array<Bitmap?>?,
        descriptionText: String?, descriptionImage: Bitmap?, imageChoice: Boolean
    ): Int? {
        var selectedArray: BooleanArray? = null
        val length = if (items != null) items.size else if (images != null) images.size else 0
        if (selected >= 0 && selected < length) {
            selectedArray = BooleanArray(length)
            selectedArray[selected] = true
        }
        val result = requireUserChoice(
            columns, selectedArray, items!!, images!!, descriptionText, descriptionImage,
            false, imageChoice
        )
        if (result != null) {
            for (i in result.indices) {
                if (result[i]) {
                    return i
                }
            }
            return -1
        }
        return null
    }

    @Throws(InterruptedException::class)
    private fun requireUserChoice(
        columns: Int, selected: BooleanArray?, items: Array<CharSequence?>?, images: Array<Bitmap?>?,
        descriptionText: String?, descriptionImage: Bitmap?, multiple: Boolean, imageChoice: Boolean
    ): BooleanArray? {
        if (imageChoice && images == null) {
            throw NullPointerException("Images array is null")
        }
        if (!imageChoice && items == null) {
            throw NullPointerException("Items array is null")
        }
        val pendingData = ChoicePendingData()
        val pendingDataId = putPendingData(pendingData)
        try {
            val handlerData = ChoiceHandlerData(
                pendingDataId, columns, selected, images, items,
                descriptionText, descriptionImage, multiple
            )
            handler.obtainMessage(MESSAGE_REQUIRE_USER_CHOICE, handlerData).sendToTarget()
            return if (pendingData.await(handler, handlerData)) pendingData.result else null
        } finally {
            removePendingData(pendingDataId)
        }
    }

    @Throws(HttpException::class, InterruptedException::class)
    fun requireUserRecaptchaV2(
        referer: String?,
        apiKey: String?,
        invisible: Boolean,
        hcaptcha: Boolean,
        challengeExtra: ChallengeExtra?
    ): String? {
        val pendingData = RecaptchaV2PendingData()
        val pendingDataId = putPendingData(pendingData)
        try {
            val handlerData = RecaptchaV2HandlerData(
                pendingDataId,
                referer, apiKey, invisible, hcaptcha, challengeExtra
            )
            handler.obtainMessage(MESSAGE_REQUIRE_USER_RECAPTCHA_V2, handlerData).sendToTarget()
            if (!pendingData.await(handler, handlerData)) {
                return null
            }
            if (pendingData.exception != null) {
                throw pendingData.exception!!
            }
            return pendingData.response
        } finally {
            removePendingData(pendingDataId)
        }
    }

    @Throws(InterruptedException::class)
    fun <T> requireUserResolveFirewall(request: FirewallResolutionDialogRequest<T>): T? {
        val pendingData = FirewallResolutionPendingData<T?>()
        val pendingDataId = putPendingData(pendingData)
        try {
            val handlerData = FirewallHandlerData(pendingDataId, request)
            handler.obtainMessage(MESSAGE_REQUIRE_USER_RESOLVE_FIREWALL, handlerData).sendToTarget()
            return if (pendingData.await(handler, handlerData)) pendingData.result else null
        } finally {
            removePendingData(pendingDataId)
        }
    }

    fun register(activity: FragmentActivity) {
        Objects.requireNonNull<FragmentActivity?>(activity)
        if (this.activity != null) {
            val oldActivity = this.activity!!.get()
            if (oldActivity === activity) {
                return
            }
            if (oldActivity != null) {
                oldActivity.lifecycle.removeObserver(lifecycleObserver)
            }
        }
        this.activity = WeakReference<FragmentActivity?>(activity)
        this.viewModel = WeakReference<InstanceViewModel?>(
            ViewModelProvider(activity).get(
                InstanceViewModel::class.java
            )
        )
        activity.lifecycle.addObserver(lifecycleObserver)
        handleActivityResumeChecked(activity)
    }

    companion object {
        private val INSTANCE = ForegroundManager()

        @JvmStatic
        fun getInstance(): ForegroundManager = INSTANCE

        private const val MESSAGE_INTERRUPT = 1
        private const val MESSAGE_REQUIRE_USER_CAPTCHA = 2
        private const val MESSAGE_REQUIRE_USER_CHOICE = 3
        private const val MESSAGE_REQUIRE_USER_RECAPTCHA_V2 = 4
        private const val MESSAGE_REQUIRE_USER_RESOLVE_FIREWALL = 5
        private const val MESSAGE_SHOW_CAPTCHA_INVALID = 6

        private fun appendDescriptionImageView(
            viewGroup: ViewGroup,
            descriptionImage: Bitmap?
        ): ImageView {
            val imageView: ImageView = object : ImageView(viewGroup.getContext()) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    val width = getMeasuredWidth()
                    var height = 0
                    val drawable = getDrawable()
                    if (drawable != null) {
                        val dw = drawable.getIntrinsicWidth()
                        val dh = drawable.getIntrinsicHeight()
                        if (dw > 0 && dh > 0) {
                            height = width * dh / dw
                        }
                    }
                    height = min(height, (120f * obtainDensity(this)).toInt())
                    setMeasuredDimension(width, height)
                }
            }
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER)
            imageView.setImageBitmap(descriptionImage)
            viewGroup.addView(imageView)
            return imageView
        }
    }
}
