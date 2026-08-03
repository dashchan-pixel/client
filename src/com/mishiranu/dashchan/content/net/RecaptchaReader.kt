package com.mishiranu.dashchan.content.net

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
import chan.util.StringUtils.isEmpty
import chan.util.StringUtils.nullIfEmpty
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.RecaptchaReader.ChallengeExtra.ForegroundSolver
import com.mishiranu.dashchan.content.net.RecaptchaReader.WebViewHolder.ArgumentsProvider
import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ConcurrentUtils.mainGet
import com.mishiranu.dashchan.util.IOUtils.readRawResourceString
import com.mishiranu.dashchan.util.ResourceUtils.obtainDensity
import com.mishiranu.dashchan.widget.ScaledWebView
import java.util.Arrays
import java.util.concurrent.Callable
import kotlin.math.min

class RecaptchaReader private constructor() {
    private val accessLock = Any()

    class ChallengeExtra internal constructor(
        private val solver: ForegroundSolver?,
        @JvmField val response: String?,
        internal var holder: WebViewHolder?,
    ) {
        internal fun interface ForegroundSolver {
            @Throws(CancelException::class, HttpException::class, InterruptedException::class)
            fun solve(
                holder: HttpHolder,
                challengeExtra: ChallengeExtra?,
            ): String?
        }

        @Throws(CancelException::class, HttpException::class, InterruptedException::class)
        fun getResponse(holder: HttpHolder): String? {
            if (response != null) {
                return response
            } else {
                return solver!!.solve(holder, this)
            }
        }

        fun cleanup() {
            if (holder != null) {
                ConcurrentUtils.HANDLER.post(
                    Runnable {
                        holder?.destroy()
                        holder = null
                    },
                )
            }
        }
    }

    @Throws(CancelException::class, HttpException::class)
    fun getChallenge2(
        initialHolder: HttpHolder,
        apiKey: String,
        invisible: Boolean,
        referer: String?,
        solveInBackground: Boolean,
        solveAutomatically: Boolean,
    ): ChallengeExtra? {
        val refererFinal = if (referer != null) referer else "https://www.google.com/"
        if (solveAutomatically) {
            val autoResponse =
                CaptchaSolving.getInstance().solveCaptcha(
                    initialHolder,
                    if (invisible) CaptchaSolving.CaptchaType.RECAPTCHA_2_INVISIBLE else CaptchaSolving.CaptchaType.RECAPTCHA_2,
                    apiKey,
                    refererFinal,
                )
            if (autoResponse != null) {
                return ChallengeExtra(null, autoResponse, null)
            }
        }
        val solver =
            ForegroundSolver { newHolder: HttpHolder, challengeExtra: ChallengeExtra? ->
                synchronized(accessLock) {
                    val response =
                        ForegroundManager
                            .getInstance()
                            .requireUserRecaptchaV2(
                                refererFinal,
                                apiKey,
                                invisible,
                                false,
                                challengeExtra,
                            )
                    if (response == null) {
                        throw CancelException()
                    }
                    return@ForegroundSolver response
                }
            }
        synchronized(accessLock) {
            if (solveInBackground) {
                return BackgroundSolver(solver, refererFinal, apiKey, invisible, false, null).await()
            } else {
                return ChallengeExtra(solver, null, null)
            }
        }
    }

    @Throws(CancelException::class, HttpException::class)
    fun getChallengeHcaptcha(
        initialHolder: HttpHolder,
        apiKey: String,
        referer: String?,
        solveInBackground: Boolean,
        solveAutomatically: Boolean,
    ): ChallengeExtra? {
        val refererFinal = if (referer != null) referer else "https://www.hcaptcha.com/"
        if (solveAutomatically) {
            val autoResponse =
                CaptchaSolving.getInstance().solveCaptcha(
                    initialHolder,
                    CaptchaSolving.CaptchaType.HCAPTCHA,
                    apiKey,
                    refererFinal,
                )
            if (autoResponse != null) {
                return ChallengeExtra(null, autoResponse, null)
            }
        }
        val solver =
            ForegroundSolver { holder: HttpHolder?, challengeExtra: ChallengeExtra? ->
                synchronized(accessLock) {
                    val response =
                        ForegroundManager
                            .getInstance()
                            .requireUserRecaptchaV2(refererFinal, apiKey, false, true, challengeExtra)
                    if (response == null) {
                        throw CancelException()
                    }
                    return@ForegroundSolver response
                }
            }
        synchronized(accessLock) {
            if (solveInBackground) {
                return BackgroundSolver(solver, refererFinal, apiKey, false, true, null).await()
            } else {
                return ChallengeExtra(solver, null, null)
            }
        }
    }

    /**
     * reCAPTCHA v3 asks the user nothing: the script scores the request behind the page and
     * answers with a token or an error. There is therefore no challenge to hand back and no
     * dialog to fall back on — the token is minted here or not at all.
     *
     * [referer] is the page the challenge is run inside, and is required: the score is a judgement
     * of that page, and no page of ours can stand in for it.
     */
    @Throws(CancelException::class, HttpException::class)
    fun getChallenge3(
        initialHolder: HttpHolder,
        apiKey: String,
        action: String,
        referer: String?,
        solveAutomatically: Boolean,
    ): ChallengeExtra? {
        val refererFinal = referer ?: throw HttpException(ErrorItem.Type.INVALID_RESPONSE, false, false)
        if (solveAutomatically) {
            val autoResponse =
                CaptchaSolving.getInstance().solveCaptcha(
                    initialHolder,
                    CaptchaSolving.CaptchaType.RECAPTCHA_3,
                    apiKey,
                    refererFinal,
                    action,
                )
            if (autoResponse != null) {
                return ChallengeExtra(null, autoResponse, null)
            }
        }
        // Nothing the user could do would produce a v3 token, so a solver that is asked for one
        // can only report that there is none.
        val solver = ForegroundSolver { _: HttpHolder, _: ChallengeExtra? -> throw CancelException() }
        synchronized(accessLock) {
            return BackgroundSolver(solver, refererFinal, apiKey, false, false, action).await()
        }
    }

    class CancelException : Exception()

    internal class WebViewHolder {
        class Arguments(
            val referer: String?,
            val apiKey: String,
            val invisible: Boolean,
            val hcaptcha: Boolean,
            /**
             * Non-null selects reCAPTCHA v3, which is a different script and a different page
             * from the widget the other two share. Empty means v3 without a named action.
             */
            val action: String?,
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

        /**
         * Set for reCAPTCHA 3, which has no page of its own: the challenge is run by injecting
         * this into the site's page once that has loaded. [injectHost] is the only host the page
         * is allowed to navigate to, so the site may redirect to itself and nothing else.
         */
        private var injectScript: String? = null
        private var injectHost: String? = null
        private var cancel = false
        private var response: String? = null
        private var exception: HttpException? = null

        fun destroy() {
            webView?.destroy()
            webView = null
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        fun obtainWebView(
            context: Context,
            newParent: ViewGroup?,
            index: Int,
            argumentsProvider: ArgumentsProvider,
        ): WebView? {
            val appContext = context.getApplicationContext()
            val widthUnscaled = 300
            val maxHeightUnscaled = 580
            val minHeightUnscaled = 250
            val configuration = appContext.getResources().getConfiguration()
            val density = obtainDensity(appContext)
            val dialogPaddingDp = 16
            val screenWidthDp = configuration.screenWidthDp - 2 * dialogPaddingDp
            val screenHeightDp = configuration.screenHeightDp - 2 * dialogPaddingDp
            var minScaleMultiplier = Float.MAX_VALUE
            for (size in Arrays.asList(screenWidthDp, screenHeightDp)) {
                for (max in Arrays.asList(widthUnscaled, maxHeightUnscaled)) {
                    minScaleMultiplier = min(minScaleMultiplier, size.toFloat() / max)
                }
            }
            val scaleMultiplier =
                min(
                    screenWidthDp.toFloat() / widthUnscaled,
                    screenHeightDp.toFloat() / maxHeightUnscaled,
                )
            val minScale = density * minScaleMultiplier
            val scale = density * scaleMultiplier

            var load = false
            var webView = this.webView
            if (webView == null) {
                load = true
                webView = ScaledWebView(appContext, minScale, EXTRA_SCALE_FOR_SYSTEM_PADDING)
                this.webView = webView
                webView.getSettings().setJavaScriptEnabled(true)
                webView.getSettings().setBuiltInZoomControls(false)
                // reCAPTCHA keeps what it knows about a client in local storage, under the
                // `_grecaptcha` key, and in cookies on its own domain — which are third party to
                // whatever page it runs inside. A WebView allows neither by default, so without
                // this every solve presents a client that has never been seen before. Version 2
                // only weighs that; version 3, which has nothing else to go on, scores it at the
                // floor and the site refuses the token as if it were invalid.
                webView.getSettings().setDomStorageEnabled(true)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.setHorizontalScrollBarEnabled(false)
                webView.setVerticalScrollBarEnabled(false)
                webView.addJavascriptInterface(javascriptInterface, "jsi")
                webView.setWebViewClient(client)
                webView.setWebChromeClient(
                    object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(
                                "RecaptchaReader",
                                "Console message: " + consoleMessage.lineNumber() + " " +
                                    consoleMessage.sourceId() + " " + consoleMessage.message(),
                            )
                            return super.onConsoleMessage(consoleMessage)
                        }
                    },
                )
            }
            this.scale = scale
            webView.setScale(this.totalScale)
            if (webView.getParent() != null) {
                (webView.getParent() as ViewGroup).removeView(webView)
            }

            val defaultWidth =
                (
                    (
                        if (lastWidthUnscaled > 0) {
                            lastWidthUnscaled
                        } else {
                            widthUnscaled
                        }
                    ) * this.totalScale
                ).toInt()
            val defaultHeight =
                (
                    (
                        if (lastHeightUnscaled > 0) {
                            lastHeightUnscaled
                        } else {
                            minHeightUnscaled
                        }
                    ) * this.totalScale
                ).toInt()
            webView.setLayoutParams(FrameLayout.LayoutParams(defaultWidth, defaultHeight))
            if (newParent == null) {
                Companion.layout(webView)
            } else {
                newParent.addView(webView, index)
            }

            if (load) {
                val arguments = argumentsProvider.create()
                val action = arguments.action
                if (action != null) {
                    // The default agent carries a `wv` token that no browser sends, which is one
                    // more thing marking the client as not one.
                    val settings = webView.getSettings()
                    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                    // Version 3 is not shown, it is scored, and what it scores is the page it runs
                    // in. So it is run in the site's own page, which is the one the site scores
                    // when it does this itself, instead of in a page assembled here that has none
                    // of its cookies, none of its history and nothing else to be judged on.
                    injectScript =
                        readRawResourceString(webView.getResources(), R.raw.web_recaptcha_v3)
                            .replace("__REPLACE_API_KEY__", arguments.apiKey)
                            .replace("__REPLACE_ACTION__", action)
                    injectHost = Uri.parse(arguments.referer).host
                    webView.loadUrl(arguments.referer.orEmpty())
                } else {
                    val data =
                        readRawResourceString(webView.getResources(), R.raw.web_recaptcha_v2)
                            .replace("__REPLACE_API_KEY__", arguments.apiKey)
                            .replace("__REPLACE_INVISIBLE__", if (arguments.invisible) "true" else "false")
                            .replace("__REPLACE_HCAPTCHA__", if (arguments.hcaptcha) "true" else "false")
                    // The base URL is what the key is registered against, so the page has to claim
                    // the site's own origin rather than the one the data belongs to.
                    webView.loadDataWithBaseURL(arguments.referer, data, "text/html", "UTF-8", null)
                }
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
        private val javascriptInterface: Any =
            object : Any() {
                @JavascriptInterface
                fun onResponse(response: String?) {
                    ConcurrentUtils.HANDLER.post(
                        Runnable {
                            // What this solve earned is only worth anything to the next one if it
                            // outlives the WebView, which is destroyed as soon as it answers.
                            CookieManager.getInstance().flush()
                            if (webView != null) {
                                val exception =
                                    if (!isEmpty(response)) {
                                        null
                                    } else {
                                        HttpException(
                                            ErrorItem.Type.INVALID_RESPONSE,
                                            false,
                                            false,
                                        )
                                    }
                                val callback = this@WebViewHolder.callback
                                if (callback != null) {
                                    if (exception != null) {
                                        callback.onError(exception)
                                    } else {
                                        callback.onResponse(response)
                                    }
                                } else {
                                    this@WebViewHolder.response = nullIfEmpty(response)
                                    if (exception != null) {
                                        this@WebViewHolder.exception = exception
                                    }
                                }
                            }
                        },
                    )
                }

                @JavascriptInterface
                fun onError() {
                    ConcurrentUtils.HANDLER.post(
                        Runnable {
                            if (webView != null) {
                                val exception = HttpException(ErrorItem.Type.UNKNOWN, false, false)
                                val callback = this@WebViewHolder.callback
                                if (callback != null) {
                                    callback.onError(exception)
                                } else {
                                    this@WebViewHolder.exception = exception
                                }
                            }
                        },
                    )
                }

                @JavascriptInterface
                fun onSizeChanged(
                    width: Int,
                    height: Int,
                ) {
                    ConcurrentUtils.HANDLER.post(
                        Runnable {
                            val webView = this@WebViewHolder.webView
                            if (webView != null) {
                                val callback = this@WebViewHolder.callback
                                val hasContent = width > 0 && height > 0
                                if (hasContent) {
                                    val wasLoaded = loaded
                                    loaded = true
                                    if (!wasLoaded && callback != null) {
                                        callback.onLoad()
                                    }
                                    lastWidthUnscaled = width
                                    lastHeightUnscaled = height
                                    extraScale = min(1f, 300f / width)
                                    val newWidth = (this@WebViewHolder.totalScale * width).toInt()
                                    val newHeight = (this@WebViewHolder.totalScale * height).toInt()
                                    webView.setScale(this@WebViewHolder.totalScale)
                                    val layoutParams = webView.getLayoutParams()
                                    if (layoutParams.width != newWidth || layoutParams.height != newHeight) {
                                        layoutParams.width = newWidth
                                        layoutParams.height = newHeight
                                        if (webView.getParent() != null) {
                                            webView.requestLayout()
                                        } else {
                                            Companion.layout(webView)
                                        }
                                    }
                                } else if ((lastWidthUnscaled > 0 && lastHeightUnscaled > 0) && !hasContent) {
                                    if (callback != null) {
                                        callback.onCancel()
                                    } else {
                                        cancel = true
                                    }
                                }
                            }
                        },
                    )
                }
            }

        private val client: WebViewClient =
            object : WebViewClient() {
                override fun onScaleChanged(
                    view: WebView?,
                    oldScale: Float,
                    newScale: Float,
                ) {
                    webView?.notifyClientScaleChanged(newScale)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // The version 3 challenge is the site's own page, which is therefore allowed
                    // to reach itself. Every other navigation stays blocked, as it always was.
                    val host = injectHost ?: return true
                    return !host.equals(request?.url?.host, ignoreCase = true)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    // Injected on every load rather than only the first: reaching the page can
                    // take a redirect or an interstitial, and only the page that ends up loaded
                    // carries the script the challenge needs. A run left over from an earlier one
                    // can do no harm, since the holder is torn down as soon as one answers.
                    injectScript?.let { view?.evaluateJavascript(it, null) }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
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
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)

                    val uri = request.getUrl()
                    if ("google.com" == uri.getHost() || "www.google.com" == uri.getHost()) {
                        ConcurrentUtils.HANDLER.post(
                            Runnable {
                                if (webView != null) {
                                    val exception = HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                                    val callback = this@WebViewHolder.callback
                                    if (callback != null) {
                                        callback.onError(exception)
                                    } else {
                                        this@WebViewHolder.exception = exception
                                    }
                                }
                            },
                        )
                    }
                }
            }

        companion object {
            private fun layout(webView: WebView) {
                val layoutParams = webView.getLayoutParams()
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(layoutParams.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(layoutParams.height, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, webView.getMeasuredWidth(), webView.getMeasuredHeight())
            }
        }
    }

    private class BackgroundSolver(
        private val solver: ForegroundSolver,
        referer: String?,
        apiKey: String,
        invisible: Boolean,
        hcaptcha: Boolean,
        action: String?,
    ) : WebViewHolder.Callback {
        internal val holder: WebViewHolder

        private var challengeExtra: ChallengeExtra? = null
        private var error = false
        private var cancel = false

        init {
            holder =
                mainGet<WebViewHolder>(
                    Callable {
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
                                    hcaptcha,
                                    action,
                                )
                            },
                        )
                        holder.callback = this@BackgroundSolver
                        holder
                    },
                )!!
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
                holder.destroy()
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
                        ConcurrentUtils.HANDLER.post(Runnable { holder.destroy() })
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

        internal fun initHolder(holder: WebViewHolder?) {
            this.holder = if (holder != null) holder else WebViewHolder()
        }

        override fun onCleared() {
            holder!!.destroy()
            destroyCallback?.run()
        }
    }

    abstract class V2Dialog : DialogFragment {
        constructor()

        constructor(
            referer: String?,
            apiKey: String?,
            invisible: Boolean,
            hcaptcha: Boolean,
            challengeExtra: ChallengeExtra?,
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

        internal val callback: WebViewHolder.Callback =
            object : WebViewHolder.Callback {
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
            val webView = ViewModelProvider(this).get(WebViewViewModel::class.java)
            this.webView = webView
            if (webView.holder == null) {
                // challengeExtra is null whenever the framework re-creates this dialog through
                // its no-arg constructor (e.g. after process death), which is exactly the case
                // initHolder() fabricates a holder for.
                webView.initHolder(challengeExtra?.holder)
                challengeExtra?.holder = null
                challengeExtra = null
            }

            val dialog = Dialog(requireActivity())
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val layout = FrameLayout(dialog.getContext())
            dialog.setContentView(
                layout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (webView.holder!!.loaded) {
                showDialog()
                performClick()
            }

            webView.destroyCallback = Runnable { this.publishDestroyInternal() }
            webView.holder!!.obtainWebView(
                requireContext().getApplicationContext(),
                layout,
                0,
                ArgumentsProvider {
                    WebViewHolder.Arguments(
                        requireArguments().getString(EXTRA_REFERER),
                        requireArguments().getString(
                            EXTRA_API_KEY,
                        )!!,
                        requireArguments().getBoolean(EXTRA_INVISIBLE),
                        requireArguments().getBoolean(
                            EXTRA_HCAPTCHA,
                        ),
                        // This dialog only ever shows a widget, which v3 does not have.
                        null,
                    )
                },
            )
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

            val webView = this.webView
            if (webView != null) {
                val holder = webView.holder!!
                holder.setCallback(callback)
                if (!shown && holder.loaded) {
                    showDialog()
                    performClick()
                }
            }
        }

        override fun onPause() {
            super.onPause()

            this.webView?.let { it.holder!!.setCallback(null) }
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
            val webView = this.webView!!
            if (!webView.clicked) {
                webView.clicked = true
                if (!requireArguments().getBoolean(EXTRA_INVISIBLE)) {
                    ConcurrentUtils.HANDLER.postDelayed(
                        Runnable {
                            val delayedWebView = this.webView
                            if (delayedWebView != null) {
                                val holder = delayedWebView.holder!!
                                val x = (holder.totalScale * (Math.random() * 100 + 10)).toInt()
                                val y = (holder.totalScale * (Math.random() * 30 + 20)).toInt()
                                var motionEvent: MotionEvent
                                motionEvent =
                                    MotionEvent.obtain(
                                        0,
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        x.toFloat(),
                                        y.toFloat(),
                                        0,
                                    )
                                holder.webView!!.onTouchEvent(motionEvent)
                                motionEvent.recycle()
                                motionEvent =
                                    MotionEvent.obtain(
                                        0,
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP,
                                        x.toFloat(),
                                        y.toFloat(),
                                        0,
                                    )
                                holder.webView!!.onTouchEvent(motionEvent)
                                motionEvent.recycle()
                            }
                        },
                        500,
                    )
                }
            }
        }

        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            publishResponseInternal(null, null)
        }

        private fun publishResponseInternal(
            response: String?,
            exception: HttpException?,
        ) {
            val webView = this.webView
            if (webView != null && !webView.published) {
                webView.published = true
                webView.holder!!.setCallback(null)
                this.webView = null
                publishResult(response, exception)
            }
        }

        private fun publishDestroyInternal() {
            val webView = this.webView
            if (webView != null && !webView.published) {
                webView.published = true
                publishResult(null, null)
            }
        }

        abstract fun publishResult(
            response: String?,
            exception: HttpException?,
        )

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
    }
}
