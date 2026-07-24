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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.os.BundleCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getFallback
import chan.content.ChanConfiguration
import chan.content.ChanConfiguration.Posting
import chan.content.ChanMarkup
import chan.content.ChanPerformer.CaptchaData
import chan.content.ChanPerformer.SendPostData
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
import com.mishiranu.dashchan.content.Preferences.configuredFileNewname
import com.mishiranu.dashchan.content.Preferences.getCaptchaPass
import com.mishiranu.dashchan.content.Preferences.getPassword
import com.mishiranu.dashchan.content.Preferences.isAddSpaceAfterQuote
import com.mishiranu.dashchan.content.Preferences.isAlwaysClearMetadata
import com.mishiranu.dashchan.content.Preferences.isAlwaysRemoveFilename
import com.mishiranu.dashchan.content.Preferences.isAlwaysRenameFilename
import com.mishiranu.dashchan.content.Preferences.isAlwaysUniqueHash
import com.mishiranu.dashchan.content.Preferences.isCaptchaAutoReload
import com.mishiranu.dashchan.content.Preferences.isHideCaptchaPassBlock
import com.mishiranu.dashchan.content.Preferences.isHidePersonalData
import com.mishiranu.dashchan.content.Preferences.isHugeCaptcha
import com.mishiranu.dashchan.content.Preferences.isMarkupButtonsAtBottom
import com.mishiranu.dashchan.content.Preferences.uiCornerRadius
import com.mishiranu.dashchan.content.async.ReadCaptchaTask
import com.mishiranu.dashchan.content.async.SendPostTask.ProgressState
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
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
import com.mishiranu.dashchan.ui.preference.CommandsFragment
import com.mishiranu.dashchan.util.ConcurrentUtils
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
import com.mishiranu.dashchan.widget.ProgressDialog
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import com.mishiranu.dashchan.widget.UriPasteEditText
import com.mishiranu.dashchan.widget.ViewFactory.makeListTextHeader
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
    private var commentCommands: List<CommandsStorage.CommandItem> = emptyList()
    private var captchaForm: CaptchaForm? = null
    private var confirmationHeaderView: TextView? = null
    private var footerContainer: FrameLayout? = null
    private var sendButton: Button? = null
    private var attachmentColumnCount = 0

    private val attachments = ArrayList<AttachmentHolder>()

    private var attachmentReordered = false

    private var allowDialog = true
    private var sendButtonEnabled = true

    private var refreshCaptchaWhenLifetimeEnd = false

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
        val rootView = ExpandedLayout(container!!.getContext(), true)
        rootView.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        inflater.inflate(R.layout.activity_posting, rootView)
        return rootView
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

        Companion.addHeader(personalDataBlock, 0, R.string.personal_data)
        addHeader(postingLayout, postingLayout.indexOfChild(subjectView), R.string.message_data)
        confirmationHeaderView =
            addHeader(
                postingLayout,
                postingLayout.indexOfChild(footerContainer),
                R.string.confirmation,
            )
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
        val markupAtBottom = isMarkupButtonsAtBottom
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
        commentWrapper.addView(
            commentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        commandsButton = buildCommandsButton(commentWrapper, density)
        postingLayout.addView(commentWrapper, postingLayout.indexOfChild(commentParent))
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
                        canLoadState = captchaFromDraftHasLifetime && captchaFromDraft.alive()
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
                        showCaptcha(
                            captchaDraft.captchaState,
                            captchaDraft.captchaData,
                            null,
                            null,
                            captchaDraft.loadedValidity,
                            null,
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
                        index < commentCarriage &&
                        commentCarriage <= builder.length &&
                        builder.substring(index, commentCarriage).contains("\n>>")
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
        requireActivity().bindService(
            Intent(requireContext(), PostingService::class.java),
            postingConnection,
            Context.BIND_AUTO_CREATE,
        )

        val viewModel = ViewModelProvider(this).get<CaptchaViewModel>(CaptchaViewModel::class.java)
        viewModel.observe(getViewLifecycleOwner(), this)
    }

    public override fun onDestroyView() {
        super.onDestroyView()
        captchaForm!!.onDestroyView()

        postingBinder?.unregister(postingCallback)
        postingBinder = null
        requireActivity().unbindService(postingConnection)

        dismissSendPost()
        saveDraft()
        ViewUtils.removeFromParent(textFormatView!!)

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
        commandsButton = null
        captchaForm = null
        confirmationHeaderView = null
        footerContainer = null
        sendButton = null
        attachments.clear()
    }

    private fun obtainPostDraft(): PostDraft {
        var attachmentDrafts: ArrayList<AttachmentDraft>? = null
        if (attachments.size > 0) {
            attachmentDrafts = ArrayList<AttachmentDraft>(attachments.size)
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
        }
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
            (requireActivity() as FragmentHandler).removeFragment()
        }
    }

    override fun onChansChanged(
        changed: Collection<String>,
        removed: Collection<String>,
    ) {
        if (changed.contains(this.chanName) || removed.contains(this.chanName)) {
            updatePostingConfigurationIfNeeded()
            if (!allowPosting) {
                (requireActivity() as FragmentHandler).removeFragment()
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
                invalidateOptionsMenu()
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
            .setVisible(attachments.size < postingConfiguration.attachmentCount)
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
        return true
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
        (requireActivity() as FragmentHandler).removeFragment()
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
                        (requireActivity() as FragmentHandler).removeFragment()
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
                        Runnable {
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
        updateConfirmationHeaderState()
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
                )
            task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
            viewModel.attach(task)
        }
    }

    class CaptchaViewModel : TaskViewModel.Proxy<ReadCaptchaTask, ReadCaptchaTask.Callback>()

    // The captcha block itself is hidden with a captcha pass, so its header must go away too
    private fun updateConfirmationHeaderState() {
        val headerView = confirmationHeaderView ?: return
        val footerContainer = this.footerContainer ?: return
        val hidden = captchaState == ReadCaptchaTask.CaptchaState.PASS && isHideCaptchaPassBlock
        headerView.setVisibility(if (hidden) View.GONE else View.VISIBLE)
        // Without the header the send button would stick to the checkboxes above it
        val density = obtainDensity(footerContainer)
        footerContainer.setPadding(0, if (hidden) (16f * density).toInt() else 0, 0, 0)
        // Showing or hiding the whole block changes the height left for the comment field
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
        updateConfirmationHeaderState()
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
        updateConfirmationHeaderState()
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

    private val attachmentRemoveListener: View.OnClickListener =
        object : View.OnClickListener {
            override fun onClick(v: View) {
                val holder = v.getTag() as AttachmentHolder
                if (attachments.remove(holder)) {
                    if (attachmentColumnCount == 1) {
                        attachmentContainer!!.removeView(holder.view)
                    } else {
                        invalidateAttachments(true)
                    }
                    invalidateOptionsMenu()
                    resizeComment(true)
                    getInstance().store(obtainPostDraft())
                }
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

                else -> true
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
            )
        warningButton.setTag(holder)
        ratingButton.setTag(holder)
        removeButton.setTag(holder)
        options.setTag(holder)
        attachments.add(holder)
        invalidateOptionsMenu()
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
        if (fileHolder != null) {
            if (fileHolder.isImage) {
                try {
                    bitmap = fileHolder.readImageBitmap(targetImageSize, false, false)
                } catch (e: OutOfMemoryError) {
                    // Ignore
                }
                fileSize += " " + fileHolder.imageWidth + '×' + fileHolder.imageHeight
            }
            if (bitmap == null) {
                if (Chan.getFallback().locator.isVideoExtension(fileHolder.name)) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        fileHolder.openFileDescriptor().use { descriptor ->
                            retriever.setDataSource(descriptor.getFileDescriptor())
                            val fullBitmap = retriever.getFrameAtTime(-1)
                            if (fullBitmap != null) {
                                bitmap = reduceBitmapSize(fullBitmap, targetImageSize, true)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } catch (e: OutOfMemoryError) {
                        e.printStackTrace()
                    }
                }
            }
        }
        if (bitmap != null) {
            holder.imageView.setVisibility(View.VISIBLE)
            holder.imageView.setImageBitmap(bitmap)
            holder.view.getLayoutParams().height = (128f * obtainDensity(this)).toInt()
        }
        holder.fileSize.setText(fileSize)
        if ((jpegData == null || jpegData.exifData == null) && (pngData == null || !pngData.hasMetadata)) {
            holder.warningButton.setVisibility(View.GONE)
        }
        updateAttachmentConfiguration(holder)
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
        commandsButton?.visibility = if (commentCommands.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showCommandsPopup(anchor: View) {
        CommandsPopup.show(
            anchor,
            commentCommands,
            onRun = { runCommand(it) },
            onEdit = { (requireActivity() as FragmentHandler).pushFragment(CommandsFragment(it.id)) },
        )
    }

    /**
     * The thread being replied to, as the `thread` object handed to a command — `null` when composing a
     * new thread. The posting screen doesn't carry a thread title, so it goes out as `null`.
     */
    private fun commandThread(): CommandRunner.ThreadInfo? {
        val threadNumber = this.threadNumber ?: return null
        return CommandRunner.ThreadInfo(threadNumber, null)
    }

    /** The board being posted to, as the `board` object handed to a command. */
    private fun commandBoard(): CommandRunner.BoardInfo? {
        val boardName = this.boardName ?: return null
        return CommandRunner.BoardInfo(boardName, get(this.chanName).configuration.getBoardTitle(boardName))
    }

    private fun runCommand(command: CommandsStorage.CommandItem) {
        val commentView = commentView ?: return
        CommandRunner.run(
            command,
            commentView.getText().toString(),
            commandThread(),
            commandBoard(),
        ) { result ->
            // Delivered on the main thread; the view may be gone by the time it arrives.
            val liveCommentView = this.commentView ?: return@run
            when (result) {
                is CommandRunner.Result.Success -> {
                    val comment = result.comment
                    if (comment != null) {
                        liveCommentView.setText(comment)
                        liveCommentView.setSelection(liveCommentView.getText().length)
                    }
                }

                is CommandRunner.Result.Failure -> {
                    show(getString(R.string.command_failed__format, result.message))
                }
            }
        }
    }

    /**
     * Send-button handler. Runs any commands marked "run on send" against the comment first — in
     * order, each seeing the previous one's output — and only sends once they all succeed. A failing
     * command aborts the send with a toast so nothing is posted half-transformed.
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
        autoRunChain(commands, 0, commentView.getText().toString())
    }

    private fun autoRunChain(
        commands: List<CommandsStorage.CommandItem>,
        index: Int,
        comment: String,
    ) {
        if (index >= commands.size) {
            sendButtonEnabled = true
            updateSendButtonState()
            executeSendPost()
            return
        }
        CommandRunner.run(commands[index], comment, commandThread(), commandBoard()) { result ->
            val liveCommentView = this.commentView
            if (liveCommentView == null) {
                sendButtonEnabled = true
                return@run
            }
            when (result) {
                is CommandRunner.Result.Success -> {
                    val newComment = result.comment ?: comment
                    liveCommentView.setText(newComment)
                    liveCommentView.setSelection(liveCommentView.getText().length)
                    autoRunChain(commands, index + 1, newComment)
                }

                is CommandRunner.Result.Failure -> {
                    show(getString(R.string.command_failed__format, result.message))
                    sendButtonEnabled = true
                    updateSendButtonState()
                }
            }
        }
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
                (getView() as ExpandedLayout).setExtraTop(padding)
            }
        }
    }

    companion object {
        private const val EXTRA_CHAN_NAME = "chanName"
        private const val EXTRA_BOARD_NAME = "boardName"
        private const val EXTRA_THREAD_NUMBER = "threadNumber"
        private const val EXTRA_REPLY_DATA_LIST = "replyDataList"

        private const val EXTRA_CAPTCHA_DRAFT = "captchaDraft"

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
