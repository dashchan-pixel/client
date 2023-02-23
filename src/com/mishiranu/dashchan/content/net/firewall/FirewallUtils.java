package com.mishiranu.dashchan.content.net.firewall;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class FirewallUtils {

	private FirewallUtils() {

	}

	static Map<String, String> parseCookies(String cookiesString) {
		Map<String, String> cookies;
		if (cookiesString != null && !cookiesString.isEmpty()) {
			cookies = new HashMap<>();
			String[] splitted = cookiesString.split(";\\s*");
			for (String pair : splitted) {
				int index = pair.indexOf('=');
				if (index >= 0) {
					String key = pair.substring(0, index);
					String value = pair.substring(index + 1);
					cookies.put(key, value);
				}
			}
		} else {
			cookies = Collections.emptyMap();
		}
		return cookies;
	}

}
