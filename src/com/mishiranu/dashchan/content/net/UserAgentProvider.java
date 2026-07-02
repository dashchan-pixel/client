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
		INSTANCE.userAgent = WebSettings.getDefaultUserAgent(appContext);
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
