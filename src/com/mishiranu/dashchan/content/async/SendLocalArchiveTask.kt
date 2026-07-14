package com.mishiranu.dashchan.content.async

import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanMarkup
import chan.util.DataFile
import chan.util.StringUtils
import com.mishiranu.dashchan.content.model.Post
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.text.HtmlParser
import com.mishiranu.dashchan.text.WakabaLikeHtmlBuilder
import com.mishiranu.dashchan.text.style.GainedColorSpan
import com.mishiranu.dashchan.text.style.HeadingSpan
import com.mishiranu.dashchan.text.style.ItalicSpan
import com.mishiranu.dashchan.text.style.LinkSpan
import com.mishiranu.dashchan.text.style.MediumSpan
import com.mishiranu.dashchan.text.style.MonospaceSpan
import com.mishiranu.dashchan.text.style.NeuroslopSpan
import com.mishiranu.dashchan.text.style.OverlineSpan
import com.mishiranu.dashchan.text.style.QuoteSpan
import com.mishiranu.dashchan.text.style.ScriptSpan
import com.mishiranu.dashchan.text.style.SpoilerSpan
import com.mishiranu.dashchan.util.Hasher
import com.mishiranu.dashchan.util.MimeTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.Locale

class SendLocalArchiveTask(
    private val callback: Callback,
    private val chan: Chan,
    private val boardName: String?,
    private val threadNumber: String?,
    private val posts: Collection<Post>,
    private val saveThumbnails: Boolean,
    private val saveFiles: Boolean,
) : ExecutorTask<Int, SendLocalArchiveTask.Result?>(),
    ChanMarkup.MarkupExtra {
    fun interface DownloadResult {
        fun run(binder: DownloadService.Binder)
    }

    interface Callback {
        fun onLocalArchivationProgressUpdate(handledPostsCount: Int)

        fun onLocalArchivationComplete(result: DownloadResult?)
    }

    override fun getBoardName(): String? = boardName

    override fun getThreadNumber(): String? = threadNumber

    private class SpanItem(
        val openTag: String,
        val closeTag: String,
    ) {
        var start = 0
        var end = 0
    }

    override fun run(): Result? {
        val chan = this.chan
        val boardName = this.boardName
        val threadNumber = this.threadNumber
        val posts = this.posts
        val decodeTo = arrayOfNulls<Any>(2)
        val spanItems = ArrayList<SpanItem>()
        val archiveName = chan.name + '-' + boardName + '-' + threadNumber
        val existFilesLc = ArrayList<String>()
        val existThumbnailsLc = ArrayList<String>()
        val iconNames = HashMap<String, String>()
        val hasher = Hasher.getInstanceSha256()
        var totalFilesCount = 0
        for (post in posts) {
            totalFilesCount += post.attachments.size
        }
        val defaultName = chan.configuration.getDefaultName(boardName)
        val htmlBuilder =
            WakabaLikeHtmlBuilder(
                posts.iterator().next().subject,
                boardName,
                chan.configuration.getBoardTitle(boardName),
                chan.configuration.getTitle(),
                chan.locator.safe(false).createThreadUri(boardName, threadNumber)!!,
                posts.size,
                totalFilesCount,
            )
        val filesToDownload = ArrayList<DownloadService.DownloadItem>()
        val thumbnailsToDownload = ArrayList<DownloadService.DownloadItem>()
        for (post in posts) {
            val number = post.number
            var name = StringUtils.emptyIfNull(post.name).trim()
            val identifier = post.identifier
            val tripcode = post.tripcode
            val capcode = post.capcode
            val email = post.email
            val subject = post.subject
            val comment = post.comment
            val timestamp = post.timestamp
            val sage = post.isSage
            val originalPoster = post.isOriginalPoster
            val deleted = post.deleted
            val useDefaultName = name == defaultName || name.isEmpty()
            if (name.isEmpty()) {
                name = defaultName!!
            }
            val charSequence = HtmlParser.spanify(comment, chan.markup.markup, null, null, this)
            spanItems.clear()
            val spannable = SpannableStringBuilder(charSequence)
            replaceSpannable(spannable, '<', "&lt;")
            replaceSpannable(spannable, '>', "&gt;")
            val spans = spannable.getSpans(0, spannable.length, Any::class.java)
            for (span in spans) {
                getSpanType(span, decodeTo)
                val what = decodeTo[0] as Int
                if (what != 0) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    var extra = decodeTo[1]
                    if (what == ChanMarkup.TAG_SPECIAL_LINK) {
                        val text = spannable.subSequence(start, end).toString()
                        if (text.startsWith("&gt;&gt;")) {
                            val uri =
                                chan.locator.validateClickedUriString(
                                    decodeTo[1] as String?,
                                    boardName,
                                    threadNumber,
                                )
                            if (threadNumber == chan.locator.safe(false).getThreadNumber(uri)) {
                                val postNumber = chan.locator.safe(false).getPostNumber(uri)
                                val postNumberString = postNumber?.toString() ?: threadNumber
                                extra = "#$postNumberString"
                            } else {
                                extra = uri.toString()
                            }
                        }
                    }
                    val spanItem = makeSpanItem(what, extra)
                    if (spanItem != null) {
                        spanItem.start = start
                        spanItem.end = end
                        spanItems.add(spanItem)
                    }
                }
            }
            val builder = StringBuilder(spannable.toString())
            for (i in spanItems.indices) {
                val spanItem = spanItems[i]
                val openLength = spanItem.openTag.length
                val closeLength = spanItem.closeTag.length
                builder.insert(spanItem.start, spanItem.openTag).insert(spanItem.end + openLength, spanItem.closeTag)
                for (j in i + 1 until spanItems.size) {
                    val editingItem = spanItems[j]
                    if (editingItem.start >= spanItem.start) {
                        if (editingItem.start >= spanItem.end) {
                            editingItem.start += openLength + closeLength
                        } else {
                            editingItem.start += openLength
                        }
                    }
                    if (editingItem.end > spanItem.start) {
                        if (editingItem.end > spanItem.end) {
                            editingItem.end += openLength + closeLength
                        } else {
                            editingItem.end += openLength
                        }
                    }
                }
            }
            val htmlComment = builder.toString().replace("\r", "").replace("\n", "<br />")
            htmlBuilder.addPost(
                number.toString(),
                subject,
                name,
                identifier,
                tripcode,
                capcode,
                email,
                sage,
                originalPoster,
                timestamp,
                deleted,
                useDefaultName,
                htmlComment,
            )
            for (icon in post.icons) {
                if (icon.uri != null && !StringUtils.isEmpty(icon.title)) {
                    val iconUri = chan.locator.convert(icon.uri)
                    val simpleUri =
                        iconUri!!
                            .buildUpon()
                            .scheme(null)
                            .authority(null)
                            .build()
                            .toString()
                    val pathHash =
                        Base64.encodeToString(
                            hasher.calculate(simpleUri),
                            0,
                            12,
                            Base64.NO_WRAP or Base64.URL_SAFE,
                        )
                    val iconNameWithoutExtension = "icon-$pathHash"
                    var iconName = iconNames[iconNameWithoutExtension]
                    var downloadIcon = false
                    if (iconName == null) {
                        var extension: String? = null
                        if (ChanConfiguration.SCHEME_CHAN == iconUri.scheme) {
                            val output = ByteArrayOutputStream()
                            try {
                                if (chan.configuration.readResourceUri(iconUri, output)) {
                                    val bytes = output.toByteArray()
                                    val contentType =
                                        URLConnection
                                            .guessContentTypeFromStream(ByteArrayInputStream(bytes))
                                    if (contentType != null) {
                                        extension = MimeTypes.toExtension(contentType)
                                    }
                                }
                            } catch (e: IOException) {
                                // Ignore
                            }
                        } else {
                            extension = StringUtils.getFileExtension(iconUri.path)
                        }
                        iconName = iconNameWithoutExtension
                        if (!StringUtils.isEmpty(extension)) {
                            iconName += ".$extension"
                        }
                        iconNames[iconNameWithoutExtension] = iconName
                        downloadIcon = true
                    }
                    val iconPath = archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + iconName
                    htmlBuilder.addIcon(iconPath, icon.title)
                    if (downloadIcon && saveThumbnails) {
                        thumbnailsToDownload.add(
                            DownloadService.DownloadItem(
                                chan.name,
                                iconUri,
                                iconName,
                                null,
                                null,
                            ),
                        )
                    }
                }
            }
            for (attachment in post.attachments) {
                if (attachment is Post.Attachment.File) {
                    var fileUri = chan.locator.convert(chan.locator.fixRelativeFileUri(attachment.fileUri))
                    val thumbnailUri = chan.locator.convert(chan.locator.fixRelativeFileUri(attachment.thumbnailUri))
                    if (fileUri == null) {
                        fileUri = thumbnailUri
                    }
                    if (fileUri != null) {
                        var fileName = chan.locator.createAttachmentFileName(fileUri)
                        fileName = chooseFileName(existFilesLc, fileName)!!
                        val filePath = archiveName + "/" + DIRECTORY_FILES + "/" + fileName
                        var thumbnailName: String? = null
                        var thumbnailPath: String? = null
                        if (thumbnailUri != null) {
                            thumbnailName = chan.locator.createAttachmentFileName(thumbnailUri)
                            thumbnailName = chooseFileName(existThumbnailsLc, thumbnailName)
                            thumbnailPath = archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + thumbnailName
                        }
                        val originalName = StringUtils.getNormalizedOriginalName(attachment.originalName, fileName)
                        htmlBuilder.addFile(
                            filePath,
                            thumbnailPath,
                            originalName,
                            attachment.size,
                            attachment.width,
                            attachment.height,
                        )
                        if (saveFiles) {
                            filesToDownload.add(
                                DownloadService.DownloadItem(
                                    chan.name,
                                    fileUri,
                                    fileName,
                                    null,
                                    null,
                                ),
                            )
                        }
                        if (saveThumbnails && thumbnailUri != null) {
                            thumbnailsToDownload.add(
                                DownloadService.DownloadItem(
                                    chan.name,
                                    thumbnailUri,
                                    thumbnailName,
                                    null,
                                    null,
                                ),
                            )
                        }
                    }
                }
            }
            if (isCancelled()) {
                return null
            }
            notifyIncrement()
        }
        val html = htmlBuilder.build()
        return Result(html, archiveName, filesToDownload, thumbnailsToDownload)
    }

    override fun onProgress(progress: Int) {
        callback.onLocalArchivationProgressUpdate(progress)
    }

    override fun onComplete(result: Result?) {
        var downloadResult: DownloadResult? = null
        if (result != null) {
            val htmlBytes = result.html.toByteArray(Charsets.UTF_8)
            val results = ArrayList<DownloadResult>()
            results.add(createDownload(".nomedia", ByteArrayInputStream(ByteArray(0))))
            results.add(createDownload(result.archiveName + ".html", ByteArrayInputStream(htmlBytes)))
            results.add(createDownload(result.archiveName + "/" + DIRECTORY_THUMBNAILS, result.thumbnailsToDownload))
            results.add(createDownload(result.archiveName + "/" + DIRECTORY_FILES, result.filesToDownload))
            downloadResult =
                DownloadResult { binder ->
                    binder.accumulate().use {
                        for (innerDownloadResult in results) {
                            innerDownloadResult.run(binder)
                        }
                    }
                }
        }
        callback.onLocalArchivationComplete(downloadResult)
    }

    private var lastNotifyIncrement = 0L
    private var progress = 0

    fun notifyIncrement() {
        progress++
        val t = SystemClock.elapsedRealtime()
        if (t - lastNotifyIncrement >= 100) {
            lastNotifyIncrement = t
            notifyProgress(progress)
        }
    }

    class Result(
        @JvmField val html: String,
        @JvmField val archiveName: String,
        @JvmField val filesToDownload: List<DownloadService.DownloadItem>,
        @JvmField val thumbnailsToDownload: List<DownloadService.DownloadItem>,
    )

    private fun replaceSpannable(
        spannable: SpannableStringBuilder,
        what: Char,
        with: String,
    ) {
        var i = 0
        while (i < spannable.length) {
            if (spannable[i] == what) {
                spannable.replace(i, i + 1, with)
            }
            i++
        }
    }

    private fun makeSpanItem(
        what: Int,
        extra: Any?,
    ): SpanItem? {
        val openTag: String
        val closeTag: String
        when (what) {
            ChanMarkup.TAG_BOLD -> {
                openTag = "<b>"
                closeTag = "</b>"
            }

            ChanMarkup.TAG_ITALIC -> {
                openTag = "<i>"
                closeTag = "</i>"
            }

            ChanMarkup.TAG_UNDERLINE -> {
                openTag = "<span class=\"underline\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_OVERLINE -> {
                openTag = "<span class=\"overline\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_STRIKE -> {
                openTag = "<span class=\"strike\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_SUBSCRIPT -> {
                openTag = "<sub>"
                closeTag = "</sub>"
            }

            ChanMarkup.TAG_SUPERSCRIPT -> {
                openTag = "<sup>"
                closeTag = "</sup>"
            }

            ChanMarkup.TAG_SPOILER -> {
                openTag = "<span class=\"spoiler\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_QUOTE -> {
                openTag = "<span class=\"unkfunc\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_CODE -> {
                openTag = "<span class=\"code\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_ASCII_ART -> {
                openTag = "<span class=\"aa\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_HEADING -> {
                openTag = "<span class=\"heading\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_SPECIAL_LINK -> {
                openTag = "<a href=\"$extra\">"
                closeTag = "</a>"
            }

            ChanMarkup.TAG_SPECIAL_COLOR -> {
                openTag = "<span style=\"color: #" +
                    String.format("%06x", 0x00ffffff and extra as Int) + "\">"
                closeTag = "</span>"
            }

            ChanMarkup.TAG_AI -> {
                openTag = "<div class=\"neuroslop\">"
                closeTag = "</div>"
            }

            else -> {
                return null
            }
        }
        return SpanItem(openTag, closeTag)
    }

    private fun getSpanType(
        span: Any,
        result: Array<Any?>,
    ): Array<Any?> {
        result[0] = 0
        result[1] = null
        if (span is LinkSpan) {
            result[0] = ChanMarkup.TAG_SPECIAL_LINK
            result[1] = span.uriString
        } else if (span is SpoilerSpan) {
            result[0] = ChanMarkup.TAG_SPOILER
        } else if (span is QuoteSpan) {
            result[0] = ChanMarkup.TAG_QUOTE
        } else if (span is ScriptSpan) {
            result[0] = if (span.isSuperscript) ChanMarkup.TAG_SUPERSCRIPT else ChanMarkup.TAG_SUBSCRIPT
        } else if (span is MediumSpan) {
            result[0] = ChanMarkup.TAG_BOLD
        } else if (span is ItalicSpan) {
            result[0] = ChanMarkup.TAG_ITALIC
        } else if (span is UnderlineSpan) {
            result[0] = ChanMarkup.TAG_UNDERLINE
        } else if (span is OverlineSpan) {
            result[0] = ChanMarkup.TAG_OVERLINE
        } else if (span is StrikethroughSpan) {
            result[0] = ChanMarkup.TAG_STRIKE
        } else if (span is GainedColorSpan) {
            result[0] = ChanMarkup.TAG_SPECIAL_COLOR
            result[1] = span.foregroundColor
        } else if (span is MonospaceSpan) {
            result[0] = if (span.isAsciiArt) ChanMarkup.TAG_ASCII_ART else ChanMarkup.TAG_CODE
        } else if (span is HeadingSpan) {
            result[0] = ChanMarkup.TAG_HEADING
        } else if (span is NeuroslopSpan) {
            result[0] = ChanMarkup.TAG_AI
        }
        return result
    }

    private fun chooseFileName(
        fileNamesLc: ArrayList<String>,
        fileName: String?,
    ): String? {
        var resultFileName = fileName
        if (resultFileName != null) {
            val locale = Locale.getDefault()
            val fileNameLc = resultFileName.lowercase(locale)
            if (fileNamesLc.contains(fileNameLc)) {
                val extension = StringUtils.getFileExtension(resultFileName)
                if (extension != null) {
                    resultFileName = resultFileName.substring(0, resultFileName.length - extension.length - 1)
                }
                var newFileName: String
                var newFileNameLc: String
                var i = 0
                do {
                    newFileName = resultFileName + "-" + ++i + if (extension != null) ".$extension" else ""
                    newFileNameLc = newFileName.lowercase(locale)
                } while (fileNamesLc.contains(newFileNameLc))
                fileNamesLc.add(newFileNameLc)
                resultFileName = newFileName
            } else {
                fileNamesLc.add(fileNameLc)
            }
        }
        return resultFileName
    }

    companion object {
        private const val DIRECTORY_ARCHIVE = "Archive"
        private const val DIRECTORY_FILES = "src"
        private const val DIRECTORY_THUMBNAILS = "thumb"

        private fun createDownload(
            name: String,
            input: InputStream,
        ): DownloadResult =
            DownloadResult { binder ->
                binder.downloadDirect(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE, name, input)
            }

        private fun createDownload(
            path: String?,
            downloadItems: List<DownloadService.DownloadItem>,
        ): DownloadResult =
            DownloadResult { binder ->
                if (path != null && downloadItems.isNotEmpty()) {
                    binder.downloadDirect(
                        DataFile.Target.DOWNLOADS,
                        DIRECTORY_ARCHIVE + "/" + path,
                        false,
                        downloadItems,
                    )
                }
            }
    }
}
