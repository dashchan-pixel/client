package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.service.AudioPlayerService
import com.mishiranu.dashchan.ui.gallery.VideoSideControls
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.DropdownPopup
import com.mishiranu.dashchan.widget.ThemeEngine
import kotlin.math.abs

class AudioPlayerDialog : DialogFragment() {
    private var textView: TextView? = null
    private var seekBar: SeekBar? = null
    private var button: ImageButton? = null
    private var speedButton: TextView? = null

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
        val textView = TextView(context, null, android.R.attr.textAppearanceListItem)
        this.textView = textView
        linearLayout.addView(textView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        textView.setPadding(0, 0, 0, 0)
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
        // The same speeds the video player offers, in a dropdown anchored to a text button. Hidden
        // when the user has left only 1x enabled, since then there is nothing to choose between.
        val speedButton = TextView(context, null, android.R.attr.borderlessButtonStyle)
        this.speedButton = speedButton
        speedButton.gravity = Gravity.CENTER
        speedButton.typeface = ResourceUtils.TYPEFACE_MEDIUM
        speedButton.minWidth = (48f * density).toInt()
        speedButton.contentDescription = getString(R.string.playback_speed)
        ViewUtils.setTextSizeScaled(speedButton, 14)
        horizontal.addView(speedButton, LinearLayout.LayoutParams.WRAP_CONTENT, (48f * density).toInt())
        speedButton.visibility = if (Preferences.enabledVideoSpeeds.size > 1) View.VISIBLE else View.GONE
        speedButton.setOnClickListener { showSpeedPopup() }
        val seekBar = SeekBar(context)
        this.seekBar = seekBar
        ThemeEngine.applyStyle(seekBar)
        horizontal.addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        seekBar.setPadding((8f * density).toInt(), 0, (16f * density).toInt(), 0)
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
        button.imageTintList =
            ResourceUtils.getColorStateList(
                button.context,
                android.R.attr.textColorPrimary,
            )
        button.setBackgroundResource(
            ResourceUtils.getResourceId(
                context,
                android.R.attr.listChoiceBackgroundIndicator,
                0,
            ),
        )
        updatePlayState()
        updateSpeedState()
        button.setOnClickListener {
            audioPlayerBinder?.togglePlayback()
        }
        val dialog =
            AlertDialog
                .Builder(context)
                .setView(linearLayout)
                .setPositiveButton(R.string.stop) { _, _ ->
                    audioPlayerBinder?.stop()
                }.create()
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
        seekBar = null
        button = null
        speedButton = null
    }

    override fun onResume() {
        super.onResume()
        if (shouldCancel) {
            shouldCancel = false
            handleCancel()
        }
    }

    private fun handleCancel() {
        if (isResumed) {
            dismiss()
        } else {
            // Dismissing a stopped fragment throws, so defer it to onResume
            shouldCancel = true
        }
    }

    private fun updateSpeedState() {
        val speedButton = this.speedButton ?: return
        speedButton.text = VideoSideControls.formatSpeed(audioPlayerBinder?.speed ?: 1f)
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
}
