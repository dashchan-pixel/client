package com.mishiranu.dashchan.ui.navigator.manager

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import androidx.fragment.app.FragmentManager
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import chan.content.ChanConfiguration
import chan.content.ChanLocator.NavigationData
import chan.util.StringUtils
import chan.util.StringUtils.copyToClipboard
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences.HighlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.PostSwipeAction
import com.mishiranu.dashchan.content.Preferences.highlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.isUseInternalBrowser
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.PostItem
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

class InteractionUnit internal constructor(
    private val uiManager: UiManager,
) {
    fun handleLinkClick(
        configurationSet: ConfigurationSet,
        uri: Uri,
        extra: LinkListener.Extra,
        confirmed: Boolean,
    ) {
        var handled = false
        val chan = getPreferred(null, uri)
        if (chan.name != null) {
            val sameChan = chan.name == extra.chanName
            var navigationData: NavigationData? = null
            if (chan.locator.safe(false).isBoardUri(uri)) {
                navigationData =
                    NavigationData(
                        NavigationData.Target.THREADS,
                        chan.locator.safe(false).getBoardName(uri),
                        null,
                        null,
                        null,
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
                            chan.name,
                            boardName,
                            threadNumber,
                            postNumber,
                        )
                    } else {
                        navigationData =
                            NavigationData(
                                NavigationData.Target.POSTS,
                                boardName,
                                threadNumber,
                                postNumber,
                                null,
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
                        sameChan,
                    )
                }
            }
        }
        if (!handled) {
            handleUriInternal(uiManager.context, extra.chanName, uri)
        }
    }

    fun handleLinkLongClick(
        configurationSet: ConfigurationSet,
        uri: Uri,
    ) {
        handleLinkLongClick(configurationSet.fragmentManager!!, uri)
    }

    private class ThumbnailClickListenerImpl(
        private val uiManager: UiManager,
    ) : ThumbnailClickListener {
        private var index = 0
        private var mayShowDialog = false
        private var navigatePostMode: NavigatePostMode? = null

        override fun update(
            index: Int,
            mayShowDialog: Boolean,
            navigatePostMode: NavigatePostMode?,
        ) {
            this.index = index
            this.mayShowDialog = mayShowDialog
            this.navigatePostMode = navigatePostMode
        }

        override fun onClick(v: View) {
            val holder =
                ListViewUtils.getViewHolder(v, UiManager.Holder::class.java)
            val postItem = holder!!.postItem
            val attachmentItems = postItem.getAttachmentItems()
            if (attachmentItems != null && !attachmentItems.isEmpty()) {
                val gallerySet = holder.gallerySet
                val startImageIndex = gallerySet.findIndex(postItem)
                if (mayShowDialog) {
                    uiManager.dialog().openAttachmentOrDialog(
                        holder.configurationSet,
                        v,
                        attachmentItems,
                        startImageIndex,
                        navigatePostMode,
                        gallerySet,
                    )
                } else {
                    val index = this.index
                    var imageIndex = startImageIndex
                    for (i in 0..<index) {
                        if (attachmentItems[i].isShowInGallery()) {
                            imageIndex++
                        }
                    }
                    uiManager.dialog().openAttachment(
                        v,
                        holder.configurationSet.chanName,
                        attachmentItems,
                        index,
                        imageIndex,
                        navigatePostMode,
                        gallerySet,
                    )
                }
            }
        }
    }

    private class ThumbnailLongClickListenerImpl : ThumbnailLongClickListener {
        // update() is always dispatched before the view can be long-clicked.
        private lateinit var attachmentItem: AttachmentItem

        override fun update(attachmentItem: AttachmentItem) {
            this.attachmentItem = attachmentItem
        }

        override fun onLongClick(v: View): Boolean {
            val holder =
                ListViewUtils.getViewHolder(v, UiManager.Holder::class.java)
            Companion.showThumbnailLongClickDialogStatic(
                holder!!.configurationSet,
                attachmentItem,
                v as AttachmentView,
                holder.gallerySet.getThreadTitle(),
            )
            return true
        }
    }

    fun createThumbnailClickListener(): ThumbnailClickListener = ThumbnailClickListenerImpl(uiManager)

    fun createThumbnailLongClickListener(): ThumbnailLongClickListener = ThumbnailLongClickListenerImpl()

    fun showThumbnailLongClickDialog(
        configurationSet: ConfigurationSet,
        attachmentItem: AttachmentItem,
        attachmentView: AttachmentView,
        threadTitle: String?,
    ) {
        showThumbnailLongClickDialogStatic(
            configurationSet,
            attachmentItem,
            attachmentView,
            threadTitle,
        )
    }

    fun handlePostClick(
        view: View,
        postStateProvider: PostStateProvider,
        postItem: PostItem,
        localPostItems: Iterable<PostItem>,
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
                            UiManager.Message.POST_INVALIDATE_ALL_VIEWS,
                        )
                    }
                    if (localPostItem == postItem) {
                        break
                    }
                }
            }
            return uiManager.view().handlePostForDoubleClick(view)
        }
    }

    private enum class MenuEntryKind { ITEM, MORE, CHECK }

    private class MenuEntry(
        val action: PostSwipeAction?,
        val kind: MenuEntryKind,
        val titleResId: Int,
        val checked: Boolean,
        val runnable: Runnable,
        // Copy/share submenu leaves live in this list so a swipe can run one directly, but the
        // top-level dialog must not show them -- it offers the submenu entry instead.
        val inDialog: Boolean = true,
    )

    // Single source of truth for the post context menu: the dialog renders these entries and a
    // swipe looks one up by action, so the gesture can never fire something the menu itself
    // wouldn't offer for this post. Entries with a null action are dialog-only.
    // The context every post menu entry is derived from, so the per-group builders below can
    // stay short enough to read.
    private class PostMenuContext(
        val configurationSet: ConfigurationSet,
        val postItem: PostItem,
        val chan: Chan,
        val board: ChanConfiguration.Board,
        val context: Context,
        val postEmpty: Boolean,
        val userPost: Boolean,
    )

    private fun addReplyEntries(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        val replyable = c.configurationSet.replyable
        if (replyable == null || !replyable.onRequestReply(false)) {
            return
        }
        entries.add(
            MenuEntry(
                PostSwipeAction.REPLY,
                MenuEntryKind.ITEM,
                R.string.reply,
                false,
                Runnable {
                    replyable.onRequestReply(true, ReplyData(c.postItem.getPostNumber(), null))
                },
            ),
        )
        if (!c.postEmpty) {
            entries.add(
                MenuEntry(
                    null,
                    MenuEntryKind.ITEM,
                    R.string.quote__verb,
                    false,
                    Runnable {
                        replyable.onRequestReply(
                            true,
                            ReplyData(
                                c.postItem.getPostNumber(),
                                getCopyReadyComment(c.postItem.getComment(c.chan)),
                            ),
                        )
                    },
                ),
            )
        }
    }

    // Swipe-only mirror of one showPostCopyDialog/showPostShareDialog item, so a swipe can skip
    // the submenu. Kept out of the dialog, which shows the submenu entry instead.
    private fun addCopyShareLeaf(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
        action: PostSwipeAction,
        titleResId: Int,
        copyShareAction: PostCopyShareAction,
    ) {
        entries.add(
            MenuEntry(
                action,
                MenuEntryKind.ITEM,
                titleResId,
                false,
                Runnable {
                    handlePostContextMenuCopy(
                        c.context,
                        c.configurationSet.chanName,
                        c.postItem,
                        copyShareAction,
                    )
                },
                inDialog = false,
            ),
        )
    }

    // A post with no text has nothing to copy or share but its link.
    private fun addCopyEntry(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        entries.add(
            if (!c.postEmpty) {
                MenuEntry(
                    PostSwipeAction.COPY,
                    MenuEntryKind.MORE,
                    R.string.copy,
                    false,
                    Runnable {
                        showPostCopyDialog(
                            c.configurationSet.fragmentManager!!,
                            c.configurationSet.chanName,
                            c.postItem,
                        )
                    },
                )
            } else {
                MenuEntry(
                    PostSwipeAction.COPY,
                    MenuEntryKind.ITEM,
                    R.string.copy_link,
                    false,
                    Runnable {
                        handlePostContextMenuCopy(
                            c.context,
                            c.configurationSet.chanName,
                            c.postItem,
                            PostCopyShareAction.COPY_LINK,
                        )
                    },
                )
            },
        )
        if (!c.postEmpty) {
            // Matches showPostCopyDialog, which only exists for a post that has text.
            addCopyShareLeaf(
                entries,
                c,
                PostSwipeAction.COPY_TEXT,
                R.string.copy_text,
                PostCopyShareAction.COPY_TEXT,
            )
            addCopyShareLeaf(
                entries,
                c,
                PostSwipeAction.COPY_MARKUP,
                R.string.copy_markup,
                PostCopyShareAction.COPY_MARKUP,
            )
        }
        addCopyShareLeaf(
            entries,
            c,
            PostSwipeAction.COPY_LINK,
            R.string.copy_link,
            PostCopyShareAction.COPY_LINK,
        )
    }

    private fun addShareEntry(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        entries.add(
            if (!c.postEmpty) {
                MenuEntry(
                    PostSwipeAction.SHARE,
                    MenuEntryKind.MORE,
                    R.string.share,
                    false,
                    Runnable {
                        showPostShareDialog(
                            c.configurationSet.fragmentManager!!,
                            c.configurationSet.chanName,
                            c.postItem,
                        )
                    },
                )
            } else {
                MenuEntry(
                    PostSwipeAction.SHARE,
                    MenuEntryKind.ITEM,
                    R.string.share_link,
                    false,
                    Runnable {
                        handlePostContextMenuCopy(
                            c.context,
                            c.configurationSet.chanName,
                            c.postItem,
                            PostCopyShareAction.SHARE_LINK,
                        )
                    },
                )
            },
        )
        if (!c.postEmpty) {
            // Matches showPostShareDialog, which only exists for a post that has text.
            addCopyShareLeaf(
                entries,
                c,
                PostSwipeAction.SHARE_TEXT,
                R.string.share_text,
                PostCopyShareAction.SHARE_TEXT,
            )
        }
        addCopyShareLeaf(
            entries,
            c,
            PostSwipeAction.SHARE_LINK,
            R.string.share_link,
            PostCopyShareAction.SHARE_LINK,
        )
    }

    private fun addModerationEntries(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        if (c.postItem.isDeleted()) {
            return
        }
        if (c.board.allowReporting) {
            entries.add(
                MenuEntry(
                    PostSwipeAction.REPORT,
                    MenuEntryKind.ITEM,
                    R.string.report,
                    false,
                    Runnable {
                        uiManager.dialog().performSendReportPosts(
                            c.configurationSet.fragmentManager!!,
                            c.chan.name,
                            c.postItem.getBoardName(),
                            c.postItem.getThreadNumber(),
                            listOf(c.postItem.getPostNumber()),
                        )
                    },
                ),
            )
        }
        if (c.board.allowDeleting) {
            entries.add(
                MenuEntry(
                    null,
                    MenuEntryKind.ITEM,
                    R.string.delete,
                    false,
                    Runnable {
                        uiManager.dialog().performSendDeletePosts(
                            c.configurationSet.fragmentManager!!,
                            c.chan.name,
                            c.postItem.getBoardName(),
                            c.postItem.getThreadNumber(),
                            listOf(c.postItem.getPostNumber()),
                        )
                    },
                ),
            )
        }
    }

    private fun addPostStateEntries(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        if (c.configurationSet.allowMyMarkEdit) {
            entries.add(
                MenuEntry(
                    PostSwipeAction.MY_POST,
                    MenuEntryKind.CHECK,
                    R.string.my_post,
                    c.userPost,
                    Runnable {
                        uiManager.sendPostItemMessage(
                            c.postItem,
                            UiManager.Message.PERFORM_SWITCH_USER_MARK,
                        )
                    },
                ),
            )
        }
        if (c.configurationSet.isDialog && c.configurationSet.allowGoToPost) {
            entries.add(
                MenuEntry(
                    null,
                    MenuEntryKind.ITEM,
                    R.string.go_to_post,
                    false,
                    Runnable {
                        uiManager.sendPostItemMessage(c.postItem, UiManager.Message.PERFORM_GO_TO_POST)
                    },
                ),
            )
        }
        if (c.configurationSet.allowHiding && !c.postItem.getHideState().hidden) {
            entries.add(
                MenuEntry(
                    PostSwipeAction.HIDE,
                    MenuEntryKind.MORE,
                    R.string.hide,
                    false,
                    Runnable {
                        showPostHideDialog(c.configurationSet.fragmentManager!!, c.postItem)
                    },
                ),
            )
        }
    }

    private fun addVoteEntries(
        entries: MutableList<MenuEntry>,
        c: PostMenuContext,
    ) {
        if (!c.board.allowVotes) {
            return
        }
        for (like in booleanArrayOf(true, false)) {
            entries.add(
                MenuEntry(
                    null,
                    MenuEntryKind.ITEM,
                    if (like) R.string.vote_like else R.string.vote_dislike,
                    false,
                    Runnable {
                        uiManager.dialog().performSendVotePost(
                            c.configurationSet.fragmentManager!!,
                            c.chan.name,
                            c.postItem.getBoardName(),
                            c.postItem.getThreadNumber(),
                            c.postItem.getPostNumber(),
                            like,
                        )
                    },
                ),
            )
        }
    }

    private fun buildPostMenuEntries(
        configurationSet: ConfigurationSet,
        postItem: PostItem,
    ): List<MenuEntry> {
        val chan = get(configurationSet.chanName)
        val menuContext =
            PostMenuContext(
                configurationSet,
                postItem,
                chan,
                chan.configuration.safe().obtainBoard(postItem.getBoardName()),
                uiManager.context,
                StringUtils.isEmpty(postItem.getComment(chan).toString()),
                configurationSet.postStateProvider.isUserPost(postItem.getPostNumber()),
            )
        val entries = ArrayList<MenuEntry>()
        addReplyEntries(entries, menuContext)
        addCopyEntry(entries, menuContext)
        addShareEntry(entries, menuContext)
        addModerationEntries(entries, menuContext)
        addPostStateEntries(entries, menuContext)
        addVoteEntries(entries, menuContext)
        return entries
    }

    /**
     * Runs the post context menu entry bound to [action]. Returns whether an entry existed:
     * a post the action doesn't apply to (reporting where the board disallows it, hiding an
     * already hidden post) simply does nothing.
     */
    fun performPostSwipeAction(
        configurationSet: ConfigurationSet,
        postItem: PostItem,
        action: PostSwipeAction,
    ): Boolean {
        if (action == PostSwipeAction.DISABLED) {
            return false
        }
        val entry =
            buildPostMenuEntries(configurationSet, postItem).firstOrNull { it.action == action }
        entry?.runnable?.run()
        return entry != null
    }

    fun handlePostContextMenu(
        configurationSet: ConfigurationSet,
        postItem: PostItem,
    ) {
        val context = uiManager.context
        val dialogMenu = DialogMenu(context)
        for (entry in buildPostMenuEntries(configurationSet, postItem)) {
            if (!entry.inDialog) {
                continue
            }
            when (entry.kind) {
                MenuEntryKind.ITEM -> {
                    dialogMenu.add(entry.titleResId, entry.runnable)
                }

                MenuEntryKind.MORE -> {
                    dialogMenu.addMore(entry.titleResId, entry.runnable)
                }

                MenuEntryKind.CHECK -> {
                    dialogMenu.addCheck(entry.titleResId, entry.checked, entry.runnable)
                }
            }
        }
        val dialog = dialogMenu.create()
        uiManager
            .dialog()
            .handlePostContextMenu(configurationSet, postItem.getPostNumber(), true, dialog)
        dialog.setOnDismissListener(
            DialogInterface.OnDismissListener { d: DialogInterface? ->
                uiManager.dialog().handlePostContextMenu(
                    configurationSet,
                    postItem.getPostNumber(),
                    false,
                    dialog,
                )
            },
        )
        dialog.show()
    }

    private enum class PostCopyShareAction {
        COPY_TEXT,
        COPY_MARKUP,
        COPY_LINK,
        SHARE_LINK,
        SHARE_TEXT,
    }

    companion object {
        private fun handleLinkNavigation(
            fragmentManager: FragmentManager,
            chanName: String?,
            navigationData: NavigationData,
            sameChan: Boolean,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
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
                    val navigationDataFinal: NavigationData = navigationData
                    AlertDialog
                        .Builder(provider.context)
                        .setMessage(messageId)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            android.R.string.ok,
                            DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                                UiManager.Companion
                                    .extract(
                                        provider,
                                    )!!
                                    .navigator()!!
                                    .navigateTargetAllowReturn(chanName, navigationDataFinal)
                            },
                        ).create()
                },
            )
        }

        private fun handleLinkLongClick(
            fragmentManager: FragmentManager,
            uri: Uri,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    Companion.createLinkLongClick(
                        provider,
                        uri,
                    )
                },
            )
        }

        private fun createLinkLongClick(
            provider: InstanceDialog.Provider,
            uri: Uri,
        ): AlertDialog {
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
                Runnable { StringUtils.copyToClipboard(context, uri.toString()) },
            )
            dialogMenu.add(R.string.share_link, Runnable { shareLink(context, null, uri) })
            if (isUseInternalBrowser &&
                (
                    chan.name == null ||
                        (
                            !chan.locator
                                .safe(false)
                                .isBoardUri(uri) &&
                                !chan.locator
                                    .safe(false)
                                    .isThreadUri(uri) &&
                                !chan.locator
                                    .safe(false)
                                    .isAttachmentUri(uri) &&
                                chan.locator
                                    .safe(false)
                                    .handleUriClickSpecial(uri) == null
                        )
                )
            ) {
                dialogMenu.add(
                    R.string.web_browser,
                    Runnable {
                        handleUri(
                            context,
                            chan.name,
                            uri,
                            NavigationUtils.BrowserType.INTERNAL,
                        )
                    },
                )
            }
            if (isAttachment) {
                dialogMenu.add(
                    R.string.download_file,
                    Runnable {
                        val binder: DownloadService.Binder? =
                            UiManager.Companion
                                .extract(provider)!!
                                .callback()!!
                                .getDownloadBinder()
                        if (binder != null) {
                            binder.downloadStorage(
                                uri,
                                finalFileName!!,
                                null,
                                chan.name,
                                finalBoardName,
                                finalThreadNumber,
                                null,
                            )
                        }
                    },
                )
            }
            if (threadNumber != null) {
                dialogMenu.add(
                    R.string.open_thread,
                    Runnable {
                        UiManager.Companion.extract(provider)!!.navigator()!!.navigateTargetAllowReturn(
                            chan.name,
                            NavigationData(
                                NavigationData.Target.POSTS,
                                finalBoardName,
                                finalThreadNumber,
                                null,
                                null,
                            ),
                        )
                    },
                )
            }
            return dialogMenu.create()
        }

        private fun showThumbnailLongClickDialogStatic(
            configurationSet: ConfigurationSet,
            attachmentItem: AttachmentItem,
            attachmentView: AttachmentView,
            threadTitle: String?,
        ) {
            val chanName = configurationSet.chanName
            val chan = get(configurationSet.chanName)
            val canLoadThumbnailManually =
                attachmentItem.canLoadThumbnailManually(attachmentView, chan)
            InstanceDialog(
                configurationSet.fragmentManager!!,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    Companion.createThumbnailLongClickDialog(
                        provider,
                        chanName,
                        attachmentItem,
                        threadTitle,
                        canLoadThumbnailManually,
                    )
                },
            )
        }

        private fun createThumbnailLongClickDialog(
            provider: InstanceDialog.Provider,
            chanName: String?,
            attachmentItem: AttachmentItem,
            threadTitle: String?,
            canLoadThumbnailManually: Boolean,
        ): AlertDialog {
            val chan = get(chanName)
            // Plain provider context, like every other post menu: Theme_Gallery is dark whatever the
            // user theme is, and being fullscreen rather than floating it also skips the theme
            // engine's rounded window background.
            val context = provider.context
            val dialogMenu = DialogMenu(context)
            dialogMenu.setTitle(attachmentItem.getDialogTitle(chan))
            if (attachmentItem.canDownloadToStorage()) {
                dialogMenu.add(
                    R.string.download_file,
                    Runnable {
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
                                threadTitle,
                            )
                        }
                    },
                )
                if (attachmentItem.getType() == AttachmentItem.Type.IMAGE ||
                    attachmentItem.getThumbnailKey(chan) != null
                ) {
                    dialogMenu.add(
                        R.string.search_image,
                        Runnable {
                            val fileUri =
                                if (attachmentItem.getType() == AttachmentItem.Type.IMAGE) {
                                    attachmentItem.getFileUri(chan)
                                } else {
                                    attachmentItem.getThumbnailUri(chan)
                                }
                            val fileChan = getPreferred(null, fileUri)
                            SearchImageDialog(fileChan.name, fileUri).show(
                                provider.fragmentManager,
                                null,
                            )
                        },
                    )
                }
            }
            if (canLoadThumbnailManually) {
                dialogMenu.add(
                    R.string.show_thumbnail,
                    Runnable {
                        val uiManager: UiManager? = UiManager.Companion.extract(provider)
                        uiManager!!.reloadAttachmentItem(attachmentItem)
                    },
                )
            }
            dialogMenu.add(
                R.string.copy_link,
                Runnable {
                    StringUtils.copyToClipboard(
                        context,
                        attachmentItem.getFileUri(chan).toString(),
                    )
                },
            )
            dialogMenu.add(
                R.string.share_link,
                Runnable {
                    NavigationUtils.shareLink(
                        context,
                        null,
                        attachmentItem.getFileUri(chan)!!,
                    )
                },
            )
            return dialogMenu.create()
        }

        private fun showPostCopyDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
            postItem: PostItem,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    val context = provider.context
                    val dialogMenu = DialogMenu(context)
                    dialogMenu.add(
                        R.string.copy_text,
                        Runnable {
                            handlePostContextMenuCopy(
                                context,
                                chanName,
                                postItem,
                                PostCopyShareAction.COPY_TEXT,
                            )
                        },
                    )
                    dialogMenu.add(
                        R.string.copy_markup,
                        Runnable {
                            handlePostContextMenuCopy(
                                context,
                                chanName,
                                postItem,
                                PostCopyShareAction.COPY_MARKUP,
                            )
                        },
                    )
                    dialogMenu.add(
                        R.string.copy_link,
                        Runnable {
                            handlePostContextMenuCopy(
                                context,
                                chanName,
                                postItem,
                                PostCopyShareAction.COPY_LINK,
                            )
                        },
                    )
                    dialogMenu.create()
                },
            )
        }

        private fun showPostShareDialog(
            fragmentManager: FragmentManager,
            chanName: String?,
            postItem: PostItem,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    val context = provider.context
                    val dialogMenu = DialogMenu(context)
                    dialogMenu.add(
                        R.string.share_text,
                        Runnable {
                            handlePostContextMenuCopy(
                                context,
                                chanName,
                                postItem,
                                PostCopyShareAction.SHARE_TEXT,
                            )
                        },
                    )
                    dialogMenu.add(
                        R.string.share_link,
                        Runnable {
                            handlePostContextMenuCopy(
                                context,
                                chanName,
                                postItem,
                                PostCopyShareAction.SHARE_LINK,
                            )
                        },
                    )
                    dialogMenu.create()
                },
            )
        }

        private fun showPostHideDialog(
            fragmentManager: FragmentManager,
            postItem: PostItem,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider ->
                    val uiManager: UiManager? = UiManager.Companion.extract(provider)
                    val dialogMenu = DialogMenu(provider.context)
                    dialogMenu.add(
                        R.string.this_post,
                        Runnable {
                            uiManager!!
                                .sendPostItemMessage(postItem, UiManager.Message.PERFORM_SWITCH_HIDE)
                        },
                    )
                    dialogMenu.add(
                        R.string.replies_tree,
                        Runnable {
                            uiManager!!
                                .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_REPLIES)
                        },
                    )
                    dialogMenu.add(
                        R.string.posts_with_same_name,
                        Runnable {
                            uiManager!!
                                .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_NAME)
                        },
                    )
                    dialogMenu.add(
                        R.string.similar_posts,
                        Runnable {
                            uiManager!!
                                .sendPostItemMessage(postItem, UiManager.Message.PERFORM_HIDE_SIMILAR)
                        },
                    )
                    dialogMenu.create()
                },
            )
        }

        private fun handlePostContextMenuCopy(
            context: Context,
            chanName: String?,
            postItem: PostItem,
            action: PostCopyShareAction,
        ) {
            val chan = get(chanName)
            when (action) {
                PostCopyShareAction.COPY_TEXT -> {
                    StringUtils.copyToClipboard(
                        context,
                        getCopyReadyComment(postItem.getComment(chan)),
                    )
                }

                PostCopyShareAction.COPY_MARKUP -> {
                    StringUtils.copyToClipboard(context, postItem.getCommentMarkup(chan))
                }

                PostCopyShareAction.COPY_LINK, PostCopyShareAction.SHARE_LINK, PostCopyShareAction.SHARE_TEXT -> {
                    val boardName = postItem.getBoardName()
                    val threadNumber = postItem.getThreadNumber()
                    val postNumber = postItem.getPostNumber()
                    val uri =
                        if (postItem.isOriginalPost()) {
                            chan.locator.safe(true).createThreadUri(boardName, threadNumber)
                        } else {
                            chan.locator.safe(true).createPostUri(boardName, threadNumber, postNumber)
                        }
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
                                    context,
                                    subject,
                                    getCopyReadyComment(postItem.getComment(chan)),
                                    uri,
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        }

        private fun getCopyReadyComment(text: CharSequence): String = getCopyReadyComment(text, 0, text.length)

        fun getCopyReadyComment(
            text: CharSequence,
            start: Int,
            end: Int,
        ): String {
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
