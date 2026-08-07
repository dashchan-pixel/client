package com.mishiranu.dashchan.widget

import android.content.Context
import android.view.Gravity
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
 * The dropdown behind the ⌘ buttons — the posting screen's and the floating toolbar's — listing the
 * [commands][CommandsStorage.CommandItem] available there and letting the user run one. A command that
 * is not the user's to run ([CommandsStorage.CommandItem.runsByHand]) is shown greyed-out, a tap runs
 * the rest, and a long tap opens any command — greyed-out or not — for editing.
 *
 * It is a [ListPopupWindow] with a background we control directly
 * ([ThemeEngine.roundedPopupBackground]), because a framework PopupMenu draws its own square
 * background over the rounded window. It floats on `Widget.AppListPopupWindow`, which is where the
 * elevation comes from; see the style for why the popup has to be built with one.
 *
 * [menuContext] themes the rows and is not necessarily the anchor's own context, for the same reason
 * as in [DropdownPopup]: the floating toolbar's ⌘ is built in the *toolbar's* context, whose text
 * colour is the one that reads on the toolbar rather than the one that reads on this popup's card.
 */
object CommandsPopup {
    /**
     * The popup hugs its widest command name between these two. The floor is the Material menu
     * minimum — the same one [DropdownPopup] holds a radio list to — rather than a width chosen to
     * make the popup read as a list on its own: a bar of short command names stood the popup half a
     * screen wide with nothing in most of it. The ceiling keeps a command someone gave a sentence for
     * a name from pushing the popup across the screen.
     */
    private const val MIN_WIDTH_DP = 112f
    private const val MAX_WIDTH_DP = 280f

    /**
     * Shows the popup for [commands] anchored to [anchor]. [onRun] is invoked when a command the user
     * may run is tapped; [onEdit] when any command is long-tapped. No-op if [commands] is empty.
     *
     * [alignEnd] hangs the popup from the anchor's end edge instead of its start edge, which is what an
     * anchor in the end corner of the screen wants: the popup then keeps whatever margin the anchor has
     * rather than being pushed flush against the screen edge to fit.
     */
    fun show(
        anchor: View,
        commands: List<CommandsStorage.CommandItem>,
        menuContext: Context = anchor.context,
        alignEnd: Boolean = false,
        onRun: (CommandsStorage.CommandItem) -> Unit,
        onEdit: (CommandsStorage.CommandItem) -> Unit,
    ) {
        if (commands.isEmpty()) {
            return
        }
        val context = menuContext
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
                    view.alpha = if (commands[position].runsByHand) 1f else 0.5f
                    return view
                }
            }
        val popup = ListPopupWindow(context, null, 0, R.style.Widget_AppListPopupWindow)
        popup.anchorView = anchor
        popup.isModal = true
        if (alignEnd) {
            popup.setDropDownGravity(Gravity.END)
        }
        // Don't disturb the soft keyboard's open/closed state when the dropdown shows or dismisses.
        popup.inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setAdapter(adapter)
        // Tap feedback in a ListView comes from the list selector, not item backgrounds. Use the app's
        // neutral selectable-item ripple instead of the default accent-tinted selector.
        popup.setListSelector(context.getDrawable(getResourceId(context, android.R.attr.selectableItemBackground, 0)))
        popup.width = measureWidth(adapter, context)
        popup.setBackgroundDrawable(ThemeEngine.roundedPopupBackground(context))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (commands[position].runsByHand) {
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
        return contentWidth.coerceIn((MIN_WIDTH_DP * density).toInt(), (MAX_WIDTH_DP * density).toInt())
    }
}
