package com.mishiranu.dashchan.content

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.util.ConcurrentUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * An off-screen, zero-size [WebView] used purely as a JavaScript runtime. It never appears on
 * screen and is never attached to the visible view hierarchy; it exists only to evaluate scripts
 * and to let those scripts reach the network without the same-origin policy getting in the way.
 *
 * The engine hosts its blank document on a `file://` origin and turns on universal file access, so
 * `fetch` / `XMLHttpRequest` from a script can talk to any host cross-origin — that is the "CORS
 * disabled" wiring the powerful features rely on. Because of that reach, only run scripts you are
 * willing to grant unrestricted network access.
 *
 * Everything runs on the main thread. The public methods may be called from any thread; calls are
 * marshalled onto the main looper and result callbacks are delivered there. [evaluate] calls made
 * before the blank document finishes loading are queued and flushed, in order, once it is ready.
 *
 * Create one, keep it for as long as you need the runtime alive, and [destroy] it when done.
 *
 * @param bridges objects exposed to scripts via `@JavascriptInterface`, keyed by the global name
 *   they are bound to (`window.<name>`). Only methods annotated `@JavascriptInterface` are visible.
 */
class HeadlessJsEngine
    @JvmOverloads
    constructor(
        bridges: Map<String, Any> = emptyMap(),
        context: Context = MainApplication.getInstance(),
    ) {
        private val appContext = context.applicationContext
        private val bridges = LinkedHashMap(bridges)

        // Main-thread state. Guard everything through ConcurrentUtils.HANDLER.
        private var webView: WebView? = null
        private var ready = false
        private var destroyed = false
        private val pending = ArrayDeque<Runnable>()

        init {
            onMain { create() }
        }

        /**
         * Evaluates [script] in the runtime. [callback], if given, receives the result as returned by
         * [WebView.evaluateJavascript] — a JSON-encoded string (e.g. a returned string arrives quoted),
         * or `null` if evaluation produced no value or the engine was destroyed first. The callback is
         * invoked on the main thread. Safe to call before the document is ready; the call is queued.
         */
        @JvmOverloads
        fun evaluate(
            script: String,
            callback: ((String?) -> Unit)? = null,
        ) {
            onMain {
                if (destroyed) {
                    callback?.invoke(null)
                } else if (ready) {
                    run(script, callback)
                } else {
                    pending.addLast(Runnable { run(script, callback) })
                }
            }
        }

        /**
         * Evaluates [script] and blocks the calling thread until the result is available or [timeoutMs]
         * elapses. Returns the raw [WebView.evaluateJavascript] result, or `null` on timeout / destroyed
         * engine. Must not be called from the main thread — it would deadlock the looper that produces
         * the result.
         */
        @JvmOverloads
        fun evaluateBlocking(
            script: String,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): String? {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "evaluateBlocking must not be called on the main thread"
            }
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            evaluate(script) {
                result.set(it)
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result.get()
        }

        /** Tears down the runtime and releases the [WebView]. Idempotent; further [evaluate] calls no-op. */
        fun destroy() {
            onMain {
                if (!destroyed) {
                    destroyed = true
                    ready = false
                    pending.clear()
                    webView?.destroy()
                    webView = null
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        @Suppress("DEPRECATION")
        private fun create() {
            if (destroyed) {
                return
            }
            val webView = WebView(appContext)
            this.webView = webView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                // Host the document on a file:// origin and let it reach any origin: this is what
                // "disables CORS" for scripts running in the engine. Both setters are deprecated but
                // remain the only way to lift the same-origin policy for a WebView document.
                allowUniversalAccessFromFileURLs = true
                allowFileAccessFromFileURLs = true
            }
            for ((name, bridge) in bridges) {
                webView.addJavascriptInterface(bridge, name)
            }
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        if (view === this@HeadlessJsEngine.webView && !ready) {
                            ready = true
                            while (pending.isNotEmpty()) {
                                pending.removeFirst().run()
                            }
                        }
                    }
                }
            if (BuildConfig.DEBUG) {
                webView.webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(
                                LOG_TAG,
                                consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() +
                                    " " + consoleMessage.message(),
                            )
                            return true
                        }
                    }
            }
            // Keep the view off-screen and effectively invisible: a 1x1 layout that is never attached
            // to a window. The document still loads and scripts (including async fetch) still run.
            webView.layoutParams = ViewGroup.LayoutParams(1, 1)
            val measureSpec = View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
            webView.measure(measureSpec, measureSpec)
            webView.layout(0, 0, 1, 1)
            webView.loadDataWithBaseURL(BASE_URL, INITIAL_HTML, "text/html", "UTF-8", null)
        }

        private fun run(
            script: String,
            callback: ((String?) -> Unit)?,
        ) {
            val webView = this.webView
            if (webView == null) {
                callback?.invoke(null)
            } else {
                webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
            }
        }

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                ConcurrentUtils.HANDLER.post(action)
            }
        }

        companion object {
            private const val LOG_TAG = "HeadlessJsEngine"
            private const val DEFAULT_TIMEOUT_MS = 30000L

            // A file:// origin is what makes universal cross-origin access take effect. The path need
            // not exist; loadDataWithBaseURL only uses it to set the document origin.
            private const val BASE_URL = "file:///android_asset/headless-js-engine.html"
            private const val INITIAL_HTML = "<!DOCTYPE html><html><head></head><body></body></html>"
        }
    }
