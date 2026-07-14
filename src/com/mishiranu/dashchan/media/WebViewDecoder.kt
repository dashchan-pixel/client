package com.mishiranu.dashchan.media

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.model.FileHolder
import com.mishiranu.dashchan.util.IOUtils
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch

class WebViewDecoder private constructor(
    private val fileHolder: FileHolder,
    options: BitmapFactory.Options?,
) : WebViewClient() {
    private val sampleSize: Int = options?.inSampleSize?.coerceAtLeast(1) ?: 1
    private val latch = CountDownLatch(1)

    @Volatile private var bitmap: Bitmap? = null

    private var webView: WebView? = null

    init {
        if (!fileHolder.isImage) {
            throw IOException()
        }
        HANDLER.obtainMessage(MESSAGE_INIT_WEB_VIEW, this).sendToTarget()
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(
        view: WebView,
        url: String,
    ): WebResourceResponse? {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.endsWith("//127.0.0.1/image.jpeg")) {
                var inputStream: InputStream? = null
                try {
                    inputStream = fileHolder.openInputStream()
                    return WebResourceResponse(
                        if (fileHolder.imageType == FileHolder.ImageType.IMAGE_SVG) {
                            "image/svg+xml"
                        } else {
                            "image/jpeg"
                        },
                        null,
                        inputStream,
                    )
                } catch (e: IOException) {
                    IOUtils.close(inputStream)
                }
            }
            WebResourceResponse("text/html", "UTF-8", null)
        } else {
            null
        }
    }

    private var pageFinished = false

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        pageFinished = true
        notifyExtract(view)
    }

    private val pictureListener =
        WebView.PictureListener { view, _ ->
            if (pageFinished) {
                notifyExtract(view)
            }
        }

    private fun notifyExtract(view: WebView) {
        HANDLER.removeMessages(MESSAGE_MEASURE_PICTURE)
        HANDLER.sendMessageDelayed(
            HANDLER.obtainMessage(
                MESSAGE_MEASURE_PICTURE,
                arrayOf<Any>(this, view),
            ),
            1000,
        )
    }

    private var measured = false

    private fun measurePicture(view: WebView) {
        if (!measured) {
            measured = true
            webView = view
            view.loadUrl("javascript:calculateSize();")
        }
    }

    private fun countDownAndDestroy(view: WebView) {
        latch.countDown()
        view.destroy()
    }

    private fun checkPictureSize(
        width: Int,
        height: Int,
    ) {
        val webView = this.webView!!
        if (width > 0 && height > 0) {
            if (webView.width <= 0 || webView.height <= 0) {
                measured = false
                webView.layout(0, 0, width, height)
                webView.reload()
            } else {
                if (webView.width != width || webView.height != height) {
                    countDownAndDestroy(webView)
                } else {
                    try {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        this.bitmap = bitmap
                        webView.draw(Canvas(bitmap))
                    } catch (e: OutOfMemoryError) {
                        bitmap = null
                    } finally {
                        countDownAndDestroy(webView)
                    }
                }
            }
        } else {
            countDownAndDestroy(webView)
        }
        this.webView = null
    }

    private class Callback : Handler.Callback {
        @Suppress("DEPRECATION")
        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MESSAGE_INIT_WEB_VIEW -> {
                    val decoder = msg.obj as WebViewDecoder
                    var width = decoder.fileHolder.imageWidth
                    var height = decoder.fileHolder.imageHeight
                    val rotation = decoder.fileHolder.imageRotation
                    if (rotation == 90 || rotation == 270) {
                        val temp = width
                        width = height
                        height = temp
                    }
                    width /= decoder.sampleSize
                    height /= decoder.sampleSize
                    val webView = WebView(MainApplication.getInstance())
                    val settings = webView.settings
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.javaScriptEnabled = true
                    webView.setInitialScale(100 / decoder.sampleSize)
                    webView.webViewClient = decoder
                    webView.setBackgroundColor(Color.TRANSPARENT)
                    webView.setPictureListener(decoder.pictureListener)
                    webView.addJavascriptInterface(decoder, "jsi")
                    if (width > 0 && height > 0) {
                        webView.layout(0, 0, width, height)
                    }
                    webView.loadData(
                        "<!DOCTYPE html><html><head><script type=\"text/javascript\">" +
                            "function calculateSize() {jsi.onCalculateSize(" +
                            "document.body.children[0].naturalWidth, " +
                            "document.body.children[0].naturalHeight);}</script></head>" +
                            "<body style=\"margin: 0\">" +
                            "<img src=\"http://127.0.0.1/image.jpeg\" /></body></html>",
                        "text/html",
                        "UTF-8",
                    )
                    return true
                }

                MESSAGE_MEASURE_PICTURE -> {
                    val data = msg.obj as Array<*>
                    val decoder = data[0] as WebViewDecoder
                    val webView = data[1] as WebView
                    decoder.measurePicture(webView)
                    return true
                }

                MESSAGE_CHECK_PICTURE_SIZE -> {
                    val data = msg.obj as Array<*>
                    val decoder = data[0] as WebViewDecoder
                    val width = data[1] as Int
                    val height = data[2] as Int
                    decoder.checkPictureSize(width, height)
                    return true
                }
            }
            return false
        }
    }

    @JavascriptInterface
    fun onCalculateSize(
        width: Int,
        height: Int,
    ) {
        HANDLER
            .obtainMessage(
                MESSAGE_CHECK_PICTURE_SIZE,
                arrayOf<Any>(this, width / sampleSize, height / sampleSize),
            ).sendToTarget()
    }

    companion object {
        private const val MESSAGE_INIT_WEB_VIEW = 1
        private const val MESSAGE_MEASURE_PICTURE = 2
        private const val MESSAGE_CHECK_PICTURE_SIZE = 3

        private val HANDLER = Handler(Looper.getMainLooper(), Callback())

        @JvmStatic
        fun loadBitmap(
            fileHolder: FileHolder,
            options: BitmapFactory.Options?,
        ): Bitmap? {
            if (!MainApplication.getInstance().isLowRam) {
                val decoder =
                    try {
                        WebViewDecoder(fileHolder, options)
                    } catch (e: IOException) {
                        return null
                    }
                return decoder.bitmap
            }
            return null
        }
    }
}
