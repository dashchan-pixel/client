package com.mishiranu.dashchan.content

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Detection logic behind following a favorite thread into its continuation (a "перекат"): given the
 * raw comment HTML of the newest posts of a thread that has reached its bump limit, find the post
 * that announces the continuation, and decide whether the successor's subject confirms it.
 *
 * Everything here is a pure function of its arguments — no network, no database, no Android. The one
 * piece that needs an extension is link classification, which the caller supplies as a
 * [LinkResolver]; [ThreadContinuationResolver] backs it with `ChanLocator.Safe`.
 */
object ThreadContinuation {
    /**
     * How many of the newest posts are examined. Post numbers are board-global on some chans, so
     * they carry no index within the thread and an exact "is this post past the bump limit" test is
     * impossible; and post counts drift as posts get deleted, which makes a computed ordinal
     * brittle. "The thread is past its limit *and* the post is near the end" captures the same
     * intent and survives the drift.
     */
    const val SCAN_DEPTH = 30

    /** A post this short around the link is an announcement ("ПЕРЕКАТ"), not commentary. */
    private const val RESIDUAL_SHORT_LENGTH = 40

    /** Above this, a post that happens to link the next thread is commentary. Hard reject. */
    private const val RESIDUAL_MAX_LENGTH = 120

    /** A continuation is the next volume, allowing for a few threads that never became favorites. */
    private const val MAX_VOLUME_GAP = 5L

    /**
     * Subjects are re-typed every thread and drift, so a word added or dropped must not break the
     * chain. Only used where the text has to carry the decision alone — see [titlesMatch].
     */
    private const val MIN_TOKEN_JACCARD = 0.75

    fun interface LinkResolver {
        /**
         * Returns the thread number [link] points at, when it points at a thread of the same board
         * of the same chan, and null for anything else.
         */
        fun resolve(link: String): String?
    }

    class Candidate(
        @JvmField val threadNumber: String,
        /**
         * The link was unambiguous: repeated *and* alone in its post. Used to decide the
         * [SubjectRelation.INCONCLUSIVE] case, where the subject can neither confirm nor deny.
         */
        @JvmField val strongLink: Boolean,
    )

    enum class SubjectRelation {
        /** The successor's subject continues the predecessor's. */
        MATCH,

        /**
         * The subject neither confirms nor denies: one of the two is empty (very common,
         * comment-only original posts), or the volume number continues but the title around it does
         * not follow.
         */
        INCONCLUSIVE,

        /** The numbering contradicts, or two unnumbered subjects are unrelated. */
        MISMATCH,
    }

    private val PATTERN_ANCHOR =
        Pattern.compile("<a\\b[^>]*>.*?</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val PATTERN_HREF =
        Pattern.compile(
            "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )
    private val PATTERN_BARE_LINK = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)
    private val PATTERN_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL)
    private val PATTERN_WHITESPACE = Pattern.compile("\\s+")
    private val PATTERN_ENTITY = Pattern.compile("&(#[Xx]?[0-9A-Fa-f]+|[A-Za-z]+);")
    private val PATTERN_DIGITS = Pattern.compile("\\d+")
    private val PATTERN_VOLUME_MARKER = Pattern.compile("[\u2116#\uFF03]")
    private val PATTERN_MARKED_VOLUME = Pattern.compile("#\\s*(\\d+)")

    /**
     * Scans the newest [SCAN_DEPTH] of [comments] (ordered oldest to newest, as the posts of a
     * thread are) from the end and returns the first post that announces a continuation.
     */
    fun findCandidate(
        comments: List<String>,
        threadNumber: String,
        linkResolver: LinkResolver,
    ): Candidate? {
        val stop = (comments.size - SCAN_DEPTH).coerceAtLeast(0)
        for (index in comments.indices.reversed()) {
            if (index < stop) {
                break
            }
            val candidate = findCandidate(comments[index], threadNumber, linkResolver)
            if (candidate != null) {
                return candidate
            }
        }
        return null
    }

    private fun findCandidate(
        comment: String,
        threadNumber: String,
        linkResolver: LinkResolver,
    ): Candidate? {
        if (comment.isEmpty()) {
            return null
        }
        // Extensions emit cross-thread ">>123" quotes as real anchors with an href, so parsing the
        // anchors covers them without a separate pass over the parsed spans.
        val links = ArrayList<String>()
        val hrefMatcher = PATTERN_HREF.matcher(comment)
        while (hrefMatcher.find()) {
            val href = hrefMatcher.group(1) ?: hrefMatcher.group(2) ?: hrefMatcher.group(3)
            if (href != null) {
                links.add(href)
            }
        }
        val withoutAnchors = PATTERN_ANCHOR.matcher(comment).replaceAll(" ")
        val bareMatcher = PATTERN_BARE_LINK.matcher(withoutAnchors)
        while (bareMatcher.find()) {
            links.add(bareMatcher.group())
        }
        if (links.isEmpty()) {
            return null
        }

        var target: String? = null
        var repeats = 0
        for (link in links) {
            // A same-thread ">>" reply is not a continuation link
            val linkThreadNumber =
                linkResolver.resolve(unescapeEntities(link))?.takeIf { it != threadNumber }
                    ?: continue
            if (target == null) {
                target = linkThreadNumber
            } else if (target != linkThreadNumber) {
                // A post linking several threads is a digest, not a continuation
                return null
            }
            repeats++
        }
        if (target == null) {
            return null
        }
        // Continuations are always newer, and a non-numeric thread number carries no order at all
        val order = compareNumeric(target, threadNumber)
        if (order == null || order <= 0) {
            return null
        }

        val residual = clearHtml(PATTERN_BARE_LINK.matcher(withoutAnchors).replaceAll(" "))
        if (residual.length > RESIDUAL_MAX_LENGTH) {
            return null
        }
        val short = residual.length <= RESIDUAL_SHORT_LENGTH
        if (repeats < 2 && !short) {
            return null
        }
        return Candidate(target, repeats >= 2 && short)
    }

    fun subjectRelation(
        currentSubject: String?,
        nextSubject: String?,
    ): SubjectRelation {
        val currentNormalized = normalize(currentSubject)
        val nextNormalized = normalize(nextSubject)
        if (currentNormalized.isEmpty() || nextNormalized.isEmpty()) {
            return SubjectRelation.INCONCLUSIVE
        }
        val current = parse(currentNormalized)
        val next = parse(nextNormalized)
        if (current.volume == null && next.volume == null) {
            // No numbers to go on, so the text has to carry the whole decision by itself
            return if (jaccardMatch(current.title, next.title)) {
                SubjectRelation.MATCH
            } else {
                SubjectRelation.MISMATCH
            }
        }
        if (!volumesContinue(current.volume, next.volume)) {
            return SubjectRelation.MISMATCH
        }
        // A clean increment is never evidence *against* a continuation, so a title that fails to
        // confirm one only falls back on how unambiguous the link was — it must not veto, and it
        // must not blacklist the candidate for good. Series re-title themselves freely between
        // volumes, and the titles are what drifts while the numbering holds: four consecutive
        // volumes of one observed series shared no title word at all, one of them not even written
        // in the same language as the rest.
        return if (titlesMatch(current.title, next.title)) {
            SubjectRelation.MATCH
        } else {
            SubjectRelation.INCONCLUSIVE
        }
    }

    /**
     * A subject split into the two parts that identify a series: the recurring title in front of the
     * volume number, and the number itself.
     *
     * Everything *after* the number is deliberately dropped. Real series carry a fresh subtitle
     * every thread — only the title and the volume number recur, the tail is re-invented — so
     * comparing whole subjects scored three consecutive threads of one series at a token overlap of
     * 0.14 and 0.07 and rejected the chain.
     */
    private class Series(
        val title: String,
        val volume: Long?,
    )

    private fun parse(normalizedSubject: String): Series {
        // A marked number ("topic #12") is the volume even when the free-form part goes on to
        // mention another number; unmarked, the last number is the best guess ("topic 2024 57" is
        // volume 57, not 2024).
        val marked = PATTERN_MARKED_VOLUME.matcher(normalizedSubject)
        var start = -1
        var end = -1
        if (marked.find()) {
            start = marked.start(1)
            end = marked.end(1)
        } else {
            val digits = PATTERN_DIGITS.matcher(normalizedSubject)
            while (digits.find()) {
                start = digits.start()
                end = digits.end()
            }
        }
        // Long.MAX_VALUE has 19 digits: anything longer is not a volume anyway, and stays title text
        val volume =
            if (start >= 0 && end - start <= 18) {
                normalizedSubject.substring(start, end).toLong()
            } else {
                null
            }
        val title = if (volume != null) normalizedSubject.substring(0, start) else normalizedSubject
        return Series(clearMarkers(title), volume)
    }

    private fun volumesContinue(
        current: Long?,
        next: Long?,
    ): Boolean =
        when {
            current != null && next != null -> next > current && next - current <= MAX_VOLUME_GAP

            // An unnumbered thread continues into the second volume of its series
            current == null && next != null -> next == 2L

            // The predecessor is numbered and the successor is not: the chain broke
            else -> false
        }

    /**
     * Titles of a numbered series only have to *overlap*, because the volume numbers already carry
     * the chain and a series routinely gains or loses a word ("topic" → "topic thread"). One title's
     * tokens being contained in the other's is enough; failing that, the same [MIN_TOKEN_JACCARD]
     * the unnumbered case uses.
     */
    private fun titlesMatch(
        lhs: String,
        rhs: String,
    ): Boolean {
        val lhsTokens = tokens(lhs)
        val rhsTokens = tokens(rhs)
        // Checked before the sets are compared, because two subjects that are nothing but a number
        // are equal without naming any series, and so confirm nothing
        if (lhsTokens.isEmpty() || rhsTokens.isEmpty()) {
            return false
        }
        return lhsTokens.containsAll(rhsTokens) ||
            rhsTokens.containsAll(lhsTokens) ||
            jaccard(lhsTokens, rhsTokens) >= MIN_TOKEN_JACCARD
    }

    private fun jaccardMatch(
        lhs: String,
        rhs: String,
    ): Boolean {
        if (lhs == rhs) {
            return true
        }
        val lhsTokens = tokens(lhs)
        val rhsTokens = tokens(rhs)
        if (lhsTokens.isEmpty() || rhsTokens.isEmpty()) {
            return false
        }
        return jaccard(lhsTokens, rhsTokens) >= MIN_TOKEN_JACCARD
    }

    private fun jaccard(
        lhs: Set<String>,
        rhs: Set<String>,
    ): Double {
        val intersection = lhs.count { rhs.contains(it) }
        val union = lhs.size + rhs.size - intersection
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun tokens(title: String): Set<String> = if (title.isEmpty()) emptySet() else title.split(' ').filterTo(HashSet()) { it.isNotEmpty() }

    /**
     * NFKC, lower case, every non-alphanumeric run collapsed to a space — except the volume markers
     * `№ # ＃`, which are unified into an ASCII `#` and kept as a token of their own so [parse] can
     * tell the volume number from a number that merely appears in the subject.
     *
     * The markers are rewritten before NFKC, because NFKC expands "№" into the *letters* "No" while
     * "#" stays punctuation: normalizing first makes the two spellings of one subject differ, and
     * leaves a stray "no" token in the title of the "№" side only.
     */
    private fun normalize(subject: String?): String {
        if (subject.isNullOrEmpty()) {
            return ""
        }
        val marked = PATTERN_VOLUME_MARKER.matcher(subject).replaceAll(" # ")
        val decomposed = Normalizer.normalize(marked, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val builder = StringBuilder(decomposed.length)
        for (c in decomposed) {
            builder.append(if (Character.isLetterOrDigit(c) || c == '#') c else ' ')
        }
        return collapse(builder.toString())
    }

    private fun clearMarkers(title: String): String = collapse(title.replace('#', ' '))

    /**
     * Compares thread numbers of arbitrary length, or returns null when either is not a plain
     * decimal number. Long would overflow on some chans and none of them need it.
     */
    private fun compareNumeric(
        lhs: String,
        rhs: String,
    ): Int? {
        if (lhs.isEmpty() || rhs.isEmpty() || !lhs.all { it in '0'..'9' } || !rhs.all { it in '0'..'9' }) {
            return null
        }
        val left = lhs.trimStart('0')
        val right = rhs.trimStart('0')
        return if (left.length != right.length) left.length - right.length else left.compareTo(right)
    }

    /**
     * Strips tags and entities and collapses whitespace. `HtmlParser` would do a better job but it
     * pulls in Android, and the residual only needs its length and a rough word split.
     */
    private fun clearHtml(html: String): String = collapse(unescapeEntities(PATTERN_TAG.matcher(html).replaceAll(" ")))

    private fun unescapeEntities(string: String): String {
        if (string.indexOf('&') < 0) {
            return string
        }
        val matcher = PATTERN_ENTITY.matcher(string)
        val builder = StringBuilder(string.length)
        var end = 0
        while (matcher.find()) {
            builder.append(string, end, matcher.start())
            builder.append(entity(matcher.group(1)!!) ?: matcher.group())
            end = matcher.end()
        }
        builder.append(string, end, string.length)
        return builder.toString()
    }

    private fun entity(name: String): String? {
        if (name[0] == '#') {
            val hex = name[1] == 'x' || name[1] == 'X'
            val digits = name.substring(if (hex) 2 else 1)
            val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            return if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
        }
        return when (name) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> null
        }
    }

    private fun collapse(string: String): String = PATTERN_WHITESPACE.matcher(string).replaceAll(" ").trim()
}
