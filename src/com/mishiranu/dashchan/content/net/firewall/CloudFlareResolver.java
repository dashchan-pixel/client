package com.mishiranu.dashchan.content.net.firewall;

import android.net.Uri;
import android.os.Parcel;

import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.service.webview.WebViewExtra;
import com.mishiranu.dashchan.util.IOUtils;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import chan.content.Chan;
import chan.http.CookieBuilder;
import chan.http.FirewallResolver;
import chan.http.HttpException;
import chan.http.HttpResponse;
import chan.util.StringUtils;

public class CloudFlareResolver extends FirewallResolver {
	private static final String COOKIE_CLOUDFLARE = "cf_clearance";

	@Override
	public CheckResponseResult checkResponse(Session session, HttpResponse response) throws HttpException {
		boolean pageBlockedByCloudflare = pageBlocked(response) && (responseContainsCloudflareHeaders(response) || responseContainsCloudflareTitle(response));
		if (pageBlockedByCloudflare) {
			return new CheckResponseResult(toKey(session), new Exclusive());
		} else {
			return null;
		}
	}

	private boolean pageBlocked(HttpResponse response) {
		int responseCode = response.getResponseCode();
		return responseCode == HttpURLConnection.HTTP_FORBIDDEN || responseCode == HttpURLConnection.HTTP_UNAVAILABLE;
	}

	private boolean responseContainsCloudflareHeaders(HttpResponse response) {
		Map<String, List<String>> responseHeaders = response.getHeaderFields();
		boolean responseContainsCloudflareRayHeader = responseHeaders.containsKey("CF-RAY");
		if (!responseContainsCloudflareRayHeader) {
			List<String> serverHeader = responseHeaders.get("Server");
			return serverHeader != null && serverHeader.contains("cloudflare");
		}
		return true;
	}

	// Cloudflare uses localized titles in some challenges, so this method is very unreliable
	private boolean responseContainsCloudflareTitle(HttpResponse response) throws HttpException {
		Pattern titlePattern = Pattern.compile("<title>(.*?)</title>");
		String responseText = response.readString();
		Matcher titleMatcher = titlePattern.matcher(responseText);
		if (titleMatcher.find()) {
			String[] cloudflareTitles = {"Attention Required! | Cloudflare", "Just a moment...", "Please wait…"};
			String title = titleMatcher.group(1);
			for (String cloudflareTitle : cloudflareTitles) {
				if (cloudflareTitle.equals(title)) {
					return true;
				}
			}
		}
		return false;
	}

	private Exclusive.Key toKey(Session session) {
		return session.getKey(Identifier.Flag.USER_AGENT);
	}

	@Override
	public void collectCookies(Session session, CookieBuilder cookieBuilder) {
		Chan chan = session.getChan();
		Exclusive.Key key = toKey(session);
		String cookie = chan.configuration.getCookie(key.formatKey(COOKIE_CLOUDFLARE));
		if (!StringUtils.isEmpty(cookie)) {
			cookieBuilder.append(COOKIE_CLOUDFLARE, cookie);
		}
	}

	private void storeCookie(Session session, Exclusive.Key key, String cookie, Uri uri) {
		Chan chan = session.getChan();
		chan.configuration.storeCookie(key.formatKey(COOKIE_CLOUDFLARE), cookie,
				cookie != null ? key.formatTitle("CloudFlare") : null);
		chan.configuration.commit();
		if (uri != null) {
			String host = uri.getHost();
			if (chan.locator.isConvertableChanHost(host)) {
				chan.locator.setPreferredHost(host);
			}
			Preferences.setUseHttps(chan, "https".equals(uri.getScheme()));
		}
	}

	private static class CookieResult {
		public final String cookie;
		public final Uri uri;

		public CookieResult(String cookie, Uri uri) {
			this.cookie = cookie;
			this.uri = uri;
		}
	}

	private static class Extra implements WebViewExtra {
		public static final Creator<Extra> CREATOR = new Creator<Extra>() {
			@Override
			public Extra createFromParcel(Parcel in) {
				return new Extra();
			}

			@Override
			public Extra[] newArray(int size) {
				return new Extra[0];
			}
		};

		@Override
		public String getInjectJavascript() {
			return IOUtils.readRawResourceString(MainApplication.getInstance().getResources(),
					R.raw.web_cloudflare_inject);
		}
	}

	private static class WebViewClient extends FirewallResolvers.WebViewClientWithExtra<CookieResult> {

		public WebViewClient() {
			super("CloudFlare", new Extra());
		}

		@Override
		public boolean onPageFinished(Uri uri, Map<String, String> cookies, String title) {
			String cookie = cookies.get(COOKIE_CLOUDFLARE);
			if (cookie == null) {
				return false;
			}
			setResult(new CookieResult(cookie, uri));
			return true;
		}

		@Override
		public boolean onLoad(Uri initialUri, Uri uri) {
			String path = uri.getPath();
			return path == null || path.isEmpty() || "/".equals(path) ||
					path.equals(initialUri.getPath()) || path.startsWith("/cdn-cgi/");
		}
	}

	private class Exclusive implements FirewallResolver.Exclusive {

		@Override
		public boolean resolve(Session session, Key key) throws CancelException, InterruptedException {
			CookieResult result = session.resolveWebView(new WebViewClient());
			if (result != null) {
				storeCookie(session, key, result.cookie, result.uri);
				return true;
			}
			return false;
		}
	}
}
