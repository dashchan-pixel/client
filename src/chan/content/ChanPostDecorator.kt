package chan.content

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.ExtensionException.Companion.logException
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest

/**
 * Optional extension component that decorates displayed posts. Unlike the other four components
 * this one may be omitted: an extension declares it through the `chan.extension.class.postdecorator`
 * manifest meta-data, and a chan without it behaves exactly as before.
 *
 * A decorator can attach its own views to a post, contribute post context menu entries, intercept
 * clicks on links inside a comment, and run background work with a proper [HttpHolder].
 *
 * The data a decorator renders comes from [chan.content.model.Post.setExtra]: whatever the extension
 * stored while parsing is handed back here. The client keeps that payload with the post, including
 * in the post cache, so it does not have to be re-fetched and no side channel is needed.
 *
 * Every method except [onPerformAction] is called on the main thread.
 */
@Extendable
open class ChanPostDecorator internal constructor(
    chanProvider: Chan.Provider?,
) : Chan.Linked {
    private val chanProvider: Chan.Provider?

    @Public
    constructor() : this(null)

    override fun init() {}

    override fun get(): Chan = chanProvider!!.get()

    /**
     * Creates the view this decorator attaches to a post. Called once per recycled post holder, so
     * the result must not depend on any particular post: bind the data in [onBindPostView].
     *
     * Extension resources are available through [ChanConfiguration.getResources], but
     * [CreatePostViewData.context] is the client's, so its [android.view.LayoutInflater] cannot
     * resolve extension layout ids. Build the hierarchy programmatically.
     *
     * @return The view to attach, or `null` to decorate nothing.
     */
    @Extendable
    protected open fun onCreatePostView(data: CreatePostViewData): View? = null

    /**
     * Binds a post to the view created by [onCreatePostView]. Called every time the post is
     * displayed, including after the holder was recycled from an unrelated post, so it must fully
     * overwrite the previous state rather than assume anything about it.
     *
     * @return `true` to show the view, `false` to hide it for this post.
     */
    @Extendable
    protected open fun onBindPostView(data: BindPostViewData): Boolean = false

    /**
     * Called when a bound view stops showing its post, so pending animations and image requests can
     * be dropped. The view itself stays alive for reuse.
     */
    @Extendable
    protected open fun onUnbindPostView(data: UnbindPostViewData) {}

    /**
     * Contributes entries to a post's context menu. May be called more than once for the same menu,
     * because the client rebuilds it after a configuration change, so it must be side-effect free.
     */
    @Extendable
    protected open fun onCreatePostMenu(data: CreatePostMenuData) {}

    /**
     * Called when a link inside a post comment is clicked, before the client handles it.
     *
     * @return `true` if the decorator handled the click, `false` to let the client proceed.
     */
    @Extendable
    protected open fun onPostLinkClick(data: PostLinkClickData): Boolean = false

    /**
     * Performs work requested by [PostContext.performAction]. Called on a background thread with a
     * usable [HttpHolder], so a decorator never has to reach outside the API to make a request.
     *
     * Throwing [chan.http.HttpException] or [InvalidResponseException] reports the failure to the
     * user through the client's usual error handling.
     *
     * @return The outcome, or `null` if there is nothing to report.
     */
    @Extendable
    @Throws(HttpException::class, InvalidResponseException::class)
    protected open fun onPerformAction(data: PerformActionData): ActionResult? = null

    /**
     * Host colors resolved from the current theme, so a decorator's views can match the client
     * without guessing at its resources.
     */
    @Public
    class PostTheme(
        @field:Public @JvmField val accentColor: Int,
        @field:Public @JvmField val postTextColor: Int,
        @field:Public @JvmField val metaTextColor: Int,
        @field:Public @JvmField val cardBackgroundColor: Int,
        @field:Public @JvmField val windowBackgroundColor: Int,
    )

    /**
     * Operations a decorator can ask the client to perform for the post being decorated. Instances
     * are valid only for the duration of the call that supplied them.
     */
    @Public
    interface PostContext {
        /**
         * Rebinds the post, to display state the decorator changed on its own. Rebinding is not
         * free: drive animations from the decorator's own views instead of calling this repeatedly.
         */
        fun invalidatePost()

        /**
         * Schedules [onPerformAction] on a background thread.
         *
         * @param action Extension-defined action name.
         * @param actionExtra Extension-defined payload, or `null`.
         */
        fun performAction(
            action: String,
            actionExtra: String?,
        )

        /** Opens `uri` the way the client opens a link the user clicked. */
        fun navigate(uri: Uri)

        /**
         * Loads an image into `view` using the client's cache. The request is bound to the view, so
         * recycling it cancels the load instead of showing the wrong image.
         */
        fun loadImage(
            uri: Uri,
            view: ImageView,
        )

        /** Shows `message` to the user as the client shows its own notices. */
        fun showMessage(message: String?)
    }

    /**
     * Post context menu under construction. Entries appear below the client's own, in the order they
     * are added.
     */
    @Public
    interface PostMenu {
        fun addItem(
            title: String,
            runnable: Runnable,
        )

        fun addCheckItem(
            title: String,
            checked: Boolean,
            runnable: Runnable,
        )

        /**
         * Places `view` below every menu entry. Use it for controls a list of entries cannot
         * express; call [dismiss] from it to close the menu.
         */
        fun setFooterView(view: View)

        /** Closes the menu. */
        fun dismiss()
    }

    /** Arguments holder for [onCreatePostView]. */
    @Public
    class CreatePostViewData(
        /** Client context, themed for the post list. */
        @field:Public @JvmField val context: Context,
        /**
         * The group the returned view will be attached to. Pass it to
         * [android.view.LayoutInflater.inflate] with `attachToRoot = false` to get correct layout
         * parameters; do not add anything to it directly.
         */
        @field:Public @JvmField val parent: ViewGroup,
        @field:Public @JvmField val theme: PostTheme,
    )

    /** Arguments holder for [onBindPostView]. */
    @Public
    class BindPostViewData(
        /** The view returned by [onCreatePostView]. */
        @field:Public @JvmField val view: View,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumber: String,
        @field:Public @JvmField val originalPostNumber: String?,
        /** Payload stored by [chan.content.model.Post.setExtra], or `null`. */
        @field:Public @JvmField val extra: String?,
        @field:Public @JvmField val theme: PostTheme,
        @field:Public @JvmField val postContext: PostContext,
    )

    /** Arguments holder for [onUnbindPostView]. */
    @Public
    class UnbindPostViewData(
        @field:Public @JvmField val view: View,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val postNumber: String,
    )

    /** Arguments holder for [onCreatePostMenu]. */
    @Public
    class CreatePostMenuData(
        @field:Public @JvmField val context: Context,
        @field:Public @JvmField val menu: PostMenu,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumber: String,
        @field:Public @JvmField val extra: String?,
        @field:Public @JvmField val theme: PostTheme,
        @field:Public @JvmField val postContext: PostContext,
    )

    /** Arguments holder for [onPostLinkClick]. */
    @Public
    class PostLinkClickData(
        @field:Public @JvmField val uri: Uri,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumber: String,
        @field:Public @JvmField val extra: String?,
        /** Whether the link was long-clicked rather than clicked. */
        @field:Public @JvmField val longClick: Boolean,
        @field:Public @JvmField val postContext: PostContext,
    )

    /**
     * Arguments holder for [onPerformAction]. May be used as an [HttpRequest.Preset].
     */
    @Public
    class PerformActionData(
        @JvmField val holder: HttpHolder?,
        @field:Public @JvmField val action: String,
        @field:Public @JvmField val actionExtra: String?,
        @field:Public @JvmField val boardName: String?,
        @field:Public @JvmField val threadNumber: String?,
        @field:Public @JvmField val postNumber: String,
        @field:Public @JvmField val extra: String?,
    ) : HttpRequest.Preset {
        override fun getHolder(): HttpHolder? = holder
    }

    /** Result holder for [onPerformAction]. */
    @Public
    class ActionResult
        @Public
        constructor() {
            var extra: String? = null
            var extraSet: Boolean = false
            var message: String? = null

            /**
             * Replaces the payload the decorator sees for this post, as
             * [chan.content.model.Post.setExtra] does while parsing, and rebinds it.
             *
             * The replacement lives as long as the post stays loaded; it is not written to the post
             * cache, so a restart shows the parsed payload again. Use it to reflect what an action
             * just changed on the server, and keep durable decorator state in the extension's own
             * [ChanConfiguration] storage.
             */
            @Public
            fun setExtra(extra: String?): ActionResult {
                this.extra = extra
                extraSet = true
                return this
            }

            /** Shows a message to the user once the action completes. */
            @Public
            fun setMessage(message: String?): ActionResult {
                this.message = message
                return this
            }
        }

    class Safe internal constructor(
        private val decorator: ChanPostDecorator,
    ) {
        fun onCreatePostView(data: CreatePostViewData): View? {
            try {
                return decorator.onCreatePostView(data)
            } catch (e: LinkageError) {
                logException(e, false)
                return null
            } catch (e: RuntimeException) {
                logException(e, false)
                return null
            }
        }

        fun onBindPostView(data: BindPostViewData): Boolean {
            try {
                return decorator.onBindPostView(data)
            } catch (e: LinkageError) {
                logException(e, false)
                return false
            } catch (e: RuntimeException) {
                logException(e, false)
                return false
            }
        }

        fun onUnbindPostView(data: UnbindPostViewData) {
            try {
                decorator.onUnbindPostView(data)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
        }

        fun onCreatePostMenu(data: CreatePostMenuData) {
            try {
                decorator.onCreatePostMenu(data)
            } catch (e: LinkageError) {
                logException(e, false)
            } catch (e: RuntimeException) {
                logException(e, false)
            }
        }

        fun onPostLinkClick(data: PostLinkClickData): Boolean {
            try {
                return decorator.onPostLinkClick(data)
            } catch (e: LinkageError) {
                logException(e, false)
                return false
            } catch (e: RuntimeException) {
                logException(e, false)
                return false
            }
        }

        @Throws(ExtensionException::class, HttpException::class, InvalidResponseException::class)
        fun onPerformAction(data: PerformActionData): ActionResult? {
            try {
                return decorator.onPerformAction(data)
            } catch (e: LinkageError) {
                throw ExtensionException(e)
            } catch (e: RuntimeException) {
                throw ExtensionException(e)
            }
        }
    }

    private val safe = Safe(this)

    init {
        if (chanProvider == null) {
            val holder: ChanManager.Initializer.Holder = INITIALIZER.consume()
            this.chanProvider = holder.chanProvider
        } else {
            this.chanProvider = chanProvider
        }
    }

    fun safe(): Safe = safe

    companion object {
        val INITIALIZER: ChanManager.Initializer = ChanManager.Initializer()

        @Public
        @JvmStatic
        fun get(`object`: Any): ChanPostDecorator? = (`object` as Chan.Linked).get().postDecorator
    }
}
