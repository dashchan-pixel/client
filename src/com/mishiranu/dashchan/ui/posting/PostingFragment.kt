package com.mishiranu.dashchan.ui.posting

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.Pair
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.os.BundleCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.ChanConfiguration
import chan.content.ChanConfiguration.Posting
import chan.content.ChanMarkup
import chan.content.ChanPerformer.CaptchaData
import chan.content.ChanPerformer.SendPostData
import chan.http.HttpException
import chan.http.HttpHolder
import chan.text.CommentEditor
import chan.util.CommonUtils
import chan.util.CommonUtils.equals
import chan.util.DataFile
import chan.util.StringUtils
import chan.util.StringUtils.formatFileSize
import chan.util.StringUtils.nullIfEmpty
import com.google.android.material.button.MaterialButton
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CommandRunner
import com.mishiranu.dashchan.content.DraftAttachmentMedia
import com.mishiranu.dashchan.content.Preferences.configuredFileNewname
import com.mishiranu.dashchan.content.Preferences.getCaptchaPass
import com.mishiranu.dashchan.content.Preferences.getPassword
import com.mishiranu.dashchan.content.Preferences.isAddSpaceAfterQuote
import com.mishiranu.dashchan.content.Preferences.isAlwaysClearMetadata
import com.mishiranu.dashchan.content.Preferences.isAlwaysRemoveFilename
import com.mishiranu.dashchan.content.Preferences.isAlwaysRenameFilename
import com.mishiranu.dashchan.content.Preferences.isAlwaysUniqueHash
import com.mishiranu.dashchan.content.Preferences.isCaptchaAutoReload
import com.mishiranu.dashchan.content.Preferences.isHidePersonalData
import com.mishiranu.dashchan.content.Preferences.isHugeCaptcha
import com.mishiranu.dashchan.content.Preferences.isMarkupButtonsAtBottom
import com.mishiranu.dashchan.content.Preferences.uiCornerRadius
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.async.ReadCaptchaTask
import com.mishiranu.dashchan.content.async.SendPostTask.ProgressState
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.database.ChanDatabase
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
import com.mishiranu.dashchan.content.net.VisibleIp
import com.mishiranu.dashchan.content.net.VisibleIpCommand
import com.mishiranu.dashchan.content.service.PostingService
import com.mishiranu.dashchan.content.service.PostingService.FailResult
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.DraftsStorage.AttachmentDraft
import com.mishiranu.dashchan.content.storage.DraftsStorage.CaptchaDraft
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.content.storage.DraftsStorage.PostDraft
import com.mishiranu.dashchan.graphics.RoundedCornersDrawable
import com.mishiranu.dashchan.graphics.TransparentTileDrawable
import com.mishiranu.dashchan.ui.CaptchaForm
import com.mishiranu.dashchan.ui.CaptchaOptionsDialog
import com.mishiranu.dashchan.ui.CaptchaOptionsDialog.CaptchaImageDownloadParameters
import com.mishiranu.dashchan.ui.ContentFragment
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentOptionsDialog
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentRatingDialog
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentWarningDialog
import com.mishiranu.dashchan.ui.posting.dialog.SendPostFailDetailsDialog
import com.mishiranu.dashchan.ui.posting.text.CommentEditWatcher
import com.mishiranu.dashchan.ui.posting.text.MarkupButtonProvider.Companion.iterable
import com.mishiranu.dashchan.ui.posting.text.MarkupButtonProvider.Companion.obtainSupportedAndDisplayedTags
import com.mishiranu.dashchan.ui.posting.text.NameEditWatcher
import com.mishiranu.dashchan.ui.posting.text.QuoteEditWatcher
import com.mishiranu.dashchan.ui.preference.ChanFragment
import com.mishiranu.dashchan.ui.preference.CommandsFragment
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.DelayedProgress
import com.mishiranu.dashchan.util.GraphicsUtils.Reencoding
import com.mishiranu.dashchan.util.GraphicsUtils.applyAlpha
import com.mishiranu.dashchan.util.GraphicsUtils.isLight
import com.mishiranu.dashchan.util.GraphicsUtils.reduceBitmapSize
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getColor
import com.mishiranu.dashchan.util.ResourceUtils.getColorStateList
import com.mishiranu.dashchan.util.ResourceUtils.getDrawable
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.util.ViewUtils.getOutlineRadius
import com.mishiranu.dashchan.util.ViewUtils.getOutlineRect
import com.mishiranu.dashchan.util.ViewUtils.removeFromParent
import com.mishiranu.dashchan.util.ViewUtils.setNewMargin
import com.mishiranu.dashchan.util.ViewUtils.setNewMarginRelative
import com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground
import com.mishiranu.dashchan.util.ViewUtils.setTextSizeScaled
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ClickableToast.Companion.show
import com.mishiranu.dashchan.widget.CommandsPopup
import com.mishiranu.dashchan.widget.DropdownView
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.MaterialContext
import com.mishiranu.dashchan.widget.PostingSheetLayout
import com.mishiranu.dashchan.widget.ProgressDialog
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import com.mishiranu.dashchan.widget.UriPasteEditText
import com.mishiranu.dashchan.widget.ViewFactory.makeListTextHeader
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class PostingFragment :
    ContentFragment,
    FragmentHandler.Callback,
    CaptchaForm.Callback,
    ReadCaptchaTask.Callback,
    PostingDialogCallback,
    CaptchaOptionsDialog.Callback,
    UriPasteEditText.Callback {
    constructor()

    constructor(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
        replyDataList: MutableList<ReplyData?>,
    ) {
        val args = Bundle()
        args.putString(EXTRA_CHAN_NAME, chanName)
        args.putString(EXTRA_BOARD_NAME, boardName)
        args.putString(EXTRA_THREAD_NUMBER, threadNumber)
        args.putParcelableArrayList(EXTRA_REPLY_DATA_LIST, ArrayList<ReplyData?>(replyDataList))
        setArguments(args)
    }

    private val chanName: String?
        get() = requireArguments().getString(EXTRA_CHAN_NAME)

    private val boardName: String?
        get() = requireArguments().getString(EXTRA_BOARD_NAME)

    private val threadNumber: String?
        get() = requireArguments().getString(EXTRA_THREAD_NUMBER)

    fun check(
        chanName: String?,
        boardName: String?,
        threadNumber: String?,
    ): Boolean =
        equals(this.chanName, chanName) &&
            equals(this.boardName, boardName) &&
            equals(this.threadNumber, threadNumber)

    private var allowPosting = false
    private var sendSuccess = false
    private var draftSaved = false
    private var failResult: FailResult? = null

    private var commentEditor: CommentEditor? = null

    private lateinit var postingConfiguration: Posting
    private var userIconItems: MutableList<Pair<String, String>>? = null
    private var attachmentRatingItems: MutableList<Pair<String, String>>? = null

    private var captchaType: String? = null
    private var captchaState: ReadCaptchaTask.CaptchaState? = null
    private var captchaData: CaptchaData? = null
    private var loadedCaptchaType: String? = null
    private var loadedCaptchaInput: ChanConfiguration.Captcha.Input? = null
    private var loadedCaptchaValidity: ChanConfiguration.Captcha.Validity? = null
    private var captcha: CaptchaForm.Captcha? = null
    private var captchaLarge = false
    private var captchaBlackAndWhite = false
    private var captchaLifetimeSeconds = 0

    /**
     * Whether the form is hosted as a sheet floating over a page, which only the activity hosting it
     * can know — see `MainActivity.navigatePostingSheet`. Kept in the arguments, so that a rotation,
     * which rebuilds the fragment out of them, keeps the same host.
     */
    var sheet: Boolean
        get() = requireArguments().getBoolean(EXTRA_SHEET)
        set(sheet) {
            requireArguments().putBoolean(EXTRA_SHEET, sheet)
        }

    /** The sheet hosting the form, or null when the form is the root view itself. */
    private var sheetLayout: PostingSheetLayout? = null

    /** The sheet's stand-in for the toolbar's attach item; see [invalidateAttach]. */
    private var attachButton: ImageView? = null

    private var scrollView: ScrollView? = null
    private var commentView: UriPasteEditText? = null
    private var sageCheckBox: CheckBox? = null
    private var spoilerCheckBox: CheckBox? = null
    private var originalPosterCheckBox: CheckBox? = null
    private var checkBoxParent: View? = null
    private var attachmentContainer: LinearLayout? = null
    private var nameView: EditText? = null
    private var emailView: EditText? = null
    private var passwordView: EditText? = null
    private var subjectView: EditText? = null
    private var iconView: DropdownView? = null
    private var personalDataBlock: ViewGroup? = null
    private var textFormatView: ViewGroup? = null
    private var commentEditWatcher: CommentEditWatcher? = null
    private var commandsButton: ImageView? = null
    private var commandsProgressView: View? = null
    private var commentCommands: List<CommandsStorage.CommandItem> = emptyList()

    /**
     * Turns the ⌘ button into a spinner while a command runs, but only once it has been running long
     * enough to be worth saying so (see [DelayedProgress]) — most commands finish before the user
     * could notice anything at all.
     */
    private val commandsProgress =
        DelayedProgress({ setCommandsRunning(true) }, { setCommandsRunning(false) })
    private var commandsRunning = false

    /** The command runs started here that may still be going; see [cancelCommandRuns]. */
    private val commandRuns = ArrayList<CommandRunner.Run>()
    private var captchaForm: CaptchaForm? = null
    private var footerContainer: FrameLayout? = null
    private var sendButton: Button? = null
    private var attachmentColumnCount = 0

    private val attachments = ArrayList<AttachmentHolder>()

    private var attachmentReordered = false

    private var allowDialog = true
    private var sendButtonEnabled = true

    private var refreshCaptchaWhenLifetimeEnd = false

    /** Shown while the command is being asked for another visible IP. */
    private var visibleIpCommandDialog: ProgressDialog? = null

    /** The run of the command that changes the IP this forum is seen at, while one is in flight. */
    private var visibleIpCommandRun: CommandRunner.Run? = null

    private var postingBinder: PostingService.Binder? = null
    private val postingConnection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val postingBinder = service as PostingService.Binder
                this@PostingFragment.postingBinder = postingBinder
                postingBinder.register(
                    postingCallback,
                    this@PostingFragment.chanName,
                    this@PostingFragment.boardName,
                    this@PostingFragment.threadNumber,
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                postingBinder?.unregister(postingCallback)
                postingBinder = null
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val formLayout = ExpandedLayout(container!!.getContext(), true)
        formLayout.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        inflater.inflate(R.layout.activity_posting, formLayout)
        if (!sheet) {
            return formLayout
        }
        // The same form, floating over the page it was opened from. Swiping the sheet away is a back
        // press by another name, so it leaves through the same door.
        val sheetLayout =
            PostingSheetLayout(container.getContext(), formLayout) {
                if (isAdded()) {
                    (requireActivity() as FragmentHandler).removeFragment()
                }
            }
        this.sheetLayout = sheetLayout
        return sheetLayout
    }

    /** A sheet answers a back press by sliding off screen, and dismisses itself once it is gone. */
    override val isBackHandled: Boolean
        get() = sheetLayout?.isOpen == true

    override fun onBackPressed(): Boolean {
        val sheetLayout = this.sheetLayout
        if (sheetLayout == null || !sheetLayout.isOpen) {
            return false
        }
        sheetLayout.hide()
        return true
    }

    /**
     * Leaves the form. A sheet slides off the page first and dismisses itself once it is gone, so that
     * every way out of it looks like the swipe it is named after.
     */
    private fun leave() {
        val sheetLayout = this.sheetLayout
        if (sheetLayout != null && sheetLayout.isOpen) {
            sheetLayout.hide()
        } else {
            (requireActivity() as FragmentHandler).removeFragment()
        }
    }

    /**
     * A button on one of this form's toasts, which does nothing once the form is no longer on screen.
     * The toast is the activity's and outlives the fragment that put it up — a sheet swiped away while
     * its toast is still standing is the short way to see that — and every one of these actions wants
     * something the fragment only has while it is hosted: a manager to show a dialog with, an activity
     * to push a screen onto. Tapping such a toast afterwards used to be a crash.
     */
    private fun whileOnScreen(action: () -> Unit): Runnable =
        Runnable {
            if (isResumed) {
                action()
            }
        }

    /**
     * The attach action, which is a toolbar item for a form that owns the toolbar and a button at the
     * end of the row of checkboxes for one that does not.
     */
    private fun invalidateAttach() {
        invalidateOptionsMenu()
        attachButton?.setVisibility(
            if (attachments.size < postingConfiguration.attachmentCount) View.VISIBLE else View.GONE,
        )
    }

    /**
     * The attach button the sheet carries in place of the toolbar item, built to match the ⌘ button
     * over the comment field — the same box, padding and secondary tint. The icon is the paperclip
     * lying down rather than the toolbar's upright one with a plus: this one sits at the end of a row
     * of checkboxes, where a wide glyph reads as part of the line instead of towering over it.
     */
    private fun buildAttachButton(
        row: LinearLayout,
        density: Float,
    ): ImageView {
        val context = row.context
        val button = ImageView(context)
        button.setImageResource(R.drawable.ic_attachment)
        button.imageTintList = ColorStateList.valueOf(getColor(context, android.R.attr.textColorSecondary))
        button.setBackgroundResource(
            getResourceId(context, android.R.attr.selectableItemBackgroundBorderless, 0),
        )
        button.contentDescription = getString(R.string.attach)
        val padding = (8f * density).toInt()
        button.setPadding(padding, padding, padding, padding)
        val size = (40f * density).toInt()
        button.setOnClickListener { startAttachmentPick() }
        row.addView(button, LinearLayout.LayoutParams(size, size))
        return button
    }

    public override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val chan = get(this.chanName)
        val obtainedPosting =
            chan.configuration.safe().obtainPosting(this.boardName, this.threadNumber == null)
        allowPosting = obtainedPosting != null &&
            chan.configuration
                .safe()
                .obtainBoard(this.boardName)
                .allowPosting
        val postingConfiguration = obtainedPosting ?: Posting()
        this.postingConfiguration = postingConfiguration

        val draftsStorage = getInstance()
        captchaType = chan.configuration.captchaType
        if (allowPosting) {
            commentEditor = chan.markup.safe().obtainCommentEditor(this.boardName)
        }
        val density = obtainDensity(view)
        val screenWidthDp = getResources().getConfiguration().screenWidthDp
        val hugeCaptcha = isHugeCaptcha
        val longLayout = screenWidthDp >= 480

        val scrollView = view.findViewById<ScrollView>(R.id.scroll_view)
        this.scrollView = scrollView
        val sheetLayout = this.sheetLayout
        if (sheetLayout != null) {
            // A sheet only hands the drag over to a nested scrolling child, and a plain ScrollView
            // does not opt into that on its own
            scrollView.setNestedScrollingEnabled(true)
            // The sheet's top edge is where the toolbar ends, so a toolbar that hides itself as the
            // page behind is scrolled would leave the sheet floating below a gap
            (requireActivity() as FragmentHandler).setActionBarLocked(LOCKER_SHEET, true)
        }
        val postingLayout = view.findViewById<ViewGroup>(R.id.posting_layout)
        val commentParent = view.findViewById<LinearLayout>(R.id.comment_parent)
        val commentFormat = view.findViewById<LinearLayout?>(R.id.comment_format)
        val commentView = view.findViewById<UriPasteEditText>(R.id.comment)
        this.commentView = commentView
        val sageCheckBox = view.findViewById<CheckBox>(R.id.sage_checkbox)
        this.sageCheckBox = sageCheckBox
        val spoilerCheckBox = view.findViewById<CheckBox>(R.id.spoiler_checkbox)
        this.spoilerCheckBox = spoilerCheckBox
        val originalPosterCheckBox = view.findViewById<CheckBox>(R.id.original_poster_checkbox)
        this.originalPosterCheckBox = originalPosterCheckBox
        val checkBoxParent = view.findViewById<View>(R.id.checkbox_parent)
        this.checkBoxParent = checkBoxParent
        val nameView = view.findViewById<EditText>(R.id.name)
        this.nameView = nameView
        val emailView = view.findViewById<EditText>(R.id.email)
        this.emailView = emailView
        val passwordView = view.findViewById<EditText>(R.id.password)
        this.passwordView = passwordView
        val subjectView = view.findViewById<EditText>(R.id.subject)
        this.subjectView = subjectView
        val iconView = view.findViewById<DropdownView>(R.id.icon)
        this.iconView = iconView
        val personalDataBlock = view.findViewById<ViewGroup>(R.id.personal_data_block)
        this.personalDataBlock = personalDataBlock
        this.attachmentContainer =
            view.findViewById<LinearLayout>(R.id.attachment_container).also {
                it.setOnDragListener(attachmentDragListener)
            }
        val footerContainer = view.findViewById<FrameLayout>(R.id.footer_container)
        this.footerContainer = footerContainer
        val oldScrollViewHeight = intArrayOf(-1)
        scrollView.addOnLayoutChangeListener(
            OnLayoutChangeListener {
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
                ->
                val currentScrollView = this.scrollView ?: return@OnLayoutChangeListener
                // Track the viewport, not the raw height: ExpandedLayout pushes the system insets
                // onto this view as padding without changing its size
                val scrollViewHeight = viewportHeight(currentScrollView)
                if (scrollViewHeight != oldScrollViewHeight[0]) {
                    oldScrollViewHeight[0] = scrollViewHeight
                    resizeComment(false)
                }
            },
        )
        postingLayout.setPadding((8f * density).toInt(), 0, (8f * density).toInt(), 0)

        if (sheetLayout == null) {
            // The two headers name the halves of a form that has the screen to itself. A sheet is a
            // form laid over the thread it is replying to, where those two rows are 56dp each of the
            // little height it has, spent above the subject field on saying what a form this small
            // leaves in no doubt anyway.
            Companion.addHeader(personalDataBlock, 0, R.string.personal_data)
            addHeader(postingLayout, postingLayout.indexOfChild(subjectView), R.string.message_data)
        }
        // The confirmation block has no header of its own, so the send button would otherwise stick
        // to the checkboxes above it
        footerContainer.setPadding(0, (16f * density).toInt(), 0, 0)
        val tripcodeWarning = view.findViewById<TextView>(R.id.personal_tripcode_warning)
        val remainingCharacters = view.findViewById<TextView>(R.id.remaining_characters)
        setTextSizeScaled(tripcodeWarning, 12)
        tripcodeWarning.setPadding(
            (4f * density).toInt(),
            0,
            (4f * density).toInt(),
            (4f * density).toInt(),
        )
        setTextSizeScaled(remainingCharacters, 12)
        setNewMargin(remainingCharacters, 0, (-2f * density).toInt(), 0, 0)

        nameView.addTextChangedListener(
            NameEditWatcher(
                postingConfiguration.allowName &&
                    !postingConfiguration.allowTripcode,
                nameView,
                tripcodeWarning,
                Runnable { resizeComment(true) },
            ),
        )
        ViewUtils.applyMonospaceTypeface(passwordView)
        commentEditWatcher =
            CommentEditWatcher(
                postingConfiguration,
                commentView,
                remainingCharacters,
                Runnable { resizeComment(true) },
                Runnable { getInstance().store(obtainPostDraft()) },
            )
        commentView.setOnFocusChangeListener(
            OnFocusChangeListener { v: View?, hasFocus: Boolean ->
                updateFocusButtons(
                    hasFocus,
                )
            },
        )
        commentView.addTextChangedListener(commentEditWatcher)
        commentView.addTextChangedListener(QuoteEditWatcher(requireContext()))
        commentView.setCallback(
            this,
            buildMimeTypeList(postingConfiguration.attachmentMimeTypes),
        )
        var addPaddingToRoot = false
        val landscape =
            getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
        // When enabled, the markup bar lives inline below the comment field (added further down)
        // instead of pinned under the toolbar. postingLayout already supplies horizontal padding.
        // A sheet has no claim on the toolbar -- that belongs to the page it floats over -- so its
        // markup bar goes inline whatever the setting says.
        val markupAtBottom = isMarkupButtonsAtBottom || sheet
        val extra =
            if (markupAtBottom) {
                postingLayout
            } else if (landscape) {
                (requireActivity() as FragmentHandler).getToolbarView()
            } else {
                (requireActivity() as FragmentHandler).getToolbarExtra()
            }
        val textFormatView = LinearLayout(extra.getContext())
        textFormatView.setOrientation(LinearLayout.HORIZONTAL)
        this.textFormatView = textFormatView
        if (markupAtBottom) {
            textFormatView.setPadding(0, 0, 0, (4f * density).toInt())
        } else if (landscape) {
            val rtl = textFormatView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
            textFormatView.setPadding(
                if (rtl) 0 else (8f * density).toInt(),
                0,
                if (rtl) (8f * density).toInt() else 0,
                0,
            )
        } else {
            textFormatView.setPadding(
                (8f * density).toInt(),
                0,
                (8f * density).toInt(),
                (4f * density).toInt(),
            )
            addPaddingToRoot = true
        }
        if (!markupAtBottom) {
            extra.addView(
                textFormatView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        commentParent.removeView(commentView)
        // Wrap the comment field so a ⌘ button can float over its bottom-right corner. The field
        // keeps growing via setMinHeight (resizeComment) and the wrapper grows with it.
        val commentWrapper = FrameLayout(commentView.context)
        // In a sheet the wrapper is the row that takes the height the others do not want, and the field
        // fills it; hosted plainly the field grows itself with setMinHeight (resizeComment) and wants to
        // be no taller than its content until it does
        commentWrapper.addView(
            commentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (sheetLayout != null) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                },
            ),
        )
        commandsButton = buildCommandsButton(commentWrapper, density)
        commandsProgressView = buildCommandsProgress(commentWrapper, density)
        postingLayout.addView(
            commentWrapper,
            postingLayout.indexOfChild(commentParent),
            if (sheetLayout != null) {
                // The scroll view fills its viewport, so the room a form shorter than the sheet is not
                // using has to go to one of its rows or it is a gap. It goes here: a sheet dragged tall
                // is a taller comment field, not the same short field with space under the checkboxes.
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        postingLayout.removeView(commentParent)
        if (markupAtBottom) {
            postingLayout.addView(
                textFormatView,
                postingLayout.indexOfChild(commentWrapper) + 1,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (sheetLayout != null) {
            // A sheet has no toolbar to take the attach action from, so it goes at the end of the row
            // of checkboxes, the one line of the form with width to spare. The checkboxes get all of
            // that width, and the button keeps the end of the line even on a forum that offers no
            // checkboxes at all and leaves the row itself hidden.
            val optionsRow = LinearLayout(postingLayout.context)
            optionsRow.orientation = LinearLayout.HORIZONTAL
            optionsRow.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val index = postingLayout.indexOfChild(checkBoxParent)
            postingLayout.removeView(checkBoxParent)
            optionsRow.addView(
                checkBoxParent,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            postingLayout.addView(
                optionsRow,
                index,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            attachButton = buildAttachButton(optionsRow, density)
        }
        ViewUtils.setNewMargin(checkBoxParent, 0, (4f * density).toInt(), 0, 0)
        updateCommandsButton()

        updatePostingConfiguration(true, false, false)
        MarkupButtonsBuilder(
            addPaddingToRoot,
            (
                getResources().getConfiguration().screenWidthDp *
                    obtainDensity(getResources())
            ).toInt(),
        )

        val longFooter = longLayout && !hugeCaptcha
        val resId =
            if (longFooter) R.layout.activity_posting_footer_long else R.layout.activity_posting_footer_common
        getLayoutInflater().inflate(resId, footerContainer)
        val captchaInputRootView =
            footerContainer.findViewById<LinearLayout?>(R.id.captcha_input_root)
        val captchaInputParentView =
            footerContainer.findViewById<LinearLayout>(R.id.captcha_input_parent)
        val captchaInputView = footerContainer.findViewById<EditText>(R.id.captcha_input)
        captchaInputParentView.setPadding(
            0,
            if (longFooter) (8f * density).toInt() else 0,
            0,
            (8f * density).toInt(),
        )
        setNewMarginRelative(captchaInputView, null, null, (4f * density).toInt(), null)

        val captchaConfiguration = chan.configuration.safe().obtainCaptcha(captchaType)
        val captchaForm =
            CaptchaForm(
                this,
                true,
                !longFooter,
                footerContainer,
                captchaInputParentView,
                captchaInputView,
                captchaConfiguration,
            )
        this.captchaForm = captchaForm
        captchaLifetimeSeconds = captchaConfiguration.ttl
        refreshCaptchaWhenLifetimeEnd = isCaptchaAutoReload
        val maxTranslationZ = (2f * density).toInt().toFloat()
        val sendButton =
            object : MaterialButton(MaterialContext.wrap(captchaInputParentView.getContext())) {
                override fun setTranslationZ(translationZ: Float) {
                    super.setTranslationZ(min(translationZ, maxTranslationZ))
                }
            }
        // Match the app-wide rounded look; drop MaterialButton's default vertical touch-target insets
        // so the button keeps the full height the posting footer lays out for it.
        sendButton.cornerRadius = (uiCornerRadius * density).toInt()
        sendButton.insetTop = 0
        sendButton.insetBottom = 0
        this.sendButton = sendButton
        val rect = Rect()
        // Limit elevation height since the shadow looks ugly when the view is at the bottom
        sendButton.setOutlineProvider(
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) {
                    view.getBackground().getOutline(outline)
                    if (getOutlineRect(outline, rect)) {
                        val radius = getOutlineRadius(outline)
                        rect.bottom -= (2f * density).toInt()
                        outline.setRoundRect(rect, radius)
                    }
                }
            },
        )

        val theme = getTheme(sendButton.getContext())
        val colorControlDisabled = applyAlpha(theme.controlNormal21, theme.disabledAlpha21)
        val states = arrayOf<IntArray?>(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        val colors = intArrayOf(colorControlDisabled, theme.accent)
        sendButton.setBackgroundTintList(ColorStateList(states, colors))
        // The Material3 overlay is only there to supply M3 shapes and attrs; its stock palette must
        // not leak in. Label and ripple default to colorOnPrimary, which is a dark purple in the M3
        // dark theme — take them from the user theme instead, like the framework colored button did.
        val buttonContext = captchaInputParentView.getContext()
        getColorStateList(buttonContext, android.R.attr.textColorPrimaryInverse)?.let {
            sendButton.setTextColor(it)
        }
        getColorStateList(buttonContext, android.R.attr.colorControlHighlight)?.let {
            sendButton.rippleColor = it
        }

        sendButton.setSingleLine(true)
        // setSingleLine breaks capitalization
        sendButton.setAllCaps(true)

        captchaInputParentView.addView(sendButton, 0, LinearLayout.LayoutParams.WRAP_CONTENT)
        sendButton.setText(R.string.send)
        sendButton.setOnClickListener(View.OnClickListener { v: View? -> onSendButtonClick() })
        if (longFooter) {
            (sendButton.getLayoutParams() as LinearLayout.LayoutParams).weight = 2f
            val lastAddWeight = booleanArrayOf(true)
            captchaInputParentView.addOnLayoutChangeListener(
                OnLayoutChangeListener {
                    v: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                    ->
                    val currentSendButton = this.sendButton ?: return@OnLayoutChangeListener
                    val addWeight = captchaInputView.getVisibility() == View.GONE
                    if (addWeight != lastAddWeight[0]) {
                        lastAddWeight[0] = addWeight
                        (currentSendButton.getLayoutParams() as LinearLayout.LayoutParams).weight =
                            if (addWeight) 2f else 1f
                        currentSendButton.requestLayout()
                    }
                },
            )
        } else {
            (sendButton.getLayoutParams() as LinearLayout.LayoutParams).weight = 1f
        }
        if (sheetLayout != null) {
            // The send button has to be there at whatever height the sheet has settled at, so the
            // captcha and the button move out of the scrolling form into the sheet's pinned footer
            // row. postingLayout's own horizontal padding does not follow them, hence the padding
            // here, and the 16dp above the block is the form's spacing, which the row's edge is now.
            postingLayout.removeView(footerContainer)
            val padding = (8f * density).toInt()
            footerContainer.setPadding(padding, padding, padding, 0)
            sheetLayout.footer.addView(
                footerContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        attachmentColumnCount =
            if (screenWidthDp >= 960) {
                4
            } else if (screenWidthDp >= 480) {
                2
            } else {
                1
            }

        val builder = StringBuilder()
        var commentCarriage = 0

        attachments.clear()
        val postDraft =
            draftsStorage
                .getPostDraft(this.chanName, this.boardName, this.threadNumber)
        if (postDraft != null) {
            if (!StringUtils.isEmpty(postDraft.comment)) {
                builder.append(postDraft.comment)
                commentCarriage = postDraft.commentCarriage
            }
            val attachmentDrafts = postDraft.attachmentDrafts
            if (attachmentDrafts != null && !attachmentDrafts.isEmpty()) {
                for (attachmentDraft in attachmentDrafts) {
                    addAttachment(
                        attachmentDraft.hash,
                        attachmentDraft.name,
                        attachmentDraft.newname,
                        attachmentDraft.rating,
                        attachmentDraft.optionUniqueHash,
                        attachmentDraft.optionRemoveMetadata,
                        attachmentDraft.optionRemoveFileName,
                        attachmentDraft.optionSpoiler,
                        attachmentDraft.reencoding,
                        attachmentDraft.optionCustomName,
                    )
                }
            }
            nameView.setText(postDraft.name)
            emailView.setText(postDraft.email)
            passwordView.setText(postDraft.password)
            subjectView.setText(postDraft.subject)
            sageCheckBox.setChecked(postDraft.optionSage)
            spoilerCheckBox.setChecked(postDraft.optionSpoiler)
            originalPosterCheckBox.setChecked(postDraft.optionOriginalPoster)
            val userIconItems = this.userIconItems
            if (userIconItems != null) {
                var index = 0
                if (postDraft.userIcon != null) {
                    for (i in userIconItems.indices) {
                        if (postDraft.userIcon == userIconItems[i].first) {
                            index = i + 1
                            break
                        }
                    }
                }
                iconView.setSelection(index)
            }
        }

        var captchaRestoreSuccess = false
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_CAPTCHA_DRAFT)) {
            val captchaDraft =
                BundleCompat.getParcelable<CaptchaDraft?>(
                    savedInstanceState,
                    EXTRA_CAPTCHA_DRAFT,
                    CaptchaDraft::class.java,
                )
            if (captchaDraft!!.captchaState != null) {
                showCaptcha(
                    captchaDraft.captchaState,
                    captchaDraft.captchaData,
                    captchaDraft.loadedCaptchaType,
                    captchaDraft.loadedInput,
                    captchaDraft.loadedValidity,
                    captchaDraft.captcha,
                    captchaDraft.large,
                    captchaDraft.blackAndWhite,
                )
                captchaForm.setText(captchaDraft.text)
                captchaRestoreSuccess = true
            }
        } else {
            val captchaDraft = draftsStorage.getCaptchaDraft(this.chanName)
            if (captchaDraft != null && captchaDraft.loadedCaptchaType == null) {
                var captchaValidity: ChanConfiguration.Captcha.Validity? =
                    captchaConfiguration.validity

                if (captchaValidity == null) {
                    captchaValidity = ChanConfiguration.Captcha.Validity.SHORT_LIFETIME
                }
                if (captchaDraft.loadedValidity != null) {
                    val loadedCaptchaValidity = captchaDraft.loadedValidity
                    if (captchaDraft.captchaState != ReadCaptchaTask.CaptchaState.CAPTCHA ||
                        captchaValidity.compareTo(loadedCaptchaValidity) >= 0
                    ) {
                        // Allow only reducing of validity
                        captchaValidity = loadedCaptchaValidity
                    }
                }

                val captchaFromDraft = captchaDraft.captcha
                val captchaFromDraftHasLifetime =
                    captchaFromDraft != null && captchaFromDraft.hasLifetime()
                var canLoadState = false

                when (captchaValidity) {
                    ChanConfiguration.Captcha.Validity.SHORT_LIFETIME -> {
                        // A captcha that declares a lifetime is trusted for exactly that long; one that
                        // declares none is trusted for DraftsStorage's reuse window, which
                        // getCaptchaDraft has already applied to this draft — so all that is left to
                        // ask here is that the draft still carry a captcha to date it by.
                        //
                        // Upstream demanded a *declared* lifetime instead, which no captcha that lands
                        // in this branch has: SHORT_LIFETIME is what the app gives reCAPTCHA and
                        // hCaptcha, neither of which sets a TTL, and what it falls back to when a forum
                        // names no validity at all. So the branch could never restore anything and the
                        // form solved a captcha again on every open — including reopening it seconds
                        // later, where a solved reCAPTCHA's token is still perfectly good and the draft
                        // holding it was kept for exactly this.
                        canLoadState = captchaFromDraft != null && captchaFromDraft.alive()
                    }

                    ChanConfiguration.Captcha.Validity.IN_THREAD -> {
                        canLoadState = equals(this.boardName, captchaDraft.boardName) &&
                            equals(this.threadNumber, captchaDraft.threadNumber)
                    }

                    ChanConfiguration.Captcha.Validity.IN_BOARD_SEPARATELY -> {
                        canLoadState = equals(this.boardName, captchaDraft.boardName) &&
                            ((this.threadNumber == null) == (captchaDraft.threadNumber == null))
                    }

                    ChanConfiguration.Captcha.Validity.IN_BOARD -> {
                        canLoadState = equals(this.boardName, captchaDraft.boardName)
                    }

                    ChanConfiguration.Captcha.Validity.LONG_LIFETIME -> {
                        canLoadState = !captchaFromDraftHasLifetime || captchaFromDraft.alive()
                    }
                }

                if (canLoadState && equals(captchaType, captchaDraft.captchaType)) {
                    if (captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.CAPTCHA &&
                        captchaFromDraft != null
                    ) {
                        showCaptcha(
                            ReadCaptchaTask.CaptchaState.CAPTCHA,
                            captchaDraft.captchaData,
                            null,
                            captchaDraft.loadedInput,
                            captchaDraft.loadedValidity,
                            captchaFromDraft,
                            captchaDraft.large,
                            captchaDraft.blackAndWhite,
                        )
                        captchaForm.setText(captchaDraft.text)
                        captchaRestoreSuccess = true
                    } else if (captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.SKIP ||
                        captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.PASS
                    ) {
                        // The captcha goes back in even though a SKIP or a PASS draws none: it is what
                        // says when the state was read, and the form saves it again on the way out.
                        // Dropping it here made a draft ageless from its first restore on — a solved
                        // reCAPTCHA's token, which is a SKIP carrying the response, would then be
                        // handed back long after it went stale, and the send refused with it.
                        showCaptcha(
                            captchaDraft.captchaState,
                            captchaDraft.captchaData,
                            null,
                            null,
                            captchaDraft.loadedValidity,
                            captchaFromDraft,
                            false,
                            false,
                        )
                        captchaRestoreSuccess = true
                    } else if ((
                            captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.NEED_LOAD ||
                                captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD ||
                                captchaDraft.captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING
                        ) &&
                        captchaFromDraft != null &&
                        captchaFromDraft.alive()
                    ) {
                        // A load button is a state the forum answered with, as much as a SKIP is, and
                        // reading it again produces the very same button at the cost of the round trip
                        // that asks -- two of them on 2ch, whose emoji captcha is what the app opens
                        // with there: the captcha settings, then an id that is thrown away unread
                        // because a button may be shown instead. Nothing of the answer is spent by
                        // restoring it, since pressing the button reads a captcha of its own anyway.
                        //
                        // Only while the draft's captcha is alive, which is the app's only measure of
                        // how long "a captcha is needed here" stays true: a captcha that declares a
                        // lifetime is trusted for it (90 seconds on 2ch), one that declares none for
                        // DraftsStorage's reuse window, which getCaptchaDraft has already applied.
                        showCaptcha(
                            captchaDraft.captchaState,
                            captchaDraft.captchaData,
                            null,
                            null,
                            captchaDraft.loadedValidity,
                            captchaFromDraft,
                            false,
                            false,
                        )
                        captchaRestoreSuccess = true
                    }
                }
            }
        }

        val replyDataList: MutableList<ReplyData>? =
            if (savedInstanceState != null) {
                mutableListOf()
            } else {
                BundleCompat.getParcelableArrayList<ReplyData>(
                    requireArguments(),
                    EXTRA_REPLY_DATA_LIST,
                    ReplyData::class.java,
                )
            }
        if (!replyDataList!!.isEmpty()) {
            var onlyLinks = true
            for (data in replyDataList) {
                if (!StringUtils.isEmpty(data.comment)) {
                    onlyLinks = false
                    break
                }
            }
            for (i in replyDataList.indices) {
                val lastLink = i == replyDataList.size - 1
                val data = replyDataList.get(i)
                val postNumber = data.postNumber
                var comment = data.comment
                if (postNumber != null) {
                    val link = ">>" + postNumber
                    // Check if user replies to the same post
                    val index = builder.lastIndexOf(link, commentCarriage)
                    if (index < 0 ||
                        (
                            index < commentCarriage &&
                                commentCarriage <= builder.length &&
                                builder.substring(index, commentCarriage).contains("\n>>")
                        )
                    ) {
                        var afterSpace = false // If user wants to add link at the same line
                        if (commentCarriage > 0 && commentCarriage <= builder.length) {
                            val charBefore = builder.get(commentCarriage - 1)
                            if (charBefore != '\n') {
                                if (charBefore == ' ' && onlyLinks) {
                                    afterSpace = true
                                } else {
                                    // Ensure free line before link
                                    builder.insert(commentCarriage++, '\n')
                                }
                            }
                        }
                        builder.insert(commentCarriage, link)
                        commentCarriage += link.length
                        if (afterSpace) {
                            if (!lastLink) {
                                builder.insert(commentCarriage, ", ")
                                commentCarriage += 2
                            }
                        } else {
                            builder.insert(commentCarriage++, '\n')
                            if (commentCarriage < builder.length) {
                                if (builder.get(commentCarriage) != '\n') {
                                    // Ensure free line for typing
                                    builder.insert(commentCarriage, '\n')
                                }
                            }
                        }
                    }
                }
                if (!comment.isNullOrEmpty()) {
                    if (commentCarriage > 0 &&
                        commentCarriage <= builder.length &&
                        builder.get(
                            commentCarriage - 1,
                        ) != '\n'
                    ) {
                        builder.insert(commentCarriage++, '\n')
                    }
                    // Remove links in the beginning of the post
                    comment = comment.replace("(^|\n)(>>\\d+(\n|\\s)?)+".toRegex(), "$1")
                    if (isAddSpaceAfterQuote) {
                        comment = comment.replace("(\n+)".toRegex(), "$1> ")
                        builder.insert(commentCarriage, "> ")
                        commentCarriage += 2
                    } else {
                        comment = comment.replace("(\n+)".toRegex(), "$1>")
                        builder.insert(commentCarriage, ">")
                        commentCarriage += 1
                    }
                    builder.insert(commentCarriage, comment)
                    commentCarriage += comment.length
                    builder.insert(commentCarriage++, '\n')
                }
            }
        }

        commentView.setText(builder)
        commentView.setSelection(commentCarriage)
        commentView.requestFocus()
        if (!captchaRestoreSuccess) {
            refreshCaptcha(false, true, false)
        }
        // Opening the form is what the warning is about, not reading a captcha: a form that restored
        // one reads none, and the IP it would post from is worth the same word either way.
        checkVisibleIpBan(chan)

        if (!sheet) {
            // A sheet says what it is by covering the thread it replies to, and the toolbar behind it
            // goes on naming that thread
            (requireActivity() as FragmentHandler).setTitleSubtitle(
                getString(
                    if (StringUtils.isEmpty(
                            this.threadNumber,
                        )
                    ) {
                        R.string.new_thread
                    } else {
                        R.string.new_post
                    },
                ),
                null,
            )
        }
        requireActivity().bindService(
            Intent(requireContext(), PostingService::class.java),
            postingConnection,
            Context.BIND_AUTO_CREATE,
        )

        val viewModel = ViewModelProvider(this).get<CaptchaViewModel>(CaptchaViewModel::class.java)
        viewModel.observe(getViewLifecycleOwner(), this)

        val banWarningViewModel =
            ViewModelProvider(this).get(BanWarningViewModel::class.java)
        banWarningViewModel.observe(getViewLifecycleOwner()) { banned ->
            if (banned) {
                // The way out of a ban on this IP is another IP. Where the user has written
                // the command for it the button runs that right here; without one the fix is a proxy
                // configured by hand, on the forum's settings screen, which the button opens instead.
                // Either way the message has already said what is wrong, so the button stays a bare verb.
                val hasCommand = VisibleIpCommand.forChan(get(chanName)) != null
                show(
                    getString(R.string.visible_ip_banned),
                    null,
                    ClickableToast.Button(
                        R.string.change,
                        false,
                        whileOnScreen {
                            if (hasCommand) {
                                changeVisibleIp()
                            } else {
                                openForumProxySettings()
                            }
                        },
                    ),
                )
            }
        }
    }

    public override fun onDestroyView() {
        super.onDestroyView()
        captchaForm!!.onDestroyView()

        postingBinder?.unregister(postingCallback)
        postingBinder = null
        requireActivity().unbindService(postingConnection)

        dismissSendPost()
        saveDraft()
        visibleIpCommandDialog?.dismiss()
        visibleIpCommandDialog = null
        ViewUtils.removeFromParent(textFormatView!!)
        if (sheetLayout != null) {
            // The toolbar goes back to hiding itself with the page it belongs to
            (requireActivity() as FragmentHandler).setActionBarLocked(LOCKER_SHEET, false)
        }

        sheetLayout = null
        attachButton = null
        scrollView = null
        commentView = null
        sageCheckBox = null
        spoilerCheckBox = null
        originalPosterCheckBox = null
        checkBoxParent = null
        attachmentContainer = null
        nameView = null
        emailView = null
        passwordView = null
        subjectView = null
        iconView = null
        personalDataBlock = null
        textFormatView = null
        commentEditWatcher = null
        // A command still running outlives the views it would have spun for: its callback finds the
        // comment field gone and never releases the spinner, so drop it here.
        cancelCommandRuns()
        commandsProgress.cancel()
        commandsRunning = false
        commandsButton = null
        commandsProgressView = null
        captchaForm = null
        footerContainer = null
        sendButton = null
        attachments.clear()
    }

    /** The attached files as drafts, which is the form both storage and a command work in. */
    private fun obtainAttachmentDrafts(): ArrayList<AttachmentDraft> {
        val attachmentDrafts = ArrayList<AttachmentDraft>(attachments.size)
        for (holder in attachments) {
            attachmentDrafts.add(
                AttachmentDraft(
                    holder.hash!!,
                    holder.name,
                    holder.newname,
                    holder.rating,
                    holder.optionUniqueHash,
                    holder.optionRemoveMetadata,
                    holder.optionRemoveFileName,
                    holder.optionSpoiler,
                    holder.reencoding,
                    holder.optionCustomName,
                ),
            )
        }
        return attachmentDrafts
    }

    private fun obtainPostDraft(): PostDraft {
        // Kept null rather than empty: a draft with no attachments stores no attachment list at all.
        val attachmentDrafts = if (attachments.size > 0) obtainAttachmentDrafts() else null
        val subject = subjectView!!.getText().toString()
        val comment = commentView!!.getText().toString()
        val commentCarriage = commentView!!.getSelectionEnd()
        val name = nameView!!.getText().toString()
        val email = emailView!!.getText().toString()
        val password = passwordView!!.getText().toString()
        val optionSage = sageCheckBox!!.isChecked()
        val optionSpoiler = spoilerCheckBox!!.isChecked()
        val optionOriginalPoster = originalPosterCheckBox!!.isChecked()
        val userIcon = this.userIcon
        return PostDraft(
            this.chanName,
            this.boardName,
            this.threadNumber,
            name,
            email,
            password,
            subject,
            comment,
            commentCarriage,
            attachmentDrafts,
            optionSage,
            optionSpoiler,
            optionOriginalPoster,
            userIcon,
        )
    }

    private fun obtainCaptchaDraft(): CaptchaDraft {
        val input = captchaForm!!.input
        return CaptchaDraft(
            captchaType,
            captchaState,
            captchaData,
            loadedCaptchaType,
            loadedCaptchaInput,
            loadedCaptchaValidity,
            input,
            captcha,
            captchaLarge,
            captchaBlackAndWhite,
            this.boardName,
            this.threadNumber,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val captchaDraft = obtainCaptchaDraft()
        outState.putParcelable(EXTRA_CAPTCHA_DRAFT, captchaDraft)
        saveDraft()
    }

    public override fun onResume() {
        super.onResume()

        val draftsStorage = getInstance()
        val futureAttachmentDrafts = draftsStorage.getFutureAttachmentDrafts()
        if (!futureAttachmentDrafts.isEmpty()) {
            val attachmentsToAdd = ArrayList<Pair<String?, String?>>(futureAttachmentDrafts.size)
            for (attachmentDraft in futureAttachmentDrafts) {
                attachmentsToAdd.add(
                    Pair<String?, String?>(
                        attachmentDraft.hash,
                        attachmentDraft.name,
                    ),
                )
            }
            handleAttachmentsToAdd(attachmentsToAdd, futureAttachmentDrafts.size)
            getInstance().consumeFutureAttachmentDrafts()
        }

        val futureComment = draftsStorage.getFutureComment()
        if (!futureComment.isNullOrEmpty()) {
            insertFutureComment(futureComment)
            draftsStorage.consumeFutureComment()
        }

        val failResult = this.failResult
        this.failResult = null
        if (failResult != null) {
            handleFailResult(failResult)
        }
        draftSaved = false
        if (!allowPosting || sendSuccess) {
            leave()
        }
    }

    override fun onChansChanged(
        changed: Collection<String>,
        removed: Collection<String>,
    ) {
        if (changed.contains(this.chanName) || removed.contains(this.chanName)) {
            updatePostingConfigurationIfNeeded()
            if (!allowPosting) {
                leave()
            }
        }
    }

    // Should be called both from onDestroyView (1) and onSaveInstanceState (2).
    // 1: Ensures draft is saved when user leaves posting screen.
    // 2: Ensures draft is saved when activity is recreated.
    private fun saveDraft() {
        if (!sendSuccess && !draftSaved) {
            draftSaved = true
            val draftsStorage = getInstance()
            draftsStorage.store(obtainPostDraft())
            draftsStorage.store(this.chanName, obtainCaptchaDraft())
        }
    }

    override fun onUriWithAllowedMimeTypePasted(uri: Uri) {
        val file = obtain(uri)
        if (file != null) {
            val hash = getInstance().store(file)
            if (hash != null) {
                val name = file.name
                addAttachment(hash, name)
            }
        }
    }

    override fun showCaptchaOptionsDialog(dialog: CaptchaOptionsDialog) {
        dialog.show(getChildFragmentManager(), null)
    }

    override fun attachCaptchaImageToPost(captchaImageAttachmentDataFile: DataFile) {
        val captchaImageFileHolder = obtain(captchaImageAttachmentDataFile)
        if (captchaImageFileHolder != null) {
            val captchaImageAttachmentHash = getInstance().store(captchaImageFileHolder)
            val captchaImageAttachmentName = captchaImageFileHolder.name
            addAttachment(captchaImageAttachmentHash, captchaImageAttachmentName)
        }
        captchaImageAttachmentDataFile.delete()
    }

    override fun getCaptchaImageDownloadParameters(): CaptchaImageDownloadParameters = CaptchaImageDownloadParameters(this.chanName, this.boardName, this.threadNumber)

    override fun refreshCaptcha() {
        onRefreshCaptcha(true)
    }

    override fun onRefreshCaptcha(forceRefresh: Boolean) {
        refreshCaptcha(forceRefresh, false, true)
    }

    override fun onConfirmCaptcha() {
        executeSendPost()
    }

    override fun onCaptchaLifetimeEnded() {
        if (refreshCaptchaWhenLifetimeEnd) {
            onRefreshCaptcha(false)
        } else {
            showCaptcha(
                ReadCaptchaTask.CaptchaState.NEED_LOAD,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
            )
        }
    }

    private fun updatePostingConfiguration(
        views: Boolean,
        attachmentOptions: Boolean,
        attachmentCount: Boolean,
    ) {
        val posting = postingConfiguration
        if (views) {
            val iconView = iconView!!
            val userIconItems = if (posting.userIcons.size > 0) posting.userIcons else null
            this.userIconItems = userIconItems
            if (userIconItems != null) {
                val lastUserIcon = this.userIcon
                var lastUserIconIndex = -1
                val items = ArrayList<String>()
                items.add(getString(R.string.no_icon))
                for (i in userIconItems.indices) {
                    val iconItem = userIconItems[i]
                    items.add(iconItem.second)
                    if (equals(lastUserIcon, iconItem.first)) {
                        lastUserIconIndex = i
                    }
                }
                iconView.setItems(items)
                iconView.setVisibility(View.VISIBLE)
                iconView.setSelection(lastUserIconIndex + 1)
            } else {
                iconView.setVisibility(View.GONE)
            }
            var needPassword = false
            val chan = get(this.chanName)
            val board =
                chan.configuration.safe().obtainBoard(
                    this.chanName,
                )
            if (board.allowDeleting) {
                val deleting = chan.configuration.safe().obtainDeleting(this.chanName)
                needPassword = deleting != null && deleting.password
            }
            nameView!!.setVisibility(if (posting.allowName) View.VISIBLE else View.GONE)
            emailView!!.setVisibility(if (posting.allowEmail) View.VISIBLE else View.GONE)
            passwordView!!.setVisibility(if (needPassword) View.VISIBLE else View.GONE)
            subjectView!!.setVisibility(if (posting.allowSubject) View.VISIBLE else View.GONE)
            sageCheckBox!!.setVisibility(if (posting.optionSage) View.VISIBLE else View.GONE)
            spoilerCheckBox!!.setVisibility(if (posting.optionSpoiler) View.VISIBLE else View.GONE)
            originalPosterCheckBox!!.setVisibility(if (posting.optionOriginalPoster) View.VISIBLE else View.GONE)
            checkBoxParent!!.setVisibility(
                if (posting.optionSage || posting.optionSpoiler || posting.optionOriginalPoster) {
                    View.VISIBLE
                } else {
                    View.GONE
                },
            )
            var showPersonalDataBlock = !isHidePersonalData
            if (showPersonalDataBlock) {
                showPersonalDataBlock = posting.allowName ||
                    posting.allowEmail ||
                    needPassword ||
                    userIconItems != null
            }
            personalDataBlock!!.setVisibility(if (showPersonalDataBlock) View.VISIBLE else View.GONE)
            commentEditWatcher!!.updateConfiguration(postingConfiguration)
        }
        if (attachmentOptions || attachmentCount) {
            if (attachmentOptions) {
                attachmentRatingItems =
                    if (posting.attachmentRatings.size > 0) posting.attachmentRatings else null
            }
            if (attachmentCount) {
                if (attachments.size > posting.attachmentCount) {
                    attachments.subList(posting.attachmentCount, attachments.size).clear()
                }
            }
            invalidateAttachments(attachmentCount)
            if (attachmentCount) {
                invalidateAttach()
            }
        }
    }

    private fun compareListOfPairs(
        first: List<Pair<String, String>>,
        second: List<Pair<String, String>>,
    ): Boolean {
        if (first.size != second.size) {
            return false
        }
        for (i in first.indices) {
            if (!equals(first.get(i).first, first.get(i).second) ||
                !equals(first.get(i).second, first.get(i).second)
            ) {
                return false
            }
        }
        return false
    }

    private fun updatePostingConfigurationIfNeeded() {
        val chan = get(this.chanName)
        val oldPosting = postingConfiguration
        var newPosting =
            chan.configuration
                .safe()
                .obtainPosting(this.boardName, this.threadNumber == null)
        if (newPosting == null) {
            allowPosting = false
            newPosting = Posting()
        } else {
            allowPosting =
                chan.configuration
                    .safe()
                    .obtainBoard(this.boardName)
                    .allowPosting
        }
        val views =
            oldPosting.allowName != newPosting.allowName ||
                oldPosting.allowEmail != newPosting.allowEmail ||
                oldPosting.allowTripcode != newPosting.allowTripcode ||
                oldPosting.allowSubject != newPosting.allowSubject ||
                oldPosting.optionSage != newPosting.optionSage ||
                oldPosting.optionSpoiler != newPosting.optionSpoiler ||
                oldPosting.optionOriginalPoster != newPosting.optionOriginalPoster ||
                oldPosting.maxCommentLength != newPosting.maxCommentLength ||
                !equals(
                    oldPosting.maxCommentLengthEncoding,
                    newPosting.maxCommentLengthEncoding,
                ) ||
                !compareListOfPairs(oldPosting.userIcons, newPosting.userIcons)
        val attachmentOptions =
            oldPosting.attachmentSpoiler != newPosting.attachmentSpoiler ||
                !compareListOfPairs(oldPosting.attachmentRatings, newPosting.attachmentRatings)
        val attachmentCount = oldPosting.attachmentCount != newPosting.attachmentCount
        if (views || attachmentOptions || attachmentCount) {
            postingConfiguration = newPosting
            updatePostingConfiguration(views, attachmentOptions, attachmentCount)
            resizeComment(true)
        }
    }

    private val userIcon: String?
        get() {
            val userIconItems = userIconItems ?: return null
            val position = iconView!!.getSelectedItemPosition() - 1
            if (position >= 0 && position < userIconItems.size) {
                return userIconItems[position].first
            }
            return null
        }

    public override fun onCreateOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        // The toolbar over a sheet is the page's, so the form adds nothing to it; see buildAttachButton
        if (sheet) {
            return
        }
        menu
            .add(0, R.id.menu_attach, 0, R.string.attach)
            .setIcon((requireActivity() as FragmentHandler).getActionBarIcon(R.attr.iconActionAttach))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    public override fun onPrepareOptionsMenu(
        menu: Menu,
        primary: Boolean,
    ) {
        menu
            .findItem(R.id.menu_attach)
            ?.setVisible(attachments.size < postingConfiguration.attachmentCount)
    }

    private fun handleMimeTypeGroup(
        list: ArrayList<String>,
        mimeTypes: MutableCollection<String>,
        mimeTypeGroup: String,
    ) {
        val allSubMimeTypes = mimeTypeGroup + "*"
        if (mimeTypes.contains(allSubMimeTypes)) {
            list.add(allSubMimeTypes)
        }
        for (mimeType in mimeTypes) {
            if (mimeType.startsWith(mimeTypeGroup) && allSubMimeTypes != mimeType) {
                list.add(mimeType)
            }
        }
    }

    private fun buildMimeTypeList(mimeTypes: MutableCollection<String>): ArrayList<String> {
        val list = ArrayList<String>()
        handleMimeTypeGroup(list, mimeTypes, "image/")
        handleMimeTypeGroup(list, mimeTypes, "video/")
        handleMimeTypeGroup(list, mimeTypes, "audio/")
        for (mimeType in mimeTypes) {
            if (!list.contains(mimeType)) {
                list.add(mimeType)
            }
        }
        return list
    }

    public override fun onMenuItemSelected(item: MenuItem): Boolean {
        val switchItemId0 = item.getItemId()
        if (switchItemId0 == R.id.menu_attach) {
            startAttachmentPick()
        }
        return true
    }

    private fun startAttachmentPick() {
        // SHOW_ADVANCED to show folder navigation

        val intent =
            Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
        val mimeTypes = buildMimeTypeList(postingConfiguration.attachmentMimeTypes)
        if (mimeTypes.size >= 2) {
            intent.setType("*/*")
            intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                CommonUtils.toArray(mimeTypes, String::class.java),
            )
        } else if (mimeTypes.size == 1) {
            intent.setType(mimeTypes.get(0))
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        try {
            attachLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            show(R.string.unknown_address)
        }
    }

    private fun updateFocusButtons(commentFocused: Boolean) {
        val textFormatView = textFormatView!!
        for (i in 0..<textFormatView.getChildCount()) {
            textFormatView.getChildAt(i).setClickable(commentFocused)
        }
    }

    private val formatButtonClickListener: View.OnClickListener =
        object : View.OnClickListener {
            override fun onClick(v: View) {
                val what = v.getTag() as Int
                when (what) {
                    ChanMarkup.TAG_QUOTE -> {
                        formatQuote()
                    }

                    else -> {
                        commentEditor!!.formatSelectedText(commentView!!, what)
                    }
                }
                val inputMethodManager =
                    requireContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(commentView, 0)
                }
            }
        }

    private fun updateSendButtonState() {
        sendButton!!.setEnabled(sendButtonEnabled && captchaState != null && captchaState != ReadCaptchaTask.CaptchaState.NEED_LOAD)
    }

    private fun getTextIfVisible(editText: EditText): String? =
        if (editText.getVisibility() == View.VISIBLE) {
            nullIfEmpty(
                editText.getText().toString(),
            )
        } else {
            null
        }

    private fun isCheckedIfVisible(checkBox: CheckBox): Boolean = checkBox.getVisibility() == View.VISIBLE && checkBox.isChecked()

    private fun executeSendPost() {
        val postingBinder = this.postingBinder ?: return
        val subject = getTextIfVisible(subjectView!!)
        val comment = getTextIfVisible(commentView!!)
        val name = getTextIfVisible(nameView!!)
        val email = getTextIfVisible(emailView!!)
        var password = getTextIfVisible(passwordView!!)
        if (password == null) {
            password = getPassword(get(this.chanName))
        }
        val optionSage = isCheckedIfVisible(sageCheckBox!!)
        val optionSpoiler = isCheckedIfVisible(spoilerCheckBox!!)
        val optionOriginalPoster = isCheckedIfVisible(originalPosterCheckBox!!)
        val userIcon = if (iconView!!.getVisibility() == View.VISIBLE) this.userIcon else null
        val array = ArrayList<SendPostData.Attachment>()
        val draftsStorage = getInstance()
        val attachmentRatingItems = this.attachmentRatingItems
        for (i in attachments.indices) {
            val data = attachments.get(i)
            var rating = data.rating
            if (rating != null && attachmentRatingItems != null) {
                var found = false
                for (pair in attachmentRatingItems) {
                    if (rating == pair.first) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    rating = null
                }
            } else {
                rating = null
            }
            if (attachmentRatingItems != null && rating == null) {
                rating = attachmentRatingItems[0].first
            }
            val fileHolder = draftsStorage.getAttachmentDraftFileHolder(data.hash)
            if (fileHolder != null) {
                array.add(
                    SendPostData.Attachment(
                        fileHolder,
                        if (data.optionCustomName && !data.optionRemoveFileName) data.newname else data.name,
                        rating,
                        data.optionUniqueHash,
                        data.optionRemoveMetadata,
                        data.optionRemoveFileName,
                        postingConfiguration.attachmentSpoiler && data.optionSpoiler,
                        data.reencoding,
                    ),
                )
            }
        }
        var attachments: Array<SendPostData.Attachment?>? = null
        if (array.size > 0) {
            @Suppress("UNCHECKED_CAST")
            attachments = array.toTypedArray() as Array<SendPostData.Attachment?>
        }
        val captchaType = if (loadedCaptchaType != null) loadedCaptchaType else this.captchaType
        var captchaData = this.captchaData
        if (captchaData != null) {
            captchaData = captchaData.copy()
            captchaData.put(CaptchaData.INPUT, captchaForm!!.input)
        }
        val captchaNeedLoad =
            captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD ||
                captchaState == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING
        val data =
            SendPostData(
                this.boardName,
                this.threadNumber,
                subject,
                comment,
                name,
                email,
                password,
                attachments,
                optionSage,
                optionSpoiler,
                optionOriginalPoster,
                userIcon,
                captchaType,
                captchaData,
                captchaNeedLoad,
                15000,
                45000,
            )
        getInstance().store(obtainPostDraft())
        allowDialog = false
        if (postingBinder.executeSendPost(this.chanName, data)) {
            sendButtonEnabled = false
            updateSendButtonState()
            progressDialog?.dismiss()
            progressDialog = null
            onSendPostMinimize()
        } else {
            allowDialog = true
        }
    }

    private var progressDialog: ProgressDialog? = null

    private fun onSendPostCancel() {
        progressDialog = null
        postingBinder!!.cancelSendPost(this.chanName, this.boardName, this.threadNumber)
    }

    private fun onSendPostMinimize() {
        progressDialog = null
        leave()
    }

    private fun dismissSendPost() {
        progressDialog?.dismiss()
        progressDialog = null
        sendButtonEnabled = true
        if (sendButton != null) {
            updateSendButtonState()
        }
    }

    private val postingCallback: PostingService.Callback =
        object : PostingService.Callback {
            override fun onState(
                progressMode: Boolean,
                progressState: ProgressState,
                attachmentIndex: Int,
                attachmentsCount: Int,
            ) {
                if (allowDialog && progressDialog == null) {
                    val progressDialog =
                        ProgressDialog(requireContext(), if (progressMode) "%1\$d / %2\$d kB" else null)
                    this@PostingFragment.progressDialog = progressDialog
                    progressDialog.setOnCancelListener(DialogInterface.OnCancelListener { d: DialogInterface? -> onSendPostCancel() })
                    progressDialog.setButton(
                        DialogInterface.BUTTON_POSITIVE,
                        getString(R.string.minimize),
                        DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> onSendPostMinimize() },
                    )
                    progressDialog.setButton(
                        DialogInterface.BUTTON_NEGATIVE,
                        getString(android.R.string.cancel),
                        DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> onSendPostCancel() },
                    )
                    progressDialog.show()
                }
                val progressDialog = progressDialog ?: return
                when (progressState) {
                    ProgressState.CONNECTING -> {
                        progressDialog.setMax(1)
                        progressDialog.setIndeterminate(true)
                        progressDialog.setMessage(getString(R.string.sending__ellipsis))
                    }

                    ProgressState.SENDING -> {
                        progressDialog.setIndeterminate(false)
                        if (progressMode) {
                            progressDialog.setMessage(
                                getString(
                                    R.string.sending_number_of_number__ellipsis_format,
                                    attachmentIndex + 1,
                                    attachmentsCount,
                                ),
                            )
                        } else {
                            progressDialog.setMessage(getString(R.string.sending__ellipsis))
                        }
                    }

                    ProgressState.PROCESSING -> {
                        progressDialog.setIndeterminate(false)
                        progressDialog.setMessage(getString(R.string.processing_data__ellipsis))
                    }
                }
            }

            override fun onProgress(
                progress: Long,
                progressMax: Long,
            ) {
                val progressDialog = progressDialog ?: return
                progressDialog.setMax((progressMax / 1000).toInt())
                progressDialog.setValue((progress / 1000).toInt())
            }

            override fun onStop(success: Boolean) {
                dismissSendPost()
                if (success) {
                    sendSuccess = true
                    if (isResumed()) {
                        leave()
                    }
                }
            }
        }

    fun handleFailResult(failResult: FailResult) {
        if (isResumed()) {
            if (failResult.extra != null) {
                show(
                    failResult.errorItem.toString(),
                    null,
                    ClickableToast.Button(
                        R.string.details,
                        false,
                        whileOnScreen {
                            SendPostFailDetailsDialog(failResult.extra)
                                .show(getChildFragmentManager(), null)
                        },
                    ),
                )
            } else {
                show(failResult.errorItem)
            }
            if (failResult.errorItem.httpResponseCode == 0 && !failResult.keepCaptcha) {
                refreshCaptcha(false, !failResult.captchaError, true)
            }
            updatePostingConfigurationIfNeeded()
        } else {
            this.failResult = failResult
        }
    }

    private fun refreshCaptcha(
        forceCaptcha: Boolean,
        mayShowLoadButton: Boolean,
        restart: Boolean,
    ) {
        val allowSolveAutomatically =
            !forceCaptcha ||
                captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING
        captchaState = null
        loadedCaptchaType = null
        captcha = null
        updateSendButtonState()
        captchaForm!!.showLoading()
        updateCaptchaBlockLayout()
        val viewModel = ViewModelProvider(this).get<CaptchaViewModel>(CaptchaViewModel::class.java)
        if (restart || !viewModel.hasTaskOrValue()) {
            val chan = get(this.chanName)
            val captchaPass = if (forceCaptcha) null else getCaptchaPass(chan)
            val task =
                ReadCaptchaTask(
                    viewModel.callback,
                    null,
                    captchaType,
                    null,
                    captchaPass,
                    mayShowLoadButton,
                    allowSolveAutomatically,
                    chan,
                    this.boardName,
                    this.threadNumber,
                ).apply { forPosting = true }
            task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
            viewModel.attach(task)
        }
    }

    class CaptchaViewModel : TaskViewModel.Proxy<ReadCaptchaTask, ReadCaptchaTask.Callback>()

    /**
     * Resolve the IP the forum currently sees and warn, once per posting screen, when the ban
     * log already holds an active ban against it -- a post from that IP would only be refused
     * again, so it is worth changing before spending a captcha on it. Runs off the UI thread and
     * only reports a hit, so a miss (or no network) is silent.
     */
    private fun checkVisibleIpBan(chan: Chan) {
        val viewModel = ViewModelProvider(this).get(BanWarningViewModel::class.java)
        // Resolving the IP is a network round-trip: do it once per screen, not on every
        // open of the form. The flag lives on the view model, so it also survives a rotation.
        if (viewModel.checked) {
            return
        }
        viewModel.checked = true
        val task = BanWarningTask(viewModel, chan, this.boardName)
        task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        viewModel.attach(task)
    }

    /**
     * Open the forum's settings and reveal the Proxy row, the same scroll-and-pulse a search hit
     * gets: the [PreferenceFragment.EXTRA_REVEAL_TITLE] argument names the row by its title, so the
     * screen flashes it once it has built. This is where the visible IP gets changed.
     */
    private fun openForumProxySettings() {
        val fragment = ChanFragment(chanName)
        val arguments = fragment.arguments ?: Bundle().also { fragment.arguments = it }
        arguments.putString(PreferenceFragment.EXTRA_REVEAL_TITLE, getString(R.string.proxy))
        (requireActivity() as FragmentHandler).pushFragment(fragment)
    }

    /**
     * Run the command the user flagged to have this forum seen at another IP (see
     * [VisibleIpCommand]), and let the ban check say its piece again afterwards: the ban that
     * prompted this may well not apply to the new IP.
     *
     * Tracked with the draft commands, so leaving the screen drops it -- unlike the provider request
     * this replaced, which was held in a view model and survived a rotation. A command run belongs to
     * the screen that started it here as everywhere else, and the toast that offers this comes back on
     * the next check anyway.
     */
    private fun changeVisibleIp() {
        if (visibleIpCommandRun?.isFinished == false) {
            return
        }
        val dialog = ProgressDialog(requireContext(), null)
        visibleIpCommandDialog = dialog
        dialog.setMessage(getString(R.string.loading__ellipsis))
        dialog.setOnCancelListener {
            visibleIpCommandDialog = null
            visibleIpCommandRun?.cancel()
            visibleIpCommandRun = null
        }
        dialog.show()
        val run =
            VisibleIpCommand.run(get(chanName)) { result ->
                visibleIpCommandRun = null
                visibleIpCommandDialog?.dismiss()
                visibleIpCommandDialog = null
                // The result waits on the pooled connections being dropped, so the screen may have gone
                // in between -- and a view model asked for by a fragment that far gone throws
                if (commentView == null) {
                    return@run
                }
                when (result) {
                    is CommandRunner.AppResult.Success -> {
                        ClickableToast.show(result.message ?: getString(R.string.visible_ip_changed))
                        val banViewModel = ViewModelProvider(this).get(BanWarningViewModel::class.java)
                        banViewModel.checked = false
                        checkVisibleIpBan(get(chanName))
                    }

                    is CommandRunner.AppResult.Failure -> {
                        show(getString(R.string.command_failed__format, result.message))
                    }
                }
            }
        visibleIpCommandRun = run
        if (run == null) {
            // Deleted or unflagged since the toast offered this
            dialog.dismiss()
            visibleIpCommandDialog = null
            openForumProxySettings()
        } else {
            trackCommandRun(run)
        }
    }

    class BanWarningViewModel : TaskViewModel<BanWarningTask, Boolean>() {
        var checked = false
    }

    class BanWarningTask(
        private val viewModel: BanWarningViewModel,
        private val chan: Chan,
        private val boardName: String?,
    ) : HttpHolderTask<Unit, Boolean>(chan) {
        override fun run(holder: HttpHolder): Boolean {
            val chanName = chan.name ?: return false
            val ip = VisibleIp.resolve(chan, holder)?.ip ?: return false
            return ChanDatabase.getInstance().hasActiveBanForAddress(chanName, boardName, ip)
        }

        override fun onComplete(result: Boolean) {
            viewModel.handleResult(result)
        }
    }

    // The captcha block is hidden with a captcha pass or no captcha at all, and showing or hiding it
    // changes the height left for the comment field
    private fun updateCaptchaBlockLayout() {
        if (footerContainer == null) {
            return
        }
        resizeComment(true)
    }

    override fun onReadCaptchaSuccess(result: ReadCaptchaTask.Result) {
        showCaptcha(
            result.captchaState!!,
            result.captchaData,
            result.captchaType,
            result.input,
            result.validity,
            CaptchaForm.Captcha(result.image, captchaLifetimeSeconds),
            result.large,
            result.blackAndWhite,
        )
        updatePostingConfigurationIfNeeded()
    }

    override fun onReadCaptchaError(errorItem: ErrorItem) {
        show(errorItem)
        captchaForm!!.showError()
        updateCaptchaBlockLayout()
        updatePostingConfigurationIfNeeded()
    }

    private fun showCaptcha(
        captchaState: ReadCaptchaTask.CaptchaState,
        captchaData: CaptchaData?,
        captchaType: String?,
        input: ChanConfiguration.Captcha.Input?,
        validity: ChanConfiguration.Captcha.Validity?,
        captcha: CaptchaForm.Captcha?,
        large: Boolean,
        blackAndWhite: Boolean,
    ) {
        var input = input
        var validity = validity
        this.captchaState = captchaState
        this.captchaData = captchaData
        this.captcha = captcha
        captchaLarge = large
        captchaBlackAndWhite = blackAndWhite
        loadedCaptchaType = captchaType
        if (captchaType != null) {
            val captchaConfiguration =
                get(this.chanName)
                    .configuration
                    .safe()
                    .obtainCaptcha(captchaType)
            if (input == null) {
                input = captchaConfiguration.input
            }
            if (validity == null) {
                validity = captchaConfiguration.validity
            }
        }
        loadedCaptchaInput = input
        loadedCaptchaValidity = validity
        val invertColors =
            blackAndWhite && !isLight(getColor(requireContext(), android.R.attr.colorBackground))
        captchaForm!!.showCaptcha(captchaState, input, captcha, large, invertColors)
        updateCaptchaBlockLayout()
        val scrollView = scrollView!!
        if (scrollView.getScrollY() + viewportHeight(scrollView) >=
            scrollView
                .getChildAt(0)
                .getHeight()
        ) {
            scrollView.post(
                Runnable {
                    val currentScrollView = this.scrollView ?: return@Runnable
                    currentScrollView.setScrollY(
                        max(
                            currentScrollView.getChildAt(0).getHeight() -
                                viewportHeight(currentScrollView),
                            0,
                        ),
                    )
                },
            )
        }
        updateSendButtonState()
    }

    private val attachLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartActivityForResult(),
        ) { result ->
            val data: Intent? = result.getData()
            if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                val uris = LinkedHashSet<Uri>()
                val dataUri = data.getData()
                if (dataUri != null) {
                    uris.add(dataUri)
                }
                val clipData = data.getClipData()
                if (clipData != null) {
                    for (i in 0..<clipData.getItemCount()) {
                        val item = clipData.getItemAt(i)
                        val uri = item.getUri()
                        if (uri != null) {
                            uris.add(uri)
                        }
                    }
                }

                val attachmentsToAdd = ArrayList<Pair<String?, String?>>()
                for (uri in uris) {
                    val fileHolder = obtain(uri)
                    if (fileHolder != null) {
                        val hash = getInstance().store(fileHolder)
                        if (hash != null) {
                            attachmentsToAdd.add(Pair<String?, String?>(hash, fileHolder.name))
                        }
                    }
                }
                handleAttachmentsToAdd(attachmentsToAdd, uris.size)
            }
        }

    private fun handleAttachmentsToAdd(
        attachmentsToAdd: ArrayList<Pair<String?, String?>>,
        addedCount: Int,
    ) {
        val oldCount = attachments.size
        for (attachmentToAdd in attachmentsToAdd) {
            if (attachments.size < postingConfiguration.attachmentCount) {
                addAttachment(attachmentToAdd.first, attachmentToAdd.second)
            }
        }
        val newCount = attachments.size - oldCount
        if (newCount > 0) {
            getInstance().store(obtainPostDraft())
        }
        val errorCount = addedCount - newCount
        if (errorCount > 0) {
            show(
                getResources().getQuantityString(
                    R.plurals
                        .number_files_havent_been_attached__format,
                    errorCount,
                    errorCount,
                ),
            )
        }
    }

    override fun getAttachmentHolder(index: Int): AttachmentHolder? = if (index >= 0 && index < attachments.size) attachments.get(index) else null

    override fun getAttachmentRatingItems(): List<Pair<String, String>>? = attachmentRatingItems

    override fun getPostingConfiguration(): Posting = postingConfiguration

    private val attachmentOptionsListener =
        View.OnClickListener { v: View ->
            val holder = v.getTag() as AttachmentHolder?
            val attachmentIndex = attachments.indexOf(holder)
            AttachmentOptionsDialog(attachmentIndex).show(
                getChildFragmentManager(),
                AttachmentOptionsDialog.TAG,
            )
        }

    private val attachmentWarningListener =
        View.OnClickListener { v: View ->
            val holder = v.getTag() as AttachmentHolder?
            val attachmentIndex = attachments.indexOf(holder)
            AttachmentWarningDialog(attachmentIndex).show(
                getChildFragmentManager(),
                AttachmentWarningDialog.TAG,
            )
        }

    private val attachmentRatingListener =
        View.OnClickListener { v: View ->
            val holder = v.getTag() as AttachmentHolder?
            val attachmentIndex = attachments.indexOf(holder)
            AttachmentRatingDialog(attachmentIndex).show(
                getChildFragmentManager(),
                AttachmentRatingDialog.TAG,
            )
        }

    private val attachmentRemoveListener =
        View.OnClickListener { v: View ->
            val holder = v.getTag() as AttachmentHolder
            if (attachments.remove(holder)) {
                if (attachmentColumnCount == 1) {
                    attachmentContainer!!.removeView(holder.view)
                } else {
                    invalidateAttachments(true)
                }
                invalidateAttach()
                resizeComment(true)
                getInstance().store(obtainPostDraft())
            }
        }

    private val attachmentDragStartListener =
        View.OnLongClickListener { v: View ->
            val holder = v.getTag() as? AttachmentHolder
            if (holder == null || attachments.size < 2) {
                false
            } else {
                holder.view.startDragAndDrop(
                    ClipData.newPlainText("", ""),
                    View.DragShadowBuilder(holder.view),
                    holder,
                    0,
                )
                true
            }
        }

    private val attachmentDragListener =
        View.OnDragListener { _: View, event: DragEvent ->
            when (event.getAction()) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    attachmentReordered = false
                    (event.getLocalState() as? AttachmentHolder)?.view?.setAlpha(0.4f)
                    true
                }

                DragEvent.ACTION_DRAG_LOCATION -> {
                    val holder = event.getLocalState() as? AttachmentHolder
                    if (holder != null) {
                        val from = attachments.indexOf(holder)
                        val to = findAttachmentIndexAt(event.getX(), event.getY())
                        if (from >= 0 && to >= 0 && to != from) {
                            attachments.removeAt(from)
                            attachments.add(to, holder)
                            invalidateAttachments(true)
                            attachmentReordered = true
                        }
                    }
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    for (holder in attachments) {
                        holder.view.setAlpha(1f)
                    }
                    if (attachmentReordered) {
                        attachmentReordered = false
                        getInstance().store(obtainPostDraft())
                    }
                    true
                }

                else -> {
                    true
                }
            }
        }

    private fun findAttachmentIndexAt(
        x: Float,
        y: Float,
    ): Int {
        val container = attachmentContainer ?: return -1
        val rect = Rect()
        for (i in attachments.indices) {
            val view = attachments[i].view
            if (view.getParent() == null) {
                continue
            }
            rect.set(0, 0, view.getWidth(), view.getHeight())
            container.offsetDescendantRectToMyCoords(view, rect)
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom) {
                return i
            }
        }
        return -1
    }

    private fun invalidateAttachments(clearContainer: Boolean) {
        if (clearContainer) {
            attachmentContainer!!.removeAllViews()
        }
        for (i in attachments.indices) {
            val holder = attachments.get(i)
            if (clearContainer) {
                removeFromParent(holder.view)
                addAttachmentViewToContainer(holder.view, i)
            }
            updateAttachmentConfiguration(holder)
        }
    }

    private fun addAttachmentViewToContainer(
        attachmentView: View,
        position: Int,
    ) {
        val attachmentContainer = attachmentContainer!!
        var layoutParams = attachmentView.getLayoutParams() as LinearLayout.LayoutParams
        if (attachmentColumnCount == 1) {
            layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            layoutParams.weight = 0f
            layoutParams.leftMargin = 0
            attachmentContainer.addView(attachmentView)
        } else {
            val density = obtainDensity(this)
            val paddingDp = 4f
            layoutParams.width = 0
            layoutParams.weight = 1f
            layoutParams.leftMargin = (paddingDp * density).toInt()
            val row = position / attachmentColumnCount
            val column = position % attachmentColumnCount
            val subcontainer: LinearLayout
            val placeholder: View
            if (column == 0) {
                subcontainer = LinearLayout(requireContext())
                attachmentContainer.addView(
                    subcontainer,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                subcontainer.setOrientation(LinearLayout.HORIZONTAL)
                placeholder = View(requireContext())
                subcontainer.addView(placeholder, 0, LinearLayout.LayoutParams.MATCH_PARENT)
                subcontainer.setPadding(0, 0, (paddingDp * density).toInt(), 0)
                subcontainer.setGravity(Gravity.BOTTOM)
            } else {
                subcontainer = attachmentContainer.getChildAt(row) as LinearLayout
                placeholder = subcontainer.getChildAt(subcontainer.getChildCount() - 1)
            }
            subcontainer.addView(attachmentView, column)
            layoutParams = (placeholder.getLayoutParams() as LinearLayout.LayoutParams)
            layoutParams.weight = (attachmentColumnCount - column - 1).toFloat()
            layoutParams.leftMargin = (paddingDp * density * layoutParams.weight).toInt()
            placeholder.setVisibility(if (attachmentColumnCount == column + 1) View.GONE else View.VISIBLE)
        }
    }

    private fun addNewAttachment(): AttachmentHolder {
        val density = obtainDensity(getResources())
        val minHeight = (48f * density).toInt()
        val view = FrameLayout(attachmentContainer!!.getContext())
        view.setLayoutParams(
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minHeight,
            ),
        )
        setNewMargin(view, 0, (4f * density).toInt(), 0, 0)
        view.setBackgroundColor(-0x1000000)
        view.setForeground(
            RoundedCornersDrawable(
                (2f * density).toInt(),
                getTheme(view.getContext()).window,
            ),
        )

        addAttachmentViewToContainer(view, attachments.size)
        val imageView = ImageView(view.getContext())
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
        imageView.setBackground(TransparentTileDrawable(imageView.getContext(), true))
        imageView.setVisibility(View.GONE)
        view.addView(
            imageView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        val overlay = View(view.getContext())
        overlay.setBackgroundColor(getColor(overlay.getContext(), R.attr.colorBlockBackground))
        view.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, minHeight)
        (overlay.getLayoutParams() as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM
        val options = View(view.getContext())
        setSelectableItemBackground(options)
        options.setOnClickListener(attachmentOptionsListener)
        options.setOnLongClickListener(attachmentDragStartListener)
        view.addView(
            options,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // Marks a preview that stands for something to play, the way a video or audio thumbnail is
        // marked in a post: the same icons over the same dim. Not a target of its own — the whole
        // attachment keeps opening the options, where the file itself can be played.
        val previewBadge = ImageView(view.getContext())
        previewBadge.setScaleType(ImageView.ScaleType.CENTER)
        previewBadge.setVisibility(View.GONE)
        view.addView(previewBadge, previewAreaLayoutParams(minHeight))

        val controls = LinearLayout(view.getContext())
        controls.setOrientation(LinearLayout.HORIZONTAL)
        view.addView(controls, FrameLayout.LayoutParams.MATCH_PARENT, minHeight)
        (controls.getLayoutParams() as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM
        controls.setPaddingRelative((8f * density).toInt(), 0, 0, 0)

        val textLayout = LinearLayout(controls.getContext())
        textLayout.setOrientation(LinearLayout.VERTICAL)
        textLayout.setGravity(Gravity.CENTER_VERTICAL)
        controls.addView(textLayout, 0, LinearLayout.LayoutParams.MATCH_PARENT)
        (textLayout.getLayoutParams() as LinearLayout.LayoutParams).weight = 1f
        textLayout.setPaddingRelative((4f * density).toInt(), 0, (8f * density).toInt(), 0)
        val fileName = TextView(controls.getContext())
        textLayout.addView(
            fileName,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        TextViewCompat.setTextAppearance(
            fileName,
            getResourceId(fileName.getContext(), android.R.attr.textAppearanceListItem, 0),
        )
        fileName.setSingleLine(true)
        fileName.setEllipsize(TextUtils.TruncateAt.END)
        setTextSizeScaled(fileName, 12)
        fileName.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

        val fileSize = TextView(controls.getContext())
        textLayout.addView(
            fileSize,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        TextViewCompat.setTextAppearance(
            fileSize,
            getResourceId(fileSize.getContext(), android.R.attr.textAppearanceListItem, 0),
        )
        fileSize.setSingleLine(true)
        fileSize.setEllipsize(TextUtils.TruncateAt.END)
        setTextSizeScaled(fileSize, 12)

        val warningButton: View =
            addAttachmentButton(
                controls,
                minHeight,
                R.attr.iconButtonWarning,
                attachmentWarningListener,
            )
        val ratingButton: View =
            addAttachmentButton(
                controls,
                minHeight,
                R.attr.iconButtonRating,
                attachmentRatingListener,
            )
        val removeButton: View =
            addAttachmentButton(
                controls,
                minHeight,
                R.attr.iconButtonCancel,
                attachmentRemoveListener,
            )

        val holder =
            AttachmentHolder(
                view,
                fileName,
                fileSize,
                imageView,
                warningButton,
                ratingButton,
                previewBadge,
            )
        warningButton.setTag(holder)
        ratingButton.setTag(holder)
        removeButton.setTag(holder)
        options.setTag(holder)
        attachments.add(holder)
        invalidateAttach()
        resizeComment(true)
        return holder
    }

    private fun addAttachment(
        hash: String?,
        name: String?,
        newname: String? = configuredFileNewname,
        rating: String? = null,
        optionUniqueHash: Boolean = isAlwaysUniqueHash,
        optionRemoveMetadata: Boolean = isAlwaysClearMetadata,
        optionRemoveFileName: Boolean = isAlwaysRemoveFilename,
        optionSpoiler: Boolean = false,
        reencoding: Reencoding? = null,
        optionCustomName: Boolean = isAlwaysRenameFilename,
    ) {
        val fileHolder = getInstance().getAttachmentDraftFileHolder(hash)
        val jpegData = if (fileHolder != null) fileHolder.jpegData else null
        val pngData = if (fileHolder != null) fileHolder.pngData else null
        val holder = addNewAttachment()
        holder.hash = hash
        holder.name = name
        holder.newname = newname
        holder.rating = rating
        holder.optionUniqueHash = optionUniqueHash
        holder.optionRemoveMetadata = optionRemoveMetadata
        holder.optionRemoveFileName = optionRemoveFileName
        holder.optionSpoiler = optionSpoiler
        holder.reencoding = reencoding
        holder.optionCustomName = optionCustomName
        holder.fileName.setText(name)
        val size = if (fileHolder != null) fileHolder.size else 0
        var fileSize = formatFileSize(size.toLong(), false)
        var bitmap: Bitmap? = null
        val metrics = getResources().getDisplayMetrics()
        val targetImageSize = max(metrics.widthPixels, metrics.heightPixels)
        val video = DraftAttachmentMedia.isVideo(name)
        val audio = DraftAttachmentMedia.isAudio(name)
        if (fileHolder != null) {
            if (fileHolder.isImage) {
                try {
                    bitmap = fileHolder.readImageBitmap(targetImageSize, false, false)
                } catch (e: OutOfMemoryError) {
                    // Ignore
                }
                fileSize += " " + fileHolder.imageWidth + '×' + fileHolder.imageHeight
            }
            // The type is taken from the draft's own name, not from the stored file's: the file is named
            // after its content hash, so it has no extension of its own to go by, and this check used to
            // match nothing at all — which is why videos went without a preview.
            if (bitmap == null && (video || audio)) {
                val mediaPreview = readMediaPreview(fileHolder, video, targetImageSize)
                bitmap = mediaPreview.bitmap
                fileSize += mediaPreview.summary
            }
        }
        bitmap?.let { applyAttachmentPreview(holder, it, video, audio) }
        holder.fileSize.setText(fileSize)
        if ((jpegData == null || jpegData.exifData == null) && (pngData == null || !pngData.hasMetadata)) {
            holder.warningButton.setVisibility(View.GONE)
        }
        updateAttachmentConfiguration(holder)
    }

    /**
     * Gives the attachment its preview box: the image, the frame or the cover art, marked with what it
     * stands for. Called only when there is something to show — a music file with no cover art stays the
     * bare row it was, and is played from the options dialog like every other attachment.
     */
    private fun applyAttachmentPreview(
        holder: AttachmentHolder,
        previewBitmap: Bitmap,
        video: Boolean,
        audio: Boolean,
    ) {
        holder.view.getLayoutParams().height = (128f * obtainDensity(this)).toInt()
        holder.imageView.setVisibility(View.VISIBLE)
        holder.imageView.setImageBitmap(previewBitmap)
        if (video || audio) {
            holder.previewBadge.setImageDrawable(
                getDrawable(
                    holder.previewBadge.getContext(),
                    if (video) R.attr.iconAttachmentVideo else R.attr.iconAttachmentAudio,
                    0,
                ),
            )
            holder.previewBadge.setBackgroundColor(AttachmentHolder.PREVIEW_DIM_COLOR)
            holder.previewBadge.setVisibility(View.VISIBLE)
        }
    }

    /** What a video or audio attachment can show of itself. */
    private class MediaPreview(
        val bitmap: Bitmap?,
        /** Appended to the file size: the frame size of a video, and the duration of either. */
        val summary: String,
    )

    private fun readMediaPreview(
        fileHolder: FileHolder,
        video: Boolean,
        targetImageSize: Int,
    ): MediaPreview {
        var bitmap: Bitmap? = null
        var summary = ""
        try {
            MediaMetadataRetriever().use { retriever ->
                fileHolder.openFileDescriptor().use { descriptor ->
                    retriever.setDataSource(descriptor.getFileDescriptor())
                    if (video) {
                        val fullBitmap = retriever.getFrameAtTime(-1)
                        if (fullBitmap != null) {
                            bitmap = reduceBitmapSize(fullBitmap, targetImageSize, true)
                        }
                        val width = extractMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val height = extractMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        if (width > 0 && height > 0) {
                            summary += " " + width + '×' + height
                        }
                    } else {
                        val picture = retriever.getEmbeddedPicture()
                        if (picture != null) {
                            val fullBitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                            if (fullBitmap != null) {
                                bitmap = reduceBitmapSize(fullBitmap, targetImageSize, true)
                            }
                        }
                    }
                    val duration = extractMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (duration > 0) {
                        summary += " " + formatDuration(duration)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
        }
        return MediaPreview(bitmap, summary)
    }

    private fun updateAttachmentConfiguration(holder: AttachmentHolder) {
        val attachmentRatingItems = attachmentRatingItems
        if (attachmentRatingItems != null) {
            if (holder.rating == null) {
                holder.rating = attachmentRatingItems[0].first
            }
            holder.ratingButton.setVisibility(View.VISIBLE)
        } else {
            holder.ratingButton.setVisibility(View.GONE)
        }
    }

    /**
     * Inserts text shared from another app at the cursor, the way a future attachment draft is
     * added to the attachment list.
     */
    private fun insertFutureComment(comment: String) {
        val commentView = commentView ?: return
        val editable = commentView.getText()
        val carriage = commentView.getSelectionEnd().coerceIn(0, editable.length)
        val insert =
            (if (carriage > 0 && editable[carriage - 1] != '\n') "\n" else "") + comment + "\n"
        editable.insert(carriage, insert)
        commentView.setSelection(carriage + insert.length)
        commentView.requestFocus()
    }

    private fun formatQuote() {
        val commentView = commentView!!
        val editable = commentView.getText()
        val text = editable.toString()
        val selectionStart = commentView.getSelectionStart()
        val selectionEnd = commentView.getSelectionEnd()
        val selectedText = text.substring(selectionStart, selectionEnd)
        val oneSymbolBefore = text.substring(max(selectionStart - 1, 0), selectionStart)
        if (selectedText.startsWith(">")) {
            val unQuotedText =
                selectedText.replaceFirst("> ?".toRegex(), "").replace("(\n+)> ?".toRegex(), "$1")
            val diff = selectedText.length - unQuotedText.length
            editable.replace(selectionStart, selectionEnd, unQuotedText)
            commentView.setSelection(selectionStart, selectionEnd - diff)
        } else {
            val firstSymbol =
                if (oneSymbolBefore.length == 0 || oneSymbolBefore == "\n") "" else "\n"
            val quotedText = firstSymbol + "> " + selectedText.replace("(\n+)".toRegex(), "$1> ")
            val diff = quotedText.length - selectedText.length
            editable.replace(selectionStart, selectionEnd, quotedText)
            var newStart = selectionStart + firstSymbol.length
            val newEnd = selectionEnd + diff
            if (newEnd - newStart <= 2) {
                newStart = newEnd
            }
            commentView.setSelection(newStart, newEnd)
        }
    }

    // The scroll view carries the system insets as its own padding (ExpandedLayout, clipToPadding
    // false), so its height is larger than the area content can actually occupy. Filling the raw
    // height instead made an empty form scrollable and pushed the send button off the bottom edge.
    private fun viewportHeight(scrollView: ScrollView): Int {
        val padding = scrollView.getPaddingTop() + scrollView.getPaddingBottom()
        return scrollView.getHeight() - padding
    }

    private fun resizeComment(post: Boolean) {
        val scrollView = scrollView!!
        scrollView.removeCallbacks(resizeComment)
        if (post) {
            scrollView.post(resizeComment)
        } else {
            resizeComment.run()
        }
    }

    private val resizeComment =
        Runnable {
            val scrollView = scrollView ?: return@Runnable
            val commentView = commentView ?: return@Runnable
            if (sheet) {
                // Not this way round in a sheet: the field is the weighted row of the form there, so the
                // room the sheet has and the form is not using is already its (see onViewCreated), and a
                // second pass adding filler height on top of that would only fight the first
                return@Runnable
            }
            val postMain = scrollView.getChildAt(0)
            commentView.setMinLines(4)
            // Drop the filler height added by the previous pass. Without this the field can only
            // grow, so once anything else appears (attachments, captcha, keyboard) the extra height
            // stays and pushes the send button below the bottom of the screen
            commentView.setMinHeight(0)
            val widthMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(postMain.getWidth(), View.MeasureSpec.EXACTLY)
            val heightMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            postMain.measure(widthMeasureSpec, heightMeasureSpec)
            val delta = viewportHeight(scrollView) - postMain.getMeasuredHeight()
            if (delta > 0) {
                commentView.setMinHeight(commentView.getMeasuredHeight() + delta)
            }
        }

    private fun buildCommandsButton(
        parent: FrameLayout,
        density: Float,
    ): ImageView {
        val context = parent.context
        val button = ImageView(context)
        button.setImageResource(R.drawable.ic_command)
        button.imageTintList = ColorStateList.valueOf(getColor(context, android.R.attr.textColorSecondary))
        button.setBackgroundResource(
            getResourceId(context, android.R.attr.selectableItemBackgroundBorderless, 0),
        )
        button.contentDescription = getString(R.string.commands)
        val padding = (8f * density).toInt()
        button.setPadding(padding, padding, padding, padding)
        val size = (40f * density).toInt()
        val params =
            FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                val margin = (2f * density).toInt()
                setMargins(0, 0, margin, margin)
            }
        button.visibility = View.GONE
        button.setOnClickListener { showCommandsPopup(button) }
        parent.addView(button, params)
        return button
    }

    /**
     * The spinner that stands in for the ⌘ button while a command runs: the same corner, the same box
     * and the same tint, so it reads as the button itself being busy rather than as something new
     * appearing next to it.
     */
    private fun buildCommandsProgress(
        parent: FrameLayout,
        density: Float,
    ): View {
        val context = parent.context
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        progress.indeterminateTintList =
            ColorStateList.valueOf(getColor(context, android.R.attr.textColorSecondary))
        // Padding rather than a smaller box: it keeps the spinner concentric with the button it
        // replaces, since ProgressBar scales its drawable into whatever the padding leaves.
        val padding = (10f * density).toInt()
        progress.setPadding(padding, padding, padding, padding)
        val size = (40f * density).toInt()
        val params =
            FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                val margin = (2f * density).toInt()
                setMargins(0, 0, margin, margin)
            }
        progress.visibility = View.GONE
        parent.addView(progress, params)
        return progress
    }

    /**
     * Recomputes which commands apply to the current forum/board and shows or hides the ⌘ button.
     * Both manually-triggered and run-on-send commands are kept: the menu lists them all, but
     * run-on-send ones are shown disabled (they fire automatically from [onSendButtonClick]) so the
     * user can still see that they exist and apply here.
     */
    private fun updateCommandsButton() {
        commentCommands =
            CommandsStorage
                .getInstance()
                .getAvailable(CommandsStorage.UseIn.COMMENT, chanName, boardName)
        updateCommandsViews()
    }

    /**
     * Shows the ⌘ button, or the spinner in its place while a command is running, or neither when no
     * command applies here. Both are driven from one place so a refresh of the available commands
     * can't reveal the button from under the spinner.
     */
    private fun updateCommandsViews() {
        val available = commentCommands.isNotEmpty()
        commandsButton?.visibility = if (available && !commandsRunning) View.VISIBLE else View.GONE
        commandsProgressView?.visibility =
            if (available && commandsRunning) View.VISIBLE else View.GONE
    }

    private fun setCommandsRunning(running: Boolean) {
        commandsRunning = running
        updateCommandsViews()
    }

    private fun showCommandsPopup(anchor: View) {
        CommandsPopup.show(
            anchor,
            commentCommands,
            onRun = { runCommand(it) },
            onEdit = { (requireActivity() as FragmentHandler).pushFragment(CommandsFragment(it.id)) },
        )
    }

    private fun runCommand(command: CommandsStorage.CommandItem) {
        val commentView = commentView ?: return
        commandsProgress.start()
        trackCommandRun(
            CommandRunner.run(
                command,
                commentView.getText().toString(),
                obtainAttachmentDrafts(),
                this.chanName,
                this.threadNumber,
                this.boardName,
            ) { result ->
                // Delivered on the main thread; the view may be gone by the time it arrives. Released
                // before that check, since nothing releases it afterwards.
                commandsProgress.finish()
                val liveCommentView = this.commentView ?: return@run
                when (result) {
                    is CommandRunner.Result.Success -> {
                        val comment = result.comment
                        if (comment != null) {
                            liveCommentView.setText(comment)
                            liveCommentView.setSelection(liveCommentView.getText().length)
                        }
                        result.attachments?.let { applyCommandAttachments(it) }
                        // The comment field stores the draft as it is typed in, but nothing does that
                        // for a command's output, and a rewritten draft is worth keeping.
                        getInstance().store(obtainPostDraft())
                    }

                    is CommandRunner.Result.Failure -> {
                        show(getString(R.string.command_failed__format, result.message))
                    }
                }
            },
        )
    }

    /**
     * Replaces the attached files with what a command returned, in the order it returned them. The
     * views are rebuilt rather than reconciled: a command may have reordered, dropped and added files
     * in one go, and each holder's preview is built from its file anyway.
     *
     * Anything past what the board accepts is dropped with the same message the file picker uses for
     * a file it couldn't attach — the command asked for more than the form can hold, which the user
     * has to know about before sending.
     */
    private fun applyCommandAttachments(attachmentDrafts: List<AttachmentDraft>) {
        attachments.clear()
        attachmentContainer!!.removeAllViews()
        val allowed = attachmentDrafts.take(postingConfiguration.attachmentCount)
        for (attachmentDraft in allowed) {
            addAttachment(
                attachmentDraft.hash,
                attachmentDraft.name,
                attachmentDraft.newname,
                attachmentDraft.rating,
                attachmentDraft.optionUniqueHash,
                attachmentDraft.optionRemoveMetadata,
                attachmentDraft.optionRemoveFileName,
                attachmentDraft.optionSpoiler,
                attachmentDraft.reencoding,
                attachmentDraft.optionCustomName,
            )
        }
        invalidateAttach()
        resizeComment(true)
        val droppedCount = attachmentDrafts.size - allowed.size
        if (droppedCount > 0) {
            show(
                getResources().getQuantityString(
                    R.plurals.number_files_havent_been_attached__format,
                    droppedCount,
                    droppedCount,
                ),
            )
        }
    }

    /**
     * Keeps [run] so [cancelCommandRuns] can reach it, dropping the ones that are over first — a send
     * chain adds an entry per command in it, and the ⌘ button can be tapped again on any run that
     * finishes before the spinner has replaced it.
     */
    private fun trackCommandRun(run: CommandRunner.Run) {
        commandRuns.removeAll { it.isFinished }
        commandRuns.add(run)
    }

    /**
     * Stops the commands still running for this screen. Their results are dropped by the null check on
     * the comment field anyway, so letting them go on would only hold an engine — and this fragment
     * with it — until each one's deadline.
     */
    private fun cancelCommandRuns() {
        for (run in commandRuns) {
            run.cancel()
        }
        commandRuns.clear()
    }

    /**
     * Send-button handler. Runs any commands marked "run on send" against the draft first — in order,
     * each seeing the previous one's output — and only sends once they all succeed. A failing command
     * aborts the send with a toast so nothing is posted half-transformed.
     */
    private fun onSendButtonClick() {
        val commentView = this.commentView
        val commands =
            CommandsStorage
                .getInstance()
                .getAvailable(CommandsStorage.UseIn.COMMENT, chanName, boardName)
                .filter { it.autoRun }
        if (commentView == null || commands.isEmpty()) {
            executeSendPost()
            return
        }
        // Disable the button so the send can't be re-triggered while the chain runs.
        sendButtonEnabled = false
        updateSendButtonState()
        // Held around the whole chain rather than each command in it, so the spinner doesn't blink
        // between two quick commands or restart its delay on each of them.
        commandsProgress.start()
        autoRunChain(commands, 0, commentView.getText().toString())
    }

    /**
     * One link of the send chain. The comment is threaded through the recursion, but the attachments
     * are read back off the form each time instead: a command's list is applied to the views before
     * the next one runs, and the ids it hands out are positions in the list it was given, so the next
     * command has to be given the list the form actually holds.
     */
    private fun autoRunChain(
        commands: List<CommandsStorage.CommandItem>,
        index: Int,
        comment: String,
    ) {
        if (index >= commands.size) {
            commandsProgress.finish()
            sendButtonEnabled = true
            updateSendButtonState()
            executeSendPost()
            return
        }
        trackCommandRun(
            CommandRunner.run(
                commands[index],
                comment,
                obtainAttachmentDrafts(),
                this.chanName,
                this.threadNumber,
                this.boardName,
            ) { result ->
                val liveCommentView = this.commentView
                if (liveCommentView == null) {
                    commandsProgress.finish()
                    sendButtonEnabled = true
                    return@run
                }
                when (result) {
                    is CommandRunner.Result.Success -> {
                        val newComment = result.comment ?: comment
                        liveCommentView.setText(newComment)
                        liveCommentView.setSelection(liveCommentView.getText().length)
                        result.attachments?.let { applyCommandAttachments(it) }
                        // Each link persists its own output rather than the chain persisting once at
                        // the end: a file a command downloaded is only kept by the drafts store for as
                        // long as a draft names it.
                        getInstance().store(obtainPostDraft())
                        // Only the chain's end releases the spinner, so the recursion carries it along.
                        autoRunChain(commands, index + 1, newComment)
                    }

                    is CommandRunner.Result.Failure -> {
                        commandsProgress.finish()
                        show(getString(R.string.command_failed__format, result.message))
                        sendButtonEnabled = true
                        updateSendButtonState()
                    }
                }
            },
        )
    }

    private inner class MarkupButtonsBuilder(
        private val addPaddingToRoot: Boolean,
        initialWidth: Int,
    ) : OnLayoutChangeListener,
        Runnable {
        private var lastWidth: Int

        override fun onLayoutChange(
            v: View?,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            val textFormatView = textFormatView ?: return
            val width = textFormatView.getWidth()
            if (lastWidth != width) {
                lastWidth = width
                textFormatView.removeCallbacks(this)
                textFormatView.post(this)
            }
        }

        override fun run() {
            if (textFormatView != null) {
                fillContainer()
            }
        }

        private var lastSupportedTags = 0
        private var lastDisplayedTags = 0

        init {
            textFormatView!!.addOnLayoutChangeListener(this)
            lastWidth = initialWidth
            fillContainer()
        }

        fun fillContainer() {
            val textFormatView = textFormatView ?: return
            val density = obtainDensity(getResources())
            val maxButtonsWidth =
                lastWidth - textFormatView.getPaddingLeft() - textFormatView.getPaddingRight()
            val buttonMarginLeft = ((-4f) * density).toInt()
            val supportedAndDisplayedTags: Pair<Int, Int> =
                obtainSupportedAndDisplayedTags(
                    if (allowPosting) get(this@PostingFragment.chanName).markup else null,
                    this@PostingFragment.boardName,
                    density,
                    maxButtonsWidth,
                    buttonMarginLeft,
                )
            val supportedTags: Int = supportedAndDisplayedTags.first
            val displayedTags: Int = supportedAndDisplayedTags.second
            if (lastSupportedTags == supportedTags && lastDisplayedTags == displayedTags) {
                return
            }

            lastSupportedTags = supportedTags
            lastDisplayedTags = displayedTags
            commentEditor?.handleSimilar(supportedTags)
            textFormatView.removeAllViews()
            var firstMarkupButton = true
            for (provider in iterable(displayedTags)) {
                val button =
                    provider.createButton(
                        textFormatView.getContext(),
                        android.R.attr.borderlessButtonStyle,
                    )
                setTextSizeScaled(button, 14)
                val layoutParams =
                    LinearLayout.LayoutParams(
                        (provider.widthDp * density).toInt(),
                        (40f * density).toInt(),
                    )
                if (!firstMarkupButton) {
                    layoutParams.leftMargin = buttonMarginLeft
                }
                button.setTag(provider.tag)
                button.setOnClickListener(formatButtonClickListener)
                button.setPadding(0, 0, 0, 0)
                button.setAllCaps(false)

                provider.applyTextAndStyle(button)
                textFormatView.addView(button, layoutParams)
                firstMarkupButton = false
            }
            textFormatView.setVisibility(if (textFormatView.getChildCount() > 0) View.VISIBLE else View.GONE)

            if (addPaddingToRoot) {
                val padding: Int
                if (textFormatView.getVisibility() != View.GONE) {
                    val measureSpec =
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    textFormatView.measure(measureSpec, measureSpec)
                    padding = textFormatView.getMeasuredHeight()
                } else {
                    padding = 0
                }
                // Only reached with the markup bar under the toolbar, which is to say never for a
                // sheet: that one carries its bar inline, so the root here is always the form itself
                (getView() as ExpandedLayout).setExtraTop(padding)
            }
        }
    }

    companion object {
        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_BOARD_NAME = "boardName"
        private const val EXTRA_THREAD_NUMBER = "threadNumber"
        private const val EXTRA_REPLY_DATA_LIST = "replyDataList"

        private const val EXTRA_SHEET = "sheet"

        private const val EXTRA_CAPTCHA_DRAFT = "captchaDraft"

        /** Keeps the toolbar from hiding itself out from under a sheet's top edge. */
        private const val LOCKER_SHEET = "postingSheet"

        /**
         * Fills the attachment down to the top of the controls strip. An attachment with no preview is
         * exactly as tall as that strip, which leaves nothing of the view — that is what keeps the badge
         * out of the way when there is no preview to speak of.
         */
        private fun previewAreaLayoutParams(stripHeight: Int): FrameLayout.LayoutParams =
            FrameLayout
                .LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply { bottomMargin = stripHeight }

        private fun extractMetadataInt(
            retriever: MediaMetadataRetriever,
            key: Int,
        ): Int = retriever.extractMetadata(key)?.toIntOrNull() ?: 0

        private fun formatDuration(milliseconds: Int): String {
            val seconds = milliseconds / 1000
            return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        }

        private fun addHeader(
            layout: ViewGroup,
            index: Int,
            textResId: Int,
        ): TextView {
            val textView = makeListTextHeader(layout)
            textView.setText(textResId)
            layout.addView(textView, index)
            val density = obtainDensity(textView)
            textView.setPadding((4f * density).toInt(), 0, (4f * density).toInt(), 0)
            setNewMargin(textView, 0, 0, 0, (-8f * density).toInt())
            return textView
        }

        private fun addAttachmentButton(
            parent: LinearLayout,
            width: Int,
            attrResId: Int,
            listener: View.OnClickListener?,
        ): View {
            val density = obtainDensity(parent)
            val imageView: ImageView
            imageView = ImageView(parent.getContext(), null, android.R.attr.borderlessButtonStyle)

            parent.addView(imageView, width, LinearLayout.LayoutParams.MATCH_PARENT)
            val layoutParams = imageView.getLayoutParams() as LinearLayout.LayoutParams
            layoutParams.gravity = Gravity.CENTER_VERTICAL
            setNewMarginRelative(imageView, (-8f * density).toInt(), 0, 0, 0)

            imageView.setScaleType(ImageView.ScaleType.CENTER)
            imageView.setImageDrawable(getDrawable(imageView.getContext(), attrResId, 0))
            imageView.setImageTintList(
                getColorStateList(
                    imageView.getContext(),
                    android.R.attr.textColorPrimary,
                ),
            )

            imageView.setOnClickListener(listener)
            return imageView
        }
    }
}
