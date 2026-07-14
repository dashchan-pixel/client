package chan.text

import chan.annotation.Extendable
import chan.annotation.Public
import chan.util.StringUtils
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.Locale

@Public
class GroupParser private constructor(
    private val callback: Callback,
) {
    @Public
    class Attributes internal constructor() {
        internal var html: CharSequence? = null

        @Public
        fun get(attribute: String?): String? = extractAttr(html, attribute)

        @Public
        fun contains(string: CharSequence): Boolean = StringUtils.indexOf(html!!, 0, string) >= 0
    }

    @Extendable
    interface Callback {
        @Extendable
        @Throws(ParseException::class)
        fun onStartElement(
            parser: GroupParser,
            tagName: String,
            attributes: Attributes,
        ): Boolean

        @Extendable
        @Throws(ParseException::class)
        fun onEndElement(
            parser: GroupParser,
            tagName: String,
        )

        @Extendable
        @Throws(ParseException::class)
        fun onText(
            parser: GroupParser,
            text: CharSequence,
        )

        @Extendable
        @Throws(ParseException::class)
        fun onGroupComplete(
            parser: GroupParser,
            text: String,
        )
    }

    private class SubBuilder : CharSequence {
        var builder: StringBuilder? = null
        var start = 0
        var end = 0

        override val length: Int
            get() = end - start

        override fun get(index: Int): Char = builder!![start + index]

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            if (startIndex == 0 && endIndex == length) {
                return this
            }
            val result = SubBuilder()
            result.builder = builder
            result.start = start + startIndex
            result.end = start + endIndex
            return result
        }

        override fun toString(): String = builder!!.substring(start, end)
    }

    private val groupBuilder = StringBuilder()

    private var groupTagName: String? = null
    private var groupCount = -1

    private class ReaderWrapper(
        val reader: Reader,
    ) {
        val stack = StringBuilder()

        var eof = false
        var readerEof = false

        @Throws(IOException::class)
        fun next(): Char {
            val newStackSize = stack.length - 1
            if (newStackSize >= 0) {
                val c = stack[newStackSize]
                stack.setLength(newStackSize)
                return c
            } else if (!readerEof) {
                val ci = reader.read()
                if (ci < 0) {
                    readerEof = true
                    eof = true
                }
                return ci.toChar()
            } else {
                eof = readerEof
                return (-1).toChar()
            }
        }

        @Throws(IOException::class)
        fun readTo(
            builder: StringBuilder?,
            stop: CharArray,
        ): Char {
            while (true) {
                val c = next()
                if (eof) {
                    return (-1).toChar()
                }
                for (s in stop) {
                    if (c == s) {
                        return c
                    }
                }
                builder?.append(c)
            }
        }

        @Throws(IOException::class)
        fun skipTo(
            lowerCase: Boolean,
            end: String,
        ) {
            var index = 0
            while (true) {
                var c = next()
                if (eof) {
                    break
                }
                if (lowerCase) {
                    c = Character.toLowerCase(c)
                }
                if (end[index] == c) {
                    index++
                } else if (end[0] == c) {
                    index = 1
                } else {
                    index = 0
                }
                if (index == end.length) {
                    break
                }
            }
        }
    }

    @Throws(IOException::class, ParseException::class)
    private fun convert(reader: Reader) {
        convertInternal(ReaderWrapper(reader))
    }

    @Throws(IOException::class, ParseException::class)
    private fun convertInternal(reader: ReaderWrapper) {
        val builder = StringBuilder()
        reader.readTo(builder, CHARACTERS_TAG_START)
        if (builder.isNotEmpty()) {
            onText(builder)
        }

        // loop is started with "<" character already read
        while (!reader.eof) {
            var next = reader.next()
            if (reader.eof) {
                break
            }
            if (next == '!') {
                // Skip comment
                next = reader.next()
                if (reader.eof) {
                    break
                }
                var malformedComment = false
                var commentHandled = false
                if (next == '>') {
                    next = reader.next()
                    if (reader.eof) {
                        break
                    }
                    commentHandled = true
                } else if (next == '-') {
                    next = reader.next()
                    if (reader.eof) {
                        break
                    }
                    if (next == '>') {
                        next = reader.next()
                        if (reader.eof) {
                            break
                        }
                        commentHandled = true
                    } else if (next != '-') {
                        malformedComment = true
                    }
                } else {
                    malformedComment = true
                }
                if (!commentHandled) {
                    if (malformedComment) {
                        reader.readTo(null, CHARACTERS_TAG_END)
                    } else {
                        reader.skipTo(false, "-->")
                    }
                    if (reader.eof) {
                        break
                    }
                    next = reader.next()
                    if (reader.eof) {
                        break
                    }
                }
            } else {
                builder.setLength(0)
                builder.append('<')
                reader.stack.append(next)

                // Find tag end including cases when < or > is a part of attribute
                // E.g. <span onclick="test.innerHTML='<p>test</p>'">
                var inApostrophes = false
                var inQuotes = false
                // Found a valid end (ends with ">")
                var endsWithGt = true
                var endFound = false
                // Check illegal syntax in first "tryCount" characters only
                val tryCount = 500
                for (i in 0 until tryCount) {
                    val c = reader.next()
                    if (reader.eof) {
                        break
                    }
                    if (c == '"' && !inApostrophes) {
                        inQuotes = !inQuotes
                    } else if (c == '\'' && !inQuotes) {
                        inApostrophes = !inApostrophes
                    } else if (c == '<' && !inApostrophes && !inQuotes) {
                        // Malformed HTML, e.g. <span style="color: #fff"<p>test</p>
                        // The last "<" is going to stack
                        reader.stack.append(c)
                        endsWithGt = false
                        endFound = true
                        break
                    } else if (c == '>' && !inApostrophes && !inQuotes) {
                        builder.append(c)
                        endFound = true
                        break
                    }
                    builder.append(c)
                }

                if (!endFound) {
                    val index = StringUtils.nearestIndexOf(builder, 1, *CHARACTERS_TAG_START_END)
                    if (index >= 0) {
                        // Put remaining characters to stack
                        for (i in builder.length - 1 downTo index) {
                            reader.stack.append(builder[i])
                        }
                        builder.setLength(index + 1)
                        if (builder.length < 2) {
                            throw ParseException("Illegal parser state")
                        }
                        endsWithGt = builder[index] == '>'
                        if (!endsWithGt) {
                            reader.stack.append('<')
                            builder.setLength(builder.length - 1)
                        }
                    } else {
                        val stop = reader.readTo(builder, CHARACTERS_TAG_START_END)
                        if (reader.eof) {
                            throw ParseException("Malformed HTML: end of tag was not found ($builder)")
                        }
                        endsWithGt = stop == '>'
                        if (endsWithGt) {
                            builder.append(stop)
                        }
                    }
                }

                if (builder.length <= (if (endsWithGt) 2 else 1)) {
                    // Empty tag, handle as text characters
                    onText(builder)
                } else {
                    if (!endsWithGt) {
                        builder.append('>')
                    }
                    val close = builder[1] == '/'
                    val selfClose = builder[builder.length - 2] == '/'
                    val tagEnd = builder.length - if (selfClose) 2 else 1
                    val tagNameStart = if (close) 2 else 1
                    var tagName = ""
                    var attrsStart = -1
                    var attrsEnd = -1
                    var tagNameEnd = StringUtils.nearestIndexOf(builder, tagNameStart, *CHARACTERS_TAG_NAME_END)
                    if (tagNameEnd < 0) {
                        tagNameEnd = tagEnd
                    }
                    if (tagNameEnd > tagNameStart) {
                        tagName = builder.substring(tagNameStart, tagNameEnd)
                        if (!close && tagEnd > tagNameEnd + 1) {
                            attrsStart = tagNameEnd + 1
                            attrsEnd = tagEnd
                        }
                    }
                    // Ignore weird "</>" and "<//>" cases
                    if (tagName.isNotEmpty()) {
                        val tagNameLower = tagName.lowercase(Locale.US)
                        if (!close && (tagNameLower == "script" || tagNameLower == "style")) {
                            reader.skipTo(true, "</$tagNameLower>")
                            if (reader.eof) {
                                throw ParseException("Can't find closing $tagNameLower")
                            }
                        } else if (close) {
                            onEndElement(tagNameLower, builder)
                        } else {
                            onStartElement(tagNameLower, builder, attrsStart, attrsEnd)
                        }
                    }
                }
                next = reader.next()
            }

            if (!reader.eof && next != '<') {
                builder.setLength(0)
                builder.append(next)
                reader.readTo(builder, CHARACTERS_TAG_START)
                if (builder.isNotEmpty()) {
                    onText(builder)
                }
            }
        }
    }

    private val workSubBuilder = SubBuilder()
    private val workAttributes = Attributes()

    @Throws(ParseException::class)
    private fun onStartElement(
        tagName: String,
        source: StringBuilder,
        attrsStart: Int,
        attrsEnd: Int,
    ) {
        if (groupTagName != null) {
            if (tagName == groupTagName) {
                groupCount++
            }
            groupBuilder.append(source)
        } else {
            val attributes = workAttributes
            val html = workSubBuilder
            html.builder = source
            html.start = attrsStart
            html.end = attrsEnd
            attributes.html = html
            val groupStart = callback.onStartElement(this, tagName, attributes)
            if (groupStart) {
                groupTagName = tagName
                groupCount = 1
                groupBuilder.setLength(0)
            }
        }
    }

    @Throws(ParseException::class)
    private fun onEndElement(
        tagName: String,
        source: CharSequence,
    ) {
        if (groupTagName == null) {
            callback.onEndElement(this, tagName)
        } else if (tagName == groupTagName) {
            groupCount--
            if (groupCount == 0) {
                callback.onGroupComplete(this, groupBuilder.toString())
                groupTagName = null
            }
        }
        if (groupTagName != null) {
            groupBuilder.append(source)
        }
    }

    @Throws(ParseException::class)
    private fun onText(text: CharSequence) {
        if (groupTagName != null) {
            groupBuilder.append(text)
        } else {
            callback.onText(this, text)
        }
    }

    companion object {
        @Public
        @JvmStatic
        @Throws(ParseException::class)
        fun parse(
            source: String,
            callback: Callback,
        ) {
            try {
                parse(StringReader(source), callback)
            } catch (e: IOException) {
                throw ParseException(e)
            }
        }

        @Public
        @JvmStatic
        @Throws(IOException::class, ParseException::class)
        fun parse(
            reader: Reader,
            callback: Callback,
        ) {
            GroupParser(callback).convert(reader)
        }

        private val CHARACTERS_TAG_NAME_END = charArrayOf(' ', '\r', '\n', '\t')
        private val CHARACTERS_TAG_START_END = charArrayOf('<', '>')
        private val CHARACTERS_TAG_START = charArrayOf('<')
        private val CHARACTERS_TAG_END = charArrayOf('>')

        @JvmStatic
        fun extractAttr(
            html: CharSequence?,
            attribute: String?,
        ): String? {
            if (html != null && !attribute.isNullOrEmpty()) {
                val find = "$attribute="
                // Fast match \b${attr}=
                var index = -1
                do {
                    index = StringUtils.indexOf(html, index + 1, find)
                } while (index > 0 && html[index - 1] > ' ')

                if (index >= 0) {
                    index += attribute.length + 1
                    val c = html[index]
                    if (c == '\'' || c == '"') {
                        for (i in index + 1 until html.length) {
                            if (html[i] == c) {
                                return html.subSequence(index + 1, i).toString()
                            }
                        }
                    } else {
                        val endIndex = StringUtils.nearestIndexOf(html, index, ' ', '\r', '\n', '\t')
                        if (endIndex >= index) {
                            return html.subSequence(index, endIndex).toString()
                        }
                        return html.subSequence(index, html.length).toString()
                    }
                }
            }
            return null
        }
    }
}
