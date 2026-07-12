package com.mishiranu.dashchan.content

import android.content.Context
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.text.JsonSerial
import chan.text.ParseException
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.content.model.PostNumber
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseNullable
import com.mishiranu.dashchan.content.model.PostNumber.Companion.parseOrThrow
import com.mishiranu.dashchan.content.storage.AutohideStorage
import com.mishiranu.dashchan.content.storage.AutohideStorage.AutohideItem
import com.mishiranu.dashchan.text.SimilarTextEstimator
import com.mishiranu.dashchan.text.SimilarTextEstimator.WordsData
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.IOException
import java.util.Arrays

class HidePerformer(context: Context?) {
    interface PostsProvider {
        fun findPostItem(postNumber: PostNumber?): PostItem?
    }

    private val autohideStorage = AutohideStorage.getInstance()
    private val estimator = SimilarTextEstimator(MAX_COMMENT_LENGTH, true)
    private val autohidePrefix: String
    private var postsProvider: PostsProvider? = null

    private var replies: LinkedHashSet<PostNumber>? = null
    private var names: LinkedHashSet<String>? = null
    private var similar: ArrayList<WordsData<PostNumber?>>? = null

    init {
        autohidePrefix = if (context != null) context.getString(R.string.autohide) + ": " else ""
    }

    fun setPostsProvider(postsProvider: PostsProvider?) {
        this.postsProvider = postsProvider
    }

    fun checkHidden(chan: Chan, postItem: PostItem): String? {
        var message = checkHiddenByReplies(postItem)
        if (message == null) {
            message = checkHiddenByName(chan, postItem)
        }
        if (message == null) {
            message = checkHiddenBySimilarPost(chan, postItem)
        }
        if (message == null) {
            message = checkHiddenGlobalAutohide(chan, postItem)
        }
        if (message == null) {
            message = checkHiddenIfAIGenerated(chan, postItem)
        }
        return if (message != null) autohidePrefix + message else null
    }

    private fun checkHiddenByReplies(postItem: PostItem?): String? {
        var postItem = postItem
        if (replies != null && postsProvider != null) {
            if (replies!!.contains(postItem!!.getPostNumber())) {
                return "replies tree " + postItem.getPostNumber()
            }
            for (postNumber in postItem.getReferencesTo()) {
                postItem = postsProvider!!.findPostItem(postNumber)
                if (postItem != null) {
                    val message = checkHiddenByReplies(postItem)
                    if (message != null) {
                        return message
                    }
                }
            }
        }
        return null
    }

    private fun checkHiddenByName(chan: Chan, postItem: PostItem): String? {
        if (names != null) {
            val name = postItem.getFullName(chan).toString()
            if (names!!.contains(name)) {
                return "name " + name
            }
        }
        return null
    }

    private fun checkHiddenBySimilarPost(chan: Chan, postItem: PostItem): String? {
        if (similar != null) {
            val wordsData =
                estimator.getWords<PostNumber?>(postItem.getComment(chan).toString())
            if (wordsData != null) {
                for (similarWordsData in similar) {
                    if (estimator.checkSimiliar<PostNumber?>(wordsData, similarWordsData)) {
                        return "similar to " + similarWordsData.extra
                    }
                }
            }
        }
        return null
    }

    private fun checkHiddenIfAIGenerated(chan: Chan, postItem: PostItem): String? {
        if (chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING) && Preferences.isHideAIPosts(
                chan
            ) && postItem.getPost().isAiGenerated
        ) {
            return "Is AI-generated!"
        }
        return null
    }

    private fun checkHiddenGlobalAutohide(chan: Chan, postItem: PostItem): String? {
        val boardName = postItem.getBoardName()
        val originalPostNumber = postItem.getOriginalPostNumber()
        val originalPostNumberString: String = (if (originalPostNumber != null)
            originalPostNumber.toString()
        else
            null)!!
        val originalPost = postItem.isOriginalPost()
        val sage = postItem.isSage()
        var subject: String? = null
        var comment: String? = null
        var names: MutableList<String>? = null
        val autohideItems = autohideStorage.getItems()
        for (i in autohideItems.indices) {
            val autohideItem = autohideItems.get(i)
            // AND selection (only if chan, board, thread, op, and sage match the rule)
            if (autohideItem.chanNames == null || autohideItem.chanNames!!.contains(chan.name!!)) {
                if (StringUtils.isEmpty(autohideItem.boardName) || boardName == null || autohideItem.boardName == boardName) {
                    if (StringUtils.isEmpty(autohideItem.threadNumber) || autohideItem.boardName != null &&
                        autohideItem.threadNumber == originalPostNumberString
                    ) {
                        if ((!autohideItem.optionOriginalPost || autohideItem.optionOriginalPost == originalPost)
                            && (!autohideItem.optionSage || autohideItem.optionSage == sage)
                        ) {
                            var result: String?
                            // OR selection (hide if subject, comment, or name match the rule)
                            if (autohideItem.optionSubject) {
                                if (subject == null) {
                                    subject = postItem.getSubject()
                                }
                                if ((autohideItem.find(subject).also { result = it }) != null) {
                                    return autohideItem.getReason(
                                        AutohideItem
                                            .ReasonSource.SUBJECT, comment, result
                                    )
                                }
                            }
                            if (autohideItem.optionComment) {
                                if (comment == null) {
                                    comment = postItem.getComment(chan).toString()
                                }
                                if ((autohideItem.find(comment).also { result = it }) != null) {
                                    return autohideItem.getReason(
                                        AutohideItem
                                            .ReasonSource.COMMENT, comment, result
                                    )
                                }
                            }
                            if (autohideItem.optionName) {
                                if (names == null) {
                                    val name = postItem.getFullName(chan).toString()
                                    val icons = postItem.getIcons()
                                    if (!icons.isEmpty()) {
                                        names = ArrayList<String>(1 + icons.size)
                                        names.add(name)
                                        for (icon in icons) {
                                            names.add(icon.title)
                                        }
                                    } else {
                                        names = mutableListOf<String?>(name)
                                    }
                                }
                                for (name in names) {
                                    if ((autohideItem.find(name).also { result = it }) != null) {
                                        return autohideItem.getReason(
                                            AutohideItem
                                                .ReasonSource.NAME, name, result
                                        )
                                    }
                                }
                            }
                            if (autohideItem.optionFileName && postItem.hasAttachments()) {
                                for (attachmentItem in postItem.getAttachmentItems()!!) {
                                    val originalName: String =
                                        StringUtils.emptyIfNull(attachmentItem.getOriginalName())
                                    if ((autohideItem.find(originalName)
                                            .also { result = it }) != null
                                    ) {
                                        return autohideItem.getReason(
                                            AutohideItem
                                                .ReasonSource.FILE, originalName, result
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    enum class AddResult {
        SUCCESS, FAIL, EXISTS
    }

    fun addHideByReplies(postItem: PostItem): AddResult {
        if (replies == null) {
            replies = LinkedHashSet<PostNumber>()
        }
        val postNumber = postItem.getPostNumber()
        if (replies!!.contains(postNumber)) {
            return AddResult.EXISTS
        }
        replies!!.add(postNumber)
        return AddResult.SUCCESS
    }

    fun addHideByName(chan: Chan, postItem: PostItem): AddResult {
        if (postItem.isUseDefaultName()) {
            ClickableToast.show(R.string.default_name_cant_be_hidden)
            return AddResult.FAIL
        }
        if (names == null) {
            names = LinkedHashSet<String>()
        }
        val fullName = postItem.getFullName(chan).toString()
        if (names!!.contains(fullName)) {
            return AddResult.EXISTS
        }
        names!!.add(fullName)
        return AddResult.SUCCESS
    }

    fun addHideSimilar(chan: Chan, postItem: PostItem): AddResult {
        val comment = postItem.getComment(chan).toString()
        val wordsData = estimator.getWords<PostNumber?>(comment)
        if (wordsData == null) {
            ClickableToast.show(R.string.too_few_meaningful_words)
            return AddResult.FAIL
        }
        if (similar == null) {
            similar = ArrayList<WordsData<PostNumber?>>()
        }
        val postNumber = postItem.getPostNumber()
        wordsData.extra = postNumber
        // Remove repeats
        for (i in similar!!.indices.reversed()) {
            if (postNumber.equals(similar!!.get(i).extra)) {
                similar!!.removeAt(i)
            }
        }
        similar!!.add(wordsData)
        return AddResult.SUCCESS
    }

    fun hasLocalFilters(): Boolean {
        val repliesLength = if (replies != null) replies!!.size else 0
        val namesLength = if (names != null) names!!.size else 0
        val similarLength = if (similar != null) similar!!.size else 0
        return repliesLength + namesLength + similarLength > 0
    }

    fun getReadableLocalFilters(context: Context): MutableList<String?> {
        val localFilters = ArrayList<String?>()
        if (replies != null) {
            for (postNumber in replies) {
                localFilters.add(
                    context.getString(
                        R.string.replies_to_number__format,
                        postNumber.toString()
                    )
                )
            }
        }
        if (names != null) {
            for (name in names) {
                localFilters.add(context.getString(R.string.with_name_name__format, name))
            }
        }
        if (similar != null) {
            for (wordsData in similar) {
                localFilters.add(
                    context.getString(
                        R.string.similar_to_number__format,
                        wordsData.extra.toString()
                    )
                )
            }
        }
        return localFilters
    }

    fun removeLocalFilter(index: Int) {
        var index = index
        if (replies != null) {
            if (index >= replies!!.size) {
                index -= replies!!.size
            } else {
                Companion.removeFromLinkedHashSet(replies!!, index)
                if (replies!!.isEmpty()) {
                    replies = null
                }
                return
            }
        }
        if (names != null) {
            if (index >= names!!.size) {
                index -= names!!.size
            } else {
                Companion.removeFromLinkedHashSet(names!!, index)
                if (names!!.isEmpty()) {
                    names = null
                }
                return
            }
        }
        if (similar != null) {
            if (index >= similar!!.size) {
                index -= similar!!.size
            } else {
                similar!!.removeAt(index)
                if (similar!!.isEmpty()) {
                    similar = null
                }
                return
            }
        }
    }

    @Throws(IOException::class)
    fun encodeLocalFilters(writer: JsonSerial.Writer) {
        val repliesLength = if (replies != null) replies!!.size else 0
        val namesLength = if (names != null) names!!.size else 0
        val similarLength = if (similar != null) similar!!.size else 0
        writer.startObject()
        if (repliesLength > 0) {
            writer.name("replies")
            writer.startArray()
            for (postNumber in replies!!) {
                writer.value(postNumber.toString())
            }
            writer.endArray()
        }
        if (namesLength > 0) {
            writer.name("names")
            writer.startArray()
            for (name in names!!) {
                writer.value(name)
            }
            writer.endArray()
        }
        if (similarLength > 0) {
            writer.name("similar")
            writer.startArray()
            for (wordsData in similar!!) {
                writer.startObject()
                writer.name("number")
                writer.value(wordsData.extra.toString())
                writer.name("count")
                writer.value(wordsData.count)
                writer.name("words")
                writer.startArray()
                for (word in wordsData.words) {
                    writer.value(word)
                }
                writer.endArray()
                writer.endObject()
            }
            writer.endArray()
        }
        writer.endObject()
    }

    @Throws(IOException::class, ParseException::class)
    fun decodeLocalFilters(reader: JsonSerial.Reader?) {
        this.replies = null
        this.names = null
        this.similar = null
        if (reader != null) {
            reader.startObject()
            while (!reader.endStruct()) {
                when (reader.nextName()) {
                    "replies" -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            if (replies == null) {
                                replies = LinkedHashSet<PostNumber>()
                            }
                            replies!!.add(parseOrThrow(reader.nextString()))
                        }
                    }

                    "names" -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            if (names == null) {
                                names = LinkedHashSet<String>()
                            }
                            names!!.add(reader.nextString()!!)
                        }
                    }

                    "similar" -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            var postNumber: PostNumber? = null
                            var count = 0
                            val words = HashSet<String?>()
                            reader.startObject()
                            while (!reader.endStruct()) {
                                when (reader.nextName()) {
                                    "number" -> {
                                        postNumber = parseOrThrow(reader.nextString())
                                    }

                                    "count" -> {
                                        count = reader.nextInt()
                                    }

                                    "words" -> {
                                        reader.startArray()
                                        while (!reader.endStruct()) {
                                            words.add(reader.nextString())
                                        }
                                    }
                                }
                            }
                            val wordsData =
                                WordsData<PostNumber?>(words, count)
                            wordsData.extra = postNumber
                            if (similar == null) {
                                similar = ArrayList<WordsData<PostNumber?>>()
                            }
                            similar!!.add(wordsData)
                        }
                    }

                    else -> {
                        reader.skip()
                    }
                }
            }
        }
    }

    fun decodeLocalFiltersLegacy(localFilters: Array<Array<String?>?>?) {
        this.replies = null
        this.names = null
        this.similar = null
        if (localFilters != null) {
            for (rule in localFilters) {
                if (rule == null || rule.size < 2) {
                    continue
                }
                when (emptyIfNull(rule[0])) {
                    "replies" -> {
                        val postNumber = parseNullable(rule[1])
                        if (postNumber != null) {
                            if (replies == null) {
                                replies = LinkedHashSet<PostNumber>()
                            }
                            replies!!.add(postNumber)
                        }
                    }

                    "name" -> {
                        if (!isEmpty(rule[1])) {
                            if (names == null) {
                                names = LinkedHashSet<String>()
                            }
                            names!!.add(rule[1]!!)
                        }
                    }

                    "similar" -> {
                        if (rule.size >= 3) {
                            val postNumber = parseNullable(rule[1])
                            if (postNumber != null) {
                                var count = 0
                                try {
                                    count = rule[2]!!.toInt()
                                } catch (e: NumberFormatException) {
                                    // Ignore exception
                                }
                                if (count > 0) {
                                    val words = HashSet<String?>(
                                        Arrays.asList<String?>(*rule).subList(3, rule.size)
                                    )
                                    words.remove(null)
                                    words.remove("")
                                    if (!words.isEmpty()) {
                                        val wordsData =
                                            WordsData<PostNumber?>(words, count)
                                        wordsData.extra = postNumber
                                        if (similar == null) {
                                            similar = ArrayList<WordsData<PostNumber?>>()
                                        }
                                        this.similar!!.add(wordsData)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_COMMENT_LENGTH = 1000

        private fun removeFromLinkedHashSet(set: LinkedHashSet<*>, index: Int) {
            var k = 0
            val iterator: MutableIterator<*> = set.iterator()
            while (iterator.hasNext()) {
                iterator.next()
                if (k++ == index) {
                    iterator.remove()
                    break
                }
            }
        }
    }
}
