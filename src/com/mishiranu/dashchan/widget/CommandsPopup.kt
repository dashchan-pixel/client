package com.mishiranu.dashchan.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.storage.CommandsStorage
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ResourceUtils.getResourceId
import kotlin.math.max

/**
 * The dropdown behind the posting screen's ⌘ button, listing the
 * [commands][CommandsStorage.CommandItem] available there and letting the user run one. Auto-run
 * commands are shown greyed-out (they fire automatically and can't be run by hand), a tap runs a manual
 * command, and a long tap opens any command — greyed-out or not — for editing.
 *
 * It is a [ListPopupWindow] with a background we control directly
 * ([ThemeEngine.roundedPopupBackground]), because a framework PopupMenu draws its own square
 * background over the rounded window.
 */
object CommandsPopup {
    /**
     * Shows the popup for [commands] anchored to [anchor]. [onRun] is invoked when a non-auto-run
     * command is tapped; [onEdit] when any command is long-tapped. No-op if [commands] is empty.
     */
    fun show(
        anchor: View,
        commands: List<CommandsStorage.CommandItem>,
        onRun: (CommandsStorage.CommandItem) -> Unit,
        onEdit: (CommandsStorage.CommandItem) -> Unit,
    ) {
        if (commands.isEmpty()) {
            return
        }
        val context = anchor.context
        val density = ResourceUtils.obtainDensity(context)
        val titles: List<CharSequence> =
            commands.map { command ->
                val name = command.name
                if (name.isNullOrEmpty()) context.getString(R.string.command) else name
            }
        val itemPaddingHorizontal = (16f * density).toInt()
        val itemPaddingVertical = (12f * density).toInt()
        val adapter =
            object : ArrayAdapter<CharSequence>(
                context,
                android.R.layout.simple_list_item_1,
                android.R.id.text1,
                titles,
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    view.setPadding(
                        itemPaddingHorizontal,
                        itemPaddingVertical,
                        itemPaddingHorizontal,
                        itemPaddingVertical,
                    )
                    // Run-on-send/open commands can't be run manually (greyed out) but can still be
                    // opened for edit via long tap, so the row stays enabled; only the tap action is
                    // guarded.
                    view.alpha = if (commands[position].autoRun) 0.5f else 1f
                    return view
                }
            }
        val popup = ListPopupWindow(context)
        popup.anchorView = anchor
        popup.isModal = true
        // Don't disturb the soft keyboard's open/closed state when the dropdown shows or dismisses.
        popup.inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setAdapter(adapter)
        // Tap feedback in a ListView comes from the list selector, not item backgrounds. Use the app's
        // neutral selectable-item ripple instead of the default accent-tinted selector.
        popup.setListSelector(context.getDrawable(getResourceId(context, android.R.attr.selectableItemBackground, 0)))
        popup.width = measureWidth(adapter, context)
        popup.setBackgroundDrawable(ThemeEngine.roundedPopupBackground(context))
        try {
            val popupField = androidx.appcompat.widget.ListPopupWindow::class.java.getDeclaredField("mPopup")
            popupField.isAccessible = true
            val popupWindow = popupField.get(popup) as android.widget.PopupWindow
            popupWindow.elevation = 8f * density
        } catch (e: Exception) {
            android.util.Log.w("CommandsPopup", "Failed to set popup elevation", e)
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (!commands[position].autoRun) {
                onRun(commands[position])
            }
        }
        popup.show()
        popup.listView?.setOnItemLongClickListener { _, _, position, _ ->
            popup.dismiss()
            onEdit(commands[position])
            true
        }
    }

    private fun measureWidth(
        adapter: ArrayAdapter<CharSequence>,
        context: Context,
    ): Int {
        val density = ResourceUtils.obtainDensity(context)
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val fakeParent = android.widget.FrameLayout(context)
        var contentWidth = 0
        var itemView: View? = null
        for (i in 0 until adapter.count) {
            itemView = adapter.getView(i, itemView, fakeParent)
            itemView.measure(measureSpec, measureSpec)
            contentWidth = max(contentWidth, itemView.measuredWidth)
        }
        return contentWidth.coerceIn((200f * density).toInt(), (280f * density).toInt())
    }
}
