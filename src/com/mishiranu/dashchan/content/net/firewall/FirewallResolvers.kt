package com.mishiranu.dashchan.content.net.firewall

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import chan.content.Chan
import chan.content.Chan.Companion.getFallback
import chan.content.ChanConfiguration
import chan.content.ChanPerformer
import chan.content.ChanPerformer.CaptchaData
import chan.content.ChanPerformer.ReadCaptchaData
import chan.content.ChanPerformer.ReadCaptchaResult
import chan.content.ExtensionException.Companion.logException
import chan.http.CookieBuilder
import chan.http.FirewallResolver
import chan.http.FirewallResolver.CheckResponseResult
import chan.http.HttpClient
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpResponse
import chan.util.StringUtils.formatHex
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences.FirewallResolutionMethod
import com.mishiranu.dashchan.content.Preferences.firewallResolutionMethod
import com.mishiranu.dashchan.content.Preferences.isRecaptchaJavascript
import com.mishiranu.dashchan.content.Preferences.isVerifyCertificate
import com.mishiranu.dashchan.content.async.ReadCaptchaTask.CaptchaReader
import com.mishiranu.dashchan.content.async.ReadCaptchaTask.RemoteResult
import com.mishiranu.dashchan.content.net.RecaptchaReader
import com.mishiranu.dashchan.content.net.RecaptchaReader.ChallengeExtra
import com.mishiranu.dashchan.content.net.firewall.FirewallUtils.parseCookies
import com.mishiranu.dashchan.content.service.webview.IRequestCallback
import com.mishiranu.dashchan.content.service.webview.IWebViewService
import com.mishiranu.dashchan.content.service.webview.WebViewExtra
import com.mishiranu.dashchan.content.service.webview.WebViewService
import com.mishiranu.dashchan.ui.ForegroundManager
import com.mishiranu.dashchan.util.Hasher.Companion.getInstanceSha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Arrays
import java.util.UUID
import kotlin.math.min

class FirewallResolvers : FirewallResolver.Implementation() {
    private class Key(private val hash: String?) : FirewallResolver.Exclusive.Key {
        class Generator {
            private var output: ByteArrayOutputStream? = null
            private var writer: OutputStreamWriter? = null

            fun append(key: String?, value: String?) {
                try {
                    if (output == null) {
                        output = ByteArrayOutputStream()
                        writer = OutputStreamWriter(output, "UTF-8")
                    }
                    writer!!.write(key)
                    writer!!.write('='.code)
                    if (value != null) {
                        writer!!.write(value)
                    }
                    writer!!.flush()
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            }

            fun generate(): String? {
                if (output != null) {
                    val bytes = output!!.toByteArray()
                    if (bytes.size > 0) {
                        return formatHex(getInstanceSha256().calculate(bytes))
                    }
                }
                return null
            }
        }

        override fun formatKey(value: String?): String? {
            return if (hash != null) value + "_" + hash else value
        }

        override fun formatTitle(value: String?): String? {
            return if (hash != null) value + " #" + hash.substring(
                0,
                min(8, hash.length)
            ) else value
        }
    }

    private abstract class BaseSession(
        private val uri: Uri?,
        override val chan: Chan,
        private val identifier: FirewallResolver.Identifier
    ) : FirewallResolver.Session {
        override fun getUri(): Uri? {
            return uri
        }

        override fun getChanConfiguration(): ChanConfiguration? {
            return chan.configuration
        }

        override fun getIdentifier(): FirewallResolver.Identifier {
            return identifier
        }

        override fun getKey(vararg flags: FirewallResolver.Identifier.Flag): FirewallResolver.Exclusive.Key? {
            val identifier = this.identifier
            val generator = Key.Generator()
            for (flag in flags) {
                when (flag) {
                    FirewallResolver.Identifier.Flag.USER_AGENT -> {
                        if (!identifier.defaultUserAgent) {
                            generator.append("user_agent", identifier.userAgent)
                        }
                    }

                    FirewallResolver.Identifier.Flag.HOST -> generator.append(
                        "host",
                        identifier.host
                    )

                    else -> {
                        throw IllegalArgumentException()
                    }
                }
            }
            return Key(generator.generate())
        }
    }

    private inner class CheckSession(
        uri: Uri?,
        internal val holder: HttpHolder,
        chan: Chan,
        identifier: FirewallResolver.Identifier,
        internal val resolve: Boolean,
        private val exclusive: Boolean
    ) : BaseSession(uri, chan, identifier) {
        override fun getHolder(): HttpHolder {
            if (exclusive) {
                return holder
            } else {
                throw IllegalStateException()
            }
        }

        override fun isResolveRequest(): Boolean {
            return resolve
        }

        @Throws(FirewallResolver.CancelException::class, InterruptedException::class)
        override fun <Result : Any> resolveWebView(webViewClient: FirewallResolver.WebViewClient<Result>): Result? {
            if (exclusive) {
                return this@FirewallResolvers.resolveWebView(this, webViewClient)
            } else {
                throw IllegalStateException()
            }
        }
    }

    private class CookieSession(uri: Uri?, chan: Chan, identifier: FirewallResolver.Identifier) :
        BaseSession(uri, chan, identifier) {
        public override fun getUri(): Uri? {
            throw IllegalStateException()
        }

        override fun getHolder(): HttpHolder? {
            throw IllegalStateException()
        }

        override fun isResolveRequest(): Boolean {
            return false
        }

        override fun <Result : Any> resolveWebView(webViewClient: FirewallResolver.WebViewClient<Result>): Result? {
            throw IllegalStateException()
        }
    }

    private class CheckHolder {
        var ready: Boolean = false
        var success: Boolean = false
    }

    private class FirewallResolverCaptchaReader(
        private val apiKey: String?, private val referer: String?,
        private val challengeExtra: Any?, private val allowSolveAutomatically: Boolean
    ) : CaptchaReader {
        override fun onReadCaptcha(data: ReadCaptchaData): RemoteResult {
            val captchaData = CaptchaData()
            captchaData.put(CaptchaData.API_KEY, apiKey)
            captchaData.put(CaptchaData.REFERER, referer)
            return RemoteResult(
                ReadCaptchaResult(ChanPerformer.CaptchaState.CAPTCHA, captchaData),
                challengeExtra,
                allowSolveAutomatically
            )
        }
    }

    private class WebViewRequestCallback(
        val client: FirewallResolver.WebViewClient<*>,
        val initialUri: Uri?, val chanTitle: String?, val cancel: Runnable
    ) : IRequestCallback.Stub() {
        override fun onPageFinished(uriString: String?, cookie: String?, title: String?): Boolean {
            var uri: Uri?
            try {
                uri = Uri.parse(uriString)
            } catch (e: Exception) {
                uri = null
            }
            if (uri == null) {
                return true
            }
            val cookies = parseCookies(cookie)
            try {
                return client.onPageFinished(uri, cookies, title)
            } catch (e: LinkageError) {
                e.printStackTrace()
                return true
            } catch (e: RuntimeException) {
                e.printStackTrace()
                return true
            }
        }

        override fun onLoad(uriString: String?): Boolean {
            var uri: Uri?
            try {
                uri = Uri.parse(uriString)
            } catch (e: Exception) {
                uri = null
            }
            try {
                return client.onLoad(initialUri!!, uri ?: return false)
            } catch (e: LinkageError) {
                e.printStackTrace()
                return false
            } catch (e: RuntimeException) {
                e.printStackTrace()
                return false
            }
        }

        var isRetry: Boolean = false
            get() {
                try {
                    return field
                } finally {
                    field = true
                }
            }
            private set

        private var interrupted = false
        private var requireThread: Thread? = null

        fun interrupt() {
            synchronized(this) {
                interrupted = true
                if (requireThread != null) {
                    requireThread!!.interrupt()
                }
            }
        }

        fun requireUserCaptcha(
            captchaType: String?, apiKey: String?, referer: String?,
            challengeExtra: Any?, allowSolveAutomatically: Boolean, retry: Boolean
        ): String? {
            val description = MainApplication.getInstance().localizedContext.getString(
                R.string.firewall_block__format_sentence,
                client.name + " (" + chanTitle + ")"
            )
            val reader = FirewallResolverCaptchaReader(
                apiKey, referer,
                challengeExtra, allowSolveAutomatically
            )
            var captchaData: CaptchaData?
            synchronized(this) {
                if (interrupted) {
                    return null
                }
                requireThread = Thread.currentThread()
            }
            try {
                captchaData = ForegroundManager.getInstance().requireUserCaptcha(
                    reader,
                    captchaType, null, null, null, null, description, retry
                )
            } catch (e: InterruptedException) {
                return null
            } finally {
                synchronized(this) {
                    if (requireThread === Thread.currentThread()) {
                        requireThread = null
                        // Clear interrupted state
                        Thread.interrupted()
                    }
                }
            }
            if (captchaData == null) {
                cancel.run()
            }
            return if (captchaData != null) captchaData.get(CaptchaData.INPUT) else null
        }

        override fun onRecaptchaV2(apiKey: String?, invisible: Boolean, referer: String?): String? {
            val retry = this.isRetry
            val allowSolveAutomatically = !retry
            val captchaType = if (invisible)
                ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2_INVISIBLE
            else
                ChanConfiguration.CAPTCHA_TYPE_RECAPTCHA_2
            val challengeExtra: ChallengeExtra?
            val holder = HttpHolder(getFallback())
            try {
                holder.use().use { ignored ->
                    challengeExtra = RecaptchaReader.getInstance().getChallenge2(
                        holder,
                        apiKey!!, invisible, referer, isRecaptchaJavascript,
                        true, allowSolveAutomatically
                    )
                }
            } catch (e: RecaptchaReader.CancelException) {
                return null
            } catch (e: HttpException) {
                return null
            }
            try {
                if (challengeExtra != null && challengeExtra.response != null) {
                    return challengeExtra.response
                }
                return requireUserCaptcha(
                    captchaType, apiKey, referer, challengeExtra,
                    allowSolveAutomatically, retry
                )
            } finally {
                if (challengeExtra != null) {
                    challengeExtra.cleanup()
                }
            }
        }

        override fun onHcaptcha(apiKey: String?, referer: String?): String? {
            val retry = this.isRetry
            val allowSolveAutomatically = !retry
            val challengeExtra: ChallengeExtra?
            val holder = HttpHolder(getFallback())
            try {
                holder.use().use { ignored ->
                    challengeExtra = RecaptchaReader.getInstance().getChallengeHcaptcha(
                        holder,
                        apiKey!!, referer, true, allowSolveAutomatically
                    )
                }
            } catch (e: RecaptchaReader.CancelException) {
                return null
            } catch (e: HttpException) {
                return null
            }
            try {
                if (challengeExtra != null && challengeExtra.response != null) {
                    return challengeExtra.response
                }
                return requireUserCaptcha(
                    ChanConfiguration.CAPTCHA_TYPE_HCAPTCHA,
                    apiKey, referer, challengeExtra, allowSolveAutomatically, retry
                )
            } finally {
                if (challengeExtra != null) {
                    challengeExtra.cleanup()
                }
            }
        }
    }

    abstract class WebViewClientWithExtra<Result>(name: String?, internal val extra: WebViewExtra?) :
        FirewallResolver.WebViewClient<Result>(name)

    @Throws(FirewallResolver.CancelException::class, InterruptedException::class)
    private fun <T : Any> resolveWebView(
        session: FirewallResolver.Session,
        client: FirewallResolver.WebViewClient<T>
    ): T? {
        val initialUri = session.getUri()!!.buildUpon().clearQuery().encodedFragment(null).build()
        val chan: Chan = session.chan!!
        val userAgent = session.getIdentifier()!!.userAgent
        val proxyData: HttpClient.ProxyData? = HttpClient.getInstance().getProxyData(chan)
        val firewallResolutionMethod = firewallResolutionMethod
        var firewallResolutionResult: T? = null
        when (firewallResolutionMethod) {
            FirewallResolutionMethod.MANUAL -> {
                firewallResolutionResult =
                    resolveWebViewForeground(initialUri, userAgent, proxyData, client)
            }

            FirewallResolutionMethod.AUTO -> {
                firewallResolutionResult =
                    resolveWebViewBackground(initialUri, userAgent, proxyData, client, chan)
            }

            FirewallResolutionMethod.AUTO_THEN_MANUAL -> {
                firewallResolutionResult =
                    resolveWebViewBackground(initialUri, userAgent, proxyData, client, chan)
                if (firewallResolutionResult == null) {
                    firewallResolutionResult =
                        resolveWebViewForeground(initialUri, userAgent, proxyData, client)
                }
            }

            else -> {}
        }
        return firewallResolutionResult
    }

    @Throws(InterruptedException::class)
    private fun <T : Any> resolveWebViewForeground(
        initialUri: Uri,
        userAgent: String?,
        proxyData: HttpClient.ProxyData?,
        client: FirewallResolver.WebViewClient<T>
    ): T? {
        val firewallResolutionDialogRequest =
            FirewallResolutionDialogRequest(initialUri.toString(), userAgent, proxyData, client)
        return ForegroundManager.getInstance()
            .requireUserResolveFirewall(firewallResolutionDialogRequest)
    }

    @Throws(FirewallResolver.CancelException::class, InterruptedException::class)
    private fun <T : Any> resolveWebViewBackground(
        initialUri: Uri,
        userAgent: String?,
        proxyData: HttpClient.ProxyData?,
        client: FirewallResolver.WebViewClient<T>,
        chan: Chan
    ): T? {
        val context: Context = MainApplication.getInstance()

        class Status {
            var established: Boolean = false
            var service: IWebViewService? = null
            var cancel: Boolean = false
        }

        val status = Status()
        val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                synchronized(status) {
                    status.established = true
                    status.service = IWebViewService.Stub.asInterface(binder)
                    (status as Object).notifyAll()
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                synchronized(status) {
                    status.established = true
                    status.service = null
                    (status as Object).notifyAll()
                }
            }
        }

        try {
            context.bindService(
                Intent(context, WebViewService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            val service: IWebViewService?
            synchronized(status) {
                val startTime = SystemClock.elapsedRealtime()
                val waitTime: Long = 10000
                while (!status.established) {
                    val time = waitTime - (SystemClock.elapsedRealtime() - startTime)
                    if (time <= 0) {
                        break
                    }
                    (status as Object).wait(time)
                }
                service = status.service
            }
            if (service != null) {
                val chanTitle = chan.configuration.getTitle()
                val finished = booleanArrayOf(false)
                val verifyCertificate = chan.locator.isUseHttps() && isVerifyCertificate
                val requestCallback = WebViewRequestCallback(
                    client, initialUri, chanTitle,
                    Runnable { status.cancel = true })
                val requestId = UUID.randomUUID().toString()
                val blockingCallThread = Thread(Runnable {
                    val extra = if (client is WebViewClientWithExtra<*>)
                        (client as WebViewClientWithExtra<*>).extra
                    else
                        null
                    try {
                        val result = service.loadWithCookieResult(
                            requestId,
                            initialUri.toString(),
                            userAgent,
                            proxyData != null && proxyData.socks,
                            if (proxyData != null) proxyData.host else null,
                            if (proxyData != null) proxyData.port else 0,
                            verifyCertificate,
                            WEB_VIEW_TIMEOUT.toLong(),
                            extra,
                            requestCallback
                        )
                        synchronized(finished) {
                            finished[0] = result
                        }
                    } catch (e: RemoteException) {
                        e.printStackTrace()
                    }
                })
                blockingCallThread.start()
                try {
                    blockingCallThread.join()
                } catch (e: InterruptedException) {
                    try {
                        service.interrupt(requestId)
                    } catch (e1: RemoteException) {
                        e1.printStackTrace()
                    }
                    requestCallback.interrupt()
                    throw e
                }
                synchronized(finished) {
                    if (!finished[0]) {
                        return null
                    }
                }
                val result = client.result
                if (result != null) {
                    return result
                }
                if (status.cancel) {
                    throw FirewallResolver.CancelException()
                }
            }
            return null
        } finally {
            context.unbindService(connection)
        }
    }

    private val checkHolders: HashMap<FirewallResolver.Exclusive.Key?, CheckHolder?> =
        HashMap<FirewallResolver.Exclusive.Key?, CheckHolder?>()
    private val lastCheckCancel = HashMap<FirewallResolver.Exclusive.Key?, Long?>()

    @Throws(HttpException::class, InterruptedException::class)
    private fun runExclusive(
        session: CheckSession, key: FirewallResolver.Exclusive.Key?,
        exclusive: FirewallResolver.Exclusive
    ): Boolean {
        checkNotNull(session.holder)
        var checkHolder: CheckHolder?
        var handle = false
        synchronized(lastCheckCancel) {
            val cancel = lastCheckCancel.get(key)
            if (cancel != null && cancel + 15 * 1000 > SystemClock.elapsedRealtime()) {
                return false
            }
        }
        synchronized(checkHolders) {
            checkHolder = checkHolders.get(key)
            if (checkHolder == null) {
                checkHolder = CheckHolder()
                checkHolders.put(key, checkHolder)
                handle = true
            }
        }

        if (handle) {
            try {
                try {
                    session.holder.use().use { ignored ->
                        val exclusiveSession = CheckSession(
                            session.getUri(), session.holder,
                            session.chan, session.getIdentifier(), session.resolve, true
                        )
                        checkHolder!!.success = exclusive.resolve(exclusiveSession, key!!)
                    }
                } catch (e: FirewallResolver.CancelException) {
                    synchronized(lastCheckCancel) {
                        lastCheckCancel.put(key, SystemClock.elapsedRealtime())
                    }
                }
            } finally {
                synchronized(checkHolder!!) {
                    checkHolder.ready = true
                    (checkHolder as Object).notifyAll()
                }
                synchronized(checkHolders) {
                    checkHolders.remove(key)
                }
            }
        } else {
            synchronized(checkHolder!!) {
                while (!checkHolder.ready) {
                    (checkHolder as Object).wait()
                }
            }
        }
        return checkHolder.success
    }

    private val resolvers: List<FirewallResolver> = listOf(CloudFlareResolver(), StormWallResolver())

    @Throws(HttpException::class, InterruptedException::class)
    public override fun checkResponse(
        chan: Chan,
        uri: Uri,
        holder: HttpHolder,
        response: HttpResponse?,
        identifier: FirewallResolver.Identifier,
        resolve: Boolean
    ): FirewallResolver.CheckResult? {
        if (chan.locator.getChanHosts(false).contains(uri.getHost())) {
            val session = CheckSession(uri, holder, chan, identifier, resolve, false)
            var result: CheckResponseResult? = null
            for (resolver in resolvers) {
                result = resolver.checkResponse(session, response!!)
                if (result != null) {
                    break
                }
            }
            if (result == null) {
                val resolvers: List<FirewallResolver> = chan.performer.getFirewallResolvers()
                for (resolver in resolvers) {
                    result = resolver.checkResponse(session, response!!)
                    if (result != null) {
                        break
                    }
                }
            }
            if (result != null && result.key != null && result.exclusive != null) {
                val resolved = resolve && runExclusive(session, result.key!!, result.exclusive!!)
                return FirewallResolver.CheckResult(resolved, result.retransmitOnSuccess)
            }
        }
        return null
    }

    public override fun collectCookies(
        chan: Chan,
        uri: Uri?,
        identifier: FirewallResolver.Identifier,
        safe: Boolean
    ): CookieBuilder {
        val cookieBuilder = CookieBuilder()
        val session = CookieSession(uri, chan, identifier)
        for (resolver in resolvers) {
            resolver.collectCookies(session, cookieBuilder)
        }
        val resolvers: List<FirewallResolver> = chan.performer.getFirewallResolvers()
        if (!resolvers.isEmpty()) {
            try {
                for (resolver in resolvers) {
                    resolver.collectCookies(session, cookieBuilder)
                }
            } catch (e: LinkageError) {
                if (safe) {
                    logException(e, true)
                } else {
                    throw e
                }
            } catch (e: RuntimeException) {
                if (safe) {
                    logException(e, true)
                } else {
                    throw e
                }
            }
        }
        return cookieBuilder
    }

    companion object {
        private const val WEB_VIEW_TIMEOUT = 20000
    }
}
