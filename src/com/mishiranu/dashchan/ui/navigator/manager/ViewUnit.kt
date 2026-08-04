package com.mishiranu.dashchan.ui.navigator.manager

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getFallback
import chan.util.StringUtils
import chan.util.StringUtils.fixParsedUriString
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.Preferences.HighlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.highlightUnreadMode
import com.mishiranu.dashchan.content.Preferences.isAllAttachments
import com.mishiranu.dashchan.content.Preferences.isDisplayIcons
import com.mishiranu.dashchan.content.Preferences.isHighlightUserPosts
import com.mishiranu.dashchan.content.Preferences.isSfwMode
import com.mishiranu.dashchan.content.Preferences.isShowMyPosts
import com.mishiranu.dashchan.content.Preferences.isShowPostsBorders
import com.mishiranu.dashchan.content.Preferences.isShowSpoilers
import com.mishiranu.dashchan.content.Preferences.postMaxLines
import com.mishiranu.dashchan.content.Preferences.textScale
import com.mishiranu.dashchan.content.Preferences.thumbnailsScale
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostItem.BumpLimitState
import com.mishiranu.dashchan.content.model.PostItem.DescriptionBuilder
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage.Companion.getInstance
import com.mishiranu.dashchan.text.style.LinkSuffixSpan
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay.NavigatePostMode
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit.IconData
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ConfigurationSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.DemandSet
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.PostStateProvider
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ThumbnailClickListener
import com.mishiranu.dashchan.ui.navigator.manager.UiManager.ThumbnailLongClickListener
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.util.AnimationUtils.measureDynamicHeight
import com.mishiranu.dashchan.util.AnimationUtils.ofHeight
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.ListViewUtils.UnlimitedRecycledViewPool
import com.mishiranu.dashchan.util.PostDateFormatter
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.isTablet
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.util.ViewUtils.applyScaleMarginLR
import com.mishiranu.dashchan.util.ViewUtils.applyScaleSize
import com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground
import com.mishiranu.dashchan.widget.AttachmentView
import com.mishiranu.dashchan.widget.CardView
import com.mishiranu.dashchan.widget.CommentTextView
import com.mishiranu.dashchan.widget.CommentTextView.ExtraButton
import com.mishiranu.dashchan.widget.CommentTextView.LimitListener
import com.mishiranu.dashchan.widget.CommentTextView.LinkConfiguration
import com.mishiranu.dashchan.widget.CommentTextView.LinkListener
import com.mishiranu.dashchan.widget.CommentTextView.PrepareToCopyListener
import com.mishiranu.dashchan.widget.CommentTextView.RecyclerKeeper
import com.mishiranu.dashchan.widget.CommentTextView.SpanStateListener
import com.mishiranu.dashchan.widget.LinebreakLayout
import com.mishiranu.dashchan.widget.PostBorderView
import com.mishiranu.dashchan.widget.PostLinearLayout
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.fastParseThemeFromText
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getColorScheme
import com.mishiranu.dashchan.widget.ThemeEngine.Companion.getTheme
import com.mishiranu.dashchan.widget.ThreadDescriptionView
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class ViewUnit
    @SuppressLint("InflateParams")
    internal constructor(
        uiManager: UiManager,
    ) {
        private val uiManager: UiManager
        private val postDateFormatter: PostDateFormatter
        private val extraButtons: MutableList<ExtraButton?>
        private val postDimensions = Lazy<PostViewHolder.Dimensions>()

        private val defaultLinkListener: LinkListener =
            object : LinkListener {
                override fun onLinkClick(
                    view: CommentTextView,
                    uri: Uri,
                    extra: LinkListener.Extra,
                    confirmed: Boolean,
                ) {
                    val holder =
                        ListViewUtils.getViewHolder(view, UiManager.Holder::class.java)
                    uiManager
                        .interaction()
                        .handleLinkClick(holder!!.configurationSet, uri, extra, confirmed)
                }

                override fun onLinkLongClick(
                    view: CommentTextView,
                    uri: Uri,
                    extra: LinkListener.Extra,
                ) {
                    val holder =
                        ListViewUtils.getViewHolder(view, UiManager.Holder::class.java)
                    uiManager.interaction().handleLinkLongClick(holder!!.configurationSet, uri)
                }
            }

        private fun onSpanStateChanged(view: CommentTextView) {
            uiManager.sendPostItemMessage(view, UiManager.Message.INVALIDATE_COMMENT_VIEW)
        }

        private val spanStateListener: SpanStateListener =
            SpanStateListener { view: CommentTextView -> this.onSpanStateChanged(view) }

        private val prepareToCopyListener: PrepareToCopyListener =
            PrepareToCopyListener { view: CommentTextView, text: Spannable, start: Int, end: Int ->
                InteractionUnit.Companion.getCopyReadyComment(
                    text,
                    start,
                    end,
                )
            }

        enum class ViewType {
            THREAD,
            THREAD_HIDDEN,
            THREAD_CARD,
            THREAD_CARD_HIDDEN,
            THREAD_CARD_CELL,
            POST,
            POST_HIDDEN,
        }

        private enum class ThreadViewType {
            LIST,
            CARD,
            CELL,
        }

        private val threadsPostsViewPool = UnlimitedRecycledViewPool()

        fun bindThreadsPostRecyclerView(recyclerView: RecyclerView) {
            recyclerView.setRecycledViewPool(threadsPostsViewPool)
            (recyclerView.getLayoutManager() as LinearLayoutManager).setRecycleChildrenOnDetach(true)
        }

        fun createView(
            parent: ViewGroup,
            viewType: ViewType,
        ): RecyclerView.ViewHolder {
            when (viewType) {
                ViewType.THREAD -> {
                    return ThreadViewHolder(parent, uiManager, ThreadViewType.LIST)
                }

                ViewType.THREAD_HIDDEN -> {
                    return HiddenViewHolder(parent, false, true)
                }

                ViewType.THREAD_CARD -> {
                    return ThreadViewHolder(parent, uiManager, ThreadViewType.CARD)
                }

                ViewType.THREAD_CARD_HIDDEN -> {
                    return HiddenViewHolder(parent, true, true)
                }

                ViewType.THREAD_CARD_CELL -> {
                    return ThreadViewHolder(parent, uiManager, ThreadViewType.CELL)
                }

                ViewType.POST -> {
                    return PostViewHolder(parent, uiManager, postDimensions)
                }

                ViewType.POST_HIDDEN -> {
                    return HiddenViewHolder(parent, false, false)
                }
            }
        }

        fun bindThreadView(
            viewHolder: RecyclerView.ViewHolder?,
            postItem: PostItem,
            configurationSet: ConfigurationSet,
        ) {
            val context = uiManager.context
            val colorScheme = getColorScheme(context)
            val holder: ThreadViewHolder = viewHolder as ThreadViewHolder
            val chan = get(configurationSet.chanName)
            holder.configure(postItem, configurationSet)

            val bumpLimitReached = postItem.getBumpLimitReachedState(chan, 0) == BumpLimitState.REACHED
            val stateData = PostState.Predicate.Data(postItem, configurationSet, bumpLimitReached)
            for (i in PostState.THREAD_ITEM_STATES.indices) {
                val visible =
                    PostState.THREAD_ITEM_STATES[i]
                        .predicate
                        .apply(stateData)
                holder.stateImages!![i].setVisibility(if (visible) View.VISIBLE else View.GONE)
            }

            val subject = postItem.getSubject()
            if (!StringUtils.isEmpty(subject)) {
                holder.subject.setVisibility(View.VISIBLE)
                holder.subject.setText(subject)
            } else {
                holder.subject.setVisibility(View.GONE)
            }
            val parentWidth =
                (
                    obtainDensity(holder.itemView) *
                        holder.itemView
                            .getResources()
                            .getConfiguration()
                            .screenWidthDp
                ).toInt()
            var comment = postItem.getThreadCommentShort(parentWidth, holder.comment.getTextSize(), 8)
            colorScheme.apply(postItem.getThreadCommentShortSpans())
            if (StringUtils.isEmpty(subject) && StringUtils.isEmpty(comment)) {
                // Avoid 0 height
                comment = " "
            }
            holder.comment.setText(comment)
            holder.comment.setVisibility(if (holder.comment.getText().length > 0) View.VISIBLE else View.GONE)
            holder.description.clear()
            postItem.formatThreadCardDescription(
                context.getResources(),
                false,
                DescriptionBuilder { value: String -> holder.description.append(value) },
            )

            val attachmentItems = postItem.getAttachmentItems()
            if (attachmentItems != null) {
                val attachmentItem = attachmentItems[0]
                val needShowMultipleIcon = attachmentItems.size > 1
                attachmentItem.configureAndLoad(holder.thumbnail, chan, needShowMultipleIcon, false)
                holder.thumbnailClickListener.update(0, true, NavigatePostMode.DISABLED)
                holder.thumbnailLongClickListener.update(attachmentItem)
                holder.thumbnail.setSfwMode(isSfwMode)
                holder.thumbnail.setVisibility(View.VISIBLE)
            } else {
                ImageLoader.getInstance().cancel(holder.thumbnail)
                holder.thumbnail.resetImage(null)
                holder.thumbnail.setVisibility(View.GONE)
            }
            holder.thumbnail.setOnClickListener(holder.thumbnailClickListener)
            holder.thumbnail.setOnLongClickListener(holder.thumbnailLongClickListener)
        }

        private fun bindThreadCellSubject(
            holder: ThreadViewHolder,
            postItem: PostItem,
            hidden: Boolean,
        ) {
            val subject = postItem.getSubject()
            if (!StringUtils.isEmptyOrWhitespace(subject) && !hidden) {
                holder.subject.setVisibility(View.VISIBLE)
                holder.subject.setSingleLine(true)
                val builder = SpannableStringBuilder(subject.trim { it <= ' ' })
                builder.setSpan(
                    RelativeSizeSpan(4f / 3f),
                    0,
                    builder.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(
                    TypefaceSpan("sans-serif-light"),
                    0,
                    builder.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                holder.subject.setText(builder)
            } else {
                holder.subject.setVisibility(View.GONE)
            }
        }

        fun bindThreadCellView(
            viewHolder: RecyclerView.ViewHolder?,
            postItem: PostItem,
            configurationSet: ConfigurationSet,
            contentHeight: Int,
        ) {
            val context = uiManager.context
            val colorScheme = getColorScheme(context)
            val holder: ThreadViewHolder = viewHolder as ThreadViewHolder
            val chan = get(configurationSet.chanName)
            holder.configure(postItem, configurationSet)

            val attachmentItems = postItem.getAttachmentItems()
            val hidden = postItem.getHideState().hidden
            (holder.threadContent!!.getParent() as View).setAlpha(if (hidden) ALPHA_HIDDEN_POST else 1f)
            bindThreadCellSubject(holder, postItem, hidden)
            var comment: CharSequence? = null
            if (hidden) {
                comment = postItem.getHideReason()
            } else {
                val parentWidth =
                    (
                        obtainDensity(holder.itemView) *
                            holder.itemView
                                .getResources()
                                .getConfiguration()
                                .screenWidthDp
                    ).toInt()
                comment =
                    postItem.getThreadCommentShort(
                        parentWidth / 2,
                        holder.comment.getTextSize(),
                        if (attachmentItems != null) 4 else 12,
                    )
                colorScheme.apply(postItem.getThreadCommentShortSpans())
            }
            holder.comment.setText(comment)
            holder.comment.setVisibility(if (StringUtils.isEmpty(comment)) View.GONE else View.VISIBLE)
            holder.description.clear()
            postItem.formatThreadCardDescription(
                context.getResources(),
                true,
                DescriptionBuilder { value: String -> holder.description.append(value) },
            )

            if (attachmentItems != null && !hidden) {
                val attachmentItem = attachmentItems[0]
                val needShowMultipleIcon = attachmentItems.size > 1
                attachmentItem.configureAndLoad(holder.thumbnail, chan, needShowMultipleIcon, false)
                holder.thumbnailClickListener.update(0, true, NavigatePostMode.DISABLED)
                holder.thumbnailLongClickListener.update(attachmentItem)
                holder.thumbnail.setSfwMode(isSfwMode)
                holder.thumbnail.setVisibility(View.VISIBLE)
            } else {
                ImageLoader.getInstance().cancel(holder.thumbnail)
                holder.thumbnail.resetImage(null)
                holder.thumbnail.setVisibility(View.GONE)
            }
            holder.thumbnail.setOnClickListener(holder.thumbnailClickListener)
            holder.thumbnail.setOnLongClickListener(holder.thumbnailLongClickListener)

            holder.threadContent.getLayoutParams().height = contentHeight
        }

        fun bindThreadViewReloadAttachment(
            viewHolder: RecyclerView.ViewHolder?,
            attachmentItem: AttachmentItem,
        ) {
            val holder: ThreadViewHolder = viewHolder as ThreadViewHolder
            val attachmentItems: List<AttachmentItem>? =
                holder.postItem.getAttachmentItems()
            if (attachmentItems != null && !attachmentItems.isEmpty() && attachmentItems[0] === attachmentItem) {
                val chan = get(holder.configurationSet.chanName)
                attachmentItem.startLoad(holder.thumbnail, chan, true)
            }
        }

        fun bindThreadHiddenView(
            viewHolder: RecyclerView.ViewHolder?,
            postItem: PostItem,
            configurationSet: ConfigurationSet,
        ) {
            val holder: HiddenViewHolder = viewHolder as HiddenViewHolder
            holder.configure(postItem, configurationSet)
            var description = postItem.getHideReason()
            if (description == null) {
                description = postItem.getSubjectOrComment()
            }
            holder.comment.setText(description)
        }

        fun bindPostView(
            viewHolder: RecyclerView.ViewHolder?,
            postItem: PostItem,
            configurationSet: ConfigurationSet,
            demandSet: DemandSet,
        ) {
            val colorScheme = getColorScheme(uiManager.context)
            val holder: PostViewHolder = viewHolder as PostViewHolder
            val chan = get(configurationSet.chanName)
            holder.resetAnimations()
            holder.configure(postItem, configurationSet)
            holder.selection = demandSet.selection

            val postNumber = postItem.getPostNumber()
            var bumpLimitReached = false
            val bumpLimitReachedState = postItem.getBumpLimitReachedState(chan, 0)
            if (bumpLimitReachedState == BumpLimitState.REACHED) {
                bumpLimitReached = true
            } else if (bumpLimitReachedState == BumpLimitState.NEED_COUNT &&
                configurationSet.postsProvider != null
            ) {
                var postsCount = 0
                for (itPostItem in configurationSet.postsProvider) {
                    if (!itPostItem.isDeleted()) {
                        postsCount++
                    }
                }
                bumpLimitReached =
                    postItem.getBumpLimitReachedState(chan, postsCount) == BumpLimitState.REACHED
            }
            holder.number.setText("#" + postNumber)
            val stateData = PostState.Predicate.Data(postItem, configurationSet, bumpLimitReached)
            for (i in PostState.POST_ITEM_STATES.indices) {
                val visible =
                    PostState.POST_ITEM_STATES[i]
                        .predicate
                        .apply(stateData)
                holder.stateImages[i].setVisibility(if (visible) View.VISIBLE else View.GONE)
            }
            viewHolder.itemView.setAlpha(if (postItem.isDeleted()) ALPHA_DELETED_POST else 1f)

            val name = postItem.getFullName(chan)
            colorScheme.apply(postItem.getFullNameSpans())
            holder.name.setText(makeHighlightedText(demandSet.highlightText, name))
            holder.date.setText(postItem.getDateTime(postDateFormatter))

            if (postItem.isShowVotes()) {
                holder.votingState.likeText.setText(postItem.getLikes().toString())
                holder.votingState.likeText.setVisibility(View.VISIBLE)
                holder.votingState.likeImage.setVisibility(View.VISIBLE)
                holder.votingState.dislikeText.setText(postItem.getDislikes().toString())
                holder.votingState.dislikeText.setVisibility(View.VISIBLE)
                holder.votingState.dislikeImage.setVisibility(View.VISIBLE)
                holder.voting.setVisibility(View.VISIBLE)
            } else {
                holder.votingState.likeText.setVisibility(View.GONE)
                holder.votingState.likeImage.setVisibility(View.GONE)
                holder.votingState.dislikeText.setVisibility(View.GONE)
                holder.votingState.dislikeImage.setVisibility(View.GONE)
                holder.voting.setVisibility(View.GONE)
            }

            val subject = postItem.getSubject()
            val comment =
                if (configurationSet.repliesToPost != null) {
                    postItem.getComment(chan, configurationSet.repliesToPost)
                } else {
                    postItem.getComment(chan)
                }
            colorScheme.apply(postItem.getCommentSpans())
            val linkSuffixSpans = postItem.getLinkSuffixSpansAfterComment()

            val border = holder.border
            var borderStyle: PostBorderView.BorderStyle? = null
            val showPostsBorders = !configurationSet.isDialog && isShowPostsBorders

            val setBorderStyleUserPost =
                showPostsBorders && configurationSet.postStateProvider.isUserPost(postNumber)
            if (setBorderStyleUserPost) {
                borderStyle = PostBorderView.BorderStyle.USER_POST
            }

            if (linkSuffixSpans != null) {
                val showMyPosts = isShowMyPosts
                for (span in linkSuffixSpans) {
                    val isReply = configurationSet.postStateProvider.isUserPost(span.postNumber)
                    val showReply = showMyPosts && isReply
                    span.setSuffix(LinkSuffixSpan.SUFFIX_USER_POST, showReply)

                    // A same-thread reference whose target the client has flagged deleted gets an "X".
                    // Restricted to same-thread references (a different-thread link's number could
                    // collide with a local post) and resolved against the loaded thread, so it also
                    // lights up an ordinary link whose target was deleted after it was posted.
                    val referencedDeleted =
                        !span.isSuffixPresent(LinkSuffixSpan.SUFFIX_DIFFERENT_THREAD) &&
                            span.postNumber != null &&
                            configurationSet.postsProvider?.findPostItem(span.postNumber)?.isDeleted() == true
                    span.setSuffix(LinkSuffixSpan.SUFFIX_DELETED_POST, referencedDeleted)

                    val setBorderStyleReply = showPostsBorders && borderStyle == null && isReply
                    if (setBorderStyleReply) {
                        borderStyle = PostBorderView.BorderStyle.REPLY
                    }
                }
            }
            border.setBorderStyle(borderStyle)

            val linkSpans = postItem.getLinkSpansAfterComment()
            if (linkSpans != null) {
                for (linkSpan in linkSpans) {
                    if (linkSpan.postNumber != null) {
                        var hidden = false
                        if (postItem.getReferencesTo().contains(linkSpan.postNumber) &&
                            configurationSet.postsProvider != null
                        ) {
                            val linkPostItem =
                                configurationSet.postsProvider.findPostItem(linkSpan.postNumber)
                            if (linkPostItem != null) {
                                hidden =
                                    configurationSet.postStateProvider.isHiddenResolve(linkPostItem)
                            }
                        }
                        linkSpan.setHidden(hidden)
                    }
                }
            }
            holder.commentTextView.setSpoilersEnabled(!isShowSpoilers)
            holder.commentTextView.setSubjectAndComment(
                makeHighlightedText(demandSet.highlightText, subject),
                makeHighlightedText(demandSet.highlightText, comment),
            )
            holder.commentTextView.setVisibility(if (subject.length > 0 || comment.length > 0) View.VISIBLE else View.GONE)
            holder.commentTextView.bindSelectionPaddingView(if (demandSet.lastInList) holder.textSelectionPadding else null)

            handlePostViewIcons(holder)
            handlePostViewAttachments(holder)
            holder.index.setText(postItem.getOrdinalIndexString())
            val showName =
                holder.thumbnail.getVisibility() == View.VISIBLE ||
                    (!postItem.isUseDefaultName() && !StringUtils.isEmpty(name))
            holder.name.setVisibility(if (showName) View.VISIBLE else View.GONE)
            val showIndex = postItem.getOrdinalIndex() != PostItem.ORDINAL_INDEX_NONE
            holder.index.setVisibility(if (showIndex) View.VISIBLE else View.GONE)

            if (demandSet.selection == UiManager.Selection.THREADSHOT) {
                holder.bottomBarReplies.setVisibility(View.GONE)
                holder.bottomBarExpand.setVisibility(View.GONE)
                holder.bottomBarOpenThread.setVisibility(View.GONE)
            } else {
                val replyCount = postItem.getPostReplyCount()
                if (postItem.getPostReplyCount() > 0) {
                    holder.bottomBarReplies.setText(
                        holder.itemView
                            .getResources()
                            .getQuantityString(R.plurals.number_replies__format, replyCount, replyCount),
                    )
                    holder.bottomBarReplies.setVisibility(View.VISIBLE)
                } else {
                    holder.bottomBarReplies.setVisibility(View.GONE)
                }
                holder.bottomBarExpand.setVisibility(View.GONE)
                holder.bottomBarOpenThread.setVisibility(if (demandSet.showOpenThreadButton) View.VISIBLE else View.GONE)
            }
            var resetLimit = true
            if (configurationSet.mayCollapse &&
                !configurationSet.postStateProvider.isExpanded(
                    postNumber,
                )
            ) {
                val maxLines = postMaxLines
                if (maxLines > 0) {
                    resetLimit = false
                    holder.commentTextView.setLinesLimit(
                        maxLines,
                        holder.dimensions.commentAdditionalHeight,
                    )
                }
            }
            if (resetLimit) {
                holder.commentTextView.setLinesLimit(0, 0)
            }
            holder.bottomBarExpand.setVisibility(View.GONE)
            handlePostViewDecorator(holder, postItem, demandSet, chan)
            holder.invalidateBottomBar()

            val viewsEnabled = demandSet.selection == UiManager.Selection.DISABLED
            holder.thumbnail.setEnabled(viewsEnabled)
            holder.commentTextView.setEnabled(viewsEnabled)
            holder.head.setEnabled(viewsEnabled)
            holder.bottomBarReplies.setEnabled(viewsEnabled)
            holder.bottomBarReplies.setClickable(viewsEnabled)
            holder.bottomBarExpand.setEnabled(viewsEnabled)
            holder.bottomBarExpand.setClickable(viewsEnabled)
            holder.bottomBarOpenThread.setEnabled(viewsEnabled)
            holder.bottomBarOpenThread.setClickable(viewsEnabled)
            holder.installBackground()
        }

        fun bindPostViewInvalidateComment(viewHolder: RecyclerView.ViewHolder?) {
            val holder: PostViewHolder = viewHolder as PostViewHolder
            holder.commentTextView.invalidateAllSpans()
        }

        fun bindPostViewReloadAttachment(
            viewHolder: RecyclerView.ViewHolder?,
            attachmentItem: AttachmentItem,
        ) {
            val holder: PostViewHolder = viewHolder as PostViewHolder
            val attachmentItems: List<AttachmentItem>? =
                holder.postItem.getAttachmentItems()
            var attachmentView: AttachmentView? = null
            if (attachmentItems != null) {
                val index = attachmentItems.indexOf(attachmentItem)
                if (index >= 0) {
                    if (holder.attachmentViewCount >= 2) {
                        attachmentView = holder.attachmentHolders!![index].thumbnail
                    } else {
                        attachmentView = holder.thumbnail
                    }
                }
            }
            if (attachmentView != null) {
                val chan = get(holder.configurationSet.chanName)
                attachmentItem.startLoad(attachmentView, chan, true)
            }
        }

        fun bindPostHiddenView(
            viewHolder: RecyclerView.ViewHolder?,
            postItem: PostItem,
            configurationSet: ConfigurationSet,
        ) {
            val holder: HiddenViewHolder = viewHolder as HiddenViewHolder
            holder.configure(postItem, configurationSet)
            holder.index.setText(postItem.getOrdinalIndexString())
            holder.number.setText("#" + postItem.getPostNumber())
            var description = postItem.getHideReason()
            if (description == null) {
                description = postItem.getSubjectOrComment()
            }
            holder.comment.setText(description)
            configurationSet.postStateProvider.setRead(postItem.getPostNumber())
        }

        @SuppressLint("InflateParams")
        private fun handlePostViewAttachments(holder: PostViewHolder) {
            val postItem = holder.postItem
            val configurationSet = holder.configurationSet
            val context = uiManager.context
            val chan = get(configurationSet.chanName)
            val attachmentItems = postItem.getAttachmentItems()
            if (attachmentItems != null && !attachmentItems.isEmpty()) {
                val size = attachmentItems.size
                if (size >= 2 && isAllAttachments) {
                    holder.thumbnail.resetImage(null)
                    holder.thumbnail.setVisibility(View.GONE)
                    holder.attachmentInfo.setVisibility(View.GONE)
                    var attachmentHolders = holder.attachmentHolders
                    if (attachmentHolders == null) {
                        attachmentHolders = ArrayList<AttachmentHolder>()
                        holder.attachmentHolders = attachmentHolders
                    }
                    val holders = attachmentHolders.size
                    if (holders < size) {
                        val postBackgroundColor: Int =
                            getPostBackgroundColor(uiManager.context, configurationSet)
                        val thumbnailsScale = thumbnailsScale
                        val textScale = textScale
                        for (i in holders..<size) {
                            val view =
                                LayoutInflater
                                    .from(context)
                                    .inflate(R.layout.list_item_post_attachment, null)
                            val attachmentHolder = AttachmentHolder()
                            attachmentHolder.container = view
                            attachmentHolder.thumbnail =
                                view.findViewById<AttachmentView>(R.id.thumbnail)
                            attachmentHolder.attachmentInfo =
                                view.findViewById<TextView>(R.id.attachment_info)
                            attachmentHolder.thumbnail.setDrawTouching(true)
                            attachmentHolder.thumbnail.applyRoundedCorners(postBackgroundColor)
                            attachmentHolder.thumbnail.setOnClickListener(attachmentHolder.thumbnailClickListener)
                            attachmentHolder.thumbnail.setOnLongClickListener(attachmentHolder.thumbnailLongClickListener)
                            attachmentHolder.attachmentInfo.getLayoutParams().width =
                                holder.dimensions.multipleAttachmentInfoWidth
                            val thumbnailLayoutParams = attachmentHolder.thumbnail.getLayoutParams()
                            if (thumbnailsScale != 1f) {
                                thumbnailLayoutParams.width =
                                    (holder.dimensions.thumbnailWidth * thumbnailsScale).toInt()
                                thumbnailLayoutParams.height = thumbnailLayoutParams.width
                            } else {
                                thumbnailLayoutParams.width = holder.dimensions.thumbnailWidth
                                thumbnailLayoutParams.height = holder.dimensions.thumbnailWidth
                            }
                            if (textScale != 1f) {
                                applyScaleSize(textScale, attachmentHolder.attachmentInfo)
                            }
                            attachmentHolders.add(attachmentHolder)
                            holder.attachments.addView(view)
                        }
                    }
                    val sfwMode = isSfwMode
                    for (i in 0..<size) {
                        val attachmentHolder = attachmentHolders[i]
                        val attachmentItem = attachmentItems[i]
                        attachmentItem.configureAndLoad(
                            attachmentHolder.thumbnail,
                            chan,
                            false,
                            false,
                        )
                        attachmentHolder.thumbnailClickListener.update(
                            i,
                            false,
                            if (configurationSet.isDialog) {
                                NavigatePostMode.MANUALLY
                            } else {
                                NavigatePostMode.ENABLED
                            },
                        )
                        attachmentHolder.thumbnailLongClickListener.update(attachmentItem)
                        attachmentHolder.thumbnail.setSfwMode(sfwMode)
                        attachmentHolder.attachmentInfo.setText(
                            attachmentItem.getDescription(
                                AttachmentItem.FormatMode
                                    .THREE_LINES,
                            ),
                        )
                        attachmentHolder.container.setVisibility(View.VISIBLE)
                    }
                    for (i in size..<holders) {
                        val attachmentHolder = attachmentHolders[i]
                        ImageLoader.getInstance().cancel(attachmentHolder.thumbnail)
                        attachmentHolder.thumbnail.resetImage(null)
                        attachmentHolder.container.setVisibility(View.GONE)
                    }
                    holder.attachments.setVisibility(View.VISIBLE)
                    holder.attachmentViewCount = size
                } else {
                    val attachmentItem = attachmentItems[0]
                    attachmentItem.configureAndLoad(holder.thumbnail, chan, size > 1, false)
                    holder.thumbnailClickListener.update(
                        0,
                        true,
                        if (configurationSet.isDialog) {
                            NavigatePostMode.MANUALLY
                        } else {
                            NavigatePostMode.ENABLED
                        },
                    )
                    holder.thumbnailLongClickListener.update(attachmentItem)
                    holder.thumbnail.setSfwMode(isSfwMode)
                    holder.thumbnail.setVisibility(View.VISIBLE)
                    holder.attachmentInfo.setText(
                        postItem.getAttachmentsDescription(
                            context.getResources(),
                            AttachmentItem.FormatMode.LONG,
                        ),
                    )
                    holder.attachmentInfo.setVisibility(View.VISIBLE)
                    holder.attachments.setVisibility(View.GONE)
                    holder.attachmentViewCount = 1
                }
            } else {
                ImageLoader.getInstance().cancel(holder.thumbnail)
                holder.thumbnail.resetImage(null)
                holder.thumbnail.setVisibility(View.GONE)
                holder.attachmentInfo.setVisibility(View.GONE)
                holder.attachments.setVisibility(View.GONE)
                holder.attachmentViewCount = 1
            }
        }

        /**
         * Gives the chan's [chan.content.ChanPostDecorator], if it has one, the chance to attach a
         * view to this post. Skipped for threadshots, which render offscreen with no interaction and
         * would otherwise let a decorator start work nothing will consume.
         */
        private fun handlePostViewDecorator(
            holder: PostViewHolder,
            postItem: PostItem,
            demandSet: DemandSet,
            chan: Chan,
        ) {
            val state = holder.decoratorState
            if (demandSet.selection == UiManager.Selection.THREADSHOT) {
                uiManager.decorator().unbindPostView(state)
                state.slot.setVisibility(View.GONE)
                return
            }
            val view =
                uiManager.decorator().bindPostView(
                    chan,
                    state,
                    postItem,
                    postItem.getBoardName(),
                    postItem.getThreadNumber(),
                    holder.configurationSet,
                )
            state.slot.setVisibility(if (view != null) View.VISIBLE else View.GONE)
        }

        private fun handlePostViewIcons(holder: PostViewHolder) {
            val postItem = holder.postItem
            val configurationSet = holder.configurationSet
            val context = uiManager.context
            val chan = get(configurationSet.chanName)
            val icons = postItem.getIcons()
            if (!icons.isEmpty() && isDisplayIcons) {
                val badgeImages =
                    holder.badgeImages
                        ?: ArrayList<ImageView>().also { holder.badgeImages = it }
                val count = badgeImages.size
                val add = icons.size - count
                // Create more image views for icons
                if (add > 0) {
                    val anchorView =
                        if (count > 0) badgeImages[count - 1] else holder.index
                    val anchorIndex = holder.head.indexOfChild(anchorView) + 1
                    val density = obtainDensity(context)
                    val size = (12f * density).toInt()
                    val textScale = textScale
                    for (i in 0..<add) {
                        val imageView = ImageView(context)
                        holder.head.addView(
                            imageView,
                            anchorIndex + i,
                            ViewGroup.LayoutParams(size, size),
                        )
                        if (textScale != 1f) {
                            applyScaleSize(textScale, imageView)
                        }
                        badgeImages.add(imageView)
                    }
                }
                for (i in badgeImages.indices) {
                    val imageView = badgeImages[i]
                    if (i < icons.size) {
                        imageView.setVisibility(View.VISIBLE)
                        var uri = icons[i].uri
                        if (uri != null) {
                            uri = if (uri.isRelative) chan.locator.convert(uri) else uri
                            ImageLoader.getInstance().loadImage(chan, uri!!, false, imageView)
                        } else {
                            ImageLoader.getInstance().cancel(imageView)
                            imageView.setTag(null)
                            imageView.setImageDrawable(null)
                        }
                    } else {
                        ImageLoader.getInstance().cancel(imageView)
                        imageView.setVisibility(View.GONE)
                    }
                }
            } else if (holder.badgeImages != null) {
                for (imageView in holder.badgeImages) {
                    imageView.setTag(null)
                    imageView.setVisibility(View.GONE)
                }
            }
        }

        private fun makeHighlightedText(
            highlightText: MutableCollection<String>,
            text: CharSequence?,
        ): CharSequence? {
            var currentText = text
            if (!highlightText.isEmpty() && currentText != null) {
                val locale = Locale.getDefault()
                val spannable = SpannableString(currentText)
                val searchable = currentText.toString().lowercase(locale)
                val colorScheme = getColorScheme(uiManager.context)
                for (highlight in highlightText) {
                    val lowercaseHighlight = highlight.lowercase(locale)
                    var textIndex = -1
                    while ((
                            searchable
                                .indexOf(lowercaseHighlight, textIndex + 1)
                                .also { textIndex = it }
                        ) >= 0
                    ) {
                        spannable.setSpan(
                            BackgroundColorSpan(colorScheme.highlightTextColor),
                            textIndex,
                            textIndex + lowercaseHighlight.length,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    currentText = spannable
                }
            }
            return currentText
        }

        fun handlePostForDoubleClick(view: View): Boolean {
            val holder: PostViewHolder? =
                ListViewUtils.getViewHolder(view, PostViewHolder::class.java)
            if (holder != null) {
                if (holder.commentTextView.getVisibility() != View.VISIBLE || holder.commentTextView.isSelectionMode()) {
                    return false
                }
                val t = SystemClock.elapsedRealtime()
                val timeout = holder.commentTextView.preferredDoubleTapTimeout
                if (t - holder.lastCommentClick > timeout) {
                    holder.lastCommentClick = t
                } else {
                    val recyclerView = view.getParent() as RecyclerView
                    val position = recyclerView.getChildAdapterPosition(view)
                    holder.commentTextView.startSelection()
                    val padding = holder.commentTextView.selectionPadding
                    if (padding > 0) {
                        val listHeight =
                            recyclerView.getHeight() - recyclerView.getPaddingTop() -
                                recyclerView.getPaddingBottom()
                        recyclerView.post(
                            Runnable {
                                val end = holder.commentTextView.getSelectionEnd()
                                if (end >= 0) {
                                    val layout = holder.commentTextView.getLayout()
                                    val line = layout.getLineForOffset(end)
                                    val count = layout.getLineCount()
                                    if (count - line <= 4) {
                                        (recyclerView.getLayoutManager() as LinearLayoutManager)
                                            .scrollToPositionWithOffset(
                                                position,
                                                listHeight - view.getHeight(),
                                            )
                                    }
                                }
                            },
                        )
                    }
                }
                return true
            } else {
                return false
            }
        }

        private val repliesBlockClickListener =
            View.OnClickListener { v ->
                val holder: PostViewHolder? =
                    ListViewUtils.getViewHolder(v, PostViewHolder::class.java)
                uiManager
                    .dialog()
                    .displayReplies(holder!!.configurationSet, holder.postItem)
            }

        private val threadLinkBlockClickListener =
            View.OnClickListener { v ->
                val holder: PostViewHolder? =
                    ListViewUtils.getViewHolder(v, PostViewHolder::class.java)
                val postItem = holder!!.postItem
                val postNumber = if (postItem.isOriginalPost()) null else postItem.getPostNumber()
                uiManager.navigator()!!.navigatePosts(
                    holder.configurationSet.chanName,
                    postItem.getBoardName(),
                    postItem.getThreadNumber(),
                    postNumber,
                    null,
                )
            }

        private val threadShowOriginalPostClickListener =
            View.OnClickListener { v ->
                val holder: ThreadViewHolder? =
                    ListViewUtils.getViewHolder(v, ThreadViewHolder::class.java)
                uiManager
                    .dialog()
                    .displayThread(holder!!.configurationSet, holder.postItem)
            }

        private val headContentTouchListener: OnTouchListener =
            object : OnTouchListener {
                private val TYPE_NONE = 0
                private val TYPE_BADGES = 1
                private val TYPE_STATES = 2

                private var type = 0
                private var startX = 0f
                private var startY = 0f

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(
                    v: View,
                    event: MotionEvent,
                ): Boolean {
                    when (event.getAction()) {
                        MotionEvent.ACTION_DOWN -> {
                            type = TYPE_NONE
                            val x = event.getX()
                            val y = event.getY()
                            val holder: PostViewHolder? =
                                ListViewUtils.getViewHolder(v, PostViewHolder::class.java)
                            val head = holder!!.head
                            var i = 0
                            while (i < head.getChildCount()) {
                                val child = head.getChildAt(i)
                                if (child.getVisibility() == View.VISIBLE) {
                                    val width = child.getWidth()
                                    val height = child.getHeight()
                                    val radius =
                                        (
                                            sqrt(
                                                width.toDouble().pow(2.0) + height.toDouble().pow(2.0),
                                            ) / 2f
                                        ).toInt()
                                    val centerX = child.getLeft() + child.getWidth() / 2
                                    val centerY = child.getTop() + child.getHeight() / 2
                                    val distance =
                                        (
                                            sqrt(
                                                (centerX - x).toDouble().pow(2.0) +
                                                    (centerY - y)
                                                        .toDouble()
                                                        .pow(2.0),
                                            ) / 2f
                                        ).toInt()
                                    if (distance <= radius * 3 / 2) {
                                        startX = x
                                        startY = y
                                        // noinspection SuspiciousMethodCalls
                                        if (holder.badgeImages?.contains(child) == true) {
                                            type = TYPE_BADGES
                                            return true
                                        }
                                        // noinspection SuspiciousMethodCalls
                                        if (Arrays
                                                .asList<ImageView>(*holder.stateImages)
                                                .contains(child)
                                        ) {
                                            type = TYPE_STATES
                                            return true
                                        }
                                    }
                                }
                                i++
                            }
                        }

                        MotionEvent.ACTION_UP -> {
                            if (type != TYPE_NONE) {
                                val context = uiManager.context
                                val holder: PostViewHolder? =
                                    ListViewUtils.getViewHolder(
                                        v,
                                        PostViewHolder::class.java,
                                    )
                                val postItem = holder!!.postItem
                                val configurationSet = holder.configurationSet
                                val touchSlop = ViewConfiguration.get(context).getScaledTouchSlop()
                                if (abs(event.getX() - startX) <= touchSlop &&
                                    abs(event.getY() - startY) <= touchSlop
                                ) {
                                    val icons = ArrayList<IconData>()
                                    val emailToCopy: String? = null
                                    when (type) {
                                        TYPE_BADGES -> {
                                            val chan = get(configurationSet.chanName)
                                            val postIcons = postItem.getIcons()
                                            for (postIcon in postIcons) {
                                                var uri = postIcon.uri
                                                if (uri != null) {
                                                    uri =
                                                        if (uri.isRelative) chan.locator.convert(uri) else uri
                                                }
                                                icons.add(IconData(postIcon.title, uri))
                                            }
                                        }

                                        TYPE_STATES -> {
                                            var i = 0
                                            while (i < PostState.POST_ITEM_STATES.size) {
                                                if (holder.stateImages[i].getVisibility() == View.VISIBLE) {
                                                    val postState = PostState.POST_ITEM_STATES[i]
                                                    val title =
                                                        postState.titleProvider
                                                            .get(uiManager.context, postItem)
                                                    icons.add(IconData(title, postState.iconAttrResId))
                                                }
                                                i++
                                            }
                                        }
                                    }
                                    uiManager.dialog().showPostDescriptionDialog(
                                        configurationSet.fragmentManager!!,
                                        icons,
                                        configurationSet.chanName,
                                        emailToCopy,
                                    )
                                }
                                return true
                            }
                        }
                    }
                    return false
                }
            }

        init {
            val context = uiManager.context
            this.uiManager = uiManager
            postDateFormatter = PostDateFormatter(context)

            extraButtons =
                Arrays
                    .asList<ExtraButton?>(
                        ExtraButton(
                            context.getString(R.string.quote__verb),
                            R.attr.iconActionPaste,
                            ExtraButton.Callback { view: CommentTextView, text: ExtraButton.Text, click: Boolean ->
                                val holder: PostViewHolder? =
                                    ListViewUtils.getViewHolder(
                                        view,
                                        PostViewHolder::class.java,
                                    )
                                val configurationSet = holder!!.configurationSet
                                if (configurationSet.replyable != null &&
                                    configurationSet.replyable.onRequestReply(
                                        false,
                                    )
                                ) {
                                    if (click) {
                                        configurationSet.replyable.onRequestReply(
                                            true,
                                            ReplyData(
                                                holder.postItem.getPostNumber(),
                                                text.toPreparedString(view),
                                            ),
                                        )
                                    }
                                    return@Callback true
                                }
                                false
                            },
                        ),
                        ExtraButton(
                            context.getString(R.string.web_browser),
                            R.attr.iconActionForward,
                            ExtraButton.Callback { view: CommentTextView, text: ExtraButton.Text, click: Boolean ->
                                val uri: Uri? = extractUri(text.toString())
                                if (uri != null) {
                                    if (click) {
                                        val holder: PostViewHolder? =
                                            ListViewUtils.getViewHolder(
                                                view,
                                                PostViewHolder::class.java,
                                            )
                                        val configurationSet = holder!!.configurationSet
                                        val linkListener =
                                            if (configurationSet.linkListener != null) {
                                                configurationSet.linkListener
                                            } else {
                                                defaultLinkListener
                                            }
                                        linkListener.onLinkClick(view, uri, LinkListener.Extra.EMPTY, true)
                                    }
                                    return@Callback true
                                }
                                false
                            },
                        ),
                        ExtraButton(
                            context.getString(R.string.add_theme),
                            R.attr.iconActionAddRule,
                            ExtraButton.Callback { view: CommentTextView, text: ExtraButton.Text, click: Boolean ->
                                val theme = fastParseThemeFromText(context, text.toString())
                                if (theme != null) {
                                    if (click) {
                                        uiManager.navigator()!!.navigateSetTheme(theme)
                                    }
                                    return@Callback true
                                }
                                false
                            },
                        ),
                        ExtraButton(
                            context.getString(R.string.add_command),
                            R.attr.iconActionAddRule,
                            ExtraButton.Callback { view: CommentTextView, text: ExtraButton.Text, click: Boolean ->
                                val import = CommandsStorage.fastParseImportFromText(text.toString())
                                if (!import.isEmpty) {
                                    if (click) {
                                        uiManager.navigator()!!.navigateAddCommand(import)
                                    }
                                    return@Callback true
                                }
                                false
                            },
                        ),
                    )
        }

        private enum class PostState(
            val iconAttrResId: Int,
            val titleProvider: TitleProvider,
            val predicate: Predicate,
        ) {
            USER_POST(
                R.attr.iconPostUserPost,
                R.string.my_post,
                PostState.Predicate { data: Predicate.Data ->
                    isShowMyPosts && data.configurationSet.postStateProvider.isUserPost(data.postItem.getPostNumber())
                },
            ),
            ORIGINAL_POSTER(
                R.attr.iconPostOriginalPoster,
                R.string.original_poster,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isOriginalPoster() },
            ),
            SAGE(
                R.attr.iconPostSage,
                R.string.doesnt_bring_up_thread,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isSage() || data.bumpLimitReached },
            ),
            EMAIL(
                R.attr.iconPostEmail,
                TitleProvider { _: Context, postItem: PostItem ->
                    var email = postItem.getEmail()
                    if (email.startsWith("mailto:")) {
                        email = email.substring(7)
                    }
                    email
                },
                PostState.Predicate { data: Predicate.Data -> !isEmpty(data.postItem.getEmail()) },
            ),
            STICKY(
                R.attr.iconPostSticky,
                R.string.sticky_thread,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isSticky() },
            ),
            CLOSED(
                R.attr.iconPostClosed,
                R.string.thread_is_closed,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isClosed() },
            ),
            CYCLICAL(
                R.attr.iconPostCyclical,
                R.string.cyclical_thread,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isCyclical() },
            ),
            WARNED(
                R.attr.iconPostWarned,
                R.string.user_is_warned,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isPosterWarned() },
            ),
            BANNED(
                R.attr.iconPostBanned,
                R.string.user_is_banned,
                PostState.Predicate { data: Predicate.Data -> data.postItem.isPosterBanned() },
            ),
            ;

            fun interface TitleProvider {
                fun get(
                    context: Context,
                    postItem: PostItem,
                ): String?
            }

            fun interface Predicate {
                class Data(
                    val postItem: PostItem,
                    val configurationSet: ConfigurationSet,
                    val bumpLimitReached: Boolean,
                )

                fun apply(data: Data): Boolean
            }

            constructor(iconAttrResId: Int, titleResId: Int, predicate: Predicate) : this(
                iconAttrResId,
                TitleProvider { c: Context, _: PostItem -> c.getString(titleResId) },
                predicate,
            )

            companion object {
                val POST_ITEM_STATES: List<PostState> =
                    listOf(
                        PostState.USER_POST,
                        PostState.ORIGINAL_POSTER,
                        PostState.SAGE,
                        PostState.EMAIL,
                        PostState.STICKY,
                        PostState.CLOSED,
                        PostState.CYCLICAL,
                        PostState.WARNED,
                        PostState.BANNED,
                    )

                val THREAD_ITEM_STATES: List<PostState> =
                    listOf(
                        PostState.SAGE,
                        PostState.STICKY,
                        PostState.CLOSED,
                        PostState.CYCLICAL,
                    )
            }
        }

        private inner class AttachmentHolder {
            lateinit var thumbnail: AttachmentView
            val thumbnailClickListener: ThumbnailClickListener
            val thumbnailLongClickListener: ThumbnailLongClickListener

            lateinit var container: View
            lateinit var attachmentInfo: TextView

            init {
                thumbnailClickListener = uiManager.interaction().createThumbnailClickListener()
                thumbnailLongClickListener = uiManager.interaction().createThumbnailLongClickListener()
            }
        }

        private class Lazy<T : Any> {
            interface Provider<T> {
                fun createLazy(): T
            }

            private var data: T? = null

            fun get(provider: Provider<T>): T = data ?: provider.createLazy().also { data = it }
        }

        private open class BasePostViewHolder(
            itemView: View,
        ) : RecyclerView.ViewHolder(itemView),
            UiManager.Holder,
            ClickCallback<Unit?, BasePostViewHolder> {
            private lateinit var postItemRef: WeakReference<PostItem>
            private lateinit var configurationSetRef: WeakReference<ConfigurationSet>

            protected open fun onConfigure(
                postItem: PostItem,
                configurationSet: ConfigurationSet,
            ) {}

            fun configure(
                postItem: PostItem,
                configurationSet: ConfigurationSet,
            ) {
                this.postItemRef = WeakReference(postItem)
                this.configurationSetRef = WeakReference(configurationSet)
                onConfigure(postItem, configurationSet)
            }

            override val postItem: PostItem
                get() = postItemRef.get()!!

            override val configurationSet: ConfigurationSet
                get() = configurationSetRef.get()!!

            override fun onItemClick(
                holder: BasePostViewHolder,
                position: Int,
                item: Unit?,
                longClick: Boolean,
            ): Boolean =
                configurationSet.clickCallback!!.onItemClick(
                    holder,
                    position,
                    postItem,
                    longClick,
                )
        }

        private class ThreadViewHolder(
            parent: ViewGroup,
            uiManager: UiManager,
            threadViewType: ThreadViewType?,
        ) : BasePostViewHolder(
                if (threadViewType != ThreadViewType.LIST) {
                    createCardLayout(parent)
                } else {
                    LayoutInflater
                        .from(parent.getContext())
                        .inflate(R.layout.list_item_thread, parent, false)
                },
            ) {
            private val cardView: CardView?
            val thumbnail: AttachmentView
            val subject: TextView
            val comment: TextView
            val description: ThreadDescriptionView
            val stateImages: Array<ImageView>?
            val threadContent: View?
            val showOriginalPost: View?

            val thumbnailClickListener: ThumbnailClickListener
            val thumbnailLongClickListener: ThumbnailLongClickListener

            init {
                if (threadViewType != ThreadViewType.LIST) {
                    cardView = itemView as CardView
                    val cardContent = cardView.getChildAt(0) as ViewGroup
                    val inflater = LayoutInflater.from(itemView.getContext())
                    inflater.inflate(
                        if (threadViewType == ThreadViewType.CELL) {
                            R.layout.list_item_thread_cell
                        } else {
                            R.layout.list_item_thread_card
                        },
                        cardContent,
                    )
                    ListViewUtils.bind<Unit?, BasePostViewHolder>(this, cardContent, true, null, this)
                } else {
                    cardView = null
                    setSelectableItemBackground(itemView)
                    ListViewUtils.bind<Unit?, BasePostViewHolder>(this, itemView, true, null, this)
                }

                thumbnail = itemView.findViewById<AttachmentView>(R.id.thumbnail)
                subject = itemView.findViewById<TextView>(R.id.subject)
                comment = itemView.findViewById<TextView>(R.id.comment)
                description = itemView.findViewById<ThreadDescriptionView>(R.id.thread_description)
                threadContent = itemView.findViewById<View>(R.id.thread_content)
                val showOriginalPost = itemView.findViewById<ViewGroup?>(R.id.show_original_post)
                this.showOriginalPost = showOriginalPost
                (if (threadViewType == ThreadViewType.CELL) description else showOriginalPost)
                    .setOnClickListener(uiManager.view().threadShowOriginalPostClickListener)

                thumbnailClickListener = uiManager.interaction().createThumbnailClickListener()
                thumbnailLongClickListener = uiManager.interaction().createThumbnailLongClickListener()

                val density = obtainDensity(itemView)
                val textScale = textScale
                val descriptionSpacingDp = 8
                thumbnail.setDrawTouching(true)
                description.setTextColor(getTheme(description.getContext()).meta)
                description.setTextSizeSp(11f * textScale)
                description.setSpacing((descriptionSpacingDp * density).toInt())
                if (threadViewType == ThreadViewType.CELL) {
                    thumbnail.setFitSquare(true)
                    applyScaleSize(textScale, comment, subject)
                    stateImages = null
                    description.setToEnd(true)
                } else {
                    stateImages =
                        Companion.createStateImages(
                            showOriginalPost!!,
                            if (threadViewType == ThreadViewType.CARD) 1 else 0,
                            PostState.THREAD_ITEM_STATES,
                            0.5f,
                            (if (threadViewType == ThreadViewType.CARD) descriptionSpacingDp else 0).toFloat(),
                            (if (threadViewType == ThreadViewType.CARD) 0 else descriptionSpacingDp).toFloat(),
                        )
                    val thumbnailLayoutParams =
                        thumbnail.getLayoutParams() as MarginLayoutParams
                    applyScaleSize(textScale, comment, subject)
                    applyScaleSize(textScale, *stateImages)
                    if (isTablet(itemView.getResources().getConfiguration()) &&
                        threadViewType == ThreadViewType.CARD
                    ) {
                        description.setToEnd(false)
                        val thumbnailSize = (72f * density).toInt()
                        thumbnailLayoutParams.width = thumbnailSize
                        thumbnailLayoutParams.height = thumbnailSize
                        val descriptionPaddingLeft =
                            thumbnailSize + thumbnailLayoutParams.leftMargin +
                                thumbnailLayoutParams.rightMargin
                        description.setPadding(
                            descriptionPaddingLeft,
                            description.getPaddingTop(),
                            description.getPaddingRight(),
                            description.getPaddingBottom(),
                        )
                        comment.setMaxLines(8)
                    } else {
                        description.setToEnd(threadViewType != ThreadViewType.LIST)
                        comment.setMaxLines(6)
                    }
                    val thumbnailsScale = thumbnailsScale
                    if (thumbnailsScale != 1f) {
                        thumbnailLayoutParams.width =
                            (thumbnailLayoutParams.width * thumbnailsScale).toInt()
                        thumbnailLayoutParams.height =
                            (thumbnailLayoutParams.height * thumbnailsScale).toInt()
                    }
                }
            }

            override fun onConfigure(
                postItem: PostItem,
                configurationSet: ConfigurationSet,
            ) {
                val thumbnailBackground =
                    if (cardView != null) {
                        cardView.getBackgroundColor()
                    } else {
                        getPostBackgroundColor(itemView.getContext(), configurationSet)
                    }
                thumbnail.applyRoundedCorners(thumbnailBackground)
            }
        }

        private class NewPostAnimation(
            private val layout: PostLinearLayout,
            private val postStateProvider: PostStateProvider,
            private val postNumber: PostNumber?,
            startColor: Int,
            private val endColor: Int,
        ) : Runnable,
            AnimatorUpdateListener {
            private val drawable: ColorDrawable

            private var animator: ValueAnimator? = null
            private var applied = false

            init {
                drawable = ColorDrawable(startColor)
                layout.setSecondaryBackground(drawable)
                layout.postDelayed(this, 500)
            }

            override fun run() {
                val startColor = drawable.getColor()
                val animator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor)
                this.animator = animator
                animator.addUpdateListener(this)
                animator.setDuration(500)
                animator.start()
            }

            override fun onAnimationUpdate(animation: ValueAnimator) {
                if (!applied) {
                    applied = true
                    postStateProvider.setRead(postNumber)
                }
                drawable.setColor(animation.getAnimatedValue() as Int)
            }

            fun cancel() {
                layout.removeCallbacks(this)
                animator?.cancel()
                animator = null
            }
        }

        private class VoteState {
            val votingImages: Array<ImageView?> = arrayOfNulls(2)
            val votingText: Array<TextView?> = arrayOfNulls(2)
            lateinit var likeImage: ImageView
            lateinit var dislikeImage: ImageView
            lateinit var likeText: TextView
            lateinit var dislikeText: TextView

            // fillVoting() populates the arrays; this hands the four slots to the bind
            // path as non-null, keeping the assertions at the real nullable boundary.
            fun bindArrayToView() {
                likeImage = votingImages[0]!!
                dislikeImage = votingImages[1]!!
                likeText = votingText[0]!!
                dislikeText = votingText[1]!!
            }
        }

        private class PostViewHolder(
            parent: ViewGroup,
            private val uiManager: UiManager,
            dimensions: Lazy<Dimensions>,
        ) : BasePostViewHolder(
                LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_post, parent, false),
            ),
            Lazy.Provider<PostViewHolder.Dimensions>,
            RecyclerKeeper.Holder,
            OnAttachStateChangeListener,
            LimitListener,
            LinkListener,
            LinkConfiguration,
            View.OnClickListener {
            class Dimensions(
                val thumbnailWidth: Int,
                val multipleAttachmentInfoWidth: Int,
                val commentAdditionalHeight: Int,
            )

            val dimensions: Dimensions
            val layout: PostLinearLayout
            val border: PostBorderView
            val head: LinebreakLayout
            val voting: LinebreakLayout
            val number: TextView
            val name: TextView
            val index: TextView
            val date: TextView
            val attachments: ViewGroup
            val thumbnail: AttachmentView
            val attachmentInfo: TextView
            override val commentTextView: CommentTextView
            val textSelectionPadding: View?
            val decoratorState: DecoratorUnit.DecoratorState
            val textBarPadding: View
            val bottomBar: View
            val bottomBarReplies: TextView
            val bottomBarExpand: TextView
            val bottomBarOpenThread: TextView
            val votingState: VoteState = VoteState()

            var attachmentHolders: ArrayList<AttachmentHolder>? = null
            var attachmentViewCount: Int = 1
            var badgeImages: ArrayList<ImageView>? = null
            val stateImages: Array<ImageView>
            val highlightBackgroundColor: Int
            val highlightUserPostBackgroundColor: Int

            val thumbnailClickListener: ThumbnailClickListener
            val thumbnailLongClickListener: ThumbnailLongClickListener
            val defaultLinkListener: LinkListener

            var selection: UiManager.Selection? = null
            var expandAnimator: Animator? = null
            var newPostAnimation: NewPostAnimation? = null
            var lastCommentClick: Long = 0

            init {
                layout = itemView as PostLinearLayout
                layout.addOnAttachStateChangeListener(this)
                setSelectableItemBackground(layout)
                border = itemView.findViewById<PostBorderView>(R.id.border)
                head = itemView.findViewById<LinebreakLayout>(R.id.head)
                voting = itemView.findViewById<LinebreakLayout>(R.id.voting)
                number = itemView.findViewById<TextView>(R.id.number)
                name = itemView.findViewById<TextView>(R.id.name)
                index = itemView.findViewById<TextView>(R.id.index)
                date = itemView.findViewById<TextView>(R.id.date)
                stateImages =
                    createStateImages(
                        head,
                        head.indexOfChild(number) + 1,
                        PostState.POST_ITEM_STATES,
                        0f,
                        0f,
                        0f,
                    )
                fillVoting(voting, votingState, 0f, 0f, 0f)
                votingState.bindArrayToView()
                attachments = itemView.findViewById<ViewGroup>(R.id.attachments)
                thumbnail = itemView.findViewById<AttachmentView>(R.id.thumbnail)
                attachmentInfo = itemView.findViewById<TextView>(R.id.attachment_info)
                this.commentTextView = itemView.findViewById<CommentTextView>(R.id.comment)
                textSelectionPadding = itemView.findViewById<View?>(R.id.text_selection_padding)
                decoratorState =
                    DecoratorUnit.DecoratorState(itemView.findViewById<ViewGroup>(R.id.decorator))
                textBarPadding = itemView.findViewById<View>(R.id.text_bar_padding)
                bottomBar = itemView.findViewById<View>(R.id.bottom_bar)
                bottomBarReplies = itemView.findViewById<TextView>(R.id.bottom_bar_replies)
                bottomBarExpand = itemView.findViewById<TextView>(R.id.bottom_bar_expand)
                bottomBarOpenThread = itemView.findViewById<TextView>(R.id.bottom_bar_open_thread)

                val colorScheme = getColorScheme(itemView.getContext())
                highlightBackgroundColor = colorScheme.highlightBackgroundColor
                highlightUserPostBackgroundColor = colorScheme.highlightUserPostBackgroundColor

                thumbnailClickListener = uiManager.interaction().createThumbnailClickListener()
                thumbnailLongClickListener = uiManager.interaction().createThumbnailLongClickListener()
                defaultLinkListener = uiManager.view().defaultLinkListener
                ListViewUtils.bind<Unit?, BasePostViewHolder>(this, itemView, true, null, this)

                head.setOnTouchListener(uiManager.view().headContentTouchListener)
                commentTextView.setLimitListener(this)
                commentTextView.setSpanStateListener(uiManager.view().spanStateListener)
                commentTextView.setPrepareToCopyListener(uiManager.view().prepareToCopyListener)
                commentTextView.setLinkListener(this, this)
                commentTextView.setExtraButtons(uiManager.view().extraButtons)
                thumbnail.setOnClickListener(thumbnailClickListener)
                thumbnail.setOnLongClickListener(thumbnailLongClickListener)
                bottomBarReplies.setOnClickListener(uiManager.view().repliesBlockClickListener)
                bottomBarExpand.setOnClickListener(this)
                bottomBarOpenThread.setOnClickListener(uiManager.view().threadLinkBlockClickListener)

                index.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
                bottomBarReplies.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
                bottomBarExpand.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
                bottomBarOpenThread.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)

                val textScale = textScale
                if (textScale != 1f) {
                    applyScaleSize(
                        textScale,
                        number,
                        name,
                        index,
                        date,
                        this.commentTextView,
                        attachmentInfo,
                        bottomBarReplies,
                        bottomBarExpand,
                        bottomBarOpenThread,
                    )
                    applyScaleSize(textScale, *stateImages)
                    head.horizontalSpacing = (head.horizontalSpacing * textScale).toInt()
                }

                this.dimensions = dimensions.get(this)
                thumbnail.setDrawTouching(true)
                val thumbnailLayoutParams = thumbnail.getLayoutParams()
                val thumbnailsScale = thumbnailsScale
                if (thumbnailsScale != 1f) {
                    thumbnailLayoutParams.width =
                        (this.dimensions.thumbnailWidth * thumbnailsScale).toInt()
                    thumbnailLayoutParams.height = thumbnailLayoutParams.width
                } else {
                    thumbnailLayoutParams.width = this.dimensions.thumbnailWidth
                }
            }

            override fun createLazy(): Dimensions {
                val density = obtainDensity(itemView)
                val configuration = itemView.getResources().getConfiguration()
                val widthMeasureSpec =
                    View.MeasureSpec.makeMeasureSpec(
                        (320 * density + 0.5f).toInt(),
                        View.MeasureSpec.AT_MOST,
                    )
                val heightMeasureSpec =
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                itemView.measure(widthMeasureSpec, heightMeasureSpec)
                val commentAdditionalHeight = bottomBar.getMeasuredHeight()
                val thumbnailWidth = head.getMeasuredHeight()
                // Approximately equals to thumbnail width + right padding
                val additionalAttachmentInfoWidthDp = 64
                val minAttachmentInfoWidthDp = additionalAttachmentInfoWidthDp + 68
                val maxAttachmentInfoWidthDp = additionalAttachmentInfoWidthDp + 84
                var attachmentInfoWidthDp =
                    configuration.smallestScreenWidthDp * minAttachmentInfoWidthDp / 320
                attachmentInfoWidthDp =
                    max(
                        min(attachmentInfoWidthDp, maxAttachmentInfoWidthDp),
                        minAttachmentInfoWidthDp,
                    )
                attachmentInfoWidthDp -= additionalAttachmentInfoWidthDp
                val multipleAttachmentInfoWidth = (attachmentInfoWidthDp * density + 0.5f).toInt()
                return Dimensions(thumbnailWidth, multipleAttachmentInfoWidth, commentAdditionalHeight)
            }

            fun installBackground() {
                if (itemView.isAttachedToWindow()) {
                    installBackgroundUnchecked()
                }
            }

            fun installBackgroundUnchecked() {
                newPostAnimation?.cancel()
                newPostAnimation = null
                val postItem = postItem
                val configurationSet = configurationSet
                val highlightUserPost =
                    isHighlightUserPosts &&
                        configurationSet.postStateProvider.isUserPost(postItem.getPostNumber())
                if (selection == UiManager.Selection.DISABLED &&
                    !configurationSet.postStateProvider.isRead(postItem.getPostNumber())
                ) {
                    when (highlightUnreadMode) {
                        HighlightUnreadMode.AUTOMATICALLY -> {
                            val endColor: Int
                            if (highlightUserPost) {
                                endColor = highlightUserPostBackgroundColor
                            } else {
                                endColor = ColorUtils.setAlphaComponent(highlightBackgroundColor, 0)
                            }
                            newPostAnimation =
                                NewPostAnimation(
                                    layout,
                                    configurationSet.postStateProvider,
                                    postItem.getPostNumber(),
                                    highlightBackgroundColor,
                                    endColor,
                                )
                        }

                        HighlightUnreadMode.MANUALLY -> {
                            layout.setSecondaryBackgroundColor(highlightBackgroundColor)
                        }

                        HighlightUnreadMode.NEVER -> {
                            layout.setSecondaryBackground(null)
                        }

                        else -> {
                            error("Unsupported highlight unread mode")
                        }
                    }
                } else if (selection == UiManager.Selection.SELECTED) {
                    layout.setSecondaryBackgroundColor(highlightBackgroundColor)
                } else {
                    if (highlightUserPost) {
                        layout.setSecondaryBackgroundColor(highlightUserPostBackgroundColor)
                    } else {
                        layout.setSecondaryBackground(null)
                    }
                }
            }

            fun resetAnimations() {
                if (expandAnimator != null) {
                    expandAnimator?.cancel()
                    expandAnimator = null
                    commentTextView.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                newPostAnimation?.cancel()
                newPostAnimation = null
            }

            fun invalidateBottomBar() {
                val repliesVisible = bottomBarReplies.getVisibility() == View.VISIBLE
                val expandVisible = bottomBarExpand.getVisibility() == View.VISIBLE
                val openThreadVisible = bottomBarOpenThread.getVisibility() == View.VISIBLE
                val needBar = repliesVisible || expandVisible || openThreadVisible
                bottomBarReplies.getLayoutParams().width =
                    if (repliesVisible &&
                        !expandVisible &&
                        !openThreadVisible
                    ) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                bottomBarExpand.getLayoutParams().width =
                    if (expandVisible && !openThreadVisible) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                bottomBar.setVisibility(if (needBar) View.VISIBLE else View.GONE)
                val hasText = commentTextView.getVisibility() == View.VISIBLE
                val density = obtainDensity(textBarPadding)
                textBarPadding.getLayoutParams().height =
                    (
                        (
                            if (needBar) {
                                0f
                            } else if (hasText) {
                                10f
                            } else {
                                6f
                            }
                        ) * density
                    ).toInt()
            }

            override fun onViewAttachedToWindow(v: View) {
                installBackgroundUnchecked()
            }

            override fun onViewDetachedFromWindow(v: View) {
                resetAnimations()
                // Detaching is how a row leaves the screen, so tell the decorator to stop whatever
                // its view is doing. A row that comes back is rebound, which binds it again.
                uiManager.decorator().unbindPostView(decoratorState)
            }

            override fun onApplyLimit(limited: Boolean) {
                if (limited != (bottomBarExpand.getVisibility() == View.VISIBLE)) {
                    bottomBarExpand.setVisibility(if (limited) View.VISIBLE else View.GONE)
                    invalidateBottomBar()
                }
            }

            // Never null: falls back to the unit's own listener, so the assertions the
            // J2K conversion put on every dispatch below were unreachable.
            val linkListener: LinkListener
                get() = configurationSet.linkListener ?: defaultLinkListener

            // Both the thread page and the post popup install their own LinkListener, and each is
            // reached through the property above, so this is the one place every link click inside a
            // post passes through -- the right place to offer it to the chan's decorator first.
            private fun handleDecoratorLinkClick(
                uri: Uri,
                longClick: Boolean,
            ): Boolean {
                val configurationSet = configurationSet
                val chan = get(configurationSet.chanName)
                if (chan.postDecorator == null) {
                    return false
                }
                val postItem = postItem
                return uiManager.decorator().handleLinkClick(
                    chan,
                    postItem,
                    postItem.getBoardName(),
                    postItem.getThreadNumber(),
                    configurationSet,
                    uri,
                    longClick,
                )
            }

            override fun onLinkClick(
                view: CommentTextView,
                uri: Uri,
                extra: LinkListener.Extra,
                confirmed: Boolean,
            ) {
                if (handleDecoratorLinkClick(uri, false)) {
                    return
                }
                this.linkListener.onLinkClick(view, uri, extra, confirmed)
            }

            override fun onLinkLongClick(
                view: CommentTextView,
                uri: Uri,
                extra: LinkListener.Extra,
            ) {
                if (handleDecoratorLinkClick(uri, true)) {
                    return
                }
                this.linkListener.onLinkLongClick(view, uri, extra)
            }

            override val chanName: String?
                get() = configurationSet.chanName

            override val boardName: String?
                get() = postItem.getBoardName()

            override val threadNumber: String?
                get() = postItem.getThreadNumber()

            override fun onClick(v: View?) {
                val postItem = postItem
                val postNumber = postItem.getPostNumber()
                val configurationSet = configurationSet
                if (v === bottomBarExpand && !configurationSet.postStateProvider.isExpanded(postNumber)) {
                    configurationSet.postStateProvider.setExpanded(postNumber)
                    commentTextView.setLinesLimit(0, 0)
                    bottomBarExpand.setVisibility(View.GONE)
                    val bottomBarHeight = bottomBar.getHeight()
                    invalidateBottomBar()
                    expandAnimator?.cancel()
                    var fromHeight = commentTextView.getHeight()
                    measureDynamicHeight(this.commentTextView)
                    val toHeight = commentTextView.getMeasuredHeight()
                    if (bottomBarHeight > 0 && bottomBar.getVisibility() == View.GONE) {
                        // When button bar becomes hidden, height of the view becomes smaller, so it can cause
                        // a short list jump; Solution - start the animation from fromHeight + bottomBarHeight
                        fromHeight += bottomBarHeight
                    }
                    if (toHeight > fromHeight) {
                        val density =
                            obtainDensity(
                                this.commentTextView,
                            )
                        var value = (toHeight - fromHeight) / density / 400
                        if (value > 1f) {
                            value = 1f
                        } else if (value < 0.2f) {
                            value = 0.2f
                        }
                        val animator =
                            ofHeight(
                                this.commentTextView,
                                fromHeight,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                false,
                            )
                        this.expandAnimator = animator
                        animator.setDuration((200 * value).toInt().toLong())
                        animator.start()
                    }
                }
            }

            override fun onConfigure(
                postItem: PostItem,
                configurationSet: ConfigurationSet,
            ) {
                thumbnail.applyRoundedCorners(
                    getPostBackgroundColor(
                        itemView.getContext(),
                        configurationSet,
                    ),
                )
            }
        }

        private class HiddenViewHolder(
            parent: ViewGroup,
            card: Boolean,
            thread: Boolean,
        ) : BasePostViewHolder(
                createBaseView(parent, card),
            ) {
            val index: TextView
            val number: TextView
            val comment: TextView

            init {
                index = itemView.findViewById<TextView>(R.id.index)
                number = itemView.findViewById<TextView>(R.id.number)
                comment = itemView.findViewById<TextView>(R.id.comment)
                itemView.findViewById<View>(R.id.head).setAlpha(ALPHA_HIDDEN_POST)

                val textScale = textScale
                applyScaleSize(textScale, index, number, comment)
                applyScaleMarginLR(textScale, index, number, comment)
                index.setTypeface(ResourceUtils.TYPEFACE_MEDIUM)
                if (thread) {
                    index.setVisibility(View.GONE)
                    number.setVisibility(View.GONE)
                }
                if (card) {
                    val cardView = itemView as CardView
                    val cardContent = cardView.getChildAt(0) as ViewGroup
                    ListViewUtils.bind<Unit?, BasePostViewHolder>(this, cardContent, true, null, this)
                } else {
                    setSelectableItemBackground(itemView)
                    ListViewUtils.bind<Unit?, BasePostViewHolder>(this, itemView, true, null, this)
                }
            }

            companion object {
                private fun createBaseView(
                    parent: ViewGroup,
                    card: Boolean,
                ): View {
                    if (card) {
                        val cardView: CardView = createCardLayout(parent)
                        val cardContent = cardView.getChildAt(0) as ViewGroup
                        LayoutInflater
                            .from(cardView.getContext())
                            .inflate(R.layout.list_item_hidden, cardContent)
                        return cardView
                    } else {
                        return LayoutInflater
                            .from(parent.getContext())
                            .inflate(R.layout.list_item_hidden, parent, false)
                    }
                }
            }
        }

        companion object {
            private const val ALPHA_HIDDEN_POST = 0.2f
            private const val ALPHA_DELETED_POST = 0.5f
            private const val CARD_CONTENT_PADDING_DP = 8f

            private fun extractUri(text: String): Uri? {
                var currentText = text
                val fixedText = fixParsedUriString(currentText)
                if (currentText == fixedText) {
                    if (!currentText.matches("[a-z]+:.*".toRegex())) {
                        currentText = "http://" + currentText.replace("^/+".toRegex(), "")
                    }
                    var uri = Uri.parse(currentText)
                    if (uri != null) {
                        if (isEmpty(uri.getAuthority())) {
                            uri = uri.buildUpon().scheme("http").build()
                        }
                        val host = uri.getHost()
                        if (host != null &&
                            host.matches(".+\\..+".toRegex()) &&
                            getFallback().locator.isWebScheme(
                                uri,
                            )
                        ) {
                            return uri
                        }
                    }
                }
                return null
            }

            private fun getPostBackgroundColor(
                context: Context,
                configurationSet: ConfigurationSet,
            ): Int {
                val colorScheme = getColorScheme(context)
                return if (configurationSet.isDialog) colorScheme.dialogBackgroundColor else colorScheme.windowBackgroundColor
            }

            private fun createCardLayout(parent: ViewGroup): CardView {
                val theme = getTheme(parent.getContext())
                val cardView = CardView(parent.getContext())
                cardView.setBackgroundColor(theme.card)
                val content = FrameLayout(cardView.getContext())
                setSelectableItemBackground(content)
                // Inner padding so the cell content clears the (now rounded) card edges. Applied to the
                // ripple-backed content frame, not the card, so the ripple still fills the whole card.
                val contentPadding = (CARD_CONTENT_PADDING_DP * obtainDensity(cardView.getContext())).toInt()
                content.setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                cardView.addView(
                    content,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                cardView.setLayoutParams(
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                return cardView
            }

            private fun createStateImages(
                parent: ViewGroup,
                anchorIndex: Int,
                states: List<PostState>,
                topDp: Float,
                startDp: Float,
                endDp: Float,
            ): Array<ImageView> {
                val density = obtainDensity(parent)
                val size = (12f * density + 0.5f).toInt()
                val top = (topDp * density + 0.5f).toInt()
                val start = (startDp * density + 0.5f).toInt()
                val end = (endDp * density + 0.5f).toInt()
                val rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                val left = if (rtl) end else start
                val right = if (rtl) start else end
                val attrs = IntArray(states.size)
                for (i in attrs.indices) {
                    attrs[i] = states[i].iconAttrResId
                }
                val typedArray = parent.getContext().obtainStyledAttributes(attrs)
                val images =
                    Array(states.size) { i ->
                        val imageView = ImageView(parent.getContext())
                        imageView.setImageDrawable(typedArray.getDrawable(i))
                        parent.addView(imageView, anchorIndex + i, ViewGroup.LayoutParams(size, size))
                        val layoutParams = imageView.getLayoutParams()
                        if (layoutParams is MarginLayoutParams) {
                            layoutParams.topMargin = top
                            layoutParams.leftMargin = left
                            layoutParams.rightMargin = right
                        }
                        imageView
                    }
                typedArray.recycle()
                if (images.isNotEmpty()) {
                    val tint = ColorStateList.valueOf(getTheme(images[0].getContext()).meta)
                    for (image in images) {
                        image.setImageTintList(tint)
                    }
                }
                return images
            }

            private fun fillVoting(
                parent: ViewGroup,
                voteState: VoteState,
                topDp: Float,
                startDp: Float,
                endDp: Float,
            ) {
                val density = obtainDensity(parent)
                val size = (12f * density + 0.5f).toInt()
                val top = (topDp * density + 0.5f).toInt()
                val start = (startDp * density + 0.5f).toInt()
                val end = (endDp * density + 0.5f).toInt()
                val rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                val left = if (rtl) end else start
                val right = if (rtl) start else end

                val typedArray =
                    parent
                        .getContext()
                        .obtainStyledAttributes(intArrayOf(R.attr.iconVoteLike, R.attr.iconVoteDislike))

                val likeImageView = ImageView(parent.getContext())
                voteState.votingImages[0] = likeImageView
                likeImageView.setImageDrawable(typedArray.getDrawable(0))
                parent.addView(likeImageView, 0, ViewGroup.LayoutParams(size, size))
                val layoutParamsLike = likeImageView.getLayoutParams()
                if (layoutParamsLike is MarginLayoutParams) {
                    val marginLayoutParams = layoutParamsLike
                    marginLayoutParams.topMargin = top
                    marginLayoutParams.leftMargin = left
                    marginLayoutParams.rightMargin = right
                }

                val likeTextView = TextView(parent.getContext())
                voteState.votingText[0] = likeTextView
                TextViewCompat.setTextAppearance(likeTextView, R.style.Widget_VoteTextLike)
                parent.addView(
                    likeTextView,
                    1,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                val dislikeImageView = ImageView(parent.getContext())
                voteState.votingImages[1] = dislikeImageView
                dislikeImageView.setImageDrawable(typedArray.getDrawable(1))
                parent.addView(dislikeImageView, 2, ViewGroup.LayoutParams(size, size))
                val layoutParamsDislike = dislikeImageView.getLayoutParams()
                if (layoutParamsDislike is MarginLayoutParams) {
                    val marginLayoutParams = layoutParamsDislike
                    marginLayoutParams.topMargin = top
                    marginLayoutParams.leftMargin = left
                    marginLayoutParams.rightMargin = right
                }

                val dislikeTextView = TextView(parent.getContext())
                voteState.votingText[1] = dislikeTextView
                TextViewCompat.setTextAppearance(dislikeTextView, R.style.Widget_VoteTextDislike)
                parent.addView(
                    dislikeTextView,
                    3,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                typedArray.recycle()

                val likeTintList = ColorStateList.valueOf(getTheme(likeImageView.getContext()).meta)
                likeImageView.setImageTintList(likeTintList)
                val dislikeTintList =
                    ColorStateList.valueOf(getTheme(dislikeImageView.getContext()).meta)
                dislikeImageView.setImageTintList(dislikeTintList)
            }
        }
    }
