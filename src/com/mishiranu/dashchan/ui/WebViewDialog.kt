package com.mishiranu.dashchan.ui

import android.animation.ObjectAnimator
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.mishiranu.dashchan.R

abstract class WebViewDialog : DialogFragment() {
    @JvmField
    protected var webView: WebView? = null

    protected lateinit var titleTextView: TextView

    protected lateinit var pageLoadingProgressBar: ProgressBar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window!!.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.dialog_webview, container)
        webView = rootView.findViewById(R.id.dialog_webview_webview)
        titleTextView = rootView.findViewById(R.id.dialog_webview_title)
        pageLoadingProgressBar = rootView.findViewById(R.id.dialog_webview_progressbar)

        val webView = this.webView ?: return rootView
        webView.webChromeClient = WebChromeClientWrapper()

        val closeIcon = rootView.findViewById<View>(R.id.dialog_webview_icon_close)
        closeIcon.setOnClickListener { dismiss() }

        val refreshIcon = rootView.findViewById<View>(R.id.dialog_webview_icon_refresh)
        refreshIcon.setOnClickListener { webView.reload() }

        return rootView
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    protected fun setWebChromeClient(webChromeClient: WebChromeClient) {
        val webView = this.webView ?: return
        webView.webChromeClient = WebChromeClientWrapper(webChromeClient)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val webView = this.webView ?: return
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
    }

    private inner class WebChromeClientWrapper : WebChromeClient {
        private val delegate: WebChromeClient
        private val progressBarVisibilityAnimator =
            ObjectAnimator.ofFloat(pageLoadingProgressBar, "alpha", 1f)
        private var lastProgress = 0

        constructor() {
            delegate = WebChromeClient()
        }

        constructor(delegate: WebChromeClient) {
            this.delegate = delegate
        }

        override fun onProgressChanged(
            view: WebView,
            newProgress: Int,
        ) {
            delegate.onProgressChanged(view, newProgress)
            animateProgressBarVisibility(newProgress)
            pageLoadingProgressBar.setProgress(newProgress, true)

            lastProgress = newProgress
        }

        private fun animateProgressBarVisibility(newProgress: Int) {
            val animateHide = newProgress == 100 && lastProgress != 100
            val animateShow = lastProgress == 0 || (newProgress != 100 && lastProgress == 100)
            val animate = animateHide || animateShow
            if (animate) {
                if (progressBarVisibilityAnimator.isRunning) {
                    progressBarVisibilityAnimator.cancel()
                }
                val finalAlphaValue: Float
                val animationDurationMillis: Int
                if (animateHide) {
                    finalAlphaValue = 0f
                    animationDurationMillis = 200
                } else {
                    finalAlphaValue = 1f
                    animationDurationMillis = 250
                }
                progressBarVisibilityAnimator.setFloatValues(finalAlphaValue)
                progressBarVisibilityAnimator.duration = animationDurationMillis.toLong()
                progressBarVisibilityAnimator.start()
            }
        }

        override fun onReceivedTitle(
            view: WebView,
            title: String,
        ) {
            delegate.onReceivedTitle(view, title)
            titleTextView.text = title
        }

        override fun onReceivedIcon(
            view: WebView,
            icon: Bitmap,
        ) {
            delegate.onReceivedIcon(view, icon)
        }

        override fun onReceivedTouchIconUrl(
            view: WebView,
            url: String,
            precomposed: Boolean,
        ) {
            delegate.onReceivedTouchIconUrl(view, url, precomposed)
        }

        override fun onShowCustomView(
            view: View,
            callback: CustomViewCallback,
        ) {
            delegate.onShowCustomView(view, callback)
        }

        @Deprecated("Deprecated in Java")
        override fun onShowCustomView(
            view: View,
            requestedOrientation: Int,
            callback: CustomViewCallback,
        ) {
            @Suppress("DEPRECATION")
            delegate.onShowCustomView(view, requestedOrientation, callback)
        }

        override fun onHideCustomView() {
            delegate.onHideCustomView()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean = delegate.onCreateWindow(view, isDialog, isUserGesture, resultMsg)

        override fun onRequestFocus(view: WebView) {
            delegate.onRequestFocus(view)
        }

        override fun onCloseWindow(window: WebView) {
            delegate.onCloseWindow(window)
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean = delegate.onJsAlert(view, url, message, result)

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean = delegate.onJsConfirm(view, url, message, result)

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsPromptResult,
        ): Boolean = delegate.onJsPrompt(view, url, message, defaultValue, result)

        override fun onJsBeforeUnload(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean = delegate.onJsBeforeUnload(view, url, message, result)

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            delegate.onGeolocationPermissionsShowPrompt(origin, callback)
        }

        override fun onGeolocationPermissionsHidePrompt() {
            delegate.onGeolocationPermissionsHidePrompt()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            delegate.onPermissionRequest(request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            delegate.onPermissionRequestCanceled(request)
        }

        @Deprecated("Deprecated in Java")
        override fun onJsTimeout(): Boolean {
            @Suppress("DEPRECATION")
            return delegate.onJsTimeout()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = delegate.onConsoleMessage(consoleMessage)

        override fun getDefaultVideoPoster(): Bitmap? = delegate.defaultVideoPoster

        override fun getVideoLoadingProgressView(): View? = delegate.videoLoadingProgressView

        override fun getVisitedHistory(callback: ValueCallback<Array<String>>) {
            delegate.getVisitedHistory(callback)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = delegate.onShowFileChooser(webView, filePathCallback, fileChooserParams)
    }
}
