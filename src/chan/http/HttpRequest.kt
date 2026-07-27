package chan.http

import android.net.Uri
import android.util.Pair
import chan.annotation.Public
import com.mishiranu.dashchan.content.Preferences.isVerifyCertificate
import java.net.HttpURLConnection
import java.util.Objects

@Public
class HttpRequest {
    // Declared as getter methods rather than Kotlin properties on purpose. The Java API these
    // interfaces replaced backed every one of them with a *public field* on the implementing
    // ChanPerformer.*Data classes, and extensions in the wild read those fields directly
    // (`new HttpRequest(uri, data.holder, data)`). A Kotlin `override val` forces a private
    // backing field, which turns such an access into IllegalAccessError at runtime. Keeping the
    // interface member a method lets implementors expose `@JvmField val holder` and satisfy the
    // interface with `override fun getHolder()`, reproducing the original ABI byte for byte.
    @Public
    interface Preset {
        fun getHolder(): HttpHolder?
    }

    interface TimeoutsPreset : Preset {
        fun getConnectTimeout(): Int

        fun getReadTimeout(): Int
    }

    interface OutputListenerPreset : Preset {
        fun getOutputListener(): OutputListener?
    }

    interface RangePreset : Preset {
        fun getRangeStart(): Long

        fun getRangeEnd(): Long
    }

    interface OutputListener {
        fun onOutputProgressChange(
            progress: Long,
            progressMax: Long,
        )
    }

    @Public
    fun interface RedirectHandler {
        @Public
        enum class Action {
            @Public
            CANCEL,

            @Public
            GET,

            @Public
            RETRANSMIT,
        }

        @Public
        @Throws(HttpException::class)
        fun onRedirect(response: HttpResponse?): Action?

        companion object {
            @Public
            @JvmField
            val NONE: RedirectHandler =
                RedirectHandler { response: HttpResponse? -> Action.CANCEL }

            @Public
            @JvmField
            val BROWSER: RedirectHandler =
                RedirectHandler { response: HttpResponse? -> Action.GET }

            @Public
            @JvmField
            val STRICT: RedirectHandler =
                RedirectHandler { response: HttpResponse? ->
                    when (response!!.getResponseCode()) {
                        HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> {
                            return@RedirectHandler Action.RETRANSMIT
                        }

                        else -> {
                            return@RedirectHandler Action.GET
                        }
                    }
                }
        }
    }

    val uri: Uri?
    private val holder: HttpHolder
    private val client: HttpClient

    internal enum class RequestMethod {
        GET,
        HEAD,
        POST,
        PUT,
        DELETE,
    }

    internal var requestMethod: RequestMethod? = RequestMethod.GET
    var requestEntity: RequestEntity? = null

    var successOnly: Boolean = true
    var redirectHandler: RedirectHandler = RedirectHandler.Companion.BROWSER
    var validator: HttpValidator? = null
    var keepAlive: Boolean = true

    var outputListener: OutputListener? = null
    var rangeStart: Long = -1
    var rangeEnd: Long = -1

    var connectTimeout: Int = 15000
    var readTimeout: Int = 15000
    var delay: Int = 0

    var headers: ArrayList<Pair<String?, String?>?>? = null
    var cookieBuilder: CookieBuilder? = null

    // Retained: used throughout the client for requests bound to an HttpHolder.
    @Public
    constructor(uri: Uri?, holder: HttpHolder) {
        Objects.requireNonNull<HttpHolder?>(holder)
        this.uri = uri
        this.holder = holder
        client = HttpClient.getInstance()
    }

    @Public
    constructor(uri: Uri?, preset: Preset?) {
        val holder: HttpHolder = (if (preset != null) preset.getHolder() else null)!!
        Objects.requireNonNull<HttpHolder?>(holder)
        this.uri = uri
        this.holder = holder
        client = HttpClient.getInstance()
        if (preset is TimeoutsPreset) {
            setTimeouts(preset.getConnectTimeout(), preset.getReadTimeout())
        }
        if (preset is OutputListenerPreset) {
            setOutputListener(preset.getOutputListener())
        }
        if (preset is RangePreset) {
            val rangePreset = preset
            setRange(rangePreset.getRangeStart(), rangePreset.getRangeEnd())
        }
    }

    private fun setMethod(
        method: RequestMethod?,
        entity: RequestEntity?,
    ): HttpRequest {
        requestMethod = method
        requestEntity = entity
        return this
    }

    @Public
    fun setGetMethod(): HttpRequest = setMethod(RequestMethod.GET, null)

    @Public
    fun setHeadMethod(): HttpRequest = setMethod(RequestMethod.HEAD, null)

    @Public
    fun setPostMethod(entity: RequestEntity?): HttpRequest = setMethod(RequestMethod.POST, entity)

    @Public
    fun setPutMethod(entity: RequestEntity?): HttpRequest = setMethod(RequestMethod.PUT, entity)

    @Public
    fun setDeleteMethod(entity: RequestEntity?): HttpRequest = setMethod(RequestMethod.DELETE, entity)

    @Public
    fun setSuccessOnly(successOnly: Boolean): HttpRequest {
        this.successOnly = successOnly
        return this
    }

    @Public
    fun setRedirectHandler(redirectHandler: RedirectHandler): HttpRequest {
        this.redirectHandler = redirectHandler
        return this
    }

    @Public
    fun setValidator(validator: HttpValidator?): HttpRequest {
        this.validator = validator
        return this
    }

    @Public
    fun setKeepAlive(keepAlive: Boolean): HttpRequest {
        this.keepAlive = keepAlive
        return this
    }

    @Public
    fun setTimeouts(
        connectTimeout: Int,
        readTimeout: Int,
    ): HttpRequest {
        if (connectTimeout >= 0) {
            this.connectTimeout = connectTimeout
        }
        if (readTimeout >= 0) {
            this.readTimeout = readTimeout
        }
        return this
    }

    @Public
    fun setDelay(delay: Int): HttpRequest {
        this.delay = delay
        return this
    }

    fun setOutputListener(listener: OutputListener?): HttpRequest {
        outputListener = listener
        return this
    }

    fun setRange(
        start: Long,
        end: Long,
    ): HttpRequest {
        this.rangeStart = start
        this.rangeEnd = end
        return this
    }

    private fun addHeader(header: Pair<String?, String?>?): HttpRequest {
        if (header != null && header.first != null && header.second != null) {
            val headers =
                this.headers ?: ArrayList<Pair<String?, String?>?>().also { this.headers = it }
            headers.add(header)
        }
        return this
    }

    @Public
    fun addHeader(
        name: String?,
        value: String?,
    ): HttpRequest = addHeader(Pair<String?, String?>(name, value))

    @Public
    fun clearHeaders(): HttpRequest {
        headers = null
        return this
    }

    @Public
    fun addCookie(
        name: String?,
        value: String?,
    ): HttpRequest {
        if (name != null && value != null) {
            val cookieBuilder = this.cookieBuilder ?: CookieBuilder().also { this.cookieBuilder = it }
            cookieBuilder.append(name, value)
        }
        return this
    }

    @Public
    fun addCookie(cookie: String?): HttpRequest {
        if (cookie != null) {
            val cookieBuilder = this.cookieBuilder ?: CookieBuilder().also { this.cookieBuilder = it }
            cookieBuilder.append(cookie)
        }
        return this
    }

    @Public
    fun addCookie(builder: CookieBuilder?): HttpRequest {
        if (builder != null) {
            val cookieBuilder = this.cookieBuilder ?: CookieBuilder().also { this.cookieBuilder = it }
            cookieBuilder.append(builder)
        }
        return this
    }

    @Public
    fun clearCookies(): HttpRequest {
        cookieBuilder = null
        return this
    }

    @Public
    fun copy(): HttpRequest {
        val request = HttpRequest(uri, holder)
        request.setMethod(requestMethod, requestEntity)
        request.setSuccessOnly(successOnly)
        request.setRedirectHandler(redirectHandler)
        request.setValidator(validator)
        request.setKeepAlive(keepAlive)
        request.setOutputListener(outputListener)
        request.setTimeouts(connectTimeout, readTimeout)
        request.setDelay(delay)
        headers?.let { request.headers = ArrayList<Pair<String?, String?>?>(it) }
        request.addCookie(cookieBuilder)
        return request
    }

    @Public
    @Throws(HttpException::class)
    fun perform(): HttpResponse? {
        val verifyCertificate = holder.chan!!.locator.isUseHttps() && isVerifyCertificate
        val session =
            holder.createSession(
                client,
                uri,
                client.getProxy(holder.chan),
                verifyCertificate,
                delay,
                10,
            )
        return client.execute(session, this)
    }
}
