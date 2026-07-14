package com.mishiranu.dashchan.ui.navigator.manager

import android.content.Context
import android.view.View
import android.view.View.OnLongClickListener
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import chan.content.ChanLocator.NavigationData
import com.mishiranu.dashchan.content.model.AttachmentItem
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.content.service.WatcherService
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay.NavigatePostMode
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit.StackInstance
import com.mishiranu.dashchan.ui.posting.Replyable
import com.mishiranu.dashchan.ui.posting.Replyable.ReplyData
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.ClickCallback
import com.mishiranu.dashchan.util.WeakObservable
import com.mishiranu.dashchan.widget.CommentTextView.LinkListener
import com.mishiranu.dashchan.widget.ThemeEngine
import java.lang.ref.WeakReference

class UiManager(
    val context: Context?,
    callback: Callback?,
    localNavigator: LocalNavigator?,
) {
    private val viewUnit: ViewUnit
    private val dialogUnit: DialogUnit
    private val interactionUnit: InteractionUnit
    private val observable = WeakObservable<Observer>()

    private val callback: Callback?
    private val localNavigator: LocalNavigator?

    init {
        viewUnit = ViewUnit(this)
        dialogUnit = DialogUnit(this)
        interactionUnit = InteractionUnit(this)
        this.callback = callback
        this.localNavigator = localNavigator
    }

    fun view(): ViewUnit = viewUnit

    fun dialog(): DialogUnit = dialogUnit

    fun interaction(): InteractionUnit = interactionUnit

    fun callback(): Callback? = callback

    fun navigator(): LocalNavigator? = localNavigator

    enum class Message {
        POST_INVALIDATE_ALL_VIEWS,
        INVALIDATE_COMMENT_VIEW,
        PERFORM_SWITCH_USER_MARK,
        PERFORM_SWITCH_HIDE,
        PERFORM_HIDE_REPLIES,
        PERFORM_HIDE_NAME,
        PERFORM_HIDE_SIMILAR,
        PERFORM_GO_TO_POST,
    }

    interface Observer {
        fun onPostItemMessage(
            postItem: PostItem,
            message: Message,
        ) {}

        fun onReloadAttachmentItem(attachmentItem: AttachmentItem) {}
    }

    fun sendPostItemMessage(
        view: View,
        message: Message?,
    ) {
        val holder = ListViewUtils.getViewHolder(view, Holder::class.java)
        sendPostItemMessage(holder!!.postItem, message)
    }

    fun sendPostItemMessage(
        postItem: PostItem?,
        message: Message?,
    ) {
        for (observer in observable) {
            observer.onPostItemMessage(postItem!!, message!!)
        }
    }

    fun reloadAttachmentItem(attachmentItem: AttachmentItem?) {
        val iterator = observable.iterator()
        var observer: Observer? = null
        while (iterator.hasNext()) {
            observer = iterator.next()
        }
        if (observer != null) {
            observer.onReloadAttachmentItem(attachmentItem!!)
        }
    }

    fun observable(): WeakObservable<Observer> = observable

    interface PostsProvider : Iterable<PostItem> {
        fun findPostItem(postNumber: PostNumber?): PostItem?
    }

    interface PostStateProvider {
        fun isHiddenResolve(postItem: PostItem): Boolean = postItem.getHideState().hidden

        fun isUserPost(postNumber: PostNumber?): Boolean = false

        fun isExpanded(postNumber: PostNumber?): Boolean = true

        fun setExpanded(postNumber: PostNumber?) {}

        fun isRead(postNumber: PostNumber?): Boolean = true

        fun setRead(postNumber: PostNumber?) {}

        companion object {
            @JvmField
            val DEFAULT: PostStateProvider = object : PostStateProvider {}
        }
    }

    interface Callback {
        fun onDialogStackOpen()

        fun getDownloadBinder(): DownloadService.Binder?

        val watcherClient: WatcherService.Client?
    }

    interface LocalNavigator {
        fun navigateBoardsOrThreads(
            chanName: String?,
            boardName: String?,
        )

        fun navigatePosts(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            postNumber: PostNumber?,
            threadTitle: String?,
        )

        fun navigateSearch(
            chanName: String?,
            boardName: String?,
            searchQuery: String?,
        )

        fun navigateArchive(
            chanName: String?,
            boardName: String?,
        )

        fun navigateTargetAllowReturn(
            chanName: String?,
            navigationData: NavigationData,
        )

        fun navigatePosting(
            chanName: String?,
            boardName: String?,
            threadNumber: String?,
            vararg data: ReplyData?,
        )

        fun navigateGallery(
            chanName: String?,
            gallerySet: GalleryItem.Set,
            imageIndex: Int,
            view: View?,
            navigatePostMode: NavigatePostMode?,
            galleryMode: Boolean,
        )

        fun navigateSetTheme(theme: ThemeEngine.Theme)
    }

    enum class Selection {
        DISABLED,
        NOT_SELECTED,
        SELECTED,
        THREADSHOT,
    }

    class DemandSet {
        @JvmField
        var lastInList: Boolean = false

        @JvmField
        var selection: Selection = Selection.DISABLED
        var showOpenThreadButton: Boolean = false

        @JvmField
        var highlightText: MutableCollection<String> = mutableListOf()
    }

    class ConfigurationSet(
        @JvmField val chanName: String?,
        val replyable: Replyable?,
        val postsProvider: PostsProvider?,
        @JvmField val postStateProvider: PostStateProvider?,
        val galleryProvider: GalleryItem.Provider?,
        val fragmentManager: FragmentManager?,
        @JvmField val stackInstance: StackInstance?,
        val linkListener: LinkListener?,
        val clickCallback: ClickCallback<PostItem?, RecyclerView.ViewHolder>?,
        val mayCollapse: Boolean,
        val isDialog: Boolean,
        val allowMyMarkEdit: Boolean,
        val allowHiding: Boolean,
        val allowGoToPost: Boolean,
        val repliesToPost: PostNumber?,
    ) {
        fun copy(
            clickCallback: ClickCallback<PostItem?, RecyclerView.ViewHolder>?,
            mayCollapse: Boolean,
            isDialog: Boolean,
            repliesToPost: PostNumber?,
        ): ConfigurationSet =
            ConfigurationSet(
                chanName,
                replyable,
                postsProvider,
                postStateProvider,
                galleryProvider,
                fragmentManager,
                stackInstance,
                linkListener,
                clickCallback,
                mayCollapse,
                isDialog,
                allowMyMarkEdit,
                allowHiding,
                allowGoToPost,
                repliesToPost,
            )
    }

    interface ThumbnailClickListener : View.OnClickListener {
        fun update(
            index: Int,
            mayShowDialog: Boolean,
            navigatePostMode: NavigatePostMode?,
        )
    }

    interface ThumbnailLongClickListener : OnLongClickListener {
        fun update(attachmentItem: AttachmentItem)
    }

    interface Holder {
        val postItem: PostItem?
        val configurationSet: ConfigurationSet?

        val gallerySet: GalleryItem.Set
            get() = this.configurationSet!!.galleryProvider!!.getGallerySet(this.postItem!!)
    }

    class UiManagerViewModel : ViewModel() {
        internal var uiManager: WeakReference<UiManager?>? = null
    }

    fun attach(activity: FragmentActivity) {
        val viewModel =
            ViewModelProvider(activity).get<UiManagerViewModel>(UiManagerViewModel::class.java)
        viewModel.uiManager = WeakReference<UiManager?>(this)
    }

    companion object {
        fun extract(provider: InstanceDialog.Provider): UiManager? {
            val activity = provider.activity
            val viewModel =
                ViewModelProvider(activity).get<UiManagerViewModel>(UiManagerViewModel::class.java)
            val uiManager = if (viewModel.uiManager != null) viewModel.uiManager!!.get() else null
            return if (uiManager != null && uiManager.context === activity) uiManager else null
        }
    }
}
