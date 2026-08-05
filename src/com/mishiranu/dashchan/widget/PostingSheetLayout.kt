package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.util.ResourceUtils

/**
 * The submission form as a modal bottom sheet over the page it was opened from, used instead of a form
 * filling the screen when [Preferences.isPostingSheet] is on. The point of it is the thread staying
 * where it was: the sheet drags between full and half height, so the posts being replied to can be read
 * behind it, and swiping it away or tapping above it dismisses the form — which is what [onDismiss]
 * reports, since the fragment leaving the screen is the activity's business, not this view's.
 *
 * Deliberately **not** an [ExpandedScreen.Layout]: that interface is for a view that carries the window
 * insets as padding, and this one wants them as margins instead, which is what [ExpandedScreen] does to
 * any other content view. Sitting below the toolbar and above the navigation bar is what keeps the
 * sheet's geometry honest — [BottomSheetBehavior] measures the sheet against its parent and slides it
 * down from there, so a sheet that was inset by `expandedOffset` inside a full-height parent would hang
 * exactly that far below the screen, taking the send button with it.
 */
@SuppressLint("ViewConstructor")
class PostingSheetLayout(
    context: Context,
    form: View,
    private val onDismiss: () -> Unit,
) : CoordinatorLayout(context) {
    /**
     * The row the drag handle sits in, centred. A form adds the controls it would have put in the
     * toolbar to the end of this instead — the toolbar above a sheet belongs to the page behind it.
     */
    val header: FrameLayout = FrameLayout(context)

    private val scrim = View(context)
    private val sheet = LinearLayout(context)
    private val behavior = BottomSheetBehavior<View>()

    private var opened = false

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
        header.addView(
            dragHandle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        sheet.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        sheet.addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            sheet,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).also {
                it.behavior = behavior
            },
        )

        // Half height is a state worth having on a form this tall -- it is what the thread behind is
        // read through -- so the sheet is measured against the parent rather than its contents. The
        // collapsed peek in between is skipped, which also makes a downward fling a dismissal instead
        // of a stop at the peek.
        behavior.isFitToContents = false
        behavior.halfExpandedRatio = HALF_EXPANDED_RATIO
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
                    // skips: the scrim tracks how much of the sheet is on screen, so the thread behind
                    // is dimmed by exactly as much as the form covering it -- clear again by the time
                    // the sheet has been swiped away, and only half shaded at half height, which is
                    // what makes the posts back there readable.
                    val shown = (height - bottomSheet.top).toFloat() / height
                    scrim.alpha = SCRIM_ALPHA * shown.coerceIn(0f, 1f)
                }
            },
        )
    }

    /** Swipes the sheet away, which dismisses the form once it is off screen. */
    fun hide() {
        if (isOpen) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        if (!opened) {
            // The behavior only animates between states it has laid the sheet out for, so the slide in
            // has to wait for the layout that put it off screen
            opened = true
            post { behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        }
    }

    private companion object {
        const val ELEVATION_DP = 8f
        const val HALF_EXPANDED_RATIO = 0.5f
        const val SCRIM_ALPHA = 0.4f
    }
}
