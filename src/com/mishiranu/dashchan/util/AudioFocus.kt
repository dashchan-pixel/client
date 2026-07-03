package com.mishiranu.dashchan.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AudioFocus(context: Context, callback: Callback) {
	enum class Change { LOSS, LOSS_TRANSIENT, GAIN }

	fun interface Callback {
		fun onChange(change: Change)
	}

	private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
	private val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
		if (acquired) {
			val change = when (focusChange) {
				AudioManager.AUDIOFOCUS_LOSS -> {
					release()
					Change.LOSS
				}
				AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Change.LOSS_TRANSIENT
				AudioManager.AUDIOFOCUS_GAIN -> Change.GAIN
				else -> null
			}
			if (change != null) {
				callback.onChange(change)
			}
		}
	}
	private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
			.setAudioAttributes(AudioAttributes.Builder()
					.setLegacyStreamType(AudioManager.STREAM_MUSIC).build())
			.setOnAudioFocusChangeListener(listener).build()

	private var acquired = false

	fun acquire(): Boolean {
		if (!acquired && audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			acquired = true
			return true
		}
		return acquired
	}

	fun release() {
		if (acquired) {
			acquired = false
			audioManager.abandonAudioFocusRequest(request)
		}
	}
}
