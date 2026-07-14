package com.mishiranu.dashchan.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.net.Uri
import android.os.SystemClock
import android.text.Layout
import android.text.Selection
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import chan.content.Chan.Companion.get
import chan.util.StringUtils.copyToClipboard
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.text.style.LinkSpan
import com.mishiranu.dashchan.text.style.OverlineSpan.Companion.draw
import com.mishiranu.dashchan.text.style.SpoilerSpan
import com.mishiranu.dashchan.util.ListViewUtils
import com.mishiranu.dashchan.util.ListViewUtils.getRootViewInList
import com.mishiranu.dashchan.util.NavigationUtils
import com.mishiranu.dashchan.util.NavigationUtils.handleUri
import com.mishiranu.dashchan.util.ResourceUtils.getDrawable
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import java.lang.ref.WeakReference
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * TextView with LinkSpan feedback ability without conflict with selection MovementMethod.
 * This class has method to start text selection directly.
 */
class CommentTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {
    private val deltaAttempts: Array<IntArray>
    private val touchSlop: Int

    private var selectionMode: SelectionMode? = null
    private var spanToClick: ClickableSpan? = null
    private var spanStartX = 0f
    private var spanStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastXYSet: Long = 0
    private var linesLimit = 0
    private var linesLimitAdditionalHeight = 0
    private var selectionPaddingView: View? = null
    private var useAdditionalPadding = false

    private var limitListener: LimitListener? = null
    private var spanStateListener: SpanStateListener? = null
    private var prepareToCopyListener: PrepareToCopyListener? = null
    private var linkListener: LinkListener? = null
    private var linkConfiguration: LinkConfiguration? = null
    private var extraButtons: MutableList<ExtraButton?>? = null
    private var spoilersEnabled = false

    private interface SelectionMode {
        val isActive: Boolean
        fun invalidateMenu()

        companion object {
            val INITIAL: SelectionMode = object : SelectionMode {
                override val isActive: Boolean
                    get() = false

                override fun invalidateMenu() {}
            }
        }
    }

    interface ClickableSpan {
        fun setClicked(clicked: Boolean)
    }

    interface LimitListener {
        fun onApplyLimit(limited: Boolean)
    }

    fun interface SpanStateListener {
        fun onSpanStateChanged(view: CommentTextView)
    }

    fun interface PrepareToCopyListener {
        fun onPrepareToCopy(view: CommentTextView, text: Spannable, start: Int, end: Int): String?
    }

    interface LinkListener {
        class Extra(@JvmField val chanName: String?, @JvmField val inBoardLink: Boolean) {
            companion object {
                @JvmField
                val EMPTY: Extra = Extra(null, false)
            }
        }

        fun onLinkClick(view: CommentTextView, uri: Uri, extra: Extra, confirmed: Boolean)
        fun onLinkLongClick(view: CommentTextView, uri: Uri, extra: Extra)
    }

    interface LinkConfiguration {
        val chanName: String?
        val boardName: String?
        val threadNumber: String?
    }

    class ExtraButton(
        internal val title: String?,
        internal val iconAttr: Int,
        internal val callback: Callback
    ) {
        class Text(val text: CharSequence, val start: Int, val end: Int) {
            fun toPreparedString(view: CommentTextView): String? {
                if (text is Spannable) {
                    return view.getPartialCommentString(text, start, end)
                } else {
                    return toString()
                }
            }

            override fun toString(): String {
                return if (end > start) text.toString().substring(start, end) else ""
            }
        }

        fun interface Callback {
            fun handle(view: CommentTextView?, text: Text?, click: Boolean): Boolean
        }
    }

    fun setLimitListener(listener: LimitListener?) {
        limitListener = listener
    }

    fun setSpanStateListener(listener: SpanStateListener?) {
        spanStateListener = listener
    }

    fun setPrepareToCopyListener(listener: PrepareToCopyListener?) {
        prepareToCopyListener = listener
    }

    fun setLinkListener(listener: LinkListener?, configuration: LinkConfiguration?) {
        linkListener = listener
        linkConfiguration = configuration
    }

    fun setExtraButtons(extraButtons: MutableList<ExtraButton?>?) {
        this.extraButtons = extraButtons
    }

    private fun getLinkListener(): LinkListener {
        return (if (linkListener != null) linkListener else CommentTextView.Companion.DEFAULT_LINK_LISTENER)!!
    }

    fun setSubjectAndComment(subject: CharSequence?, comment: CharSequence?) {
        val hasSubject = !isEmpty(subject)
        val hasComment = !isEmpty(comment)
        if (hasComment && comment is Spanned) {
            val spoilerSpans =
                comment.getSpans<SpoilerSpan>(0, comment.length, SpoilerSpan::class.java)
            val enabled = spoilersEnabled
            for (spoilerSpan in spoilerSpans) {
                spoilerSpan.setEnabled(enabled)
            }
        }
        if (hasSubject) {
            val spannable = SpannableStringBuilder()
            spannable.append(subject)
            val length = spannable.length
            spannable.setSpan(
                TypefaceSpan("sans-serif-light"),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                RelativeSizeSpan(4f / 3f),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (hasComment) {
                spannable.append("\n\n")
                spannable.setSpan(
                    RelativeSizeSpan(0.75f),
                    length,
                    length + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.append(comment)
            }
            setText(spannable)
        } else if (hasComment) {
            setText(comment)
        } else {
            setText(null)
        }
    }

    private val spannedText: Spanned?
        get() {
            val text = getText()
            return if (text is Spanned) text else null
        }

    private val spannableText: Spannable?
        get() {
            val text = getText()
            return if (text is Spannable) text else null
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        var limited = false
        if (linesLimit > 0) {
            val layout = getLayout()
            val count = layout.getLineCount()
            if (count > linesLimit) {
                val removeHeight = layout.getLineTop(count) - layout.getLineTop(linesLimit)
                if (removeHeight > linesLimitAdditionalHeight) {
                    if (!isSelectionMode()) {
                        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() - removeHeight)
                    }
                    limited = true
                }
            }
        }
        if (limitListener != null) {
            limitListener!!.onApplyLimit(limited)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        // Cause mEditor.prepareCursorControllers() call to enabled selection controllers
        // mSelectionControllerEnabled can be set to false before view laid out
        setCursorVisible(false)
        setCursorVisible(true)
    }

    fun setSpoilersEnabled(enabled: Boolean) {
        // Must invalidate text after changing this field
        spoilersEnabled = enabled
    }

    override fun setCustomSelectionActionModeCallback(actionModeCallback: ActionMode.Callback?) {
        throw UnsupportedOperationException()
    }

    override fun setTextIsSelectable(selectable: Boolean) {
        throw UnsupportedOperationException()
    }

    fun isSelectionMode(): Boolean {
        return selectionMode != null
    }

    private fun setSelectionMode(selectionMode: SelectionMode?) {
        val oldSelection = isSelectionMode()
        this.selectionMode = selectionMode
        val newSelection = isSelectionMode()
        if (!newSelection || selectionMode!!.isActive) {
            // Stop selection fixing when selection is disabled or becomes active
            restoreSelectionRunnable = null
        }
        if (oldSelection != newSelection) {
            updateUseAdditionalPadding(false)
            requestLayout()
            if (!newSelection && isFocused()) {
                val rootView = getRootViewInList(this)
                if (rootView != null) {
                    val listView = rootView.getParent() as View?
                    if (listView != null) {
                        // Move focus from this view to list
                        listView.requestFocus()
                    }
                }
            }
            requestLayout()
        }
    }

    private fun removeSelection() {
        val text = this.spannableText
        if (text != null) {
            Selection.removeSelection(text)
        }
    }

    private var restoreSelectionRunnable: Runnable? = null

    private val resetSelectionRunnable = Runnable {
        if (isSelectionMode() && !selectionMode!!.isActive) {
            restoreSelectionRunnable = null
            removeSelection()
            setSelectionMode(null)
        }
    }

    private fun sendFakeMotionEvent(action: Int, x: Int, y: Int) {
        val motionEvent =
            MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        motionEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        onTouchEvent(motionEvent)
        motionEvent.recycle()
    }

    private fun startSelection(x: Int, y: Int, start: Int, end: Int) {
        var x = x
        var y = y
        var start = start
        var end = end
        val text = this.spannableText
        if (text == null) {
            return
        }
        val length = text.length
        val layout = getLayout()
        if (x != Int.MAX_VALUE && y != Int.MAX_VALUE &&
            (start < 0 || end < 0 || end > length || start >= end)
        ) {
            start = 0
            end = text.length
            val lx = x - getTotalPaddingLeft()
            val ly = y - getTotalPaddingTop()
            if (lx >= 0 && ly >= 0 && lx < getWidth() - getTotalPaddingRight() && ly < getHeight() - getTotalPaddingBottom()) {
                val offset =
                    layout.getOffsetForHorizontal(layout.getLineForVertical(ly), lx.toFloat())
                if (offset >= 0 && offset < length) {
                    for (i in offset downTo 0) {
                        if (text.get(i) == '\n') {
                            start = i + 1
                            break
                        }
                    }
                    for (i in offset..<length) {
                        if (text.get(i) == '\n') {
                            end = i
                            break
                        }
                    }
                    if (end > start) {
                        val part = text.subSequence(start, end).toString()
                        val matcher: Matcher = LIST_PATTERN.matcher(part)
                        if (matcher.find()) {
                            start += matcher.group().length
                        }
                    }
                }
            }
        }
        if (end <= start || start < 0) {
            start = 0
            end = text.length
        }
        x = getTotalPaddingLeft()
        y = getTotalPaddingRight()
        setSelectionMode(SelectionMode.INITIAL)
        val finalX = x
        val finalY = y
        val finalStart = start
        val finalEnd = end
        post(Runnable {
            removeCallbacks(resetSelectionRunnable)
            val newText = this.spannableText
            if (newText == null) {
                resetSelectionRunnable.run()
                return@Runnable
            }
            val max = newText.length
            val restoreSelectionRunnable = Runnable {
                Selection.setSelection(
                    newText,
                    min(finalStart, max), min(finalEnd, max)
                )
            }
            // restoreSelectionRunnable can be nullified during sending motion event
            this.restoreSelectionRunnable = restoreSelectionRunnable
            sendFakeMotionEvent(MotionEvent.ACTION_DOWN, finalX, finalY)
            sendFakeMotionEvent(MotionEvent.ACTION_UP, finalX, finalY)
            sendFakeMotionEvent(MotionEvent.ACTION_DOWN, finalX, finalY)
            sendFakeMotionEvent(MotionEvent.ACTION_UP, finalX, finalY)
            restoreSelectionRunnable.run()
            postDelayed(resetSelectionRunnable, 500)
        })
    }

    fun startSelection() {
        var x = Int.MAX_VALUE
        var y = Int.MAX_VALUE
        if (SystemClock.elapsedRealtime() - lastXYSet <= this.preferredDoubleTapTimeout) {
            x = lastX.toInt()
            y = lastY.toInt()
        }
        startSelection(x, y, -1, -1)
    }

    fun startSelection(start: Int, end: Int) {
        startSelection(Int.MAX_VALUE, Int.MAX_VALUE, start, end)
    }

    private fun getPartialCommentString(text: Spannable, start: Int, end: Int): String? {
        return if (prepareToCopyListener != null) prepareToCopyListener!!
            .onPrepareToCopy(this@CommentTextView, text, start, end) else text.subSequence(
            start,
            end
        ).toString()
    }

    private class CustomSelectionCallback(textView: CommentTextView?) : ActionMode.Callback {
        private val textView: WeakReference<CommentTextView>
        private var currentActionMode: ActionMode? = null

        init {
            this.textView = WeakReference<CommentTextView>(textView)
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val selectionMode: SelectionMode = object : SelectionMode {
                override val isActive: Boolean
                    get() = true

                override fun invalidateMenu() {
                    // Call "onPrepareActionMode" instead of "invalidate"
                    // to fix action mode resizing bug in Android 5
                    onPrepareActionMode(mode, menu)
                }
            }
            val textView: CommentTextView = this.textView.get()!!
            textView.setSelectionMode(selectionMode)
            currentActionMode = mode
            val floating = mode.getType() == ActionMode.TYPE_FLOATING
            // Only "cut" menu item uses this order "1" which doesn't present in non-editable TextView
            textView.onCreateSelectionMenu(menu, if (floating) 1 else 0)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean {
            if (currentActionMode === mode) {
                textView.get()!!.onPrepareSelectionMenu(menu)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            if (currentActionMode === mode) {
                currentActionMode = null
                textView.get()!!.setSelectionMode(null)
            }
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val result = textView.get()!!.onSelectionItemClicked(item.getItemId())
            if (result) {
                mode.finish()
            }
            return result
        }
    }

    private fun getExtraButton(index: Int): ExtraButton? {
        return if (extraButtons != null && index < extraButtons!!.size && index < EXTRA_BUTTON_IDS.size)
            extraButtons!!.get(index)
        else
            null
    }

    private val extraButtonText: ExtraButton.Text
        get() {
            val start = getSelectionStart()
            val end = getSelectionEnd()
            val min = max(0, min(start, end))
            val max = max(0, max(start, end))
            return ExtraButton.Text(
                getText(),
                min,
                max
            )
        }

    private fun onCreateSelectionMenu(menu: Menu, order: Int) {
        for (i in EXTRA_BUTTON_IDS.indices) {
            val extraButton = getExtraButton(i)
            if (extraButton != null) {
                menu.add(0, EXTRA_BUTTON_IDS[i], order, extraButton.title)
                    .setIcon(getDrawable(getContext(), extraButton.iconAttr, 0))
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            }
        }
    }

    private fun onPrepareSelectionMenu(menu: Menu) {
        val text =
            this.extraButtonText
        for (i in EXTRA_BUTTON_IDS.indices) {
            val extraButton = getExtraButton(i)
            if (extraButton != null) {
                menu.findItem(EXTRA_BUTTON_IDS[i]).setVisible(
                    extraButton.callback
                        .handle(this@CommentTextView, text, false)
                )
            }
        }
    }

    private fun onSelectionItemClicked(id: Int): Boolean {
        val text =
            this.extraButtonText
        when (id) {
            android.R.id.copy -> {
                if (text.text is Spannable) {
                    copyToClipboard(
                        getContext(),
                        getPartialCommentString(text.text, text.start, text.end)
                    )
                }
                return true
            }
        }
        for (i in EXTRA_BUTTON_IDS.indices) {
            if (EXTRA_BUTTON_IDS[i] == id) {
                val extraButton = getExtraButton(i)
                if (extraButton != null) {
                    extraButton.callback.handle(this@CommentTextView, text, true)
                }
                return true
            }
        }
        return false
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        return super.onTextContextMenuItem(id)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (isSelectionMode()) {
            if (restoreSelectionRunnable != null) {
                // Fix selection during selection mode initialization
                restoreSelectionRunnable!!.run()
            }
            selectionMode!!.invalidateMenu()
        }
    }

    fun setLinesLimit(limit: Int, additionalHeight: Int) {
        this.linesLimit = limit
        this.linesLimitAdditionalHeight = additionalHeight
        requestLayout()
    }

    override fun setMaxLines(maxLines: Int) {
        throw UnsupportedOperationException()
    }

    override fun setMaxHeight(maxHeight: Int) {
        throw UnsupportedOperationException()
    }

    fun bindSelectionPaddingView(selectionPaddingView: View?) {
        if (this.selectionPaddingView != null) {
            this.selectionPaddingView!!.setVisibility(GONE)
        }
        val force = this.selectionPaddingView !== selectionPaddingView
        this.selectionPaddingView = selectionPaddingView
        updateUseAdditionalPadding(force)
    }

    val selectionPadding: Int
        get() = if (selectionPaddingView != null) max(
            selectionPaddingView!!.getLayoutParams().height,
            0
        ) else 0

    private fun updateUseAdditionalPadding(force: Boolean) {
        val useAdditionalPadding = selectionPaddingView != null && isSelectionMode()
        if (this.useAdditionalPadding != useAdditionalPadding || force) {
            if (selectionPaddingView != null) {
                selectionPaddingView!!.setVisibility(if (useAdditionalPadding) VISIBLE else GONE)
            }
            this.useAdditionalPadding = useAdditionalPadding
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        // Ignore scrolling
    }

    override fun scrollBy(x: Int, y: Int) {
        // Ignore scrolling
    }

    override fun hasExplicitFocusable(): Boolean {
        return super.hasExplicitFocusable() && isSelectionMode()
    }

    override fun hasFocusable(): Boolean {
        return super.hasFocusable() && isSelectionMode()
    }

    val preferredDoubleTapTimeout: Long
        get() = max(ViewConfiguration.getDoubleTapTimeout(), 500).toLong()

    private fun createUri(uriString: String?): Uri? {
        val configuration = linkConfiguration
        val chanName = if (configuration != null) configuration.chanName else null
        if (chanName != null) {
            val chan = get(chanName)
            return chan.locator.validateClickedUriString(
                uriString,
                configuration!!.boardName, configuration.threadNumber
            )
        } else {
            return Uri.parse(uriString)
        }
    }

    private val layoutPosition = Point()

    private fun fillLayoutPosition(x: Float, y: Float): Point {
        var ix = x.toInt()
        var iy = y.toInt()
        ix -= getTotalPaddingLeft()
        iy -= getTotalPaddingTop()
        ix += getScrollX()
        iy += getScrollY()
        layoutPosition.set(ix, iy)
        return layoutPosition
    }

    private fun checkAcceptSpan(x: Float, y: Float, layoutPosition: Point?): Boolean {
        var layoutPosition = layoutPosition
        val insideTouchSlop = abs(x - spanStartX) <= touchSlop && abs(y - spanStartY) <= touchSlop
        if (insideTouchSlop) {
            return true
        }
        if (layoutPosition == null) {
            layoutPosition = fillLayoutPosition(x, y)
        }
        val layout = getLayout()
        val text = this.spannedText
        if (layout != null && text != null) {
            val spans = findSpansToClick(layout, text, Any::class.java, layoutPosition)
            for (span in spans) {
                if (span === spanToClick) {
                    return true
                }
            }
        }
        return false
    }

    private val linkLongClickRunnable = Runnable {
        if (spanToClick is LinkSpan) {
            val linkSpan = spanToClick as LinkSpan
            val uri = createUri(linkSpan.uriString)
            setSpanToClick(null, lastX, lastY)
            if (uri != null) {
                val chanName = if (linkConfiguration != null) linkConfiguration!!.chanName else null
                val extra = LinkListener.Extra(chanName, linkSpan.inBoardLink())
                getLinkListener().onLinkLongClick(this@CommentTextView, uri, extra)
            }
        }
    }

    init {
        ThemeEngine.Companion.applyStyle(this)
        val density = obtainDensity(this)
        val delta = (RING_RADIUS * density).toInt()
        deltaAttempts = Array(1 + RINGS * BASE_POINTS.size) { IntArray(2) }
        deltaAttempts[0][0] = 0
        deltaAttempts[0][1] = 0
        var add = 1
        for (r in 0..<RINGS) {
            for (i in BASE_POINTS.indices) {
                for (j in BASE_POINTS[i]!!.indices) {
                    deltaAttempts[i + add][j] = ((r + 1) * delta * BASE_POINTS[i]!![j]).toInt()
                }
            }
            add += BASE_POINTS.size
        }
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop()
        super.setCustomSelectionActionModeCallback(CustomSelectionCallback(this))
        super.setTextIsSelectable(true)
    }

    private fun handleSpanClick() {
        if (spanToClick is LinkSpan) {
            val linkSpan = spanToClick as LinkSpan
            val uri = createUri(linkSpan.uriString)
            if (uri != null) {
                val chanName = if (linkConfiguration != null) linkConfiguration!!.chanName else null
                val extra = LinkListener.Extra(chanName, linkSpan.inBoardLink())
                getLinkListener().onLinkClick(this, uri, extra, false)
            }
        } else if (spanToClick is SpoilerSpan) {
            val spoilerSpan = (spanToClick as SpoilerSpan)
            spoilerSpan.isVisible = !spoilerSpan.isVisible
            val listener = spanStateListener
            if (listener != null) {
                post(Runnable { listener.onSpanStateChanged(this@CommentTextView) })
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled()) {
            return false
        }
        if (isSelectionMode()) {
            return super.onTouchEvent(event)
        }
        val action = event.getAction()
        val x = event.getX()
        val y = event.getY()
        val layoutPosition = fillLayoutPosition(x, y)
        lastX = x
        lastY = y
        lastXYSet = SystemClock.elapsedRealtime()
        if (action != MotionEvent.ACTION_DOWN && spanToClick != null) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (!checkAcceptSpan(x, y, layoutPosition)) {
                    removeCallbacks(linkLongClickRunnable)
                    setSpanToClick(null, x, y)
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(linkLongClickRunnable)
                if (action == MotionEvent.ACTION_UP) {
                    handleSpanClick()
                }
                setSpanToClick(null, x, y)
            }
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) {
            val layout = getLayout()
            val text = this.spannedText
            if (layout != null && text != null) {
                if (spanToClick != null) {
                    setSpanToClick(null, x, y)
                }
                // 1st priority: show spoiler
                var spoilerSpans: ArrayList<SpoilerSpan>? = null
                if (spoilersEnabled) {
                    spoilerSpans = findSpansToClick(
                        layout,
                        text,
                        SpoilerSpan::class.java,
                        layoutPosition
                    )
                    for (span in spoilerSpans) {
                        if (!span.isVisible) {
                            setSpanToClick(span, x, y)
                            return true
                        }
                    }
                }
                // 2nd priority: open link
                val linkSpans =
                    findSpansToClick(layout, text, LinkSpan::class.java, layoutPosition)
                if (!linkSpans.isEmpty()) {
                    setSpanToClick(linkSpans.get(0), x, y)
                    postDelayed(
                        linkLongClickRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    return true
                }
                // 3rd priority: hide spoiler
                if (spoilersEnabled) {
                    for (span in spoilerSpans!!) {
                        if (span.isVisible) {
                            setSpanToClick(span, x, y)
                            invalidateSpanToClick()
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun setSpanToClick(span: ClickableSpan?, x: Float, y: Float) {
        if (spanToClick != null) {
            spanToClick!!.setClicked(false)
            invalidateSpanToClick()
        }
        spanToClick = span
        if (spanToClick != null) {
            spanToClick!!.setClicked(true)
            invalidateSpanToClick()
        }
        spanStartX = x
        spanStartY = y
    }

    private fun <T : Any> findSpansToClick(
        layout: Layout,
        spanned: Spanned,
        type: Class<T>,
        layoutPosition: Point
    ): ArrayList<T> {
        val result = ArrayList<T>()
        // Find spans around touch point for better click treatment
        for (deltaAttempt in deltaAttempts) {
            val startX = layoutPosition.x + deltaAttempt[0]
            val startY = layoutPosition.y + deltaAttempt[1]
            val spans = findSpansToClickSingle(layout, spanned, type, startX, startY)
            if (spans != null) {
                for (span in spans) {
                    if (span != null) {
                        result.add(span)
                    }
                }
            }
        }
        return result
    }

    private fun <T : Any> findSpansToClickSingle(
        layout: Layout,
        spanned: Spanned,
        type: Class<T>,
        x: Int,
        y: Int
    ): Array<T?>? {
        val line = layout.getLineForVertical(y)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val spans = spanned.getSpans(off, off, type) as Array<T?>?
        if (spans != null) {
            for (i in spans.indices) {
                val end = spanned.getSpanEnd(spans[i])
                if (off >= end) {
                    spans[i] = null
                }
            }
        }
        return spans
    }

    private fun invalidateSpanToClick() {
        if (spanToClick == null) {
            return
        }
        val text = this.spannableText
        if (text != null) {
            val start = text.getSpanStart(spanToClick)
            val end = text.getSpanEnd(spanToClick)
            if (start >= 0 && end >= start) {
                val watcher: SpanWatcher? = getSpanWatcher(text)
                if (watcher != null) {
                    // Notify span changed to redraw it
                    watcher.onSpanChanged(text, spanToClick, start, end, start, end)
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        draw(this, canvas)
    }

    fun invalidateAllSpans() {
        val text = this.spannableText
        if (text != null) {
            val spans = text.getSpans<ClickableSpan?>(0, text.length, ClickableSpan::class.java)
            if (spans != null && spans.size > 0) {
                val watcher: SpanWatcher? = getSpanWatcher(text)
                for (span in spans) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    watcher!!.onSpanChanged(text, span, start, end, start, end)
                }
            }
        }
    }

    class RecyclerKeeper(private val recyclerView: RecyclerView) : AdapterDataObserver(), Runnable {
        interface Holder {
            val commentTextView: CommentTextView
        }

        private var postCount = 0

        private var text: String? = null
        private var selectionStart = 0
        private var selectionEnd = 0
        private var position = 0

        override fun onChanged() {
            var i = 0
            val count = recyclerView.getChildCount()
            while (i < count) {
                val view = recyclerView.getChildAt(i)
                val holder = ListViewUtils.getViewHolder(view, Holder::class.java)
                if (holder != null) {
                    val textView = holder.commentTextView
                    if (textView.isSelectionMode()) {
                        val position = recyclerView.getChildLayoutPosition(view)
                        if (position >= 0) {
                            this.position = position
                            text = textView.getText().toString()
                            selectionStart = textView.getSelectionStart()
                            selectionEnd = textView.getSelectionEnd()
                            postCount = 2
                            recyclerView.removeCallbacks(this)
                            recyclerView.post(this)
                        }
                        break
                    }
                }
                i++
            }
        }

        override fun run() {
            if (postCount-- > 0) {
                recyclerView.post(this)
                return
            }
            val childCount = recyclerView.getChildCount()
            if (position >= 0 && childCount > 0) {
                val index =
                    position - recyclerView.getChildLayoutPosition(recyclerView.getChildAt(0))
                if (index >= 0 && index < childCount) {
                    val view = recyclerView.getChildAt(index)
                    val holder = ListViewUtils.getViewHolder(view, Holder::class.java)
                    if (holder != null) {
                        val textView = holder.commentTextView
                        val text = textView.getText().toString()
                        if (text == this.text) {
                            textView.startSelection(selectionStart, selectionEnd)
                            position = -1
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val BASE_POINTS: Array<DoubleArray?>
        private const val RING_RADIUS = 6
        private const val RINGS = 3

        init {
            BASE_POINTS = arrayOfNulls<DoubleArray>(8)
            BASE_POINTS[0] = doubleArrayOf(-1.0, 0.0)
            BASE_POINTS[1] = doubleArrayOf(0.0, -1.0)
            BASE_POINTS[2] = doubleArrayOf(1.0, 0.0)
            BASE_POINTS[3] = doubleArrayOf(0.0, 1.0)
            val sqrth2 = sqrt(0.5)
            BASE_POINTS[4] = doubleArrayOf(-sqrth2, -sqrth2)
            BASE_POINTS[5] = doubleArrayOf(sqrth2, -sqrth2)
            BASE_POINTS[6] = doubleArrayOf(-sqrth2, sqrth2)
            BASE_POINTS[7] = doubleArrayOf(sqrth2, sqrth2)
        }

        private val DEFAULT_LINK_LISTENER: LinkListener = object : LinkListener {
            override fun onLinkClick(view: CommentTextView, uri: Uri, extra: LinkListener.Extra, confirmed: Boolean) {
                handleUri(view.getContext(), extra.chanName, uri, NavigationUtils.BrowserType.AUTO)
            }

            override fun onLinkLongClick(view: CommentTextView, uri: Uri, extra: LinkListener.Extra) {
            }
        }

        private val LIST_PATTERN: Pattern = Pattern.compile("^(?:(?:\\d+[.)]|[\u2022-]) |>(?!>) ?)")

        private val EXTRA_BUTTON_IDS =
            intArrayOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)

        private fun getSpanWatcher(text: Spannable): SpanWatcher? {
            val watchers = text.getSpans<SpanWatcher?>(0, text.length, SpanWatcher::class.java)
            if (watchers != null && watchers.size > 0) {
                for (watcher in watchers) {
                    if (watcher.javaClass.getName() == "android.widget.TextView\$ChangeWatcher") {
                        return watcher
                    }
                }
            }
            return null
        }
    }
}
