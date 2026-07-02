package chan.http;

import android.net.Uri;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Response;

public final class HttpSession {
	final HttpHolder holder;
	final HttpClient client;
	HttpResponse response;

	final Proxy proxy;
	final boolean verifyCertificate;
	final boolean mayCheckFirewallBlock;
	final int delay;

	private final ArrayList<Uri> requestedUris = new ArrayList<>(2);
	Uri redirectedUri;

	int attempt;
	boolean forceGet;
	boolean executing;

	Call call;
	Response okResponse;
	Response deadResponse;
	HttpHolder.Callback callback;

	HttpSession(HttpHolder holder, HttpClient client, Uri uri, Proxy proxy,
			boolean verifyCertificate, boolean mayCheckFirewallBlock, int delay, int maxAttempts) {
		this.holder = holder;
		this.client = client;
		this.proxy = proxy;
		this.verifyCertificate = verifyCertificate;
		this.mayCheckFirewallBlock = mayCheckFirewallBlock;
		this.delay = delay;
		attempt = maxAttempts;
		requestedUris.add(uri);
	}

	void checkThread() {
		holder.checkThread();
	}

	void checkExecuting() {
		if (executing) {
			throw new IllegalStateException("Can't perform requests during active execute process");
		}
	}

	List<Uri> getRequestedUris() {
		checkThread();
		return requestedUris;
	}

	Uri getCurrentRequestedUri() {
		checkThread();
		return requestedUris.get(requestedUris.size() - 1);
	}

	void setNextRequestedUri(Uri uri) {
		checkThread();
		requestedUris.add(uri);
	}

	private void setCallInternal(Call call, HttpHolder.Callback callback)
			throws HttpClient.InterruptedHttpException {
		checkThread();
		this.call = call;
		this.callback = callback;
		redirectedUri = null;
		if (holder.isInterrupted()) {
			this.call = null;
			this.callback = null;
			throw new HttpClient.InterruptedHttpException();
		}
		if (call != null) {
			client.onConnect(holder.chan, call, delay);
		}
	}

	void setCall(Call call) throws HttpClient.InterruptedHttpException {
		checkThread();
		setCallInternal(call, null);
	}

	void setCallback(HttpHolder.Callback callback) throws HttpClient.InterruptedHttpException {
		checkThread();
		setCallInternal(null, callback);
	}

	boolean nextAttempt() {
		checkThread();
		return attempt-- > 0;
	}

	void disconnectAndClear() {
		checkThread();
		Call call = this.call;
		this.call = null;
		Response okResponse = this.okResponse;
		this.okResponse = null;
		HttpHolder.Callback callback = this.callback;
		this.callback = null;
		if (response != null) {
			// HttpResponse will call disconnectAndClear if the response is still active
			response.cleanupAndDisconnect();
		}
		if (okResponse != null) {
			okResponse.close();
			deadResponse = okResponse;
		}
		if (call != null) {
			call.cancel();
			client.onDisconnect(call);
		}
		if (callback != null) {
			callback.onDisconnectRequested();
		}
	}

	void checkResponseCode() throws HttpException {
		checkThread();
		int responseCode = getResponseCode();
		boolean success = responseCode >= 200 && responseCode <= 303
				|| responseCode == HttpClient.HTTP_TEMPORARY_REDIRECT;
		if (!success) {
			String message = HttpClient.transformResponseMessage(getResponseMessage());
			disconnectAndClear();
			throw new HttpException(responseCode, message);
		}
	}

	private Response getResponseForHeaders() {
		checkThread();
		Response response = okResponse;
		if (response == null) {
			response = deadResponse;
		}
		return response;
	}

	int getResponseCode() {
		Response response = getResponseForHeaders();
		return response != null ? response.code() : -1;
	}

	String getResponseMessage() {
		Response response = getResponseForHeaders();
		if (response != null) {
			String message = response.message();
			return message.isEmpty() ? null : message;
		}
		return null;
	}

	Map<String, List<String>> getHeaderFields() {
		Response response = getResponseForHeaders();
		if (response == null) {
			return Collections.emptyMap();
		}
		Headers headers = response.headers();
		LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
		for (int i = 0; i < headers.size(); i++) {
			String name = headers.name(i);
			List<String> values = map.get(name);
			if (values == null) {
				values = new ArrayList<>(1);
				map.put(name, values);
			}
			values.add(headers.value(i));
		}
		return map;
	}

	String getCookieValue(String name) {
		Map<String, List<String>> headers = getHeaderFields();
		List<String> cookies = headers.get("Set-Cookie");
		if (cookies == null) {
			cookies = headers.get("set-cookie");
		}
		if (cookies != null) {
			String start = name + "=";
			for (String cookie : cookies) {
				if (cookie.startsWith(start)) {
					int startIndex = start.length();
					int endIndex = cookie.indexOf(';');
					if (endIndex >= 0) {
						return cookie.substring(startIndex, endIndex);
					} else {
						return cookie.substring(startIndex);
					}
				}
			}
		}
		return null;
	}

	long getLength() {
		Response response = getResponseForHeaders();
		if (response != null && HttpClient.Encoding.get(response.headers()) == HttpClient.Encoding.IDENTITY) {
			String contentLength = response.header("Content-Length");
			if (contentLength != null) {
				try {
					return Long.parseLong(contentLength);
				} catch (NumberFormatException e) {
					return -1;
				}
			}
		}
		return -1;
	}
}
