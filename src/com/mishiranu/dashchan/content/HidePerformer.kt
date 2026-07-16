package com.mishiranu.dashchan.content

import android.content.Context
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.text.JsonSerial
import chan.text.ParseException
import chan.util.StringUtils
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.R
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

class HidePerformer(
    context: Context?,
) {
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

    fun checkHidden(
        chan: Chan,
        postItem: PostItem,
    ): String? {
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

    private fun checkHiddenByReplies(postItem: PostItem): String? {
        val replies = this.replies ?: return null
        val postsProvider = this.postsProvider ?: return null
        if (replies.contains(postItem.getPostNumber())) {
            return "replies tree " + postItem.getPostNumber()
        }
        for (postNumber in postItem.getReferencesTo()) {
            val referencedPostItem = postsProvider.findPostItem(postNumber) ?: continue
            val message = checkHiddenByReplies(referencedPostItem)
            if (message != null) {
                return message
            }
        }
        return null
    }

    private fun checkHiddenByName(
        chan: Chan,
        postItem: PostItem,
    ): String? {
        val names = this.names ?: return null
        val name = postItem.getFullName(chan).toString()
        if (names.contains(name)) {
            return "name " + name
        }
        return null
    }

    private fun checkHiddenBySimilarPost(
        chan: Chan,
        postItem: PostItem,
    ): String? {
        val similar = this.similar ?: return null
        val wordsData =
            estimator.getWords<PostNumber?>(postItem.getComment(chan).toString()) ?: return null
        for (similarWordsData in similar) {
            if (estimator.checkSimiliar<PostNumber?>(wordsData, similarWordsData)) {
                return "similar to " + similarWordsData.extra
            }
        }
        return null
    }

    private fun checkHiddenIfAIGenerated(
        chan: Chan,
        postItem: PostItem,
    ): String? {
        if (chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING) &&
            Preferences.isHideAIPosts(
                chan,
            ) &&
            postItem.getPost().isAiGenerated
        ) {
            return "Is AI-generated!"
        }
        return null
    }

    private fun checkHiddenGlobalAutohide(
        chan: Chan,
        postItem: PostItem,
    ): String? {
        val boardName = postItem.getBoardName()
        val originalPostNumber = postItem.getOriginalPostNumber()
        // PostItem.getOriginalPostNumber() is non-null (PostItem dereferences it
        // unconditionally in isOriginalPost()), so the Java's null branch was dead.
        val originalPostNumberString: String = originalPostNumber.toString()
        val originalPost = postItem.isOriginalPost()
        val sage = postItem.isSage()
        var subject: String? = null
        var comment: String? = null
        var names: MutableList<String>? = null
        val autohideItems = autohideStorage.getItems()
        for (i in autohideItems.indices) {
            val autohideItem = autohideItems[i]
            // AND selection (only if chan, board, thread, op, and sage match the rule)
            val autohideChanNames = autohideItem.chanNames
            // chan.name is null for the fallback Chan (an extension that is not loaded).
            // HashSet.contains(null) returned false in the Java, so the rule simply did not
            // match; '!!' here would crash instead.
            if (autohideChanNames == null ||
                chan.name?.let { autohideChanNames.contains(it) } == true
            ) {
                if (StringUtils.isEmpty(autohideItem.boardName) || boardName == null || autohideItem.boardName == boardName) {
                    if (StringUtils.isEmpty(autohideItem.threadNumber) ||
                        autohideItem.boardName != null &&
                        autohideItem.threadNumber == originalPostNumberString
                    ) {
                        if ((!autohideItem.optionOriginalPost || autohideItem.optionOriginalPost == originalPost) &&
                            (!autohideItem.optionSage || autohideItem.optionSage == sage)
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
                                            .ReasonSource.SUBJECT,
                                        comment,
                                        result,
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
                                            .ReasonSource.COMMENT,
                                        comment,
                                        result,
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
                                        names = mutableListOf(name)
                                    }
                                }
                                for (name in names) {
                                    if ((autohideItem.find(name).also { result = it }) != null) {
                                        return autohideItem.getReason(
                                            AutohideItem
                                                .ReasonSource.NAME,
                                            name,
                                            result,
                                        )
                                    }
                                }
                            }
                            if (autohideItem.optionFileName && postItem.hasAttachments()) {
                                for (attachmentItem in postItem.getAttachmentItems()!!) {
                                    val originalName: String =
                                        StringUtils.emptyIfNull(attachmentItem.getOriginalName())
                                    if ((
                                            autohideItem
                                                .find(originalName)
                                                .also { result = it }
                                        ) != null
                                    ) {
                                        return autohideItem.getReason(
                                            AutohideItem
                                                .ReasonSource.FILE,
                                            originalName,
                                            result,
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
        SUCCESS,
        FAIL,
        EXISTS,
    }

    fun addHideByReplies(postItem: PostItem): AddResult {
        val replies = this.replies ?: LinkedHashSet<PostNumber>().also { this.replies = it }
        val postNumber = postItem.getPostNumber()
        if (replies.contains(postNumber)) {
            return AddResult.EXISTS
        }
        replies.add(postNumber)
        return AddResult.SUCCESS
    }

    fun addHideByName(
        chan: Chan,
        postItem: PostItem,
    ): AddResult {
        if (postItem.isUseDefaultName()) {
            ClickableToast.show(R.string.default_name_cant_be_hidden)
            return AddResult.FAIL
        }
        val names = this.names ?: LinkedHashSet<String>().also { this.names = it }
        val fullName = postItem.getFullName(chan).toString()
        if (names.contains(fullName)) {
            return AddResult.EXISTS
        }
        names.add(fullName)
        return AddResult.SUCCESS
    }

    fun addHideSimilar(
        chan: Chan,
        postItem: PostItem,
    ): AddResult {
        val comment = postItem.getComment(chan).toString()
        val wordsData = estimator.getWords<PostNumber?>(comment)
        if (wordsData == null) {
            ClickableToast.show(R.string.too_few_meaningful_words)
            return AddResult.FAIL
        }
        val similar = this.similar ?: ArrayList<WordsData<PostNumber?>>().also { this.similar = it }
        val postNumber = postItem.getPostNumber()
        wordsData.extra = postNumber
        // Remove repeats
        for (i in similar.indices.reversed()) {
            if (postNumber.equals(similar[i].extra)) {
                similar.removeAt(i)
            }
        }
        similar.add(wordsData)
        return AddResult.SUCCESS
    }

    fun hasLocalFilters(): Boolean {
        val repliesLength = replies?.size ?: 0
        val namesLength = names?.size ?: 0
        val similarLength = similar?.size ?: 0
        return repliesLength + namesLength + similarLength > 0
    }

    fun getReadableLocalFilters(context: Context): MutableList<String?> {
        val localFilters = ArrayList<String?>()
        if (replies != null) {
            for (postNumber in replies) {
                localFilters.add(
                    context.getString(
                        R.string.replies_to_number__format,
                        postNumber.toString(),
                    ),
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
                        wordsData.extra.toString(),
                    ),
                )
            }
        }
        return localFilters
    }

    fun removeLocalFilter(index: Int) {
        var remainingIndex = index
        val replies = this.replies
        if (replies != null) {
            if (remainingIndex >= replies.size) {
                remainingIndex -= replies.size
            } else {
                Companion.removeFromLinkedHashSet(replies, remainingIndex)
                if (replies.isEmpty()) {
                    this.replies = null
                }
                return
            }
        }
        val names = this.names
        if (names != null) {
            if (remainingIndex >= names.size) {
                remainingIndex -= names.size
            } else {
                Companion.removeFromLinkedHashSet(names, remainingIndex)
                if (names.isEmpty()) {
                    this.names = null
                }
                return
            }
        }
        val similar = this.similar
        if (similar != null) {
            if (remainingIndex >= similar.size) {
                remainingIndex -= similar.size
            } else {
                similar.removeAt(remainingIndex)
                if (similar.isEmpty()) {
                    this.similar = null
                }
                return
            }
        }
    }

    @Throws(IOException::class)
    fun encodeLocalFilters(writer: JsonSerial.Writer) {
        val repliesLength = replies?.size ?: 0
        val namesLength = names?.size ?: 0
        val similarLength = similar?.size ?: 0
        writer.startObject()
        if (repliesLength > 0) {
            writer.name("replies")
            writer.startArray()
            for (postNumber in replies.orEmpty()) {
                writer.value(postNumber.toString())
            }
            writer.endArray()
        }
        if (namesLength > 0) {
            writer.name("names")
            writer.startArray()
            for (name in names.orEmpty()) {
                writer.value(name)
            }
            writer.endArray()
        }
        if (similarLength > 0) {
            writer.name("similar")
            writer.startArray()
            for (wordsData in similar.orEmpty()) {
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
                            val replies =
                                this.replies
                                    ?: LinkedHashSet<PostNumber>().also { this.replies = it }
                            replies.add(parseOrThrow(reader.nextString()))
                        }
                    }

                    "names" -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            val names =
                                this.names ?: LinkedHashSet<String>().also { this.names = it }
                            names.add(reader.nextString()!!)
                        }
                    }

                    "similar" -> {
                        reader.startArray()
                        while (!reader.endStruct()) {
                            var postNumber: PostNumber? = null
                            var count = 0
                            val words = HashSet<String>()
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
                                            words.add(reader.nextString()!!)
                                        }
                                    }
                                }
                            }
                            val wordsData =
                                WordsData<PostNumber?>(words, count)
                            wordsData.extra = postNumber
                            val similar =
                                this.similar
                                    ?: ArrayList<WordsData<PostNumber?>>().also { this.similar = it }
                            similar.add(wordsData)
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
                            val replies =
                                this.replies
                                    ?: LinkedHashSet<PostNumber>().also { this.replies = it }
                            replies.add(postNumber)
                        }
                    }

                    "name" -> {
                        if (!isEmpty(rule[1])) {
                            val names =
                                this.names ?: LinkedHashSet<String>().also { this.names = it }
                            names.add(rule[1]!!)
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
                                    val words =
                                        HashSet<String>(
                                            Arrays.asList(*rule).subList(3, rule.size).filterNotNull(),
                                        )
                                    words.remove("")
                                    if (!words.isEmpty()) {
                                        val wordsData =
                                            WordsData<PostNumber?>(words, count)
                                        wordsData.extra = postNumber
                                        val similar =
                                            this.similar ?: ArrayList<WordsData<PostNumber?>>()
                                                .also { this.similar = it }
                                        similar.add(wordsData)
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

        private fun removeFromLinkedHashSet(
            set: LinkedHashSet<*>,
            index: Int,
        ) {
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
