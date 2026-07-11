package chan.http

import android.net.Uri
import chan.content.Chan
import chan.http.HttpClient.InterruptedHttpException
import chan.http.HttpHolder
import java.io.Closeable
import java.net.Proxy
import kotlin.concurrent.Volatile

class HttpHolder(val chan: Chan?) {
    fun interface Use : Closeable {
        override fun close()
    }

    private var thread: Thread? = null
    private var session: HttpSession? = null
    private var sessions: ArrayList<HttpSession?>? = null

    var mayResolveFirewallBlock: Boolean = true

    fun checkThread() {
        synchronized(this) {
            check(thread === Thread.currentThread()) { "This action is allowed from the initial thread only" }
        }
    }

    fun use(): Use {
        // Lock for concurrent "thread" variable access
        synchronized(this) {
            if (thread != null) {
                checkThread()
                if (sessions == null) {
                    sessions = ArrayList<HttpSession?>()
                }
                sessions!!.add(session)
                if (session != null) {
                    session!!.disconnectAndClear()
                }
                session = null
                return Use {
                    releaseSession()
                    session = sessions!!.removeAt(sessions!!.size - 1)
                }
            } else {
                thread = Thread.currentThread()
                return Use { this.releaseSession() }
            }
        }
    }

    fun createSession(
        client: HttpClient?, uri: Uri?, proxy: Proxy?,
        verifyCertificate: Boolean, delay: Int, maxAttempts: Int
    ): HttpSession {
        checkThread()
        if (session != null) {
            session!!.disconnectAndClear()
        }
        val mayCheckFirewallBlock = sessions == null || sessions!!.isEmpty()
        session = HttpSession(
            this, client!!, uri, proxy,
            verifyCertificate, mayCheckFirewallBlock, delay, maxAttempts
        )
        return session!!
    }

    private fun releaseSession() {
        checkThread()
        if (session != null) {
            session!!.disconnectAndClear()
        }
    }

    @Volatile
    var isInterrupted: Boolean = false
        private set

    interface Callback {
        fun onDisconnectRequested()
    }

    fun interrupt() {
        this.isInterrupted = true
    }

    @Throws(InterruptedHttpException::class)
    fun checkInterrupted() {
        if (this.isInterrupted) {
            throw InterruptedHttpException()
        }
    }

    fun extractValidator(): HttpValidator? {
        checkThread()
        return session?.response?.getValidator()
    }
}
