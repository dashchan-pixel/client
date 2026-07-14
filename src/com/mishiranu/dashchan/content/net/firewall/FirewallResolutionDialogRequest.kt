package com.mishiranu.dashchan.content.net.firewall

import chan.http.FirewallResolver
import chan.http.HttpClient

class FirewallResolutionDialogRequest<T>(
    val url: String,
    val userAgent: String?,
    val proxyData: HttpClient.ProxyData?,
    val client: FirewallResolver.WebViewClient<T>,
)
