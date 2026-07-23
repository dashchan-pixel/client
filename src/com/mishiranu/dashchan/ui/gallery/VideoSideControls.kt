package com.mishiranu.dashchan.ui.gallery

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import java.util.Locale
import kotlin.math.abs

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

    private val speedButton: TextView
    private val muteButton: ImageButton
    private val pipButton: ImageButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val density = ResourceUtils.obtainDensity(context)
        val size = (48f * density).toInt()

        speedButton = TextView(context, null, android.R.attr.borderlessButtonStyle)
        speedButton.gravity = Gravity.CENTER
        speedButton.setTextColor(0xffffffff.toInt())
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
            muteButton.setImageResource(R.drawable.ic_volume_off)
            muteButton.imageAlpha = 0x66
            muteButton.isEnabled = false
            muteButton.contentDescription = context.getString(R.string.mute)
        } else {
            muteButton.setImageResource(
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
        pipButton.setImageResource(R.drawable.ic_picture_in_picture)
        pipButton.contentDescription = context.getString(R.string.picture_in_picture)
    }

    /**
     * Show the playback-speed chooser as a small single-choice popup anchored to the speed button
     * (it drops up from the player's bottom edge). [current] is pre-selected; [onSelect] fires with
     * the picked speed. A [PopupMenu] attaches to the anchor's window, so it works from the gallery's
     * token-less base context where an [android.app.AlertDialog] would throw a BadTokenException.
     */
    fun showSpeedPopup(
        current: Float,
        speeds: List<Float>,
        onSelect: (Float) -> Unit,
    ) {
        val popup = PopupMenu(context, speedButton, Gravity.END)
        speeds.forEachIndexed { index, speed ->
            popup.menu.add(SPEED_GROUP, index, index, formatSpeed(speed))
        }
        // Exclusive checkable group = radio behaviour, with the current speed marked.
        popup.menu.setGroupCheckable(SPEED_GROUP, true, true)
        val currentIndex = speeds.indexOfFirst { abs(it - current) < 0.001f }
        if (currentIndex >= 0) {
            popup.menu.getItem(currentIndex).isChecked = true
        }
        popup.setOnMenuItemClickListener { item ->
            onSelect(speeds[item.itemId])
            true
        }
        popup.show()
    }

    companion object {
        private const val SPEED_GROUP = 1

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
