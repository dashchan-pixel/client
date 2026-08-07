package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.ResourceUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The bar of page actions that floats in the bottom corner of a list page, holding what used to be the
 * icons at the far end of the toolbar: the pencil that opens the submission form, the button that
 * refreshes what is on screen, and — when the user has written any — the ⌘ that reaches the app's
 * global commands. It is painted in the toolbar's own colour and carries the toolbar's icons, because
 * it is the toolbar's end: the actions moved down to the thumb rather than changed.
 *
 * The view added to the page is a full-size transparent container and the bar is a child of it, laid
 * out in a corner. That is what lets the insets [ExpandedLayout] hands its children be the bar's
 * margins — a bar sitting directly in the page would take them as padding and grow instead of moving —
 * and it keeps the bar clear of the navigation bar without the page knowing anything about it. The
 * container itself is not clickable, so a touch that misses the bar falls through to the list behind
 * it, which is the whole point of a bar that floats over content.
 *
 * Two gestures belong to the bar itself, which is why it handles touches rather than leaving them to
 * its buttons:
 * - **a long press then a drag reorders the buttons.** The press is the button's own long click (so
 *   the bar needs no gesture detector to find it) and the drag is the bar's, taken over the moment the
 *   finger moves: the dragged button follows the finger and the ones it passes slide out of its way by
 *   a button's width, none of them changing place in the layout until the finger lifts. Reordering the
 *   views live instead would send [android.view.ViewGroup.removeView] through the view being touched,
 *   which cancels the gesture that is doing the dragging.
 * - **a two finger tap turns the bar between horizontal and vertical.** It is caught at the second
 *   finger's ACTION_POINTER_DOWN, before any button can take it for a press.
 *
 * Both the order and the direction are the user's, so both are kept in [Preferences] rather than in the
 * page: the bar looks the same on every page it appears on, and a button a page does not offer is
 * simply absent from an order that still holds its place.
 */
@SuppressLint("ViewConstructor")
class FloatingToolbar(
    context: Context,
    /** The toolbar's own context: what the bar's colour, its icons and their ripples are read from. */
    private val toolbarContext: Context,
) : FrameLayout(context) {
    /**
     * A button's identity, which is what an order is written in — not the menu item behind it, which
     * differs from page to page (a thread's *Reply* and a board's *New thread* are one button).
     *
     * Declaration order is the default order: pencil, refresh, command.
     */
    enum class Slot {
        COMPOSE,
        CONTENTS,
        COMMANDS,
    }

    /**
     * One button of the bar: which [slot] it occupies, what it looks like and what a tap does.
     *
     * [onClick] is handed the *bar* rather than the button that was tapped, because the only thing a
     * page does with the view is anchor a popup to it: a dropdown then hangs from the bar's own edge,
     * keeping the margin the bar keeps, instead of from whichever button the user happened to have
     * dragged the ⌘ next to.
     */
    class Action(
        val slot: Slot,
        val icon: Drawable?,
        val title: CharSequence?,
        val onClick: (View) -> Unit,
    )

    private val density = ResourceUtils.obtainDensity(context)
    private val bar = Bar(toolbarContext)
    private var actions: List<Action> = emptyList()

    /** The slots standing as spinners rather than as their icon; see [setBusy]. */
    private val busySlots = HashSet<Slot>()

    /**
     * Told the room the bar takes at the bottom of the page whenever that changes, so the list behind
     * can keep its last item out from under it. Called with 0 for a bar that is off screen.
     */
    var onContentInsetChanged: ((Int) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(contentInset())
        }

    /**
     * How far up from the bottom of the page the bar reaches: its own thickness — a button plus the
     * bar's padding, times the buttons in a column when it stands vertical — and the margin below it,
     * and the same margin again above it so the last item of a list clears the bar rather than ending
     * against it. Worked out from what the bar is made of rather than measured, because the list is
     * padded while the bar is still being laid out.
     */
    private fun contentInset(): Int {
        val actions = this.actions
        if (actions.isEmpty()) {
            return 0
        }
        val across = if (Preferences.isFloatingToolbarVertical) actions.size else 1
        return ((across * BUTTON_SIZE_DP + 2 * BAR_PADDING_DP + 2 * MARGIN_DP) * density).toInt()
    }

    /** The toolbar's own ripple colour, the one the borderless button style would have used. */
    private val rippleHighlight =
        ResourceUtils.getColorStateList(toolbarContext, android.R.attr.colorControlHighlight)
            ?: ColorStateList.valueOf(Color.TRANSPARENT)

    init {
        val margin = (MARGIN_DP * density).toInt()
        addView(
            bar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(margin, margin, margin, margin) },
        )
        visibility = GONE
    }

    /**
     * Replaces what the bar holds. An empty list takes the whole thing off screen, so a page with
     * nothing to offer shows no empty bar.
     */
    fun setActions(actions: List<Action>) {
        if (sameActions(this.actions, actions)) {
            return
        }
        this.actions = actions
        rebuild()
    }

    /**
     * Whether a fresh set of actions is the one already on screen. The menu is prepared far more often
     * than it changes, and rebuilding the buttons under a finger that is dragging one of them would
     * drop the drag.
     */
    private fun sameActions(
        current: List<Action>,
        next: List<Action>,
    ): Boolean =
        current.size == next.size &&
            current.indices.all { current[it].slot == next[it].slot && current[it].title == next[it].title }

    /**
     * Turns a slot into a spinner in its own place in the bar, and back into its button. For work a
     * button started that the user is waiting on: the ⌘ spins while the command it ran is running,
     * which is where the modal progress dialog such a run used to put itself.
     *
     * A busy slot the bar does not currently hold is remembered rather than dropped, so a bar rebuilt
     * while the work is still going comes back with the spinner still in it.
     */
    fun setBusy(
        slot: Slot,
        busy: Boolean,
    ) {
        val changed = if (busy) busySlots.add(slot) else busySlots.remove(slot)
        if (changed && actions.any { it.slot == slot }) {
            rebuild()
        }
    }

    private fun rebuild() {
        bar.removeAllViews()
        val actions = this.actions
        onContentInsetChanged?.invoke(contentInset())
        if (actions.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val order = storedOrder()
        val size = (BUTTON_SIZE_DP * density).toInt()
        bar.orientation = if (Preferences.isFloatingToolbarVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (action in actions.sortedBy { order.indexOf(it.slot) }) {
            val view = if (action.slot in busySlots) createProgress(action) else createButton(action)
            bar.addView(view, LinearLayout.LayoutParams(size, size))
        }
        val padding = (BAR_PADDING_DP * density).toInt()
        bar.setPadding(padding, padding, padding, padding)
        val radius = min(Preferences.uiCornerRadius * density, (size + 2 * padding) / 2f)
        val shape =
            MaterialShapeDrawable(
                ShapeAppearanceModel
                    .builder()
                    .setAllCornerSizes(radius)
                    .build(),
            )
        shape.fillColor = ColorStateList.valueOf(ThemeEngine.getTheme(toolbarContext).primary or Color.BLACK)
        bar.background = shape
        bar.clipToOutline = true
        bar.elevation = ELEVATION_DP * density
        // The corner is rounded off the ripple here rather than left to the bar to clip, because a
        // borderless button's ripple is *unbounded* and an unbounded ripple projects: it is not drawn
        // into the button's own layer at all but into the nearest ancestor that receives projections,
        // which is how it escapes the bar's clipToOutline and squares the corner off for as long as
        // the button is held. Giving each button a mask makes its ripple bounded — nothing to project,
        // nothing to clip — and the mask carries the bar's own corner, pulled in by the bar's padding
        // so the two curves are the same curve.
        for (index in 0 until bar.childCount) {
            bar.getChildAt(index).background =
                RippleDrawable(
                    rippleHighlight,
                    null,
                    ShapeDrawable(
                        RoundRectShape(cornerRadii(index, bar.childCount, (radius - padding).coerceAtLeast(0f)), null, null),
                    ).apply { paint.color = Color.WHITE },
                )
        }
    }

    /**
     * The eight radii of the button at [index] of [count]: the bar's [radius] on the corners that are
     * the bar's own corners, and square everywhere the button meets another button.
     */
    private fun cornerRadii(
        index: Int,
        count: Int,
        radius: Float,
    ): FloatArray {
        val vertical = Preferences.isFloatingToolbarVertical
        // A horizontal bar's first button is at its *right* when the layout runs right to left, so
        // which corners are the bar's follow the direction the buttons were laid out in, not the index.
        val rtl = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        val atStart = index == 0
        val atEnd = index == count - 1
        val left = if (rtl) atEnd else atStart
        val right = if (rtl) atStart else atEnd
        val topLeft = if (vertical) atStart else left
        val topRight = if (vertical) atStart else right
        val bottomLeft = if (vertical) atEnd else left
        val bottomRight = if (vertical) atEnd else right
        return floatArrayOf(
            if (topLeft) radius else 0f,
            if (topLeft) radius else 0f,
            if (topRight) radius else 0f,
            if (topRight) radius else 0f,
            if (bottomRight) radius else 0f,
            if (bottomRight) radius else 0f,
            if (bottomLeft) radius else 0f,
            if (bottomLeft) radius else 0f,
        )
    }

    private fun createButton(action: Action): ImageView {
        val button = ImageView(toolbarContext, null, android.R.attr.borderlessButtonStyle)
        button.setImageDrawable(action.icon)
        button.scaleType = ImageView.ScaleType.CENTER
        button.contentDescription = action.title
        button.setOnClickListener { action.onClick(bar) }
        button.setOnLongClickListener { view -> bar.beginDrag(view) }
        return button
    }

    /**
     * The spinner that stands in for a busy slot's button: the same box in the same place in the bar,
     * painted the toolbar's own colour like every icon here, so it reads as that button being busy
     * rather than as the bar having changed. Padded down to an icon's size, because a ProgressBar
     * scales its drawable into whatever the padding leaves and its own is smaller than a button.
     *
     * It carries no click and no long press: there is nothing to run while a run is going, and a slot
     * that is not a button for the moment is not one to drag either.
     */
    private fun createProgress(action: Action): View {
        val progress = ProgressBar(toolbarContext, null, android.R.attr.progressBarStyleSmall)
        progress.indeterminateTintList =
            ColorStateList.valueOf(ResourceUtils.getColor(toolbarContext, android.R.attr.textColorPrimary))
        val padding = ((BUTTON_SIZE_DP - PROGRESS_SIZE_DP) / 2f * density).toInt()
        progress.setPadding(padding, padding, padding, padding)
        progress.contentDescription = action.title
        return progress
    }

    /**
     * The stored order, with anything it does not name appended in declaration order — a bar the user
     * has never dragged is the declaration order in full, and one dragged before a button existed keeps
     * what it says about the buttons it does know.
     */
    private fun storedOrder(): List<Slot> {
        val stored =
            Preferences
                .floatingToolbarOrder
                .orEmpty()
                .split(',')
                .mapNotNull { name -> Slot.entries.firstOrNull { it.name == name } }
        return stored + Slot.entries.filter { it !in stored }
    }

    /** Writes the order the buttons are in now, the one that was at [from] having moved to [to]. */
    private fun storeOrder(
        from: Int,
        to: Int,
    ) {
        val order = storedOrder().toMutableList()
        val shown = actions.map { it.slot }.sortedBy { order.indexOf(it) }.toMutableList()
        shown.add(to, shown.removeAt(from))
        // Only the slots on screen have been reordered; the rest keep the places they had, so a button
        // that is not here today comes back where the user last left it.
        val absent = order.filter { it !in shown }
        Preferences.floatingToolbarOrder = (shown + absent).joinToString(",") { it.name }
    }

    /**
     * The bar proper. [FrameLayout] holds it in a corner; this holds the buttons and the two gestures
     * that are the bar's rather than a button's.
     */
    private inner class Bar(
        context: Context,
    ) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        init {
            // The bar swallows what lands on it rather than letting a tap between two buttons scroll the
            // list behind, and a swallowed ACTION_DOWN is also what keeps the second finger of a two
            // finger tap coming to this view when the first one missed a button.
            isClickable = true
        }

        private var dragView: View? = null
        private var dragging = false
        private var dragFrom = 0
        private var dragTo = 0
        private var downX = 0f
        private var downY = 0f

        private var twoFinger = false
        private var twoFingerTime = 0L
        private var twoFingerX = 0f
        private var twoFingerY = 0f

        /**
         * Lifts [view] out of the row, ready for the drag the next movement starts. Declined when it is
         * the only button there is, so its long press falls back to the tooltip every other icon shows.
         */
        fun beginDrag(view: View): Boolean {
            if (childCount <= 1) {
                return false
            }
            dragView = view
            dragFrom = indexOfChild(view)
            dragTo = dragFrom
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view
                .animate()
                .scaleX(DRAG_SCALE)
                .scaleY(DRAG_SCALE)
                .alpha(DRAG_ALPHA)
                .setDuration(SHIFT_DURATION)
                .start()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    twoFinger = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!dragging && ev.pointerCount == 2) {
                        endDrag(commit = false)
                        twoFinger = true
                        twoFingerTime = SystemClock.uptimeMillis()
                        twoFingerX = ev.rawX
                        twoFingerY = ev.rawY
                        return true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragView != null) {
                        dragging = true
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag(commit = false)
                }
            }
            return false
        }

        // The bar has no click of its own: what lands on it either belongs to a gesture below or is
        // passed to View, which calls performClick itself.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (twoFinger) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        val quick = SystemClock.uptimeMillis() - twoFingerTime <= TWO_FINGER_TAP_TIMEOUT
                        val still =
                            abs(event.rawX - twoFingerX) <= touchSlop && abs(event.rawY - twoFingerY) <= touchSlop
                        twoFinger = false
                        if (quick && still) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            Preferences.isFloatingToolbarVertical = !Preferences.isFloatingToolbarVertical
                            rebuild()
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        twoFinger = false
                    }
                }
                return true
            }
            if (dragging) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> dragTo(event.rawX - downX, event.rawY - downY)
                    MotionEvent.ACTION_UP -> endDrag(commit = true)
                    MotionEvent.ACTION_CANCEL -> endDrag(commit = false)
                }
                return true
            }
            return super.onTouchEvent(event)
        }

        /**
         * Moves the dragged button by the distance the finger has covered and slides whatever it has
         * passed out of its way. Nothing changes place in the layout here — where the button would land
         * is [dragTo], and it lands there when the finger lifts.
         */
        private fun dragTo(
            dx: Float,
            dy: Float,
        ) {
            val view = dragView ?: return
            val vertical = orientation == VERTICAL
            val step = (if (vertical) view.height else view.width).toFloat()
            if (step <= 0f) {
                return
            }
            val offset = (if (vertical) dy else dx).coerceIn(-dragFrom * step, (childCount - 1 - dragFrom) * step)
            if (vertical) {
                view.translationY = offset
            } else {
                view.translationX = offset
            }
            val target = (dragFrom + (offset / step).roundToInt()).coerceIn(0, childCount - 1)
            if (target != dragTo) {
                dragTo = target
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    if (child === view) {
                        continue
                    }
                    val shift =
                        when {
                            index in (dragFrom + 1)..dragTo -> -step
                            index in dragTo..<dragFrom -> step
                            else -> 0f
                        }
                    val animator = child.animate().setDuration(SHIFT_DURATION)
                    if (vertical) {
                        animator.translationY(shift)
                    } else {
                        animator.translationX(shift)
                    }
                    animator.start()
                }
            }
        }

        /**
         * Ends a drag, whether the finger lifted on a new place for the button ([commit]) or the
         * gesture was taken away. A committed move is written out and the bar rebuilt from it, which is
         * also what puts every button back at rest; an abandoned one just clears what the drag drew.
         */
        private fun endDrag(commit: Boolean) {
            val view = dragView ?: return
            dragView = null
            dragging = false
            if (commit && dragTo != dragFrom) {
                storeOrder(dragFrom, dragTo)
                rebuild()
                return
            }
            view
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(SHIFT_DURATION)
                .start()
            for (index in 0 until childCount) {
                getChildAt(index)
                    .animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(SHIFT_DURATION)
                    .start()
            }
        }
    }

    companion object {
        private const val BUTTON_SIZE_DP = 44f

        /** The box the busy spinner is drawn into, an action bar icon's own size. */
        private const val PROGRESS_SIZE_DP = 24f
        private const val BAR_PADDING_DP = 2f
        private const val MARGIN_DP = 16f
        private const val ELEVATION_DP = 6f
        private const val SHIFT_DURATION = 120L
        private const val TWO_FINGER_TAP_TIMEOUT = 300L
        private const val DRAG_SCALE = 1.15f
        private const val DRAG_ALPHA = 0.9f
    }
}
