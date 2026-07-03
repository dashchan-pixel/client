package com.mishiranu.dashchan.media

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.IOException

/**
 * Decodes animated GIF, PNG and WebP files via the platform [ImageDecoder].
 * Throws [IOException] if the file is not an animated image, letting the
 * caller fall back to regular bitmap decoding.
 */
class AnimatedImageDecoder @Throws(IOException::class) constructor(file: File) {
	private val drawable: AnimatedImageDrawable

	init {
		val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
		if (drawable !is AnimatedImageDrawable) {
			throw IOException("Not an animated image")
		}
		this.drawable = drawable
		this.drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
		this.drawable.start()
	}

	fun getDrawable(): Drawable = drawable

	fun recycle() {
		drawable.stop()
	}
}
