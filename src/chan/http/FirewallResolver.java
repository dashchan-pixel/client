package chan.http;

import android.net.Uri;
import chan.annotation.Extendable;
import chan.annotation.Public;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.content.net.firewall.FirewallResolvers;
import java.util.Map;
import java.util.Objects;

@Extendable
public abstract class FirewallResolver {
	public static class CheckResult {
		public final boolean resolved;
		public final boolean retransmitOnSuccess;

		public CheckResult(boolean resolved, boolean retransmitOnSuccess) {
			this.resolved = resolved;
			this.retransmitOnSuccess = retransmitOnSuccess;
		}
	}

	public static abstract class Implementation {
		@SuppressWarnings("StaticInitializerReferencesSubClass")
		private static final Implementation INSTANCE = new FirewallResolvers();

		public static Implementation getInstance() {
			return INSTANCE;
		}

		public abstract CheckResult checkResponse(Chan chan, Uri uri, HttpHolder holder, HttpResponse response,
				Identifier identifier, boolean resolve) throws HttpException, InterruptedException;

		public abstract CookieBuilder collectCookies(Chan chan, Uri uri, Identifier identifier, boolean safe);
	}

	@Public
	public static final class Identifier {
		@Public
		public enum Flag {
			@Public USER_AGENT,
			HOST
		}

		@Public public final String userAgent;
		@Public public final boolean defaultUserAgent;
		public final String host;

		public Identifier(String userAgent, boolean defaultUserAgent, String host) {
			this.userAgent = userAgent;
			this.defaultUserAgent = defaultUserAgent;
			this.host = host;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Identifier that = (Identifier) o;
			return defaultUserAgent == that.defaultUserAgent && Objects.equals(userAgent, that.userAgent) && Objects.equals(host, that.host);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userAgent, defaultUserAgent, host);
		}
	}

	@Extendable
	public static abstract class WebViewClient<Result> {
		private final String name;
		private volatile Result result;

		@Public
		public WebViewClient(String name) {
			this.name = name;
		}

		public final String getName() {
			return name;
		}

		@Public
		public final void setResult(Result result) {
			this.result = result;
		}

		public final Result getResult() {
			return result;
		}

		@Extendable
		public boolean onPageFinished(Uri uri, Map<String, String> cookies, String title) {
			return true;
		}

		@Extendable
		public boolean onLoad(Uri initialUri, Uri uri) {
			return true;
		}
	}

	@Public
	public interface Session extends HttpRequest.Preset {
		@Public
		Uri getUri();

		@Override
		HttpHolder getHolder();

		Chan getChan();

		@Public
		ChanConfiguration getChanConfiguration();

		@Public
		Identifier getIdentifier();

		@Public
		Exclusive.Key getKey(Identifier.Flag... flags);

		@Public
		boolean isResolveRequest();

		@Public
		<Result> Result resolveWebView(WebViewClient<Result> webViewClient)
				throws CancelException, InterruptedException;
	}

	@Public
	public static final class CancelException extends Exception {}

	@Extendable
	public interface Exclusive {
		Exclusive FAIL = (session, key) -> false;

		@Public
		interface Key {
			@Public
			String formatKey(String value);

			@Public
			String formatTitle(String value);
		}

		@Extendable
		boolean resolve(Session session, Key key) throws CancelException, HttpException, InterruptedException;
	}

	@Public
	public static class CheckResponseResult {
		public final Exclusive.Key key;
		public final Exclusive exclusive;

		public boolean retransmitOnSuccess;

		@Public
		public CheckResponseResult(Exclusive.Key key, Exclusive exclusive) {
			this.key = key;
			this.exclusive = exclusive;
		}

		@Public
		public CheckResponseResult setRetransmitOnSuccess(boolean retransmitOnSuccess) {
			this.retransmitOnSuccess = retransmitOnSuccess;
			return this;
		}
	}

	@Extendable
	public abstract CheckResponseResult checkResponse(Session session, HttpResponse response) throws HttpException;

	@Extendable
	public void collectCookies(Session session, CookieBuilder cookieBuilder) {}
}
