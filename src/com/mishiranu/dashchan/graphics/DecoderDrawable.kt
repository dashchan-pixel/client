package com.mishiranu.dashchan.graphics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.LruCache
import java.io.IOException

class DecoderDrawable @Throws(IOException::class) constructor(private val scaledBitmap: Bitmap,
		fileHolder: FileHolder) : BaseDrawable() {
	private val decoder: BitmapRegionDecoder

	private val tasks = LinkedHashMap<Int, DecodeTask>()
	private val fragments = LruCache<Int, Bitmap>(MIN_MAX_ENTRIES) { _, v -> v.recycle() }

	private val width: Int
	private val height: Int
	private val rotation: Int
	private val gammaCorrection: Float?

	private val rect = Rect()
	private val dstRect = Rect()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

	private var enabled = true
	private var recycled = false

	init {
		if (!fileHolder.isImageRegionDecoderSupported) {
			throw IOException("Decoder drawable is not supported")
		}
		@Suppress("DEPRECATION")
		decoder = BitmapRegionDecoder.newInstance(fileHolder.openInputStream(), false)
				?: throw IOException("Failed to create region decoder")
		width = fileHolder.imageWidth
		height = fileHolder.imageHeight
		rotation = fileHolder.imageRotation
		gammaCorrection = fileHolder.imageGammaCorrectionForSkia
	}

	override fun draw(canvas: Canvas) {
		val bounds = bounds
		val rect = this.rect
		val dstRect = this.dstRect
		if (!(canvas.getClipBounds(rect) && rect.intersect(bounds))) {
			rect.set(bounds)
		}
		var maxEntries = 0
		var scale = 1
		var drawScaled = false
		if (!recycled) {
			val callback = callback
			if (callback is View) {
				val contentWidth = callback.width
				val contentHeight = callback.height
				val rectWidth = rect.width()
				val rectHeight = rect.height()
				val scaledSize: Int
				val contentSize: Int
				val rectSize: Int
				val size: Int
				if (rectWidth * contentHeight > rectHeight * contentWidth) {
					scaledSize = scaledBitmap.width
					contentSize = contentWidth
					rectSize = rectWidth
					size = width
				} else {
					scaledSize = scaledBitmap.height
					contentSize = contentHeight
					rectSize = rectHeight
					size = height
				}
				scale = Integer.highestOneBit(maxOf(rectSize / contentSize, 1))
				drawScaled = scaledSize >= size / scale
			}
		} else {
			drawScaled = true
		}
		val size = FRAGMENT_SIZE * scale
		if (enabled && !drawScaled) {
			var y = 0
			while (y < height) {
				var x = 0
				while (x < width) {
					if (rect.intersects(x, y, x + size, y + size)) {
						val key = calculateKey(x, y, scale)
						val fragment = fragments[key]
						var drawScaledFragment = false
						if (fragment != null) {
							if (fragment != NULL_BITMAP) {
								dstRect.set(x, y, x + scale * fragment.width, y + scale * fragment.height)
								canvas.drawBitmap(fragment, null, dstRect, paint)
							} else {
								drawScaledFragment = true
							}
						} else {
							var task = tasks[key]
							if (task == null) {
								task = DecodeTask(key, x, y, scale)
								task.execute(EXECUTOR)
								tasks[key] = task
							}
							drawScaledFragment = true
						}
						if (drawScaledFragment) {
							canvas.save()
							canvas.clipRect(x, y, x + size, y + size)
							dstRect.set(0, 0, width, height)
							canvas.drawBitmap(scaledBitmap, null, dstRect, paint)
							canvas.restore()
						}
						maxEntries++
					}
					x += size
				}
				y += size
			}
		} else {
			dstRect.set(0, 0, width, height)
			canvas.drawBitmap(scaledBitmap, null, dstRect, paint)
		}
		maxEntries = maxOf(MIN_MAX_ENTRIES, maxEntries)
		fragments.setMaxEntries(maxEntries)
		var cancel = tasks.size - maxEntries
		if (cancel > 0) {
			val iterator = tasks.values.iterator()
			while (iterator.hasNext() && cancel-- > 0) {
				iterator.next().cancel()
				iterator.remove()
			}
		}
	}

	override fun getIntrinsicWidth(): Int = width

	override fun getIntrinsicHeight(): Int = height

	fun hasAlpha(): Boolean = scaledBitmap.hasAlpha()

	private fun clear() {
		for (task in tasks.values) {
			task.cancel()
		}
		tasks.clear()
		for (fragment in fragments.values) {
			fragment.recycle()
		}
		fragments.clear()
	}

	fun setEnabled(enabled: Boolean) {
		if (this.enabled != enabled) {
			this.enabled = enabled
			if (!enabled) {
				clear()
			}
		}
	}

	fun recycle() {
		recycle(true)
	}

	private fun recycle(recycleScaled: Boolean) {
		if (!recycled) {
			recycled = true
			clear()
			synchronized(this) {
				decoder.recycle()
			}
		}
		if (recycleScaled) {
			scaledBitmap.recycle()
		}
	}

	private fun calculateKey(x: Int, y: Int, scale: Int): Int = x shl 18 or (y shl 4) or scale

	private inner class DecodeTask(private val key: Int, x: Int, y: Int, scale: Int) :
			ExecutorTask<Void, Bitmap>() {
		private val rect: Rect
		private val options = BitmapFactory.Options()

		private var error = false

		init {
			rect = Rect(x, y, minOf(x + FRAGMENT_SIZE * scale, width),
					minOf(y + FRAGMENT_SIZE * scale, height))
			if (rotation != 0) {
				val matrix = Matrix()
				matrix.setRotate(rotation.toFloat())
				when (rotation) {
					90 -> matrix.postTranslate(height.toFloat(), 0f)
					270 -> matrix.postTranslate(0f, width.toFloat())
					else -> matrix.postTranslate(width.toFloat(), height.toFloat())
				}
				val rectF = RectF(rect)
				matrix.mapRect(rectF)
				rect.set(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())
			}
			options.inSampleSize = scale
		}

		override fun run(): Bitmap? {
			return try {
				synchronized(this@DecoderDrawable) {
					var bitmap = decoder.decodeRegion(rect, options)
					bitmap = GraphicsUtils.applyRotation(bitmap, rotation)
					if (gammaCorrection != null) {
						bitmap = GraphicsUtils.applyGammaCorrection(bitmap, gammaCorrection)
					}
					bitmap
				}
			} catch (t: Throwable) {
				error = true
				t.printStackTrace()
				null
			}
		}

		public override fun cancel() {
			super.cancel()
		}

		override fun onCancel(bitmap: Bitmap?) {
			bitmap?.recycle()
		}

		override fun onComplete(bitmap: Bitmap?) {
			tasks.remove(key)
			if (error) {
				recycle(false)
			} else {
				fragments.put(key, bitmap ?: NULL_BITMAP)
				invalidateSelf()
			}
		}
	}

	companion object {
		private val EXECUTOR = ConcurrentUtils.newSingleThreadPool(20000, "DecoderDrawable", null)
		private val NULL_BITMAP = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

		private const val FRAGMENT_SIZE = 512
		private const val MIN_MAX_ENTRIES = 16
	}
}
