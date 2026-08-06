package chan.http

import android.net.Uri
import chan.http.HttpClient.InterruptedHttpException
import chan.util.CommonUtils.equals
import okhttp3.Call
import okhttp3.Response

class HttpSession internal constructor(
    val holder: HttpHolder,
    val client: HttpClient,
    uri: Uri?,
    val proxyData: HttpClient.ProxyData?,
    val verifyCertificate: Boolean,
    val mayCheckFirewallBlock: Boolean,
    val delay: Int,
    var attempt: Int,
) {
    var response: HttpResponse? = null

    private val requestedUris = ArrayList<Uri?>(2)
    var redirectedUri: Uri? = null

    var forceGet: Boolean = false
    var executing: Boolean = false

    var currentCall: Call? = null
    var okResponse: Response? = null
    var deadResponse: Response? = null
    var currentCallback: HttpHolder.Callback? = null

    init {
        requestedUris.add(uri)
    }

    fun checkThread() {
        holder.checkThread()
    }

    fun checkExecuting() {
        check(!executing) { "Can't perform requests during active execute process" }
    }

    fun getRequestedUris(): MutableList<Uri?> {
        checkThread()
        return requestedUris
    }

    val currentRequestedUri: Uri?
        get() {
            checkThread()
            return requestedUris[requestedUris.size - 1]
        }

    fun setNextRequestedUri(uri: Uri?) {
        checkThread()
        requestedUris.add(uri)
    }

    @Throws(InterruptedHttpException::class)
    private fun setCallInternal(
        call: Call?,
        callback: HttpHolder.Callback?,
    ) {
        checkThread()
        this.currentCall = call
        this.currentCallback = callback
        redirectedUri = null
        if (holder.isInterrupted) {
            this.currentCall = null
            this.currentCallback = null
            throw InterruptedHttpException()
        }
        if (call != null) {
            client.onConnect(holder.chan!!, call, delay)
        }
    }

    @Throws(InterruptedHttpException::class)
    fun setCall(call: Call?) {
        checkThread()
        setCallInternal(call, null)
    }

    @Throws(InterruptedHttpException::class)
    fun setCallback(callback: HttpHolder.Callback?) {
        checkThread()
        setCallInternal(null, callback)
    }

    fun nextAttempt(): Boolean {
        checkThread()
        return attempt-- > 0
    }

    fun disconnectAndClear() {
        checkThread()
        val call = this.currentCall
        this.currentCall = null
        val okResponse = this.okResponse
        this.okResponse = null
        val callback = this.currentCallback
        this.currentCallback = null
        // HttpResponse will call disconnectAndClear if the response is still active
        response?.cleanupAndDisconnect()
        if (okResponse != null) {
            okResponse.close()
            deadResponse = okResponse
        }
        if (call != null) {
            call.cancel()
            client.onDisconnect(call)
        }
        if (callback != null) {
            callback.onDisconnectRequested()
        }
    }

    @Throws(HttpException::class)
    fun checkResponseCode() {
        checkThread()
        val responseCode = this.responseCode
        val success =
            (responseCode >= 200 && responseCode <= 303) ||
                responseCode == HttpClient.Companion.HTTP_TEMPORARY_REDIRECT
        if (!success) {
            val message: String? =
                HttpClient.Companion.transformResponseMessage(
                    this.responseMessage,
                )
            disconnectAndClear()
            throw HttpException(responseCode, message)
        }
    }

    private val responseForHeaders: Response?
        get() {
            checkThread()
            var response = okResponse
            if (response == null) {
                response = deadResponse
            }
            return response
        }

    val responseCode: Int
        get() {
            val response = this.responseForHeaders
            return if (response != null) response.code else -1
        }

    val responseMessage: String?
        get() {
            val response = this.responseForHeaders
            if (response != null) {
                val message = response.message
                return if (message.isEmpty()) null else message
            }
            return null
        }

    /**
     * Keyed without regard to case, because the case a header arrives in is the protocol's choice,
     * not the server's: HTTP/2 lowercases every name, so a server that sent `CF-RAY` over HTTP/1.1
     * sends `cf-ray` here. Everything reading a header by its familiar spelling — the firewall
     * resolvers, the extensions — would otherwise silently find nothing on exactly the hosts modern
     * enough to negotiate HTTP/2.
     */
    val headerFields: MutableMap<String?, MutableList<String>?>
        get() {
            val response = this.responseForHeaders ?: return sortedByHeaderName()
            val headers = response.headers
            val map = sortedByHeaderName()
            for (i in 0..<headers.size) {
                val name = headers.name(i)
                var values = map[name]
                if (values == null) {
                    values = ArrayList(1)
                    map[name] = values
                }
                values.add(headers.value(i))
            }
            return map
        }

    fun getCookieValue(name: String?): String? {
        val cookies = this.headerFields["Set-Cookie"]
        if (cookies != null) {
            val start = name + "="
            for (cookie in cookies) {
                if (cookie.startsWith(start)) {
                    val startIndex = start.length
                    val endIndex = cookie.indexOf(';')
                    if (endIndex >= 0) {
                        return cookie.substring(startIndex, endIndex)
                    } else {
                        return cookie.substring(startIndex)
                    }
                }
            }
        }
        return null
    }

    val length: Long
        get() {
            val response = this.responseForHeaders
            if (response != null && HttpClient.Encoding.Companion.get(response.headers) == HttpClient.Encoding.IDENTITY) {
                val contentLength = response.header("Content-Length")
                if (contentLength != null) {
                    try {
                        return contentLength.toLong()
                    } catch (e: NumberFormatException) {
                        return -1
                    }
                }
            }
            return -1
        }

    companion object {
        /**
         * The map [headerFields] hands out. A null name never comes off the wire, but the map is
         * `@Public` with a nullable key type, so the order tolerates one rather than throwing where
         * the old [LinkedHashMap] would have returned null.
         */
        private val HEADER_NAME_ORDER =
            Comparator<String?> { first, second ->
                when {
                    first == null && second == null -> 0
                    first == null -> -1
                    second == null -> 1
                    else -> String.CASE_INSENSITIVE_ORDER.compare(first, second)
                }
            }

        private fun sortedByHeaderName(): MutableMap<String?, MutableList<String>?> = java.util.TreeMap(HEADER_NAME_ORDER)
    }
}
