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
import com.mishiranu.dashchan.content.storage.DraftsStorage
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
    private val drafts = ArrayList<DraftsStorage.PostDraft>()
    private var adapter: Adapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        drafts.clear()
        drafts.addAll(DraftsStorage.getInstance().getPostDrafts())
        val recyclerView = PaddedRecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        val density = ResourceUtils.obtainDensity(context)
        recyclerView.setPadding(0, (12f * density).toInt(), 0, 0)
        val adapter = Adapter(drafts, this::onDraftClick, this::onDeleteClick)
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

    private fun onDraftClick(draft: DraftsStorage.PostDraft) {
        val navigator = activity as UiManager.LocalNavigator
        dismiss()
        // The posting form looks the draft up by chan, board and thread on its own
        ConcurrentUtils.HANDLER.post {
            navigator.navigatePosting(draft.chanName, draft.boardName, draft.threadNumber)
        }
    }

    private fun onDeleteClick(position: Int) {
        val draft = drafts.removeAt(position)
        // The drawer entry follows the storage, so it hears about this on its own
        DraftsStorage.getInstance().removePostDraft(draft.chanName, draft.boardName, draft.threadNumber)
        if (drafts.isEmpty()) {
            dismiss()
        } else {
            adapter?.notifyItemRemoved(position)
        }
    }

    private class ItemViewHolder(
        val holder: ViewFactory.TwoLinesViewHolder,
    ) : RecyclerView.ViewHolder(holder.view)

    private class Adapter(
        private val drafts: List<DraftsStorage.PostDraft>,
        private val clickCallback: (DraftsStorage.PostDraft) -> Unit,
        private val deleteCallback: (Int) -> Unit,
    ) : RecyclerView.Adapter<ItemViewHolder>(),
        ListViewUtils.ClickCallback<DraftsStorage.PostDraft, ItemViewHolder> {
        // Drafts of a single chan need no chan title, the board name already tells them apart
        private val multipleChans = drafts.mapTo(HashSet()) { it.chanName }.size >= 2

        override fun getItemCount(): Int = drafts.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ItemViewHolder {
            val viewHolder = ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_WIDGET)
            // Enough of the comment to recognize the draft by, without rows of unequal height
            viewHolder.text2.maxLines = 3
            viewHolder.text2.ellipsize = TextUtils.TruncateAt.END
            alignWithDialog(viewHolder.view)
            val holder = ItemViewHolder(viewHolder)
            val widgetFrame = checkNotNull(viewHolder.widgetFrame)
            widgetFrame.addView(createDeleteButton(parent.context, viewHolder, holder), 0)
            widgetFrame.visibility = View.VISIBLE
            return ListViewUtils.bind(holder, false, drafts::get, this)
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
            item: DraftsStorage.PostDraft?,
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
            val context = holder.itemView.context
            val draft = drafts[position]
            val viewHolder = holder.holder
            viewHolder.text1.text = formatTarget(context, draft, multipleChans)
            val description = describe(context, draft)
            viewHolder.text2.text = description
            viewHolder.text2.visibility = if (description == null) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")

        /** The thread the draft is meant for, named the way the drawer and the history name it. */
        fun formatTarget(
            context: Context,
            draft: DraftsStorage.PostDraft,
            multipleChans: Boolean,
        ): String {
            val chanName = draft.chanName.orEmpty()
            val target =
                if (draft.threadNumber.isNullOrEmpty()) {
                    StringUtils.formatBoardTitle(
                        chanName,
                        draft.boardName,
                        context.getString(R.string.new_thread),
                    )
                } else {
                    StringUtils.formatThreadTitle(chanName, draft.boardName, draft.threadNumber)
                }
            val chanTitle =
                if (multipleChans) Chan.get(draft.chanName).configuration.getTitle() else null
            return if (chanTitle.isNullOrEmpty()) target else "$chanTitle — $target"
        }

        /**
         * What the draft holds. Options alone can keep a draft alive, so this can come out empty;
         * the second line of the item is hidden in that case.
         */
        fun describe(
            context: Context,
            draft: DraftsStorage.PostDraft,
        ): String? {
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
            return if (pieces.isEmpty()) null else pieces.joinToString(" — ")
        }
    }
}
