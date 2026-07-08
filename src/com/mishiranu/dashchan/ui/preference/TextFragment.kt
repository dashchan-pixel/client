package com.mishiranu.dashchan.ui.preference

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import chan.content.ChanMarkup
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.LocaleManager
import com.mishiranu.dashchan.content.async.ReadChangelogTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.graphics.ColorScheme
import com.mishiranu.dashchan.text.SpanComparator
import com.mishiranu.dashchan.text.style.HeadingSpan
import com.mishiranu.dashchan.ui.FragmentHandler
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.IOUtils
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.util.ViewUtils
import com.mishiranu.dashchan.widget.CommentTextView
import com.mishiranu.dashchan.widget.DividerItemDecoration
import com.mishiranu.dashchan.widget.ExpandedLayout
import com.mishiranu.dashchan.widget.PostLinearLayout
import com.mishiranu.dashchan.widget.PostsLayoutManager
import com.mishiranu.dashchan.widget.ThemeEngine
import com.mishiranu.dashchan.widget.ViewFactory
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class TextFragment : BaseListFragment {
	enum class Type { LICENSES, CHANGELOG }

	private var changelogEntries: List<ReadChangelogTask.Entry>? = null
	private var errorItem: ErrorItem? = null

	private var progressView: View? = null

	constructor()

	constructor(type: Type) {
		val args = Bundle()
		args.putString(EXTRA_TYPE, type.name)
		arguments = args
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
			savedInstanceState: Bundle?): View {
		val layout = super.onCreateView(inflater, container, savedInstanceState) as ExpandedLayout
		progressView = ViewFactory.createProgressLayout(layout)
		return layout
	}

	override fun onDestroyView() {
		super.onDestroyView()
		progressView = null
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val recyclerView = getRecyclerView()!!
		val context = recyclerView.context
		recyclerView.layoutManager = PostsLayoutManager(context)
		val adapter = TextAdapter()
		recyclerView.adapter = adapter

		when (Type.valueOf(requireArguments().getString(EXTRA_TYPE)!!)) {
			Type.LICENSES -> {
				(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.foss_licenses), null)
				val text = IOUtils.readRawResourceString(resources, R.raw.markup_licenses)
				adapter.setItems(context, formatText(text))
			}
			Type.CHANGELOG -> {
				(requireActivity() as FragmentHandler).setTitleSubtitle(getString(R.string.changelog), null)
				changelogEntries = if (savedInstanceState != null)
						BundleCompat.getParcelableArrayList(savedInstanceState, EXTRA_CHANGELOG_ENTRIES, ReadChangelogTask.Entry::class.java) else null
				errorItem = if (savedInstanceState != null)
						BundleCompat.getParcelable(savedInstanceState, EXTRA_ERROR_ITEM, ErrorItem::class.java) else null
				val errorItem = this.errorItem
				if (errorItem != null) {
					recyclerView.visibility = View.GONE
					setErrorText(errorItem.toString())
				} else if (changelogEntries != null) {
					adapter.setItems(context, formatChangelogEntries(context, changelogEntries!!))
				} else {
					recyclerView.visibility = View.GONE
					progressView!!.visibility = View.VISIBLE
					val viewModel = ViewModelProvider(this).get(ChangelogViewModel::class.java)
					if (!viewModel.hasTaskOrValue()) {
						val task = ReadChangelogTask(viewModel.callback,
								LocaleManager.getInstance().getLocales(resources.configuration))
						task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
						viewModel.attach(task)
					}
					viewModel.observe(viewLifecycleOwner) { entries, errorItem ->
						changelogEntries = entries
						this.errorItem = errorItem
						progressView!!.visibility = View.GONE
						if (entries != null) {
							recyclerView.visibility = View.VISIBLE
							adapter.setItems(context, formatChangelogEntries(context, entries))
						} else {
							val error = errorItem ?: ErrorItem(ErrorItem.Type.UNKNOWN)
							setErrorText(error.toString())
						}
					}
				}
			}
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)

		when (Type.valueOf(requireArguments().getString(EXTRA_TYPE)!!)) {
			Type.CHANGELOG -> {
				outState.putParcelable(EXTRA_ERROR_ITEM, errorItem)
				outState.putParcelableArrayList(EXTRA_CHANGELOG_ENTRIES, ArrayList(changelogEntries!!))
			}
			Type.LICENSES -> {}
		}
	}

	override fun setListPadding(recyclerView: RecyclerView) {}

	override fun configureDivider(configuration: DividerItemDecoration.Configuration,
			position: Int): DividerItemDecoration.Configuration {
		val padding = getPadding(resources)
		return configuration.need(true).horizontal(padding, padding)
	}

	private class ListHeaderSpan(sub: Boolean) : RelativeSizeSpan((if (sub) 12f else 16f) / 14f)

	private class TextAdapter : RecyclerView.Adapter<TextAdapter.ViewHolder>() {
		private class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(PostLinearLayout(parent.context)),
				ListViewUtils.ClickCallback<Void, ViewHolder> {
			val textView: CommentTextView

			private var lastClickTime: Long = 0

			init {
				val layout = itemView as PostLinearLayout
				layout.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT)
				ViewUtils.setSelectableItemBackground(layout)
				textView = CommentTextView(parent.context, null, android.R.attr.textAppearance)
				ViewUtils.setTextSizeScaled(textView, 14)
				val padding = getPadding(textView.resources)
				textView.setPadding(padding, padding, padding, padding)
				layout.addView(textView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
				ListViewUtils.bind(this, false, null, this)
			}

			override fun onItemClick(holder: ViewHolder, position: Int, item: Void?, longClick: Boolean): Boolean {
				val time = SystemClock.elapsedRealtime()
				if (time - lastClickTime < ViewConfiguration.getDoubleTapTimeout()) {
					lastClickTime = 0
					textView.startSelection()
				} else {
					lastClickTime = time
				}
				return true
			}
		}

		private var items: List<CharSequence> = emptyList()

		fun setItems(context: Context, items: List<CharSequence>) {
			val colorScheme = ThemeEngine.getColorScheme(context)
			for (text in items) {
				colorScheme.apply(text)
			}
			this.items = items
			notifyDataSetChanged()
		}

		override fun getItemCount(): Int = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(parent)

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			holder.textView.text = items[position]
		}
	}

	private class PrefixSpan : ReplacementSpan(), ColorScheme.Span {
		private val fontMetrics = Paint.FontMetricsInt()
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val rect = RectF()

		private var background = 0
		private var foreground = 0

		override fun applyColorScheme(colorScheme: ColorScheme?) {
			if (colorScheme != null) {
				background = colorScheme.linkColor
				foreground = colorScheme.windowBackgroundColor
			}
		}

		override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
				fm: Paint.FontMetricsInt?): Int {
			val workPaint = this.paint
			workPaint.set(paint)
			updateTextSize(workPaint)
			val drawText = getText(text, start, end)
			return (workPaint.measureText(drawText, 0, drawText.length) +
					2 * workPaint.measureText(" ", 0, 1) + 0.5f).toInt()
		}

		override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
				x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
			val fontMetrics = this.fontMetrics
			paint.getFontMetricsInt(fontMetrics)
			val workPaint = this.paint
			val fullSize = getSize(paint, text, start, end, fontMetrics)
			workPaint.color = background
			val radius = workPaint.textSize / 11f
			val padding = fontMetrics.descent / 3f
			val baseline = bottom - fontMetrics.bottom
			rect.set(x, baseline + fontMetrics.ascent + padding, x + fullSize,
					baseline + fontMetrics.descent - padding)
			canvas.drawRoundRect(rect, radius, radius, workPaint)
			workPaint.color = foreground
			val drawText = getText(text, start, end)
			val textSize = (workPaint.measureText(drawText, 0, drawText.length) + 0.5f).toInt()
			val dx = (fullSize - textSize) / 2f
			var topDy = fontMetrics.ascent.toFloat()
			var bottomDy = fontMetrics.descent.toFloat()
			workPaint.getFontMetricsInt(fontMetrics)
			topDy -= fontMetrics.ascent
			bottomDy -= fontMetrics.descent
			val dy = (topDy + bottomDy) / 2f
			canvas.drawText(drawText, 0, drawText.length, x + dx, baseline + dy, workPaint)
		}

		companion object {
			private fun updateTextSize(paint: Paint) {
				paint.textSize = (11f / 14f * paint.textSize + 0.5f).toInt().toFloat()
				paint.typeface = ResourceUtils.TYPEFACE_MEDIUM
			}

			private fun getText(text: CharSequence, start: Int, end: Int): CharSequence {
				val builder = StringBuilder()
				for (i in start + 1 until end - 1) {
					builder.append(Character.toUpperCase(text[i]))
				}
				return builder
			}
		}
	}

	class ChangelogViewModel : TaskViewModel.Proxy<ReadChangelogTask, ReadChangelogTask.Callback>()

	companion object {
		private const val EXTRA_TYPE = "type"

		private const val EXTRA_CHANGELOG_ENTRIES = "changelogEntries"
		private const val EXTRA_ERROR_ITEM = "errorItem"

		private val BUILDER = ChanMarkup.MarkupBuilder { markup ->
			markup.addTag("h1", ChanMarkup.TAG_HEADING)
			markup.addTag("pre", ChanMarkup.TAG_CODE)
		}

		private val DATE_FORMAT_CHANGELOG = SimpleDateFormat("dd.MM.yyyy", Locale.US)

		private fun formatText(html: String): List<CharSequence> {
			val text = BUILDER.fromHtmlReduced(html)
			val builder = SpannableStringBuilder(text)
			val spans = builder.getSpans(0, builder.length, HeadingSpan::class.java)
			if (spans != null && spans.size > 1) {
				val sorted = spans.sortedWith(SpanComparator(builder, SpanComparator.Property.START))
				val items = ArrayList<CharSequence>()
				for (i in sorted.indices) {
					val start = builder.getSpanStart(sorted[i])
					val end = if (i < sorted.size - 1) builder.getSpanStart(sorted[i + 1]) else builder.length
					val subBuilder = SpannableStringBuilder(builder, start, end)
					var subEnd = subBuilder.length
					while (subEnd > 0) {
						if (subBuilder[subEnd - 1] == '\n') {
							subEnd--
						} else {
							break
						}
					}
					if (subEnd < subBuilder.length) {
						subBuilder.delete(subEnd, subBuilder.length)
					}
					if (subBuilder.length > 0) {
						val spanEnd = subBuilder.getSpanEnd(sorted[i])
						subBuilder.removeSpan(sorted[i])
						subBuilder.setSpan(ListHeaderSpan(false), 0, spanEnd,
								Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
						items.add(subBuilder)
					}
				}
				return items
			} else {
				return listOf(builder)
			}
		}

		private fun formatChangelogEntries(context: Context,
				changelogEntries: List<ReadChangelogTask.Entry>): List<CharSequence> {
			val dateFormat = android.text.format.DateFormat.getDateFormat(context)
			val items = ArrayList<CharSequence>()
			val versionText = context.getString(R.string.version)
			for (entry in changelogEntries) {
				val builder = SpannableStringBuilder()
				val header: String
				val subHeader: String?
				val start = entry.versions[0]
				val startName = start.getMajorMinor()
				val startDate = formatChangelogDate(dateFormat, start.date)
				if (entry.versions.size >= 2) {
					val end = entry.versions[entry.versions.size - 1]
					val endName = end.getMajorMinor()
					val endDate = formatChangelogDate(dateFormat, end.date)
					if (startName == endName) {
						if (startDate == endDate) {
							header = "$versionText $startName"
							subHeader = startDate
						} else {
							header = "$versionText $startName"
							subHeader = "$startDate — $endDate"
						}
					} else {
						header = "$versionText $startName — $endName"
						subHeader = "$startDate — $endDate"
					}
				} else {
					val startNameSuffix = start.name.substring(startName.length)
					header = if (startNameSuffix == ".0") {
						"$versionText $startName"
					} else {
						"$versionText ${start.name}"
					}
					subHeader = startDate
				}
				builder.append(header)
				builder.setSpan(ListHeaderSpan(false), builder.length - header.length, builder.length,
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				builder.append("\n")
				builder.append(subHeader)
				builder.append("\n\n")
				var newLine = false
				for (text in entry.texts) {
					for (rawLine in text.split("\n")) {
						var line = rawLine
						if (line.isNotEmpty()) {
							if (!newLine) {
								newLine = true
							} else {
								builder.append('\n')
							}
							var bullet = false
							if (line.startsWith("*")) {
								line = line.substring(1).trim()
								bullet = true
							}
							if (bullet) {
								builder.append("• ")
							}
							if (line.startsWith("[")) {
								val bracketEnd = line.indexOf(']')
								if (bracketEnd >= 0) {
									val prefix = line.substring(0, bracketEnd + 1)
									line = line.substring(bracketEnd + 1).trim()
									builder.append(prefix)
									builder.setSpan(PrefixSpan(), builder.length - prefix.length, builder.length,
											Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
									builder.append(' ')
								}
							}
							builder.append(line)
						}
					}
				}
				items.add(StringUtils.reduceEmptyLines(builder))
			}
			return items
		}

		private fun getPadding(resources: Resources): Int {
			val density = ResourceUtils.obtainDensity(resources)
			return (16f * density).toInt()
		}

		@JvmStatic
		fun formatChangelogDate(dateFormat: DateFormat, dateString: String): String? {
			val date: Long
			try {
				date = DATE_FORMAT_CHANGELOG.parse(dateString)!!.time
			} catch (e: ParseException) {
				e.printStackTrace()
				return null
			}
			return dateFormat.format(date)
		}
	}
}
