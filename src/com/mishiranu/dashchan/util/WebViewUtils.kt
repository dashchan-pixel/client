package com.mishiranu.dashchan.util

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import chan.http.HttpClient
import com.mishiranu.dashchan.content.MainApplication
import java.util.concurrent.Executor

object WebViewUtils {
    @JvmStatic
    fun clearCookie() {
        CookieManager.getInstance().removeAllCookies(null)
    }

    @JvmStatic
    fun clearAll(webView: WebView?) {
        clearCookie()
        webView?.clearCache(true)
        val webViewDatabase = WebViewDatabase.getInstance(MainApplication.getInstance())
        webViewDatabase.clearHttpAuthUsernamePassword()
        WebStorage.getInstance().deleteAllData()
    }

    private val EXECUTOR = Executor { it.run() }

    @JvmStatic
    fun setProxy(
        context: Context,
        proxyData: HttpClient.ProxyData?,
        callback: Runnable?,
    ) {
        val nonNullCallback = callback ?: Runnable {}
        if (proxyData != null) {
            val uriString =
                (if (proxyData.socks) "socks" else "http") + "://" +
                    proxyData.host + ":" + proxyData.port
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig
                    .Builder()
                    .addProxyRule(uriString)
                    .build(),
                EXECUTOR,
                nonNullCallback,
            )
        } else {
            ProxyController.getInstance().clearProxyOverride(EXECUTOR, nonNullCallback)
        }
    }
}
