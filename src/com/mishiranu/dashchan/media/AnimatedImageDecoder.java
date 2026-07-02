package com.mishiranu.dashchan.media;

import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.io.IOException;

/**
 * Decodes animated GIF, PNG and WebP files via the platform {@link ImageDecoder}.
 * Throws {@link IOException} if the file is not an animated image, letting the
 * caller fall back to regular bitmap decoding.
 */
public class AnimatedImageDecoder {
	private final AnimatedImageDrawable drawable;

	public AnimatedImageDecoder(File file) throws IOException {
		Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
		if (!(drawable instanceof AnimatedImageDrawable)) {
			throw new IOException("Not an animated image");
		}
		this.drawable = (AnimatedImageDrawable) drawable;
		this.drawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
		this.drawable.start();
	}

	public Drawable getDrawable() {
		return drawable;
	}

	public void recycle() {
		drawable.stop();
	}
}
