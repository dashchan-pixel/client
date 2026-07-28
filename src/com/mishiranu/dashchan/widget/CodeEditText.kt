package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.ScrollView
import com.mishiranu.dashchan.util.GraphicsUtils
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
    private var isTouchDown = false

    /** Siblings hidden by [updateExpansionState], to be restored when the editor collapses. */
    private val hiddenSiblings = ArrayList<View>()

    /** This field's own margins, dropped while it is expanded and given back when it collapses. */
    private var collapsedMargins: Rect? = null

    /** The container's padding, dropped and given back the same way as [collapsedMargins]. */
    private var collapsedContainerPadding: Rect? = null

    /** This field's own padding, widened while it is expanded and given back when it collapses. */
    private var collapsedPadding: Rect? = null

    /**
     * True while the user is working the selection — a finger is down on the text, or a range is
     * selected so a handle may be dragging it.
     *
     * The caret context below is meant for typing, where the selection is collapsed. During a drag
     * it fights the gesture: forcing an extra scroll moves the text out from under the finger, the
     * next move event therefore resolves to a different offset, which scrolls again — and the
     * request that leaks to the enclosing [ScrollView] makes the whole dialog jitter.
     */
    private val isSelecting: Boolean
        get() = isTouchDown || hasSelection()

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

        // Which palette applies follows the surface this editor is drawn on, not the system's night
        // mode: themes are picked by name in the app's own settings, so a dark theme is routinely in
        // effect while the system is in day mode (and with "follow system" on, the night slot may
        // even hold a light theme). The colour the theme gave the unhighlighted text says which it
        // is — light text means a dark surface. Going by the system flag put the navy-and-green day
        // palette on dark backgrounds, where it read as nearly black.
        val onDarkSurface = GraphicsUtils.isLight(currentTextColor)
        val keywordColor = if (onDarkSurface) KEYWORD_DARK else KEYWORD_LIGHT
        val stringColor = if (onDarkSurface) STRING_DARK else STRING_LIGHT
        val commentColor = if (onDarkSurface) COMMENT_DARK else COMMENT_LIGHT
        val numberColor = if (onDarkSurface) NUMBER_DARK else NUMBER_LIGHT

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
        ViewTreeObserver.OnGlobalLayoutListener {
            if (isExpanded) {
                updateExpandedHeight()
            }
        }

    /**
     * Keeps the expanded field exactly as tall as the scroller holding it. The height it is expanded
     * to is the display's — before the first layout nothing knows the real one, and asking for more
     * than the dialog has is what makes the dialog hand out everything it has — and is corrected here
     * once the scroller has been measured, in both directions, so the field also follows a keyboard
     * that grows or shrinks.
     */
    private fun updateExpandedHeight() {
        val container = parent as? ViewGroup ?: return
        val scrollView = container.parent as? ScrollView ?: return
        val available = scrollView.height - container.paddingTop - container.paddingBottom
        if (available > 0 && layoutParams.height != available) {
            layoutParams.height = available
            requestLayout()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnApplyWindowInsetsListener { _, insets ->
            isKeyboardOpen = insets.isVisible(WindowInsets.Type.ime())
            updateExpansionState()
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

        val container = parent as? ViewGroup
        if (container != null) {
            if (shouldExpand) {
                // Only the siblings this view actually hides are remembered, so collapsing restores
                // exactly them. A sibling the dialog had already hidden — an option that doesn't apply
                // to what the user picked, like the per-post checkbox of a comment command — must stay
                // hidden; blanket VISIBLE resurrected it.
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child != this && child.visibility == View.VISIBLE) {
                        child.visibility = View.GONE
                        hiddenSiblings.add(child)
                    }
                }
                collapsedContainerPadding =
                    Rect(
                        container.paddingLeft,
                        container.paddingTop,
                        container.paddingRight,
                        container.paddingBottom,
                    )
                container.setPadding(0, 0, 0, 0)
            } else {
                for (child in hiddenSiblings) {
                    child.visibility = View.VISIBLE
                }
                hiddenSiblings.clear()
                collapsedContainerPadding?.let {
                    container.setPadding(it.left, it.top, it.right, it.bottom)
                }
                collapsedContainerPadding = null
            }
        }
        applyExpandedLayout(shouldExpand)

        onExpandedStateChanged?.invoke(shouldExpand)
    }

    /**
     * Gives the expanded field the whole screen to grow into — [updateExpandedHeight] cuts it back to
     * what the dialog really has — and takes its margins away with it, since a fullscreen editor is
     * not laid out inside a form any more.
     *
     * The margins become padding rather than being dropped: the first and the last character of a line
     * have to be tappable, and a margin is outside the field, where a tap lands on nothing. Padding is
     * inside it, so a tap next to the text still puts the caret at the end of the line it belongs to.
     */
    private fun applyExpandedLayout(expand: Boolean) {
        if (expand) {
            collapsedPadding = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
            val horizontal = (EXPANDED_HORIZONTAL_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(max(paddingLeft, horizontal), paddingTop, max(paddingRight, horizontal), paddingBottom)
        } else {
            collapsedPadding?.let { setPadding(it.left, it.top, it.right, it.bottom) }
            collapsedPadding = null
        }
        val lp = layoutParams ?: return
        if (lp is ViewGroup.MarginLayoutParams) {
            if (expand) {
                collapsedMargins = Rect(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
                lp.setMargins(0, 0, 0, 0)
            } else {
                collapsedMargins?.let { lp.setMargins(it.left, it.top, it.right, it.bottom) }
                collapsedMargins = null
            }
        }
        lp.height =
            if (expand) {
                resources.displayMetrics.heightPixels
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        layoutParams = lp
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
        if (isSelecting) {
            return changed
        }
        return keepCaretContextVisible(offset) || changed
    }

    override fun bringPointIntoView(
        offset: Int,
        requestRectangleVisible: Boolean,
    ): Boolean {
        val changed = super.bringPointIntoView(offset, requestRectangleVisible)
        if (isSelecting) {
            return changed
        }
        return keepCaretContextVisible(offset) || changed
    }

    override fun getFocusedRect(r: Rect) {
        super.getFocusedRect(r)
        if (!isSelecting) {
            addContextBelow(r)
        }
    }

    override fun requestRectangleOnScreen(
        rectangle: Rect,
        immediate: Boolean,
    ): Boolean {
        if (isSelecting) {
            return super.requestRectangleOnScreen(rectangle, immediate)
        }
        requestRect.set(rectangle)
        addContextBelow(requestRect)
        return super.requestRectangleOnScreen(requestRect, immediate)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchDown = true
                if (isTextScrollable) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            // Cleared before super so that the caret placed by this very event still gets its
            // context — only the drag in between is left alone.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchDown = false
                if (isTextScrollable) {
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

        /** How far from the screen edges the expanded field keeps its text, in dp. */
        private const val EXPANDED_HORIZONTAL_PADDING_DP = 12f

        // Both palettes are kept above 4.5:1 against the surface they belong to, comments aside:
        // those are deliberately the quietest token of the four.
        private val KEYWORD_DARK = 0xffff9d62.toInt()
        private val STRING_DARK = 0xffa5d6a7.toInt()
        private val COMMENT_DARK = 0xffa0a0a0.toInt()
        private val NUMBER_DARK = 0xff90caf9.toInt()

        private val KEYWORD_LIGHT = 0xff000080.toInt()
        private val STRING_LIGHT = 0xff008000.toInt()
        private val COMMENT_LIGHT = 0xff808080.toInt()
        private val NUMBER_LIGHT = 0xff0000ff.toInt()
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
