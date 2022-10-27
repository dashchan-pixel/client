package com.mishiranu.dashchan.content.net;

import android.app.Application;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.MainThread;

public class UserAgentProvider {
	private static final UserAgentProvider INSTANCE = new UserAgentProvider();

	private String userAgent;

	@MainThread
	public static void initialize(Application appContext) {
		String userAgent;

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
			userAgent = WebSettings.getDefaultUserAgent(appContext);
		} else {
			userAgent = new WebView(appContext).getSettings().getUserAgentString();
		}

		INSTANCE.userAgent = userAgent;
	}

	public static UserAgentProvider getInstance() {
		if (INSTANCE.userAgent == null) {
			throw new IllegalStateException("UserAgentProvider is not initialized");
		}
		return INSTANCE;
	}

	private UserAgentProvider() {
	}

	public String getUserAgent() {
		return userAgent;
	}

}
