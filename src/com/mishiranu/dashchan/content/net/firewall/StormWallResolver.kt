package com.mishiranu.dashchan.content.net.firewall

import android.net.Uri
import chan.content.Chan
import chan.http.CookieBuilder
import chan.http.FirewallResolver
import chan.http.HttpException
import chan.http.HttpRequest
import chan.http.HttpResponse
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import java.util.regex.Pattern

class StormWallResolver : FirewallResolver() {
	private class CookieResult(val cookie: String, val uri: Uri)

	private class WebViewClient : FirewallResolver.WebViewClient<CookieResult>("StormWall") {
		@Volatile private var wasChecked = false
		@Volatile private var wasReloaded = false

		override fun onPageFinished(uri: Uri, cookies: Map<String, String>, title: String?): Boolean {
			if (wasChecked) {
				wasChecked = false
				wasReloaded = true
			} else if (wasReloaded) {
				val cookie = cookies[COOKIE_STORMWALL]
				setResult(if (cookie != null) CookieResult(cookie, uri) else null)
				wasReloaded = false
				return true
			}
			return false
		}

		override fun onLoad(initialUri: Uri, uri: Uri): Boolean {
			if ("static.stormwall.pro" == uri.getHost()) {
				wasChecked = true
				return true
			}
			val path = uri.getPath()
			return path == null || path.isEmpty() || "/" == path || path == initialUri.getPath()
		}
	}

	private inner class Exclusive(val responseText: String) : FirewallResolver.Exclusive {
		@Throws(FirewallResolver.CancelException::class, HttpException::class, InterruptedException::class)
		override fun resolve(session: FirewallResolver.Session, key: FirewallResolver.Exclusive.Key): Boolean {
			val ceMatcher = PATTERN_CE.matcher(responseText)
			val ckMatcher = PATTERN_CK.matcher(responseText)
			if (ceMatcher.find() && ckMatcher.find()) {
				val ce = StringUtils.emptyIfNull(ceMatcher.group(2))
				val ckString = StringUtils.emptyIfNull(ckMatcher.group(2))
				val ck: Int? = try {
					ckString.toInt()
				} catch (e: NumberFormatException) {
					// Ignore
					null
				}
				if (ce.isNotEmpty() && ck != null) {
					val calculatedCookie = calculateCookie(ce, ck)
					val response = HttpRequest(session.getUri(), session)
							.setHeadMethod().setSuccessOnly(false)
							.addHeader("User-Agent", session.getIdentifier().userAgent)
							.addCookie(COOKIE_STORMWALL, calculatedCookie)
							.perform()
					try {
						if (!isBlocked(response)) {
							storeCookie(session, key, calculatedCookie, session.getUri())
							return true
						}
					} finally {
						response.cleanupAndDisconnect()
					}
				}
			}
			val result = session.resolveWebView(WebViewClient())
			if (result != null) {
				storeCookie(session, key, result.cookie, result.uri)
				return true
			}
			return false
		}
	}

	private fun isBlocked(response: HttpResponse): Boolean {
		val headers = response.getHeaderFields()["X-FireWall-Protection"]
		return headers != null && !headers.isEmpty()
	}

	@Throws(HttpException::class)
	override fun checkResponse(session: FirewallResolver.Session,
			response: HttpResponse): FirewallResolver.CheckResponseResult? {
		if (isBlocked(response)) {
			if (session.isResolveRequest()) {
				val contentType = response.getHeaderFields()["Content-Type"]
				if (contentType != null && contentType.size == 1 && contentType[0].startsWith("text/html")) {
					val responseText = response.readString()
					return FirewallResolver.CheckResponseResult(session.getKey(), Exclusive(responseText))
				}
			}
			return FirewallResolver.CheckResponseResult(session.getKey(), FirewallResolver.Exclusive.FAIL)
		}
		return null
	}

	private fun storeCookie(session: FirewallResolver.Session, key: FirewallResolver.Exclusive.Key,
			cookie: String?, uri: Uri?) {
		val chan = session.getChan()
		chan.configuration.storeCookie(key.formatKey(COOKIE_STORMWALL), cookie,
				if (cookie != null) key.formatTitle("StormWall") else null)
		chan.configuration.commit()
		if (uri != null) {
			val host = uri.getHost()
			if (chan.locator.isConvertableChanHost(host)) {
				chan.locator.setPreferredHost(host)
			}
			Preferences.setUseHttps(chan, "https" == uri.getScheme())
		}
	}

	override fun collectCookies(session: FirewallResolver.Session, cookieBuilder: CookieBuilder) {
		val chan = session.getChan()
		val key = toKey(session)
		val cookie = chan.configuration.getCookie(key.formatKey(COOKIE_STORMWALL))
		if (!StringUtils.isEmpty(cookie)) {
			cookieBuilder.append(COOKIE_STORMWALL, cookie)
		}
	}

	companion object {
		private const val COOKIE_STORMWALL = "swp_token"

		private val PATTERN_CE = Pattern.compile(" cE ?= ?(['\"])(.*?)\\1")
		private val PATTERN_CK = Pattern.compile(" cK ?= ?(?:(['\"])|)(.*?)(?:\\1|;)")

		private fun calculateCookie(ce: String, ck: Int): String {
			val result = StringBuilder()
			val alphabet = "0123456789qwertyuiopasdfghjklzxcvbnm:?!"
			val length = alphabet.length
			for (i in ce.indices) {
				val c = ce[i]
				val index = alphabet.indexOf(c)
				result.append(if (index >= 0) alphabet[(index - ((ck + i) % length) + length) % length] else c)
			}
			return result.toString()
		}

		private fun toKey(session: FirewallResolver.Session): FirewallResolver.Exclusive.Key {
			return session.getKey(FirewallResolver.Identifier.Flag.USER_AGENT)
		}
	}
}
