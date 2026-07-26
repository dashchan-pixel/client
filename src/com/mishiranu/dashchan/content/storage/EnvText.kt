package com.mishiranu.dashchan.content.storage

/**
 * The text format of the shared command environment (see [CommandsStorage.getEnvText]) — a small
 * subset of what a shell accepts, one `NAME=value` per line:
 *
 * - a value may be wrapped in `'` or `"`; the quotes are not part of it, and the value may run over
 *   several lines until the closing quote. Inside `"` a backslash escapes the next character, inside
 *   `'` everything is literal.
 * - `#` starts a comment (to the end of the line) at a line start or after whitespace, but not
 *   inside quotes.
 * - an unquoted value is trimmed; `NAME=` is a valid, empty value.
 *
 * Lines without a valid identifier key (or with no `=`) are ignored. The text itself is what gets
 * stored, so comments, blank lines and ordering survive editing; [parse] only produces the map the
 * scripts see.
 */
object EnvText {
    // Keys must be usable as `env.NAME`, i.e. valid JS identifiers.
    private val KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Reads the environment text into the key→value map handed to scripts. */
    fun parse(text: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                index++
                continue
            }
            val lineEnd = lineEnd(text, index)
            if (c == '#') {
                index = lineEnd
                continue
            }
            val eq = text.indexOf('=', index)
            if (eq < 0 || eq > lineEnd) {
                index = lineEnd
                continue
            }
            val key = text.substring(index, eq).trim()
            if (!KEY_REGEX.matches(key)) {
                index = lineEnd
                continue
            }
            var start = eq + 1
            while (start < lineEnd && (text[start] == ' ' || text[start] == '\t')) {
                start++
            }
            val quote = if (start < lineEnd) text[start] else ' '
            val closing = if (quote == '"' || quote == '\'') findClosingQuote(text, start) else -1
            if (closing >= 0) {
                result[key] = unescape(text.substring(start + 1, closing), quote)
                // Anything after the closing quote is a trailing comment or stray text.
                index = lineEnd(text, closing + 1)
            } else {
                // Unquoted, or a quote that is never closed: take the rest of the line.
                result[key] = stripComment(text.substring(start, lineEnd)).trim()
                index = lineEnd
            }
        }
        return result
    }

    /**
     * Writes a map out as environment text. Only used to seed the editor for an environment stored
     * before the text became the stored form — normal editing keeps the user's own text.
     */
    fun format(env: Map<String, String>): String = env.entries.joinToString("\n") { "${it.key}=${formatValue(it.value)}" }

    /**
     * Writes a value so that [parse] reads it back unchanged. Quotes are added only when the bare
     * form would lose something: an empty value (which would otherwise look like a line the user is
     * still typing), edge whitespace, a line break, a leading quote, or a `#` that would be taken
     * for a comment. Single quotes are preferred when the value itself contains `"`, so nothing has
     * to be escaped.
     */
    private fun formatValue(value: String): String {
        val bare =
            value.isNotEmpty() &&
                value == value.trim() &&
                '\n' !in value &&
                value[0] != '"' &&
                value[0] != '\'' &&
                stripComment(value) == value
        if (bare) {
            return value
        }
        if ('"' in value && '\'' !in value) {
            return "'$value'"
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** Index of the `\n` at or after [from], or the end of [text]. */
    private fun lineEnd(
        text: String,
        from: Int,
    ): Int {
        val index = text.indexOf('\n', from)
        return if (index >= 0) index else text.length
    }

    /**
     * Index of the quote closing the one at [start], or -1 when it is never closed. Newlines are
     * ordinary characters here — that is what makes multiline values work.
     */
    private fun findClosingQuote(
        text: String,
        start: Int,
    ): Int {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            if (c == '\\' && quote == '"') {
                index += 2
                continue
            }
            if (c == quote) {
                return index
            }
            index++
        }
        return -1
    }

    /** Resolves the backslash escapes of a double-quoted value; single quotes are literal. */
    private fun unescape(
        value: String,
        quote: Char,
    ): String {
        if (quote != '"' || '\\' !in value) {
            return value
        }
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                // Only the characters the writer escapes are unescaped, so a Windows path or a
                // regexp typed by hand keeps its backslashes.
                if (next == '"' || next == '\\') {
                    builder.append(next)
                    index += 2
                    continue
                }
            }
            builder.append(c)
            index++
        }
        return builder.toString()
    }

    /** Drops a trailing `#` comment from an unquoted value (`a#b` is not a comment). */
    private fun stripComment(value: String): String {
        for (index in value.indices) {
            if (value[index] == '#' && (index == 0 || value[index - 1] == ' ' || value[index - 1] == '\t')) {
                return value.substring(0, index)
            }
        }
        return value
    }
}
