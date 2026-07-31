package com.mishiranu.dashchan.ui.posting.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.Spanned
import android.text.TextWatcher
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import chan.util.StringUtils.getFileExtension
import chan.util.StringUtils.removeFileExtension
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.DraftAttachmentMedia
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.graphics.TransparentTileDrawable
import com.mishiranu.dashchan.ui.posting.AttachmentHolder
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback
import com.mishiranu.dashchan.util.FilenameUtils.getFilenameMaxCharacterCount
import com.mishiranu.dashchan.util.FilenameUtils.isValidCharacter
import com.mishiranu.dashchan.util.GraphicsUtils.Reencoding
import com.mishiranu.dashchan.util.GraphicsUtils.canRemoveMetadata
import com.mishiranu.dashchan.util.GraphicsUtils.isLight
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.MaterialButton
import com.mishiranu.dashchan.widget.ThemeEngine

class AttachmentOptionsDialog :
    DialogFragment,
    OnItemClickListener {
    private enum class Type {
        UNIQUE_HASH,
        REMOVE_METADATA,
        REENCODE_IMAGE,
        REMOVE_FILE_NAME,
        SPOILER,
        RENAME,
    }

    private class OptionItem(
        val title: String?,
        val type: Type,
        val checked: Boolean,
    )

    private val optionItems: ArrayList<OptionItem> = ArrayList<OptionItem>()
    private val optionIndices = HashMap<Type?, Int?>()

    private lateinit var listView: ListView
    private lateinit var filenameEditText: EditText
    private lateinit var extensionTextView: TextView
    private lateinit var restoreButton: Button

    constructor()

    constructor(attachmentIndex: Int) {
        val args = Bundle()
        args.putInt(EXTRA_ATTACHMENT_INDEX, attachmentIndex)
        setArguments(args)
    }

    private class ItemsAdapter(
        context: Context,
        resId: Int,
        items: ArrayList<String?>,
    ) : ArrayAdapter<String?>(context, resId, android.R.id.text1, items) {
        private val enabledItems = SparseBooleanArray()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val view = super.getView(position, convertView, parent)
            view.setEnabled(isEnabled(position))
            return view
        }

        override fun isEnabled(position: Int): Boolean = enabledItems.get(position, true)

        fun setEnabled(
            index: Int,
            enabled: Boolean,
        ) {
            enabledItems.put(index, enabled)
        }
    }

    private val attachmentHolder: AttachmentHolder?
        get() =
            (getParentFragment() as PostingDialogCallback)
                .getAttachmentHolder(requireArguments().getInt(EXTRA_ATTACHMENT_INDEX))

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val holder = this.attachmentHolder
        val fileHolder =
            if (holder != null) {
                getInstance()
                    .getAttachmentDraftFileHolder(holder.hash)
            } else {
                null
            }
        if (holder == null || fileHolder == null) {
            dismiss()
            return Dialog(activity)
        }
        val postingConfiguration =
            (getParentFragment() as PostingDialogCallback)
                .getPostingConfiguration()
        var index = 0
        optionItems.clear()
        optionIndices.clear()
        optionItems.add(
            OptionItem(
                getString(R.string.unique_hash),
                Type.UNIQUE_HASH,
                holder.optionUniqueHash,
            ),
        )
        optionIndices[Type.UNIQUE_HASH] = index++
        if (canRemoveMetadata(fileHolder)) {
            optionItems.add(
                OptionItem(
                    getString(R.string.remove_metadata),
                    Type.REMOVE_METADATA,
                    holder.optionRemoveMetadata,
                ),
            )
            optionIndices[Type.REMOVE_METADATA] = index++
        }
        if (fileHolder.isImage) {
            optionItems.add(
                OptionItem(
                    getString(R.string.reencode_image),
                    Type.REENCODE_IMAGE,
                    holder.reencoding != null,
                ),
            )
            optionIndices[Type.REENCODE_IMAGE] = index++
        }
        optionItems.add(
            OptionItem(
                getString(R.string.remove_file_name),
                Type.REMOVE_FILE_NAME,
                holder.optionRemoveFileName,
            ),
        )
        optionIndices[Type.REMOVE_FILE_NAME] = index++
        if (postingConfiguration.attachmentSpoiler) {
            optionItems.add(
                OptionItem(
                    getString(R.string.spoiler),
                    Type.SPOILER,
                    holder.optionSpoiler,
                ),
            )
            // noinspection UnusedAssignment
            optionIndices[Type.SPOILER] = index++
        }
        optionItems.add(
            OptionItem(
                getString(R.string.rename),
                Type.RENAME,
                holder.optionCustomName,
            ),
        )
        optionIndices[Type.RENAME] = index++
        val items = ArrayList<String?>()
        for (optionItem in optionItems) {
            items.add(optionItem.title)
        }
        val linearLayout = LinearLayout(activity)
        linearLayout.setOrientation(LinearLayout.VERTICAL)
        linearLayout.addView(
            createPreview(activity, holder),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        listView = ListView(activity)
        linearLayout.addView(
            listView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE)
        val resId =
            ResourceUtils.obtainAlertDialogLayoutResId(
                activity,
                ResourceUtils.DialogLayout.MULTI_CHOICE,
            )
        listView.setDividerHeight(0)

        val adapter = AttachmentOptionsDialog.ItemsAdapter(activity, resId, items)

        val nameExtensionLayout =
            LayoutInflater
                .from(activity)
                .inflate(R.layout.dialog_filename, listView, false) as ViewGroup
        nameExtensionLayout.setOnClickListener(null)
        listView.addFooterView(nameExtensionLayout)
        listView.setAdapter(adapter)

        for (i in optionItems.indices) {
            listView.setItemChecked(i, optionItems[i].checked)
        }
        listView.setOnItemClickListener(this)

        filenameEditText = nameExtensionLayout.findViewById<EditText>(R.id.filename)
        filenameEditText.setText(removeFileExtension(holder.newname))
        val filter =
            InputFilter { source: CharSequence, start: Int, end: Int, dest: Spanned?, dstart: Int, dend: Int ->
                for (i in start..<end) {
                    if (!isValidCharacter(source[i])) {
                        return@InputFilter ""
                    }
                }
                null
            }
        filenameEditText.setFilters(
            arrayOf<InputFilter>(
                filter,
                LengthFilter(getFilenameMaxCharacterCount()),
            ),
        )
        filenameEditText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                }

                override fun afterTextChanged(s: Editable) {
                    holder.newname = s.toString() + "." + getFileExtension(holder.name)
                }
            },
        )
        extensionTextView = nameExtensionLayout.findViewById<TextView>(R.id.extension)
        val ext: CharSequence = "." + getFileExtension(holder.name)
        extensionTextView.setText(ext)
        restoreButton = MaterialButton(activity)
        restoreButton.setText(R.string.restore_filename)
        restoreButton.setOnClickListener(
            View.OnClickListener { v: View? ->
                holder.newname = holder.name
                filenameEditText.setText(removeFileExtension(holder.newname))
            },
        )
        nameExtensionLayout.addView(restoreButton)

        updateItemsEnabled(adapter, holder)
        val dialog = AlertDialog.Builder(activity).setView(linearLayout).create()
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    /**
     * The large preview at the top of the dialog, and the one place a draft attachment can be looked at
     * in full: tapping it opens the image or the video in the gallery, and plays music in the audio
     * player. The small preview in the form opens this dialog instead, so this is the only way in.
     *
     * The box is here whether or not there is anything to show in it, since the layout gives it the
     * space left over — which is what makes a music file with no cover art playable all the same.
     */
    private fun createPreview(
        context: Context,
        holder: AttachmentHolder,
    ): View {
        val preview = FrameLayout(context)
        preview.setBackground(TransparentTileDrawable(context, true))
        val imageView = ImageView(context)
        val drawable = holder.imageView.getDrawable()
        imageView.setImageDrawable(drawable)
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
        preview.addView(
            imageView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        if (!DraftAttachmentMedia.canOpen(holder.name)) {
            return preview
        }
        val video = DraftAttachmentMedia.isVideo(holder.name)
        val audio = DraftAttachmentMedia.isAudio(holder.name)
        if (video || audio) {
            val icon =
                ResourceUtils
                    .getDrawable(
                        context,
                        if (video) R.attr.iconAttachmentVideo else R.attr.iconAttachmentAudio,
                        0,
                    )?.mutate()
            val badge = ImageView(context)
            badge.setScaleType(ImageView.ScaleType.CENTER)
            if (drawable != null) {
                // Over a frame or a cover the icon just needs a dim behind it for the contrast.
                badge.setBackgroundColor(AttachmentHolder.PREVIEW_DIM_COLOR)
            } else {
                // A cover-less audio (or frame-less video) would otherwise leave the white glyph on
                // the bare transparency tiles: fill the box with the accent so it reads as a playable
                // preview, dark-tinting the glyph on a light accent so it stays legible. With no image
                // to give it height the box would otherwise collapse to the glyph, so give it a proper
                // one — a cover would have supplied its own.
                val accent = ThemeEngine.getTheme(context).accent
                preview.setBackgroundColor(accent)
                preview.minimumHeight = (PREVIEW_MIN_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
                if (isLight(accent)) {
                    icon?.setTint(Color.BLACK)
                }
            }
            badge.setImageDrawable(icon)
            preview.addView(
                badge,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        // As the foreground, so the ripple lies over the preview instead of replacing the tiles behind it
        preview.setForeground(
            ResourceUtils.getDrawable(context, android.R.attr.selectableItemBackground, 0),
        )
        preview.setContentDescription(getString(if (video || audio) R.string.play else R.string.view__verb))
        preview.setOnClickListener {
            DraftAttachmentMedia.open(context, holder.hash, holder.name)
        }
        return preview
    }

    override fun onResume() {
        super.onResume()
        requireDialog().getWindow()!!.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    private fun updateItemsEnabled(
        adapter: ItemsAdapter,
        holder: AttachmentHolder,
    ) {
        val reencodeIndex = optionIndices[Type.REENCODE_IMAGE]
        val allowRemoveMetadata = reencodeIndex == null || holder.reencoding == null
        val removeMetadataIndex = optionIndices[Type.REMOVE_METADATA]
        if (removeMetadataIndex != null) {
            adapter.setEnabled(removeMetadataIndex, allowRemoveMetadata)
            adapter.notifyDataSetChanged()
        }
        var extensionFormat = "."
        val reencoding = holder.reencoding
        if (reencoding != null) {
            extensionFormat += reencoding.format
        } else {
            extensionFormat += getFileExtension(holder.name)
        }
        extensionTextView.setText(extensionFormat)
        val removeIndex = optionIndices[Type.REMOVE_FILE_NAME]
        val renameIndex = optionIndices[Type.RENAME]
        if (removeIndex != null && renameIndex != null) {
            adapter.setEnabled(renameIndex, !holder.optionRemoveFileName)
            adapter.notifyDataSetChanged()
        }
        updateFilenameElementsEnabled(holder)
    }

    private fun updateFilenameElementsEnabled(holder: AttachmentHolder) {
        filenameEditText.setEnabled(holder.optionCustomName && !holder.optionRemoveFileName)
        extensionTextView.setEnabled(holder.optionCustomName && !holder.optionRemoveFileName)
        restoreButton.setEnabled(holder.optionCustomName && !holder.optionRemoveFileName)
    }

    override fun onItemClick(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long,
    ) {
        val holder = this.attachmentHolder ?: return
        val type = optionItems[position].type
        val checked = listView.isItemChecked(position)
        when (type) {
            Type.UNIQUE_HASH -> {
                holder.optionUniqueHash = checked
            }

            Type.REMOVE_METADATA -> {
                holder.optionRemoveMetadata = checked
            }

            Type.REENCODE_IMAGE -> {
                if (checked) {
                    listView.setItemChecked(position, false)
                    ReencodingDialog().show(getChildFragmentManager(), ReencodingDialog.TAG)
                } else {
                    holder.reencoding = null
                }
            }

            Type.REMOVE_FILE_NAME -> {
                holder.optionRemoveFileName = checked
            }

            Type.SPOILER -> {
                holder.optionSpoiler = checked
            }

            Type.RENAME -> {
                holder.optionCustomName = checked
            }
        }
        updateItemsEnabled(
            (listView.getAdapter() as HeaderViewListAdapter).getWrappedAdapter() as com.mishiranu.dashchan.ui.posting.dialog.AttachmentOptionsDialog.ItemsAdapter,
            holder,
        )
    }

    fun setReencoding(reencoding: Reencoding?) {
        val holder = this.attachmentHolder ?: return
        val reencodeIndex = optionIndices[Type.REENCODE_IMAGE]
        if (reencodeIndex != null) {
            holder.reencoding = reencoding
            listView.setItemChecked(reencodeIndex, reencoding != null)
            updateItemsEnabled(
                (listView.getAdapter() as HeaderViewListAdapter).getWrappedAdapter() as com.mishiranu.dashchan.ui.posting.dialog.AttachmentOptionsDialog.ItemsAdapter,
                holder,
            )
        }
    }

    companion object {
        @JvmField
        val TAG: String = AttachmentOptionsDialog::class.java.getName()

        private const val EXTRA_ATTACHMENT_INDEX = "attachmentIndex"

        /** Height of the cover-less audio / video preview box, which has no image to size itself. */
        private const val PREVIEW_MIN_HEIGHT_DP = 128f
    }
}
