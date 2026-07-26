package chan.content

import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.Pair
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.Chan.Companion.getFallback
import chan.content.ExtensionException.Companion.logException
import chan.text.CommentEditor
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import chan.util.StringUtils.reduceEmptyLines
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseNullable
import com.mishiranu.dashchan.text.HtmlParser
import com.mishiranu.dashchan.text.HtmlParser.Companion.spanify
import com.mishiranu.dashchan.text.HtmlParser.SpanProvider
import com.mishiranu.dashchan.text.style.GainedColorSpan
import com.mishiranu.dashchan.text.style.HeadingSpan
import com.mishiranu.dashchan.text.style.ItalicSpan
import com.mishiranu.dashchan.text.style.LinkSpan
import com.mishiranu.dashchan.text.style.LinkSuffixSpan
import com.mishiranu.dashchan.text.style.MediumSpan
import com.mishiranu.dashchan.text.style.MonospaceSpan
import com.mishiranu.dashchan.text.style.NeuroslopSpan
import com.mishiranu.dashchan.text.style.OverlineSpan
import com.mishiranu.dashchan.text.style.QuoteSpan
import com.mishiranu.dashchan.text.style.ScriptSpan
import com.mishiranu.dashchan.text.style.SpoilerSpan
import com.mishiranu.dashchan.text.style.TabulationSpan
import com.mishiranu.dashchan.text.style.UnderlyingSpoilerSpan
import org.xml.sax.Attributes

@Extendable
open class ChanMarkup internal constructor(
    chanProvider: Chan.Provider?,
) : Chan.Linked {
    private val chanProvider: Chan.Provider?

    @Public
    constructor() : this(null)

    override fun init() {}

    override fun get(): Chan = chanProvider!!.get()

    @Extendable
    protected open fun obtainCommentEditor(boardName: String?): CommentEditor? = null

    @Extendable
    protected open fun isTagSupported(
        boardName: String?,
        tag: Int,
    ): Boolean = false

    private class MarkupItem {
        var tagItem: TagItem? = null
        var cssClassTagItems: HashMap<String?, TagItem>? = null
        var attrubuteItems: ArrayList<AttributeItem>? = null
    }

    private class AttributeItem(
        val attribute: String?,
        val value: String?,
    ) {
        val tagItem: TagItem = TagItem()
    }

    private class TagItem {
        var parentTagItem: TagItem? = null

        var tag: Int = 0
        var colorable: Boolean = false

        var blockDefined: Boolean = false
        var block: Boolean = false
        var spaced: Boolean = false

        var preformattedDefined: Boolean = false
        var preformatted: Boolean = false

        fun setBlock(
            block: Boolean,
            spaced: Boolean,
        ) {
            blockDefined = true
            this.block = block
            this.spaced = spaced
        }

        fun definePreformatted(preformatted: Boolean) {
            preformattedDefined = true
            this.preformatted = preformatted
        }

        fun applyTagData(tagData: HtmlParser.TagData) {
            parentTagItem?.applyTagData(tagData)
            if (blockDefined) {
                tagData.block = block
                tagData.spaced = spaced
            }
            if (preformattedDefined) {
                tagData.preformatted =
                    if (preformatted) {
                        HtmlParser.TagData.Preformatted.ENABLED
                    } else {
                        HtmlParser.TagData.Preformatted.DISABLED
                    }
            }
        }

        val isMorePreferredThanParent: Boolean
            get() {
                val parentTagItem = this.parentTagItem
                return parentTagItem == null || tag != 0 || parentTagItem.tag == 0
            }
    }

    private val markupItems: HashMap<String?, MarkupItem?> = HashMap<String?, MarkupItem?>()

    private fun obtainTagItem(
        tagName: String,
        withCssClass: Boolean,
        cssClass: String?,
        withAttribute: Boolean,
        attribute: String?,
        value: String?,
    ): TagItem {
        var lowerTagName = tagName
        if (withCssClass && cssClass == null) {
            throw NullPointerException("cssClass must not be null")
        }
        if (withAttribute && (attribute == null || value == null)) {
            throw NullPointerException("attribute and value must not be null")
        }
        lowerTagName = lowerTagName.lowercase()
        var markupItem = markupItems[lowerTagName]
        if (markupItem == null) {
            markupItem = MarkupItem()
            markupItems[lowerTagName] = markupItem
        }
        if (withCssClass) {
            val cssClassTagItems =
                markupItem.cssClassTagItems
                    ?: HashMap<String?, TagItem>().also { markupItem.cssClassTagItems = it }
            var tagItem = cssClassTagItems[cssClass]
            if (tagItem == null) {
                tagItem = TagItem()
                cssClassTagItems[cssClass] = tagItem
                tagItem.parentTagItem = markupItem.tagItem
            }
            return tagItem
        } else if (withAttribute) {
            val attrubuteItems =
                markupItem.attrubuteItems
                    ?: ArrayList<AttributeItem>().also { markupItem.attrubuteItems = it }
            var tagItem: TagItem? = null
            for (attributeItem in attrubuteItems) {
                if (attribute == attributeItem.attribute && value == attributeItem.value) {
                    tagItem = attributeItem.tagItem
                    break
                }
            }
            if (tagItem == null) {
                val attributeItem = AttributeItem(attribute, value)
                attrubuteItems.add(attributeItem)
                attributeItem.tagItem.parentTagItem = markupItem.tagItem
                tagItem = attributeItem.tagItem
            }
            return tagItem
        } else {
            var tagItem = markupItem.tagItem
            if (tagItem == null) {
                tagItem = TagItem()
                markupItem.tagItem = tagItem
                markupItem.cssClassTagItems?.values?.forEach { it.parentTagItem = tagItem }
                markupItem.attrubuteItems?.forEach { it.tagItem.parentTagItem = tagItem }
            }
            return tagItem
        }
    }

    @Public
    fun addTag(
        tagName: String,
        tag: Int,
    ) {
        obtainTagItem(tagName, false, null, false, null, null).tag = tag
    }

    @Public
    fun addTag(
        tagName: String,
        cssClass: String,
        tag: Int,
    ) {
        obtainTagItem(tagName, true, cssClass, false, null, null).tag = tag
    }

    @Public
    fun addTag(
        tagName: String,
        attribute: String,
        value: String,
        tag: Int,
    ) {
        obtainTagItem(tagName, false, null, true, attribute, value).tag = tag
    }

    @Public
    fun addColorable(tagName: String) {
        obtainTagItem(tagName, false, null, false, null, null).colorable = true
    }

    @Public
    fun addColorable(
        tagName: String,
        cssClass: String,
    ) {
        obtainTagItem(tagName, true, cssClass, false, null, null).colorable = true
    }

    @Public
    fun addColorable(
        tagName: String,
        attribute: String,
        value: String,
    ) {
        obtainTagItem(tagName, false, null, true, attribute, value).colorable = true
    }

    @Public
    fun addBlock(
        tagName: String,
        block: Boolean,
        spaced: Boolean,
    ) {
        obtainTagItem(tagName, false, null, false, null, null).setBlock(block, spaced)
    }

    @Public
    fun addBlock(
        tagName: String,
        cssClass: String,
        block: Boolean,
        spaced: Boolean,
    ) {
        obtainTagItem(tagName, true, cssClass, false, null, null).setBlock(block, spaced)
    }

    @Public
    fun addBlock(
        tagName: String,
        attribute: String,
        value: String,
        block: Boolean,
        spaced: Boolean,
    ) {
        obtainTagItem(tagName, false, null, true, attribute, value).setBlock(block, spaced)
    }

    @Public
    fun addPreformatted(
        tagName: String,
        preformatted: Boolean,
    ) {
        obtainTagItem(tagName, false, null, false, null, null).definePreformatted(preformatted)
    }

    @Public
    fun addPreformatted(
        tagName: String,
        cssClass: String,
        preformatted: Boolean,
    ) {
        obtainTagItem(tagName, true, cssClass, false, null, null).definePreformatted(preformatted)
    }

    @Public
    fun addPreformatted(
        tagName: String,
        attribute: String,
        value: String,
        preformatted: Boolean,
    ) {
        obtainTagItem(tagName, false, null, true, attribute, value).definePreformatted(preformatted)
    }

    val markup: HtmlParser.Markup<MarkupExtra?, *, ChanSpanProvider> =
        object : HtmlParser.Markup<MarkupExtra?, TagItem?, ChanSpanProvider> {
            override fun onBeforeTagStart(
                parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>,
                builder: StringBuilder,
                tagName: String,
                attributes: Attributes,
                tagData: HtmlParser.TagData,
            ): TagItem? {
                if (tagName != "a") {
                    val markupItem = markupItems[tagName]
                    if (markupItem != null) {
                        var tagItem = markupItem.tagItem
                        var preferredTagItemFound = false
                        val cssClassTagItems = markupItem.cssClassTagItems
                        if (cssClassTagItems != null) {
                            val fullCssClass = attributes.getValue("", "class")
                            val cssClasses: Array<String?>? =
                                if (fullCssClass != null) {
                                    fullCssClass
                                        .split(" +".toRegex())
                                        .dropLastWhile { it.isEmpty() }
                                        .toTypedArray()
                                } else {
                                    null
                                }
                            if (cssClasses != null) {
                                for (cssClass in cssClasses) {
                                    val preferredTagItem =
                                        cssClassTagItems[cssClass]
                                    if (preferredTagItem != null &&
                                        preferredTagItem.isMorePreferredThanParent
                                    ) {
                                        tagItem = preferredTagItem
                                        preferredTagItemFound = true
                                        break
                                    }
                                }
                            }
                        }
                        if (!preferredTagItemFound && markupItem.attrubuteItems != null) {
                            for (attributeItem in markupItem.attrubuteItems) {
                                val value = attributes.getValue("", attributeItem.attribute)
                                if (attributeItem.value == value &&
                                    attributeItem.tagItem.isMorePreferredThanParent
                                ) {
                                    tagItem = attributeItem.tagItem
                                    break
                                }
                            }
                        }
                        if (tagItem != null) {
                            tagItem.applyTagData(tagData)
                            if (tagItem.tag == TAG_AI) {
                                // Set off the AI answer with a blank line above and below.
                                tagData.block = true
                                tagData.spaced = true
                            }
                            return tagItem
                        }
                        return UNUSED_TAG_ITEM
                    }
                }
                return null
            }

            override fun onTagStart(
                parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>,
                builder: StringBuilder,
                tagName: String,
                attributes: Attributes,
                obj: TagItem?,
            ) {
                val provider = parser.getSpanProvider()
                var tag = 0
                var extra: Any? = null
                if (tagName == "a") {
                    tag = TAG_SPECIAL_LINK
                    val linkHolder = LinkHolder()
                    linkHolder.uriString = nullIfEmpty(attributes.getValue("", "href"))
                    extra = linkHolder
                } else if (obj != null) {
                    if (obj.tag != 0) {
                        tag = obj.tag
                    } else if (obj.colorable) {
                        extra = parser.getColorAttribute(attributes)
                        if (extra != null) {
                            tag = TAG_SPECIAL_COLOR
                        } else {
                            tag = TAG_SPECIAL_UNUSED
                        }
                    } else {
                        tag = TAG_SPECIAL_UNUSED
                    }
                }
                if (tag != 0) {
                    if (parser.isUnmarkMode) {
                        val commentEditor = provider!!.commentEditor
                        if (commentEditor != null) {
                            val markupTag = commentEditor.getTag(tag, false, 0)
                            if (markupTag != null) {
                                builder.append(markupTag)
                            }
                        }
                    }
                    provider!!.add(tag, extra, builder.length)
                }
            }

            override fun onTagEnd(
                parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>,
                builder: StringBuilder,
                tagName: String,
            ) {
                val provider = parser.getSpanProvider()
                var styledItem: StyledItem? = null
                if (tagName == "a" || markupItems.containsKey(tagName)) {
                    styledItem = provider!!.lastOpenStyledItem
                }
                if (styledItem != null) {
                    if (parser.isUnmarkMode) {
                        val commentEditor = provider!!.commentEditor
                        if (commentEditor != null) {
                            if (styledItem.tag != 0) {
                                val markupTag =
                                    commentEditor.getTag(
                                        styledItem.tag,
                                        true,
                                        builder.length - styledItem.start,
                                    )
                                if (markupTag != null) {
                                    builder.append(markupTag)
                                }
                            }
                        }
                    }
                    var end = builder.length
                    if (styledItem.tag == TAG_SPECIAL_LINK) {
                        val linkHolder: LinkHolder = styledItem.extra as LinkHolder
                        if (parser.isSpanifyMode) {
                            provider!!.modifyLink(parser, styledItem.start, end, linkHolder)
                        } else if (parser.isUnmarkMode) {
                            end +=
                                provider!!.replaceLink(
                                    parser,
                                    styledItem.start,
                                    end,
                                    linkHolder.uriString,
                                )
                        }
                    }
                    styledItem.close(end)
                }
            }

            override fun onListLineStart(
                parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>,
                builder: StringBuilder,
                ordered: Boolean,
                line: Int,
            ): Int {
                val length = builder.length
                if (parser.isSpanifyMode) {
                    if (ordered) {
                        builder.append(line).append(". ")
                    } else {
                        builder.append("\u2022 ")
                    }
                } else if (parser.isUnmarkMode) {
                    val provider = parser.getSpanProvider()
                    val commentEditor = provider!!.commentEditor
                    val mark: String?
                    if (commentEditor != null) {
                        if (ordered) {
                            mark = commentEditor.getOrderedListMark()
                        } else {
                            mark = commentEditor.getUnorderedListMark()
                        }
                    } else {
                        if (ordered) {
                            mark = null
                        } else {
                            mark = "- "
                        }
                    }
                    if (mark == null) {
                        builder.append(line).append(". ")
                    } else {
                        builder.append(mark)
                    }
                }
                return builder.length - length
            }

            override fun onCutBlock(
                parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>,
                builder: StringBuilder,
            ) {
                val provider = parser.getSpanProvider()
                provider!!.cut(builder.length)
            }

            override fun initSpanProvider(parser: HtmlParser<MarkupExtra?, TagItem?, ChanSpanProvider>): ChanSpanProvider {
                val provider = ChanSpanProvider()
                if (parser.isUnmarkMode) {
                    val extra = parser.getExtra()
                    if (extra != null) {
                        val boardName = extra.getBoardName()
                        provider.commentEditor = safe.obtainCommentEditor(boardName)
                    }
                }
                return provider
            }
        }

    private class NotImplementedException : Exception()

    @Extendable
    @Throws(NotImplementedException::class)
    protected open fun obtainPostLinkThreadPostNumbers(uriString: String?): Pair<String?, String?>? = throw NotImplementedException()

    internal class LinkHolder {
        var uriString: String? = null
        var postNumber: PostNumber? = null
    }

    private class LinkSuffixHolder {
        var suffix: Int = 0
        var postNumber: PostNumber? = null
    }

    internal class StyledItem(
        val tag: Int,
        val extra: Any?,
        var start: Int,
    ) {
        var end: Int

        init {
            this.end = -1
        }

        val isClosed: Boolean
            get() = end >= start

        fun close(end: Int) {
            this.end = end
        }
    }

    class MarkupBuilder(
        constructor: Constructor,
    ) {
        fun interface Constructor {
            fun configure(markup: ChanMarkup?)
        }

        private val markup: ChanMarkup

        init {
            markup = ChanMarkup(Chan.Provider(getFallback()))
            constructor.configure(markup)
        }

        fun fromHtmlReduced(html: String?): CharSequence =
            reduceEmptyLines(
                spanify(
                    html,
                    markup.markup,
                    null,
                    null,
                    null,
                ),
            )
    }

    inner class ChanSpanProvider internal constructor() : SpanProvider<MarkupExtra?> {
        private val styledItems: ArrayList<StyledItem> = ArrayList<StyledItem>()

        var commentEditor: CommentEditor? = null

        internal fun add(
            tag: Int,
            extra: Any?,
            start: Int,
        ): StyledItem {
            val styledItem = StyledItem(tag, extra, start)
            styledItems.add(styledItem)
            return styledItem
        }

        internal val lastOpenStyledItem: StyledItem?
            get() {
                val styledItems = this.styledItems
                for (i in styledItems.indices.reversed()) {
                    val styledItem = styledItems[i]
                    if (!styledItem.isClosed) {
                        return styledItem
                    }
                }
                return null
            }

        fun cut(length: Int) {
            val styledItems = this.styledItems
            for (i in styledItems.indices.reversed()) {
                val styledItem = styledItems[i]
                if (styledItem.end > length) {
                    styledItem.end = length
                }
                if (styledItem.start > length) {
                    styledItem.start = length
                }
            }
        }

        internal fun modifyLink(
            parser: HtmlParser<MarkupExtra?, *, *>,
            start: Int,
            end: Int,
            linkHolder: LinkHolder,
        ) {
            val processThreadNumber = parser.getThreadNumber()
            val processOriginalPostNumber = parser.getOriginalPostNumber()
            if (linkHolder.uriString != null && processThreadNumber != null && processOriginalPostNumber != null) {
                val builder = parser.getBuilder()
                val string = builder.substring(start, end)
                if (string.length < 3) {
                    return
                }
                // Fast match >>\d+
                for (i in 0..<string.length) {
                    val c = string[i]
                    if (!((i < 2 && c == '>') || (i >= 2 && c >= '0' && c <= '9'))) {
                        return
                    }
                }
                val uriString = linkHolder.uriString
                var threadNumber: String? = null
                var postNumber: PostNumber? = null
                try {
                    var numbers: Pair<String?, String?>? = null
                    try {
                        numbers = obtainPostLinkThreadPostNumbers(uriString)
                    } catch (e: LinkageError) {
                        logException(e, false)
                    } catch (e: RuntimeException) {
                        logException(e, false)
                    }
                    if (numbers != null) {
                        threadNumber = numbers.first
                        if (!isEmpty(numbers.second)) {
                            postNumber = parseNullable(numbers.second)
                        }
                    }
                } catch (e: NotImplementedException) {
                    val extra = parser.getExtra()
                    if (extra != null) {
                        val locator = get().locator
                        val uri =
                            locator.validateClickedUriString(
                                uriString,
                                extra.getBoardName(),
                                extra.getThreadNumber(),
                            )
                        threadNumber = locator.safe(false).getThreadNumber(uri)
                        postNumber = locator.safe(false).getPostNumber(uri)
                    }
                }
                if (threadNumber != null || postNumber != null) {
                    val linkSuffixHolder = LinkSuffixHolder()
                    linkSuffixHolder.postNumber = postNumber
                    linkHolder.postNumber =
                        if (postNumber == null) processOriginalPostNumber else postNumber
                    if (!isEmpty(threadNumber) && processThreadNumber != threadNumber) {
                        linkSuffixHolder.suffix =
                            linkSuffixHolder.suffix or LinkSuffixSpan.SUFFIX_DIFFERENT_THREAD
                    } else if (postNumber == null || processOriginalPostNumber.equals(postNumber)) {
                        linkSuffixHolder.suffix =
                            linkSuffixHolder.suffix or LinkSuffixSpan.SUFFIX_ORIGINAL_POSTER
                    }
                    builder.append('\u00a0')
                    val length = builder.length
                    add(TAG_SPECIAL_LINK_SUFFIX, linkSuffixHolder, length - 1).close(length)
                }
            }
        }

        /*
         * Returns number of characters added or removed
         */
        internal fun replaceLink(
            parser: HtmlParser<MarkupExtra?, *, *>,
            start: Int,
            end: Int,
            uriString: String?,
        ): Int {
            if (uriString != null) {
                val builder = parser.getBuilder()
                val string = builder.substring(start, end)
                if (!string.startsWith(">>")) {
                    builder.replace(start, end, uriString)
                    return uriString.length - end + start
                }
            }
            return 0
        }

        @SuppressLint("ResourceAsColor")
        override fun transformBuilder(
            parser: HtmlParser<MarkupExtra?, *, *>,
            builder: StringBuilder,
        ): CharSequence {
            val spannable = SpannableString(builder)
            for (i in 0..<spannable.length) {
                if (spannable.get(i) == '\t') {
                    spannable.setSpan(
                        TabulationSpan(),
                        i,
                        i + 1,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            for (styledItem in styledItems) {
                if (styledItem.isClosed) {
                    var span: Any? = null
                    when (styledItem.tag) {
                        TAG_BOLD -> {
                            span = MediumSpan()
                        }

                        TAG_ITALIC -> {
                            span = ItalicSpan()
                        }

                        TAG_SUBSCRIPT -> {
                            span = ScriptSpan(false)
                        }

                        TAG_SUPERSCRIPT -> {
                            span = ScriptSpan(true)
                        }

                        TAG_QUOTE -> {
                            span = QuoteSpan()
                        }

                        TAG_SPOILER -> {
                            span = UnderlyingSpoilerSpan()
                        }

                        TAG_UNDERLINE -> {
                            span = UnderlineSpan()
                        }

                        TAG_OVERLINE -> {
                            val result = OverlineSpan()
                            span = result
                        }

                        TAG_STRIKE -> {
                            span = StrikethroughSpan()
                        }

                        TAG_CODE -> {
                            span = MonospaceSpan(false)
                        }

                        TAG_ASCII_ART -> {
                            span = MonospaceSpan(true)
                        }

                        TAG_HEADING -> {
                            span = HeadingSpan()
                        }

                        TAG_SPECIAL_LINK -> {
                            val linkHolder: LinkHolder = styledItem.extra as LinkHolder
                            if (linkHolder.uriString != null) {
                                span = LinkSpan(linkHolder.uriString, linkHolder.postNumber)
                            }
                        }

                        TAG_SPECIAL_COLOR -> {
                            span = GainedColorSpan(styledItem.extra as Int)
                        }

                        TAG_SPECIAL_LINK_SUFFIX -> {
                            val linkSuffixHolder: LinkSuffixHolder =
                                styledItem.extra as LinkSuffixHolder
                            span =
                                LinkSuffixSpan(linkSuffixHolder.suffix, linkSuffixHolder.postNumber)
                        }

                        TAG_AI -> {
                            span = NeuroslopSpan()
                        }
                    }
                    if (span != null) {
                        spannable.setSpan(
                            span,
                            styledItem.start,
                            styledItem.end,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
            // Spoiler spans must be above the rest spans
            for (styledItem in styledItems) {
                if (styledItem.tag == TAG_SPOILER && styledItem.isClosed) {
                    spannable.setSpan(
                        SpoilerSpan(),
                        styledItem.start,
                        styledItem.end,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return spannable
        }
    }

    interface MarkupExtra {
        fun getBoardName(): String?

        fun getThreadNumber(): String?
    }

    class Safe internal constructor(
        private val markup: ChanMarkup,
    ) {
        fun obtainCommentEditor(boardName: String?): CommentEditor? {
            try {
                return markup.obtainCommentEditor(boardName)
            } catch (e: LinkageError) {
                logException(e, false)
                return null
            } catch (e: RuntimeException) {
                logException(e, false)
                return null
            }
        }

        fun isTagSupported(
            boardName: String?,
            tag: Int,
        ): Boolean {
            try {
                return markup.isTagSupported(boardName, tag)
            } catch (e: LinkageError) {
                logException(e, false)
                return false
            } catch (e: RuntimeException) {
                logException(e, false)
                return false
            }
        }
    }

    private val safe = ChanMarkup.Safe(this)

    init {
        if (chanProvider == null) {
            val holder: ChanManager.Initializer.Holder = INITIALIZER.consume()
            this.chanProvider = holder.chanProvider
        } else {
            this.chanProvider = chanProvider
        }
    }

    fun safe(): Safe = safe

    companion object {
        val INITIALIZER: ChanManager.Initializer = ChanManager.Initializer()

        @Public
        @JvmStatic
        fun get(`object`: Any): ChanMarkup = (`object` as Chan.Linked).get().markup

        @Public
        const val TAG_BOLD: Int = 0x00000001

        @Public
        const val TAG_ITALIC: Int = 0x00000002

        @Public
        const val TAG_UNDERLINE: Int = 0x00000004

        @Public
        const val TAG_OVERLINE: Int = 0x00000008

        @Public
        const val TAG_STRIKE: Int = 0x00000010

        @Public
        const val TAG_SUBSCRIPT: Int = 0x00000020

        @Public
        const val TAG_SUPERSCRIPT: Int = 0x00000040

        @Public
        const val TAG_SPOILER: Int = 0x00000080

        @Public
        const val TAG_QUOTE: Int = 0x00000100

        @Public
        const val TAG_CODE: Int = 0x00000200

        @Public
        const val TAG_ASCII_ART: Int = 0x00000400

        @Public
        const val TAG_HEADING: Int = 0x00000800

        @Public
        const val TAG_AI: Int = 0x00001600

        const val TAG_SPECIAL_UNUSED: Int = 0x01000000
        const val TAG_SPECIAL_LINK: Int = 0x01000001
        const val TAG_SPECIAL_COLOR: Int = 0x01000002
        const val TAG_SPECIAL_LINK_SUFFIX: Int = 0x01000003

        private val UNUSED_TAG_ITEM = TagItem()
    }
}
