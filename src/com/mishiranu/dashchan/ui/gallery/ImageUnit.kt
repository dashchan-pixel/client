package com.mishiranu.dashchan.ui.gallery

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.fragment.app.FragmentManager
import chan.content.Chan.Companion.get
import chan.content.Chan.Companion.getPreferred
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.CacheManager
import com.mishiranu.dashchan.content.NetworkObserver.Companion.getInstance
import com.mishiranu.dashchan.content.Preferences.loadNearestImage
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.ReadFileTask
import com.mishiranu.dashchan.content.async.ReadFileTask.FileCallback
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.content.model.FileHolder.Companion.obtain
import com.mishiranu.dashchan.content.model.GalleryItem
import com.mishiranu.dashchan.graphics.DecoderDrawable
import com.mishiranu.dashchan.graphics.SimpleBitmapDrawable
import com.mishiranu.dashchan.media.AnimatedImageDecoder
import com.mishiranu.dashchan.media.JpegData
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.newSingleThreadPool
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.widget.CircularProgressBar
import com.mishiranu.dashchan.widget.PhotoView
import com.mishiranu.dashchan.widget.SummaryLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class ImageUnit(
    private val instance: PagerInstance,
) {
    private var readFileTask: ReadFileTask? = null
    private var readBitmapCallback: ReadBitmapCallback? = null

    fun interrupt(force: Boolean) {
        val readFileTask = this.readFileTask
        if (force && readFileTask != null) {
            readFileTask.cancel()
            this.readFileTask = null
            readBitmapCallback = null
        }
        interruptHolder(instance.leftHolder)
        interruptHolder(instance.currentHolder)
        interruptHolder(instance.rightHolder)
    }

    private fun interruptHolder(holder: PagerInstance.ViewHolder?) {
        if (holder != null) {
            if (holder.decodeBitmapTask != null) {
                (holder.decodeBitmapTask as DecodeBitmapTask).cancel()
                holder.progressBar.setVisible(false, true)
                holder.decodeBitmapTask = null
            }
        }
    }

    fun applyImage(
        uri: Uri,
        file: File,
        reload: Boolean,
    ) {
        if (!reload && file.exists()) {
            applyImageFromFile(file)
        } else {
            loadImage(uri, file, instance.currentHolder!!)
        }
    }

    private fun applyImageFromFile(file: File) {
        val holder = instance.currentHolder
        if (attachReadBitmapCallback(holder)) {
            return
        }
        if (holder!!.mediaSummary.updateSize(file.length())) {
            instance.galleryInstance.callback.updateTitle()
        }
        val fileHolder = obtain(file)
        if (holder.decodeBitmapTask != null) {
            (holder.decodeBitmapTask as DecodeBitmapTask).cancel()
            holder.progressBar.setVisible(false, true)
        }
        val decodeBitmapTask = DecodeBitmapTask(file, fileHolder)
        decodeBitmapTask.execute(EXECUTOR)
        holder.decodeBitmapTask = decodeBitmapTask
        val nextHolder = if (instance.scrollingLeft) instance.leftHolder else instance.rightHolder
        if (nextHolder != null &&
            loadNearestImage
                .isNetworkAvailable(getInstance())
        ) {
            val nextGalleryItem = nextHolder.galleryItem!!
            val chan = get(instance.galleryInstance.chanName)
            if (nextGalleryItem.isImage(chan)) {
                val nextUri = nextGalleryItem.getFileUri(chan)
                val nextCachedFile: File? = CacheManager.getInstance().getMediaFile(nextUri, true)
                if (nextCachedFile != null && !nextCachedFile.exists()) {
                    loadImage(nextUri!!, nextCachedFile, nextHolder)
                }
            }
        }
    }

    private fun loadImage(
        uri: Uri,
        cachedFile: File,
        holder: PagerInstance.ViewHolder,
    ) {
        if (attachReadBitmapCallback(holder)) {
            return
        }
        readFileTask?.cancel()
        val readBitmapCallback = ReadBitmapCallback(holder.galleryItem)
        this.readBitmapCallback = readBitmapCallback
        val chan = getPreferred(instance.galleryInstance.chanName, uri)
        val readFileTask =
            ReadFileTask.createCachedMediaFile(readBitmapCallback, chan, uri, cachedFile)
        this.readFileTask = readFileTask
        readFileTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    private fun attachReadBitmapCallback(holder: PagerInstance.ViewHolder?): Boolean {
        val readBitmapCallback = this.readBitmapCallback
        if (readBitmapCallback != null && readBitmapCallback.isHolder(holder)) {
            readBitmapCallback.attachDownloading()
            return true
        }
        return false
    }

    private inner class ReadBitmapCallback(
        private val galleryItem: GalleryItem?,
    ) : FileCallback {
        val isCurrentHolder: Boolean
            get() = isHolder(instance.currentHolder)

        fun isHolder(holder: PagerInstance.ViewHolder?): Boolean = holder != null && holder.galleryItem == galleryItem

        // Only valid while isCurrentHolder is true
        private val currentProgressBar: CircularProgressBar
            get() = instance.currentHolder!!.progressBar

        override fun onStartDownloading() {
            if (this.isCurrentHolder) {
                val progressBar = currentProgressBar
                progressBar.setVisible(true, false)
                progressBar.setIndeterminate(true)
            }
        }

        private var pendingProgress = 0
        private var pendingProgressMax = 0

        fun attachDownloading() {
            if (this.isCurrentHolder) {
                val progressBar = currentProgressBar
                progressBar.setVisible(true, false)
                progressBar.setIndeterminate(pendingProgressMax <= 0)
                if (pendingProgressMax > 0) {
                    progressBar.setProgress(
                        pendingProgress,
                        pendingProgressMax,
                        true,
                    )
                }
            }
        }

        override fun onFinishDownloading(
            success: Boolean,
            uri: Uri,
            file: File,
            errorItem: ErrorItem?,
        ) {
            readFileTask = null
            readBitmapCallback = null
            if (this.isCurrentHolder) {
                val holder = instance.currentHolder!!
                holder.progressBar.setVisible(false, false)
                if (success) {
                    applyImageFromFile(file)
                } else {
                    // errorItem is nullable here; a bare toString() would show "null".
                    instance.callback.showError(
                        holder,
                        (errorItem ?: ErrorItem(ErrorItem.Type.UNKNOWN)).toString(),
                    )
                }
            }
        }

        override fun onCancelDownloading() {
            if (this.isCurrentHolder) {
                currentProgressBar.setVisible(false, true)
            }
        }

        override fun onUpdateProgress(
            progress: Long,
            progressMax: Long,
        ) {
            if (this.isCurrentHolder) {
                val progressBar = currentProgressBar
                progressBar.setIndeterminate(false)
                progressBar.setProgress(
                    progress.toInt(),
                    progressMax.toInt(),
                    progress == 0L,
                )
            } else {
                pendingProgress = progress.toInt()
                pendingProgressMax = progressMax.toInt()
            }
        }
    }

    fun hasMetadata(): Boolean {
        val jpegData = instance.currentHolder!!.jpegData
        return jpegData != null &&
            jpegData.exifData != null &&
            !jpegData.exifData
                .getUserMetadata()
                .isEmpty()
    }

    fun viewMetadata() {
        val holder = instance.currentHolder!!
        val fileName =
            holder
                .galleryItem!!
                .getFileName(get(instance.galleryInstance.chanName))
        showMetadata(
            instance.galleryInstance.callback.getChildFragmentManager(),
            holder.jpegData,
            fileName,
        )
    }

    private inner class DecodeBitmapTask(
        private val file: File,
        private val fileHolder: FileHolder,
    ) : ExecutorTask<Unit?, Unit?>() {
        private val photoView: PhotoView

        private var bitmap: Bitmap? = null
        private var decoderDrawable: DecoderDrawable? = null
        private var animatedImageDecoder: AnimatedImageDecoder? = null
        private var errorMessageId = 0

        init {
            val holder = instance.currentHolder!!
            photoView = holder.photoView
            if ((fileHolder.imageWidth >= 2048 && fileHolder.imageHeight >= 2048) ||
                fileHolder.imageType == FileHolder.ImageType.IMAGE_SVG
            ) {
                val progressBar = holder.progressBar
                progressBar.setVisible(true, false)
                progressBar.setIndeterminate(true)
            }
        }

        override fun run(): Unit? {
            if (!fileHolder.isImage) {
                errorMessageId = R.string.image_is_corrupted
                return null
            }
            if (fileHolder.imageType == FileHolder.ImageType.IMAGE_PNG ||
                fileHolder.imageType == FileHolder.ImageType.IMAGE_GIF ||
                fileHolder.imageType == FileHolder.ImageType.IMAGE_WEBP
            ) {
                try {
                    animatedImageDecoder = AnimatedImageDecoder(file)
                    return null
                } catch (e: IOException) {
                    // Not an animated image, fall back to bitmap decoding
                }
            }
            try {
                val maxSize = photoView.maximumImageSizeAsync
                val decoded = fileHolder.readImageBitmap(maxSize, true, true)
                bitmap = decoded
                if (decoded == null) {
                    errorMessageId = R.string.image_is_corrupted
                } else {
                    // Display-only from here on: move to GPU memory. DecoderDrawable
                    // also only draws the scaled bitmap, never touches its pixels.
                    val hardware = GraphicsUtils.toHardware(decoded)
                    bitmap = hardware
                    if (hardware.getWidth() < fileHolder.imageWidth ||
                        hardware.getHeight() < fileHolder.imageHeight
                    ) {
                        try {
                            decoderDrawable = DecoderDrawable(hardware, fileHolder)
                            bitmap = null
                        } catch (e: OutOfMemoryError) {
                            // Ignore exception
                        } catch (e: IOException) {
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                errorMessageId = R.string.no_enough_memory_to_handle_image
            } catch (e: InterruptedException) {
                errorMessageId = R.string.unknown_error
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessageId = R.string.image_is_corrupted
            }
            return null
        }

        override fun onComplete(result: Unit?) {
            val holder = instance.currentHolder!!
            val bitmap = this.bitmap
            val decoderDrawable = this.decoderDrawable
            val animatedImageDecoder = this.animatedImageDecoder
            holder.decodeBitmapTask = null
            holder.progressBar.setVisible(false, false)
            if (bitmap != null || decoderDrawable != null || animatedImageDecoder != null) {
                val width: Int
                val height: Int
                if (animatedImageDecoder != null) {
                    holder.animatedImageDecoder = animatedImageDecoder
                    val drawable = animatedImageDecoder.getDrawable()
                    width = drawable.getIntrinsicWidth()
                    height = drawable.getIntrinsicHeight()
                    setPhotoViewImage(holder, drawable, true)
                } else if (decoderDrawable != null) {
                    holder.decoderDrawable = decoderDrawable
                    width = decoderDrawable.getIntrinsicWidth()
                    height = decoderDrawable.getIntrinsicHeight()
                    setPhotoViewImage(holder, decoderDrawable, decoderDrawable.hasAlpha())
                } else {
                    val simpleBitmapDrawable = SimpleBitmapDrawable(bitmap!!, true)
                    holder.simpleBitmapDrawable = simpleBitmapDrawable
                    width = bitmap.getWidth()
                    height = bitmap.getHeight()
                    setPhotoViewImage(
                        holder,
                        simpleBitmapDrawable,
                        bitmap.hasAlpha(),
                    )
                }
                if (holder.mediaSummary.updateDimensions(width, height)) {
                    instance.galleryInstance.callback.updateTitle()
                }
                holder.loadState = PagerInstance.LoadState.COMPLETE
                instance.galleryInstance.callback.invalidateOptionsMenu()
            } else {
                instance.callback.showError(
                    holder,
                    instance.galleryInstance.context.getString(errorMessageId),
                )
            }
        }

        fun setPhotoViewImage(
            holder: PagerInstance.ViewHolder,
            drawable: Drawable,
            hasAlpha: Boolean,
        ) {
            holder.photoView.setImage(drawable, hasAlpha, false, holder.photoViewThumbnail)
            holder.jpegData = fileHolder.jpegData
            holder.photoViewThumbnail = false
        }
    }

    companion object {
        private val EXECUTOR: Executor = newSingleThreadPool(20000, "DecodeBitmapTask", null)

        private fun showMetadata(
            fragmentManager: FragmentManager,
            jpegData: JpegData?,
            fileName: String?,
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider ->
                    val context = GalleryInstance.getCallback(provider).getWindow()!!.getContext()
                    val dialogBuilder =
                        AlertDialog
                            .Builder(context)
                            .setTitle(R.string.metadata)
                            .setPositiveButton(android.R.string.ok, null)
                    val exifData = if (jpegData != null) jpegData.exifData else null
                    val geolocation = if (exifData != null) exifData.getGeolocation(false) else null
                    if (geolocation != null) {
                        val uri =
                            Uri
                                .Builder()
                                .scheme("geo")
                                .appendQueryParameter(
                                    "q",
                                    geolocation + "(" + fileName + ")",
                                ).build()
                        val intent = Intent(Intent.ACTION_VIEW).setData(uri)
                        if (!context
                                .getPackageManager()
                                .queryIntentActivities(
                                    intent,
                                    PackageManager.MATCH_DEFAULT_ONLY,
                                ).isEmpty()
                        ) {
                            dialogBuilder.setNeutralButton(
                                R.string.show_on_map,
                                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                    context.startActivity(intent)
                                },
                            )
                        }
                    }
                    val dialog = dialogBuilder.create()
                    val layout = SummaryLayout(dialog)
                    if (exifData != null) {
                        for (pair in exifData.getUserMetadata()) {
                            if (pair != null) {
                                layout.add(pair.first, pair.second)
                            } else {
                                layout.addDivider()
                            }
                        }
                        layout.addDivider()
                    }
                    dialog
                },
            )
        }
    }
}
