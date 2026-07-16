package com.mishiranu.dashchan.content.model

import android.net.Uri
import chan.content.Chan
import chan.content.ChanLocator
import chan.util.StringUtils
import com.mishiranu.dashchan.C
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.ImageLoader
import com.mishiranu.dashchan.content.NetworkObserver
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.net.EmbeddedType
import com.mishiranu.dashchan.widget.AttachmentView
import java.util.Locale

abstract class AttachmentItem protected constructor(
    private val master: Master,
) {
    enum class Type { IMAGE, VIDEO, AUDIO, FILE }

    enum class GeneralType { FILE, EMBEDDED, LINK }

    interface Master {
        fun getBoardName(): String?

        fun getThreadNumber(): String?

        fun getPostNumber(): PostNumber
    }

    abstract fun getFileUri(chan: Chan): Uri?

    abstract fun getThumbnailUri(chan: Chan): Uri?

    abstract fun getThumbnailKey(chan: Chan): String?

    abstract fun getDialogTitle(chan: Chan): String?

    abstract fun getSize(): Int

    abstract fun getType(): Type

    abstract fun getGeneralType(): GeneralType

    abstract fun isShowInGallery(): Boolean

    abstract fun canDownloadToStorage(): Boolean

    abstract fun createGalleryItem(): GalleryItem?

    abstract fun getExtension(): String?

    abstract fun getFileName(chan: Chan): String?

    abstract fun getOriginalName(): String?

    abstract fun getDescription(formatMode: FormatMode): String

    fun getBoardName(): String? = master.getBoardName()

    fun getThreadNumber(): String? = master.getThreadNumber()

    fun getPostNumber(): PostNumber = master.getPostNumber()

    private class FileAttachmentItem(
        master: Master,
        locator: ChanLocator,
        fileUri: Uri?,
        thumbnailUri: Uri?,
        originalName: String?,
        private val fileSize: Int,
        val width: Int,
        val height: Int,
    ) : AttachmentItem(master) {
        val fileUri: Uri?
        val thumbnailUri: Uri?

        private val normalizedOriginalName: String?
        val displayedExtension: String?
        private val fileType: Type

        private var thumbnailKey: String? = null

        init {
            val realFileUri = fileUri ?: thumbnailUri
            val fileName = locator.createAttachmentFileName(realFileUri!!)
            val extension = StringUtils.getFileExtension(fileName)
            this.fileUri = realFileUri
            this.thumbnailUri =
                if (C.IMAGE_EXTENSIONS.contains(extension) ||
                    C.VIDEO_EXTENSIONS.contains(extension)
                ) {
                    thumbnailUri
                } else {
                    null
                }
            normalizedOriginalName = StringUtils.getNormalizedOriginalName(originalName, fileName)
            displayedExtension = StringUtils.getNormalizedExtension(extension)
            fileType =
                when {
                    C.IMAGE_EXTENSIONS.contains(displayedExtension) -> Type.IMAGE
                    C.VIDEO_EXTENSIONS.contains(displayedExtension) -> Type.VIDEO
                    C.AUDIO_EXTENSIONS.contains(displayedExtension) -> Type.AUDIO
                    else -> Type.FILE
                }
        }

        override fun getFileUri(chan: Chan): Uri? = chan.locator.convert(fileUri)

        override fun getThumbnailUri(chan: Chan): Uri? = chan.locator.convert(thumbnailUri)

        override fun getThumbnailKey(chan: Chan): String? {
            if (thumbnailKey == null && thumbnailUri != null) {
                thumbnailKey = CacheManager.getInstance().getCachedFileKey(getThumbnailUri(chan))
            }
            return thumbnailKey
        }

        override fun getDialogTitle(chan: Chan): String? = if (!normalizedOriginalName.isNullOrEmpty()) normalizedOriginalName else getFileName(chan)

        override fun getSize(): Int = fileSize

        override fun getType(): Type = fileType

        override fun getGeneralType(): GeneralType = GeneralType.FILE

        override fun isShowInGallery(): Boolean = fileType == Type.IMAGE || fileType == Type.VIDEO

        override fun canDownloadToStorage(): Boolean = true

        override fun createGalleryItem(): GalleryItem =
            GalleryItem(
                fileUri,
                thumbnailUri,
                getBoardName(),
                getThreadNumber(),
                getPostNumber(),
                normalizedOriginalName,
                width,
                height,
                fileSize,
            )

        override fun getExtension(): String? = displayedExtension

        override fun getFileName(chan: Chan): String? = chan.locator.createAttachmentFileName(getFileUri(chan)!!)

        override fun getOriginalName(): String? = normalizedOriginalName

        override fun getDescription(formatMode: FormatMode): String {
            val builder = StringBuilder()
            when (formatMode) {
                FormatMode.LONG, FormatMode.SIMPLE -> {
                    if (formatMode == FormatMode.LONG && displayedExtension != null) {
                        builder.append(displayedExtension.uppercase(Locale.US))
                    }
                    if (width > 0 && height > 0) {
                        if (builder.isNotEmpty()) {
                            builder.append(' ')
                        }
                        builder.append(width).append('×').append(height)
                    }
                    if (fileSize > 0) {
                        if (builder.isNotEmpty()) {
                            builder.append(' ')
                        }
                        builder.append(StringUtils.formatFileSize(fileSize.toLong(), true))
                    }
                }

                FormatMode.TWO_LINES, FormatMode.THREE_LINES -> {
                    if (displayedExtension != null) {
                        builder.append(displayedExtension.uppercase(Locale.US))
                    }
                    if (fileSize > 0) {
                        builder.append(if (formatMode == FormatMode.THREE_LINES) '\n' else ' ')
                        builder.append(StringUtils.formatFileSize(fileSize.toLong(), true))
                    }
                    if (width > 0 && height > 0) {
                        builder
                            .append('\n')
                            .append(width)
                            .append('×')
                            .append(height)
                    }
                }
            }
            return builder.toString()
        }
    }

    private class EmbeddedAttachmentItem(
        master: Master,
        val fileUri: Uri,
        val thumbnailUri: Uri?,
        val embeddedType: String?,
        contentType: Post.Attachment.Embedded.ContentType?,
        val canDownload: Boolean,
        val fileName: String?,
        val fromComment: Boolean,
    ) : AttachmentItem(master) {
        val isAudio: Boolean = contentType == Post.Attachment.Embedded.ContentType.AUDIO
        val isVideo: Boolean = contentType == Post.Attachment.Embedded.ContentType.VIDEO

        private var thumbnailKey: String? = null

        override fun getFileUri(chan: Chan): Uri = fileUri

        override fun getThumbnailUri(chan: Chan): Uri? = thumbnailUri

        override fun getThumbnailKey(chan: Chan): String? {
            if (thumbnailKey == null && thumbnailUri != null) {
                thumbnailKey = CacheManager.getInstance().getCachedFileKey(getThumbnailUri(chan))
            }
            return thumbnailKey
        }

        override fun getDialogTitle(chan: Chan): String? = embeddedType

        override fun getSize(): Int = 0

        override fun getType(): Type =
            if (isAudio) {
                Type.AUDIO
            } else if (isVideo) {
                Type.VIDEO
            } else {
                Type.FILE
            }

        override fun getGeneralType(): GeneralType = if (fromComment) GeneralType.LINK else GeneralType.FILE

        override fun isShowInGallery(): Boolean = false

        override fun canDownloadToStorage(): Boolean = canDownload

        override fun createGalleryItem(): GalleryItem? = null

        override fun getExtension(): String? = null

        override fun getFileName(chan: Chan): String? = fileName

        override fun getOriginalName(): String? = null

        override fun getDescription(formatMode: FormatMode): String {
            val builder = StringBuilder()
            if (formatMode == FormatMode.LONG ||
                formatMode == FormatMode.TWO_LINES ||
                formatMode == FormatMode.THREE_LINES
            ) {
                builder.append(if (fromComment) "URL" else "Embedded")
                builder.append(
                    if (formatMode == FormatMode.TWO_LINES ||
                        formatMode == FormatMode.THREE_LINES
                    ) {
                        '\n'
                    } else {
                        ' '
                    },
                )
            }
            builder.append(embeddedType)
            return builder.toString()
        }
    }

    enum class FormatMode { LONG, SIMPLE, TWO_LINES, THREE_LINES }

    fun configureAndLoad(
        view: AttachmentView,
        chan: Chan,
        needShowMultipleIcon: Boolean,
        force: Boolean,
    ) {
        view.setCropEnabled(Preferences.isCutThumbnails)
        val type = getType()
        val key = getThumbnailKey(chan)
        val overlay =
            if (needShowMultipleIcon) {
                AttachmentView.Overlay.MULTIPLE
            } else {
                when (type) {
                    Type.IMAGE -> {
                        if (key.isNullOrEmpty()) {
                            AttachmentView.Overlay.WARNING
                        } else {
                            AttachmentView.Overlay.NONE
                        }
                    }

                    Type.VIDEO -> {
                        AttachmentView.Overlay.VIDEO
                    }

                    Type.AUDIO -> {
                        AttachmentView.Overlay.AUDIO
                    }

                    Type.FILE -> {
                        AttachmentView.Overlay.FILE
                    }
                }
            }
        view.resetImage(key, overlay)
        startLoad(view, chan, key, force)
    }

    fun startLoad(
        view: AttachmentView,
        chan: Chan,
        force: Boolean,
    ) {
        startLoad(view, chan, getThumbnailKey(chan), force)
    }

    private fun startLoad(
        view: AttachmentView,
        chan: Chan,
        key: String?,
        force: Boolean,
    ) {
        if (key != null) {
            val uri = getThumbnailUri(chan)
            val loadThumbnails =
                Preferences.loadThumbnails
                    .isNetworkAvailable(NetworkObserver.getInstance())
            val allowDownload = loadThumbnails || force
            ImageLoader.getInstance().loadImage(chan, uri!!, key, !allowDownload, view)
        } else {
            ImageLoader.getInstance().cancel(view)
        }
    }

    fun canLoadThumbnailManually(
        attachmentView: AttachmentView,
        chan: Chan,
    ): Boolean =
        getThumbnailKey(chan) != null &&
            !attachmentView.hasImage() &&
            !ImageLoader.getInstance().hasRunningTask(attachmentView)

    companion object {
        @JvmStatic
        fun obtain(
            master: Master,
            post: Post,
            locator: ChanLocator,
        ): ArrayList<AttachmentItem>? {
            val attachmentItems = ArrayList<AttachmentItem>()
            for (attachment in post.attachments) {
                val attachmentItem: AttachmentItem? =
                    when (attachment) {
                        is Post.Attachment.File -> {
                            obtainFileAttachmentItem(master, locator, attachment)
                        }

                        is Post.Attachment.Embedded -> {
                            obtainEmbeddedAttachmentItem(master, locator, attachment, false)
                        }

                        else -> {
                            null
                        }
                    }
                if (attachmentItem != null) {
                    attachmentItems.add(attachmentItem)
                }
            }
            for (embeddedType in EmbeddedType.values()) {
                addCommentAttachmentItems(attachmentItems, master, locator, post.comment, embeddedType)
            }
            if (attachmentItems.isNotEmpty()) {
                attachmentItems.trimToSize()
                return attachmentItems
            }
            return null
        }

        private fun obtainFileAttachmentItem(
            master: Master,
            locator: ChanLocator,
            file: Post.Attachment.File?,
        ): AttachmentItem? {
            if (file == null) {
                return null
            }
            val fileUri = locator.convert(locator.fixRelativeFileUri(file.fileUri))
            val thumbnailUri = locator.convert(locator.fixRelativeFileUri(file.thumbnailUri))
            if (fileUri == null && thumbnailUri == null) {
                return null
            }
            return FileAttachmentItem(
                master,
                locator,
                fileUri,
                thumbnailUri,
                file.originalName,
                file.size,
                file.width,
                file.height,
            )
        }

        private fun obtainEmbeddedAttachmentItem(
            master: Master,
            locator: ChanLocator,
            embedded: Post.Attachment.Embedded?,
            fromComment: Boolean,
        ): AttachmentItem? {
            if (embedded == null) {
                return null
            }
            val fileUri = embedded.fileUri ?: return null
            val canDownload = embedded.canDownload
            var fileName = ""
            if (canDownload) {
                val forcedName = StringUtils.escapeFile(embedded.forcedName, false)
                fileName = locator.createAttachmentFileName(fileUri, forcedName)
            }
            return EmbeddedAttachmentItem(
                master,
                fileUri,
                embedded.thumbnailUri,
                embedded.embeddedType,
                embedded.contentType,
                canDownload,
                fileName,
                fromComment,
            )
        }

        private fun addCommentAttachmentItems(
            attachmentItems: MutableList<AttachmentItem>,
            master: Master,
            locator: ChanLocator,
            comment: String?,
            embeddedType: EmbeddedType,
        ) {
            val embeddedCodes = embeddedType.getAll(locator, comment)
            if (embeddedCodes != null && embeddedCodes.isNotEmpty()) {
                for (embeddedCode in embeddedCodes) {
                    val attachmentItem =
                        obtainEmbeddedAttachmentItem(
                            master,
                            locator,
                            embeddedType.obtainAttachment(locator, embeddedCode!!),
                            true,
                        )
                    if (attachmentItem != null) {
                        attachmentItems.add(attachmentItem)
                    }
                }
            }
        }
    }
}
