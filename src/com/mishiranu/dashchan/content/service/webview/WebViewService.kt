package com.mishiranu.dashchan.content.service.webview

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.RemoteException
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import chan.http.HttpClient
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.IOUtils.readRawResourceString
import com.mishiranu.dashchan.util.WebViewUtils.clearAll
import com.mishiranu.dashchan.util.WebViewUtils.setProxy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedList

class WebViewService : Service() {
    private class CookieRequest(
        val uriString: String,
        val userAgent: String?,
        val proxyData: HttpClient.ProxyData?,
        val verifyCertificate: Boolean,
        val timeout: Long,
        val extra: WebViewExtra?,
        val requestCallback: IRequestCallback,
    ) {
        var ready: Boolean = false
        var finished: Boolean = false

        var recaptchaV2ApiKey: String? = null
        var recaptchaV2Result: String? = null
        var recaptchaIsHcaptcha: Boolean = false
    }

    private val cookieRequests: LinkedList<CookieRequest?> = LinkedList<CookieRequest?>()

    private var webView: WebView? = null
    private var cookieRequest: CookieRequest? = null
    private var captchaThread: Thread? = null

    private val handler =
        Handler(
            Looper.getMainLooper(),
            Handler.Callback { message: Message? ->
                if (webView == null) {
                    return@Callback false
                }
                when (message!!.what) {
                    MESSAGE_HANDLE_NEXT -> {
                        handleNextCookieRequest()
                        return@Callback true
                    }

                    MESSAGE_HANDLE_FINISH -> {
                        val cookieRequest = this.cookieRequest
                        if (cookieRequest != null) {
                            synchronized(cookieRequest) {
                                if (!cookieRequest.ready) {
                                    cookieRequest.ready = true
                                    (cookieRequest as Object).notifyAll()
                                }
                            }
                            this.cookieRequest = null
                        }
                        handleNextCookieRequest()
                        return@Callback true
                    }

                    MESSAGE_HANDLE_AFTER_INTERRUPT -> {
                        val cookieRequest: CookieRequest? = message.obj as CookieRequest?
                        if (cookieRequest === this.cookieRequest) {
                            this.cookieRequest = null
                            handleNextCookieRequest()
                        }
                        return@Callback true
                    }

                    MESSAGE_HANDLE_CAPTCHA -> {
                        val cookieRequest: CookieRequest = message.obj as CookieRequest
                        if (cookieRequest === this.cookieRequest) {
                            message.getTarget().removeMessages(MESSAGE_HANDLE_FINISH)
                            if (cookieRequest.recaptchaV2Result != null) {
                                webView!!.loadUrl("javascript:handleResult('" + cookieRequest.recaptchaV2Result + "')")
                                message
                                    .getTarget()
                                    .sendEmptyMessageDelayed(MESSAGE_HANDLE_FINISH, cookieRequest.timeout)
                            } else {
                                message.getTarget().sendEmptyMessage(MESSAGE_HANDLE_FINISH)
                            }
                        }
                        return@Callback true
                    }

                    MESSAGE_DRAW_TO_FILE -> {
                        if (captureImageFile != null && webView != null) {
                            val bitmap =
                                Bitmap.createBitmap(
                                    webView!!.getLayoutParams().width,
                                    webView!!.getLayoutParams().height,
                                    Bitmap.Config.ARGB_8888,
                                )
                            webView!!.draw(Canvas(bitmap))
                            try {
                                FileOutputStream(captureImageFile).use { output ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                }
                            } catch (e: IOException) {
                                // Ignore exception
                            } finally {
                                bitmap.recycle()
                            }
                            message.getTarget().sendEmptyMessageDelayed(
                                MESSAGE_DRAW_TO_FILE,
                                DRAW_TO_FILE_INTERVAL.toLong(),
                            )
                        }
                        return@Callback true
                    }
                }
                false
            },
        )

    private inner class ServiceClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = false

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            super.onPageFinished(view, url)

            val cookieRequest = this@WebViewService.cookieRequest
            var finished = true
            if (cookieRequest != null) {
                val cookie = CookieManager.getInstance().getCookie(url)
                try {
                    finished =
                        cookieRequest.requestCallback.onPageFinished(url, cookie, view.getTitle())
                    cookieRequest.finished = finished
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
                if (!finished) {
                    if (cookieRequest.extra != null) {
                        val injectJavascript = cookieRequest.extra.getInjectJavascript()
                        if (injectJavascript != null) {
                            val sanitized =
                                injectJavascript
                                    .replace("\\", "\\\\")
                                    .replace("\n", "\\n")
                                    .replace("\"", "\\\"")
                            view.loadUrl("javascript:eval(\"" + sanitized + "\")")
                        }
                    }
                }
            }
            if (finished) {
                view.stopLoading()
                handler.removeMessages(MESSAGE_HANDLE_FINISH)
                handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH)
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler,
            error: SslError?,
        ) {
            val cookieRequest = this@WebViewService.cookieRequest
            if (cookieRequest != null) {
                if (cookieRequest.verifyCertificate) {
                    handler.cancel()
                    handler.removeMessages(MESSAGE_HANDLE_FINISH)
                    handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH)
                } else {
                    handler.proceed()
                }
            } else {
                super.onReceivedSslError(view, handler, error)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)

            // The deprecated single-error callback was reported for the main resource only.
            if (request.isForMainFrame()) {
                handler.removeMessages(MESSAGE_HANDLE_FINISH)
                handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH)
            }
        }

        fun getCaptchaApi(onLoad: String?): WebResourceResponse {
            val stub =
                readRawResourceString(getResources(), R.raw.web_captcha_api)
                    .replace("__REPLACE_ON_LOAD__", if (onLoad != null) "'" + onLoad + "'" else "null")
            return WebResourceResponse(
                "application/javascript",
                "UTF-8",
                ByteArrayInputStream(stub.toByteArray()),
            )
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            var allowed = false
            val cookieRequest = this@WebViewService.cookieRequest
            val uri = request.getUrl()
            val url = uri.toString()
            val recaptcha = url.contains("recaptcha")
            val hcaptcha = url.contains("hcaptcha")
            if (recaptcha || hcaptcha) {
                val key = uri.getQueryParameter("render")
                val onLoad = uri.getQueryParameter("onload")
                if (key != null || onLoad != null) {
                    cookieRequest!!.recaptchaV2ApiKey = key
                    cookieRequest.recaptchaIsHcaptcha = hcaptcha
                    return getCaptchaApi(onLoad)
                }
            }
            if (cookieRequest != null) {
                try {
                    allowed = cookieRequest.requestCallback.onLoad(url)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
            if (allowed) {
                return super.shouldInterceptRequest(view, request)
            } else {
                return WebResourceResponse("text/html", "UTF-8", null)
            }
        }
    }

    @Suppress("unused")
    private val javascriptInterface: Any =
        object : Any() {
            @JavascriptInterface
            fun onRequestRecaptcha(apiKey: String?) {
                val cookieRequest = this@WebViewService.cookieRequest
                if (cookieRequest != null) {
                    if (apiKey != null) {
                        cookieRequest.recaptchaV2ApiKey = apiKey
                    }
                    handler.removeMessages(MESSAGE_HANDLE_FINISH)
                    startCaptchaThread(cookieRequest)
                }
            }
        }

    private fun startCaptchaThread(cookieRequest: CookieRequest) {
        synchronized(this) {
            if (captchaThread != null) {
                captchaThread!!.interrupt()
            }
            captchaThread =
                Thread(
                    Runnable {
                        try {
                            if (cookieRequest.recaptchaIsHcaptcha) {
                                cookieRequest.recaptchaV2Result =
                                    cookieRequest.requestCallback
                                        .onHcaptcha(cookieRequest.recaptchaV2ApiKey, cookieRequest.uriString)
                            } else {
                                cookieRequest.recaptchaV2Result =
                                    cookieRequest.requestCallback
                                        .onRecaptchaV2(
                                            cookieRequest.recaptchaV2ApiKey,
                                            true,
                                            cookieRequest.uriString,
                                        )
                            }
                        } catch (e: RemoteException) {
                            // Ignore
                        }
                        handler.obtainMessage(MESSAGE_HANDLE_CAPTCHA, cookieRequest).sendToTarget()
                    },
                )
            captchaThread!!.start()
        }
    }

    private fun handleNextCookieRequest() {
        if (cookieRequest == null) {
            synchronized(cookieRequests) {
                while (!cookieRequests.isEmpty()) {
                    cookieRequest = cookieRequests.removeFirst()
                    // Ignore interrupted requests
                    if (!cookieRequest!!.ready) {
                        break
                    }
                }
            }
            handler.removeMessages(MESSAGE_DRAW_TO_FILE)
            webView!!.stopLoading()
            clearAll(webView)
            val cookieRequest = this.cookieRequest
            if (cookieRequest != null) {
                handler.removeMessages(MESSAGE_HANDLE_FINISH)
                handler.sendEmptyMessageDelayed(MESSAGE_HANDLE_FINISH, cookieRequest.timeout)
                webView!!.getSettings().setUserAgentString(cookieRequest.userAgent)
                setProxy(
                    this,
                    cookieRequest.proxyData,
                    Runnable {
                        if (this.cookieRequest === cookieRequest && webView != null) {
                            webView!!.loadUrl(cookieRequest.uriString)
                        }
                    },
                )
                if (captureImageFile != null) {
                    handler.sendEmptyMessageDelayed(
                        MESSAGE_DRAW_TO_FILE,
                        DRAW_TO_FILE_INTERVAL.toLong(),
                    )
                }
            } else {
                webView!!.loadUrl("about:blank")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return object : IWebViewService.Stub() {
            private val interruptedRequests: HashMap<String?, CookieRequest?> =
                HashMap<String?, CookieRequest?>()

            @Throws(RemoteException::class)
            override fun loadWithCookieResult(
                requestId: String?,
                uriString: String,
                userAgent: String?,
                proxySocks: Boolean,
                proxyHost: String?,
                proxyPort: Int,
                verifyCertificate: Boolean,
                timeout: Long,
                extra: WebViewExtra?,
                requestCallback: IRequestCallback,
            ): Boolean {
                val proxyData =
                    if (proxyHost != null) {
                        HttpClient.ProxyData(proxySocks, proxyHost, proxyPort)
                    } else {
                        null
                    }
                val cookieRequest =
                    CookieRequest(
                        uriString,
                        userAgent,
                        proxyData,
                        verifyCertificate,
                        timeout,
                        extra,
                        requestCallback,
                    )
                synchronized(interruptedRequests) {
                    if (interruptedRequests.containsKey(requestId)) {
                        interruptedRequests.remove(requestId)
                        return false
                    } else {
                        interruptedRequests[requestId] = cookieRequest
                    }
                }
                try {
                    synchronized(cookieRequests) {
                        cookieRequests.add(cookieRequest)
                    }
                    synchronized(cookieRequest) {
                        handler.sendEmptyMessage(MESSAGE_HANDLE_NEXT)
                        while (!cookieRequest.ready) {
                            try {
                                (cookieRequest as Object).wait()
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw RemoteException("interrupted")
                            }
                        }
                    }
                    return cookieRequest.finished
                } finally {
                    synchronized(interruptedRequests) {
                        interruptedRequests.remove(requestId)
                    }
                }
            }

            override fun interrupt(requestId: String?) {
                synchronized(interruptedRequests) {
                    val cookieRequest = interruptedRequests[requestId]
                    if (cookieRequest != null) {
                        synchronized(cookieRequest) {
                            cookieRequest.ready = true
                            (cookieRequest as Object).notifyAll()
                        }
                        handler
                            .obtainMessage(MESSAGE_HANDLE_AFTER_INTERRUPT, cookieRequest)
                            .sendToTarget()
                    } else {
                        interruptedRequests[requestId] = null
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate() {
        super.onCreate()

        if (!captureImageFileInit) {
            captureImageFileInit = true
            val file = File(getExternalCacheDir()!!.getParentFile(), "files/webview.png")
            if (file.exists()) {
                captureImageFile = file
                WebView.enableSlowWholeDocumentDraw()
            }
        }

        webView = WebView(this)
        webView!!.getSettings().setJavaScriptEnabled(true)
        webView!!.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE)
        webView!!.addJavascriptInterface(javascriptInterface, "jsi")
        webView!!.setWebViewClient(ServiceClient())
        webView!!.setWebChromeClient(
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val text = consoleMessage.message()
                    if (text != null && text.contains("SyntaxError")) {
                        handler.removeMessages(MESSAGE_HANDLE_FINISH)
                        handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH)
                        return true
                    }
                    return false
                }
            },
        )
        webView!!.setLayoutParams(ViewGroup.LayoutParams(480, 270))
        var initialScale = 25
        if (captureImageFile != null) {
            val factor = 4
            webView!!.getLayoutParams().width *= factor
            webView!!.getLayoutParams().height *= factor
            initialScale *= factor
        }
        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        webView!!.measure(measureSpec, measureSpec)
        webView!!.layout(
            0,
            0,
            webView!!.getLayoutParams().width,
            webView!!.getLayoutParams().height,
        )
        webView!!.setInitialScale(initialScale)
    }

    override fun onDestroy() {
        super.onDestroy()

        webView!!.destroy()
        webView = null
    }

    companion object {
        private var captureImageFileInit = false
        private var captureImageFile: File? = null

        private const val DRAW_TO_FILE_INTERVAL = 2000

        private const val MESSAGE_HANDLE_NEXT = 1
        private const val MESSAGE_HANDLE_FINISH = 2
        private const val MESSAGE_HANDLE_AFTER_INTERRUPT = 3
        private const val MESSAGE_HANDLE_CAPTCHA = 4
        private const val MESSAGE_DRAW_TO_FILE = 5
    }
}
