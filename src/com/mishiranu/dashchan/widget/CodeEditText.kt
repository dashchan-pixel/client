package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ScrollView
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

open class CodeEditText : SafePasteEditText {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    private val requestRect = Rect()

    private val visibleTextHeight: Int
        get() = height - extendedPaddingTop - extendedPaddingBottom

    private val contextHeight: Int
        get() = CONTEXT_LINES * lineHeight

    private val isTextScrollable: Boolean
        get() = (layout?.height ?: 0) > visibleTextHeight

    private var isKeyboardOpen = false
    private var isExpanded = false

    private var isUpdatingHighlight = false
    private val highlightRunnable = Runnable { applyHighlighting() }

    /** What the text is highlighted as. Defaults to [Syntax.JAVASCRIPT] (command bodies). */
    var syntax: Syntax = Syntax.JAVASCRIPT
        set(value) {
            if (field != value) {
                field = value
                applyHighlighting()
            }
        }

    private val textWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {}

            override fun afterTextChanged(s: Editable?) {
                removeCallbacks(highlightRunnable)
                postDelayed(highlightRunnable, 300)
            }
        }

    init {
        addTextChangedListener(textWatcher)
    }

    private fun applyHighlighting() {
        val s = text ?: return
        if (s.isEmpty()) return
        if (isUpdatingHighlight) return
        isUpdatingHighlight = true

        val isNightMode = ThemeEngine.isNightMode(context)
        val keywordColor = if (isNightMode) Color.parseColor("#CC7832") else Color.parseColor("#000080")
        val stringColor = if (isNightMode) Color.parseColor("#6A8759") else Color.parseColor("#008000")
        val commentColor = Color.parseColor("#808080")
        val numberColor = if (isNightMode) Color.parseColor("#6897BB") else Color.parseColor("#0000FF")

        val spans = s.getSpans(0, s.length, ForegroundColorSpan::class.java)
        for (span in spans) {
            s.removeSpan(span)
        }

        val matcher = syntax.pattern.matcher(s)
        while (matcher.find()) {
            // Only the groups the active syntax declares may be queried — asking a Pattern for a name
            // it doesn't define throws.
            for (group in syntax.groups) {
                if (matcher.group(group) == null) {
                    continue
                }
                val color =
                    when (group) {
                        GROUP_COMMENT -> commentColor
                        GROUP_STRING -> stringColor
                        GROUP_NUMBER -> numberColor
                        else -> keywordColor
                    }
                s.setSpan(
                    ForegroundColorSpan(color),
                    matcher.start(group),
                    matcher.end(group),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                break
            }
        }

        isUpdatingHighlight = false
    }

    private val layoutListener =
        android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (isExpanded) {
                val parentGroup = parent as? ViewGroup ?: return@OnGlobalLayoutListener
                val scrollView = parentGroup.parent as? ScrollView ?: return@OnGlobalLayoutListener
                val availableHeight = scrollView.height - parentGroup.paddingTop - parentGroup.paddingBottom

                if (availableHeight > 0 && layoutParams.height != availableHeight) {
                    if (layoutParams.height == resources.displayMetrics.heightPixels) {
                        layoutParams.height = availableHeight
                        requestLayout()
                    }
                }
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnApplyWindowInsetsListener { _, insets ->
            isKeyboardOpen = insets.isVisible(WindowInsets.Type.ime())
            updateExpansionState()

            if (isExpanded) {
                layoutParams.height = resources.displayMetrics.heightPixels
                requestLayout()
            }

            insets
        }
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        updateExpansionState()
    }

    var onExpandedStateChanged: ((Boolean) -> Unit)? = null

    private fun updateExpansionState() {
        val shouldExpand = isKeyboardOpen && isFocused
        if (isExpanded == shouldExpand) return
        isExpanded = shouldExpand

        val parentGroup = parent as? ViewGroup ?: return
        for (i in 0 until parentGroup.childCount) {
            val child = parentGroup.getChildAt(i)
            if (child != this) {
                child.visibility = if (shouldExpand) View.GONE else View.VISIBLE
            }
        }

        val lp = layoutParams
        if (shouldExpand) {
            lp.height = resources.displayMetrics.heightPixels
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        layoutParams = lp

        onExpandedStateChanged?.invoke(shouldExpand)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!isExpanded && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val limit = (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()
            if (measuredHeight > limit) {
                setMeasuredDimension(measuredWidthAndState, limit)
            }
        }
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        val changed = super.bringPointIntoView(offset)
        return keepCaretContextVisible(offset) || changed
    }

    override fun bringPointIntoView(
        offset: Int,
        requestRectangleVisible: Boolean,
    ): Boolean {
        val changed = super.bringPointIntoView(offset, requestRectangleVisible)
        return keepCaretContextVisible(offset) || changed
    }

    override fun getFocusedRect(r: Rect) {
        super.getFocusedRect(r)
        addContextBelow(r)
    }

    override fun requestRectangleOnScreen(
        rectangle: Rect,
        immediate: Boolean,
    ): Boolean {
        requestRect.set(rectangle)
        addContextBelow(requestRect)
        return super.requestRectangleOnScreen(requestRect, immediate)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTextScrollable) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun addContextBelow(rect: Rect) {
        val room = height - rect.bottom
        if (room > 0) {
            rect.bottom += min(room, contextHeight)
        }
    }

    private fun keepCaretContextVisible(offset: Int): Boolean {
        val layout = layout ?: return false
        val visibleHeight = visibleTextHeight
        if (visibleHeight <= 0) {
            return false
        }
        val line = layout.getLineForOffset(offset)
        val contextBottom = layout.getLineBottom(min(line + CONTEXT_LINES, layout.lineCount - 1))
        val contextTop = layout.getLineTop(max(line - CONTEXT_LINES, 0))
        var scroll = scrollY
        if (contextBottom - scroll > visibleHeight) {
            scroll = contextBottom - visibleHeight
        }
        if (contextTop < scroll) {
            scroll = contextTop
        }
        val caretTop = layout.getLineTop(line)
        val caretMinScroll = layout.getLineBottom(line) - visibleHeight
        scroll = if (caretMinScroll <= caretTop) scroll.coerceIn(caretMinScroll, caretTop) else caretTop
        scroll = scroll.coerceIn(0, max(layout.height - visibleHeight, 0))
        if (scroll == scrollY) {
            return false
        }
        scrollTo(scrollX, scroll)
        return true
    }

    /** The languages this editor can colour. */
    enum class Syntax(
        internal val pattern: Pattern,
        /** Named groups of [pattern], in the order they should be tested. */
        internal val groups: List<String>,
    ) {
        JAVASCRIPT(JS_PATTERN, listOf(GROUP_COMMENT, GROUP_STRING, GROUP_KEYWORD, GROUP_NUMBER)),

        /** `NAME=value` lines with `#` comments — see the Commands environment editor. */
        ENVIRONMENT(ENV_PATTERN, listOf(GROUP_COMMENT, GROUP_STRING, GROUP_KEY)),
    }

    companion object {
        private const val CONTEXT_LINES = 3
        private const val MAX_HEIGHT_FRACTION = 0.3f
    }
}

// Kept out of the companion object: an enum entry's constructor arguments are evaluated before the
// companion is initialized, so Syntax can't read them from there.

private const val GROUP_COMMENT = "comment"
private const val GROUP_STRING = "string"
private const val GROUP_KEYWORD = "keyword"
private const val GROUP_NUMBER = "number"
private const val GROUP_KEY = "key"

private val JS_PATTERN =
    Pattern.compile(
        "(?<comment>//[^\\n]*|(?s:/\\*.*?\\*/))" +
            "|(?<string>\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`)" +
            "|(?<keyword>\\b(?:var|let|const|if|else|for|while|do|break|continue|return|function|class|extends|import|export|default|new|this|super|true|false|null|undefined|typeof|instanceof|switch|case|try|catch|finally|throw|yield|await|async|void|delete|in)\\b)" +
            "|(?<number>\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)",
    )

// Mirrors the parser of the environment editor: a `#` comment only starts at a line start or after
// whitespace, single quotes are literal, double quotes take backslash escapes, and a quoted value
// may run over several lines (so a string swallowing line ends is intended).
private val ENV_PATTERN =
    Pattern.compile(
        "(?<comment>(?:^|(?<=[ \\t]))#[^\\n]*)" +
            "|(?<string>\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^']*')" +
            "|^[ \\t]*(?<key>[A-Za-z_][A-Za-z0-9_]*)(?=[ \\t]*=)",
        Pattern.MULTILINE,
    )
