package com.mishiranu.dashchan.content.net

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Pair
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.content.Chan.Companion.getFallback
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.UrlEncodedEntity
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.AdvancedPreferences.getGoogleCookie
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.RecaptchaReader.ChallengeExtra.ForegroundSolver
import com.mishiranu.dashchan.content.net.RecaptchaReader.WebViewHolder.ArgumentsProvider
import com.mishiranu.dashchan.text.HtmlParser.Companion.clear
import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.mainGet
import com.mishiranu.dashchan.util.GraphicsUtils.handleBlackAndWhiteCaptchaImage
import com.mishiranu.dashchan.util.GraphicsUtils.isBlackAndWhiteCaptchaImage
import com.mishiranu.dashchan.util.IOUtils.readRawResourceString
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.ScaledWebView
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

class RecaptchaReader private constructor() {
    private val accessLock = Any()

    class ChallengeExtra(
		private val solver: ForegroundSolver,
	    @JvmField val response: String?,
	    internal var holder: WebViewHolder?
    ) {
        private fun interface ForegroundSolver {
            @Throws(CancelException::class, HttpException::class, InterruptedException::class)
            fun solve(holder: HttpHolder?, challengeExtra: ChallengeExtra?): String?
        }

        @Throws(CancelException::class, HttpException::class, InterruptedException::class)
        fun getResponse(holder: HttpHolder?): String? {
            if (response != null) {
                return response
            } else {
                return solver.solve(holder, this)
            }
        }

        fun cleanup() {
            if (holder != null) {
                ConcurrentUtils.HANDLER.post(Runnable {
                    if (holder != null) {
                        holder!!.destroy()
                        holder = null
                    }
                })
            }
        }
    }

    @Throws(CancelException::class, HttpException::class)
    fun getChallenge2(
        initialHolder: HttpHolder,
        apiKey: String,
        invisible: Boolean,
        referer: String?,
        useJavaScript: Boolean,
        solveInBackground: Boolean,
        solveAutomatically: Boolean
    ): ChallengeExtra? {
        val refererFinal = if (referer != null) referer else "https://www.google.com/"
        if (solveAutomatically) {
            val autoResponse = CaptchaSolving.getInstance().solveCaptcha(
                initialHolder,
                if (invisible) CaptchaSolving.CaptchaType.RECAPTCHA_2_INVISIBLE else CaptchaSolving.CaptchaType.RECAPTCHA_2,
                apiKey,
                refererFinal
            )
            if (autoResponse != null) {
                return ChallengeExtra(null, autoResponse, null)
            }
        }
        if (useJavaScript) {
            val solver =
                ForegroundSolver { newHolder: HttpHolder?, challengeExtra: ChallengeExtra? ->
                    synchronized(accessLock) {
                        val response = ForegroundManager.getInstance()
                            .requireUserRecaptchaV2(
                                refererFinal,
                                apiKey,
                                invisible,
                                false,
                                challengeExtra
                            )
                        if (response == null) {
                            throw CancelException()
                        }
                        return@ForegroundSolver response
                    }
                }
            synchronized(accessLock) {
                if (solveInBackground) {
                    return BackgroundSolver(solver, refererFinal, apiKey, invisible, false).await()
                } else {
                    return ChallengeExtra(solver, null, null)
                }
            }
        } else {
            val chan = getFallback()
            val uri = chan.locator.buildQueryWithHost(
                "www.google.com",
                "recaptcha/api/fallback",
                "k",
                apiKey
            )
            val acceptLanguage = "en-US,en;q=0.5"
            val initialResponseText: String = chan.http.HttpRequest(uri, initialHolder)
                .addCookie(getGoogleCookie())
                .addHeader("Accept-Language", acceptLanguage)
                .addHeader("Referer", refererFinal)
                .perform().readString()
            if (initialResponseText == null) {
                throw chan.http.HttpException(ErrorItem.Type.INVALID_RESPONSE, false, false)
            }
            val initialResponse: Pair<String?, String?>? = parseResponse2(initialResponseText)
            if (initialResponse == null) {
                if (initialResponseText
                        .contains("Please enable JavaScript to get a reCAPTCHA challenge")
                ) {
                    return getChallenge2(
                        initialHolder, apiKey, invisible, refererFinal,
                        true, solveInBackground, false
                    )
                } else {
                    throw chan.http.HttpException(ErrorItem.Type.INVALID_RESPONSE, false, false)
                }
            }
            val consumed = booleanArrayOf(false)
            val solver = ForegroundSolver { holder: HttpHolder?, challengeExtra: ChallengeExtra? ->
                var captchaImage: Bitmap? = null
                var response: Pair<String?, String?>?
                if (consumed[0]) {
                    val responseText: String = chan.http.HttpRequest(uri, holder)
                        .addCookie(getGoogleCookie())
                        .addHeader("Accept-Language", acceptLanguage)
                        .addHeader("Referer", refererFinal)
                        .perform().readString()
                    response = parseResponse2(responseText)
                } else {
                    consumed[0] = true
                    response = initialResponse
                }
                while (true) {
                    if (response != null) {
                        if (captchaImage != null) {
                            captchaImage.recycle()
                        }
                        captchaImage = getImage2(holder, apiKey, response.second, null, false).first
                        val result = ForegroundManager.getInstance().requireUserImageMultipleChoice(
                            3, null,
                            Companion.splitImages(captchaImage!!, 3, 3), clear(response.first), null
                        )
                        if (result != null) {
                            var hasSelected = false
                            val entity = UrlEncodedEntity("c", response.second!!)
                            for (i in result.indices) {
                                if (result[i]) {
                                    entity.add("response", i.toString())
                                    hasSelected = true
                                }
                            }
                            if (!hasSelected) {
                                continue
                            }
                            val responseText: String =
                                chan.http.HttpRequest(uri, holder).setPostMethod(entity)
                                    .addCookie(getGoogleCookie())
                                    .setRedirectHandler(chan.http.HttpRequest.RedirectHandler.STRICT)
                                    .addHeader("Accept-Language", acceptLanguage)
                                    .addHeader("Referer", referer)
                                    .perform().readString()
                            val matcher: Matcher = RECAPTCHA_RESULT_PATTERN.matcher(responseText)
                            if (matcher.find()) {
                                return@ForegroundSolver matcher.group(1)
                            }
                            response = parseResponse2(responseText)
                            continue
                        }
                        throw CancelException()
                    } else {
                        throw chan.http.HttpException(ErrorItem.Type.INVALID_RESPONSE, false, false)
                    }
                }
            }
            return ChallengeExtra(solver, null, null)
        }
    }

    @Throws(CancelException::class, HttpException::class)
    fun getChallengeHcaptcha(
        initialHolder: HttpHolder, apiKey: String, referer: String?,
        solveInBackground: Boolean, solveAutomatically: Boolean
    ): ChallengeExtra? {
        val refererFinal = if (referer != null) referer else "https://www.hcaptcha.com/"
        if (solveAutomatically) {
            val autoResponse = CaptchaSolving.getInstance().solveCaptcha(
                initialHolder,
                CaptchaSolving.CaptchaType.HCAPTCHA, apiKey, refererFinal
            )
            if (autoResponse != null) {
                return ChallengeExtra(null, autoResponse, null)
            }
        }
        val solver = ForegroundSolver { holder: HttpHolder?, challengeExtra: ChallengeExtra? ->
            synchronized(accessLock) {
                val response = ForegroundManager.getInstance()
                    .requireUserRecaptchaV2(refererFinal, apiKey, false, true, challengeExtra)
                if (response == null) {
                    throw CancelException()
                }
                return@ForegroundSolver response
            }
        }
        synchronized(accessLock) {
            if (solveInBackground) {
                return BackgroundSolver(solver, refererFinal, apiKey, false, true).await()
            } else {
                return ChallengeExtra(solver, null, null)
            }
        }
    }

    @Throws(HttpException::class)
    private fun getImage2(
        holder: HttpHolder?, apiKey: String?, challenge: String?, id: String?,
        transformBlackAndWhite: Boolean
    ): Pair<Bitmap?, Boolean?> {
        var transformBlackAndWhite = transformBlackAndWhite
        val chan = getFallback()
        val uri = chan.locator.buildQueryWithHost(
            "www.google.com", "recaptcha/api2/payload",
            "c", challenge, "k", apiKey, "id", StringUtils.emptyIfNull(id)
        )
        val image: Bitmap? = chan.http.HttpRequest(uri, holder).perform().readBitmap()
        if (transformBlackAndWhite) {
            transformBlackAndWhite = isBlackAndWhiteCaptchaImage(image)
        }
        return if (transformBlackAndWhite) handleBlackAndWhiteCaptchaImage(image) else Pair<Bitmap?, Boolean?>(
            image,
            false
        )
    }

    class CancelException : Exception()

    private class WebViewHolder {
        class Arguments(
            val referer: String?,
            val apiKey: String,
            val invisible: Boolean,
            val hcaptcha: Boolean
        )

        fun interface ArgumentsProvider {
            fun create(): Arguments
        }

        interface Callback {
            fun onLoad()
            fun onCancel()
            fun onResponse(response: String?)
            fun onError(exception: HttpException?)
        }

        internal var webView: ScaledWebView? = null

        private var scale = 1f
        private var extraScale = 1f
        var lastWidthUnscaled: Int = 0
        var lastHeightUnscaled: Int = 0

        internal var callback: Callback? = null
        internal var loaded = false
        private var cancel = false
        private var response: String? = null
        private var exception: HttpException? = null

        fun destroy() {
            if (webView != null) {
                webView!!.destroy()
                webView = null
            }
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        fun obtainWebView(
            context: Context,
            newParent: ViewGroup?, index: Int, argumentsProvider: ArgumentsProvider
        ): WebView? {
            var context = context
            context = context.getApplicationContext()
            val widthUnscaled = 300
            val maxHeightUnscaled = 580
            val minHeightUnscaled = 250
            val configuration = context.getResources().getConfiguration()
            val density = obtainDensity(context)
            val dialogPaddingDp = 16
            val screenWidthDp = configuration.screenWidthDp - 2 * dialogPaddingDp
            val screenHeightDp = configuration.screenHeightDp - 2 * dialogPaddingDp
            var minScaleMultiplier = Float.MAX_VALUE
            for (size in Arrays.asList<Int?>(screenWidthDp, screenHeightDp)) {
                for (max in Arrays.asList<Int?>(widthUnscaled, maxHeightUnscaled)) {
                    minScaleMultiplier = min(minScaleMultiplier, size / max)
                }
            }
            val scaleMultiplier = min(
                screenWidthDp.toFloat() / widthUnscaled,
                screenHeightDp.toFloat() / maxHeightUnscaled
            )
            val minScale = density * minScaleMultiplier
            val scale = density * scaleMultiplier

            var load = false
            if (webView == null) {
                load = true
                webView = ScaledWebView(context, minScale, EXTRA_SCALE_FOR_SYSTEM_PADDING)
                webView!!.getSettings().setJavaScriptEnabled(true)
                webView!!.getSettings().setBuiltInZoomControls(false)
                webView!!.setHorizontalScrollBarEnabled(false)
                webView!!.setVerticalScrollBarEnabled(false)
                webView!!.addJavascriptInterface(javascriptInterface, "jsi")
                webView!!.setWebViewClient(client)
                webView!!.setWebChromeClient(object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            "RecaptchaReader",
                            "Console message: " + consoleMessage.lineNumber() + " " +
                                    consoleMessage.sourceId() + " " + consoleMessage.message()
                        )
                        return super.onConsoleMessage(consoleMessage)
                    }
                })
            }
            this.scale = scale
            webView!!.setScale(this.totalScale)
            if (webView!!.getParent() != null) {
                (webView!!.getParent() as ViewGroup).removeView(webView)
            }

            val defaultWidth = ((if (lastWidthUnscaled > 0)
                lastWidthUnscaled
            else
                widthUnscaled) * this.totalScale).toInt()
            val defaultHeight = ((if (lastHeightUnscaled > 0)
                lastHeightUnscaled
            else
                minHeightUnscaled) * this.totalScale).toInt()
            webView!!.setLayoutParams(FrameLayout.LayoutParams(defaultWidth, defaultHeight))
            if (newParent == null) {
                Companion.layout(webView!!)
            } else {
                newParent.addView(webView, index)
            }

            if (load) {
                val arguments = argumentsProvider.create()
                val data = readRawResourceString(webView!!.getResources(), R.raw.web_recaptcha_v2)
                    .replace("__REPLACE_API_KEY__", arguments.apiKey)
                    .replace("__REPLACE_INVISIBLE__", if (arguments.invisible) "true" else "false")
                    .replace("__REPLACE_HCAPTCHA__", if (arguments.hcaptcha) "true" else "false")
                webView!!.loadDataWithBaseURL(arguments.referer, data, "text/html", "UTF-8", null)
            }
            return webView
        }

        val totalScale: Float
            get() = scale * extraScale

        fun setCallback(callback: Callback?) {
            this.callback = callback
            if (callback != null) {
                val cancel = this.cancel
                val response = this.response
                val exception = this.exception
                this.cancel = false
                this.response = null
                this.exception = null
                if (cancel) {
                    callback.onCancel()
                } else if (response != null) {
                    callback.onResponse(response)
                } else if (exception != null) {
                    callback.onError(exception)
                }
            }
        }

        @Suppress("unused")
        private val javascriptInterface: Any = object : Any() {
            @JavascriptInterface
            fun onResponse(response: String?) {
                ConcurrentUtils.HANDLER.post(Runnable {
                    if (webView != null) {
                        val exception = if (!isEmpty(response)) null else HttpException(
                            ErrorItem.Type.INVALID_RESPONSE,
                            false,
                            false
                        )
                        if (callback != null) {
                            if (exception != null) {
                                callback!!.onError(exception)
                            } else {
                                callback!!.onResponse(response)
                            }
                        } else {
                            this@WebViewHolder.response = nullIfEmpty(response)
                            if (exception != null) {
                                this@WebViewHolder.exception = exception
                            }
                        }
                    }
                })
            }

            @JavascriptInterface
            fun onError() {
                ConcurrentUtils.HANDLER.post(Runnable {
                    if (webView != null) {
                        val exception = HttpException(ErrorItem.Type.UNKNOWN, false, false)
                        if (callback != null) {
                            callback!!.onError(exception)
                        } else {
                            this@WebViewHolder.exception = exception
                        }
                    }
                })
            }

            @JavascriptInterface
            fun onSizeChanged(width: Int, height: Int) {
                ConcurrentUtils.HANDLER.post(Runnable {
                    if (webView != null) {
                        val hasContent = width > 0 && height > 0
                        if (hasContent) {
                            val wasLoaded = loaded
                            loaded = true
                            if (!wasLoaded && callback != null) {
                                callback!!.onLoad()
                            }
                            lastWidthUnscaled = width
                            lastHeightUnscaled = height
                            extraScale = min(1f, 300f / width)
                            val newWidth = (this.totalScale * width).toInt()
                            val newHeight = (this.totalScale * height).toInt()
                            webView!!.setScale(this.totalScale)
                            val layoutParams = webView!!.getLayoutParams()
                            if (layoutParams.width != newWidth || layoutParams.height != newHeight) {
                                layoutParams.width = newWidth
                                layoutParams.height = newHeight
                                if (webView!!.getParent() != null) {
                                    webView!!.requestLayout()
                                } else {
                                    Companion.layout(webView!!)
                                }
                            }
                        } else if ((lastWidthUnscaled > 0 && lastHeightUnscaled > 0) && !hasContent) {
                            if (callback != null) {
                                callback!!.onCancel()
                            } else {
                                cancel = true
                            }
                        }
                    }
                })
            }
        }

        private val client: WebViewClient = object : WebViewClient() {
            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                if (webView != null) {
                    webView!!.notifyClientScaleChanged(newScale)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if ("favicon.ico" == request.getUrl().getLastPathSegment()) {
                    return WebResourceResponse("text/plain", "ISO-8859-1", null)
                } else {
                    return super.shouldInterceptRequest(view, request)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                val uri = request.getUrl()
                if ("google.com" == uri.getHost() || "www.google.com" == uri.getHost()) {
                    ConcurrentUtils.HANDLER.post(Runnable {
                        if (webView != null) {
                            val exception = HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                            if (callback != null) {
                                callback!!.onError(exception)
                            } else {
                                this@WebViewHolder.exception = exception
                            }
                        }
                    })
                }
            }
        }

        companion object {
            private fun layout(webView: WebView) {
                val layoutParams = webView.getLayoutParams()
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(layoutParams.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(layoutParams.height, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, webView.getMeasuredWidth(), webView.getMeasuredHeight())
            }
        }
    }

    private class BackgroundSolver(
        private val solver: ForegroundSolver,
        referer: String?, apiKey: String, invisible: Boolean, hcaptcha: Boolean
    ) : WebViewHolder.Callback {
        internal val holder: WebViewHolder?

        private var challengeExtra: ChallengeExtra? = null
        private var error = false
        private var cancel = false

        init {
            holder = mainGet<WebViewHolder?>(Callable {
                val holder = WebViewHolder()
                holder.obtainWebView(
                    MainApplication.getInstance(),
                    null,
                    0,
                    ArgumentsProvider {
                        WebViewHolder.Arguments(
                            referer,
                            apiKey,
                            invisible,
                            hcaptcha
                        )
                    })
                holder.callback = this@BackgroundSolver
                holder
            })
        }

        override fun onLoad() {
            synchronized(this) {
                challengeExtra = ChallengeExtra(solver, null, holder)
                (this as Object).notifyAll()
            }
        }

        override fun onCancel() {
            synchronized(this) {
                cancel = true
                (this as Object).notifyAll()
            }
        }

        override fun onResponse(response: String?) {
            synchronized(this) {
                holder!!.destroy()
                challengeExtra = ChallengeExtra(solver, response, null)
                (this as Object).notifyAll()
            }
        }

        override fun onError(exception: HttpException?) {
            synchronized(this) {
                error = true
                (this as Object).notifyAll()
            }
        }

        @Throws(CancelException::class, HttpException::class)
        fun await(): ChallengeExtra {
            synchronized(this) {
                while (challengeExtra == null && !error && !cancel) {
                    try {
                        (this as Object).wait()
                    } catch (e: InterruptedException) {
                        ConcurrentUtils.HANDLER.post(Runnable { holder!!.destroy() })
                        Thread.currentThread().interrupt()
                        throw CancelException()
                    }
                }
                if (error) {
                    throw HttpException(ErrorItem.Type.UNKNOWN, false, false)
                }
                if (cancel) {
                    throw CancelException()
                }
                return challengeExtra!!
            }
        }
    }

    class WebViewViewModel : ViewModel() {
        internal var holder: WebViewHolder? = null
        internal var clicked = false
        internal var destroyCallback: Runnable? = null
        internal var published = false

        fun initHolder(holder: WebViewHolder?) {
            this.holder = if (holder != null) holder else WebViewHolder()
        }

        override fun onCleared() {
            holder!!.destroy()
            if (destroyCallback != null) {
                destroyCallback!!.run()
            }
        }
    }

    abstract class V2Dialog : DialogFragment {
        constructor()

        constructor(
            referer: String?, apiKey: String?, invisible: Boolean, hcaptcha: Boolean,
            challengeExtra: ChallengeExtra?
        ) {
            val args = Bundle()
            args.putString(EXTRA_REFERER, referer)
            args.putString(EXTRA_API_KEY, apiKey)
            args.putBoolean(EXTRA_INVISIBLE, invisible)
            args.putBoolean(EXTRA_HCAPTCHA, hcaptcha)
            setArguments(args)
            this.challengeExtra = challengeExtra
        }

        internal var webView: WebViewViewModel? = null
        private var challengeExtra: ChallengeExtra? = null

        private var started = false
        private var shown = false

        internal val callback: WebViewHolder.Callback = object : WebViewHolder.Callback {
            override fun onLoad() {
                showDialog()
                performClick()
            }

            override fun onCancel() {
                dismiss()
                publishResponseInternal(null, null)
            }

            override fun onResponse(response: String?) {
                dismiss()
                publishResponseInternal(response, null)
            }

            override fun onError(exception: HttpException?) {
                dismiss()
                publishResponseInternal(null, exception)
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            webView = ViewModelProvider(this).get<WebViewViewModel?>(WebViewViewModel::class.java)
            if (webView!!.holder == null) {
                webView!!.initHolder(challengeExtra!!.holder)
                if (challengeExtra != null) {
                    challengeExtra.holder = null
                    challengeExtra = null
                }
            }

            val dialog = Dialog(requireActivity())
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val layout = FrameLayout(dialog.getContext())
            dialog.setContentView(
                layout, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            if (webView!!.holder!!.loaded) {
                showDialog()
                performClick()
            }

            webView!!.destroyCallback = Runnable? { this.publishDestroyInternal() }
            webView!!.holder!!.obtainWebView(
                requireContext().getApplicationContext(),
                layout,
                0,
                ArgumentsProvider {
                    WebViewHolder.Arguments(
                        requireArguments().getString(EXTRA_REFERER),
                        requireArguments().getString(
                            EXTRA_API_KEY
                        )!!,
                        requireArguments().getBoolean(EXTRA_INVISIBLE),
                        requireArguments().getBoolean(
                            EXTRA_HCAPTCHA
                        )
                    )
                })
            return dialog
        }

        override fun onStart() {
            super.onStart()

            started = true
            val dialog = getDialog()
            if (dialog != null && !shown) {
                dialog.hide()
            }
        }

        override fun onResume() {
            super.onResume()

            if (webView != null) {
                webView.holder!!.setCallback(callback)
                if (!shown && webView.holder.loaded) {
                    showDialog()
                    performClick()
                }
            }
        }

        override fun onPause() {
            super.onPause()

            if (webView != null) {
                webView.holder!!.setCallback(null)
            }
        }

        override fun onStop() {
            super.onStop()
            started = false
        }

        private fun showDialog() {
            if (!shown) {
                shown = true
                if (started) {
                    getDialog()!!.show()
                }
            }
        }

        private fun performClick() {
            if (!webView!!.clicked) {
                webView!!.clicked = true
                if (!requireArguments().getBoolean(EXTRA_INVISIBLE)) {
                    ConcurrentUtils.HANDLER.postDelayed(Runnable {
                        if (webView != null) {
                            val x =
                                (webView.holder!!.totalScale * (Math.random() * 100 + 10)).toInt()
                            val y =
                                (webView.holder!!.totalScale * (Math.random() * 30 + 20)).toInt()
                            var motionEvent: MotionEvent
                            motionEvent = MotionEvent.obtain(
                                0, SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
                            )
                            webView.holder.webView!!.onTouchEvent(motionEvent)
                            motionEvent.recycle()
                            motionEvent = MotionEvent.obtain(
                                0, SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
                            )
                            webView.holder.webView!!.onTouchEvent(motionEvent)
                            motionEvent.recycle()
                        }
                    }, 500)
                }
            }
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            publishResponseInternal(null, null)
        }

        private fun publishResponseInternal(response: String?, exception: HttpException?) {
            if (webView != null && !webView.published) {
                webView.published = true
                webView.holder!!.setCallback(null)
                webView = null
                publishResult(response, exception)
            }
        }

        private fun publishDestroyInternal() {
            if (webView != null && !webView.published) {
                webView.published = true
                publishResult(null, null)
            }
        }

        abstract fun publishResult(response: String?, exception: HttpException?)

        companion object {
            private const val EXTRA_REFERER = "referer"
            private const val EXTRA_API_KEY = "apiKey"
            private const val EXTRA_INVISIBLE = "invisible"
            private const val EXTRA_HCAPTCHA = "hcaptcha"
        }
    }

    companion object {
        private const val EXTRA_SCALE_FOR_SYSTEM_PADDING = 0.8f

        private val INSTANCE = RecaptchaReader()

        @JvmStatic
        fun getInstance(): RecaptchaReader = INSTANCE

        private val RECAPTCHA_FALLBACK_PATTERN: Pattern = Pattern.compile(
            "(?:(?:<div " +
                    "class=\"(?:rc-imageselect-desc(?:-no-canonical)?|fbc-imageselect-message-error)\">)(.*?)" +
                    "</div>.*?)?value=\"(.{20,}?)\""
        )
        private val RECAPTCHA_RESULT_PATTERN: Pattern =
            Pattern.compile("<textarea.*?>(.*?)</textarea>")

        private fun parseResponse2(responseText: String): Pair<String?, String?>? {
            val matcher: Matcher = RECAPTCHA_FALLBACK_PATTERN.matcher(responseText)
            if (matcher.find()) {
                val imageSelectorDescription = matcher.group(1)
                val challenge = matcher.group(2)
                return Pair<String?, String?>(imageSelectorDescription, challenge)
            }
            return null
        }

        private fun splitImages(image: Bitmap, sizeX: Int, sizeY: Int): Array<Bitmap?> {
            val images = arrayOfNulls<Bitmap>(sizeX * sizeY)
            val width = image.getWidth() / sizeX
            val height = image.getHeight() / sizeY
            for (y in 0..<sizeY) {
                for (x in 0..<sizeX) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawBitmap(
                        image,
                        (-x * width).toFloat(),
                        (-y * height).toFloat(),
                        null
                    )
                    images[y * sizeX + x] = bitmap
                }
            }
            return images
        }
    }
}
