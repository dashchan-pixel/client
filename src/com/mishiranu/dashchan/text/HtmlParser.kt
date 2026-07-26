package com.mishiranu.dashchan.text

import android.graphics.Color
import chan.util.StringUtils
import com.mishiranu.dashchan.content.model.PostNumber
import org.ccil.cowan.tagsoup.HTMLSchema
import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import java.io.StringReader
import java.util.regex.Pattern

class HtmlParser<E, D, S : HtmlParser.SpanProvider<E>> private constructor(
    source: String,
    markup: Markup<E, D, S>?,
    private val parsingMode: Mode,
    private val threadNumber: String?,
    private val originalPostNumber: PostNumber?,
    private val extra: E?,
) : ContentHandler {
    interface Markup<E, D, S : SpanProvider<E>> {
        fun onBeforeTagStart(
            parser: HtmlParser<E, D, S>,
            builder: StringBuilder,
            tagName: String,
            attributes: Attributes,
            tagData: TagData,
        ): D?

        fun onTagStart(
            parser: HtmlParser<E, D, S>,
            builder: StringBuilder,
            tagName: String,
            attributes: Attributes,
            obj: D?,
        )

        fun onTagEnd(
            parser: HtmlParser<E, D, S>,
            builder: StringBuilder,
            tagName: String,
        )

        fun onListLineStart(
            parser: HtmlParser<E, D, S>,
            builder: StringBuilder,
            ordered: Boolean,
            line: Int,
        ): Int

        fun onCutBlock(
            parser: HtmlParser<E, D, S>,
            builder: StringBuilder,
        )

        fun initSpanProvider(parser: HtmlParser<E, D, S>): S?
    }

    interface SpanProvider<E> {
        fun transformBuilder(
            parser: HtmlParser<E, *, *>,
            builder: StringBuilder,
        ): CharSequence?
    }

    class TagData(
        @JvmField var block: Boolean,
        @JvmField var spaced: Boolean,
        preformatted: Boolean,
    ) {
        enum class Preformatted { UNDEFINED, ENABLED, DISABLED }

        @JvmField var preformatted: Preformatted =
            if (preformatted) Preformatted.ENABLED else Preformatted.UNDEFINED
    }

    private enum class Mode { SPANIFY, CLEAR, UNMARK }

    private val source: String = source.replace("&#10;", "\n")
    private val builder = StringBuilder()

    @Suppress("UNCHECKED_CAST")
    private val markup: Markup<E, D, S> = markup ?: IDLE_MARKUP as Markup<E, D, S>
    private val spanProvider: S? =
        if (isSpanifyMode || isUnmarkMode) this.markup.initSpanProvider(this) else null

    fun convert(): CharSequence {
        val parser = Parser()
        try {
            parser.setProperty(Parser.schemaProperty, SCHEMA)
        } catch (e: SAXNotRecognizedException) {
            throw RuntimeException(e)
        } catch (e: SAXNotSupportedException) {
            throw RuntimeException(e)
        }
        parser.contentHandler = this
        val builder = this.builder
        try {
            parser.parse(InputSource(StringReader(source)))
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        removeBlockLastWhitespaces()
        var start = 0
        var end = 0
        var substring = false
        var found = false
        val length = builder.length
        for (i in 0 until length) {
            if (builder[i] != '\n') {
                start = i
                found = true
                substring = substring or (i > 0)
                break
            }
        }
        if (!found) {
            return ""
        }
        for (i in length - 1 downTo start) {
            if (builder[i] != '\n') {
                end = i + 1
                substring = substring or (end < length)
                break
            }
        }
        if (isSpanifyMode || isClearMode) {
            // Replace non-breaking spaces with regular spaces
            for (i in 0 until length) {
                if (builder[i] == '\u00a0') {
                    builder.setCharAt(i, ' ')
                }
            }
        }
        if (isSpanifyMode && spanProvider != null) {
            val charSequence = spanProvider.transformBuilder(this, builder) ?: return ""
            return if (substring) charSequence.subSequence(start, end) else charSequence
        }
        return if (substring) builder.substring(start, end) else builder
    }

    val isSpanifyMode: Boolean
        get() = parsingMode == Mode.SPANIFY

    val isClearMode: Boolean
        get() = parsingMode == Mode.CLEAR

    val isUnmarkMode: Boolean
        get() = parsingMode == Mode.UNMARK

    fun getBuilder(): StringBuilder = builder

    fun getThreadNumber(): String? = threadNumber

    fun getOriginalPostNumber(): PostNumber? = originalPostNumber

    fun getExtra(): E? = extra

    fun getSpanProvider(): S? = spanProvider

    override fun setDocumentLocator(locator: Locator?) {}

    override fun startDocument() {}

    override fun endDocument() {}

    override fun startPrefixMapping(
        prefix: String?,
        uri: String?,
    ) {}

    override fun endPrefixMapping(prefix: String?) {}

    private class PositiveStateStack {
        private var state: BooleanArray? = null
        private var position = -1

        fun push(state: Boolean) {
            if (state || position >= 0) {
                position++
                val array = this.state
                if (array == null) {
                    this.state = BooleanArray(4)
                } else if (position == array.size) {
                    this.state = array.copyOf(array.size * 2)
                }
                this.state!![position] = state
            }
        }

        fun pop(): Boolean = position >= 0 && state!![position--]

        fun check(): Boolean = position >= 0 && state!![position]
    }

    private val tagData = TagData(false, false, false)

    private fun fillBaseTagData(tagName: String): TagData {
        val tagData = this.tagData
        val copyTagData = DEFAULT_TAGS[tagName]
        if (copyTagData != null) {
            tagData.block = copyTagData.block
            tagData.spaced = copyTagData.spaced
            tagData.preformatted = copyTagData.preformatted
        } else {
            tagData.block = false
            tagData.spaced = false
            tagData.preformatted = TagData.Preformatted.UNDEFINED
        }
        return tagData
    }

    private fun appendLineBreak() {
        val builder = this.builder
        var mayAppend = true
        if (isSpanifyMode) {
            val length = builder.length
            mayAppend =
                if (length >= 2) {
                    builder[length - 1] != '\n' || builder[length - 2] != '\n'
                } else {
                    length == 1 && builder[0] != '\n'
                }
        }
        if (mayAppend) {
            builder.append("\n")
        }
    }

    private fun appendBlockBreak(spacedTag: Boolean) {
        when (lastBlock) {
            LAST_BLOCK_NONE -> {
                appendLineBreak()
                if (spacedTag) {
                    appendLineBreak()
                }
            }

            LAST_BLOCK_COMMON -> {
                if (spacedTag) {
                    appendLineBreak()
                }
            }

            LAST_BLOCK_SPACED -> {
                // Do nothing
            }
        }
    }

    // Removes unnecessary whitespaces in the end of block.
    private fun removeBlockLastWhitespaces() {
        if (!preformattedMode.check()) {
            val builder = this.builder
            val length = builder.length
            var remove = 0
            for (i in length - 1 downTo length - lastCharactersLength) {
                if (builder[i] == ' ') {
                    remove++
                } else {
                    break
                }
            }
            if (remove > 0) {
                builder.delete(length - remove, length)
                markup.onCutBlock(this, builder)
                lastCharactersLength -= remove
            }
        }
    }

    private var lastCharactersLength = 0
    private var lastBlock = LAST_BLOCK_NONE
    private val blockMode = PositiveStateStack()
    private val spacedMode = PositiveStateStack()
    private val preformattedMode = PositiveStateStack()

    private var tableStart = 0

    private var orderedList = false
    private var listStart = -1

    private var hidden = false

    override fun startElement(
        uri: String?,
        tagName: String,
        qName: String?,
        attributes: Attributes,
    ) {
        if (HIDDEN_TAGS.contains(tagName)) {
            hidden = true
            return
        }
        val builder = this.builder
        if ("br" == tagName) {
            return // Ignore tag
        }
        val tagData = fillBaseTagData(tagName)
        val obj = markup.onBeforeTagStart(this, builder, tagName, attributes, tagData)
        val blockTag = tagData.block
        val spacedTag = blockTag && tagData.spaced
        val preformattedTag =
            tagData.preformatted == TagData.Preformatted.ENABLED ||
                (
                    tagData.preformatted == TagData.Preformatted.UNDEFINED &&
                        preformattedMode.check()
                )
        if (blockTag) {
            appendBlockBreak(spacedTag)
        }
        markup.onTagStart(this, builder, tagName, attributes, obj)
        blockMode.push(blockTag)
        spacedMode.push(spacedTag)
        preformattedMode.push(preformattedTag)
        lastBlock =
            if (blockTag) {
                if (spacedTag) {
                    LAST_BLOCK_SPACED
                } else {
                    LAST_BLOCK_COMMON
                }
            } else {
                LAST_BLOCK_NONE
            }

        if (tagName == "tr" || tagName == "th" || tagName == "td") {
            if (tagName == "tr") {
                tableStart = 1
            } else {
                val length = builder.length
                builder.append(tableStart).append(". ")
                lastBlock = LAST_BLOCK_NONE
                lastCharactersLength = builder.length - length
                val colspan =
                    try {
                        Integer.parseInt(attributes.getValue("", "colspan"))
                    } catch (e: Exception) {
                        1
                    }
                tableStart += colspan
            }
        } else if (tagName == "ol" || tagName == "ul") {
            orderedList = tagName == "ol"
            listStart = 0
        } else if (tagName == "li" && listStart >= 0) {
            val added = markup.onListLineStart(this, builder, orderedList, ++listStart)
            if (added > 0) {
                lastBlock = LAST_BLOCK_NONE
                lastCharactersLength = added
            }
        }
    }

    override fun endElement(
        uri: String?,
        tagName: String,
        qName: String?,
    ) {
        if (hidden) {
            if (HIDDEN_TAGS.contains(tagName)) {
                hidden = false
            }
            return
        }
        val builder = this.builder
        if ("br" == tagName) {
            removeBlockLastWhitespaces()
            appendLineBreak()
            lastBlock = LAST_BLOCK_COMMON
            return
        }
        val blockTag = blockMode.pop()
        val spacedTag = spacedMode.pop()
        preformattedMode.pop()
        if (blockTag) {
            removeBlockLastWhitespaces()
        }
        markup.onTagEnd(this, builder, tagName)
        if (blockTag) {
            appendBlockBreak(spacedTag)
        }
        if (tagName == "ol" || tagName == "ul") {
            listStart = -1
        }
        lastBlock =
            if (blockTag) {
                if (spacedTag) {
                    LAST_BLOCK_SPACED
                } else {
                    LAST_BLOCK_COMMON
                }
            } else {
                LAST_BLOCK_NONE
            }
    }

    override fun characters(
        ch: CharArray,
        start: Int,
        length: Int,
    ) {
        if (hidden) {
            return
        }
        val builder = this.builder
        var realLength = 0
        if (preformattedMode.check()) {
            var p = ' '
            val to = start + length
            for (i in start until to) {
                val c = ch[i]
                // Last line break may be ignored
                if (c == '\n' && i - 1 == to) {
                    break
                }
                // \r\r - 2 spaces, \n\n - 2 spaces, \n\r - 2 spaces, \r\n - 1 space
                if ((c >= ' ' || c == '\t' || c == '\n' || c == '\r') && !(c == '\n' && p == '\r')) {
                    builder.append(if (c == '\r') '\n' else c)
                    realLength++
                }
                p = c
            }
        } else {
            val to = start + length
            for (i in start until to) {
                val c = ch[i]
                // Special characters (< 0x20): \n, \r and \t are handled as whitespace. The rest are ignored.
                // This behavior is the same as in Firefox.
                if (c == '\n' || c == '\r' || c == '\t') {
                    ch[i] = ' '
                }
            }
            var p = if (builder.isNotEmpty()) builder[builder.length - 1] else ' '
            for (i in start until to) {
                val c = ch[i]
                // Ignore special characters
                if (c >= ' ') {
                    if (c != ' ' || (p != ' ' && p != '\n')) {
                        builder.append(c)
                        realLength++
                    }
                    p = c
                }
            }
        }
        if (lastBlock == LAST_BLOCK_NONE) {
            lastCharactersLength += realLength
        } else {
            lastCharactersLength = realLength
        }
        if (realLength > 0) {
            lastBlock = LAST_BLOCK_NONE
        }
    }

    override fun ignorableWhitespace(
        ch: CharArray?,
        start: Int,
        length: Int,
    ) {}

    override fun processingInstruction(
        target: String?,
        data: String?,
    ) {}

    override fun skippedEntity(name: String?) {}

    fun getColorAttribute(attributes: Attributes): Int? {
        val style = attributes.getValue("", "style")
        var color: String? = null
        if (style != null) {
            val matcher = COLOR_PATTERN.matcher(style)
            if (matcher.find()) {
                if (matcher.group(1) != null) {
                    val r = Integer.parseInt(matcher.group(1)!!)
                    val g = Integer.parseInt(matcher.group(2)!!)
                    val b = Integer.parseInt(matcher.group(3)!!)
                    return Color.rgb(r, g, b)
                }
                color = matcher.group(4)
            }
        }
        if (StringUtils.isEmpty(color)) {
            color = attributes.getValue("", "color")
        }
        if (!color.isNullOrEmpty()) {
            if (color[0] == '#' && color.length != 7) {
                if (color.length == 4) {
                    color = "#" + color[1] + color[1] + color[2] + color[2] + color[3] + color[3]
                } else if (color.length < 7) {
                    color += "000000".substring(color.length - 1)
                } else {
                    return null
                }
            }
            try {
                return Color.BLACK or Color.parseColor(color)
            } catch (e: IllegalArgumentException) {
                // Not a color, ignore exception
            }
        }
        return null
    }

    companion object {
        @JvmStatic
        fun <E, D, S : SpanProvider<E>> spanify(
            source: String?,
            markup: Markup<E, D, S>,
            threadNumber: String?,
            originalPostNumber: PostNumber?,
            extra: E?,
        ): CharSequence = parse(source, markup, Mode.SPANIFY, threadNumber, originalPostNumber, extra)

        @JvmStatic
        fun clear(source: String?): String = parse<Nothing?, Nothing?, Nothing>(source, null, Mode.CLEAR, null, null, null).toString()

        @JvmStatic
        fun <E, D, S : SpanProvider<E>> unmark(
            source: String?,
            markup: Markup<E, D, S>?,
            extra: E?,
        ): String = parse(source, markup, Mode.UNMARK, null, null, extra).toString()

        private fun <E, D, S : SpanProvider<E>> parse(
            source: String?,
            markup: Markup<E, D, S>?,
            parsingMode: Mode,
            threadNumber: String?,
            originalPostNumber: PostNumber?,
            extra: E?,
        ): CharSequence {
            if (source.isNullOrEmpty()) {
                return ""
            }
            return HtmlParser(source, markup, parsingMode, threadNumber, originalPostNumber, extra).convert()
        }

        @JvmField
        val SCHEMA = HTMLSchema()

        private val DEFAULT_TAGS =
            hashMapOf(
                "blockquote" to TagData(block = true, spaced = true, preformatted = false),
                "center" to TagData(block = true, spaced = false, preformatted = false),
                "div" to TagData(block = true, spaced = false, preformatted = false),
                "h1" to TagData(block = true, spaced = true, preformatted = false),
                "h2" to TagData(block = true, spaced = true, preformatted = false),
                "h3" to TagData(block = true, spaced = true, preformatted = false),
                "h4" to TagData(block = true, spaced = true, preformatted = false),
                "h5" to TagData(block = true, spaced = true, preformatted = false),
                "h6" to TagData(block = true, spaced = true, preformatted = false),
                "hr" to TagData(block = true, spaced = false, preformatted = false),
                "li" to TagData(block = true, spaced = false, preformatted = false),
                "ol" to TagData(block = true, spaced = true, preformatted = false),
                "p" to TagData(block = true, spaced = true, preformatted = false),
                "pre" to TagData(block = true, spaced = true, preformatted = true),
                "ul" to TagData(block = true, spaced = true, preformatted = false),
                // Show tables as lists
                "table" to TagData(block = true, spaced = false, preformatted = false),
                "td" to TagData(block = true, spaced = false, preformatted = false),
                "th" to TagData(block = true, spaced = false, preformatted = false),
                "tr" to TagData(block = true, spaced = true, preformatted = false),
            )

        private val HIDDEN_TAGS = hashSetOf("script", "style")

        private const val LAST_BLOCK_NONE = 0
        private const val LAST_BLOCK_COMMON = 1
        private const val LAST_BLOCK_SPACED = 2

        private val COLOR_PATTERN =
            Pattern.compile(
                "color: ?(?:rgba?\\((\\d+), ?(\\d+)," +
                    " ?(\\d+)(?:, ?\\d+)?\\)|(#[0-9A-Fa-f]+|[A-Za-z]+))",
            )

        private val IDLE_MARKUP: Markup<*, *, *> =
            object : Markup<Any?, Any?, SpanProvider<Any?>> {
                override fun onBeforeTagStart(
                    parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>,
                    builder: StringBuilder,
                    tagName: String,
                    attributes: Attributes,
                    tagData: TagData,
                ): Any? = null

                override fun onTagStart(
                    parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>,
                    builder: StringBuilder,
                    tagName: String,
                    attributes: Attributes,
                    obj: Any?,
                ) {}

                override fun onTagEnd(
                    parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>,
                    builder: StringBuilder,
                    tagName: String,
                ) {}

                override fun onListLineStart(
                    parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>,
                    builder: StringBuilder,
                    ordered: Boolean,
                    line: Int,
                ): Int = 0

                override fun onCutBlock(
                    parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>,
                    builder: StringBuilder,
                ) {}

                override fun initSpanProvider(parser: HtmlParser<Any?, Any?, SpanProvider<Any?>>): SpanProvider<Any?>? = null
            }
    }
}
