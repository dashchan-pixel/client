package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import chan.util.CommonUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.graphics.RoundedCornersDrawable
import com.mishiranu.dashchan.graphics.TransparentTileDrawable
import com.mishiranu.dashchan.util.AnimationUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils

class AttachmentView(context: Context, attrs: AttributeSet?) :
		View(ContextThemeWrapper(context, R.style.Theme_Gallery), attrs) {
	private val source = Rect()
	private val destination = RectF()
	private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

	private var tileDrawable: TransparentTileDrawable? = null
	private var errorOverlayDrawable: Drawable? = null

	private var backgroundColor: Int
	private var cropEnabled = false
	private var fitSquare = false
	private var sfwMode = false
	private var drawTouching = false
	private var cornersDrawable: RoundedCornersDrawable? = null

	private var lastClickTime: Long = 0

	private val workColorMatrix: FloatArray
	private val colorMatrix1: ColorMatrix
	private val colorMatrix2: ColorMatrix

	init {
		ViewUtils.setSelectableItemBackground(this)
		// Use old context to obtain background color.
		backgroundColor = ResourceUtils.getColor(context, R.attr.colorAttachmentBackground)
		workColorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
		colorMatrix1 = ColorMatrix(workColorMatrix)
		colorMatrix2 = ColorMatrix(workColorMatrix)
	}

	fun setCropEnabled(enabled: Boolean) {
		cropEnabled = enabled
	}

	fun setFitSquare(fitSquare: Boolean) {
		this.fitSquare = fitSquare
	}

	fun setSfwMode(sfwMode: Boolean) {
		this.sfwMode = sfwMode
	}

	enum class Overlay { NONE, MULTIPLE, AUDIO, VIDEO, FILE, WARNING }

	private var key: String? = null
	private var bitmap: Bitmap? = null
	private var error = false
	private var overlay: Overlay? = null
	private var overlayDrawable: Drawable? = null
	private var keepOverlayOnError = false

	fun resetImage(key: String?) {
		resetImage(key, Overlay.NONE)
	}

	fun resetImage(key: String?, overlay: Overlay) {
		var invalidate = false
		if (!CommonUtils.equals(this.key, key)) {
			this.key = key
			if (bitmap != null || error) {
				bitmap = null
				error = false
				invalidate = true
			}
		}
		if (overlay != this.overlay) {
			var overlayAttrId = 0
			var keepOverlayOnError = false
			when (overlay) {
				Overlay.MULTIPLE -> {
					overlayAttrId = R.attr.iconAttachmentMultiple
					keepOverlayOnError = true
				}
				Overlay.AUDIO -> {
					overlayAttrId = R.attr.iconAttachmentAudio
					keepOverlayOnError = true
				}
				Overlay.VIDEO -> {
					overlayAttrId = R.attr.iconAttachmentVideo
				}
				Overlay.FILE -> {
					overlayAttrId = R.attr.iconAttachmentFile
					keepOverlayOnError = true
				}
				Overlay.WARNING -> {
					overlayAttrId = R.attr.iconAttachmentWarning
					keepOverlayOnError = true
				}
				Overlay.NONE -> {}
			}
			this.overlay = overlay
			overlayDrawable = if (overlayAttrId != 0) ResourceUtils.getDrawable(context, overlayAttrId, 0) else null
			this.keepOverlayOnError = keepOverlayOnError
			invalidate = true
		}
		if (invalidate) {
			invalidate()
		}
	}

	fun handleLoadedImage(key: String?, bitmap: Bitmap?, error: Boolean, instantly: Boolean) {
		if (this.bitmap == null && CommonUtils.equals(this.key, key)) {
			this.bitmap = bitmap
			this.error = bitmap == null && error
			imageApplyTime = if (instantly) 0L else SystemClock.elapsedRealtime()
			invalidate()
		}
	}

	fun hasImage(): Boolean {
		return bitmap != null
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				if (SystemClock.elapsedRealtime() - lastClickTime <= ViewConfiguration.getDoubleTapTimeout()) {
					event.action = MotionEvent.ACTION_CANCEL
					return false
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				lastClickTime = SystemClock.elapsedRealtime()
			}
		}
		return super.onTouchEvent(event)
	}

	override fun setBackgroundColor(color: Int) {
		backgroundColor = color
	}

	fun setDrawTouching(drawTouching: Boolean) {
		this.drawTouching = drawTouching
	}

	fun applyRoundedCorners(backgroundColor: Int) {
		if (cornersDrawable == null) {
			val density = ResourceUtils.obtainDensity(this)
			val radius = (2f * density + 0.5f).toInt()
			cornersDrawable = RoundedCornersDrawable(radius)
			if (width > 0) {
				updateCornersBounds(width, height)
			}
		}
		cornersDrawable!!.setColor(backgroundColor)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		if (fitSquare) {
			val width = measuredWidth
			setMeasuredDimension(width, width)
		}
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		updateCornersBounds(right - left, bottom - top)
	}

	private fun updateCornersBounds(width: Int, height: Int) {
		cornersDrawable?.setBounds(0, 0, width, height)
	}

	private var imageApplyTime = 0L

	override fun draw(canvas: Canvas) {
		val interpolator = AnimationUtils.DECELERATE_INTERPOLATOR
		canvas.drawColor(backgroundColor)
		val current = this.bitmap
		val bitmap = if (current != null && !current.isRecycled) current else null
		val hasImage = bitmap != null
		val vw = width
		val vh = height
		val dt = if (imageApplyTime > 0L) (SystemClock.elapsedRealtime() - imageApplyTime).toInt() else Int.MAX_VALUE
		val alpha = interpolator.getInterpolation(Math.min(dt / 200f, 1f))
		var invalidate = false
		if (bitmap != null) {
			val bw = bitmap.width
			val bh = bitmap.height
			val scale: Float
			val dx: Float
			val dy: Float
			if ((bw * vh > vw * bh) xor !cropEnabled) {
				scale = vh.toFloat() / bh.toFloat()
				dx = ((vw - bw * scale) * 0.5f + 0.5f).toInt().toFloat()
				dy = 0f
			} else {
				scale = vw.toFloat() / bw.toFloat()
				dx = 0f
				dy = ((vh - bh * scale) * 0.5f + 0.5f).toInt().toFloat()
			}
			source.set(0, 0, bw, bh)
			destination.set(dx, dy, dx + bw * scale, dy + bh * scale)
			if (bitmap.hasAlpha()) {
				val left = Math.max((destination.left + 0.5f).toInt(), 0)
				val top = Math.max((destination.top + 0.5f).toInt(), 0)
				val right = Math.min((destination.right + 0.5f).toInt(), vw)
				val bottom = Math.min((destination.bottom + 0.5f).toInt(), vh)
				if (tileDrawable == null) {
					tileDrawable = TransparentTileDrawable(context, false)
				}
				tileDrawable!!.setBounds(left, top, right, bottom)
				tileDrawable!!.draw(canvas)
			}
			bitmapPaint.alpha = (0xff * alpha).toInt()
			val contrast = interpolator.getInterpolation(Math.min(dt / 300f, 1f))
			val saturation = interpolator.getInterpolation(Math.min(dt / 400f, 1f))
			if (saturation < 1f) {
				val matrix = workColorMatrix
				val contrastGain = 1f + 2f * (1f - contrast)
				val contrastExtra = (1f - contrastGain) * 255f
				matrix[12] = contrastGain
				matrix[6] = matrix[12]
				matrix[0] = matrix[6]
				matrix[14] = contrastExtra
				matrix[9] = matrix[14]
				matrix[4] = matrix[9]
				colorMatrix2.set(matrix)
				colorMatrix1.setSaturation(saturation)
				colorMatrix1.postConcat(colorMatrix2)
				bitmapPaint.colorFilter = ColorMatrixColorFilter(colorMatrix1)
			} else {
				bitmapPaint.colorFilter = null
			}
			invalidate = saturation < 1f

			if (!canvas.isHardwareAccelerated && bitmap.config == Bitmap.Config.HARDWARE) {
				// Threadshots render post views into a software canvas, which cannot
				// draw hardware bitmaps - draw a transient software copy instead.
				val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
				if (softwareBitmap != null) {
					canvas.drawBitmap(softwareBitmap, source, destination, bitmapPaint)
					softwareBitmap.recycle()
				}
			} else {
				canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
			}
		}
		if (sfwMode) {
			canvas.drawColor(backgroundColor and 0x00ffffff or 0xe0000000.toInt())
		}
		var overlayDrawable = this.overlayDrawable
		if (error && !keepOverlayOnError) {
			if (errorOverlayDrawable == null) {
				errorOverlayDrawable = ResourceUtils.getDrawable(context, R.attr.iconAttachmentWarning, 0)
			}
			overlayDrawable = errorOverlayDrawable
		}
		if (overlayDrawable != null) {
			if (hasImage && !sfwMode) {
				canvas.drawColor((0x66 * alpha).toInt() shl 24)
			}
			val dw = overlayDrawable.intrinsicWidth
			val dh = overlayDrawable.intrinsicHeight
			val left = (vw - dw) / 2
			val top = (vh - dh) / 2
			val right = left + dw
			val bottom = top + dh
			overlayDrawable.setBounds(left, top, right, bottom)
			overlayDrawable.draw(canvas)
		}
		if (drawTouching) {
			super.draw(canvas)
		}
		cornersDrawable?.draw(canvas)
		if (invalidate) {
			invalidate()
		}
	}
}
