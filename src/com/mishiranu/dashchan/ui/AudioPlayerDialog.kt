package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.service.AudioPlayerService
import com.mishiranu.dashchan.ui.gallery.VideoSideControls
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.AttachmentView
import com.mishiranu.dashchan.widget.DropdownPopup
import com.mishiranu.dashchan.widget.ThemeEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class AudioPlayerDialog : DialogFragment() {
    private var textView: TextView? = null
    private var artView: AttachmentView? = null
    private var seekBar: SeekBar? = null
    private var button: ImageButton? = null
    private var speedButton: TextView? = null
    private var minimizeButton: TextView? = null
    private var timeTextView: TextView? = null

    private var artworkBitmap: Bitmap? = null

    private var tracking = false
    private var shouldCancel = false

    private val callback =
        object : AudioPlayerService.Callback {
            override fun onTogglePlayback() {
                updatePlayState()
            }

            override fun onCancel() {
                handleCancel()
            }

            override fun onArtworkChanged() {
                updateArtworkState()
            }
        }

    private var audioPlayerBinder: AudioPlayerService.Binder? = null
    private val audioPlayerConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                componentName: ComponentName,
                binder: IBinder,
            ) {
                val audioPlayerBinder = binder as AudioPlayerService.Binder
                this@AudioPlayerDialog.audioPlayerBinder = audioPlayerBinder
                audioPlayerBinder.registerCallback(callback)
                if (audioPlayerBinder.isRunning) {
                    val seekBar = this@AudioPlayerDialog.seekBar ?: return
                    val textView = this@AudioPlayerDialog.textView ?: return
                    seekBar.removeCallbacks(seekBarUpdate)
                    textView.text = audioPlayerBinder.getFileName()
                    seekBar.max = audioPlayerBinder.duration.coerceAtLeast(0)
                    updatePlayState()
                    updateSpeedState()
                    updateArtworkState()
                    seekBarUpdate.run()
                } else {
                    handleCancel()
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                audioPlayerBinder?.let {
                    it.unregisterCallback(callback)
                    audioPlayerBinder = null
                }
            }
        }

    private val seekBarUpdate =
        object : Runnable {
            override fun run() {
                val audioPlayerBinder = audioPlayerBinder ?: return
                val seekBar = this@AudioPlayerDialog.seekBar ?: return
                // The player prepares asynchronously, so the duration is usually still unknown
                // when the dialog binds: pick it up as soon as the file has been parsed
                val duration = audioPlayerBinder.duration
                if (duration > 0 && seekBar.max != duration) {
                    seekBar.max = duration
                }
                if (!tracking) {
                    seekBar.progress = audioPlayerBinder.position
                }
                updateTimeState()
                seekBar.postDelayed(this, 500)
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = ResourceUtils.obtainDensity(this)
        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding_view)
        linearLayout.setPadding(padding, padding, padding, (8f * density).toInt())
        // The header carries the file name, with the embedded cover art (when the track has any)
        // shown as a square thumbnail to its left, drawn like the attachment previews are: centre
        // cropped into a rounded square. The art view starts gone and is revealed once decoded.
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        linearLayout.addView(
            header,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val artView = AttachmentView(context, null)
        this.artView = artView
        artView.setCropEnabled(true)
        artView.applyRoundedCorners(ResourceUtils.getDialogBackground(context))
        artView.visibility = View.GONE
        val artSize = (56f * density).toInt()
        val artParams = LinearLayout.LayoutParams(artSize, artSize)
        // The gap to the title rides on the art view, so it collapses along with it when gone.
        artParams.marginEnd = (16f * density).toInt()
        header.addView(artView, artParams)
        val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
        this.textView = textView
        header.addView(textView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.isSingleLine = true
        val horizontal = LinearLayout(context)
        horizontal.orientation = LinearLayout.HORIZONTAL
        horizontal.gravity = Gravity.CENTER_VERTICAL
        horizontal.setPadding(0, (16f * density).toInt(), 0, 0)
        linearLayout.addView(
            horizontal,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        tracking = false
        val seekBar = SeekBar(context)
        this.seekBar = seekBar
        ThemeEngine.applyStyle(seekBar)
        val seekBarParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        // A small gap to the play button; the rest of the old right padding becomes the thumb inset.
        seekBarParams.marginEnd = (8f * density).toInt()
        horizontal.addView(seekBar, seekBarParams)
        // Inset the track by half the thumb on each side, so the thumb sits flush with the bar's
        // edges at 0 and max rather than spilling past them (and clipping) at those extremes.
        val thumbInset = (seekBar.thumb?.intrinsicWidth ?: 0) / 2
        seekBar.setPadding(thumbInset, 0, thumbInset, 0)
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (audioPlayerBinder != null && fromUser) {
                        audioPlayerBinder?.seekTo(progress)
                    }
                    updateTimeState()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    tracking = false
                }
            },
        )
        val button = ImageButton(context)
        this.button = button
        horizontal.addView(button, (48f * density).toInt(), (48f * density).toInt())
        button.scaleType = ImageView.ScaleType.CENTER
        // A filled accent play / pause button, its corners rounded to the app's UI corner-radius
        // setting like the other surfaces, with the glyph in whichever of black / white stays legible
        // on the accent and a ripple masked to the same rounded shape for the press feedback.
        val accent = ThemeEngine.getTheme(context).accent
        val radius = Preferences.uiCornerRadius * density
        val fill = ShapeDrawable(RoundRectShape(FloatArray(8) { radius }, null, null))
        fill.paint.color = accent
        val mask = ShapeDrawable(RoundRectShape(FloatArray(8) { radius }, null, null))
        mask.paint.color = -0x1
        val highlight =
            ResourceUtils.getColorStateList(context, android.R.attr.colorControlHighlight)
                ?: ColorStateList.valueOf(0)
        button.background = RippleDrawable(highlight, fill, mask)
        button.imageTintList =
            ColorStateList.valueOf(if (GraphicsUtils.isLight(accent)) Color.BLACK else Color.WHITE)
        // The bottom line is a single row of three same-font labels: the elapsed / total timestamps
        // pinned to the left edge, the speed dropdown dead-centred (so the row stays symmetric), and
        // the minimize button pinned to the right edge, taking over the dialog's old positive button.
        // A frame, not a weighted row, keeps the speed centred in the dialog regardless of how wide
        // the timestamps or the minimize label happen to be, while all three stay compact.
        val bottom = FrameLayout(context)
        linearLayout.addView(
            bottom,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val barHeight = (48f * density).toInt()
        val timeTextView = bottomBarLabel(context)
        this.timeTextView = timeTextView
        bottom.addView(
            timeTextView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                barHeight,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
        // The speed button offers the same speeds the video player does, in a dropdown anchored to
        // it, and is hidden when the user has left only 1x enabled, since then there is nothing to
        // choose between.
        val speedButton = bottomBarButton(context)
        this.speedButton = speedButton
        speedButton.contentDescription = getString(R.string.playback_speed)
        bottom.addView(
            speedButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, barHeight, Gravity.CENTER),
        )
        speedButton.visibility = if (Preferences.enabledVideoSpeeds.size > 1) View.VISIBLE else View.GONE
        speedButton.setOnClickListener { showSpeedPopup() }
        // Minimize just closes the dialog and leaves the track playing in the notification; only a
        // plain dismissal (back or a tap outside, handled in onCancel) stops playback.
        val minimizeButton = bottomBarButton(context)
        this.minimizeButton = minimizeButton
        minimizeButton.setText(R.string.minimize)
        // Caps, matching the dialog's old positive button this stands in for.
        minimizeButton.isAllCaps = true
        bottom.addView(
            minimizeButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                barHeight,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        minimizeButton.setOnClickListener { dismiss() }
        updatePlayState()
        updateSpeedState()
        updateTimeState()
        button.setOnClickListener {
            audioPlayerBinder?.togglePlayback()
        }
        val dialog =
            AlertDialog
                .Builder(context)
                .setView(linearLayout)
                .create()
        dialog.setVolumeControlStream(AudioManager.STREAM_MUSIC)
        requireContext().bindService(
            Intent(requireContext(), AudioPlayerService::class.java),
            audioPlayerConnection,
            Context.BIND_AUTO_CREATE,
        )
        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (audioPlayerBinder != null) {
            audioPlayerBinder?.unregisterCallback(callback)
            audioPlayerBinder = null
            requireContext().unbindService(audioPlayerConnection)
        }
        seekBar?.removeCallbacks(seekBarUpdate)
        textView = null
        artView = null
        seekBar = null
        button = null
        speedButton = null
        minimizeButton = null
        timeTextView = null
        artworkBitmap = null
    }

    override fun onResume() {
        super.onResume()
        if (shouldCancel) {
            shouldCancel = false
            handleCancel()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // A back press or a tap outside stops the track; Minimize keeps it playing (it dismisses,
        // which does not cancel). A configuration change or a programmatic dismiss never lands here.
        audioPlayerBinder?.stop()
    }

    private fun handleCancel() {
        if (isResumed) {
            dismiss()
        } else {
            // Dismissing a stopped fragment throws, so defer it to onResume
            shouldCancel = true
        }
    }

    /**
     * The shared look of the three bottom-line labels: one font, so they read as a set, and the
     * same horizontal inset, so the left and right ends sit symmetrically off the dialog edges (and
     * the elapsed time lines up under the start of the seek bar's track).
     */
    private fun bottomBarLabel(context: Context): TextView {
        val density = ResourceUtils.obtainDensity(context)
        val view = TextView(context, null, android.R.attr.textAppearanceSmall)
        view.typeface = ResourceUtils.TYPEFACE_MEDIUM
        ViewUtils.setTextSizeScaled(view, 14)
        // Centre the text in the row's height, so the timestamps sit on the same line as the
        // buttons rather than riding along the top of their cell.
        view.gravity = Gravity.CENTER_VERTICAL
        val padding = (12f * density).toInt()
        view.setPadding(padding, 0, padding, 0)
        return view
    }

    /** A [bottomBarLabel] made tappable: centred text and a borderless ripple. */
    private fun bottomBarButton(context: Context): TextView {
        val view = bottomBarLabel(context)
        view.gravity = Gravity.CENTER
        view.setBackgroundResource(
            ResourceUtils.getResourceId(context, android.R.attr.selectableItemBackgroundBorderless, 0),
        )
        return view
    }

    private fun updateSpeedState() {
        val speedButton = this.speedButton ?: return
        speedButton.text = VideoSideControls.formatSpeed(audioPlayerBinder?.speed ?: 1f)
    }

    private fun updateArtworkState() {
        val artView = this.artView ?: return
        if (artworkBitmap == null) {
            artworkBitmap = audioPlayerBinder?.artworkData?.let { decodeArtwork(it) }
        }
        val bitmap = artworkBitmap
        if (bitmap != null) {
            artView.resetImage(ARTWORK_KEY)
            artView.handleLoadedImage(ARTWORK_KEY, bitmap, false, true)
            artView.visibility = View.VISIBLE
        } else {
            artView.visibility = View.GONE
        }
    }

    /**
     * Decode the cover art, downsampled so a large embedded image (some are as big as a photo)
     * does not become a many-megabyte bitmap for a thumbnail. Returns null on unreadable data.
     */
    private fun decodeArtwork(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val largest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest > 0 && largest / sample > ARTWORK_MAX_SIZE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun updateTimeState() {
        val timeTextView = this.timeTextView ?: return
        val seekBar = this.seekBar ?: return
        // The seek bar mirrors the player position when idle and the scrubbed value while tracking,
        // so it is the right source for the elapsed time either way. The total is shown once the
        // file has been parsed and its duration is known.
        val position = seekBar.progress.coerceAtLeast(0)
        val duration = audioPlayerBinder?.duration ?: 0
        timeTextView.text =
            if (duration > 0) {
                "${formatTime(position)} / ${formatTime(duration)}"
            } else {
                formatTime(position)
            }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun showSpeedPopup() {
        val speedButton = this.speedButton ?: return
        val audioPlayerBinder = this.audioPlayerBinder ?: return
        val speeds = Preferences.enabledVideoSpeeds
        DropdownPopup.show(
            speedButton,
            speedButton.context,
            speeds.map { VideoSideControls.formatSpeed(it) },
            speeds.indexOfFirst { abs(it - audioPlayerBinder.speed) < 0.001f },
            // The speed button already shows the current speed, so a radio mark would be redundant.
            showRadio = false,
        ) { position ->
            audioPlayerBinder.speed = speeds[position]
            updateSpeedState()
        }
    }

    private fun updatePlayState() {
        val button = this.button ?: return
        val playing = audioPlayerBinder?.isPlaying == true
        button.setImageResource(
            ResourceUtils.getResourceId(
                requireContext(),
                if (playing) R.attr.iconButtonPause else R.attr.iconButtonPlay,
                0,
            ),
        )
    }

    private companion object {
        /** Fixed key for the single cover-art image the [AttachmentView] ever holds. */
        private const val ARTWORK_KEY = "artwork"

        /** Longest edge the decoded cover art is downsampled towards; plenty for a thumbnail. */
        private const val ARTWORK_MAX_SIZE = 512
    }
}
