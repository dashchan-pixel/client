package com.mishiranu.dashchan.ui.navigator.manager

import chan.util.StringUtils

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.ContextThemeWrapper
import android.view.View
import androidx.fragment.app.FragmentManager
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanConfiguration
import chan.content.ChanLocator.NavigationData
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.isEmptyOrWhitespace
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.HighlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.highlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.isUseInternalBrowser
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.text.style.LinkSuffixSpan
import com.mishiranu.dashchan.ui.DialogMenu
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.SearchImageDialog
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay.NavigatePostMode
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ThumbnailClickListener
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ThumbnailLongClickListener
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.NavigationUtils.handleUri
import com.mishiranu.dashchan.util.NavigationUtils.handleUriInternal
import com.mishiranu.dashchan.util.NavigationUtils.shareLink
import com.mishiranu.dashchan.util.NavigationUtils.shareText
import com.mishiranu.dashchan.widget.AttachmentView
import com.mishiranu.dashchan.widget.CommentTextView.LinkListener

class InteractionUnit internal constructor(private val uiManager: UiManager) {
    fun handleLinkClick(
        configurationSet: ConfigurationSet,
        uri: Uri, extra: LinkListener.Extra, confirmed: Boolean
    ) {
        var handled = false
        val chan = getPreferred(null, uri)
        if (chan.name != null) {
            val sameChan = chan.name == extra.chanName
            var navigationData: NavigationData? = null
            if (chan.locator.safe(false).isBoardUri(uri)) {
                navigationData = NavigationData(
                    NavigationData.Target.THREADS,
                    chan.locator.safe(false).getBoardName(uri), null, null, null
                )
                handled = true
            } else if (chan.locator.safe(false).isThreadUri(uri)) {
                val boardName = chan.locator.safe(false).getBoardName(uri)
                val threadNumber = chan.locator.safe(false).getThreadNumber(uri)
                val postNumber = chan.locator.safe(false).getPostNumber(uri)
                if (threadNumber != null) {
                    if (sameChan && chan.configuration.getOption(ChanConfiguration.OPTION_READ_SINGLE_POST)) {
                        uiManager.dialog().displayReplyAsync(
                            configurationSet,
                            chan.name, boardName, threadNumber, postNumber
                        )
                    } else {
                        navigationData = NavigationData(
                            NavigationData.Target.POSTS,
                            boardName, threadNumber, postNumber, null
                        )
                    }
                    handled = true
                }
            } else if (sameChan) {
                navigationData = chan.locator.safe(false).handleUriClickSpecial(uri)
                if (navigationData != null) {
                    handled = true
                }
            }
            if (handled && navigationData != null) {
                if (confirmed) {
                    uiManager.navigator()!!.navigateTargetAllowReturn(chan.name, navigationData)
                } else {
                    handleLinkNavigation(
                        configurationSet.fragmentManager!!,
                        chan.name,
                        navigationData,
                        sameChan
                    )
                }
            }
        }
        if (!handled) {
            handleUriInternal(uiManager.context!!, extra.chanName, uri)
        }
    }

    fun handleLinkLongClick(configurationSet: ConfigurationSet, uri: Uri) {
        handleLinkLongClick(configurationSet.fragmentManager!!, uri)
    }

    private class ThumbnailClickListenerImpl(private val uiManager: UiManager) :
        ThumbnailClickListener {
        private var index = 0
        private var mayShowDialog = false
        private var navigatePostMode: NavigatePostMode? = null

        override fun update(
            index: Int,
            mayShowDialog: Boolean,
            navigatePostMode: NavigatePostMode?
        ) {
            this.index = index
            this.mayShowDialog = mayShowDialog
            this.navigatePostMode = navigatePostMode
        }

        override fun onClick(v: View) {
            val holder =
                ListViewUtils.getViewHolder(v, UiManager.Holder::class.java)
            val postItem = holder!!.postItem
            val attachmentItems = postItem!!.getAttachmentItems()
            if (attachmentItems != null && !attachmentItems.isEmpty()) {
                val gallerySet = holder.gallerySet
                val startImageIndex = gallerySet.findIndex(postItem)
                if (mayShowDialog) {
                    uiManager.dialog().openAttachmentOrDialog(
                        holder.configurationSet!!, v,
                        attachmentItems, startImageIndex, navigatePostMode, gallerySet
                    )
                } else {
                    val index = this.index
                    var imageIndex = startImageIndex
                    for (i in 0..<index) {
                        if (attachmentItems.get(i)!!.isShowInGallery()) {
                            imageIndex++
                        }
                    }
                    uiManager.dialog().openAttachment(
                        v, holder.configurationSet!!.chanName,
                        attachmentItems, index, imageIndex, navigatePostMode, gallerySet
                    )
                }
            }
        }
    }

    private class ThumbnailLongClickListenerImpl : ThumbnailLongClickListener {
        private var attachmentItem: AttachmentItem? = null

        override fun update(attachmentItem: AttachmentItem) {
            this.attachmentItem = attachmentItem
        }

        override fun onLongClick(v: View): Boolean {
            val holder =
                ListViewUtils.getViewHolder(v, UiManager.Holder::class.java)
            Companion.showThumbnailLongClickDialogStatic(
                holder!!.configurationSet!!,
                attachmentItem!!, v as AttachmentView, holder.gallerySet.getThreadTitle()
            )
            return true
        }
    }

    fun createThumbnailClickListener(): ThumbnailClickListener {
        return ThumbnailClickListenerImpl(uiManager)
    }

    fun createThumbnailLongClickListener(): ThumbnailLongClickListener {
        return ThumbnailLongClickListenerImpl()
    }

    fun showThumbnailLongClickDialog(
        configurationSet: ConfigurationSet,
        attachmentItem: AttachmentItem, attachmentView: AttachmentView, threadTitle: String?
    ) {
        showThumbnailLongClickDialogStatic(
            configurationSet,
            attachmentItem,
            attachmentView,
            threadTitle
        )
    }

    fun handlePostClick(
        view: View?, postStateProvider: PostStateProvider,
        postItem: PostItem, localPostItems: Iterable<PostItem>
    ): Boolean {
        if (postItem.getHideState().hidden) {
            uiManager.sendPostItemMessage(postItem, UiManager.Message.PERFORM_SWITCH_HIDE)
            return true
        } else {
            if (highlightUnreadMode == HighlightUnreadMode.MANUALLY) {
                for (localPostItem in localPostItems) {
                    if (!postStateProvider.isRead(localPostItem.getPostNumber())) {
                        postStateProvider.setRead(localPostItem.getPostNumber())
                        uiManager.sendPostItemMessage(
                            localPostItem,
                            UiManager.Message.POST_INVALIDATE_ALL_VIEWS
                        )
                    }
                    if (localPostItem == postItem) {
                        break
                    }
                }
            }
            return uiManager.view().handlePostForDoubleClick(view!!)
        }
    }

    fun handlePostContextMenu(configurationSet: ConfigurationSet, postItem: PostItem) {
        val chan = get(configurationSet.chanName)
        val context = uiManager.context
        val board = chan.configuration.safe().obtainBoard(postItem.getBoardName())
        val postEmpty: Boolean = StringUtils.isEmpty(postItem.getComment(chan).toString())
        val copyText = !postEmpty
        val shareText = !postEmpty
        val userPost = configurationSet.postStateProvider!!.isUserPost(postItem.getPostNumber())
        val dialogMenu = DialogMenu(context!!)
        if (configurationSet.replyable != null && configurationSet.replyable.onRequestReply(false)) {
            dialogMenu.add(R.string.reply, Runnable {
                configurationSet.replyable
                    .onRequestReply(true, ReplyData(postItem.getPostNumber(), null))
            })
            if (!postEmpty) {
                dialogMenu.add(R.string.quote__verb, Runnable {
                    configurationSet.replyable
                        .onRequestReply(
                            true, ReplyData(
                                postItem.getPostNumber(),
                                getCopyReadyComment(postItem.getComment(chan))
                            )
                        )
                })
            }
        }
        if (copyText) {
            dialogMenu.addMore(R.string.copy, Runnable {
                showPostCopyDialog(
                    configurationSet.fragmentManager!!,
                    configurationSet.chanName, postItem
                )
            })
        } else {
            dialogMenu.add(R.string.copy_link, Runnable {
                handlePostContextMenuCopy(
                    context!!,
                    configurationSet.chanName, postItem, PostCopyShareAction.COPY_LINK
                )
            })
        }
        if (shareText) {
            dialogMenu.addMore(R.string.share, Runnable {
                showPostShareDialog(
                    configurationSet.fragmentManager!!,
                    configurationSet.chanName, postItem
                )
            })
        } else {
            dialogMenu.add(R.string.share_link, Runnable {
                handlePostContextMenuCopy(
                    context!!,
                    configurationSet.chanName, postItem, PostCopyShareAction.SHARE_LINK
                )
            })
        }
        if (!postItem.isDeleted()) {
            if (board.allowReporting) {
                dialogMenu.add(R.string.report, Runnable {
                    uiManager.dialog()
                        .performSendReportPosts(
                            configurationSet.fragmentManager!!,
                            chan.name,
                            postItem.getBoardName(),
                            postItem.getThreadNumber(),
                            listOf(postItem.getPostNumber())
                        )
                })
            }
            if (board.allowDeleting) {
                dialogMenu.add(R.string.delete, Runnable {
                    uiManager.dialog()
                        .performSendDeletePosts(
                            configurationSet.fragmentManager!!,
                            chan.name,
                            postItem.getBoardName(),
                            postItem.getThreadNumber(),
                            listOf(postItem.getPostNumber())
                        )
                })
            }
        }
        if (configurationSet.allowMyMarkEdit) {
            dialogMenu.addCheck(R.string.my_post, userPost, Runnable {
                uiManager
                    .sendPostItemMessage(postItem, UiManager.Message.PERFORM_SWITCH_USER_MARK)
            })
        }
        if (configurationSet.isDialog && configurationSet.allowGoToPost) {
            dialogMenu.add(R.string.go_to_post, Runnable {
                uiManager
                    .sendPostItemMessage(postItem, UiManager.Message.PERFORM_GO_TO_POST)
            })
        }
        if (configurationSet.allowHiding && !postItem.getHideState().hidden) {
            dialogMenu.addMore(
                R.string.hide,
                Runnable { showPostHideDialog(configurationSet.fragmentManager!!, postItem) })
        }
        if (board.allowVotes) {
            dialogMenu.add(R.string.vote_like, Runnable {
                uiManager.dialog().performSendVotePost(
                    configurationSet.fragmentManager!!,
                    chan.name,
                    postItem.getBoardName(),
                    postItem.getThreadNumber(),
                    postItem.getPostNumber(),
                    true
                )
            })
            dialogMenu.add(R.string.vote_dislike, Runnable {
                uiManager.dialog().performSendVotePost(
                    configurationSet.fragmentManager!!,
                    chan.name,
                    postItem.getBoardName(),
                    postItem.getThreadNumber(),
                    postItem.getPostNumber(),
                    false
                )
            })
        }
        val dialog = dialogMenu.create()
        uiManager.dialog()
            .handlePostContextMenu(configurationSet, postItem.getPostNumber(), true, dialog)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
            uiManager.dialog().handlePostContextMenu(
                configurationSet,
                postItem.getPostNumber(), false, dialog
            )
        })
        dialog.show()
    }

    private enum class PostCopyShareAction {
        COPY_TEXT, COPY_MARKUP, COPY_LINK, SHARE_LINK, SHARE_TEXT
    }

    companion object {
        private fun handleLinkNavigation(
            fragmentManager: FragmentManager,
            chanName: String?, navigationData: NavigationData, sameChan: Boolean
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val messageId: Int
                    if (sameChan) {
                        when (navigationData.target) {
                            NavigationData.Target.THREADS -> {
                                messageId = R.string.go_to_threads_list__sentence
                            }

                            NavigationData.Target.POSTS -> {
                                messageId = R.string.open_thread__sentence
                            }

                            NavigationData.Target.SEARCH -> {
                                messageId = R.string.go_to_search__sentence
                            }
                        }
                    } else {
                        messageId = R.string.follow_the_link__sentence
                    }
                    val navigationDataFinal: NavigationData? = navigationData
                    AlertDialog.Builder(provider!!.context)
                        .setMessage(messageId)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                UiManager.Companion.extract(provider)!!.navigator()!!.navigateTargetAllowReturn(chanName, navigationDataFinal!!)
                            })
                        .create()
                })
        }

        private fun handleLinkLongClick(fragmentManager: FragmentManager, uri: Uri) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    Companion.createLinkLongClick(
                        provider!!,
                        uri
                    )
                })
        }

        private fun createLinkLongClick(provider: InstanceDialog.Provider, uri: Uri): AlertDialog {
            val chan = getPreferred(null, uri)
            var fileName: String? = null
            var boardName: String? = null
            var threadNumber: String? = null
            var isAttachment = false
            if (chan.name != null && chan.locator.safe(false).isAttachmentUri(uri)) {
                fileName = chan.locator.createAttachmentFileName(uri)
                boardName = chan.locator.safe(false).getBoardName(uri)
                threadNumber = chan.locator.safe(false).getThreadNumber(uri)
                if (threadNumber == null) {
                    boardName = null
                }
                isAttachment = true
            }
            val finalFileName = fileName
            val finalBoardName = boardName
            val finalThreadNumber = threadNumber
            val context = provider.context
            val dialogMenu = DialogMenu(context)
            dialogMenu.add(
                R.string.copy_link,
                Runnable { StringUtils.copyToClipboard(context, uri.toString()) })
            dialogMenu.add(R.string.share_link, Runnable { shareLink(context, null, uri) })
            if (isUseInternalBrowser && (chan.name == null || !chan.locator.safe(false)
                    .isBoardUri(uri) && !chan.locator.safe(false)
                    .isThreadUri(uri) && !chan.locator.safe(false)
                    .isAttachmentUri(uri) && chan.locator.safe(false)
                    .handleUriClickSpecial(uri) == null)
            ) {
                dialogMenu.add(R.string.web_browser, Runnable {
                    handleUri(
                        context, chan.name, uri,
                        NavigationUtils.BrowserType.INTERNAL
                    )
                })
            }
            if (isAttachment) {
                dialogMenu.add(R.string.download_file, Runnable {
                    val binder: DownloadService.Binder? =
                        UiManager.Companion.extract(provider)!!.callback()!!.getDownloadBinder()
                    if (binder != null) {
                        binder.downloadStorage(
                            uri, finalFileName!!, null,
                            chan.name, finalBoardName, finalThreadNumber, null
                        )
                    }
                })
            }
            if (threadNumber != null) {
                dialogMenu.add(R.string.open_thread, Runnable {
                    UiManager.Companion.extract(provider)!!.navigator()!!.navigateTargetAllowReturn(
                            chan.name,
                            NavigationData(
                                NavigationData.Target.POSTS,
                                finalBoardName,
                                finalThreadNumber,
                                null,
                                null
                            )
                        )
                })
            }
            return dialogMenu.create()
        }

        private fun showThumbnailLongClickDialogStatic(
            configurationSet: ConfigurationSet,
            attachmentItem: AttachmentItem, attachmentView: AttachmentView, threadTitle: String?
        ) {
            val chanName = configurationSet.chanName
            val chan = get(configurationSet.chanName)
            val canLoadThumbnailManually =
                attachmentItem.canLoadThumbnailManually(attachmentView, chan)
            InstanceDialog(
                configurationSet.fragmentManager!!,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    Companion.createThumbnailLongClickDialog(
                        provider!!,
                        chanName, attachmentItem, threadTitle, canLoadThumbnailManually
                    )
                })
        }

        private fun createThumbnailLongClickDialog(
            provider: InstanceDialog.Provider,
            chanName: String?,
            attachmentItem: AttachmentItem,
            threadTitle: String?,
            canLoadThumbnailManually: Boolean
        ): AlertDialog {
            val chan = get(chanName)
            val context: Context = ContextThemeWrapper(provider.context, R.style.Theme_Gallery)
            val dialogMenu = DialogMenu(context)
            dialogMenu.setTitle(attachmentItem.getDialogTitle(chan))
            if (attachmentItem.canDownloadToStorage()) {
                dialogMenu.add(R.string.download_file, Runnable {
                    val uiManager: UiManager? = UiManager.Companion.extract(provider)
                    val binder = uiManager!!.callback()!!.getDownloadBinder()
                    if (binder != null) {
                        binder.downloadStorage(
                            attachmentItem.getFileUri(chan),
                            attachmentItem.getFileName(chan)!!,
                            attachmentItem.getOriginalName(),
                            chan.name,
                            attachmentItem.getBoardName(),
                            attachmentItem.getThreadNumber(),
                            threadTitle
                        )
                    }
                })
                if (attachmentItem.getType() == AttachmentItem.Type.IMAGE ||
                    attachmentItem.getThumbnailKey(chan) != null
                ) {
                    dialogMenu.add(R.string.search_image, Runnable {
                        val fileUri = if (attachmentItem.getType() == AttachmentItem.Type.IMAGE)
                            attachmentItem.getFileUri(chan)
                        else
                            attachmentItem.getThumbnailUri(chan)
                        val fileChan = getPreferred(null, fileUri)
                        SearchImageDialog(fileChan.name, fileUri).show(
                            provider.fragmentManager,
                            null
                        )
                    })
                }
            }
            if (canLoadThumbnailManually) {
                dialogMenu.add(R.string.show_thumbnail, Runnable {
                    val uiManager: UiManager? = UiManager.Companion.extract(provider)
                    uiManager!!.reloadAttachmentItem(attachmentItem)
                })
            }
            dialogMenu.add(R.string.copy_link, Runnable {
                StringUtils.copyToClipboard(
                    context,
                    attachmentItem.getFileUri(chan).toString()
                )
            })
            dialogMenu.add(R.string.share_link, Runnable {
                NavigationUtils.shareLink(
                    context, null,
                    attachmentItem.getFileUri(chan)!!
                )
            })
            return dialogMenu.create()
        }

        private fun showPostCopyDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
            postItem: PostItem
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val dialogMenu = DialogMenu(context)
                    dialogMenu.add(R.string.copy_text, Runnable {
                        handlePostContextMenuCopy(
                            context,
                            chanName, postItem, PostCopyShareAction.COPY_TEXT
                        )
                    })
                    dialogMenu.add(R.string.copy_markup, Runnable {
                        handlePostContextMenuCopy(
                            context,
                            chanName, postItem, PostCopyShareAction.COPY_MARKUP
                        )
                    })
                    dialogMenu.add(R.string.copy_link, Runnable {
                        handlePostContextMenuCopy(
                            context,
                            chanName, postItem, PostCopyShareAction.COPY_LINK
                        )
                    })
                    dialogMenu.create()
                })
        }

        private fun showPostShareDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
            postItem: PostItem
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = provider!!.context
                    val dialogMenu = DialogMenu(context)
                    dialogMenu.add(R.string.share_text, Runnable {
                        handlePostContextMenuCopy(
                            context,
                            chanName, postItem, PostCopyShareAction.SHARE_TEXT
                        )
                    })
                    dialogMenu.add(R.string.share_link, Runnable {
                        handlePostContextMenuCopy(
                            context,
                            chanName, postItem, PostCopyShareAction.SHARE_LINK
                        )
                    })
                    dialogMenu.create()
                })
        }

        private fun showPostHideDialog(fragmentManager: FragmentManager, postItem: PostItem?) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val uiManager: UiManager? = UiManager.Companion.extract(provider!!)
                    val dialogMenu = DialogMenu(provider!!.context)
                    dialogMenu.add(R.string.this_post, Runnable {
                        uiManager!!
                            .sendPostItemMessage(postItem, UiManager.Message.PERFORM_SWITCH_HIDE)
                    })
                    dialogMenu.add(R.string.replies_tree, Runnable {
                        uiManager!!
                            .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_REPLIES)
                    })
                    dialogMenu.add(R.string.posts_with_same_name, Runnable {
                        uiManager!!
                            .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_NAME)
                    })
                    dialogMenu.add(R.string.similar_posts, Runnable {
                        uiManager!!
                            .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_SIMILAR)
                    })
                    dialogMenu.create()
                })
        }

        private fun handlePostContextMenuCopy(
            context: Context,
            chanName: String?, postItem: PostItem, action: PostCopyShareAction
        ) {
            val chan = get(chanName)
            when (action) {
                PostCopyShareAction.COPY_TEXT -> {
                    StringUtils.copyToClipboard(
                        context,
                        getCopyReadyComment(postItem.getComment(chan))
                    )
                }

                PostCopyShareAction.COPY_MARKUP -> {
                    StringUtils.copyToClipboard(context, postItem.getCommentMarkup(chan))
                }

                PostCopyShareAction.COPY_LINK, PostCopyShareAction.SHARE_LINK, PostCopyShareAction.SHARE_TEXT -> {
                    val boardName = postItem.getBoardName()
                    val threadNumber = postItem.getThreadNumber()
                    val postNumber = postItem.getPostNumber()
                    val uri = if (postItem.isOriginalPost())
                        chan.locator.safe(true).createThreadUri(boardName, threadNumber)
                    else
                        chan.locator.safe(true).createPostUri(boardName, threadNumber, postNumber)
                    if (uri != null) {
                        when (action) {
                            PostCopyShareAction.COPY_LINK -> {
                                StringUtils.copyToClipboard(context, uri.toString())
                            }

                            PostCopyShareAction.SHARE_LINK -> {
                                var subject = postItem.getSubjectOrComment()
                                if (StringUtils.isEmptyOrWhitespace(subject)) {
                                    subject = uri.toString()
                                }
                                shareLink(context, subject, uri)
                            }

                            PostCopyShareAction.SHARE_TEXT -> {
                                var subject = postItem.getSubjectOrComment()
                                if (StringUtils.isEmptyOrWhitespace(subject)) {
                                    subject = uri.toString()
                                }
                                shareText(
                                    context, subject,
                                    getCopyReadyComment(postItem.getComment(chan)), uri
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        }

        private fun getCopyReadyComment(text: CharSequence): String {
            return getCopyReadyComment(text, 0, text.length)
        }

        fun getCopyReadyComment(text: CharSequence, start: Int, end: Int): String {
            if (text is Spanned) {
                val builder = SpannableStringBuilder(text.subSequence(start, end))
                val spans =
                    builder.getSpans<LinkSuffixSpan?>(0, builder.length, LinkSuffixSpan::class.java)
                if (spans != null && spans.size > 0) {
                    for (span in spans) {
                        val spanStart = builder.getSpanStart(span)
                        val spanEnd = builder.getSpanEnd(span)
                        builder.delete(spanStart, spanEnd)
                    }
                }
                return builder.toString()
            } else {
                return text.subSequence(start, end).toString()
            }
        }
    }
}
