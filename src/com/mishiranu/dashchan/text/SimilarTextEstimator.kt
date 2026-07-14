package com.mishiranu.dashchan.text

import java.util.Locale

class SimilarTextEstimator(
    private val maxLength: Int,
    private val removePostLinks: Boolean,
) {
    fun <E> checkSimiliar(
        wordsData1: WordsData<E>?,
        wordsData2: WordsData<E>?,
    ): Boolean {
        if (wordsData1 != null && wordsData2 != null) {
            val wdc = wordsData1.count
            val swdc = wordsData2.count
            if (wdc >= swdc / 2 && wdc <= swdc * 2) {
                var similarity = 0
                val swords = wordsData2.words
                for (word in wordsData1.words) {
                    if (swords.contains(word)) {
                        similarity++
                    } else {
                        similarity--
                    }
                }
                // 2/3 similarity
                return similarity >= swords.size / 3
            }
        }
        return false
    }

    fun <E> getWords(text: String?): WordsData<E>? {
        if (text == null) {
            return null
        }
        var words: HashSet<String>? = null
        var count = 0
        val cut = if (text.length > maxLength) text.substring(0, maxLength) else text
        val builder = StringBuilder(cut.lowercase(Locale.getDefault()))
        if (removePostLinks) {
            var index = 0
            while (true) {
                index = builder.indexOf(">>", index)
                if (index == -1) {
                    break
                }
                var charCount = 0
                for (i in index + 2 until builder.length) {
                    if (Character.isDigit(builder[i])) {
                        charCount++
                    } else {
                        break
                    }
                }
                if (charCount > 0) {
                    builder.delete(index, index + 2 + charCount)
                    index++
                } else {
                    index += 2
                }
            }
        }
        // Remove not letter characters
        for (i in builder.indices) {
            val c = builder[i]
            if (c != ' ' && !Character.isLetterOrDigit(c)) {
                builder.setCharAt(i, ' ')
            }
        }
        // Fast String.split(" +")
        var inWord = false
        var wordStart = 0
        val length = builder.length
        for (i in 0..length) {
            val c = if (i < length) builder[i] else ' '
            if (inWord) {
                if (c == ' ') {
                    inWord = false
                    val word = builder.substring(wordStart, i)
                    if (words == null) {
                        words = HashSet()
                    }
                    words.add(word)
                    count++
                }
            } else {
                if (c != ' ') {
                    inWord = true
                    wordStart = i
                }
            }
        }
        return if (count >= MIN_WORDS_COUNT) WordsData(words!!, count) else null
    }

    class WordsData<E>(
        @JvmField val words: Set<String>,
        @JvmField val count: Int,
    ) {
        @JvmField var extra: E? = null
    }

    companion object {
        private const val MIN_WORDS_COUNT = 1
    }
}
