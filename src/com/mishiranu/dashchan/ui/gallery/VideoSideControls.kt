package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.graphics.IconShadowDrawable
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.ThemeEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Vertical column of overlay controls pinned to the bottom-right edge of a video surface,
 * reels/shorts-style: playback speed, mute, and (where supported) picture-in-picture. Shared by
 * the gallery player ([VideoUnit]) and the flow player ([FlowVideoView]); the host wires the
 * [Callback] and pushes state through the setters.
 */
class VideoSideControls(
    context: Context,
    private val callback: Callback,
) : LinearLayout(context) {
    interface Callback {
        fun onSpeedClick()

        fun onMuteClick()

        fun onPipClick()
    }

    private val density = ResourceUtils.obtainDensity(context)

    /** Blur radius of the halo that keeps the white glyphs legible over light video frames. */
    private val shadowRadius = SHADOW_RADIUS_DP * density

    private val speedButton: TextView
    private val muteButton: ImageButton
    private val pipButton: ImageButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = (48f * density).toInt()

        speedButton = TextView(context, null, android.R.attr.borderlessButtonStyle)
        speedButton.gravity = Gravity.CENTER
        speedButton.setTextColor(0xffffffff.toInt())
        speedButton.setShadowLayer(shadowRadius, 0f, 0f, SHADOW_COLOR)
        speedButton.typeface = ResourceUtils.TYPEFACE_MEDIUM
        ViewUtils.setTextSizeScaled(speedButton, 14)
        speedButton.setOnClickListener { callback.onSpeedClick() }
        addView(speedButton, LayoutParams(LayoutParams.WRAP_CONTENT, size))

        muteButton = iconButton(context, size) { callback.onMuteClick() }
        addView(muteButton, LayoutParams(size, size))

        pipButton = iconButton(context, size) { callback.onPipClick() }
        addView(pipButton, LayoutParams(size, size))

        setSpeed(1f)
        setMuteState(muted = false, audioPresent = true)
    }

    private fun iconButton(
        context: Context,
        size: Int,
        onClick: () -> Unit,
    ): ImageButton {
        val button = ImageButton(context, null, android.R.attr.borderlessButtonStyle)
        button.scaleType = ImageView.ScaleType.CENTER
        button.minimumWidth = size
        button.minimumHeight = size
        button.setOnClickListener { onClick() }
        return button
    }

    /** Set an icon wrapped in a [IconShadowDrawable] so it stays visible on light backgrounds. */
    private fun ImageButton.setShadowedIcon(resId: Int) {
        val icon: Drawable = requireNotNull(context.getDrawable(resId)).mutate()
        setImageDrawable(IconShadowDrawable(icon, shadowRadius, SHADOW_COLOR))
    }

    fun setSpeed(speed: Float) {
        speedButton.text = formatSpeed(speed)
    }

    /** Hide the speed button entirely when only 1x is enabled (nothing to choose between). */
    fun setSpeedButtonVisible(visible: Boolean) {
        speedButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Reflect the mute state. With no audio track the button becomes a dimmed, non-interactive
     * "no audio" indicator; otherwise it toggles between the volume-on and volume-off icons.
     */
    fun setMuteState(
        muted: Boolean,
        audioPresent: Boolean,
    ) {
        if (!audioPresent) {
            muteButton.setShadowedIcon(R.drawable.ic_volume_off)
            muteButton.imageAlpha = 0x66
            muteButton.isEnabled = false
            muteButton.contentDescription = context.getString(R.string.mute)
        } else {
            muteButton.setShadowedIcon(
                if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
            )
            muteButton.imageAlpha = 0xff
            muteButton.isEnabled = true
            muteButton.contentDescription =
                context.getString(if (muted) R.string.unmute else R.string.mute)
        }
    }

    fun setPipButtonVisible(visible: Boolean) {
        pipButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    init {
        pipButton.setShadowedIcon(R.drawable.ic_picture_in_picture)
        pipButton.contentDescription = context.getString(R.string.picture_in_picture)
    }

    /**
     * Show the playback-speed chooser as a small single-choice dropdown anchored to the speed button
     * (it drops up from the player's bottom edge). [current] is pre-selected; [onSelect] fires with
     * the picked speed. A popup window attaches to the anchor's window, so it works from the
     * gallery's token-less base context where an [android.app.AlertDialog] would throw a
     * BadTokenException.
     *
     * A [ListPopupWindow] rather than a framework `PopupMenu`, for the same reason as
     * [com.mishiranu.dashchan.widget.CommandsPopup]: a PopupMenu draws the square background of its
     * own popup style, which no amount of theming rounds off. Here the background is ours
     * ([ThemeEngine.roundedPopupBackground]). The rows are built on a
     * [menu context][GalleryInstance.menuContext] rather than this view's own, whose theme is the
     * player's — white text, which the app-themed background behind it need not be dark enough for.
     */
    fun showSpeedPopup(
        current: Float,
        speeds: List<Float>,
        onSelect: (Float) -> Unit,
    ) {
        val menuContext = GalleryInstance.menuContext(context)
        val currentIndex = speeds.indexOfFirst { abs(it - current) < 0.001f }
        val density = ResourceUtils.obtainDensity(menuContext)
        val paddingHorizontal = (16f * density).toInt()
        val paddingVertical = (12f * density).toInt()
        val adapter =
            object : ArrayAdapter<String>(
                menuContext,
                android.R.layout.simple_list_item_single_choice,
                android.R.id.text1,
                speeds.map { formatSpeed(it) },
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    view.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                    // The row is a CheckedTextView, so the radio mark rides along without the list
                    // needing a choice mode of its own.
                    (view as? Checkable)?.isChecked = position == currentIndex
                    return view
                }
            }
        val popup = ListPopupWindow(menuContext)
        popup.anchorView = speedButton
        popup.isModal = true
        popup.setAdapter(adapter)
        // Tap feedback in a ListView comes from the list selector, not item backgrounds.
        popup.setListSelector(
            menuContext.getDrawable(
                ResourceUtils.getResourceId(menuContext, android.R.attr.selectableItemBackground, 0),
            ),
        )
        popup.width = measureSpeedWidth(adapter, menuContext, density)
        popup.setBackgroundDrawable(ThemeEngine.roundedPopupBackground(menuContext))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelect(speeds[position])
        }
        popup.show()
    }

    /** Widest row, so the dropdown hugs its labels ("0.33x") instead of the anchor's width. */
    private fun measureSpeedWidth(
        adapter: ArrayAdapter<String>,
        menuContext: Context,
        density: Float,
    ): Int {
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val fakeParent = FrameLayout(menuContext)
        var contentWidth = 0
        var itemView: View? = null
        for (i in 0 until adapter.count) {
            itemView = adapter.getView(i, itemView, fakeParent)
            itemView.measure(measureSpec, measureSpec)
            contentWidth = max(contentWidth, itemView.measuredWidth)
        }
        return contentWidth.coerceAtLeast((112f * density).toInt())
    }

    companion object {
        /** Soft dark halo behind the white glyphs, so they read on light video frames. */
        private const val SHADOW_RADIUS_DP = 3.5f
        private const val SHADOW_COLOR = 0xcc000000.toInt()

        fun formatSpeed(speed: Float): String {
            val text =
                if (speed == speed.toLong().toFloat()) {
                    speed.toLong().toString()
                } else {
                    // Trim to one decimal place (0.5, 1.5) without a trailing zero.
                    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
                }
            return text + "x"
        }
    }
}
