package chan.http

import android.net.Uri
import chan.annotation.Extendable
import chan.annotation.Public
import chan.content.Chan
import chan.content.ChanConfiguration
import com.mishiranu.dashchan.content.net.firewall.FirewallResolvers
import java.util.Objects
import kotlin.concurrent.Volatile

@Extendable
abstract class FirewallResolver {
    class CheckResult(val resolved: Boolean, val retransmitOnSuccess: Boolean)

    abstract class Implementation {
        @Throws(HttpException::class, InterruptedException::class)
        abstract fun checkResponse(
            chan: Chan,
            uri: Uri,
            holder: HttpHolder,
            response: HttpResponse?,
            identifier: Identifier,
            resolve: Boolean
        ): CheckResult?

        abstract fun collectCookies(
            chan: Chan,
            uri: Uri?,
            identifier: Identifier,
            safe: Boolean
        ): CookieBuilder

        companion object {
            private val INSTANCE: Implementation = FirewallResolvers()

            @JvmStatic
            fun getInstance(): Implementation = INSTANCE
        }
    }

    @Public
    class Identifier(
        @JvmField @field:Public val userAgent: String?,
        @JvmField @field:Public val defaultUserAgent: Boolean,
        @JvmField val host: String?
    ) {
        @Public
        enum class Flag {
            @Public
            USER_AGENT,
            HOST
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) return true
            if (o == null || javaClass != o.javaClass) return false
            val that = o as Identifier
            return defaultUserAgent == that.defaultUserAgent && userAgent == that.userAgent && host == that.host
        }

        override fun hashCode(): Int {
            return Objects.hash(userAgent, defaultUserAgent, host)
        }
    }

    @Extendable
    abstract class WebViewClient<Result> @Public constructor(@JvmField val name: String?) {
        @Volatile
        internal var result: Result? = null

        @Public
        fun setResult(result: Result?) {
            this.result = result
        }

        fun getResult(): Result? {
            return result
        }

        @Extendable
        open fun onPageFinished(
            uri: Uri,
            cookies: Map<String, String>,
            title: String?
        ): Boolean {
            return true
        }

        @Extendable
        open fun onLoad(initialUri: Uri, uri: Uri): Boolean {
            return true
        }
    }

    @Public
    interface Session : HttpRequest.Preset {
        @Public
        fun getUri(): Uri?

        override val holder: HttpHolder?

        val chan: Chan?

        @Public
        fun getChanConfiguration(): ChanConfiguration?

        @Public
        fun getIdentifier(): Identifier?

        @Public
        fun getKey(vararg flags: Identifier.Flag): Exclusive.Key?

        @Public
        fun isResolveRequest(): Boolean

        @Public
        @Throws(CancelException::class, InterruptedException::class)
        fun <Result : Any> resolveWebView(webViewClient: WebViewClient<Result>): Result?
    }

    @Public
    class CancelException : Exception()

    @Extendable
    fun interface Exclusive {
        @Public
        interface Key {
            @Public
            fun formatKey(value: String?): String?

            @Public
            fun formatTitle(value: String?): String?
        }

        @Extendable
        @Throws(CancelException::class, HttpException::class, InterruptedException::class)
        fun resolve(session: Session, key: Key): Boolean

        companion object {
            val FAIL: Exclusive =
                Exclusive { session: Session, key: Key -> false }
        }
    }

    @Public
    class CheckResponseResult @Public constructor(
        @JvmField val key: Exclusive.Key?,
        @JvmField val exclusive: Exclusive?
    ) {
        @JvmField
        var retransmitOnSuccess: Boolean = false

        @Public
        fun setRetransmitOnSuccess(retransmitOnSuccess: Boolean): CheckResponseResult {
            this.retransmitOnSuccess = retransmitOnSuccess
            return this
        }
    }

    @Extendable
    @Throws(HttpException::class)
    abstract fun checkResponse(session: Session, response: HttpResponse): CheckResponseResult?

    @Extendable
    open fun collectCookies(session: Session, cookieBuilder: CookieBuilder) {
    }
}
