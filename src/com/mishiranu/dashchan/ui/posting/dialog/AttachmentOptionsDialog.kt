package com.mishiranu.dashchan.ui.posting.dialog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
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
import android.widget.HeaderViewListAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import chan.util.StringUtils.getFileExtension
import chan.util.StringUtils.removeFileExtension
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.DraftsStorage.Companion.getInstance
import com.mishiranu.dashchan.graphics.TransparentTileDrawable
import com.mishiranu.dashchan.ui.posting.AttachmentHolder
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback
import com.mishiranu.dashchan.util.FilenameUtils.getFilenameMaxCharacterCount
import com.mishiranu.dashchan.util.FilenameUtils.isValidCharacter
import com.mishiranu.dashchan.util.GraphicsUtils.Reencoding
import com.mishiranu.dashchan.util.GraphicsUtils.canRemoveMetadata
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.MaterialButton

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
        val activity: Activity? = getActivity()
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
            return Dialog(activity!!)
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
        if (postingConfiguration!!.attachmentSpoiler) {
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
        val imageView = ImageView(activity)
        imageView.setBackground(TransparentTileDrawable(activity!!, true))
        imageView.setImageDrawable(holder.imageView.getDrawable())
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
        linearLayout.addView(
            imageView,
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
            InputFilter { source: CharSequence?, start: Int, end: Int, dest: Spanned?, dstart: Int, dend: Int ->
                for (i in start..<end) {
                    if (!isValidCharacter(source!![i])) {
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
        if (holder.reencoding != null) {
            extensionFormat += holder.reencoding!!.format
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
    }
}
