package chan.http

import android.net.Uri
import android.util.Pair
import chan.annotation.Public
import com.mishiranu.dashchan.content.Preferences.isVerifyCertificate
import java.net.HttpURLConnection
import java.util.Objects

@Public
class HttpRequest {
    @Public
    interface Preset {
        val holder: HttpHolder?
    }

    interface TimeoutsPreset : Preset {
        val connectTimeout: Int
        val readTimeout: Int
    }

    interface OutputListenerPreset : Preset {
        val outputListener: OutputListener?
    }

    interface RangePreset : Preset {
        val rangeStart: Long
        val rangeEnd: Long
    }

    interface OutputListener {
        fun onOutputProgressChange(progress: Long, progressMax: Long)
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
            RETRANSMIT
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
            val STRICT: RedirectHandler = RedirectHandler { response: HttpResponse? ->
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
        GET, HEAD, POST, PUT, DELETE
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

    // TODO CHAN
    // Remove this constructor after updating (also used by the client itself)
    // alphachan alterchan brchan chaosach exach fiftyfive fourplebs haibane kropyvach lainchan nulltirech onechanca
    // synch twentyseven uboachan wizardchan
    // Added: 18.10.20 18:58
    @Public
    constructor(uri: Uri?, holder: HttpHolder) {
        Objects.requireNonNull<HttpHolder?>(holder)
        this.uri = uri
        this.holder = holder
        client = HttpClient.getInstance()
    }

    @Public
    constructor(uri: Uri?, preset: Preset?) {
        val holder: HttpHolder = (if (preset != null) preset.holder else null)!!
        Objects.requireNonNull<HttpHolder?>(holder)
        this.uri = uri
        this.holder = holder
        client = HttpClient.getInstance()
        if (preset is TimeoutsPreset) {
            setTimeouts(preset.connectTimeout, preset.readTimeout)
        }
        if (preset is OutputListenerPreset) {
            setOutputListener(preset.outputListener)
        }
        if (preset is RangePreset) {
            val rangePreset = preset
            setRange(rangePreset.rangeStart, rangePreset.rangeEnd)
        }
    }

    private fun setMethod(method: RequestMethod?, entity: RequestEntity?): HttpRequest {
        requestMethod = method
        requestEntity = entity
        return this
    }

    @Public
    fun setGetMethod(): HttpRequest {
        return setMethod(RequestMethod.GET, null)
    }

    @Public
    fun setHeadMethod(): HttpRequest {
        return setMethod(RequestMethod.HEAD, null)
    }

    @Public
    fun setPostMethod(entity: RequestEntity?): HttpRequest {
        return setMethod(RequestMethod.POST, entity)
    }

    @Public
    fun setPutMethod(entity: RequestEntity?): HttpRequest {
        return setMethod(RequestMethod.PUT, entity)
    }

    @Public
    fun setDeleteMethod(entity: RequestEntity?): HttpRequest {
        return setMethod(RequestMethod.DELETE, entity)
    }

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
    fun setTimeouts(connectTimeout: Int, readTimeout: Int): HttpRequest {
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

    fun setRange(start: Long, end: Long): HttpRequest {
        this.rangeStart = start
        this.rangeEnd = end
        return this
    }

    private fun addHeader(header: Pair<String?, String?>?): HttpRequest {
        if (header != null && header.first != null && header.second != null) {
            if (headers == null) {
                headers = ArrayList<Pair<String?, String?>?>()
            }
            headers!!.add(header)
        }
        return this
    }

    @Public
    fun addHeader(name: String?, value: String?): HttpRequest {
        return addHeader(Pair<String?, String?>(name, value))
    }

    @Public
    fun clearHeaders(): HttpRequest {
        headers = null
        return this
    }

    @Public
    fun addCookie(name: String?, value: String?): HttpRequest {
        if (name != null && value != null) {
            if (cookieBuilder == null) {
                cookieBuilder = CookieBuilder()
            }
            cookieBuilder!!.append(name, value)
        }
        return this
    }

    @Public
    fun addCookie(cookie: String?): HttpRequest {
        if (cookie != null) {
            if (cookieBuilder == null) {
                cookieBuilder = CookieBuilder()
            }
            cookieBuilder!!.append(cookie)
        }
        return this
    }

    @Public
    fun addCookie(builder: CookieBuilder?): HttpRequest {
        if (builder != null) {
            if (cookieBuilder == null) {
                cookieBuilder = CookieBuilder()
            }
            cookieBuilder!!.append(builder)
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
        val session = holder.createSession(
            client, uri, client.getProxy(holder.chan!!),
            verifyCertificate, delay, 10
        )
        return client.execute(session, this)
    }
}
