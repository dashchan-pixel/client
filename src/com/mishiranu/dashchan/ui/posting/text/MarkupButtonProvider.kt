package com.mishiranu.dashchan.ui.posting.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Pair
import android.widget.Button
import chan.content.ChanMarkup
import com.mishiranu.dashchan.text.style.HeadingSpan
import com.mishiranu.dashchan.text.style.MonospaceSpan
import com.mishiranu.dashchan.text.style.OverlineSpan
import com.mishiranu.dashchan.text.style.ScriptSpan
import com.mishiranu.dashchan.util.FlagUtils
import com.mishiranu.dashchan.widget.ThemeEngine
import java.util.NoSuchElementException

open class MarkupButtonProvider private constructor(
    @JvmField val tag: Int,
    @JvmField val widthDp: Int,
    @JvmField val priority: Int,
    @JvmField val text: String,
    private val span: Any?,
) {
    fun createButton(
        context: Context,
        defStyleAttr: Int,
    ): Button =
        object : Button(context, null, defStyleAttr) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                OverlineSpan.draw(this, canvas)
            }
        }

    open fun getSpan(context: Context): Any? = span

    fun applyTextAndStyle(button: Button) {
        val span = getSpan(button.context)
        if (span != null) {
            val spannable = SpannableString(text)
            spannable.setSpan(span, 0, spannable.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            button.text = spannable
        } else {
            button.text = text
        }
    }

    companion object {
        private val PROVIDERS = ArrayList<MarkupButtonProvider>()

        @JvmStatic
        fun obtainSupportedAndDisplayedTags(
            markup: ChanMarkup?,
            boardName: String?,
            density: Float,
            maxButtonsWidth: Int,
            buttonMarginLeft: Int,
        ): Pair<Int, Int> {
            var handledButtons = 0
            var buttonsWidth = 0
            var supportedTags = 0
            var displayedTags = 0
            var i = 0
            while (handledButtons != PROVIDERS.size) {
                var futureTags = 0
                var futureWidth = buttonsWidth
                for (j in PROVIDERS.indices) {
                    val provider = PROVIDERS[j]
                    if (provider.priority == i) {
                        if ((markup != null && markup.safe().isTagSupported(boardName, provider.tag)) ||
                            provider.tag == ChanMarkup.TAG_QUOTE
                        ) {
                            var width = (provider.widthDp * density).toInt()
                            if (futureWidth > 0) {
                                width += buttonMarginLeft
                            }
                            futureWidth += width
                            futureTags = futureTags or provider.tag
                            supportedTags = supportedTags or provider.tag
                        }
                        handledButtons++
                    }
                }
                if (futureWidth <= maxButtonsWidth) {
                    buttonsWidth = futureWidth
                    displayedTags = displayedTags or futureTags
                }
                i++
            }
            return Pair(supportedTags, displayedTags)
        }

        @JvmStatic
        fun iterable(forDisplayedTags: Int): Iterable<MarkupButtonProvider> {
            return Iterable {
                object : MutableIterator<MarkupButtonProvider> {
                    private var displayedTags = forDisplayedTags
                    private var next: MarkupButtonProvider? = null
                    private var last = -1

                    override fun hasNext(): Boolean {
                        if (next == null) {
                            for (i in last + 1 until PROVIDERS.size) {
                                val provider = PROVIDERS[i]
                                if (FlagUtils.get(displayedTags, provider.tag)) {
                                    displayedTags = FlagUtils.set(displayedTags, provider.tag, false)
                                    last = i
                                    next = provider
                                    break
                                }
                            }
                        }
                        return next != null
                    }

                    override fun next(): MarkupButtonProvider {
                        if (!hasNext()) {
                            throw NoSuchElementException()
                        }
                        val provider = next!!
                        next = null
                        return provider
                    }

                    override fun remove(): Unit = throw UnsupportedOperationException()
                }
            }
        }

        init {
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_BOLD, 40, 0, "B", StyleSpan(Typeface.BOLD)))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_ITALIC, 40, 1, "I", StyleSpan(Typeface.ITALIC)))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_UNDERLINE, 40, 2, "U", UnderlineSpan()))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_OVERLINE, 40, 9, "O", OverlineSpan()))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_STRIKE, 40, 3, "S", StrikethroughSpan()))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_CODE, 40, 6, "#", MonospaceSpan(true)))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_ASCII_ART, 44, 10, "AA", MonospaceSpan(false)))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_HEADING, 44, 7, "H", HeadingSpan()))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_SUBSCRIPT, 44, 8, "SUB", ScriptSpan(false)))
            PROVIDERS.add(MarkupButtonProvider(ChanMarkup.TAG_SUPERSCRIPT, 44, 8, "SUP", ScriptSpan(true)))
            PROVIDERS.add(
                object : MarkupButtonProvider(ChanMarkup.TAG_SPOILER, 44, 5, "SP", null) {
                    override fun getSpan(context: Context): Any = BackgroundColorSpan(ThemeEngine.getTheme(context).spoiler)
                },
            )
            PROVIDERS.add(
                object : MarkupButtonProvider(ChanMarkup.TAG_QUOTE, 40, 4, ">", null) {
                    override fun getSpan(context: Context): Any? = null
                },
            )
        }
    }
}
