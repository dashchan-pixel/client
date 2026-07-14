package com.mishiranu.dashchan.content.net.firewall

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mishiranu.dashchan.ui.WebViewDialog
import com.mishiranu.dashchan.util.WebViewUtils

abstract class FirewallResolutionDialog<T> : WebViewDialog {
    private var request: FirewallResolutionDialogRequest<T>? = null
    private var firewallResolutionFinished = false

    constructor()

    constructor(request: FirewallResolutionDialogRequest<T>) {
        arguments = Bundle()
        this.request = request
    }

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel =
            ViewModelProvider(this).get(FirewallResolutionViewModel::class.java)
                as FirewallResolutionViewModel<T>
        if (request != null) {
            viewModel.request = request
        } else {
            request = viewModel.request
        }
        if (request == null) {
            Log.e("FirewallResolverDialog", "onCreate: request is null, dismissing")
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        WebViewUtils.clearAll(webView)
        setupWebViewSettings()
        webView!!.webViewClient = WebViewClientImpl()
        setWebChromeClient(WebChromeClientImpl())
        WebViewUtils.setProxy(requireActivity(), request!!.proxyData) { webView!!.loadUrl(request!!.url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings() {
        val webViewSettings = webView!!.settings
        webViewSettings.userAgentString = request!!.userAgent
        webViewSettings.javaScriptEnabled = true
        webViewSettings.domStorageEnabled = true
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val checkFirewallResolutionResultAfterDismiss =
            request != null && !requireActivity().isChangingConfigurations
        if (checkFirewallResolutionResultAfterDismiss) {
            WebViewUtils.setProxy(requireContext(), null, null)
            val client = request!!.client
            var firewallResolutionResult = client.getResult()
            if (firewallResolutionResult == null || !firewallResolutionFinished) {
                checkFirewallResolutionFinished(webView!!.url, webView!!.title)
                firewallResolutionResult = client.getResult()
            }
            onFirewallResolutionFinished(firewallResolutionResult)
        }
    }

    protected abstract fun onFirewallResolutionFinished(firewallResolutionResult: T?)

    private fun checkFirewallAndDismissDialogIfResolutionFinished(
        url: String?,
        title: String?,
    ) {
        if (!firewallResolutionFinished) {
            firewallResolutionFinished = checkFirewallResolutionFinished(url, title)
            if (firewallResolutionFinished) {
                webView!!.stopLoading()
                dismiss()
            }
        }
    }

    private fun checkFirewallResolutionFinished(
        url: String?,
        title: String?,
    ): Boolean {
        val cookies = FirewallUtils.parseCookies(CookieManager.getInstance().getCookie(url))
        val uri = Uri.parse(url)
        firewallResolutionFinished = request!!.client.onPageFinished(uri, cookies, title)
        return firewallResolutionFinished
    }

    class FirewallResolutionViewModel<T> : ViewModel() {
        var request: FirewallResolutionDialogRequest<T>? = null

        override fun onCleared() {
            super.onCleared()
            request = null
        }
    }

    private inner class WebViewClientImpl : WebViewClient() {
        override fun onPageCommitVisible(
            view: WebView,
            url: String,
        ) {
            checkFirewallAndDismissDialogIfResolutionFinished(url, view.title)
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            checkFirewallAndDismissDialogIfResolutionFinished(url, view.title)
        }
    }

    private inner class WebChromeClientImpl : WebChromeClient() {
        override fun onReceivedTitle(
            view: WebView,
            title: String,
        ) {
            checkFirewallAndDismissDialogIfResolutionFinished(view.url, title)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val message = consoleMessage.message()
            if (message != null && message.contains("SyntaxError")) {
                firewallResolutionFinished = true
                dismiss()
                return true
            }
            return false
        }
    }
}
