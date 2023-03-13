package com.mishiranu.dashchan.content.net.firewall;

import chan.http.FirewallResolver;
import chan.http.HttpClient;

public class FirewallResolutionDialogRequest<T> {
	private final String url;
	private final String userAgent;
	private final HttpClient.ProxyData proxyData;
	private final FirewallResolver.WebViewClient<T> client;

	public FirewallResolutionDialogRequest(String url, String userAgent, HttpClient.ProxyData proxyData, FirewallResolver.WebViewClient<T> client) {
		this.url = url;
		this.userAgent = userAgent;
		this.client = client;
		this.proxyData = proxyData;
	}

	public String getUrl() {
		return url;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public HttpClient.ProxyData getProxyData() {
		return proxyData;
	}

	public FirewallResolver.WebViewClient<T> getClient() {
		return client;
	}
}
