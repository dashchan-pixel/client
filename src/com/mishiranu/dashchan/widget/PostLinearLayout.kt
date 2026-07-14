package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

class PostLinearLayout : LinearLayout {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun hasOverlappingRendering(): Boolean {
        // Makes setAlpha faster, see https://plus.google.com/+RomanNurik/posts/NSgQvbfXGQN
        // Thumbnails will become strange with alpha because background alpha and image alpha are separate now
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val focusedView = findFocus()
        if (focusedView is CommentTextView && focusedView.isSelectionMode()) {
            // Don't draw selection background
            return false
        }
        return super.onTouchEvent(event)
    }

    private var secondaryBackground: Drawable? = null

    fun setSecondaryBackgroundColor(color: Int) {
        val secondaryBackground = secondaryBackground
        if (secondaryBackground is ColorDrawable) {
            (secondaryBackground.mutate() as ColorDrawable).color = color
        } else {
            setSecondaryBackground(ColorDrawable(color))
        }
    }

    fun setSecondaryBackground(drawable: Drawable?) {
        val secondaryBackground = secondaryBackground
        if (secondaryBackground != drawable) {
            if (secondaryBackground != null) {
                secondaryBackground.callback = null
                unscheduleDrawable(secondaryBackground)
            }
            this.secondaryBackground = drawable
            drawable?.callback = this
            invalidate()
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = super.verifyDrawable(who) || who === secondaryBackground

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val secondaryBackground = secondaryBackground
        if (secondaryBackground != null) {
            secondaryBackground.setBounds(0, 0, width, height)
            secondaryBackground.draw(canvas)
        }
    }
}
