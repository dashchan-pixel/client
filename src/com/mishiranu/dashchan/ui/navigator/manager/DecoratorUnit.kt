package com.mishiranu.dashchan.ui.navigator.manager

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import chan.content.Chan
import chan.content.ChanPostDecorator
import chan.content.ExtensionException
import chan.content.InvalidResponseException
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast

/**
 * Supports [ChanPostDecorator]: resolves the host theme it is handed, implements the callbacks it
 * may invoke, and runs its background actions.
 *
 * The decorator itself is stateless from the client's point of view. Everything per-post lives in
 * the holder that owns the decorated view, so nothing here is keyed by post.
 */
class DecoratorUnit internal constructor(
    private val uiManager: UiManager,
) {
    private val runningTasks = HashSet<ActionTask>()
    private var theme: ChanPostDecorator.PostTheme? = null
    private var themeContext: Context? = null

    /**
     * Resolves the client's theme colors for `context`, recomputing them if the context changed --
     * which is how a theme or night-mode switch reaches a decorator, since that recreates the views.
     */
    fun getTheme(context: Context): ChanPostDecorator.PostTheme {
        val theme = this.theme
        if (theme != null && themeContext === context) {
            return theme
        }
        val created =
            ChanPostDecorator.PostTheme(
                ResourceUtils.getColor(context, R.attr.colorAccentSupport),
                ResourceUtils.getColor(context, R.attr.colorTextPost),
                ResourceUtils.getColor(context, R.attr.colorTextMeta),
                ResourceUtils.getColor(context, R.attr.colorCardBackground),
                ResourceUtils.getColor(context, R.attr.colorWindowBackground),
            )
        this.theme = created
        themeContext = context
        return created
    }

    /**
     * Creates the callback surface handed to a decorator for one post. Instances are cheap and are
     * not retained: a decorator that stores one and uses it later acts on a stale post, which is why
     * the API documents them as call-scoped.
     */
    fun createPostContext(
        chan: Chan,
        postItem: PostItem,
        boardName: String?,
        threadNumber: String?,
    ): ChanPostDecorator.PostContext = PostContextImpl(chan, postItem, boardName, threadNumber)

    /**
     * Drops the payload replacements installed by [ChanPostDecorator.ActionResult.setExtra].
     *
     * A replacement exists only to show what an action just changed, until the thread is read again.
     * Once a read completes the post cache holds the board's own answer, so keeping the replacement
     * would let it outrank the truth. That is not hypothetical: a reaction added here and then
     * removed elsewhere leaves the post byte-identical to its cached copy, so the read reports it as
     * unchanged and never rebuilds its [PostItem] -- the replacement would go on showing a reaction
     * that no longer exists until the thread was reopened.
     *
     * @return Whether anything was dropped, i.e. whether those posts now need rebinding.
     */
    fun discardExtraOverrides(postItems: Iterable<PostItem>): Boolean {
        var discarded = false
        for (postItem in postItems) {
            if (postItem.getDecoratorExtraOverride() != null) {
                postItem.setDecoratorExtraOverride(null)
                discarded = true
            }
        }
        return discarded
    }

    fun cancelAll() {
        val tasks = ArrayList(runningTasks)
        runningTasks.clear()
        for (task in tasks) {
            task.cancel()
        }
    }

    private inner class PostContextImpl(
        private val chan: Chan,
        private val postItem: PostItem,
        private val boardName: String?,
        private val threadNumber: String?,
    ) : ChanPostDecorator.PostContext {
        override fun invalidatePost() {
            uiManager.sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS)
        }

        override fun performAction(
            action: String,
            actionExtra: String?,
        ) {
            val task = ActionTask(chan, postItem, boardName, threadNumber, action, actionExtra)
            runningTasks.add(task)
            task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
        }

        override fun navigate(uri: Uri) {
            NavigationUtils.handleUri(
                uiManager.context,
                chan.name,
                uri,
                NavigationUtils.BrowserType.AUTO,
            )
        }

        override fun loadImage(
            uri: Uri,
            view: ImageView,
        ) {
            ImageLoader.getInstance().loadImage(chan, uri, false, view)
        }

        override fun showMessage(message: String?) {
            if (!message.isNullOrEmpty()) {
                ClickableToast.show(message)
            }
        }
    }

    private inner class ActionTask(
        private val chan: Chan,
        private val postItem: PostItem,
        private val boardName: String?,
        private val threadNumber: String?,
        private val action: String,
        private val actionExtra: String?,
    ) : HttpHolderTask<Unit, ActionOutcome>(chan) {
        override fun run(holder: HttpHolder): ActionOutcome {
            val decorator = chan.postDecorator ?: return ActionOutcome(null, null)
            try {
                val result =
                    decorator.safe().onPerformAction(
                        ChanPostDecorator.PerformActionData(
                            holder,
                            action,
                            actionExtra,
                            boardName,
                            threadNumber,
                            postItem.getPostNumber().toString(),
                            postItem.getDecoratorExtra(),
                        ),
                    )
                return ActionOutcome(result, null)
            } catch (e: HttpException) {
                return ActionOutcome(null, e.getErrorItemAndHandle())
            } catch (e: ExtensionException) {
                return ActionOutcome(null, e.getErrorItemAndHandle())
            } catch (e: InvalidResponseException) {
                return ActionOutcome(null, e.getErrorItemAndHandle())
            } finally {
                chan.configuration.commit()
            }
        }

        override fun onComplete(result: ActionOutcome) {
            runningTasks.remove(this)
            val errorItem = result.errorItem
            if (errorItem != null) {
                ClickableToast.show(errorItem)
                return
            }
            val actionResult = result.actionResult ?: return
            if (actionResult.extraSet) {
                postItem.setDecoratorExtraOverride(actionResult.extra)
            }
            val message = actionResult.message
            if (!message.isNullOrEmpty()) {
                ClickableToast.show(message)
            }
            uiManager.sendPostItemMessage(postItem, UiManager.Message.POST_INVALIDATE_ALL_VIEWS)
        }

        override fun onCancel(result: ActionOutcome?) {
            runningTasks.remove(this)
        }
    }

    private class ActionOutcome(
        val actionResult: ChanPostDecorator.ActionResult?,
        val errorItem: ErrorItem?,
    )

    /**
     * Lets a decorator claim a link click inside a post comment.
     *
     * @return `true` if the decorator handled it and the client should not.
     */
    fun handleLinkClick(
        chan: Chan,
        postItem: PostItem,
        boardName: String?,
        threadNumber: String?,
        uri: Uri,
        longClick: Boolean,
    ): Boolean {
        val decorator = chan.postDecorator ?: return false
        return decorator.safe().onPostLinkClick(
            ChanPostDecorator.PostLinkClickData(
                uri,
                boardName,
                threadNumber,
                postItem.getPostNumber().toString(),
                postItem.getDecoratorExtra(),
                longClick,
                createPostContext(chan, postItem, boardName, threadNumber),
            ),
        )
    }

    /** Collects a decorator's context menu contributions for `postItem`. */
    fun createPostMenu(
        context: Context,
        chan: Chan,
        postItem: PostItem,
        boardName: String?,
        threadNumber: String?,
        menu: ChanPostDecorator.PostMenu,
    ) {
        val decorator = chan.postDecorator ?: return
        decorator.safe().onCreatePostMenu(
            ChanPostDecorator.CreatePostMenuData(
                context,
                menu,
                boardName,
                threadNumber,
                postItem.getPostNumber().toString(),
                postItem.getDecoratorExtra(),
                getTheme(context),
                createPostContext(chan, postItem, boardName, threadNumber),
            ),
        )
    }

    /**
     * Binds `postItem` into a decorated view, creating it on first use.
     *
     * @return The view to show, or `null` to hide the slot.
     */
    fun bindPostView(
        chan: Chan,
        state: DecoratorState,
        postItem: PostItem,
        boardName: String?,
        threadNumber: String?,
    ): View? {
        val decorator = chan.postDecorator
        // The post view pool is shared across chans and pages, so a holder built for one decorator
        // is handed to another's posts. Discard the previous decorator's view rather than feed it
        // data it has never seen.
        if (state.owner !== decorator) {
            unbindPostView(state)
            state.reset()
            state.owner = decorator
        }
        if (decorator == null) {
            return null
        }
        val context = state.slot.context
        val theme = getTheme(context)
        if (!state.created) {
            state.created = true
            val view =
                decorator.safe().onCreatePostView(
                    ChanPostDecorator.CreatePostViewData(context, state.slot, theme),
                )
            if (view != null) {
                state.view = view
                state.slot.addView(view)
            }
        }
        val view = state.view ?: return null
        unbindPostView(state)
        val shown =
            decorator.safe().onBindPostView(
                ChanPostDecorator.BindPostViewData(
                    view,
                    boardName,
                    threadNumber,
                    postItem.getPostNumber().toString(),
                    postItem.getOriginalPostNumber().toString(),
                    postItem.getDecoratorExtra(),
                    theme,
                    createPostContext(chan, postItem, boardName, threadNumber),
                ),
            )
        if (shown) {
            state.boundBoardName = boardName
            state.boundPostNumber = postItem.getPostNumber().toString()
        }
        return if (shown) view else null
    }

    /** Tells a decorator its view stopped showing the post it was bound to. Idempotent. */
    fun unbindPostView(state: DecoratorState) {
        val decorator = state.owner ?: return
        val view = state.view ?: return
        val postNumber = state.boundPostNumber ?: return
        state.boundPostNumber = null
        decorator.safe().onUnbindPostView(
            ChanPostDecorator.UnbindPostViewData(view, state.boundBoardName, postNumber),
        )
        state.boundBoardName = null
    }

    /** Per-holder decorator state: which decorator owns the slot, and what it currently shows. */
    class DecoratorState(
        val slot: ViewGroup,
    ) {
        var owner: ChanPostDecorator? = null
        var created: Boolean = false
        var view: View? = null
        var boundBoardName: String? = null
        var boundPostNumber: String? = null

        fun reset() {
            owner = null
            created = false
            view = null
            boundBoardName = null
            boundPostNumber = null
            slot.removeAllViews()
        }
    }
}
