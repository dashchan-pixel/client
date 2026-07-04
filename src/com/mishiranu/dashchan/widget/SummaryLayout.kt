package com.mishiranu.dashchan.widget

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import java.lang.ref.WeakReference

class SummaryLayout(dialog: AlertDialog) : CommentTextView.PrepareToCopyListener, View.OnClickListener {
	private val textView: CommentTextView
	private val builder = SpannableStringBuilder()
	private val dividerDrawable: Drawable?

	private val titlePaddingTextSize: Float
	private val blockPaddingTextSize: Float
	private val dividerPaddingTextSize: Float
	private val dividerExtraTop: Int
	private val dividerExtraBottom: Int

	init {
		val context = dialog.context
		val scrollView = object : ScrollView(context) {
			override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
				// Don't scroll on select
				return true
			}
		}
		ThemeEngine.applyStyle(scrollView)
		dialog.setView(scrollView)
		val frameLayout = FrameLayout(context)
		scrollView.addView(frameLayout, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
		frameLayout.setOnClickListener(this)
		textView = CommentTextView(context, null, android.R.attr.textViewStyle)
		frameLayout.addView(textView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
		textView.setTextColor(ResourceUtils.getColorStateList(context, android.R.attr.textColorPrimary))
		textView.setPrepareToCopyListener(this)

		// Dialogs have disabled dividers
		val light = GraphicsUtils.isLight(ResourceUtils.getDialogBackground(context))
		val dividerContext = ContextThemeWrapper(context,
				if (light) R.style.Theme_Main_Light else R.style.Theme_Main_Dark)

		dividerDrawable = ResourceUtils.getDrawable(dividerContext, android.R.attr.dividerHorizontal, 0)

		val unspecifiedSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		val fontMetrics = Paint.FontMetrics()
		textView.text = "Measure"
		ViewUtils.setTextSizeScaled(textView, TEXT_SIZE_SP)
		textView.measure(unspecifiedSpec, unspecifiedSpec)
		textView.paint.getFontMetrics(fontMetrics)
		dividerExtraTop = textView.layout.getLineBottom(0) - textView.layout.getLineBaseline(0)
		ViewUtils.setTextSizeScaled(textView, TITLE_SIZE_SP)
		textView.measure(unspecifiedSpec, unspecifiedSpec)
		textView.paint.getFontMetrics(fontMetrics)
		dividerExtraBottom = textView.layout.getLineBaseline(0) - textView.layout.getLineTop(0)

		ViewUtils.setTextSizeScaled(textView, TEXT_SIZE_SP)
		val density = ResourceUtils.obtainDensity(context)
		val blockPadding = (BLOCK_PADDING_DP * density + 0.5f).toInt()
		val titlePadding = (TITLE_PADDING_DP * density + 0.5f).toInt()
		titlePaddingTextSize = calculateFontSize(textView, titlePadding, true, false)
		blockPaddingTextSize = calculateFontSize(textView, blockPadding, false, true)
		dividerPaddingTextSize = calculateFontSize(textView, 2 * blockPadding +
				(dividerDrawable?.intrinsicHeight ?: 0), false, true)

		textView.text = null
		textView.setPadding((24f * density).toInt(), (20f * density).toInt(),
				(24f * density).toInt(), (8f * density).toInt())
	}

	private var addDivider = false

	fun add(title: CharSequence, text: CharSequence) {
		if (builder.length > 0) {
			builder.append('\n')
			if (addDivider && dividerDrawable != null) {
				builder.append("  \n")
				builder.setSpan(PaddingSpan(dividerPaddingTextSize),
						builder.length - 2, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
				builder.setSpan(DividerSpan(textView, dividerDrawable, dividerExtraTop, dividerExtraBottom),
						builder.length - 3, builder.length - 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			} else {
				builder.append('\n')
				builder.setSpan(PaddingSpan(blockPaddingTextSize),
						builder.length - 1, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
				builder.setSpan(PaddingSpan(blockPaddingTextSize),
						builder.length - 1, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
		}
		addDivider = false
		builder.append(title).append('\n')
		builder.setSpan(RelativeSizeSpan(TITLE_SIZE_SP.toFloat() / TEXT_SIZE_SP),
				builder.length - title.length - 1, builder.length,
				SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
		builder.setSpan(ForegroundColorSpan(ResourceUtils.getColor(textView.context,
				android.R.attr.textColorSecondary)), builder.length - title.length - 1, builder.length,
				SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
		builder.append('\n')
		builder.setSpan(PaddingSpan(titlePaddingTextSize),
				builder.length - 1, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
		builder.append(text)
		textView.text = builder
	}

	fun addDivider() {
		addDivider = true
	}

	override fun onPrepareToCopy(view: CommentTextView, text: Spannable, start: Int, end: Int): String {
		return text.toString().substring(start, end).trim().replace("\n\n", "\n").replace("\n  \n", "\n\n")
	}

	private var lastClickTime: Long = 0

	override fun onClick(v: View) {
		val time = SystemClock.elapsedRealtime()
		if (time - lastClickTime < ViewConfiguration.getDoubleTapTimeout()) {
			lastClickTime = 0L
			textView.startSelection()
		} else {
			lastClickTime = time
		}
	}

	private class PaddingSpan(var textSize: Float) : MetricAffectingSpan() {
		override fun updateMeasureState(textPaint: TextPaint) {
			textPaint.textSize = textSize
		}

		override fun updateDrawState(textPaint: TextPaint) {
			updateMeasureState(textPaint)
		}
	}

	private class DividerSpan(parent: TextView, dividerDrawable: Drawable,
			private val extraTop: Int, private val extraBottom: Int) : ReplacementSpan() {
		private val parent = WeakReference(parent)
		private val dividerDrawable = WeakReference(dividerDrawable)

		override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
			return 1
		}

		override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
				x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
			val parent = this.parent.get()
			val dividerDrawable = this.dividerDrawable.get()
			if (parent != null && dividerDrawable != null) {
				val layout: Layout = parent.layout
				val line = layout.getLineForVertical(y)
				if (line >= 1) {
					val height = dividerDrawable.intrinsicHeight
					val topBaseline = layout.getLineBaseline(line - 1) + extraTop
					val bottomBaseline = layout.getLineBaseline(line + 1) - extraBottom
					val drawTop = ((topBaseline + bottomBaseline - height) / 2f + 0.5f).toInt()
					dividerDrawable.setBounds(0, drawTop, parent.width, drawTop + height)
					dividerDrawable.draw(canvas)
				}
			}
		}
	}

	companion object {
		private const val TITLE_PADDING_DP = 4
		private const val BLOCK_PADDING_DP = 16
		private const val TITLE_SIZE_SP = 12
		private const val TEXT_SIZE_SP = 16

		private fun calculateFontSize(textView: TextView, padding: Int,
				titleFirst: Boolean, titleSecond: Boolean): Float {
			val unspecifiedSpec = View.MeasureSpec.makeMeasureSpec(Int.MAX_VALUE / 2, View.MeasureSpec.AT_MOST)
			val builder = SpannableStringBuilder()
			addTestLines(builder, "Line", titleFirst, titleSecond)
			textView.text = builder
			textView.measure(unspecifiedSpec, unspecifiedSpec)
			val singleHeight = textView.measuredHeight
			builder.replace(builder.length - 1, builder.length, "\n")
			builder.append('\n')
			val paddingSpan = PaddingSpan(padding.toFloat())
			builder.setSpan(paddingSpan, builder.length - 1, builder.length,
					SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			addTestLines(builder, "Line", titleFirst, titleSecond)
			val target = 2 * singleHeight + padding
			var lastTextSize = 0f
			while (true) {
				textView.text = builder
				textView.measure(unspecifiedSpec, unspecifiedSpec)
				val height = textView.measuredHeight
				val newTextSize = paddingSpan.textSize + (target - height) / 2f
				if (newTextSize <= 0 || newTextSize == lastTextSize) {
					break
				}
				lastTextSize = paddingSpan.textSize
				paddingSpan.textSize = newTextSize
			}
			return paddingSpan.textSize
		}

		private fun addTestLines(builder: SpannableStringBuilder, lineText: String,
				titleFirst: Boolean, titleSecond: Boolean) {
			builder.append(lineText).append('\n').append(lineText).append(' ')
			val titleSize = TITLE_SIZE_SP.toFloat() / TEXT_SIZE_SP
			if (titleFirst) {
				builder.setSpan(RelativeSizeSpan(titleSize),
						builder.length - lineText.length - 1, builder.length,
						SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			if (titleSecond) {
				builder.setSpan(RelativeSizeSpan(titleSize),
						builder.length - 2 * lineText.length - 2, builder.length - lineText.length - 1,
						SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
		}
	}
}
