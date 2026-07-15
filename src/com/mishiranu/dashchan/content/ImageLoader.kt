package com.mishiranu.dashchan.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.widget.ImageView
import chan.content.Chan
import chan.content.ChanConfiguration
import chan.content.ChanPerformer.ReadContentData
import chan.content.ExtensionException
import chan.http.HttpException
import chan.http.HttpException.Companion.createNotFoundException
import chan.http.HttpHolder
import chan.http.HttpResponse
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication.Companion.getInstance
import com.mishiranu.dashchan.content.async.HttpHolderTask
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.storage.AutohideStorage.Companion.getInstance
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.isMain
import com.mishiranu.dashchan.util.ConcurrentUtils.mainGet
import com.mishiranu.dashchan.util.ConcurrentUtils.newThreadPool
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.util.GraphicsUtils.toHardware
import com.mishiranu.dashchan.util.LruCache
import com.mishiranu.dashchan.widget.AttachmentView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.Executor

class ImageLoader private constructor() {
    private val loaderTasks: HashMap<String?, LoaderTask?> = HashMap<String?, LoaderTask?>()
    private val notFoundMap = HashMap<String?, Long?>()

    private val executors = HashMap<String?, Executor?>()

    private fun getExecutor(chanName: String?): Executor {
        var executor = executors[chanName]
        if (executor == null) {
            executor = newThreadPool(3, 3, 0, "ImageLoader", chanName)
            executors[chanName] = executor
        }
        return executor
    }

    internal fun interface TaskCallback {
        fun onTaskFinished(
            key: String?,
            bitmap: Bitmap?,
            error: Boolean,
        )
    }

    private inner class LoaderTask(
        val uri: Uri,
        val chan: Chan,
        val key: String,
        val fromCacheOnly: Boolean,
    ) : HttpHolderTask<Unit, Bitmap?>(
            chan,
        ) {
        val callbacks: HashSet<TaskCallback> = HashSet<TaskCallback>()
        private val created = SystemClock.elapsedRealtime()

        private var notFound = false
        internal var finished = false

        override fun run(holder: HttpHolder): Bitmap? {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // Debounce image requests, taking into account that
            // a task can be executed much later than created.
            val sleep = 500 + created - SystemClock.elapsedRealtime()
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (e: InterruptedException) {
                    return null
                }
            }
            val scheme = uri.getScheme()
            val chanScheme = ChanConfiguration.SCHEME_CHAN == scheme
            val dataScheme = "data" == scheme
            val storeExternal = !chanScheme && !dataScheme
            var bitmap: Bitmap? = null
            try {
                bitmap =
                    if (storeExternal) {
                        CacheManager.Companion
                            .getInstance()
                            .loadThumbnailExternal(key)
                    } else {
                        null
                    }
                if (isCancelled()) {
                    return null
                }
                if (bitmap == null && !fromCacheOnly) {
                    if (chanScheme) {
                        val output = ByteArrayOutputStream()
                        if (!chan.configuration.readResourceUri(uri, output)) {
                            throw HttpException.createNotFoundException()
                        }
                        val bytes = output.toByteArray()
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else if (dataScheme) {
                        var data = uri.toString()
                        val index = data.indexOf("base64,")
                        if (index >= 0) {
                            data = data.substring(index + 7)
                            val bytes = Base64.decode(data, Base64.DEFAULT)
                            if (bytes != null) {
                                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                    } else {
                        val response: HttpResponse?
                        try {
                            val result =
                                chan.performer
                                    .safe()
                                    .onReadContent(
                                        ReadContentData(
                                            uri,
                                            CONNECT_TIMEOUT,
                                            READ_TIMEOUT,
                                            holder,
                                            -1,
                                            -1,
                                        ),
                                    )
                            response = if (result != null) result.response else null
                        } catch (e: ExtensionException) {
                            e.getErrorItemAndHandle()
                            return null
                        }
                        if (response != null) {
                            try {
                                bitmap = response.readBitmap()
                            } finally {
                                response.cleanupAndDisconnect()
                            }
                        }
                        if (bitmap == null) {
                            throw HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                        }
                    }
                    if (isCancelled()) {
                        return null
                    }
                    bitmap =
                        GraphicsUtils.reduceThumbnailSize(
                            MainApplication.getInstance().getResources(),
                            bitmap!!,
                        )
                    if (storeExternal) {
                        CacheManager.Companion.getInstance().storeThumbnailExternal(key, bitmap)
                    }
                }
            } catch (e: HttpException) {
                val responseCode = e.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    responseCode == HttpURLConnection.HTTP_GONE
                ) {
                    notFound = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
            }
            // Thumbnails are display-only: move them to GPU memory (no-op for
            // bitmaps that already came out of the cache as hardware bitmaps).
            return if (bitmap != null) toHardware(bitmap) else null
        }

        override fun onComplete(result: Bitmap?) {
            // Don't remove task but instead mark it as finished,
            // so targets could be extracted later.
            finished = true
            if (notFound) {
                notFoundMap[key] = SystemClock.elapsedRealtime()
            }
            if (result != null) {
                bitmapCache[key] = result
            }
            for (callback in callbacks) {
                callback.onTaskFinished(key, result, !fromCacheOnly)
            }
        }
    }

    private val bitmapCache =
        LruCache<String?, Bitmap?>(if (MainApplication.getInstance().isLowRam) 50 else 200)

    abstract class Target {
        var currentKey: String? = null

        internal val taskCallback =
            TaskCallback { key: String?, bitmap: Bitmap?, error: Boolean ->
                if (key == currentKey) {
                    onResult(key, bitmap, error, false)
                }
            }

        open fun onStart() {}

        abstract fun onResult(
            key: String?,
            bitmap: Bitmap?,
            error: Boolean,
            instantly: Boolean,
        )
    }

    private fun interface WrapperCallback<T> {
        fun onResult(
            target: T?,
            key: String?,
            bitmap: Bitmap?,
            error: Boolean,
            instantly: Boolean,
        )
    }

    private open class WrapperTarget<T>(
        val target: T?,
        val callback: WrapperCallback<T?>,
    ) : Target() {
        override fun onResult(
            key: String?,
            bitmap: Bitmap?,
            error: Boolean,
            instantly: Boolean,
        ) {
            callback.onResult(target, key, bitmap, error, instantly)
        }
    }

    private fun interface DetachCallback {
        fun onDetach(view: View?)
    }

    private class ViewTarget<T : View?>(
        target: T?,
        wrapperCallback: WrapperCallback<T?>,
        private val detachCallback: DetachCallback?,
    ) : WrapperTarget<T?>(target, wrapperCallback),
        Runnable,
        OnAttachStateChangeListener {
        init {
            target!!.addOnAttachStateChangeListener(this)
        }

        override fun onStart() {
            if (!target!!.isAttachedToWindow()) {
                onViewDetachedFromWindow(target)
            }
        }

        override fun run() {
            if (detachCallback != null) {
                detachCallback.onDetach(target)
            }
        }

        override fun onViewAttachedToWindow(v: View) {
            ConcurrentUtils.HANDLER.removeCallbacks(this)
        }

        override fun onViewDetachedFromWindow(v: View) {
            ConcurrentUtils.HANDLER.removeCallbacks(this)
            ConcurrentUtils.HANDLER.postDelayed(this, 2000L)
        }
    }

    private val detachCallback = DetachCallback { view: View? -> this.cancel(view!!) }

    // View.getTag() is erased to Any?; only this loader ever writes tag_image_loader,
    // so the tag is always the matching WrapperTarget.
    @Suppress("UNCHECKED_CAST")
    private fun <T : View?> getWrapperTarget(
        view: T?,
        wrapperCallback: WrapperCallback<T?>?,
    ): WrapperTarget<T?>? {
        val wrapperTarget: WrapperTarget<T?>? =
            view!!.getTag(R.id.tag_image_loader) as WrapperTarget<T?>?
        if (wrapperTarget == null && wrapperCallback != null) {
            val viewTarget = ViewTarget<T?>(view, wrapperCallback, detachCallback)
            view.setTag(R.id.tag_image_loader, viewTarget)
            return viewTarget
        }
        return wrapperTarget
    }

    fun hasRunningTask(view: View): Boolean {
        val wrapperTarget: WrapperTarget<*>? = getWrapperTarget<View?>(view, null)
        if (wrapperTarget != null && wrapperTarget.currentKey != null) {
            val loaderTask = loaderTasks[wrapperTarget.currentKey]
            return loaderTask != null && !loaderTask.finished
        }
        return false
    }

    fun cancel(target: Target) {
        val key = target.currentKey
        target.currentKey = null
        if (key != null) {
            val loaderTask = loaderTasks[key]
            if (loaderTask != null) {
                loaderTask.callbacks.remove(target.taskCallback)
                if (loaderTask.callbacks.isEmpty()) {
                    loaderTask.cancel()
                    loaderTasks.remove(key)
                }
            }
        }
    }

    fun cancel(view: View) {
        val wrapperTarget: WrapperTarget<*>? = getWrapperTarget<View?>(view, null)
        if (wrapperTarget != null) {
            cancel(wrapperTarget)
        }
    }

    fun loadImage(
        chan: Chan,
        uri: Uri,
        fromCacheOnly: Boolean,
        target: ImageView,
    ) {
        val wrapperTarget = getWrapperTarget<ImageView?>(target, WRAPPER_CALLBACK_IMAGE_VIEW)
        loadImage(chan, uri, null, fromCacheOnly, wrapperTarget!!)
    }

    fun loadImage(
        chan: Chan,
        uri: Uri,
        key: String?,
        fromCacheOnly: Boolean,
        target: AttachmentView,
    ) {
        val wrapperTarget =
            getWrapperTarget<AttachmentView?>(target, WRAPPER_CALLBACK_ATTACHMENT_VIEW)
        loadImage(chan, uri, key, fromCacheOnly, wrapperTarget!!)
    }

    fun loadImage(
        chan: Chan,
        uri: Uri,
        key: String?,
        fromCacheOnly: Boolean,
        target: Target,
    ): Boolean {
        var imageKey = key
        if (imageKey == null) {
            imageKey = CacheManager.Companion.getInstance().getCachedFileKey(uri)
        }
        if (imageKey == null) {
            return false
        }
        val mainThread = isMain()
        if (mainThread) {
            cancel(target)
        }
        val memoryCachedBitmap: Bitmap?
        if (mainThread) {
            memoryCachedBitmap = bitmapCache[imageKey]
        } else {
            val finalKey: String? = imageKey
            memoryCachedBitmap = mainGet<Bitmap?>(Callable { bitmapCache[finalKey] })
        }
        if (memoryCachedBitmap != null) {
            target.onResult(imageKey, memoryCachedBitmap, false, true)
            return true
        } else if (!mainThread) {
            // Don't enqueue tasks requested from non-main thread
            target.onResult(imageKey, null, false, true)
            return false
        }
        // Check "not found" images once per 5 minutes
        val value = notFoundMap[imageKey]
        if (value != null && SystemClock.elapsedRealtime() - value < 5 * 60 * 1000) {
            target.onResult(imageKey, null, !fromCacheOnly, true)
            return false
        }
        target.currentKey = imageKey
        target.onStart()
        val currentLoaderTask = loaderTasks[imageKey]
        val startTask =
            currentLoaderTask == null || currentLoaderTask.finished || currentLoaderTask.fromCacheOnly && !fromCacheOnly
        var registerLoaderTask = currentLoaderTask
        if (startTask) {
            val loaderTask = LoaderTask(uri, chan, imageKey, fromCacheOnly)
            registerLoaderTask = loaderTask
            if (currentLoaderTask != null) {
                currentLoaderTask.cancel()
                loaderTask.callbacks.addAll(currentLoaderTask.callbacks)
            }
            loaderTasks[imageKey] = loaderTask
            loaderTask.execute(getExecutor(chan.name))
        }
        registerLoaderTask.callbacks.add(target.taskCallback)
        return false
    }

    companion object {
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 5000

        private val INSTANCE = ImageLoader()

        @JvmStatic
        fun getInstance(): ImageLoader = INSTANCE

        private val WRAPPER_CALLBACK_IMAGE_VIEW =
            WrapperCallback { target: ImageView?, key: String?, bitmap: Bitmap?, error: Boolean, instantly: Boolean ->
                if (bitmap != null) {
                    target!!.setImageBitmap(bitmap)
                } else {
                    target!!.setImageDrawable(null)
                }
            }

        private val WRAPPER_CALLBACK_ATTACHMENT_VIEW: WrapperCallback<AttachmentView?> =
            WrapperCallback { obj: AttachmentView?, key: String?, bitmap: Bitmap?, error: Boolean, instantly: Boolean ->
                obj!!.handleLoadedImage(
                    key,
                    bitmap,
                    error,
                    instantly,
                )
            }
    }
}
