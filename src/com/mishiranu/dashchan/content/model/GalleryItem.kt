package com.mishiranu.dashchan.content.model

import android.net.Uri
import chan.content.Chan
import com.mishiranu.dashchan.content.service.DownloadService
import com.mishiranu.dashchan.util.NavigationUtils
import java.util.TreeMap

class GalleryItem {
    private val fileUriString: String?
    private val thumbnailUriString: String?

    @JvmField val boardName: String?

    @JvmField val threadNumber: String?

    @JvmField val postNumber: PostNumber?

    @JvmField val originalName: String?

    @JvmField val width: Int

    @JvmField val height: Int

    @JvmField val size: Int

    @Transient private var fileUri: Uri? = null

    @Transient private var thumbnailUri: Uri? = null

    constructor(
        fileUri: Uri?,
        thumbnailUri: Uri?,
        boardName: String?,
        threadNumber: String?,
        postNumber: PostNumber?,
        originalName: String?,
        width: Int,
        height: Int,
        size: Int,
    ) {
        fileUriString = fileUri?.toString()
        thumbnailUriString = thumbnailUri?.toString()
        this.boardName = boardName
        this.threadNumber = threadNumber
        this.postNumber = postNumber
        this.originalName = originalName
        this.width = width
        this.height = height
        this.size = size
    }

    constructor(fileUri: Uri?, boardName: String?, threadNumber: String?) {
        fileUriString = null
        thumbnailUriString = null
        this.boardName = boardName
        this.threadNumber = threadNumber
        postNumber = null
        originalName = null
        width = 0
        height = 0
        size = 0
        this.fileUri = fileUri
    }

    fun isImage(chan: Chan): Boolean = chan.locator.isImageExtension(getFileName(chan))

    fun isVideo(chan: Chan): Boolean = chan.locator.isVideoExtension(getFileName(chan))

    fun isOpenableVideo(chan: Chan): Boolean = NavigationUtils.isOpenableVideoPath(getFileName(chan))

    /**
     * True when the file has a web address of its own to be fetched from again. A local one — a draft
     * attachment shown from the posting form, a data uri embedded in a post — cannot be refreshed, and
     * its uri is of no use to anything that expects a link.
     */
    fun isRemote(chan: Chan): Boolean {
        val fileUri = getFileUri(chan) ?: return false
        return chan.locator.isWebScheme(fileUri)
    }

    fun getFileUri(chan: Chan): Uri? {
        if (fileUri == null && fileUriString != null) {
            fileUri = chan.locator.convert(Uri.parse(fileUriString))
        }
        return fileUri
    }

    fun getThumbnailUri(chan: Chan): Uri? {
        if (thumbnailUri == null && thumbnailUriString != null) {
            thumbnailUri = chan.locator.convert(Uri.parse(thumbnailUriString))
        }
        return thumbnailUri
    }

    fun getDisplayImageUri(chan: Chan): Uri? = if (isImage(chan)) getFileUri(chan) else getThumbnailUri(chan)

    fun getFileName(chan: Chan): String? {
        val fileUri = getFileUri(chan)
        return chan.locator.createAttachmentFileName(fileUri!!)
    }

    fun downloadStorage(
        binder: DownloadService.Binder,
        chan: Chan,
        threadTitle: String?,
    ) {
        binder.downloadStorage(
            getFileUri(chan),
            getFileName(chan)!!,
            originalName,
            chan.name,
            boardName,
            threadNumber,
            threadTitle,
        )
    }

    fun interface Provider {
        fun getGallerySet(postItem: PostItem): Set
    }

    class Set(
        private val navigatePostSupported: Boolean,
    ) : Provider {
        private val galleryItems = TreeMap<PostNumber, List<GalleryItem>>()

        private var threadTitle: String? = null

        fun setThreadTitle(threadTitle: String?) {
            this.threadTitle = threadTitle
        }

        fun getThreadTitle(): String? = threadTitle

        fun put(
            postNumber: PostNumber,
            attachmentItems: Collection<AttachmentItem>?,
        ) {
            if (attachmentItems != null) {
                val galleryItems = ArrayList<GalleryItem>()
                for (attachmentItem in attachmentItems) {
                    if (attachmentItem.isShowInGallery() && attachmentItem.canDownloadToStorage()) {
                        galleryItems.add(attachmentItem.createGalleryItem()!!)
                    }
                }
                if (galleryItems.isNotEmpty()) {
                    this.galleryItems[postNumber] = galleryItems
                }
            }
        }

        fun remove(postNumber: PostNumber) {
            galleryItems.remove(postNumber)
        }

        fun clear() {
            galleryItems.clear()
        }

        fun findIndex(postItem: PostItem): Int {
            if (postItem.hasAttachments()) {
                var index = 0
                val postNumber = postItem.getPostNumber()
                for ((key, value) in galleryItems) {
                    if (postNumber == key) {
                        return index
                    }
                    index += value.size
                }
            }
            return -1
        }

        fun createList(): List<GalleryItem> {
            val galleryItems = ArrayList<GalleryItem>()
            for (list in this.galleryItems.values) {
                galleryItems.addAll(list)
            }
            return galleryItems
        }

        fun isNavigatePostSupported(): Boolean = navigatePostSupported

        override fun getGallerySet(postItem: PostItem): Set = this
    }
}
