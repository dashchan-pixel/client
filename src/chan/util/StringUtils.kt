package chan.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import chan.annotation.Extendable
import chan.annotation.Public
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.text.HtmlParser
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

@Public
object StringUtils {
	@JvmStatic
	fun cutIfLongerToLine(string: String, maxLength: Int, dots: Boolean): String {
		val trimmed = string.replace("\r", "").trim()
		val index = trimmed.indexOf('\n')
		if (index > maxLength / 3) {
			return trimmed.substring(0, index).trim()
		}
		if (trimmed.length > maxLength) {
			return trimmed.substring(0, maxLength).trim().replace(Regex(" +"), " ") + if (dots) "…" else ""
		}
		return trimmed.replace(Regex(" +"), " ")
	}

	@Public
	@JvmStatic
	fun isEmpty(string: CharSequence?): Boolean = string.isNullOrEmpty()

	@Public
	@JvmStatic
	fun isEmptyOrWhitespace(string: CharSequence?): Boolean = string == null || string.toString().isBlank()

	@Public
	@JvmStatic
	fun emptyIfNull(string: CharSequence?): String = string?.toString() ?: ""

	@Public
	@JvmStatic
	fun nullIfEmpty(string: String?): String? = if (string.isNullOrEmpty()) null else string

	// TODO CHAN
	// Remove this method after updating
	// archiverbt dangeru desustorage nulldvachin
	// Added: 05.10.20 18:45
	@Deprecated("Use equality operator instead")
	@Public
	@JvmStatic
	fun equals(first: String?, second: String?): Boolean = first == second

	@JvmStatic
	fun compare(first: String?, second: String?, ignoreCase: Boolean): Int {
		if (first === second) {
			return 0
		}
		if (first == null) {
			return -1
		}
		if (second == null) {
			return 1
		}
		return if (ignoreCase) {
			first.uppercase(Locale.getDefault()).compareTo(second.uppercase(Locale.getDefault()))
		} else {
			first.compareTo(second)
		}
	}

	@Public
	@JvmStatic
	fun nearestIndexOf(string: String, start: Int, vararg what: String): Int {
		var index = -1
		for (itWhat in what) {
			val itIndex = string.indexOf(itWhat, start)
			if (itIndex >= 0 && (itIndex < index || index == -1)) {
				index = itIndex
			}
		}
		return index
	}

	@Public
	@JvmStatic
	fun nearestIndexOf(string: CharSequence, start: Int, vararg what: Char): Int {
		for (i in start until string.length) {
			val c = string[i]
			if (what.any { it == c }) {
				return i
			}
		}
		return -1
	}

	@JvmStatic
	fun indexOf(string: CharSequence, fromIndex: Int, what: CharSequence): Int {
		val target = what.length
		if (target == 0) {
			return fromIndex
		}
		var count = 0
		for (i in fromIndex until string.length) {
			if (string[i] == what[count]) {
				if (++count == target) {
					return i - target + 1
				}
			} else {
				count = 0
			}
		}
		return -1
	}

	@Extendable
	fun interface ReplacementCallback {
		@Extendable
		fun getReplacement(matcher: Matcher): String?
	}

	@Public
	@JvmStatic
	fun replaceAll(string: String?, regularExpression: String,
			replacementCallback: ReplacementCallback): String? {
		return replaceAll(string, Pattern.compile(regularExpression), replacementCallback)
	}

	@Public
	@JvmStatic
	fun replaceAll(string: String?, pattern: Pattern, replacementCallback: ReplacementCallback): String? {
		if (string == null) {
			return null
		}
		var buffer: StringBuffer? = null
		val matcher = pattern.matcher(string)
		while (matcher.find()) {
			if (buffer == null) {
				buffer = StringBuffer()
			}
			val replacement = replacementCallback.getReplacement(matcher)?.let { Matcher.quoteReplacement(it) }
			matcher.appendReplacement(buffer, replacement)
		}
		if (buffer != null) {
			matcher.appendTail(buffer)
			return buffer.toString()
		}
		return string
	}

	@JvmStatic
	fun formatHex(bytes: ByteArray?): String? {
		if (bytes == null) {
			return null
		}
		val builder = StringBuilder(bytes.size * 2)
		for (b in bytes) {
			if (b.toInt() and 0xf0 == 0) {
				builder.append(0)
			}
			builder.append((b.toInt() and 0xff).toString(16))
		}
		return builder.toString()
	}

	@JvmStatic
	fun formatFileSize(size: Long, upperCase: Boolean): String {
		val kb = size / 1000
		return if (kb >= 1000) String.format(Locale.US, "%.1f", kb / 1000f) + " MB"
		else kb.toString() + if (upperCase) " KB" else " kB"
	}

	@JvmStatic
	fun formatFileSizeMegabytes(size: Long): String {
		return String.format(Locale.US, "%.2f", size / 1000f / 1000f) + " MB"
	}

	@JvmStatic
	fun stripTrailingZeros(string: String): String {
		var length = string.length
		for (i in string.length - 1 downTo 0) {
			val c = string[i]
			if (c == '0') {
				length = i
			} else if (c == '.') {
				length = i
				break
			} else {
				break
			}
		}
		return if (length < string.length) string.substring(0, length) else string
	}

	@JvmStatic
	fun appendSpan(builder: SpannableStringBuilder, text: CharSequence,
			vararg spans: Any): SpannableStringBuilder {
		val start = builder.length
		builder.append(text)
		val end = builder.length
		for (what in spans) {
			builder.setSpan(what, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		return builder
	}

	@JvmStatic
	fun removeSingleDot(string: String?): String? {
		if (string == null || !string.endsWith(".")) {
			return string
		}
		val temp = string.replace(".", "")
		return if (string.length - temp.length == 1) temp else string
	}

	@JvmStatic
	fun escapeFile(string: String?, isPath: Boolean): String? {
		if (string == null) {
			return null
		}
		var builder: StringBuilder? = null
		for (i in string.indices) {
			val c = string[i]
			if (c == '\\' || c == '/' && !isPath || c == ':' ||
					c == '*' || c == '?' || c == '|' || c == '<' || c == '>') {
				if (builder == null) {
					builder = StringBuilder(string)
				}
				builder.setCharAt(i, '_')
			}
		}
		return builder?.toString() ?: string
	}

	private fun findLinkEnd(string: String, start: Int): Int {
		var braces = 0
		var validEndReached = false
		val length = string.length
		var i = start
		while (true) {
			val prevValidEndReached = validEndReached
			validEndReached = false
			val c = if (i < length) string[i] else '\u0000'
			when (c) {
				'(', '[', '{' -> braces++
				')', ']', '}' -> {
					if (--braces < 0) {
						return i
					}
				}
				'\u0000', '\n', '\r', '\t', ' ', '<', '"' -> {
					return if (prevValidEndReached) i - 1 else i
				}
				'.', ',', ':', ';' -> validEndReached = true
			}
			if (c == '\u0000') {
				break
			}
			i++
		}
		return -1
	}

	@JvmStatic
	fun getNormalizedOriginalName(originalName: String?, fileName: String): String? {
		return getNormalizedOriginalName(originalName, fileName, getFileExtension(fileName))
	}

	private fun getNormalizedOriginalName(originalName: String?, fileName: String,
			fileExtension: String?): String? {
		if (originalName.isNullOrEmpty()) {
			return null
		}
		var result = escapeFile(originalName, false)!!
		val extension = fileExtension ?: getFileExtension(fileName)
		if (extension != null) {
			val normalizedOriginalExtension = getNormalizedExtension(getFileExtension(result))
			val normalizedFileExtension = getNormalizedExtension(extension)
			if (normalizedFileExtension != normalizedOriginalExtension) {
				result += ".$extension"
			}
			if (fileName == result) {
				return null
			}
		}
		return result
	}

	@JvmStatic
	fun getNormalizedExtension(extension: String?): String? {
		return C.EXTENSION_TRANSFORMATION[extension] ?: extension
	}

	@Public
	@JvmStatic
	fun linkify(string: String?): String? {
		if (string == null) {
			return null
		}
		var candidates: ArrayList<IntArray>? = null
		var index = -1
		val length = string.length
		var insideLink = false
		while (true) {
			if (insideLink) {
				index = string.indexOf("</a>", index + 1)
				if (index == -1) {
					break
				}
				insideLink = false
				continue
			} else {
				val httpIndex = string.indexOf("http", index + 1)
				if (httpIndex == -1) {
					break
				}
				val openLinkIndex = string.indexOf("<a ", index + 1)
				if (openLinkIndex != -1 && openLinkIndex < httpIndex) {
					insideLink = true
					index = openLinkIndex
					continue
				}
				index = httpIndex
			}
			if (index + 8 < length) {
				val https = string[index + 4] == 's'
				val schemeOffset = if (https) 1 else 0
				if (string.substring(index + 4 + schemeOffset, index + 7 + schemeOffset) == "://") {
					// http:// or https:// reached
					if (index >= 6) {
						val before = string.substring(index - 6, index)
						if (before.contains("href=")) continue // Ignore <a href="https://..."> reached
					}
					val start = index + 7 + schemeOffset
					val end = findLinkEnd(string, start)
					if (end > start) {
						if (end + 4 <= length && string.substring(end, end + 4) == "</a>") {
							index = end + 3
							continue // Ignore <a ...>https://...</a>
						}
						if (candidates == null) {
							candidates = ArrayList()
						}
						candidates.add(intArrayOf(index, end))
						index = end - 1
					}
				}
			}
		}
		if (candidates == null) {
			return string
		}
		val builder = StringBuilder()
		var prev: IntArray? = null
		for (links in candidates) {
			val from = prev?.get(1) ?: 0
			builder.append(string, from, links[0])
			builder.append("<a href=\"")
			builder.append(string, links[0], links[1])
			builder.append("\">")
			builder.append(string, links[0], links[1])
			builder.append("</a>")
			prev = links
		}
		builder.append(string, prev!![1], length)
		return builder.toString()
	}

	@JvmStatic
	fun fixParsedUriString(uriString: String?): String? {
		if (uriString != null) {
			val end = findLinkEnd(uriString, 0)
			if (end >= 0) {
				return uriString.substring(0, end)
			}
		}
		return uriString
	}

	@JvmStatic
	fun copyToClipboard(context: Context, string: String?) {
		if (!string.isNullOrEmpty()) {
			val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			clipboard.setPrimaryClip(ClipData.newPlainText(null, string))
		}
	}

	private val PATTERN_BOARD_NAME = Pattern.compile("/?([\\w_-]+)/?")

	@JvmStatic
	fun validateBoardName(boardName: String?): String? {
		if (boardName != null) {
			val matcher = PATTERN_BOARD_NAME.matcher(boardName)
			if (matcher.matches()) {
				return matcher.group(1)
			}
		}
		return null
	}

	@JvmStatic
	fun getFileExtension(path: String?): String? {
		if (!path.isNullOrEmpty()) {
			val index1 = path.lastIndexOf('/')
			val index2 = path.lastIndexOf('.')
			if (index1 > index2 || index2 < 0) {
				return null
			}
			return path.substring(index2 + 1).lowercase(Locale.US)
		}
		return null
	}

	@JvmStatic
	fun removeFileExtension(path: String?): String? {
		if (!path.isNullOrEmpty()) {
			val ext = "." + getFileExtension(path)
			return path.replace(ext, "")
		}
		return null
	}

	@JvmStatic
	fun formatBoardTitle(chanName: String, boardName: String?, title: String?): String {
		return "/" + (if (boardName.isNullOrEmpty()) chanName else boardName) +
				if (title.isNullOrEmpty()) "/" else "/ — $title"
	}

	@JvmStatic
	fun formatThreadTitle(chanName: String, boardName: String?, threadNumber: String): String {
		return "/" + (if (boardName.isNullOrEmpty()) chanName else boardName) + "/" + threadNumber
	}

	@Public
	@JvmStatic
	fun clearHtml(string: String?): String {
		if (string.isNullOrEmpty()) {
			return ""
		}
		val length = string.length
		var checkHtmlTag = false
		var checkHtmlEntity = false
		var removeSpaces = false
		for (i in 0 until length) {
			when (string[i]) {
				'<' -> checkHtmlTag = true
				'>' -> if (checkHtmlTag) {
					return HtmlParser.clear(string)
				}
				'&' -> checkHtmlEntity = true
				';' -> if (checkHtmlEntity) {
					return HtmlParser.clear(string)
				}
				' ', '\n', '\r', '\t' -> removeSpaces = true
			}
		}
		if (!removeSpaces) {
			return string
		}
		val builder = StringBuilder()
		var lastSpace = true
		for (i in 0 until length) {
			val c = string[i]
			if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
				if (!lastSpace) {
					lastSpace = true
					builder.append(' ')
				}
			} else {
				lastSpace = false
				builder.append(c)
			}
		}
		if (lastSpace && builder.isNotEmpty()) {
			builder.setLength(builder.length - 1)
		}
		return builder.toString()
	}

	@Public
	@JvmStatic
	fun unescapeHtml(string: String?): String {
		if (string.isNullOrEmpty()) {
			return ""
		}
		val builder = StringBuilder(string.length)
		var index = 0
		while (index < string.length) {
			var start = string.indexOf('&', index)
			val end = if (start >= index) string.indexOf(';', start) else -1
			if (start >= index && end > start) {
				builder.append(string, index, start)
				val realStart = string.lastIndexOf('&', end)
				if (realStart > start) {
					builder.append(string, start, realStart)
					start = realStart
				}
				var value = -1
				val entity = string.substring(start + 1, end)
				if (entity.startsWith("#") && !entity.contains("+") && !entity.contains("-")) {
					try {
						value = if (entity.startsWith("#x") || entity.startsWith("#X")) {
							entity.substring(2).toInt(16)
						} else {
							entity.substring(1).toInt()
						}
					} catch (e: NumberFormatException) {
						// Not a number, ignore exception
					}
				} else {
					value = HtmlParser.SCHEMA.getEntity(entity)
					if (value == 0) {
						value = -1
					}
				}
				if (value >= 0) {
					builder.append(value.toChar())
				} else {
					builder.append(string, start, end + 1)
				}
				index = end + 1
			} else {
				builder.append(string, index, string.length)
				break
			}
		}
		return builder.toString()
	}

	@JvmStatic
	fun reduceEmptyLines(text: CharSequence): CharSequence {
		var result = text
		var builder: SpannableStringBuilder? = null
		var lineBreaks = 0
		for (i in text.indices) {
			if (text[i] == '\n') {
				lineBreaks++
			} else {
				if (lineBreaks > 1) {
					if (builder == null) {
						builder = SpannableStringBuilder(text)
						result = builder
					}
					builder.setSpan(RelativeSizeSpan(0.75f), i - lineBreaks, i,
							SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
				}
				lineBreaks = 0
			}
		}
		return result
	}
}
