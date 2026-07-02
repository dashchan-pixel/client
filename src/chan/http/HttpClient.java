package chan.http;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.util.Pair;
import androidx.annotation.NonNull;
import chan.content.Chan;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.brotli.dec.BrotliInputStream;

public class HttpClient {
	private static final HashMap<String, String> SHORT_RESPONSE_MESSAGES = new HashMap<>();

	private static final HostnameVerifier UNSAFE_HOSTNAME_VERIFIER = (hostname, session) -> true;

	@SuppressLint("TrustAllX509TrustManager")
	private static final X509TrustManager UNSAFE_TRUST_MANAGER = new X509TrustManager() {
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	};

	static final int HTTP_TEMPORARY_REDIRECT = 307;

	static {
		SHORT_RESPONSE_MESSAGES.put("Internal Server Error", "Internal Error");
		SHORT_RESPONSE_MESSAGES.put("Service Temporarily Unavailable", "Service Unavailable");
	}

	private static final HttpClient INSTANCE = new HttpClient();

	public static HttpClient getInstance() {
		return INSTANCE;
	}

	private HttpClient() {}

	public static class ProxyData {
		public final boolean socks;
		public final String host;
		public final int port;

		public ProxyData(boolean socks, String host, int port) {
			this.socks = socks;
			this.host = host;
			this.port = port;
		}

		private Proxy proxy;

		public Proxy getProxy() {
			if (proxy == null) {
				try {
					proxy = new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
							InetSocketAddress.createUnresolved(host, port));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			return proxy;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof ProxyData) {
				ProxyData proxyData = (ProxyData) o;
				return socks == proxyData.socks &&
						CommonUtils.equals(host, proxyData.host) &&
						port == proxyData.port;
			}
			return false;
		}

		@Override
		public int hashCode() {
			int result = (socks ? 1 : 0);
			result = 31 * result + (host != null ? host.hashCode() : 0);
			result = 31 * result + port;
			return result;
		}
	}

	public boolean checkProxyValid(Map<String, String> map) {
		ProxyData proxyData = getProxyData(map);
		return proxyData == null || proxyData.getProxy() != null;
	}

	public ProxyData getProxyData(Chan chan) {
		return getProxyData(Preferences.getProxy(chan));
	}

	private ProxyData getProxyData(Map<String, String> map) {
		if (map != null) {
			String host = map.get(Preferences.SUB_KEY_PROXY_HOST);
			if (!StringUtils.isEmpty(host)) {
				int port;
				try {
					port = Integer.parseInt(map.get(Preferences.SUB_KEY_PROXY_PORT));
				} catch (Exception e) {
					port = -1;
				}
				if (port > 0) {
					boolean socks = Preferences.VALUE_PROXY_TYPE_SOCKS.equals(map.get(Preferences.SUB_KEY_PROXY_TYPE));
					return new ProxyData(socks, host, port);
				}
			}
		}
		return null;
	}

	Proxy getProxy(Chan chan) {
		ProxyData proxyData = getProxyData(chan);
		synchronized (proxies) {
			ProxyData lastProxyData = proxies.get(chan.name);
			if (CommonUtils.equals(proxyData, lastProxyData)) {
				// With initialized proxy object
				proxyData = lastProxyData;
			} else {
				proxies.put(chan.name, proxyData);
			}
		}
		return proxyData != null ? proxyData.getProxy() : null;
	}

	private final HashMap<String, ProxyData> proxies = new HashMap<>();

	static final class InterruptedHttpException extends IOException {
		public HttpException toHttp() {
			return new HttpException(null, false, false, this);
		}
	}

	private static final class RetryException extends Exception {}

	enum Encoding {
		IDENTITY("identity"),
		GZIP("gzip"),
		DEFLATE("deflate"),
		BROTLI("br"),
		UNKNOWN("");

		public final String name;

		Encoding(String name) {
			this.name = name;
		}

		public static Encoding get(Headers headers) {
			if (headers != null) {
				String contentEncoding = headers.get("Content-Encoding");
				if (!StringUtils.isEmpty(contentEncoding)) {
					for (Encoding encoding : values()) {
						if (contentEncoding.equals(encoding.name)) {
							return encoding;
						}
					}
					return UNKNOWN;
				}
			}
			return IDENTITY;
		}
	}

	private static final String ACCEPT_ENCODING = "gzip, deflate, br";

	private OkHttpClient baseClient;
	private SSLSocketFactory unsafeSslSocketFactory;
	private final HashMap<ClientKey, OkHttpClient> clients = new HashMap<>();

	private static final class ClientKey {
		final Proxy proxy;
		final boolean verifyCertificate;
		final int connectTimeout;
		final int readTimeout;

		ClientKey(Proxy proxy, boolean verifyCertificate, int connectTimeout, int readTimeout) {
			this.proxy = proxy;
			this.verifyCertificate = verifyCertificate;
			this.connectTimeout = connectTimeout;
			this.readTimeout = readTimeout;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof ClientKey) {
				ClientKey key = (ClientKey) o;
				return CommonUtils.equals(proxy, key.proxy) && verifyCertificate == key.verifyCertificate
						&& connectTimeout == key.connectTimeout && readTimeout == key.readTimeout;
			}
			return false;
		}

		@Override
		public int hashCode() {
			int result = proxy != null ? proxy.hashCode() : 0;
			result = 31 * result + (verifyCertificate ? 1 : 0);
			result = 31 * result + connectTimeout;
			result = 31 * result + readTimeout;
			return result;
		}
	}

	private synchronized OkHttpClient obtainClient(Proxy proxy, boolean verifyCertificate,
			int connectTimeout, int readTimeout) {
		if (baseClient == null) {
			baseClient = new OkHttpClient.Builder()
					.followRedirects(false)
					.followSslRedirects(false)
					.retryOnConnectionFailure(false)
					.build();
		}
		ClientKey key = new ClientKey(proxy, verifyCertificate, connectTimeout, readTimeout);
		OkHttpClient client = clients.get(key);
		if (client == null) {
			OkHttpClient.Builder builder = baseClient.newBuilder()
					.proxy(proxy != null ? proxy : Proxy.NO_PROXY)
					.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
					.readTimeout(readTimeout, TimeUnit.MILLISECONDS)
					.writeTimeout(readTimeout, TimeUnit.MILLISECONDS);
			if (!verifyCertificate) {
				if (unsafeSslSocketFactory == null) {
					try {
						SSLContext sslContext = SSLContext.getInstance("TLS");
						sslContext.init(null, new X509TrustManager[] {UNSAFE_TRUST_MANAGER}, null);
						unsafeSslSocketFactory = sslContext.getSocketFactory();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				builder.sslSocketFactory(unsafeSslSocketFactory, UNSAFE_TRUST_MANAGER);
				builder.hostnameVerifier(UNSAFE_HOSTNAME_VERIFIER);
			}
			client = builder.build();
			if (clients.size() > 16) {
				clients.clear();
			}
			clients.put(key, client);
		}
		return client;
	}

	HttpResponse execute(HttpSession session, HttpRequest request) throws HttpException {
		while (true) {
			try {
				return executeInternal(session, request);
			} catch (RetryException e) {
				// Continue
			}
		}
	}

	@SuppressWarnings("CharsetObjectCanBeUsed")
	private static void encodeUriBufferPart(StringBuilder uriStringBuilder,
			char[] chars, int i, int start, boolean ascii) {
		if (!ascii) {
			try {
				for (byte b : new String(chars, start, i - start).getBytes("UTF-8")) {
					String s = Integer.toString(b & 0xff, 16).toUpperCase(Locale.US);
					uriStringBuilder.append('%');
					uriStringBuilder.append(s);
				}
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		} else {
			uriStringBuilder.append(chars, start, i - start);
		}
	}

	private static void encodeUriAppend(StringBuilder uriStringBuilder, String part) {
		char[] chars = part.toCharArray();
		boolean ascii = true;
		int start = 0;
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			boolean ita = c < 0x80;
			if (ita != ascii) {
				encodeUriBufferPart(uriStringBuilder, chars, i, start, ascii);
				start = i;
				ascii = ita;
			}
		}
		encodeUriBufferPart(uriStringBuilder, chars, chars.length, start, ascii);
	}

	public static URL encodeUri(Uri uri) throws MalformedURLException {
		StringBuilder uriStringBuilder = new StringBuilder();
		uriStringBuilder.append(StringUtils.emptyIfNull(uri.getScheme())).append("://");
		String host = IDN.toASCII(StringUtils.emptyIfNull(uri.getHost()));
		uriStringBuilder.append(host);
		int port = uri.getPort();
		if (port != -1) {
			uriStringBuilder.append(':').append(port);
		}
		String path = uri.getEncodedPath();
		if (!StringUtils.isEmpty(path)) {
			encodeUriAppend(uriStringBuilder, path);
		}
		String query = uri.getEncodedQuery();
		if (!StringUtils.isEmpty(query)) {
			uriStringBuilder.append('?');
			encodeUriAppend(uriStringBuilder, query);
		}
		return new URL(uriStringBuilder.toString());
	}

	private static class EntityRequestBody extends RequestBody {
		private final RequestEntity entity;
		private final HttpSession session;
		private final HttpRequest.OutputListener listener;

		EntityRequestBody(RequestEntity entity, HttpSession session, HttpRequest.OutputListener listener) {
			this.entity = entity;
			this.session = session;
			this.listener = listener;
		}

		@Override
		public MediaType contentType() {
			String contentType = entity.getContentType();
			return contentType != null ? MediaType.parse(contentType) : null;
		}

		@Override
		public long contentLength() {
			long contentLength = entity.getContentLength();
			return contentLength > 0 ? contentLength : -1;
		}

		@Override
		public void writeTo(@NonNull BufferedSink sink) throws IOException {
			long contentLength = entity.getContentLength();
			ClientOutputStream output = new ClientOutputStream(sink.outputStream(),
					session, listener, contentLength);
			entity.write(output);
			output.flush();
		}
	}

	private HttpResponse executeInternal(HttpSession session, HttpRequest request)
			throws HttpException, RetryException {
		session.checkThread();
		session.checkExecuting();
		session.disconnectAndClear();
		session.executing = true;
		try {
			Uri requestedUri = session.getCurrentRequestedUri();
			if (!session.holder.chan.locator.isWebScheme(requestedUri)) {
				throw new HttpException(ErrorItem.Type.UNSUPPORTED_SCHEME, false, false);
			}
			URL url = encodeUri(requestedUri);
			Request.Builder builder = new Request.Builder().url(url);

			String userAgent = null;
			boolean userAgentSet = false;
			boolean acceptEncodingSet = false;
			if (request.headers != null) {
				for (Pair<String, String> header : request.headers) {
					if ("Connection".equalsIgnoreCase(header.first)) {
						continue;
					}
					builder.header(header.first, header.second);
					if ("User-Agent".equalsIgnoreCase(header.first)) {
						userAgent = header.second;
						userAgentSet = true;
					}
					if ("Accept-Encoding".equalsIgnoreCase(header.first)) {
						acceptEncodingSet = true;
					}
				}
			}
			if (!userAgentSet) {
				userAgent = AdvancedPreferences.getUserAgent(session.holder.chan.name);
				builder.header("User-Agent", userAgent);
			}
			if (!acceptEncodingSet) {
				builder.header("Accept-Encoding", ACCEPT_ENCODING);
			}
			FirewallResolver.Identifier resolverIdentifier = new FirewallResolver
					.Identifier(userAgent, !userAgentSet, url.getHost());
			CookieBuilder cookieBuilder = !session.mayCheckFirewallBlock
					? request.cookieBuilder : obtainModifiedCookieBuilder(request.cookieBuilder,
					session.holder.chan, requestedUri, resolverIdentifier);
			if (cookieBuilder != null) {
				builder.header("Cookie", cookieBuilder.build());
			}
			HttpValidator validator = request.validator;
			if (validator != null) {
				if (!StringUtils.isEmpty(validator.getEntityTag())) {
					builder.header("If-None-Match", validator.getEntityTag());
				}
				if (!StringUtils.isEmpty(validator.getLastModified())) {
					builder.header("If-Modified-Since", validator.getLastModified());
				}
			}
			if (request.rangeStart >= 0 || request.rangeEnd >= 0) {
				builder.header("Range", "bytes=" +
						(request.rangeStart >= 0 ? request.rangeStart : "") + "-" +
						(request.rangeEnd >= 0 ? request.rangeEnd : ""));
			}

			boolean forceGet = session.forceGet;
			HttpRequest.RequestMethod requestMethod = request.requestMethod;
			if (forceGet && requestMethod != HttpRequest.RequestMethod.GET &&
					requestMethod != HttpRequest.RequestMethod.HEAD) {
				requestMethod = HttpRequest.RequestMethod.GET;
			}
			RequestEntity entity = forceGet ? null : request.requestEntity;
			RequestBody requestBody = null;
			if (entity != null) {
				requestBody = new EntityRequestBody(entity, session,
						forceGet ? null : request.outputListener);
			} else if (requestMethod == HttpRequest.RequestMethod.POST
					|| requestMethod == HttpRequest.RequestMethod.PUT) {
				requestBody = RequestBody.create(new byte[0], null);
			}
			builder.method(requestMethod.name(), requestBody);

			OkHttpClient client = obtainClient(session.proxy, session.verifyCertificate,
					request.connectTimeout, request.readTimeout);
			Call call = client.newCall(builder.build());
			session.setCall(call);
			Response okResponse;
			try {
				okResponse = call.execute();
			} catch (IOException e) {
				if (call.isCanceled()) {
					throw new InterruptedHttpException();
				}
				throw e;
			}
			session.okResponse = okResponse;
			int responseCode = okResponse.code();
			HttpValidator resultValidator = HttpValidator.obtain(okResponse.headers());
			String contentType = okResponse.header("Content-Type");
			String charsetName = extractCharsetName(contentType);
			session.holder.checkInterrupted();
			HttpResponse response = new HttpResponse(session, resultValidator, charsetName);
			session.response = response;

			HttpRequest.RedirectHandler redirectHandler = request.redirectHandler;
			switch (responseCode) {
				case HttpURLConnection.HTTP_MOVED_PERM:
				case HttpURLConnection.HTTP_MOVED_TEMP:
				case HttpURLConnection.HTTP_SEE_OTHER:
				case HTTP_TEMPORARY_REDIRECT: {
					boolean oldHttps = "https".equals(requestedUri.getScheme());
					Uri redirectedUri = obtainRedirectedUri(requestedUri, okResponse.header("Location"));
					if (redirectedUri == null) {
						throw new HttpException(ErrorItem.Type.DOWNLOAD, false, false);
					}
					session.redirectedUri = redirectedUri;
					HttpRequest.RedirectHandler.Action action;
					try {
						action = redirectHandler.onRedirect(response);
					} catch (HttpException e) {
						session.disconnectAndClear();
						throw e;
					}
					redirectedUri = session.redirectedUri;
					if (action == HttpRequest.RedirectHandler.Action.GET ||
							action == HttpRequest.RedirectHandler.Action.RETRANSMIT) {
						session.disconnectAndClear();
						if (redirectedUri == null) {
							throw new HttpException(ErrorItem.Type.DOWNLOAD, false, false);
						}
						boolean newHttps = "https".equals(redirectedUri.getScheme());
						if (session.verifyCertificate && oldHttps && !newHttps) {
							// Redirect from https to http is unsafe
							throw new HttpException(ErrorItem.Type.UNSAFE_REDIRECT, true, false);
						}
						if (action == HttpRequest.RedirectHandler.Action.GET) {
							session.forceGet = true;
						}
						session.redirectedUri = null;
						session.setNextRequestedUri(redirectedUri);
						if (session.nextAttempt()) {
							throw new RetryException();
						} else {
							session.disconnectAndClear();
							throw new HttpException(responseCode, session.getResponseMessage());
						}
					}
				}
			}

			if (validator != null && responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
				String responseMessage = session.getResponseMessage();
				session.disconnectAndClear();
				throw new HttpException(responseCode, responseMessage);
			}

			if (session.holder.chan.name != null && session.mayCheckFirewallBlock &&
					requestMethod != HttpRequest.RequestMethod.HEAD) {
				FirewallResolver.CheckResult result;
				try {
					result = FirewallResolver.Implementation.getInstance().checkResponse(session.holder.chan,
							requestedUri, session.holder, response, resolverIdentifier,
							session.holder.mayResolveFirewallBlock);
				} catch (InterruptedException e) {
					throw new InterruptedHttpException();
				}
				if (result != null) {
					if (result.resolved && session.nextAttempt()) {
						if (result.retransmitOnSuccess) {
							session.forceGet = false;
						}
						Uri redirectedUri = session.redirectedUri;
						if (redirectedUri != null) {
							session.redirectedUri = null;
							session.setNextRequestedUri(redirectedUri);
						}
						session.holder.mayResolveFirewallBlock = false;
						throw new RetryException();
					} else {
						session.disconnectAndClear();
						throw new HttpException(ErrorItem.Type.FIREWALL_BLOCK, true, false);
					}
				}
			}

			if (request.successOnly) {
				session.checkResponseCode();
			}
			session.holder.checkInterrupted();
			return response;
		} catch (InterruptedHttpException e) {
			session.disconnectAndClear();
			throw e.toHttp();
		} catch (IOException e) {
			if (isConnectionReset(e)) {
				// Sometimes server closes the socket, but client is still trying to use it
				if (session.nextAttempt()) {
					e.printStackTrace();
					throw new RetryException();
				}
			}
			session.disconnectAndClear();
			throw transformIOException(e);
		} finally {
			session.executing = false;
		}
	}

	InputStream open(HttpResponse response) throws HttpException {
		if (response.session == null) {
			throw new IllegalStateException();
		}
		response.session.checkThread();
		try {
			Response okResponse = response.session.okResponse;
			if (okResponse == null) {
				throw new InterruptedHttpException();
			}
			InputStream input = okResponse.body() != null ? okResponse.body().byteStream() : null;
			boolean success = false;
			try {
				if (input == null) {
					throw new HttpException(ErrorItem.Type.EMPTY_RESPONSE, false, false);
				}
				response.session.holder.checkInterrupted();
				input = new BufferedInputStream(input, 8192);
				switch (Encoding.get(okResponse.headers())) {
					case IDENTITY: {
						break;
					}
					case GZIP: {
						input = new GZIPInputStream(input);
						break;
					}
					case DEFLATE: {
						input = new DeflateInputStream(input);
						break;
					}
					case BROTLI: {
						input = new BrotliInputStream(input);
						break;
					}
					default: {
						throw new HttpException(ErrorItem.Type.DOWNLOAD, false, false);
					}
				}
				success = true;
				return new ClientInputStream(input, response.session);
			} finally {
				if (!success) {
					IOUtils.close(input);
				}
			}
		} catch (InterruptedHttpException e) {
			throw e.toHttp();
		} catch (IOException e) {
			throw transformIOException(e);
		}
	}

	CookieBuilder obtainModifiedCookieBuilder(CookieBuilder cookieBuilder, Chan chan, Uri uri,
			FirewallResolver.Identifier resolverIdentifier) {
		CookieBuilder appendCookieBuilder = FirewallResolver.Implementation
				.getInstance().collectCookies(chan, uri, resolverIdentifier, false);
		if (!appendCookieBuilder.isEmpty()) {
			cookieBuilder = new CookieBuilder(cookieBuilder);
			cookieBuilder.append(appendCookieBuilder);
		}
		return cookieBuilder;
	}

	Uri obtainRedirectedUri(Uri requestedUri, String locationHeader) {
		Uri redirectedUri;
		if (!StringUtils.isEmpty(locationHeader)) {
			redirectedUri = Uri.parse(locationHeader);
			if (redirectedUri.isRelative()) {
				Uri.Builder builder = redirectedUri.buildUpon().scheme(requestedUri.getScheme())
						.authority(requestedUri.getAuthority());
				String redirectedPath = StringUtils.emptyIfNull(redirectedUri.getPath());
				if (!redirectedPath.isEmpty() && !redirectedPath.startsWith("/")) {
					String path = StringUtils.emptyIfNull(requestedUri.getPath());
					if (!path.endsWith("/")) {
						int index = path.lastIndexOf('/');
						if (index >= 0) {
							path = path.substring(0, index + 1);
						} else {
							path = "/";
						}
					}
					path += redirectedPath;
					builder.path(path);
				}
				redirectedUri = builder.build();
			}
		} else {
			redirectedUri = requestedUri;
		}
		return redirectedUri;
	}

	static String extractCharsetName(String contentType) {
		if ("application/json".equals(contentType)) {
			// Assume UTF-8 https://tools.ietf.org/html/rfc4627#section-3
			return "UTF-8";
		}
		if (contentType != null) {
			int index = contentType.indexOf("charset=");
			if (index >= 0) {
				int end = contentType.indexOf(';', index);
				String charsetName = contentType.substring(index + 8, end >= 0 ? end : contentType.length());
				try {
					Charset.forName(charsetName);
					return charsetName;
				} catch (UnsupportedCharsetException e) {
					// Ignore
				}
			}
		}
		return null;
	}

	static String transformResponseMessage(String originalMessage) {
		String message = SHORT_RESPONSE_MESSAGES.get(originalMessage);
		return message != null ? message : originalMessage;
	}

	public static HttpException transformIOException(IOException exception) {
		ErrorItem.Type errorType;
		try {
			errorType = getErrorTypeForException(exception);
			exception.printStackTrace();
		} catch (InterruptedIOException e) {
			errorType = null;
		}
		if (errorType != null) {
			return new HttpException(errorType, false, true, exception);
		} else {
			return new HttpException(ErrorItem.Type.DOWNLOAD, false, true, exception);
		}
	}

	private static ErrorItem.Type getErrorTypeForException(IOException exception) throws InterruptedIOException {
		if (isConnectionReset(exception)) {
			return ErrorItem.Type.CONNECTION_RESET;
		}
		String message = exception.getMessage();
		if (message != null) {
			if (message.contains("thread interrupted") && exception instanceof InterruptedIOException) {
				throw (InterruptedIOException) exception;
			}
			if (message.contains("Failed to connect to") ||
					message.contains("failed to connect to")) {
				return ErrorItem.Type.CONNECT_TIMEOUT;
			}
			if (message.contains("SSL handshake timed out")) {
				return ErrorItem.Type.CONNECT_TIMEOUT;
			}
			if (message.startsWith("Hostname ") && message.endsWith(" not verified")) {
				return ErrorItem.Type.INVALID_CERTIFICATE;
			}
			if (message.contains("Could not validate certificate") ||
					message.contains("Trust anchor for certification path not found")) {
				return ErrorItem.Type.INVALID_CERTIFICATE;
			}
		}
		if (exception instanceof SSLException) {
			return ErrorItem.Type.SSL;
		}
		if (exception instanceof SocketTimeoutException) {
			String timeoutMessage = exception.getMessage();
			if (timeoutMessage != null && timeoutMessage.toLowerCase(Locale.US).contains("connect")) {
				return ErrorItem.Type.CONNECT_TIMEOUT;
			}
			return ErrorItem.Type.READ_TIMEOUT;
		}
		return null;
	}

	private static boolean isConnectionReset(IOException exception) {
		if (exception instanceof EOFException) {
			return true;
		}
		String message = exception.getMessage();
		return message != null && (message.contains("Connection reset by peer")
				|| message.contains("Connection closed by peer") || message.contains("unexpected end of stream")
				|| message.contains("Connection refused"));
	}

	private static void checkInterruptedAndClose(HttpSession session,
			Closeable closeable) throws InterruptedHttpException {
		try {
			session.holder.checkInterrupted();
		} catch (InterruptedHttpException e) {
			IOUtils.close(closeable);
			throw e;
		}
	}

	private static class DeflateInputStream extends InputStream {
		private final InputStream input;
		private InputStream workInput;

		public DeflateInputStream(InputStream input) {
			this.input = input;
		}

		public static InputStream createWorkInput(InputStream input) throws IOException {
			// Check zlib header and create a proper Inflater
			ByteArrayOutputStream[] output = {new ByteArrayOutputStream()};
			InputStream workInput = new InflaterInputStream(new InputStream() {
				@Override
				public int read() throws IOException {
					int result = input.read();
					if (result >= 0 && output[0] != null) {
						output[0].write(result);
					}
					return result;
				}

				@Override
				public int read(byte[] b, int off, int len) throws IOException {
					int result = input.read(b, off, len);
					if (result > 0 && output[0] != null) {
						output[0].write(b, off, result);
					}
					return result;
				}
			}, new Inflater(false));
			boolean success = false;
			int firstByte = -1;
			try {
				firstByte = workInput.read();
				success = true;
			} catch (ZipException e) {
				// Ignore exception
			}
			if (success) {
				output[0] = null;
				if (firstByte >= 0) {
					return new SequenceInputStream(new ByteArrayInputStream(new byte[] {(byte) firstByte}), workInput);
				} else {
					workInput.close();
					return new ByteArrayInputStream(new byte[0]);
				}
			} else {
				workInput.close();
				byte[] head = output[0].toByteArray();
				InputStream newInput = new SequenceInputStream(new ByteArrayInputStream(head), input);
				return new InflaterInputStream(newInput, new Inflater(true));
			}
		}

		private void ensureWorkInput() throws IOException {
			if (workInput == null) {
				workInput = createWorkInput(input);
			}
		}

		@Override
		public int read() throws IOException {
			ensureWorkInput();
			return workInput.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			ensureWorkInput();
			return workInput.read(b, off, len);
		}

		@Override
		public void close() throws IOException {
			input.close();
		}
	}

	private static class ClientInputStream extends InputStream {
		private final InputStream input;
		private final HttpSession session;

		public ClientInputStream(InputStream input, HttpSession session) {
			this.input = input;
			this.session = session;
		}

		@Override
		public int read() throws IOException {
			checkInterruptedAndClose(session, this);
			return input.read();
		}

		@Override
		public int read(@NonNull byte[] b) throws IOException {
			return read(b, 0, b.length);
		}

		@Override
		public int read(@NonNull byte[] b, int off, int len) throws IOException {
			checkInterruptedAndClose(session, this);
			return input.read(b, off, len);
		}

		@Override
		public long skip(long n) throws IOException {
			checkInterruptedAndClose(session, this);
			return input.skip(n);
		}

		@Override
		public int available() throws IOException {
			checkInterruptedAndClose(session, this);
			return input.available();
		}

		@Override
		public void close() throws IOException {
			try {
				input.close();
			} finally {
				if (session != null) {
					boolean validThread = false;
					try {
						session.checkThread();
						validThread = true;
					} catch (Exception e) {
						// Ignore
					}
					if (validThread && session.response != null) {
						session.response.cleanupAndDisconnectIfEquals(this);
					}
				}
			}
		}

		@Override
		public void mark(int readlimit) {
			input.mark(readlimit);
		}

		@Override
		public boolean markSupported() {
			return input.markSupported();
		}

		@Override
		public void reset() throws IOException {
			input.reset();
		}
	}

	private static class ClientOutputStream extends OutputStream {
		private final OutputStream output;
		private final HttpSession session;

		private final HttpRequest.OutputListener listener;
		private final long contentLength;

		private final AtomicLong progress = new AtomicLong();

		public ClientOutputStream(OutputStream output, HttpSession session,
				HttpRequest.OutputListener listener, long contentLength) {
			this.output = output;
			this.session = session;
			this.listener = contentLength > 0 ? listener : null;
			this.contentLength = contentLength;
			if (this.listener != null) {
				this.listener.onOutputProgressChange(0, contentLength);
			}
		}

		@Override
		public void write(int oneByte) throws IOException {
			checkInterruptedAndClose(session, this);
			output.write(oneByte);
			updateProgress(1);
		}

		@Override
		public void write(@NonNull byte[] buffer) throws IOException {
			checkInterruptedAndClose(session, this);
			output.write(buffer);
			updateProgress(buffer.length);
		}

		@Override
		public void write(@NonNull byte[] buffer, int offset, int length) throws IOException {
			checkInterruptedAndClose(session, this);
			output.write(buffer, offset, length);
			updateProgress(length);
		}

		private void updateProgress(long value) {
			if (listener != null && value > 0) {
				long progress = this.progress.addAndGet(value);
				listener.onOutputProgressChange(progress, contentLength);
			}
		}

		@Override
		public void close() throws IOException {
			flush();
		}

		@Override
		public void flush() throws IOException {
			checkInterruptedAndClose(session, this);
			output.flush();
		}
	}

	private final HashMap<String, Call> singleConnections = new HashMap<>();
	private final HashMap<Call, String> singleConnectionIdentifiers = new HashMap<>();

	private final HashMap<String, AtomicBoolean> delayLocks = new HashMap<>();

	// Called from HttpSession
	void onConnect(Chan chan, Call call, int delay) throws InterruptedHttpException {
		if (AdvancedPreferences.isSingleConnection(chan.name)) {
			synchronized (singleConnections) {
				while (singleConnections.containsKey(chan.name)) {
					try {
						singleConnections.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new InterruptedHttpException();
					}
				}
				singleConnections.put(chan.name, call);
				singleConnectionIdentifiers.put(call, chan.name);
			}
		}
		if (delay > 0) {
			String key = call.request().url().host() + ":" + call.request().url().port();
			AtomicBoolean delayLock;
			synchronized (delayLocks) {
				delayLock = delayLocks.get(key);
				if (delayLock == null) {
					delayLock = new AtomicBoolean(false);
					delayLocks.put(key, delayLock);
				}
			}
			synchronized (delayLock) {
				try {
					while (delayLock.get()) {
						delayLock.wait();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				delayLock.set(true);
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					delayLock.set(false);
					delayLock.notifyAll();
				}
			}
		}
	}

	// Called from HttpSession
	void onDisconnect(Call call) {
		synchronized (singleConnections) {
			String chanName = singleConnectionIdentifiers.remove(call);
			if (chanName != null) {
				if (call == singleConnections.get(chanName)) {
					singleConnections.remove(chanName);
					singleConnections.notifyAll();
				}
			}
		}
	}
}
