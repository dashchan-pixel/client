package chan.http;

import android.net.Uri;
import chan.content.Chan;
import com.mishiranu.dashchan.content.model.ErrorItem;
import java.io.Closeable;
import java.net.Proxy;
import java.util.ArrayList;

public final class HttpHolder {
	public interface Use extends Closeable {
		void close();
	}

	private Thread thread;
	private HttpSession session;
	private ArrayList<HttpSession> sessions;

	final Chan chan;

	boolean mayResolveFirewallBlock = true;

	public HttpHolder(Chan chan) {
		this.chan = chan;
	}

	void checkThread() {
		synchronized (this) {
			if (thread != Thread.currentThread()) {
				throw new IllegalStateException("This action is allowed from the initial thread only");
			}
		}
	}

	public Use use() {
		// Lock for concurrent "thread" variable access
		synchronized (this) {
			if (thread != null) {
				checkThread();
				if (sessions == null) {
					sessions = new ArrayList<>();
				}
				sessions.add(session);
				if (session != null) {
					session.disconnectAndClear();
				}
				session = null;
				return () -> {
					releaseSession();
					session = sessions.remove(sessions.size() - 1);
				};
			} else {
				thread = Thread.currentThread();
				return this::releaseSession;
			}
		}
	}

	HttpSession createSession(HttpClient client, Uri uri, Proxy proxy,
			boolean verifyCertificate, int delay, int maxAttempts) {
		checkThread();
		if (session != null) {
			session.disconnectAndClear();
		}
		boolean mayCheckFirewallBlock = sessions == null || sessions.isEmpty();
		session = new HttpSession(this, client, uri, proxy,
				verifyCertificate, mayCheckFirewallBlock, delay, maxAttempts);
		return session;
	}

	private void releaseSession() {
		checkThread();
		if (session != null) {
			session.disconnectAndClear();
		}
	}

	private volatile boolean interrupted = false;

	public interface Callback {
		void onDisconnectRequested();
	}

	public void interrupt() {
		interrupted = true;
	}

	boolean isInterrupted() {
		return interrupted;
	}

	void checkInterrupted() throws HttpClient.InterruptedHttpException {
		if (isInterrupted()) {
			throw new HttpClient.InterruptedHttpException();
		}
	}

	public HttpValidator extractValidator() {
		checkThread();
		return session != null && session.response != null ? session.response.getValidator() : null;
	}
}
