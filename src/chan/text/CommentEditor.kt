package chan.text

import android.text.Editable
import android.util.SparseArray
import android.util.SparseIntArray
import android.widget.EditText
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.ChanMarkup
import java.util.regex.Pattern

@Extendable
open class CommentEditor
    @Public
    constructor() {
        private val tags = SparseArray<Tag>()
        private val similar = SparseIntArray()

        private var unorderedListMark: String? = "- "
        private var orderedListMark: String? = null

        class Tag(
            @JvmField val open: String,
            @JvmField val close: String,
            @JvmField val flags: Int,
        )

        class FormatResult(
            @JvmField val start: Int,
            @JvmField val end: Int,
        )

        @Public
        fun addTag(
            what: Int,
            open: String,
            close: String,
        ) {
            addTag(what, open, close, 0)
        }

        @Public
        fun addTag(
            what: Int,
            open: String,
            close: String,
            flags: Int,
        ) {
            tags.put(what, Tag(open, close, flags))
        }

        @Public
        fun setUnorderedListMark(mark: String?) {
            unorderedListMark = mark
        }

        @Public
        fun setOrderedListMark(mark: String?) {
            orderedListMark = mark
        }

        fun getUnorderedListMark(): String? = unorderedListMark

        fun getOrderedListMark(): String? = orderedListMark

        fun getTag(
            what: Int,
            close: Boolean,
        ): String? {
            val tag = tags.get(what) ?: return null
            return if (close) tag.close else tag.open
        }

        open fun getTag(
            what: Int,
            close: Boolean,
            length: Int,
        ): String? = getTag(what, close)

        fun getAllTags(): SparseArray<Tag> = tags

        fun handleSimilar(supportedTags: Int) {
            similar.clear()
            val size = tags.size()
            for (i in 0 until size) {
                val what1 = tags.keyAt(i)
                if (supportedTags and what1 == what1) {
                    val tag1 = tags.get(what1)
                    for (j in i + 1 until size) {
                        val what2 = tags.keyAt(j)
                        if (supportedTags and what2 == what2) {
                            val tag2 = tags.get(what2)
                            if (tag1.open.endsWith(tag2.open) && tag1.close.startsWith(tag2.close)) {
                                similar.put(what2, what1)
                            }
                            if (tag2.open.endsWith(tag1.open) && tag2.close.startsWith(tag1.close)) {
                                similar.put(what1, what2)
                            }
                        }
                    }
                }
            }
        }

        fun formatSelectedText(
            commentView: EditText,
            what: Int,
        ) {
            val start = commentView.selectionStart
            val end = commentView.selectionEnd
            if (start == -1 || end == -1) {
                return
            }
            val result = formatSelectedText(commentView.text, what, start, end)
            if (result != null) {
                commentView.setSelection(result.start, result.end)
            }
        }

        open fun formatSelectedText(
            editable: Editable,
            what: Int,
            start: Int,
            end: Int,
        ): FormatResult? {
            val tag = tags.get(what) ?: return null
            val oneLine = tag.flags and FLAG_ONE_LINE == FLAG_ONE_LINE
            if (oneLine && end > start) {
                return formatSelectedTextOneLine(editable, what, tag, start, end)
            }
            return formatSelectedTextLineDirectly(editable, what, tag, start, end)
        }

        private fun formatSelectedTextOneLine(
            editable: Editable,
            what: Int,
            tag: Tag,
            startArg: Int,
            endArg: Int,
        ): FormatResult {
            var start = startArg
            var end = endArg
            var newStart = -1
            var newEnd = -1
            var nextLine = start
            val open = tag.open
            val close = tag.close
            var i = start
            while (i <= end) {
                val c = if (i == end) '\n' else editable[i]
                if (c == '\n') {
                    var lineStart = nextLine
                    var lineEnd = i
                    if (lineEnd > lineStart) {
                        val mayCutStart = lineStart > start
                        val mayCutEnd = lineEnd < end
                        if (mayCutStart || mayCutEnd) {
                            val line = editable.subSequence(lineStart, lineEnd).toString()
                            val cutStart = mayCutStart && line.startsWith(open)
                            val cutEnd = mayCutEnd && line.endsWith(close)
                            var openLength = open.length
                            var closeLength = close.length
                            val similar = this.similar.get(what, -1)
                            if (similar != -1) {
                                val similarTag = tags.get(similar)
                                if (cutStart &&
                                    line.startsWith(similarTag.open) &&
                                    !line.startsWith(open + similarTag.open)
                                ) {
                                    openLength = similarTag.open.length
                                }
                                if (cutEnd &&
                                    line.endsWith(similarTag.close) &&
                                    !line.endsWith(similarTag.close + close)
                                ) {
                                    closeLength = similarTag.close.length
                                }
                            }
                            if (mayCutStart && mayCutEnd) {
                                if (cutStart && cutEnd) {
                                    // Handle lines with open tag at start and close tag at end
                                    lineStart += openLength
                                    lineEnd -= closeLength
                                }
                            } else {
                                if (cutStart) {
                                    lineStart += openLength
                                }
                                if (cutEnd) {
                                    lineEnd -= closeLength
                                }
                            }
                        }
                        val result = formatSelectedTextLineDirectly(editable, what, tag, lineStart, lineEnd)
                        val startShift = result.start - lineStart
                        val endShift = result.end - lineEnd
                        end += startShift + endShift
                        i += startShift + endShift
                        if (newStart == -1) {
                            newStart = result.start
                            start += startShift // Count first start shift
                        }
                        newEnd = result.end
                    } else {
                        if (newStart == -1) {
                            newStart = start
                        }
                        newEnd = end
                    }
                    nextLine = i + 1
                }
                i++
            }
            return FormatResult(newStart, newEnd)
        }

        private fun formatSelectedTextLineDirectly(
            editable: Editable,
            what: Int,
            tag: Tag,
            start: Int,
            end: Int,
        ): FormatResult {
            val similar = this.similar.get(what, -1)
            if (similar != -1) {
                val text = editable.toString()
                val similarTag = tags.get(similar)
                val textBeforeSelection = text.substring(maxOf(0, start - similarTag.open.length - 1), start)
                val textAfterSelection =
                    text.substring(
                        end,
                        minOf(text.length, end + similarTag.open.length + 1),
                    )
                if (textBeforeSelection.endsWith(similarTag.open)) {
                    var apply =
                        if (textBeforeSelection.length == similarTag.open.length) {
                            true
                        } else {
                            textBeforeSelection[0] != similarTag.open[similarTag.open.length - 1]
                        }
                    if (apply && textAfterSelection.startsWith(similarTag.close)) {
                        apply =
                            if (textAfterSelection.length == similarTag.close.length) {
                                true
                            } else {
                                textAfterSelection[similarTag.close.length] != similarTag.close[0]
                            }
                        if (apply) {
                            val sh = tag.open.length
                            editable.insert(start, tag.open).insert(end + sh, tag.close)
                            return FormatResult(start + sh, end + sh)
                        }
                    }
                }
            }
            val text = editable.toString()
            val open = tag.open
            val close = tag.close
            val selectedText = text.substring(start, end)
            val textBeforeSelection = text.substring(maxOf(0, start - open.length), start)
            val textAfterSelection = text.substring(end, minOf(text.length, end + close.length))
            return if (textBeforeSelection.equals(open, ignoreCase = true) &&
                textAfterSelection.equals(close, ignoreCase = true)
            ) {
                editable.replace(start - open.length, end + close.length, selectedText)
                FormatResult(start - open.length, end - open.length)
            } else {
                editable.replace(start, end, open + selectedText + close)
                FormatResult(start + open.length, end + open.length)
            }
        }

        open fun removeTags(comment: String?): String? {
            var result = comment
            if (result != null) {
                val tags = getAllTags()
                for (i in 0 until tags.size()) {
                    val tag = tags.get(tags.keyAt(i))
                    if (tag != null) {
                        result = result!!.replace(tag.open, "").replace(tag.close, "")
                    }
                }
            }
            return result
        }

        @Extendable
        open class BulletinBoardCodeCommentEditor
            @Public
            constructor() : CommentEditor() {
                init {
                    addTag(ChanMarkup.TAG_BOLD, "[b]", "[/b]")
                    addTag(ChanMarkup.TAG_ITALIC, "[i]", "[/i]")
                    addTag(ChanMarkup.TAG_UNDERLINE, "[u]", "[/u]")
                    addTag(ChanMarkup.TAG_OVERLINE, "[o]", "[/o]")
                    addTag(ChanMarkup.TAG_STRIKE, "[s]", "[/s]")
                    addTag(ChanMarkup.TAG_SUBSCRIPT, "[sub]", "[/sub]")
                    addTag(ChanMarkup.TAG_SUPERSCRIPT, "[sup]", "[/sup]")
                    addTag(ChanMarkup.TAG_SPOILER, "[spoiler]", "[/spoiler]")
                    addTag(ChanMarkup.TAG_CODE, "[code]", "[/code]")
                    addTag(ChanMarkup.TAG_ASCII_ART, "[aa]", "[/aa]")
                }
            }

        @Extendable
        open class WakabaMarkCommentEditor
            @Public
            constructor() : CommentEditor() {
                init {
                    addTag(ChanMarkup.TAG_BOLD, "**", "**", FLAG_ONE_LINE)
                    addTag(ChanMarkup.TAG_ITALIC, "*", "*", FLAG_ONE_LINE)
                    addTag(ChanMarkup.TAG_SPOILER, "%%", "%%", FLAG_ONE_LINE)
                    addTag(ChanMarkup.TAG_CODE, "`", "`", FLAG_ONE_LINE)
                }

                override fun getTag(
                    what: Int,
                    close: Boolean,
                    length: Int,
                ): String? {
                    val result = super.getTag(what, close, length)
                    if (what == ChanMarkup.TAG_STRIKE && result == null) {
                        return if (close) "^H".repeat(length) else ""
                    }
                    return result
                }

                override fun formatSelectedText(
                    editable: Editable,
                    what: Int,
                    start: Int,
                    end: Int,
                ): FormatResult? {
                    if (what == ChanMarkup.TAG_STRIKE && super.getTag(what, false) == null) {
                        val text = editable.toString()
                        val textAfterSelection = text.substring(end, text.length)
                        val matcher = MULTIPLE_STRIKES.matcher(textAfterSelection)
                        return if (matcher.find()) {
                            val strikes = matcher.group(1)!!
                            editable.replace(end, end + strikes.length, "")
                            FormatResult(start, end)
                        } else {
                            val count = end - start
                            if (count > 0) {
                                editable.insert(end, "^H".repeat(count))
                                FormatResult(start, end)
                            } else {
                                editable.insert(end, "^H")
                                FormatResult(end + 2, end + 2)
                            }
                        }
                    } else {
                        return super.formatSelectedText(editable, what, start, end)
                    }
                }

                override fun removeTags(comment: String?): String? = super.removeTags(comment)?.replace("^H", "")

                companion object {
                    private val MULTIPLE_STRIKES = Pattern.compile("^((?:\\^H)+)")
                }
            }

        companion object {
            @Public const val FLAG_ONE_LINE = 0x00000001
        }
    }
