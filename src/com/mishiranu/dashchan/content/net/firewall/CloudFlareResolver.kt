package com.mishiranu.dashchan.content.net.firewall

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import chan.content.Chan
import chan.http.CookieBuilder
import chan.http.FirewallResolver
import chan.http.HttpException
import chan.http.HttpResponse
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.service.webview.WebViewExtra
import com.mishiranu.dashchan.util.IOUtils
import java.net.HttpURLConnection
import java.util.regex.Pattern

class CloudFlareResolver : FirewallResolver() {
	@Throws(HttpException::class)
	override fun checkResponse(session: FirewallResolver.Session,
			response: HttpResponse): FirewallResolver.CheckResponseResult? {
		val pageBlockedByCloudflare = pageBlocked(response) &&
				(responseContainsCloudflareHeaders(response) || responseContainsCloudflareTitle(response))
		return if (pageBlockedByCloudflare) {
			FirewallResolver.CheckResponseResult(toKey(session), Exclusive())
		} else {
			null
		}
	}

	private fun pageBlocked(response: HttpResponse): Boolean {
		val responseCode = response.getResponseCode()
		return responseCode == HttpURLConnection.HTTP_FORBIDDEN || responseCode == HttpURLConnection.HTTP_UNAVAILABLE
	}

	private fun responseContainsCloudflareHeaders(response: HttpResponse): Boolean {
		val responseHeaders = response.getHeaderFields()
		val responseContainsCloudflareRayHeader = responseHeaders.containsKey("CF-RAY")
		if (!responseContainsCloudflareRayHeader) {
			val serverHeader = responseHeaders["Server"]
			return serverHeader != null && serverHeader.contains("cloudflare")
		}
		return true
	}

	// Cloudflare uses localized titles in some challenges, so this method is very unreliable
	@Throws(HttpException::class)
	private fun responseContainsCloudflareTitle(response: HttpResponse): Boolean {
		val titlePattern = Pattern.compile("<title>(.*?)</title>")
		val responseText = response.readString()
		val titleMatcher = titlePattern.matcher(responseText)
		if (titleMatcher.find()) {
			val cloudflareTitles = arrayOf("Attention Required! | Cloudflare", "Just a moment...", "Please wait…")
			val title = titleMatcher.group(1)
			for (cloudflareTitle in cloudflareTitles) {
				if (cloudflareTitle == title) {
					return true
				}
			}
		}
		return false
	}

	private fun toKey(session: FirewallResolver.Session): FirewallResolver.Exclusive.Key {
		return session.getKey(FirewallResolver.Identifier.Flag.USER_AGENT, FirewallResolver.Identifier.Flag.HOST)
	}

	override fun collectCookies(session: FirewallResolver.Session, cookieBuilder: CookieBuilder) {
		val chan = session.chan
		val key = toKey(session)
		val cookie = chan!!.configuration.getCookie(key.formatKey(COOKIE_CLOUDFLARE))
		if (!StringUtils.isEmpty(cookie)) {
			cookieBuilder.append(COOKIE_CLOUDFLARE, cookie)
		}
	}

	private fun storeCookie(session: FirewallResolver.Session, key: FirewallResolver.Exclusive.Key,
			cookie: String?, uri: Uri?) {
		val chan = session.chan
		val cookieTitle = "Cloudflare " + session.getUri()!!.getHost()
		chan!!.configuration.storeCookie(key.formatKey(COOKIE_CLOUDFLARE), cookie,
				if (cookie != null) key.formatTitle(cookieTitle) else null)
		chan!!.configuration.commit()
		if (uri != null) {
			val host = uri.getHost()
			if (chan!!.locator.isConvertableChanHost(host!!)) {
				chan!!.locator.setPreferredHost(host)
			}
			Preferences.setUseHttps(chan, "https" == uri.getScheme())
		}
	}

	private class CookieResult(val cookie: String, val uri: Uri)

	private class Extra : WebViewExtra {
		override fun getInjectJavascript(): String? =
				IOUtils.readRawResourceString(MainApplication.getInstance().resources, R.raw.web_cloudflare_inject)

		companion object {
			@JvmField
			val CREATOR: Parcelable.Creator<Extra> = object : Parcelable.Creator<Extra> {
				override fun createFromParcel(source: Parcel): Extra = Extra()
				override fun newArray(size: Int): Array<Extra?> = arrayOfNulls(size)
			}
		}
	}

	private class WebViewClient :
			FirewallResolvers.WebViewClientWithExtra<CookieResult>("CloudFlare", Extra()) {
		override fun onPageFinished(uri: Uri, cookies: Map<String, String>, title: String?): Boolean {
			val cookie = cookies[COOKIE_CLOUDFLARE] ?: return false
			setResult(CookieResult(cookie, uri))
			return true
		}

		override fun onLoad(initialUri: Uri, uri: Uri): Boolean {
			val path = uri.getPath()
			return path == null || path.isEmpty() || "/" == path ||
					path == initialUri.getPath() || path.startsWith("/cdn-cgi/")
		}
	}

	private inner class Exclusive : FirewallResolver.Exclusive {
		@Throws(FirewallResolver.CancelException::class, HttpException::class, InterruptedException::class)
		override fun resolve(session: FirewallResolver.Session, key: FirewallResolver.Exclusive.Key): Boolean {
			val result = session.resolveWebView(WebViewClient())
			if (result != null) {
				storeCookie(session, key, result.cookie, result.uri)
				return true
			}
			return false
		}
	}

	companion object {
		private const val COOKIE_CLOUDFLARE = "cf_clearance"
	}
}
