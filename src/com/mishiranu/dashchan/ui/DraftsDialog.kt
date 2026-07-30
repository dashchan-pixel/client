package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chan.content.Chan
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.database.CommonDatabase
import com.mishiranu.dashchan.content.storage.DraftsStorage
import com.mishiranu.dashchan.content.storage.FavoritesStorage
import com.mishiranu.dashchan.ui.navigator.manager.UiManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.PaddedRecyclerView
import com.mishiranu.dashchan.widget.ViewFactory

/**
 * Lists the stored post drafts. A draft belongs to one thread and is otherwise only reachable by
 * reopening the posting form of exactly that thread, so this is the only place where the drafts can
 * be reviewed, resumed or thrown away.
 */
class DraftsDialog : DialogFragment() {
    /** A draft with the two lines it is shown as, resolved once for the life of the dialog. */
    private class Item(
        val draft: DraftsStorage.PostDraft,
        val text: String,
        val thread: String,
    )

    private val items = ArrayList<Item>()
    private var adapter: Adapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        items.clear()
        items.addAll(buildItems(context))
        val recyclerView = PaddedRecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        val density = ResourceUtils.obtainDensity(context)
        recyclerView.setPadding(0, (12f * density).toInt(), 0, 0)
        val adapter = Adapter(items, this::onDraftClick, this::onDeleteClick)
        this.adapter = adapter
        recyclerView.adapter = adapter
        return AlertDialog
            .Builder(context)
            .setTitle(R.string.drafts)
            .setView(recyclerView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
    }

    private fun onDraftClick(item: Item) {
        val navigator = activity as UiManager.LocalNavigator
        val draft = item.draft
        dismiss()
        // The posting form looks the draft up by chan, board and thread on its own
        ConcurrentUtils.HANDLER.post {
            navigator.navigatePosting(draft.chanName, draft.boardName, draft.threadNumber)
        }
    }

    private fun onDeleteClick(position: Int) {
        val draft = items.removeAt(position).draft
        // The drawer entry follows the storage, so it hears about this on its own
        DraftsStorage.getInstance().removePostDraft(draft.chanName, draft.boardName, draft.threadNumber)
        if (items.isEmpty()) {
            dismiss()
        } else {
            adapter?.notifyItemRemoved(position)
        }
    }

    private class ItemViewHolder(
        val holder: ViewFactory.TwoLinesViewHolder,
    ) : RecyclerView.ViewHolder(holder.view)

    private class Adapter(
        private val items: List<Item>,
        private val clickCallback: (Item) -> Unit,
        private val deleteCallback: (Int) -> Unit,
    ) : RecyclerView.Adapter<ItemViewHolder>(),
        ListViewUtils.ClickCallback<Item, ItemViewHolder> {
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ItemViewHolder {
            val viewHolder =
                ViewFactory.makeTwoLinesListItem(
                    parent,
                    ViewFactory.FEATURE_WIDGET or ViewFactory.FEATURE_SINGLE_LINE,
                )
            // The draft's own text is what a draft is; enough of it to recognize one by, without
            // rows of wildly unequal height. The thread it is meant for reads as its caption.
            viewHolder.text1.isSingleLine = false
            viewHolder.text1.maxLines = 3
            viewHolder.text1.ellipsize = TextUtils.TruncateAt.END
            alignWithDialog(viewHolder.view)
            val holder = ItemViewHolder(viewHolder)
            val widgetFrame = checkNotNull(viewHolder.widgetFrame)
            widgetFrame.addView(createDeleteButton(parent.context, viewHolder, holder), 0)
            widgetFrame.visibility = View.VISIBLE
            return ListViewUtils.bind(holder, false, items::get, this)
        }

        /**
         * A list item is padded for a screen, 16dp, while the dialog lays its title and buttons out
         * at 24dp. The start edge takes the missing 8dp so the text lines up with the title. The end
         * edge does not: the delete button is 8dp wider than the cross drawn in the middle of it, so
         * a 16dp padding is what puts that cross 24dp from the edge, level with the text.
         */
        private fun alignWithDialog(view: View) {
            val extra = (8f * ResourceUtils.obtainDensity(view.context) + 0.5f).toInt()
            view.setPaddingRelative(
                view.paddingStart + extra,
                view.paddingTop,
                view.paddingEnd,
                view.paddingBottom,
            )
        }

        private fun createDeleteButton(
            context: Context,
            viewHolder: ViewFactory.TwoLinesViewHolder,
            holder: ItemViewHolder,
        ): View {
            val delete = ImageView(context)
            delete.scaleType = ImageView.ScaleType.CENTER
            delete.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0))
            delete.imageTintList = viewHolder.text1.textColors
            delete.setBackgroundResource(
                ResourceUtils.getResourceId(
                    context,
                    android.R.attr.borderlessButtonStyle,
                    android.R.attr.background,
                    0,
                ),
            )
            delete.contentDescription = context.getString(R.string.delete)
            val size = (40f * ResourceUtils.obtainDensity(context) + 0.5f).toInt()
            delete.layoutParams = LinearLayout.LayoutParams(size, size)
            delete.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position >= 0) {
                    deleteCallback(position)
                }
            }
            return delete
        }

        override fun onItemClick(
            holder: ItemViewHolder,
            position: Int,
            item: Item?,
            longClick: Boolean,
        ): Boolean {
            if (item != null) {
                clickCallback(item)
            }
            return true
        }

        override fun onBindViewHolder(
            holder: ItemViewHolder,
            position: Int,
        ) {
            val item = items[position]
            holder.holder.text1.text = item.text
            holder.holder.text2.text = item.thread
        }
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")

        fun buildItems(context: Context): List<Item> {
            val drafts = DraftsStorage.getInstance().getPostDrafts()
            // Drafts of a single chan need no chan title, the board name already tells them apart
            val multipleChans = drafts.mapTo(HashSet()) { it.chanName }.size >= 2
            return drafts.map {
                Item(it, describe(context, it), formatThread(context, it, multipleChans))
            }
        }

        /**
         * The thread the draft is meant for: the title the favorites or the history remember for it,
         * the way the Echo names the thread a reply came from, and its number when neither of them
         * has ever seen it.
         */
        fun formatThread(
            context: Context,
            draft: DraftsStorage.PostDraft,
            multipleChans: Boolean,
        ): String {
            val chanName = draft.chanName.orEmpty()
            val threadNumber = draft.threadNumber
            val thread =
                if (threadNumber.isNullOrEmpty()) {
                    StringUtils.formatBoardTitle(
                        chanName,
                        draft.boardName,
                        context.getString(R.string.new_thread),
                    )
                } else {
                    findThreadTitle(draft.chanName, draft.boardName, threadNumber)
                        ?: StringUtils.formatThreadTitle(chanName, draft.boardName, threadNumber)
                }
            val chanTitle =
                if (multipleChans) Chan.get(draft.chanName).configuration.getTitle() else null
            return if (chanTitle.isNullOrEmpty()) thread else "$chanTitle — $thread"
        }

        private fun findThreadTitle(
            chanName: String?,
            boardName: String?,
            threadNumber: String,
        ): String? {
            if (chanName == null) {
                return null
            }
            val favoriteTitle =
                FavoritesStorage
                    .getInstance()
                    .getFavorite(chanName, boardName, threadNumber)
                    ?.title
            return StringUtils.nullIfEmpty(favoriteTitle)
                ?: StringUtils.nullIfEmpty(
                    CommonDatabase.getInstance().history.getTitle(chanName, boardName, threadNumber),
                )
        }

        /**
         * What the draft holds, which is the whole of what one is. Options alone can keep a draft
         * alive, and a draft with nothing to quote is still named rather than shown as a blank row.
         */
        fun describe(
            context: Context,
            draft: DraftsStorage.PostDraft,
        ): String {
            val pieces = ArrayList<String>(2)
            val subject = draft.subject?.trim()
            if (!subject.isNullOrEmpty()) {
                pieces.add(subject)
            }
            val comment = draft.comment?.replace(WHITESPACE, " ")?.trim()
            if (!comment.isNullOrEmpty()) {
                pieces.add(comment)
            }
            val attachments = draft.attachmentDrafts?.size ?: 0
            if (pieces.isEmpty() && attachments > 0) {
                pieces.add(
                    context.resources.getQuantityString(
                        R.plurals.number_files__format,
                        attachments,
                        attachments,
                    ),
                )
            }
            return if (pieces.isEmpty()) context.getString(R.string.draft) else pieces.joinToString(" — ")
        }
    }
}
