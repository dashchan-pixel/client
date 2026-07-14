package chan.http

import android.annotation.SuppressLint
import android.net.Uri
import chan.content.Chan
import chan.http.HttpRequest.RequestMethod
import chan.http.HttpValidator.Companion.obtain
import chan.util.CommonUtils.equals
import chan.util.StringUtils.emptyIfNull
import chan.util.StringUtils.isEmpty
import com.mishiranu.dashchan.content.AdvancedPreferences.getUserAgent
import com.mishiranu.dashchan.content.AdvancedPreferences.isSingleConnection
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.util.IOUtils.close
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.brotli.dec.BrotliInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.SequenceInputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class HttpClient private constructor() {
    class ProxyData(
        @JvmField val socks: Boolean,
        @JvmField val host: String?,
        @JvmField val port: Int,
    ) {
        var proxy: Proxy? = null
            get() {
                if (field == null) {
                    try {
                        field =
                            Proxy(
                                if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                                InetSocketAddress.createUnresolved(host, port),
                            )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return field
            }
            private set

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is ProxyData) {
                val proxyData = other
                return socks == proxyData.socks &&
                    equals(host, proxyData.host) &&
                    port == proxyData.port
            }
            return false
        }

        override fun hashCode(): Int {
            var result = (if (socks) 1 else 0)
            result = 31 * result + (if (host != null) host.hashCode() else 0)
            result = 31 * result + port
            return result
        }
    }

    fun checkProxyValid(map: Map<String, String>?): Boolean {
        val proxyData = getProxyData(map)
        return proxyData == null || proxyData.proxy != null
    }

    fun getProxyData(chan: Chan): ProxyData? = getProxyData(Preferences.getProxy(chan))

    private fun getProxyData(map: Map<String, String>?): ProxyData? {
        if (map != null) {
            val host = map[Preferences.SUB_KEY_PROXY_HOST]
            if (!isEmpty(host)) {
                var port: Int
                try {
                    port = map[Preferences.SUB_KEY_PROXY_PORT]!!.toInt()
                } catch (e: Exception) {
                    port = -1
                }
                if (port > 0) {
                    val socks =
                        Preferences.VALUE_PROXY_TYPE_SOCKS == map[Preferences.SUB_KEY_PROXY_TYPE]
                    return ProxyData(socks, host, port)
                }
            }
        }
        return null
    }

    fun getProxy(chan: Chan): Proxy? {
        var proxyData = getProxyData(chan)
        synchronized(proxies) {
            val lastProxyData = proxies[chan.name]
            if (equals(proxyData, lastProxyData)) {
                // With initialized proxy object
                proxyData = lastProxyData
            } else {
                proxies[chan.name] = proxyData
            }
        }
        return if (proxyData != null) proxyData.proxy else null
    }

    private val proxies = HashMap<String?, ProxyData?>()

    internal class InterruptedHttpException : IOException() {
        fun toHttp(): HttpException = HttpException(null, false, false, this)
    }

    private class RetryException : Exception()

    internal enum class Encoding(
        val value: String,
    ) {
        IDENTITY("identity"),
        GZIP("gzip"),
        DEFLATE("deflate"),
        BROTLI("br"),
        UNKNOWN(""),
        ;

        companion object {
            fun get(headers: Headers?): Encoding {
                if (headers != null) {
                    val contentEncoding = headers.get("Content-Encoding")
                    if (!isEmpty(contentEncoding)) {
                        for (encoding in entries) {
                            if (contentEncoding == encoding.value) {
                                return encoding
                            }
                        }
                        return Encoding.UNKNOWN
                    }
                }
                return Encoding.IDENTITY
            }
        }
    }

    private var baseClient: OkHttpClient? = null
    private var unsafeSslSocketFactory: SSLSocketFactory? = null
    private val clients: HashMap<ClientKey?, OkHttpClient?> = HashMap<ClientKey?, OkHttpClient?>()

    private class ClientKey(
        val proxy: Proxy?,
        val verifyCertificate: Boolean,
        val connectTimeout: Int,
        val readTimeout: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other is ClientKey) {
                val key: ClientKey = other
                return equals(
                    proxy,
                    key.proxy,
                ) &&
                    verifyCertificate == key.verifyCertificate &&
                    connectTimeout == key.connectTimeout &&
                    readTimeout == key.readTimeout
            }
            return false
        }

        override fun hashCode(): Int {
            var result = if (proxy != null) proxy.hashCode() else 0
            result = 31 * result + (if (verifyCertificate) 1 else 0)
            result = 31 * result + connectTimeout
            result = 31 * result + readTimeout
            return result
        }
    }

    @Synchronized
    private fun obtainClient(
        proxy: Proxy?,
        verifyCertificate: Boolean,
        connectTimeout: Int,
        readTimeout: Int,
    ): OkHttpClient {
        if (baseClient == null) {
            baseClient =
                OkHttpClient
                    .Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build()
        }
        val key = ClientKey(proxy, verifyCertificate, connectTimeout, readTimeout)
        var client = clients[key]
        if (client == null) {
            val builder =
                baseClient!!
                    .newBuilder()
                    .proxy(if (proxy != null) proxy else Proxy.NO_PROXY)
                    .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            if (!verifyCertificate) {
                if (unsafeSslSocketFactory == null) {
                    try {
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(
                            null,
                            arrayOf<X509TrustManager?>(UNSAFE_TRUST_MANAGER),
                            null,
                        )
                        unsafeSslSocketFactory = sslContext.getSocketFactory()
                    } catch (e: Exception) {
                        throw RuntimeException(e)
                    }
                }
                builder.sslSocketFactory(unsafeSslSocketFactory!!, UNSAFE_TRUST_MANAGER)
                builder.hostnameVerifier(UNSAFE_HOSTNAME_VERIFIER)
            }
            client = builder.build()
            if (clients.size > 16) {
                clients.clear()
            }
            clients[key] = client
        }
        return client
    }

    @Throws(HttpException::class)
    fun execute(
        session: HttpSession,
        request: HttpRequest,
    ): HttpResponse {
        while (true) {
            try {
                return executeInternal(session, request)
            } catch (e: RetryException) {
                // Continue
            }
        }
    }

    private class EntityRequestBody(
        private val entity: RequestEntity,
        private val session: HttpSession,
        private val listener: HttpRequest.OutputListener?,
    ) : RequestBody() {
        override fun contentType(): MediaType? {
            val contentType = entity.getContentType()
            return contentType?.toMediaTypeOrNull()
        }

        override fun contentLength(): Long {
            val contentLength = entity.getContentLength()
            return if (contentLength > 0) contentLength else -1
        }

        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
            val contentLength = entity.getContentLength()
            val output =
                ClientOutputStream(
                    sink.outputStream(),
                    session,
                    listener,
                    contentLength,
                )
            entity.write(output)
            output.flush()
        }
    }

    @Throws(HttpException::class, RetryException::class)
    private fun executeInternal(
        session: HttpSession,
        request: HttpRequest,
    ): HttpResponse {
        session.checkThread()
        session.checkExecuting()
        session.disconnectAndClear()
        session.executing = true
        try {
            val requestedUri = session.currentRequestedUri!!
            if (!session.holder.chan!!
                    .locator
                    .isWebScheme(requestedUri)
            ) {
                throw HttpException(ErrorItem.Type.UNSUPPORTED_SCHEME, false, false)
            }
            val url: URL = encodeUri(requestedUri)
            val builder = Request.Builder().url(url)

            var userAgent: String? = null
            var userAgentSet = false
            var acceptEncodingSet = false
            if (request.headers != null) {
                for (header in request.headers!!) {
                    if ("Connection".equals(header!!.first, ignoreCase = true)) {
                        continue
                    }
                    builder.header(header.first!!, header.second!!)
                    if ("User-Agent".equals(header.first, ignoreCase = true)) {
                        userAgent = header.second
                        userAgentSet = true
                    }
                    if ("Accept-Encoding".equals(header.first, ignoreCase = true)) {
                        acceptEncodingSet = true
                    }
                }
            }
            if (!userAgentSet) {
                userAgent = getUserAgent(session.holder.chan.name)
                builder.header("User-Agent", userAgent)
            }
            if (!acceptEncodingSet) {
                builder.header("Accept-Encoding", ACCEPT_ENCODING)
            }
            val resolverIdentifier =
                FirewallResolver.Identifier(userAgent, !userAgentSet, url.getHost())
            val cookieBuilder =
                if (!session.mayCheckFirewallBlock) {
                    request.cookieBuilder
                } else {
                    obtainModifiedCookieBuilder(
                        request.cookieBuilder,
                        session.holder.chan,
                        requestedUri,
                        resolverIdentifier,
                    )
                }
            if (cookieBuilder != null) {
                builder.header("Cookie", cookieBuilder.build())
            }
            val validator = request.validator
            if (validator != null) {
                if (!isEmpty(validator.entityTag)) {
                    builder.header("If-None-Match", validator.entityTag!!)
                }
                if (!isEmpty(validator.lastModified)) {
                    builder.header("If-Modified-Since", validator.lastModified!!)
                }
            }
            if (request.rangeStart >= 0 || request.rangeEnd >= 0) {
                builder.header(
                    "Range",
                    "bytes=" +
                        (if (request.rangeStart >= 0) request.rangeStart else "") + "-" +
                        (if (request.rangeEnd >= 0) request.rangeEnd else ""),
                )
            }

            val forceGet = session.forceGet
            var requestMethod = request.requestMethod
            if (forceGet && requestMethod != RequestMethod.GET && requestMethod != RequestMethod.HEAD) {
                requestMethod = RequestMethod.GET
            }
            val entity = if (forceGet) null else request.requestEntity
            var requestBody: RequestBody? = null
            if (entity != null) {
                requestBody =
                    EntityRequestBody(
                        entity,
                        session,
                        if (forceGet) null else request.outputListener,
                    )
            } else if (requestMethod == RequestMethod.POST ||
                requestMethod == RequestMethod.PUT
            ) {
                requestBody = ByteArray(0).toRequestBody(null)
            }
            builder.method(requestMethod!!.name, requestBody)

            val client =
                obtainClient(
                    session.proxy,
                    session.verifyCertificate,
                    request.connectTimeout,
                    request.readTimeout,
                )
            val call = client.newCall(builder.build())
            session.setCall(call)
            val okResponse: Response?
            try {
                okResponse = call.execute()
            } catch (e: IOException) {
                if (call.isCanceled()) {
                    throw InterruptedHttpException()
                }
                throw e
            }
            session.okResponse = okResponse
            val responseCode = okResponse.code
            val resultValidator = obtain(okResponse.headers)
            val contentType = okResponse.header("Content-Type")
            val charsetName: String? = extractCharsetName(contentType)
            session.holder.checkInterrupted()
            val response = HttpResponse(session, resultValidator, charsetName)
            session.response = response

            val redirectHandler = request.redirectHandler
            when (responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, HTTP_TEMPORARY_REDIRECT -> {
                    val oldHttps = "https" == requestedUri.getScheme()
                    var redirectedUri: Uri? =
                        obtainRedirectedUri(requestedUri, okResponse.header("Location"))
                    if (redirectedUri == null) {
                        throw HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                    }
                    session.redirectedUri = redirectedUri
                    val action: HttpRequest.RedirectHandler.Action?
                    try {
                        action = redirectHandler.onRedirect(response)
                    } catch (e: HttpException) {
                        session.disconnectAndClear()
                        throw e
                    }
                    redirectedUri = session.redirectedUri
                    if (action == HttpRequest.RedirectHandler.Action.GET ||
                        action == HttpRequest.RedirectHandler.Action.RETRANSMIT
                    ) {
                        session.disconnectAndClear()
                        if (redirectedUri == null) {
                            throw HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                        }
                        val newHttps = "https" == redirectedUri.getScheme()
                        if (session.verifyCertificate && oldHttps && !newHttps) {
                            // Redirect from https to http is unsafe
                            throw HttpException(ErrorItem.Type.UNSAFE_REDIRECT, true, false)
                        }
                        if (action == HttpRequest.RedirectHandler.Action.GET) {
                            session.forceGet = true
                        }
                        session.redirectedUri = null
                        session.setNextRequestedUri(redirectedUri)
                        if (session.nextAttempt()) {
                            throw RetryException()
                        } else {
                            session.disconnectAndClear()
                            throw HttpException(responseCode, session.responseMessage)
                        }
                    }
                }
            }

            if (validator != null && responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                val responseMessage = session.responseMessage
                session.disconnectAndClear()
                throw HttpException(responseCode, responseMessage)
            }

            if (session.holder.chan.name != null && session.mayCheckFirewallBlock && requestMethod != RequestMethod.HEAD) {
                val result: FirewallResolver.CheckResult?
                try {
                    result =
                        FirewallResolver.Implementation.getInstance().checkResponse(
                            session.holder.chan,
                            requestedUri,
                            session.holder,
                            response,
                            resolverIdentifier,
                            session.holder.mayResolveFirewallBlock,
                        )
                } catch (e: InterruptedException) {
                    throw InterruptedHttpException()
                }
                if (result != null) {
                    if (result.resolved && session.nextAttempt()) {
                        if (result.retransmitOnSuccess) {
                            session.forceGet = false
                        }
                        val redirectedUri = session.redirectedUri
                        if (redirectedUri != null) {
                            session.redirectedUri = null
                            session.setNextRequestedUri(redirectedUri)
                        }
                        session.holder.mayResolveFirewallBlock = false
                        throw RetryException()
                    } else {
                        session.disconnectAndClear()
                        throw HttpException(ErrorItem.Type.FIREWALL_BLOCK, true, false)
                    }
                }
            }

            if (request.successOnly) {
                session.checkResponseCode()
            }
            session.holder.checkInterrupted()
            return response
        } catch (e: InterruptedHttpException) {
            session.disconnectAndClear()
            throw e.toHttp()
        } catch (e: IOException) {
            if (isConnectionReset(e)) {
                // Sometimes server closes the socket, but client is still trying to use it
                if (session.nextAttempt()) {
                    e.printStackTrace()
                    throw RetryException()
                }
            }
            session.disconnectAndClear()
            throw transformIOException(e)
        } finally {
            session.executing = false
        }
    }

    @Throws(HttpException::class)
    fun open(response: HttpResponse): InputStream {
        checkNotNull(response.session)
        response.session.checkThread()
        try {
            val okResponse = response.session.okResponse
            if (okResponse == null) {
                throw InterruptedHttpException()
            }
            var input: InputStream =
                okResponse.body?.byteStream()
                    ?: throw HttpException(ErrorItem.Type.EMPTY_RESPONSE, false, false)
            var success = false
            try {
                response.session.holder.checkInterrupted()
                input = BufferedInputStream(input, 8192)
                when (Encoding.Companion.get(okResponse.headers)) {
                    Encoding.IDENTITY -> {}

                    Encoding.GZIP -> {
                        input = GZIPInputStream(input)
                    }

                    Encoding.DEFLATE -> {
                        input = DeflateInputStream(input)
                    }

                    Encoding.BROTLI -> {
                        input = BrotliInputStream(input)
                    }

                    else -> {
                        throw HttpException(ErrorItem.Type.DOWNLOAD, false, false)
                    }
                }
                success = true
                return ClientInputStream(input, response.session)
            } finally {
                if (!success) {
                    close(input)
                }
            }
        } catch (e: InterruptedHttpException) {
            throw e.toHttp()
        } catch (e: IOException) {
            throw transformIOException(e)
        }
    }

    fun obtainModifiedCookieBuilder(
        cookieBuilder: CookieBuilder?,
        chan: Chan?,
        uri: Uri?,
        resolverIdentifier: FirewallResolver.Identifier?,
    ): CookieBuilder? {
        var resultCookieBuilder = cookieBuilder
        val appendCookieBuilder: CookieBuilder =
            FirewallResolver.Implementation
                .getInstance()
                .collectCookies(chan!!, uri, resolverIdentifier!!, false)
        if (!appendCookieBuilder.isEmpty) {
            resultCookieBuilder = CookieBuilder(cookieBuilder)
            resultCookieBuilder.append(appendCookieBuilder)
        }
        return resultCookieBuilder
    }

    fun obtainRedirectedUri(
        requestedUri: Uri,
        locationHeader: String?,
    ): Uri {
        var redirectedUri: Uri
        if (!isEmpty(locationHeader)) {
            redirectedUri = Uri.parse(locationHeader)
            if (redirectedUri.isRelative()) {
                val builder =
                    redirectedUri
                        .buildUpon()
                        .scheme(requestedUri.getScheme())
                        .authority(requestedUri.getAuthority())
                val redirectedPath = emptyIfNull(redirectedUri.getPath())
                if (!redirectedPath.isEmpty() && !redirectedPath.startsWith("/")) {
                    var path = emptyIfNull(requestedUri.getPath())
                    if (!path.endsWith("/")) {
                        val index = path.lastIndexOf('/')
                        if (index >= 0) {
                            path = path.substring(0, index + 1)
                        } else {
                            path = "/"
                        }
                    }
                    path += redirectedPath
                    builder.path(path)
                }
                redirectedUri = builder.build()
            }
        } else {
            redirectedUri = requestedUri
        }
        return redirectedUri
    }

    private class DeflateInputStream(
        private val input: InputStream,
    ) : InputStream() {
        private var workInput: InputStream? = null

        @Throws(IOException::class)
        fun ensureWorkInput() {
            if (workInput == null) {
                workInput = createWorkInput(input)
            }
        }

        @Throws(IOException::class)
        override fun read(): Int {
            ensureWorkInput()
            return workInput!!.read()
        }

        @Throws(IOException::class)
        override fun read(
            b: ByteArray?,
            off: Int,
            len: Int,
        ): Int {
            ensureWorkInput()
            return workInput!!.read(b, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            input.close()
        }

        companion object {
            @Throws(IOException::class)
            fun createWorkInput(input: InputStream): InputStream {
                // Check zlib header and create a proper Inflater
                val output = arrayOf<ByteArrayOutputStream?>(ByteArrayOutputStream())
                val workInput: InputStream =
                    InflaterInputStream(
                        object : InputStream() {
                            @Throws(IOException::class)
                            override fun read(): Int {
                                val result = input.read()
                                if (result >= 0 && output[0] != null) {
                                    output[0]!!.write(result)
                                }
                                return result
                            }

                            @Throws(IOException::class)
                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                val result = input.read(b, off, len)
                                if (result > 0 && output[0] != null) {
                                    output[0]!!.write(b, off, result)
                                }
                                return result
                            }
                        },
                        Inflater(false),
                    )
                var success = false
                var firstByte = -1
                try {
                    firstByte = workInput.read()
                    success = true
                } catch (e: ZipException) {
                    // Ignore exception
                }
                if (success) {
                    output[0] = null
                    if (firstByte >= 0) {
                        return SequenceInputStream(
                            ByteArrayInputStream(byteArrayOf(firstByte.toByte())),
                            workInput,
                        )
                    } else {
                        workInput.close()
                        return ByteArrayInputStream(ByteArray(0))
                    }
                } else {
                    workInput.close()
                    val head = output[0]!!.toByteArray()
                    val newInput: InputStream =
                        SequenceInputStream(ByteArrayInputStream(head), input)
                    return InflaterInputStream(newInput, Inflater(true))
                }
            }
        }
    }

    private class ClientInputStream(
        private val input: InputStream,
        private val session: HttpSession?,
    ) : InputStream() {
        @Throws(IOException::class)
        override fun read(): Int {
            Companion.checkInterruptedAndClose(session!!, this)
            return input.read()
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray): Int = read(b, 0, b.size)

        @Throws(IOException::class)
        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            Companion.checkInterruptedAndClose(session!!, this)
            return input.read(b, off, len)
        }

        @Throws(IOException::class)
        override fun skip(n: Long): Long {
            Companion.checkInterruptedAndClose(session!!, this)
            return input.skip(n)
        }

        @Throws(IOException::class)
        override fun available(): Int {
            Companion.checkInterruptedAndClose(session!!, this)
            return input.available()
        }

        @Throws(IOException::class)
        override fun close() {
            try {
                input.close()
            } finally {
                if (session != null) {
                    var validThread = false
                    try {
                        session.checkThread()
                        validThread = true
                    } catch (e: Exception) {
                        // Ignore
                    }
                    if (validThread) {
                        session.response?.cleanupAndDisconnectIfEquals(this)
                    }
                }
            }
        }

        override fun mark(readlimit: Int) {
            input.mark(readlimit)
        }

        override fun markSupported(): Boolean = input.markSupported()

        @Throws(IOException::class)
        override fun reset() {
            input.reset()
        }
    }

    private class ClientOutputStream(
        private val output: OutputStream,
        private val session: HttpSession,
        listener: HttpRequest.OutputListener?,
        private val contentLength: Long,
    ) : OutputStream() {
        private val listener: HttpRequest.OutputListener?

        private val progress = AtomicLong()

        init {
            this.listener = if (contentLength > 0) listener else null
            if (this.listener != null) {
                this.listener.onOutputProgressChange(0, contentLength)
            }
        }

        @Throws(IOException::class)
        override fun write(oneByte: Int) {
            checkInterruptedAndClose(session, this)
            output.write(oneByte)
            updateProgress(1)
        }

        @Throws(IOException::class)
        override fun write(buffer: ByteArray) {
            checkInterruptedAndClose(session, this)
            output.write(buffer)
            updateProgress(buffer.size.toLong())
        }

        @Throws(IOException::class)
        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            checkInterruptedAndClose(session, this)
            output.write(buffer, offset, length)
            updateProgress(length.toLong())
        }

        fun updateProgress(value: Long) {
            if (listener != null && value > 0) {
                val progress = this.progress.addAndGet(value)
                listener.onOutputProgressChange(progress, contentLength)
            }
        }

        @Throws(IOException::class)
        override fun close() {
            flush()
        }

        @Throws(IOException::class)
        override fun flush() {
            checkInterruptedAndClose(session, this)
            output.flush()
        }
    }

    private val singleConnections = HashMap<String?, Call?>()
    private val singleConnectionIdentifiers = HashMap<Call?, String?>()

    private val delayLocks = HashMap<String?, AtomicBoolean?>()

    // Called from HttpSession
    @Throws(InterruptedHttpException::class)
    fun onConnect(
        chan: Chan,
        call: Call,
        delay: Int,
    ) {
        if (isSingleConnection(chan.name)) {
            synchronized(singleConnections) {
                while (singleConnections.containsKey(chan.name)) {
                    try {
                        (singleConnections as Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedHttpException()
                    }
                }
                singleConnections.put(chan.name, call)
                singleConnectionIdentifiers.put(call, chan.name)
            }
        }
        if (delay > 0) {
            val key = call.request().url.host + ":" + call.request().url.port
            var delayLock: AtomicBoolean?
            synchronized(delayLocks) {
                delayLock = delayLocks[key]
                if (delayLock == null) {
                    delayLock = AtomicBoolean(false)
                    delayLocks[key] = delayLock
                }
            }
            synchronized(delayLock!!) {
                try {
                    while (delayLock.get()) {
                        (delayLock as Object).wait()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                delayLock.set(true)
                try {
                    Thread.sleep(delay.toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    delayLock.set(false)
                    (delayLock as Object).notifyAll()
                }
            }
        }
    }

    // Called from HttpSession
    fun onDisconnect(call: Call?) {
        synchronized(singleConnections) {
            val chanName = singleConnectionIdentifiers.remove(call)
            if (chanName != null) {
                if (call === singleConnections.get(chanName)) {
                    singleConnections.remove(chanName)
                    (singleConnections as Object).notifyAll()
                }
            }
        }
    }

    companion object {
        private val SHORT_RESPONSE_MESSAGES = HashMap<String?, String?>()

        private val UNSAFE_HOSTNAME_VERIFIER =
            HostnameVerifier { hostname: String?, session: SSLSession? -> true }

        @SuppressLint("TrustAllX509TrustManager")
        private val UNSAFE_TRUST_MANAGER: X509TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<X509Certificate?>?,
                    authType: String?,
                ) {}

                override fun checkServerTrusted(
                    chain: Array<X509Certificate?>?,
                    authType: String?,
                ) {}

                override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls<X509Certificate>(0)
            }

        const val HTTP_TEMPORARY_REDIRECT: Int = 307

        init {
            SHORT_RESPONSE_MESSAGES["Internal Server Error"] = "Internal Error"
            SHORT_RESPONSE_MESSAGES["Service Temporarily Unavailable"] = "Service Unavailable"
        }

        private val INSTANCE = HttpClient()

        @JvmStatic
        fun getInstance(): HttpClient = INSTANCE

        private const val ACCEPT_ENCODING = "gzip, deflate, br"

        private fun encodeUriBufferPart(
            uriStringBuilder: StringBuilder,
            chars: CharArray?,
            i: Int,
            start: Int,
            ascii: Boolean,
        ) {
            if (!ascii) {
                try {
                    for (b in kotlin.text
                        .String(chars!!, start, i - start)
                        .toByteArray(charset("UTF-8"))) {
                        val s = (b.toInt() and 0xff).toString(16).uppercase()
                        uriStringBuilder.append('%')
                        uriStringBuilder.append(s)
                    }
                } catch (e: UnsupportedEncodingException) {
                    throw RuntimeException(e)
                }
            } else {
                uriStringBuilder.append(chars, start, i - start)
            }
        }

        private fun encodeUriAppend(
            uriStringBuilder: StringBuilder,
            part: String,
        ) {
            val chars = part.toCharArray()
            var ascii = true
            var start = 0
            for (i in chars.indices) {
                val c = chars[i]
                val ita = c.code < 0x80
                if (ita != ascii) {
                    encodeUriBufferPart(uriStringBuilder, chars, i, start, ascii)
                    start = i
                    ascii = ita
                }
            }
            encodeUriBufferPart(uriStringBuilder, chars, chars.size, start, ascii)
        }

        @Throws(MalformedURLException::class)
        fun encodeUri(uri: Uri): URL {
            val uriStringBuilder = StringBuilder()
            uriStringBuilder.append(emptyIfNull(uri.getScheme())).append("://")
            val host = IDN.toASCII(emptyIfNull(uri.getHost()))
            uriStringBuilder.append(host)
            val port = uri.getPort()
            if (port != -1) {
                uriStringBuilder.append(':').append(port)
            }
            val path = uri.getEncodedPath()
            if (!isEmpty(path)) {
                Companion.encodeUriAppend(uriStringBuilder, path!!)
            }
            val query = uri.getEncodedQuery()
            if (!isEmpty(query)) {
                uriStringBuilder.append('?')
                Companion.encodeUriAppend(uriStringBuilder, query!!)
            }
            return URL(uriStringBuilder.toString())
        }

        fun extractCharsetName(contentType: String?): String? {
            if ("application/json" == contentType) {
                // Assume UTF-8 https://tools.ietf.org/html/rfc4627#section-3
                return "UTF-8"
            }
            if (contentType != null) {
                val index = contentType.indexOf("charset=")
                if (index >= 0) {
                    val end = contentType.indexOf(';', index)
                    val charsetName =
                        contentType.substring(index + 8, if (end >= 0) end else contentType.length)
                    try {
                        Charset.forName(charsetName)
                        return charsetName
                    } catch (e: UnsupportedCharsetException) {
                        // Ignore
                    }
                }
            }
            return null
        }

        fun transformResponseMessage(originalMessage: String?): String? {
            val message: String? = SHORT_RESPONSE_MESSAGES[originalMessage]
            return if (message != null) message else originalMessage
        }

        fun transformIOException(exception: IOException): HttpException {
            var errorType: ErrorItem.Type?
            try {
                errorType = getErrorTypeForException(exception)
                exception.printStackTrace()
            } catch (e: InterruptedIOException) {
                errorType = null
            }
            if (errorType != null) {
                return HttpException(errorType, false, true, exception)
            } else {
                return HttpException(ErrorItem.Type.DOWNLOAD, false, true, exception)
            }
        }

        @Throws(InterruptedIOException::class)
        private fun getErrorTypeForException(exception: IOException): ErrorItem.Type? {
            if (isConnectionReset(exception)) {
                return ErrorItem.Type.CONNECTION_RESET
            }
            val message = exception.message
            if (message != null) {
                if (message.contains("thread interrupted") && exception is InterruptedIOException) {
                    throw exception
                }
                if (message.contains("Failed to connect to") ||
                    message.contains("failed to connect to")
                ) {
                    return ErrorItem.Type.CONNECT_TIMEOUT
                }
                if (message.contains("SSL handshake timed out")) {
                    return ErrorItem.Type.CONNECT_TIMEOUT
                }
                if (message.startsWith("Hostname ") && message.endsWith(" not verified")) {
                    return ErrorItem.Type.INVALID_CERTIFICATE
                }
                if (message.contains("Could not validate certificate") ||
                    message.contains("Trust anchor for certification path not found")
                ) {
                    return ErrorItem.Type.INVALID_CERTIFICATE
                }
            }
            if (exception is SSLException) {
                return ErrorItem.Type.SSL
            }
            if (exception is SocketTimeoutException) {
                val timeoutMessage = exception.message
                if (timeoutMessage != null && timeoutMessage.lowercase().contains("connect")) {
                    return ErrorItem.Type.CONNECT_TIMEOUT
                }
                return ErrorItem.Type.READ_TIMEOUT
            }
            return null
        }

        private fun isConnectionReset(exception: IOException): Boolean {
            if (exception is EOFException) {
                return true
            }
            val message = exception.message
            return message != null &&
                (
                    message.contains("Connection reset by peer") ||
                        message.contains("Connection closed by peer") ||
                        message.contains("unexpected end of stream") ||
                        message.contains("Connection refused")
                )
        }

        @Throws(InterruptedHttpException::class)
        private fun checkInterruptedAndClose(
            session: HttpSession,
            closeable: Closeable?,
        ) {
            try {
                session.holder.checkInterrupted()
            } catch (e: InterruptedHttpException) {
                close(closeable)
                throw e
            }
        }
    }
}
