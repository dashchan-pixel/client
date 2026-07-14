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
import com.mishiranu.dashchan.widget.PhotoView
import com.mishiranu.dashchan.widget.SummaryLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class ImageUnit(private val instance: PagerInstance) {
    private var readFileTask: ReadFileTask? = null
    private var readBitmapCallback: ReadBitmapCallback? = null

    fun interrupt(force: Boolean) {
        if (force && readFileTask != null) {
            readFileTask!!.cancel()
            readFileTask = null
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
                holder.progressBar!!.setVisible(false, true)
                holder.decodeBitmapTask = null
            }
        }
    }

    fun applyImage(uri: Uri, file: File, reload: Boolean) {
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
        if (holder!!.mediaSummary!!.updateSize(file.length())) {
            instance.galleryInstance.callback.updateTitle()
        }
        val fileHolder = obtain(file)
        if (holder.decodeBitmapTask != null) {
            (holder.decodeBitmapTask as DecodeBitmapTask).cancel()
            holder.progressBar!!.setVisible(false, true)
        }
        val decodeBitmapTask = DecodeBitmapTask(file, fileHolder)
        decodeBitmapTask.execute(EXECUTOR)
        holder.decodeBitmapTask = decodeBitmapTask
        val nextHolder = if (instance.scrollingLeft) instance.leftHolder else instance.rightHolder
        if (nextHolder != null && loadNearestImage!!
                .isNetworkAvailable(getInstance())
        ) {
            val nextGalleryItem = nextHolder.galleryItem
            val chan = get(instance.galleryInstance.chanName)
            if (nextGalleryItem!!.isImage(chan)) {
                val nextUri = nextGalleryItem.getFileUri(chan)
                val nextCachedFile: File? = CacheManager.getInstance().getMediaFile(nextUri, true)
                if (nextCachedFile != null && !nextCachedFile.exists()) {
                    loadImage(nextUri!!, nextCachedFile, nextHolder)
                }
            }
        }
    }

    private fun loadImage(uri: Uri, cachedFile: File, holder: PagerInstance.ViewHolder) {
        if (attachReadBitmapCallback(holder)) {
            return
        }
        if (readFileTask != null) {
            readFileTask!!.cancel()
        }
        readBitmapCallback = ReadBitmapCallback(holder.galleryItem)
        val chan = getPreferred(instance.galleryInstance.chanName, uri)
        readFileTask =
            ReadFileTask.createCachedMediaFile(readBitmapCallback!!, chan, uri, cachedFile)
        readFileTask!!.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
    }

    private fun attachReadBitmapCallback(holder: PagerInstance.ViewHolder?): Boolean {
        if (readBitmapCallback != null && readBitmapCallback!!.isHolder(holder)) {
            readBitmapCallback!!.attachDownloading()
            return true
        }
        return false
    }

    private inner class ReadBitmapCallback(private val galleryItem: GalleryItem?) : FileCallback {
        val isCurrentHolder: Boolean
            get() = isHolder(instance.currentHolder)

        fun isHolder(holder: PagerInstance.ViewHolder?): Boolean {
            return holder != null && holder.galleryItem == galleryItem
        }

        override fun onStartDownloading() {
            if (this.isCurrentHolder) {
                instance.currentHolder!!.progressBar!!.setVisible(true, false)
                instance.currentHolder!!.progressBar!!.setIndeterminate(true)
            }
        }

        private var pendingProgress = 0
        private var pendingProgressMax = 0

        fun attachDownloading() {
            if (this.isCurrentHolder) {
                instance.currentHolder!!.progressBar!!.setVisible(true, false)
                instance.currentHolder!!.progressBar!!.setIndeterminate(pendingProgressMax <= 0)
                if (pendingProgressMax > 0) {
                    instance.currentHolder!!.progressBar!!.setProgress(
                        pendingProgress,
                        pendingProgressMax,
                        true
                    )
                }
            }
        }

        override fun onFinishDownloading(
            success: Boolean,
            uri: Uri,
            file: File,
            errorItem: ErrorItem?
        ) {
            readFileTask = null
            readBitmapCallback = null
            if (this.isCurrentHolder) {
                instance.currentHolder!!.progressBar!!.setVisible(false, false)
                if (success) {
                    applyImageFromFile(file)
                } else {
                    instance.callback.showError(instance.currentHolder!!, errorItem.toString())
                }
            }
        }

        override fun onCancelDownloading() {
            if (this.isCurrentHolder) {
                instance.currentHolder!!.progressBar!!.setVisible(false, true)
            }
        }

        override fun onUpdateProgress(progress: Long, progressMax: Long) {
            if (this.isCurrentHolder) {
                instance.currentHolder!!.progressBar!!.setIndeterminate(false)
                instance.currentHolder!!.progressBar!!.setProgress(
                    progress.toInt(),
                    progressMax.toInt(),
                    progress == 0L
                )
            } else {
                pendingProgress = progress.toInt()
                pendingProgressMax = progressMax.toInt()
            }
        }
    }

    fun hasMetadata(): Boolean {
        val jpegData = instance.currentHolder!!.jpegData
        return jpegData != null && jpegData.exifData != null && !jpegData.exifData.getUserMetadata()
            .isEmpty()
    }

    fun viewMetadata() {
        val fileName = instance.currentHolder!!.galleryItem!!
            .getFileName(get(instance.galleryInstance.chanName))
        showMetadata(
            instance.galleryInstance.callback.getChildFragmentManager(),
            instance.currentHolder!!.jpegData, fileName
        )
    }

    private inner class DecodeBitmapTask(
        private val file: File,
        private val fileHolder: FileHolder
    ) : ExecutorTask<Void?, Void?>() {
        private val photoView: PhotoView?

        private var bitmap: Bitmap? = null
        private var decoderDrawable: DecoderDrawable? = null
        private var animatedImageDecoder: AnimatedImageDecoder? = null
        private var errorMessageId = 0

        init {
            photoView = instance.currentHolder!!.photoView
            if (fileHolder.imageWidth >= 2048 && fileHolder.imageHeight >= 2048
                || fileHolder.imageType == FileHolder.ImageType.IMAGE_SVG
            ) {
                instance.currentHolder!!.progressBar!!.setVisible(true, false)
                instance.currentHolder!!.progressBar!!.setIndeterminate(true)
            }
        }

        override fun run(): Void? {
            if (!fileHolder.isImage) {
                errorMessageId = R.string.image_is_corrupted
                return null
            }
            if (fileHolder.imageType == FileHolder.ImageType.IMAGE_PNG || fileHolder.imageType == FileHolder.ImageType.IMAGE_GIF || fileHolder.imageType == FileHolder.ImageType.IMAGE_WEBP) {
                try {
                    animatedImageDecoder = AnimatedImageDecoder(file)
                    return null
                } catch (e: IOException) {
                    // Not an animated image, fall back to bitmap decoding
                }
            }
            try {
                val maxSize = photoView!!.maximumImageSizeAsync
                bitmap = fileHolder.readImageBitmap(maxSize, true, true)
                if (bitmap == null) {
                    errorMessageId = R.string.image_is_corrupted
                } else {
                    // Display-only from here on: move to GPU memory. DecoderDrawable
                    // also only draws the scaled bitmap, never touches its pixels.
                    bitmap = GraphicsUtils.toHardware(bitmap!!)
                    if (bitmap!!.getWidth() < fileHolder.imageWidth ||
                        bitmap!!.getHeight() < fileHolder.imageHeight
                    ) {
                        try {
                            decoderDrawable = DecoderDrawable(bitmap!!, fileHolder)
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

        override fun onComplete(result: Void?) {
            val holder = instance.currentHolder
            holder!!.decodeBitmapTask = null
            holder.progressBar!!.setVisible(false, false)
            if (bitmap != null || decoderDrawable != null || animatedImageDecoder != null) {
                val width: Int
                val height: Int
                if (animatedImageDecoder != null) {
                    holder.animatedImageDecoder = animatedImageDecoder
                    val drawable = animatedImageDecoder!!.getDrawable()
                    width = drawable.getIntrinsicWidth()
                    height = drawable.getIntrinsicHeight()
                    setPhotoViewImage(holder, drawable, true)
                } else if (decoderDrawable != null) {
                    holder.decoderDrawable = decoderDrawable
                    width = decoderDrawable!!.getIntrinsicWidth()
                    height = decoderDrawable!!.getIntrinsicHeight()
                    setPhotoViewImage(holder, decoderDrawable!!, decoderDrawable!!.hasAlpha())
                } else {
                    holder.simpleBitmapDrawable = SimpleBitmapDrawable(bitmap!!, true)
                    width = bitmap!!.getWidth()
                    height = bitmap!!.getHeight()
                    setPhotoViewImage(
                        holder,
                        holder.simpleBitmapDrawable!!,
                        bitmap!!.hasAlpha()
                    )
                }
                if (holder.mediaSummary!!.updateDimensions(width, height)) {
                    instance.galleryInstance.callback.updateTitle()
                }
                holder.loadState = PagerInstance.LoadState.COMPLETE
                instance.galleryInstance.callback.invalidateOptionsMenu()
            } else {
                instance.callback.showError(
                    holder,
                    instance.galleryInstance.context.getString(errorMessageId)
                )
            }
        }

        fun setPhotoViewImage(
            holder: PagerInstance.ViewHolder,
            drawable: Drawable,
            hasAlpha: Boolean
        ) {
            holder.photoView!!.setImage(drawable, hasAlpha, false, holder.photoViewThumbnail)
            holder.jpegData = fileHolder.jpegData
            holder.photoViewThumbnail = false
        }
    }

    companion object {
        private val EXECUTOR: Executor = newSingleThreadPool(20000, "DecodeBitmapTask", null)

        private fun showMetadata(
            fragmentManager: FragmentManager,
            jpegData: JpegData?,
            fileName: String?
        ) {
            InstanceDialog(
                fragmentManager,
                null,
                InstanceDialog.Factory { provider: InstanceDialog.Provider? ->
                    val context = GalleryInstance.getCallback(provider!!).getWindow()!!.getContext()
                    val dialogBuilder = AlertDialog.Builder(context)
                        .setTitle(R.string.metadata)
                        .setPositiveButton(android.R.string.ok, null)
                    val exifData = if (jpegData != null) jpegData.exifData else null
                    val geolocation = if (exifData != null) exifData.getGeolocation(false) else null
                    if (geolocation != null) {
                        val uri = Uri.Builder().scheme("geo").appendQueryParameter(
                            "q",
                            geolocation + "(" + fileName + ")"
                        ).build()
                        val intent = Intent(Intent.ACTION_VIEW).setData(uri)
                        if (!context.getPackageManager().queryIntentActivities(
                                intent,
                                PackageManager.MATCH_DEFAULT_ONLY
                            ).isEmpty()
                        ) {
                            dialogBuilder.setNeutralButton(
                                R.string.show_on_map,
                                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                    context.startActivity(intent)
                                })
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
                })
        }
    }
}
