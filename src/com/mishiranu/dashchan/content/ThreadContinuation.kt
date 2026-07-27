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

    /** Subjects are re-typed every thread and drift, so a word added or dropped must not break the chain. */
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

        /** One of the subjects is empty — very common, comment-only original posts. */
        INCONCLUSIVE,

        /** The subjects are unrelated, or the volume number went backwards. */
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
    private val PATTERN_TRAILING_DIGITS = Pattern.compile("\\d+")
    private val PATTERN_VOLUME_MARKER = Pattern.compile("[\u2116#\uFF03]")

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
        val current = normalize(currentSubject)
        val next = normalize(nextSubject)
        if (current.isEmpty() || next.isEmpty()) {
            return SubjectRelation.INCONCLUSIVE
        }
        if (!skeletonsMatch(skeleton(current), skeleton(next))) {
            return SubjectRelation.MISMATCH
        }
        val currentVolume = volume(current)
        val nextVolume = volume(next)
        val volumesMatch =
            when {
                currentVolume != null && nextVolume != null -> {
                    nextVolume > currentVolume && nextVolume - currentVolume <= MAX_VOLUME_GAP
                }

                currentVolume == null && nextVolume != null -> {
                    nextVolume == 2L
                }

                currentVolume == null && nextVolume == null -> {
                    true
                }

                // The predecessor is numbered and the successor is not: the chain broke
                else -> {
                    false
                }
            }
        return if (volumesMatch) SubjectRelation.MATCH else SubjectRelation.MISMATCH
    }

    /** NFKC, lower case, every non-alphanumeric run (`№`, `#`, punctuation) collapsed to a space. */
    fun normalize(subject: String?): String {
        if (subject.isNullOrEmpty()) {
            return ""
        }
        // The volume markers go first, because NFKC expands "№" into the *letters* "No" while "#"
        // stays punctuation: leaving them in makes the two spellings of the same subject differ, and
        // leaves a stray "no" token in the skeleton of the numbered side only.
        val unmarked = PATTERN_VOLUME_MARKER.matcher(subject).replaceAll(" ")
        val decomposed = Normalizer.normalize(unmarked, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val builder = StringBuilder(decomposed.length)
        for (c in decomposed) {
            builder.append(if (Character.isLetterOrDigit(c)) c else ' ')
        }
        return collapse(builder.toString())
    }

    /** The last digit run of a normalized subject: "ukraine 2024 57" is volume 57, not 2024. */
    fun volume(normalizedSubject: String): Long? {
        val matcher = PATTERN_TRAILING_DIGITS.matcher(normalizedSubject)
        var value: String? = null
        while (matcher.find()) {
            value = matcher.group()
        }
        // Long.MAX_VALUE has 19 digits: anything longer is not a volume anyway
        return if (value != null && value.length <= 18) value.toLong() else null
    }

    /** A normalized subject with its volume number removed, so only the recurring title is left. */
    fun skeleton(normalizedSubject: String): String {
        val matcher = PATTERN_TRAILING_DIGITS.matcher(normalizedSubject)
        var start = -1
        var end = -1
        while (matcher.find()) {
            start = matcher.start()
            end = matcher.end()
        }
        if (start < 0) {
            return normalizedSubject
        }
        return collapse(normalizedSubject.substring(0, start) + " " + normalizedSubject.substring(end))
    }

    private fun skeletonsMatch(
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
        val intersection = lhsTokens.count { rhsTokens.contains(it) }
        val union = lhsTokens.size + rhsTokens.size - intersection
        return union > 0 && intersection.toDouble() / union >= MIN_TOKEN_JACCARD
    }

    private fun tokens(skeleton: String): Set<String> = if (skeleton.isEmpty()) emptySet() else skeleton.split(' ').filterTo(HashSet()) { it.isNotEmpty() }

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
