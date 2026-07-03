package com.mishiranu.dashchan.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.Pair
import android.view.Gravity
import androidx.core.graphics.ColorUtils
import com.mishiranu.dashchan.content.model.FileHolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

object GraphicsUtils {
	private val RANDOM = Random(System.currentTimeMillis())

	private const val CONTRAST_GAIN = 2.5f

	private val CONTRAST_FILTER = ColorMatrixColorFilter(floatArrayOf(
			CONTRAST_GAIN, 0f, 0f, 0f, (1f - CONTRAST_GAIN) * 255f,
			0f, CONTRAST_GAIN, 0f, 0f, (1f - CONTRAST_GAIN) * 255f,
			0f, 0f, CONTRAST_GAIN, 0f, (1f - CONTRAST_GAIN) * 255f,
			0f, 0f, 0f, 1f, 0f))

	private val BLACK_CHROMA_KEY_FILTER = ColorMatrixColorFilter(floatArrayOf(
			0f, 0f, 0f, 0f, 0f,
			0f, 0f, 0f, 0f, 0f,
			0f, 0f, 0f, 0f, 0f,
			-1f / 3f, -1f / 3f, -1f / 3f, 0f, 255f))

	@JvmField
	val INVERT_FILTER = ColorMatrixColorFilter(floatArrayOf(
			-1f, 0f, 0f, 0f, 255f,
			0f, -1f, 0f, 0f, 255f,
			0f, 0f, -1f, 0f, 255f,
			0f, 0f, 0f, 1f, 0f))

	@JvmField
	val SKIA_SUPPORTS_GAMMA_CORRECTION: Boolean

	init {
		// PNG image with gAMA chunk filled with 0xff7f7f7f
		val imageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAABGdBTUEAAAAQ" +
				"lpJwKQAAAApJREFUCB1jqAcAAIEAgFTzwt4AAAAASUVORK5CYII="
		val imageBytes = Base64.decode(imageBase64, 0)
		val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
		val pixel = bitmap.getPixel(0, 0)
		SKIA_SUPPORTS_GAMMA_CORRECTION = pixel != 0xff7f7f7f.toInt()
	}

	@JvmStatic
	fun modifyColorGain(color: Int, gain: Float): Int {
		val r = Color.red(color)
		val g = Color.green(color)
		val b = Color.blue(color)
		return Color.argb(Color.alpha(color), minOf((r * gain).toInt(), 0xff),
				minOf((g * gain).toInt(), 0xff), minOf((b * gain).toInt(), 0xff))
	}

	@JvmStatic
	fun isLight(color: Int): Boolean {
		return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3 >= 0x80
	}

	@JvmStatic
	fun mixColors(background: Int, foreground: Int): Int {
		val ba = Color.alpha(background)
		val fa = Color.alpha(foreground)
		val a = fa + ba * (0xff - fa) / 0xff
		val r = (Color.red(foreground) * fa + Color.red(background) * ba * (0xff - fa) / 0xff) / a
		val g = (Color.green(foreground) * fa + Color.green(background) * ba * (0xff - fa) / 0xff) / a
		val b = (Color.blue(foreground) * fa + Color.blue(background) * ba * (0xff - fa) / 0xff) / a
		return Color.argb(minOf(a, 0xff), minOf(r, 0xff), minOf(g, 0xff), minOf(b, 0xff))
	}

	@JvmStatic
	fun applyAlpha(color: Int, alpha: Float): Int {
		return ColorUtils.blendARGB(0x00ffffff and color, color, alpha)
	}

	@SuppressLint("RtlHardcoded")
	@JvmStatic
	fun getDrawableColor(context: Context, drawable: Drawable, gravity: Int): Int {
		val density = ResourceUtils.obtainDensity(context)
		var size = maxOf(drawable.minimumWidth, drawable.minimumHeight)
		if (size == 0) {
			size = (64f * density).toInt()
		}
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		try {
			drawable.setBounds(0, 0, size, size)
			drawable.draw(Canvas(bitmap))
			val x = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
				Gravity.LEFT -> 0
				Gravity.RIGHT -> size - 1
				else -> size / 2
			}
			val y = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
				Gravity.TOP -> 0
				Gravity.BOTTOM -> size - 1
				else -> size / 2
			}
			return bitmap.getPixel(x, y)
		} finally {
			bitmap.recycle()
		}
	}

	@JvmStatic
	fun getCornerRadius(drawable: GradientDrawable): Float = drawable.cornerRadius

	@JvmStatic
	fun reduceThumbnailSize(resources: Resources, bitmap: Bitmap): Bitmap {
		val newSize = (72f * ResourceUtils.obtainDensity(resources)).toInt()
		return reduceBitmapSize(bitmap, newSize, true)
	}

	@JvmStatic
	fun reduceBitmapSize(bitmap: Bitmap, newSize: Int, recycleOld: Boolean): Bitmap {
		val width = bitmap.width
		val height = bitmap.height
		val oldSize = minOf(width, height)
		val scale = newSize / oldSize.toFloat()
		if (scale >= 1.0) {
			return bitmap
		}
		val resizedBitmap = Bitmap.createScaledBitmap(bitmap,
				(width * scale).toInt(), (height * scale).toInt(), true)
		if (recycleOld && resizedBitmap != bitmap) {
			bitmap.recycle()
		}
		return resizedBitmap
	}

	class Reencoding(format: String?, quality: Int, reduce: Int) {
		@JvmField val format: String = if (FORMAT_JPEG == format || FORMAT_JPEG_ALTNAME == format ||
				FORMAT_PNG == format) format else FORMAT_JPEG
		@JvmField val quality: Int = quality.coerceIn(1, 100)
		@JvmField val reduce: Int = reduce.coerceIn(1, 8)

		companion object {
			const val FORMAT_JPEG = "jpeg"
			const val FORMAT_JPEG_ALTNAME = "jpg"
			const val FORMAT_PNG = "png"

			@JvmStatic
			fun allowQuality(format: String?): Boolean = FORMAT_JPEG == format
		}
	}

	@JvmStatic
	fun canRemoveMetadata(fileHolder: FileHolder): Boolean {
		return fileHolder.imageType == FileHolder.ImageType.IMAGE_JPEG ||
				fileHolder.imageType == FileHolder.ImageType.IMAGE_PNG
	}

	class SkipRange internal constructor(@JvmField val start: Int, @JvmField val count: Int)

	class TransformationData internal constructor(
			@JvmField val skipRanges: ArrayList<SkipRange>?,
			@JvmField val decodedBytes: ByteArray?,
			@JvmField val newFileName: String?,
			@JvmField val newWidth: Int,
			@JvmField val newHeight: Int)

	@JvmStatic
	fun transformImageForPosting(fileHolder: FileHolder, fileName: String,
			removeMetadata: Boolean, reencoding: Reencoding?): TransformationData? {
		var skipRanges: ArrayList<SkipRange>? = null
		var decodedBytes: ByteArray? = null
		var newFileName: String? = null
		var newWidth = -1
		var newHeight = -1
		if (reencoding != null && fileHolder.isImage) {
			var bitmap: Bitmap? = try {
				fileHolder.readImageBitmap(Int.MAX_VALUE, true, true)
			} catch (e: Exception) {
				null
			} catch (e: OutOfMemoryError) {
				null
			}
			if (bitmap != null) {
				try {
					if (reencoding.reduce > 1) {
						val scaledBitmap = Bitmap.createScaledBitmap(bitmap,
								maxOf(bitmap.width / reencoding.reduce, 1),
								maxOf(bitmap.height / reencoding.reduce, 1), true)
						if (scaledBitmap != bitmap) {
							bitmap.recycle()
							bitmap = scaledBitmap
						}
						newWidth = bitmap.width
						newHeight = bitmap.height
					}
					val png = Reencoding.FORMAT_PNG == reencoding.format
					val jpegToJpg = Reencoding.FORMAT_JPEG_ALTNAME == reencoding.format
					val output = ByteArrayOutputStream()
					bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
							reencoding.quality, output)
					decodedBytes = output.toByteArray()
					val index = fileName.lastIndexOf('.')
					newFileName = (if (index >= 0) fileName.substring(0, index) else fileName) +
							if (png) ".png" else if (jpegToJpg) ".jpg" else ".jpeg"
				} finally {
					bitmap.recycle()
				}
			}
		} else if (removeMetadata) {
			if (fileHolder.imageType == FileHolder.ImageType.IMAGE_JPEG) {
				try {
					BufferedInputStream(fileHolder.openInputStream(), 16 * 1024).use { input ->
						var position = 0
						val buffer = ByteArray(2)
						while (true) {
							var oneByte = input.read()
							position++
							if (oneByte == 0xff) {
								oneByte = input.read()
								position++
								if (oneByte and 0xe0 == 0xe0 || oneByte == 0xfe) {
									// Application data (0xe0 for JFIF, 0xe1 for EXIF) or comment (0xfe)
									if (!IOUtils.readExactlyCheck(input, buffer, 0, 2)) {
										break
									}
									val size = IOUtils.bytesToInt(false, 0, 2, *buffer)
									if (!IOUtils.skipExactlyCheck(input, size - 2)) {
										break
									}
									if (skipRanges == null) {
										skipRanges = ArrayList()
									}
									skipRanges.add(SkipRange(position - 2, size + 2))
									position += size
								}
							}
							if (oneByte == -1) {
								break
							}
						}
					}
				} catch (e: IOException) {
					e.printStackTrace()
				}
			} else if (fileHolder.imageType == FileHolder.ImageType.IMAGE_PNG) {
				try {
					fileHolder.openInputStream().use { input ->
						if (IOUtils.skipExactlyCheck(input, 8)) {
							var position = 8
							val buffer = ByteArray(8)
							while (true) {
								if (!IOUtils.readExactlyCheck(input, buffer, 0, 8)) {
									break
								}
								val size = IOUtils.bytesToInt(false, 0, 4, *buffer)
								val name = String(buffer, 4, 4)
								if (!IOUtils.skipExactlyCheck(input, size + 4)) {
									break
								}
								if (isUselessPngChunk(name)) {
									if (skipRanges == null) {
										skipRanges = ArrayList()
									}
									skipRanges.add(SkipRange(position, size + 12))
								}
								position += size + 12
								if ("IEND" == name) {
									val fileSize = fileHolder.size
									if (fileSize > position) {
										if (skipRanges == null) {
											skipRanges = ArrayList()
										}
										skipRanges.add(SkipRange(position, fileSize - position))
									}
									break
								}
							}
						}
					}
				} catch (e: IOException) {
					e.printStackTrace()
				}
			}
		}
		return if (skipRanges != null || decodedBytes != null || newFileName != null) {
			TransformationData(skipRanges, decodedBytes, newFileName, newWidth, newHeight)
		} else {
			null
		}
	}

	@JvmStatic
	fun isUselessPngChunk(name: String): Boolean {
		return "iTXt" == name || "tEXt" == name || "zTXt" == name || "tIME" == name || "eXIf" == name
	}

	@JvmStatic
	fun isBlackAndWhiteCaptchaImage(image: Bitmap?): Boolean {
		if (image != null) {
			val width = image.width
			val height = image.height
			val pixels = IntArray(width)
			for (i in 0 until height) {
				image.getPixels(pixels, 0, width, 0, i, width, 1)
				for (j in 0 until width) {
					val color = pixels[j]
					val a = Color.alpha(color)
					val r = Color.red(color)
					val g = Color.green(color)
					val b = Color.blue(color)
					if (a >= 0x20) {
						val max = maxOf(r, g, b)
						val min = minOf(r, g, b)
						if (max - min >= 0x1a) {
							return false // 10%
						}
					}
				}
			}
			return true
		}
		return false
	}

	@JvmStatic
	@JvmOverloads
	fun handleBlackAndWhiteCaptchaImage(image: Bitmap?, overlay: Bitmap? = null,
			overlayX: Int = 0, overlayY: Int = 0): Pair<Bitmap?, Boolean> {
		if (image != null) {
			val width = image.width
			val height = image.height
			val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
			var canvas = Canvas(mask)
			canvas.drawColor(Color.WHITE)
			val paint = Paint()
			paint.colorFilter = CONTRAST_FILTER
			canvas.drawBitmap(image, 0f, 0f, paint)
			if (overlay != null) {
				canvas.drawBitmap(overlay, overlayX.toFloat(), overlayY.toFloat(), paint)
			}
			image.recycle()
			paint.reset()
			val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
			canvas = Canvas(result)
			paint.colorFilter = BLACK_CHROMA_KEY_FILTER
			canvas.drawBitmap(mask, 0f, 0f, paint)
			mask.recycle()
			return Pair(result, true)
		}
		return Pair(null, false)
	}

	@JvmStatic
	fun generateNoise(size: Int, scale: Int, colorFrom: Int, colorTo: Int): Bitmap {
		val aFrom = Color.alpha(colorFrom)
		val rFrom = Color.red(colorFrom)
		val gFrom = Color.green(colorFrom)
		val bFrom = Color.blue(colorFrom)
		val aTo = Color.alpha(colorTo)
		val rTo = Color.red(colorTo)
		val gTo = Color.green(colorTo)
		val bTo = Color.blue(colorTo)
		val random = RANDOM
		val realScale = maxOf(scale, 1)
		val realSize = size * realScale
		val pixels = IntArray(realSize * realSize)
		var i = 0
		while (i < pixels.size) {
			var j = 0
			while (j < realSize) {
				val a = random.nextInt(aTo - aFrom + 1) + aFrom
				val r = random.nextInt(rTo - rFrom + 1) + rFrom
				val g = random.nextInt(gTo - gFrom + 1) + gFrom
				val b = random.nextInt(bTo - bFrom + 1) + bFrom
				for (k in 0 until realScale) {
					pixels[i + j + k] = Color.argb(a, r, g, b)
				}
				j += realScale
			}
			for (j2 in 1 until realScale) {
				System.arraycopy(pixels, i, pixels, i + j2 * realSize, realSize)
			}
			i += realSize * realScale
		}
		return Bitmap.createBitmap(pixels, realSize, realSize, Bitmap.Config.ARGB_8888)
	}

	@JvmStatic
	fun mutateBitmap(bitmap: Bitmap): Bitmap {
		if (bitmap.isMutable) {
			return bitmap
		}
		val newBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
		if (newBitmap != bitmap) {
			bitmap.recycle()
		}
		return newBitmap
	}

	@JvmStatic
	fun applyRotation(bitmap: Bitmap?, rotation: Int): Bitmap? {
		require(rotation / 90 * 90 == rotation) { "Invalid rotation: $rotation" }
		if (bitmap == null) {
			return null
		}
		if (rotation % 360 == 0) {
			return bitmap
		}
		val matrix = Matrix()
		matrix.setRotate((-rotation).toFloat())
		try {
			return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
		} finally {
			bitmap.recycle()
		}
	}

	@JvmStatic
	fun applyGammaCorrection(bitmap: Bitmap?, gammaCorrection: Float): Bitmap? {
		if (bitmap == null) {
			return null
		}
		val mutable = mutateBitmap(bitmap)
		val lut = IntArray(256)
		for (i in 0 until 256) {
			lut[i] = (Math.pow((i / 255f).toDouble(), gammaCorrection.toDouble()) * 255 + 0.5f).toInt()
		}
		val width = mutable.width
		val height = mutable.height
		val pixels = IntArray(width * height)
		mutable.getPixels(pixels, 0, width, 0, 0, width, height)
		for (i in pixels.indices) {
			val color = pixels[i]
			pixels[i] = Color.argb(Color.alpha(color), lut[Color.red(color)],
					lut[Color.green(color)], lut[Color.blue(color)])
		}
		mutable.setPixels(pixels, 0, width, 0, 0, width, height)
		return mutable
	}
}
