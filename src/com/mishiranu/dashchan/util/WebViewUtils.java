package com.mishiranu.dashchan.util;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import chan.http.HttpClient;
import com.mishiranu.dashchan.content.MainApplication;
import java.util.concurrent.Executor;

public class WebViewUtils {
	public static void clearCookie() {
		CookieManager.getInstance().removeAllCookies(null);
	}

	@SuppressWarnings("deprecation")
	public static void clearAll(WebView webView) {
		clearCookie();
		if (webView != null) {
			webView.clearCache(true);
		}
		WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(MainApplication.getInstance());
		webViewDatabase.clearHttpAuthUsernamePassword();
		WebStorage.getInstance().deleteAllData();
	}

	private static final Executor EXECUTOR = Runnable::run;

	public static void setProxy(Context context, HttpClient.ProxyData proxyData, Runnable callback) {
		Runnable nonNullCallback = callback != null ? callback : () -> {};
		if (proxyData != null) {
			String uriString = (proxyData.socks ? "socks" : "http") + "://"
					+ proxyData.host + ":" + proxyData.port;
			ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder()
					.addProxyRule(uriString).build(), EXECUTOR, nonNullCallback);
		} else {
			ProxyController.getInstance().clearProxyOverride(EXECUTOR, nonNullCallback);
		}
	}
}
