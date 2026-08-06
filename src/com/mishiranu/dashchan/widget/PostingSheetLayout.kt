package com.mishiranu.dashchan.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The submission form as a modal bottom sheet over the page it was opened from, used instead of a form
 * filling the screen when [Preferences.isPostingSheet] is on. The point of it is the thread staying
 * where it was: the sheet is only as tall as the form inside it, so the posts being replied to are on
 * screen above it, and swiping it away or tapping above it dismisses the form — which is what
 * [onDismiss] reports, since the fragment leaving the screen is the activity's business, not this
 * view's.
 *
 * The sheet has two heights and it measures both of them itself in [onMeasure] rather than leaving them
 * to a `wrap_content` pass: the height its content asks for, clamped to between [MIN_HEIGHT_RATIO] of
 * the room it has and all of it, and the room in full. The form is given exactly what the rows around
 * it leave, so the drag handle above and the send button below are the size they asked for whatever the
 * sheet's height is. Handing a `wrap_content` sheet a weighted child to squeeze instead read as the same
 * thing but was not: a sheet whose content had grown into the top of its parent overshot it by a row,
 * and the row that went past the bottom edge was the one with the send button in it.
 *
 * Which of the two it is at rest is [full], and the way between them is the handle: dragging it moves
 * the sheet's top edge under the finger, and letting go runs the height the rest of the way to whichever
 * of the two is nearer. [BottomSheetBehavior] cannot do that itself — a sheet already as tall as its
 * content has nowhere above to slide to, so it clamps an upward drag away to nothing — so the handle's
 * row is this view's own grip; see [onInterceptTouchEvent].
 *
 * Deliberately **not** taking the window insets the way every other content view does, which is why it
 * implements [ExpandedScreen.Layout] and then uses neither the padding nor the margins that interface is
 * usually about: this view fills the screen, and the *sheet* is what the insets are applied to. Sitting
 * below the toolbar is a ceiling on the sheet's height ([insetTop] is room this view has that the sheet
 * may not use), and clearing the navigation bar is the footer's bottom padding — so the sheet's
 * background reaches the bottom edge of the screen and the page behind it does not show through a strip
 * under the send button. Taken as margins on this view instead, that strip is exactly what it left.
 */
@SuppressLint("ViewConstructor")
class PostingSheetLayout(
    context: Context,
    private val form: View,
    private val onDismiss: () -> Unit,
) : CoordinatorLayout(context),
    ExpandedScreen.Layout {
    /**
     * The row the drag handle sits in, and the sheet's grip: dragging it up gives the form the whole
     * screen, dragging it down gives the sheet its content's height back and then swipes it away.
     */
    val header: FrameLayout = FrameLayout(context)

    /**
     * The row pinned below the scrolling form, which the form puts its captcha and send button in.
     * Whatever height the sheet has settled at, this row is the bottom of it — a send button that
     * scrolled with the rest of the form would be off the end of a sheet shorter than the form in it.
     */
    val footer: FrameLayout = FrameLayout(context)

    private val scrim = View(context)
    private val sheet = LinearLayout(context)
    private val behavior = BottomSheetBehavior<View>()

    private var opened = false

    /** Whether the sheet is at rest at the whole of the room it has, rather than at its content's height. */
    private var full = false

    /** The room the toolbar leaves above the sheet, which is this view's and not the sheet's to use. */
    private var insetTop = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** How far past the height the form asks for a downward drag goes before it means "dismiss". */
    private val dismissSlop = (DISMISS_SLOP_DP * ResourceUtils.obtainDensity(context)).toInt()

    /** Set while a gesture that started on [header] is running, since that one is ours. */
    private var gripping = false
    private var gripY = 0f
    private var gripHeight = 0

    /** Set once a [header] gesture has moved far enough to be a drag rather than a tap. */
    private var gripMoved = false

    /** Set while the finger is far enough below the sheet's own height that letting go dismisses it. */
    private var gripDismiss = false

    /**
     * Whether the grip started at the height the form asks for, which is the sheet with nothing left to
     * give back: only from there does dragging down mean the sheet going away. Starting taller it means
     * the height it is not using, and a drag long enough to reach both should still only do the first.
     */
    private var gripFromCollapsed = false

    /**
     * The height the sheet is held at by the finger on the handle or by the animation that follows it,
     * or -1 when it is at rest and [onMeasure] works the height out for itself.
     */
    private var dragHeight = -1

    private var animator: ValueAnimator? = null

    /** The two heights the sheet rests at, as [onMeasure] last worked them out; the drag reads them. */
    private var collapsedHeight = 0
    private var fullHeight = 0

    /**
     * The height the form last asked for, kept while a drag is running: the form cannot change under a
     * finger on the handle, and re-measuring the whole of it every frame is the one thing here
     * expensive enough to be felt.
     */
    private var contentHeight = -1

    /** Set when the height changed in the last measure, which a settle in flight is aiming past. */
    private var heightChanged = false

    /** Set once the sheet is on its way out, so nothing else tries to put it back on screen. */
    private var hiding = false

    /**
     * False once the sheet is on its way out, so that a back press during the closing animation falls
     * through to the navigation a dismissed sheet is waiting for anyway.
     */
    var isOpen: Boolean = true
        private set

    init {
        val density = ResourceUtils.obtainDensity(context)
        val theme = ThemeEngine.getTheme(context)

        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f
        // Consume the taps that miss the sheet rather than letting them reach the page behind it
        scrim.isClickable = true
        scrim.setOnClickListener { hide() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val radius = Preferences.uiCornerRadius * density
        val background =
            MaterialShapeDrawable(
                ShapeAppearanceModel
                    .builder()
                    .setTopLeftCornerSize(radius)
                    .setTopRightCornerSize(radius)
                    .build(),
            )
        background.fillColor = ColorStateList.valueOf(theme.card)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = background
        sheet.elevation = ELEVATION_DP * density
        val dragHandle = BottomSheetDragHandleView(MaterialContext.wrap(context))
        // The Material3 overlay is only there to supply the handle's shape and size; its stock palette
        // must not leak in, the handle is a control like any other in the user's theme
        dragHandle.imageTintList = ColorStateList.valueOf(theme.controlNormal21)
        // The handle's own click cycles the behavior's states, which are not the two heights this sheet
        // has; the row it sits in carries that instead, so that a reader is told about the one thing the
        // handle does here and touch and accessibility both arrive at the same place
        dragHandle.isClickable = false
        dragHandle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // The stock handle is a 48dp box with the bar drawn 20dp above the bottom of it, which above a
        // form whose first row is a text field reads as a gap rather than as a grip. The row keeps the
        // whole width of the sheet to be grabbed by; it is only no taller than the bar in it needs.
        dragHandle.setPadding(0, 0, 0, 0)
        dragHandle.minimumHeight = (HANDLE_HEIGHT_DP * density).toInt()
        header.addView(
            dragHandle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        header.isClickable = true
        header.setOnClickListener { settle(!full) }
        updateHeaderDescription()
        sheet.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Both rows keep the height they ask for; the form is measured to what is left, in onMeasure
        sheet.addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        sheet.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            sheet,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.behavior = behavior
            },
        )

        // The sheet is as tall as it has measured itself to be, so the state it settles in is the one
        // the content asks for and the behavior has no second stop of its own to drag to. The collapsed
        // peek is skipped as well, which makes a downward fling a dismissal rather than a stop at it.
        behavior.isFitToContents = true
        behavior.isHideable = true
        behavior.skipCollapsed = true
        // Starts off screen so that the first layout can slide it in; see onLayout
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        behavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int,
                ) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN && opened) {
                        isOpen = false
                        // Out of the settling pass: dismissing takes the view being settled away
                        post { onDismiss() }
                    }
                }

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float,
                ) {
                    // Not slideOffset, which measures the travel from the collapsed peek this sheet
                    // skips: the scrim tracks how much of the room the sheet is using, so the thread
                    // behind is dimmed by exactly as much as the form covering it -- clear again by the
                    // time the sheet has been swiped away, and barely shaded under a short sheet, which
                    // is what makes the posts back there readable.
                    val room = (height - insetTop).coerceAtLeast(1)
                    val shown = (height - bottomSheet.top).toFloat() / room
                    scrim.alpha = SCRIM_ALPHA * shown.coerceIn(0f, 1f)
                }
            },
        )
    }

    /**
     * The insets, which are the sheet's and not this view's: see the note on the class. The scrim keeps
     * the toolbar out of what it dims, the way it did when that was this view's own top margin.
     */
    override fun setVerticalInsets(
        top: Int,
        bottom: Int,
        useGesture29: Boolean,
    ) {
        if (insetTop != top || footer.paddingBottom != bottom) {
            insetTop = top
            ViewUtils.setNewMargin(scrim, null, top, null, null)
            footer.setPadding(0, 0, 0, bottom)
            requestLayout()
        }
    }

    /** Swipes the sheet away, which dismisses the form once it is off screen. */
    fun hide() {
        if (isOpen && !hiding) {
            hiding = true
            stopAnimation()
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun updateHeaderDescription() {
        header.contentDescription =
            context.getString(if (full) R.string.collapse else R.string.expand)
    }

    /**
     * Runs the sheet's height to one of the two it rests at, so that letting go of the handle finishes
     * the movement the finger was making instead of jumping to the end of it. Resizing is what is
     * animated, not sliding: the sheet's bottom edge does not move, [onLayout] holds it against the
     * bottom of the screen at every height along the way.
     */
    private fun settle(toFull: Boolean) {
        stopAnimation()
        full = toFull
        updateHeaderDescription()
        val target = if (toFull) fullHeight else collapsedHeight
        val from = if (dragHeight >= 0) dragHeight else sheet.height
        if (from <= 0 || from == target) {
            atRest()
            return
        }
        val animator = ValueAnimator.ofInt(from, target)
        animator.duration = RESIZE_DURATION
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            dragHeight = it.animatedValue as Int
            requestLayout()
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Only the animation still in charge gets to hand the height back; one that was
                    // cancelled has already had it taken away by whatever cancelled it
                    if (this@PostingSheetLayout.animator === animation) {
                        this@PostingSheetLayout.animator = null
                        atRest()
                    }
                }
            },
        )
        this.animator = animator
        animator.start()
    }

    /** Hands the height back to [onMeasure], which is what lets the form change it again. */
    private fun atRest() {
        dragHeight = -1
        contentHeight = -1
        requestLayout()
    }

    private fun stopAnimation() {
        val animator = this.animator
        // Cleared first, so that the listener knows the cancellation was not the animation finishing
        this.animator = null
        animator?.cancel()
    }

    /**
     * Takes the gestures that start on [header], because the one the sheet needs there is the one
     * [BottomSheetBehavior] cannot make. Under the finger the sheet's top edge follows it, up to the
     * whole of the room there is and down to the height the form asks for; further down than that is
     * the sheet being let go of, and it goes away when the finger does. The rest of it is still the
     * behavior's — the form scrolls and the sheet swipes off under a finger anywhere but here.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isOpen && !hiding && isInHeader(ev)) {
            stopAnimation()
            gripping = true
            gripMoved = false
            gripDismiss = false
            gripY = ev.y
            gripHeight = sheet.height
            gripFromCollapsed = gripHeight <= collapsedHeight + touchSlop
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    // The click a tap here ends in is the header row's, which is the view that carries the label and the
    // listener for it, so an accessibility activation arrives at the same place. A click on the sheet as
    // a whole is what should not exist: it would put the focus of a screen reader on the entire form.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!gripping) {
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - gripY
                if (gripMoved || abs(dy) >= touchSlop) {
                    gripMoved = true
                    val wanted = gripHeight - dy.roundToInt()
                    // The form's own height is as small as the sheet gets, so a finger below that is no
                    // longer resizing anything -- from a sheet already down there it is letting go of it
                    gripDismiss = gripFromCollapsed && wanted <= collapsedHeight - dismissSlop
                    dragHeight = wanted.coerceIn(collapsedHeight, fullHeight)
                    requestLayout()
                }
            }

            MotionEvent.ACTION_UP -> {
                gripping = false
                if (!gripMoved) {
                    // A grip that was never dragged anywhere is a tap on the handle, which asks for the
                    // other height -- the same request either way round
                    header.performClick()
                } else if (gripDismiss) {
                    hide()
                } else {
                    settle(2 * dragHeight >= collapsedHeight + fullHeight)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                gripping = false
                if (gripMoved) {
                    settle(full)
                }
            }

            else -> {
                Unit
            }
        }
        return true
    }

    private fun isInHeader(ev: MotionEvent): Boolean = ev.y >= sheet.top && ev.y < sheet.top + header.bottom && ev.x >= sheet.left && ev.x < sheet.right

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val room = MeasureSpec.getSize(heightMeasureSpec) - insetTop
        if (room > 0) {
            // What the rows around the form need, which they get whatever is going on above them
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val rows = measureRow(header, width, room) + measureRow(footer, width, room)
            // ... and what the form would like, which is what the sheet's height is taken from unless
            // the clamp has something to say about it, or the sheet is at the whole of the room
            if (dragHeight < 0 || contentHeight < 0) {
                contentHeight = rows + measureRow(form, width, (room - rows).coerceAtLeast(0))
            }
            fullHeight = room
            collapsedHeight = contentHeight.coerceIn((room * MIN_HEIGHT_RATIO).toInt(), room)
            val height =
                when {
                    dragHeight >= 0 -> dragHeight.coerceIn(collapsedHeight, fullHeight)
                    full -> fullHeight
                    else -> collapsedHeight
                }
            heightChanged = heightChanged || sheet.layoutParams.height != height
            sheet.layoutParams.height = height
            form.layoutParams.height = (height - rows).coerceAtLeast(0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The height [view] asks for with the room it is offered, which for the form is its content's. */
    private fun measureRow(
        view: View,
        width: Int,
        available: Int,
    ): Int {
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
        )
        return view.measuredHeight
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        val changedHeight = heightChanged
        heightChanged = false
        if (!opened) {
            // The behavior only animates between states it has laid the sheet out for, so the slide in
            // has to wait for the layout that put it off screen
            opened = true
            post { behavior.state = BottomSheetBehavior.STATE_EXPANDED }
            return
        }
        if (!isOpen || hiding) {
            return
        }
        // A sheet that is up and still has its bottom edge on the bottom edge of the screen -- that is
        // what makes the send button below the form the last row on it, and it is asserted here rather
        // than left to the behavior, which offsets the sheet by a top edge it worked out from the height
        // the sheet had when it last laid it out. A sheet that has grown since is left that much too
        // low: it grew downwards, past the bottom of the screen, and what went over the edge is exactly
        // the row with the send button in it. Growing upwards is the two lines below.
        when (behavior.state) {
            // Under a finger, and the finger says where it goes
            BottomSheetBehavior.STATE_DRAGGING -> {
                Unit
            }

            // Off screen and waiting to be slid in, which is an animation and not a place to be put
            BottomSheetBehavior.STATE_HIDDEN -> {
                Unit
            }

            BottomSheetBehavior.STATE_SETTLING -> {
                // Already on its way to a top edge worked out before the height changed, which it would
                // reach and stay at. Settling again aims it at the edge the height it has now wants.
                if (changedHeight) {
                    post {
                        if (isOpen && !hiding && behavior.state == BottomSheetBehavior.STATE_SETTLING) {
                            behavior.state = BottomSheetBehavior.STATE_EXPANDED
                        }
                    }
                }
            }

            else -> {
                // At rest and on screen, whichever of the behavior's states it is calling that: this
                // sheet has the one resting place, against the bottom of the screen
                val restingTop = height - sheet.height
                if (sheet.height in 1..height && sheet.top != restingTop) {
                    sheet.offsetTopAndBottom(restingTop - sheet.top)
                }
            }
        }
    }

    private companion object {
        const val ELEVATION_DP = 8f

        /** The height of the row the drag handle sits in, which is the bar in it and nothing else. */
        const val HANDLE_HEIGHT_DP = 28f

        /** The least of the room it has that a sheet takes up, however little there is in it. */
        const val MIN_HEIGHT_RATIO = 0.3f
        const val SCRIM_ALPHA = 0.4f

        /** How far below its own height a sheet is dragged before letting go dismisses it. */
        const val DISMISS_SLOP_DP = 32f
        const val RESIZE_DURATION = 200L
    }
}
