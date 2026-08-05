package com.mishiranu.dashchan.content.net

import android.net.Uri
import android.util.Base64
import android.util.Log
import chan.content.Chan
import chan.content.ChanManager
import chan.http.HttpClient
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.http.SimpleEntity
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

/**
 * A proxy service, configured once for the whole app the way [CaptchaSolving] is, and applied to the
 * forums the same way: the ones picked in the settings, or all of them when none is picked. Two are
 * supported, [AsocksService] and [DataImpulseService], and the settings say which one the account
 * belongs to.
 *
 * A covered forum is bound to a port of the account, which is written into its proxy settings -- that
 * is what turns valid credentials into a working proxy without another step. A forum gets a port of
 * its own while the account has one to spare, because a port serves a single country and a forum may
 * name its own; past that the forums double up on the ports there are.
 *
 * The other half is rotation -- asking the service for a new address on a forum's port. Neither
 * service moves the endpoint the forum connects to, only the address it is seen at changes, which is
 * the way out of a ban on the visible address.
 */
object ProxyProvider {
    private const val TAG = "ProxyProvider"

    /**
     * A refusal the settings or the account are to blame for, as opposed to an [HttpException]: each
     * one names the way it reads in the UI, so the callers need no table of their own.
     */
    sealed class ServiceException : Exception() {
        abstract val errorItem: ErrorItem
    }

    /** The service answered, but not to an account these credentials open. */
    class InvalidTokenException : ServiceException() {
        override val errorItem: ErrorItem
            get() = ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA)
    }

    /** The credentials are good but the account holds no port to bind a forum to. */
    class NoPortsException : ServiceException() {
        override val errorItem: ErrorItem
            get() = ErrorItem(R.string.proxy_provider_no_ports)
    }

    /** The country in the settings is not one the service offers. */
    class UnknownCountryException : ServiceException() {
        override val errorItem: ErrorItem
            get() = ErrorItem(R.string.unknown_country)
    }

    /**
     * A refusal the service explained itself, carried as it reads -- the account has no balance to
     * buy a port with, a field it wanted was missing, another address was asked for before it is
     * willing to give one. Repeating its words beats the generic "unknown error" a message it never
     * sent used to come out as.
     */
    class RefusedException(
        override val errorItem: ErrorItem,
    ) : ServiceException()

    private class Configuration(
        val service: Service,
        /** The API key of the account, or its proxy login for a service that authorizes by those. */
        val token: String,
        /** Only [DataImpulseService] has one: its API and its proxies take the same credentials. */
        val password: String?,
        /** The country a forum's address exits from unless it names one of its own, or `null` for any. */
        val country: String?,
        /** The protocol the forums speak to their port with. */
        val proxyType: String,
    ) {
        val socks: Boolean
            get() = proxyType == Preferences.VALUE_PROXY_TYPE_SOCKS
    }

    /** What a forum is bound to: the proxy settings it is given, and how those read in the UI. */
    private class Binding(
        val proxy: Map<String, String>,
        /** The endpoint as the service's own rotation names it. */
        val rotationId: String,
        /** The single line the settings summary shows for the forum. */
        val description: String,
    )

    private interface Service {
        /** Whether the settings hold everything this service needs. */
        fun isConfigured(configuration: Configuration): Boolean

        /**
         * Bind every forum of [chans] to a port of the account, writing what the settings screen
         * shows about the account itself into [outExtra]. Reading the account is also what proves
         * the credentials, so this is what a check of the service amounts to.
         */
        @Throws(HttpException::class, ServiceException::class)
        fun assign(
            holder: HttpHolder,
            configuration: Configuration,
            chans: List<Chan>,
            outExtra: MutableMap<String, String>?,
        ): Map<Chan, Binding>

        /** Ask for another visible address on the port [chan] is bound to. */
        @Throws(HttpException::class, ServiceException::class)
        fun rotate(
            holder: HttpHolder,
            configuration: Configuration,
            chan: Chan,
            assigned: Map<Chan, Binding>,
        ): Boolean
    }

    private fun getConfiguration(): Configuration? {
        val map = Preferences.proxyProvider
        val token = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_TOKEN]?.trim()) ?: return null
        val service =
            when (map[Preferences.SUB_KEY_PROXY_PROVIDER_SERVICE]) {
                Preferences.VALUE_PROXY_PROVIDER_SERVICE_DATAIMPULSE -> DataImpulseService

                // A configuration written before the choice existed names no service and is Asocks
                else -> AsocksService
            }
        val password = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_PASSWORD])
        val country = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_COUNTRY]?.trim())
        val proxyType =
            map[Preferences.SUB_KEY_PROXY_PROVIDER_TYPE].takeIf { it in Preferences.VALUES_PROXY_TYPE }
                ?: Preferences.VALUE_PROXY_TYPE_HTTP
        return Configuration(service, token, password, country, proxyType)
            .takeIf { service.isConfigured(it) }
    }

    fun hasConfiguration(): Boolean = getConfiguration() != null

    /** The forums the provider writes to. An empty selection means all of them, as it does for solving. */
    private fun coveredChans(): List<Chan> {
        val chanNames = Preferences.proxyProviderChans
        return ChanManager
            .getInstance()
            .availableChans
            .filter { it.name != null && (chanNames.isEmpty() || chanNames.contains(it.name)) }
    }

    fun coversChan(chan: Chan): Boolean {
        if (!hasConfiguration() || chan.name == null) {
            return false
        }
        val chanNames = Preferences.proxyProviderChans
        return chanNames.isEmpty() || chanNames.contains(chan.name)
    }

    /** The country the forum's address should exit from: its own setting, or the provider's default. */
    private fun countryFor(
        configuration: Configuration,
        chan: Chan,
    ): String? = Preferences.getProxyProviderCountry(chan) ?: configuration.country

    /**
     * The proxy this class last wrote for [chan], or `null` when the user has since set one of their
     * own -- which is how a forum's port is recovered, so that the address it has built a session on
     * survives a re-check.
     */
    private fun appliedProxy(chan: Chan): Map<String, String>? {
        val applied = Preferences.getProxyProviderApplied(chan) ?: return null
        if (applied != Preferences.getPackedProxy(chan)) {
            // The user has since set a proxy of their own, and it is not ours to move
            return null
        }
        return Preferences.getProxy(chan)
    }

    /** An endpoint of the account as the forums' proxy settings spell it. */
    private fun buildProxy(
        configuration: Configuration,
        host: String,
        port: Int,
        login: String?,
        password: String?,
    ): Map<String, String> {
        val proxy = LinkedHashMap<String, String>()
        proxy[Preferences.SUB_KEY_PROXY_HOST] = host
        proxy[Preferences.SUB_KEY_PROXY_PORT] = port.toString()
        proxy[Preferences.SUB_KEY_PROXY_TYPE] = configuration.proxyType
        login?.let { proxy[Preferences.SUB_KEY_PROXY_USERNAME] = it }
        password?.let { proxy[Preferences.SUB_KEY_PROXY_PASSWORD] = it }
        return proxy
    }

    /**
     * Write each forum's port into its proxy settings, and take the proxy back off the forums the
     * provider no longer covers. A proxy set by hand on one of those is left alone: it does not
     * match what was written for that forum last time, and only what this class wrote is removed.
     *
     * A port keeps its endpoint when its visible address is refreshed, so this runs on a check, not
     * on every request.
     */
    private fun applyToChans(assigned: Map<Chan, Binding>) {
        for (chan in ChanManager.getInstance().availableChans) {
            if (chan.name == null) {
                continue
            }
            val packed = assigned[chan]?.let { Preferences.packProxy(it.proxy) }
            val stored = Preferences.getPackedProxy(chan)
            val applied = Preferences.getProxyProviderApplied(chan)
            if (packed != null) {
                if (stored != packed) {
                    Preferences.setPackedProxy(chan, packed)
                }
                Preferences.setProxyProviderApplied(chan, packed)
            } else if (applied != null) {
                if (stored == applied) {
                    Preferences.setPackedProxy(chan, null)
                }
                Preferences.setProxyProviderApplied(chan, null)
            }
        }
    }

    /**
     * Take the provider's proxy back off the forums, for when the settings that put it there are
     * gone. A proxy set by hand stays.
     */
    fun clearFromChans() = applyToChans(emptyMap())

    /**
     * What the settings screen shows about the account: what the service says about it, and the port
     * each covered forum was given. The check is also what puts those ports into the forums'
     * settings, so validating the service and enabling it are one step.
     */
    @Throws(HttpException::class, ServiceException::class)
    fun checkService(holder: HttpHolder): Map<String, String> {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val extra = LinkedHashMap<String, String>()
        val assigned = configuration.service.assign(holder, configuration, coveredChans(), extra)
        applyToChans(assigned)
        for ((chan, binding) in assigned) {
            extra[StringUtils.emptyIfNull(chan.configuration.getTitle())] = binding.description
        }
        if (assigned.isEmpty()) {
            extra["forums"] = MainApplication.getInstance().localizedContext.getString(R.string.unavailable)
        }
        return extra
    }

    /**
     * Ask the service for a new address on the port [chan] uses -- or on the port the settings would
     * give it, when it has none yet. The pooled connections are dropped along with it: one kept
     * alive through the port would still ride the old address.
     */
    @Throws(HttpException::class, ServiceException::class)
    fun refreshVisibleAddress(
        holder: HttpHolder,
        chan: Chan,
    ): Boolean {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val assigned = configuration.service.assign(holder, configuration, coveredChans(), null)
        val success = configuration.service.rotate(holder, configuration, chan, assigned)
        if (success) {
            // The endpoint is unchanged, but the credentials behind it may not be
            applyToChans(assigned)
            HttpClient.getInstance().dropCachedConnections()
        }
        return success
    }

    private fun createInvalidResponse(
        message: String?,
        cause: Throwable? = null,
    ): HttpException = HttpException(ErrorItem.Type.INVALID_RESPONSE, true, false, Exception(message, cause))

    /**
     * Asocks (https://docs.asocks.com), whose ports are bought one at a time. A port serves a single
     * country, so a forum wanting an address the account has no spare port for gets a port bought for
     * it -- the only thing here that spends the account's balance. Ports are never deleted.
     */
    private object AsocksService : Service {
        private const val ENDPOINT = "https://api.asocks.com/v2"

        /** One page is plenty: the forums are counted in units, not hundreds. */
        private const val PORTS_PER_PAGE = 100

        /** Ports the app buys are named after the forum they serve, so the account can tell them apart. */
        private const val PORT_NAME_PREFIX = "Dashchan: "

        /**
         * A bought port keeps its address until the rotation asks for another one. The other two the
         * service offers -- 2, a new address per connection, and 3, one on a timer -- take the choice
         * away from the forum, which is the opposite of what a port bound to one is for.
         */
        private const val TYPE_ID_KEEP_PROXY = 1

        /**
         * Any pool the account can draw from, rather than residential (1), mobile (3) or corporate (4)
         * alone: the port is bought because a country has to be served, so the widest pool serves it.
         */
        private const val PROXY_TYPE_ID_ALL = 2

        private val COUNTRY_KEYS = listOf("code", "country_code", "iso", "alpha2", "short_name", "name")

        /** A proxy port of the account. [id] is what rotation is asked for. */
        private class Port(
            val id: Int,
            /** The address the port currently exits from, as the service last saw it. */
            val address: String?,
            /** The two-letter code of the country the port exits from. */
            val location: String?,
            /** Where the forums connect. Refreshing the visible address leaves this endpoint untouched. */
            val host: String?,
            val port: Int,
            val login: String?,
            val password: String?,
        )

        /** The API key is all it takes. */
        override fun isConfigured(configuration: Configuration): Boolean = true

        override fun assign(
            holder: HttpHolder,
            configuration: Configuration,
            chans: List<Chan>,
            outExtra: MutableMap<String, String>?,
        ): Map<Chan, Binding> {
            readBalance(holder, configuration.token)?.let { outExtra?.put("balance", it) }
            val ports = readPorts(holder, configuration)
            // Fails loudly on a country the service does not know, rather than at the rotation the
            // ban warning offers
            for (chan in chans) {
                countryFor(configuration, chan)?.let { resolveCountryCode(holder, configuration.token, it) }
            }
            val assigned = LinkedHashMap<Chan, Binding>()
            for ((chan, port) in assignPorts(holder, configuration, chans, ports)) {
                // A port the service has not given an endpoint yet binds nothing, and the forum is
                // left to go out unproxied rather than through a half-written proxy
                bind(configuration, port)?.let { assigned[chan] = it }
            }
            return assigned
        }

        override fun rotate(
            holder: HttpHolder,
            configuration: Configuration,
            chan: Chan,
            assigned: Map<Chan, Binding>,
        ): Boolean {
            // The forum is covered by the provider, or the caller would not offer the rotation.
            // A country of its own has already put the forum on a port of its own
            val binding = assigned[chan] ?: assigned.values.firstOrNull() ?: throw NoPortsException()
            val uri = createUri(configuration.token, "proxy", "refresh", binding.rotationId)
            return request(holder, uri).optBoolean("success")
        }

        private fun bind(
            configuration: Configuration,
            port: Port,
        ): Binding? {
            if (StringUtils.isEmpty(port.host) || port.port <= 0) {
                return null
            }
            return Binding(
                buildProxy(configuration, port.host!!, port.port, port.login, port.password),
                port.id.toString(),
                describePort(port),
            )
        }

        /** A port as the settings summary names it: which one it is and where it currently exits. */
        private fun describePort(port: Port): String {
            val address = port.address?.let { VisibleAddress.format(VisibleAddress.Result(it, port.location)) }
            return if (address != null) "${port.id} · $address" else port.id.toString()
        }

        private fun createUri(
            token: String,
            vararg path: String,
        ): Uri {
            val builder = Uri.parse(ENDPOINT).buildUpon()
            for (segment in path) {
                builder.appendPath(segment)
            }
            // The documentation names the parameter "apiKey" while the service's own PHP and Go clients
            // send "apikey": pass both and let the service read whichever it knows.
            builder.appendQueryParameter("apiKey", token)
            builder.appendQueryParameter("apikey", token)
            return builder.build()
        }

        /**
         * The service's own words for a refusal: the message it sends with one, else the first of the
         * per-field errors it sends instead when the request did not validate.
         */
        private fun readErrorMessage(jsonObject: JSONObject): String? {
            StringUtils.nullIfEmpty(jsonObject.optString("message"))?.let { return it }
            val errors = jsonObject.opt("errors")
            // A request that did not validate answers with a field to its messages instead of a message
            if (errors is JSONObject) {
                return errors
                    .keys()
                    .asSequence()
                    .mapNotNull { firstErrorMessage(errors.opt(it)) }
                    .firstOrNull()
            }
            return firstErrorMessage(errors)
        }

        /** One of the messages a field was refused with, or the refusal itself when it is a bare string. */
        private fun firstErrorMessage(value: Any?): String? {
            val message = if (value is JSONArray) value.optString(0) else value?.toString()
            return StringUtils.nullIfEmpty(message)
        }

        @Throws(HttpException::class, ServiceException::class)
        private fun request(
            holder: HttpHolder,
            uri: Uri,
            entity: SimpleEntity? = null,
        ): JSONObject {
            val request = HttpRequest(uri, holder).setSuccessOnly(false)
            if (entity != null) {
                request.setPostMethod(entity)
            }
            val response = request.perform() ?: throw createInvalidResponse(null)
            val responseCode = response.getResponseCode()
            val responseText = response.readString()
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw InvalidTokenException()
            }
            val jsonObject =
                try {
                    JSONObject(responseText.orEmpty())
                } catch (e: JSONException) {
                    throw createInvalidResponse(responseText, e)
                }
            // A served request answers "success": true, except for the port creation, which answers "data"
            if (!jsonObject.optBoolean("success") && !jsonObject.has("data")) {
                // A refused request carries its reason in "message"; a served one carries its payload
                // there, so it is only ever the reason on this branch.
                val message =
                    readErrorMessage(jsonObject)
                        ?: if (responseCode == HttpURLConnection.HTTP_OK) {
                            // A refusal with nothing said about it and nothing to read it off of
                            throw createInvalidResponse(responseText)
                        } else {
                            null
                        }
                // The status is worth showing on its own when the service named no reason, and worth
                // dropping when there is nothing wrong with it but the answer underneath
                val code = if (responseCode == HttpURLConnection.HTTP_OK) 0 else responseCode
                throw RefusedException(ErrorItem(code, message))
            }
            return jsonObject
        }

        @Throws(HttpException::class, ServiceException::class)
        private fun readBalance(
            holder: HttpHolder,
            token: String,
        ): String? = StringUtils.nullIfEmpty(request(holder, createUri(token, "user", "balance")).optString("balance"))

        private fun parsePort(jsonObject: JSONObject): Port? {
            val id = jsonObject.optInt("id")
            if (id <= 0) {
                return null
            }
            // The endpoint comes as a single "host:port" string
            val endpoint = StringUtils.nullIfEmpty(jsonObject.optString("proxy"))
            val separator = endpoint?.lastIndexOf(':') ?: -1
            val host = if (separator > 0) endpoint!!.substring(0, separator) else null
            val port = if (separator > 0) endpoint!!.substring(separator + 1).toIntOrNull() ?: -1 else -1
            return Port(
                id,
                StringUtils.nullIfEmpty(jsonObject.optString("externalIp")),
                StringUtils.nullIfEmpty(jsonObject.optString("countryCode")),
                host,
                port,
                StringUtils.nullIfEmpty(jsonObject.optString("login")),
                StringUtils.nullIfEmpty(jsonObject.optString("password")),
            )
        }

        /**
         * The ports of the account. Reading them all is what lets a forum keep its own port, and with
         * it its own country.
         */
        @Throws(HttpException::class, ServiceException::class)
        private fun readPorts(
            holder: HttpHolder,
            configuration: Configuration,
        ): List<Port> {
            val builder = createUri(configuration.token, "proxy", "ports").buildUpon()
            builder.appendQueryParameter("per_page", PORTS_PER_PAGE.toString())
            val proxies =
                request(holder, builder.build())
                    .optJSONObject("message")
                    ?.optJSONArray("proxies")
                    ?: return emptyList()
            val ports = ArrayList<Port>(proxies.length())
            for (i in 0..<proxies.length()) {
                proxies.optJSONObject(i)?.let { parsePort(it) }?.let { ports.add(it) }
            }
            return ports
        }

        /**
         * The service's countries by everything they can be named with -- the two-letter code the ports
         * report and the name the directory lists -- against the identifier the port update takes. The
         * directory is the same for every account, so it is read once.
         */
        private var countries: Map<String, String>? = null

        @Throws(HttpException::class, ServiceException::class)
        private fun readCountries(
            holder: HttpHolder,
            token: String,
        ): Map<String, String> {
            synchronized(this) {
                countries?.let { return it }
            }
            val jsonObject = request(holder, createUri(token, "dir", "countries"))
            val list: JSONArray =
                jsonObject.optJSONArray("countries")
                    ?: jsonObject.optJSONObject("countries")?.optJSONArray("countries")
                    ?: throw createInvalidResponse(jsonObject.toString())
            val map = HashMap<String, String>()
            for (i in 0..<list.length()) {
                val country = list.optJSONObject(i) ?: continue
                // The directory is not documented field by field: read every name a country may go by,
                // and take the two-letter one among them as the code a port is created with
                val names =
                    COUNTRY_KEYS.mapNotNull { StringUtils.nullIfEmpty(country.optString(it))?.uppercase(Locale.US) }
                val code = names.firstOrNull { it.length == 2 } ?: continue
                for (name in names) {
                    map[name] = code
                }
            }
            if (map.isEmpty()) {
                throw createInvalidResponse(jsonObject.toString())
            }
            synchronized(this) {
                countries = map
            }
            return map
        }

        /** The two-letter code of a country named by code or by name in the settings. */
        @Throws(HttpException::class, ServiceException::class)
        private fun resolveCountryCode(
            holder: HttpHolder,
            token: String,
            country: String,
        ): String {
            val name = country.uppercase(Locale.US)
            readCountries(holder, token)[name]?.let { return it }
            // A directory that answered in a shape this does not read must not stand in the way of a
            // code that is already a code
            if (name.length == 2) {
                return name
            }
            throw UnknownCountryException()
        }

        /**
         * Buy a port in [country] for [chan], the last resort of a forum whose country the account
         * holds no port in -- a port serves one country, and none of the ones there are serve this one.
         * The service names the port after the forum, which is how it reads on the account's dashboard
         * afterwards.
         *
         * The created port is picked out of a fresh listing rather than the creation's own answer: the
         * listing is the shape everything else here reads.
         */
        @Throws(HttpException::class, ServiceException::class)
        private fun createPort(
            holder: HttpHolder,
            configuration: Configuration,
            chan: Chan,
            country: String,
            known: List<Port>,
        ): Port {
            val entity = SimpleEntity()
            entity.setContentType("application/json")
            entity.setData(
                JSONObject()
                    .apply {
                        put("country_code", resolveCountryCode(holder, configuration.token, country))
                        // The service requires both of these alongside the country and refuses the
                        // whole request without them
                        put("type_id", TYPE_ID_KEEP_PROXY)
                        put("proxy_type_id", PROXY_TYPE_ID_ALL)
                        put("name", PORT_NAME_PREFIX + StringUtils.emptyIfNull(chan.configuration.getTitle()))
                        put("count", 1)
                    }.toString(),
            )
            request(holder, createUri(configuration.token, "proxy", "create-port"), entity)
            val knownIds = known.mapTo(HashSet()) { it.id }
            return readPorts(holder, configuration).firstOrNull { it.id !in knownIds } ?: throw NoPortsException()
        }

        /** The port a forum is already bound to: its proxy is the binding, as long as this class wrote it. */
        private fun boundPort(
            ports: List<Port>,
            chan: Chan,
        ): Port? {
            val proxy = appliedProxy(chan) ?: return null
            val host = proxy[Preferences.SUB_KEY_PROXY_HOST]
            val port = proxy[Preferences.SUB_KEY_PROXY_PORT]?.toIntOrNull()
            return ports.firstOrNull { it.host == host && it.port == port }
        }

        private fun matchesCountry(
            port: Port,
            country: String?,
        ): Boolean = country == null || country.equals(port.location, ignoreCase = true)

        /**
         * Give every covered forum a port in the country it asks for. A forum keeps the port it already
         * holds while that port still fits, so the binding -- and with it the address a forum has built
         * a session on -- survives a re-check. Otherwise it takes a port of the account that no other
         * forum has taken and that sits in the right country.
         *
         * Forums double up on a port before anything is bought. A port of the account already in the
         * right country serves a second forum as well as it serves the first, so nothing but the
         * independence of their rotations is spent by sharing it -- and buying is only worth that when
         * the account holds no port in the country at all.
         */
        @Throws(HttpException::class, ServiceException::class)
        private fun assignPorts(
            holder: HttpHolder,
            configuration: Configuration,
            chans: List<Chan>,
            ports: List<Port>,
        ): Map<Chan, Port> {
            val known = ArrayList(ports)
            val assigned = LinkedHashMap<Chan, Port>()
            val claimed = HashSet<Int>()
            for (chan in chans) {
                val port = boundPort(known, chan)
                if (port != null && !claimed.contains(port.id) && matchesCountry(port, countryFor(configuration, chan))) {
                    assigned[chan] = port
                    claimed.add(port.id)
                }
            }
            for (chan in chans) {
                if (assigned.containsKey(chan)) {
                    continue
                }
                val country = countryFor(configuration, chan)
                val port =
                    known.firstOrNull { !claimed.contains(it.id) && matchesCountry(it, country) }
                        // Every port has been handed out: share one that already exits from the country
                        // this forum wants rather than buy the account a second port just like it
                        ?: known.firstOrNull { matchesCountry(it, country) }
                        // Nothing in the country, so it has to be bought -- unless no country was named,
                        // in which case any port would have done and the account simply has none
                        ?: (
                            country?.let {
                                createPort(holder, configuration, chan, it, known).also { port -> known.add(port) }
                            } ?: throw NoPortsException()
                        )
                assigned[chan] = port
                claimed.add(port.id)
            }
            return assigned
        }
    }

    /**
     * DataImpulse (https://docs.dataimpulse.com), whose ports cost nothing: a plan comes with a range
     * of sticky ports on one shared gateway, and a forum is given one of them. The country is a
     * parameter on the login rather than a property of the port, so a forum naming its own costs
     * nothing either.
     *
     * The user API the proxy documentation does not mention -- documented at
     * https://documenter.getpostman.com/view/7041120/2sAY4rGRZC -- takes the same login and password
     * as the proxies themselves, over HTTP basic authorization, and is what reads the account and
     * rotates a port.
     */
    private object DataImpulseService : Service {
        private const val API = "https://gw.dataimpulse.com:777/api"
        private const val HOST = "gw.dataimpulse.com"
        private const val STATUS_OK = "ok"

        /** The range every plan's sticky ports come out of; a plan may be allowed only a part of it. */
        private const val STICKY_PORT_FIRST = 10000
        private const val STICKY_PORT_LAST = 20000

        /** Asked of the port listing when the forums need fewer, so there is room to spread them out. */
        private const val STICKY_PORTS_MINIMUM = 8

        /** The login carries its targeting parameters after this, `key.value` pairs separated by `;`. */
        private const val PARAMETERS_PREFIX = "__"
        private const val PARAMETER_COUNTRY = "cr"

        /** Both halves of the service take the proxy credentials, so both are needed. */
        override fun isConfigured(configuration: Configuration): Boolean = !StringUtils.isEmpty(configuration.password)

        override fun assign(
            holder: HttpHolder,
            configuration: Configuration,
            chans: List<Chan>,
            outExtra: MutableMap<String, String>?,
        ): Map<Chan, Binding> {
            // Reading the plan is also what proves the credentials: there is nothing else here that a
            // wrong password would fail on until a forum's traffic quietly stopped leaving the gateway
            readPlan(holder, configuration, outExtra)
            if (chans.isEmpty()) {
                return emptyMap()
            }
            // Fails loudly on a country the service does not know, rather than at the rotation the
            // ban warning offers
            val countries = HashMap<Chan, String>()
            for (chan in chans) {
                countryFor(configuration, chan)
                    ?.let { countries[chan] = resolveCountryCode(holder, configuration, it) }
            }
            val assigned = LinkedHashMap<Chan, Binding>()
            val ports = readStickyPorts(holder, configuration, chans.size)
            val claimed = HashSet<Int>()
            // A forum keeps the port it already holds, so the address it has built a session on
            // survives a re-check. The country rides on the login, so it never costs a forum its port
            for (chan in chans) {
                val port = boundPort(chan)
                if (port != null && port !in claimed) {
                    claimed.add(port)
                    assigned[chan] = bind(configuration, port, countries[chan])
                }
            }
            for (chan in chans) {
                if (assigned.containsKey(chan)) {
                    continue
                }
                val port = ports.firstOrNull { it !in claimed } ?: throw NoPortsException()
                claimed.add(port)
                assigned[chan] = bind(configuration, port, countries[chan])
            }
            return assigned
        }

        override fun rotate(
            holder: HttpHolder,
            configuration: Configuration,
            chan: Chan,
            assigned: Map<Chan, Binding>,
        ): Boolean {
            // The forum is covered by the provider, or the caller would not offer the rotation
            val binding = assigned[chan] ?: assigned.values.firstOrNull() ?: throw NoPortsException()
            val uri =
                buildUri("rotate_ip")
                    .buildUpon()
                    .appendQueryParameter("port", binding.rotationId)
                    .build()
            // A refusal -- another address asked for before the service is willing to give one --
            // comes back as a status of its own, which the request helper turns into the message shown
            request(holder, configuration, uri)
            return true
        }

        private fun bind(
            configuration: Configuration,
            port: Int,
            country: String?,
        ): Binding {
            val login =
                if (country != null) {
                    "${configuration.token}$PARAMETERS_PREFIX$PARAMETER_COUNTRY.$country"
                } else {
                    configuration.token
                }
            return Binding(
                buildProxy(configuration, HOST, port, login, configuration.password),
                port.toString(),
                if (country != null) "$port · ${country.uppercase(Locale.US)}" else port.toString(),
            )
        }

        private fun buildUri(path: String): Uri =
            Uri
                .parse(API)
                .buildUpon()
                .appendPath(path)
                .build()

        /**
         * The raw answer of an endpoint. Both halves of the service take the same credentials, which
         * the API expects as basic authorization rather than as a parameter.
         */
        @Throws(HttpException::class, ServiceException::class)
        private fun read(
            holder: HttpHolder,
            configuration: Configuration,
            uri: Uri,
        ): String? {
            val credentials =
                Base64.encodeToString(
                    "${configuration.token}:${StringUtils.emptyIfNull(configuration.password)}".toByteArray(),
                    Base64.NO_WRAP,
                )
            val response =
                HttpRequest(uri, holder)
                    .addHeader("Authorization", "Basic $credentials")
                    .setSuccessOnly(false)
                    .perform() ?: throw createInvalidResponse(null)
            val responseCode = response.getResponseCode()
            val responseText = response.readString()
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw InvalidTokenException()
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw HttpException(responseCode, readMessage(responseText))
            }
            return responseText
        }

        /** The reason an answer carries, for the answers that are JSON at all. */
        private fun readMessage(responseText: String?): String? =
            try {
                StringUtils.nullIfEmpty(JSONObject(responseText.orEmpty()).optString("message"))
            } catch (e: JSONException) {
                // An error the gateway itself answered, rather than the API behind it
                Log.w(TAG, "The refusal carried no readable reason", e)
                null
            }

        @Throws(HttpException::class, ServiceException::class)
        private fun request(
            holder: HttpHolder,
            configuration: Configuration,
            uri: Uri,
        ): JSONObject {
            val responseText = read(holder, configuration, uri)
            val jsonObject =
                try {
                    JSONObject(responseText.orEmpty())
                } catch (e: JSONException) {
                    throw createInvalidResponse(responseText, e)
                }
            // Every endpoint answers a status, and a served request answers "ok"
            val status = jsonObject.optString("status")
            if (status != STATUS_OK) {
                val reason =
                    StringUtils.nullIfEmpty(jsonObject.optString("message"))
                        ?: StringUtils.nullIfEmpty(status)
                        ?: throw createInvalidResponse(responseText)
                throw RefusedException(ErrorItem(reason))
            }
            return jsonObject
        }

        /** The traffic the plan has left, which is also what a wrong password fails on. */
        @Throws(HttpException::class, ServiceException::class)
        private fun readPlan(
            holder: HttpHolder,
            configuration: Configuration,
            outExtra: MutableMap<String, String>?,
        ) {
            val jsonObject = request(holder, configuration, buildUri("stats"))
            if (outExtra != null) {
                val trafficLeft = jsonObject.optLong("traffic_left", -1)
                if (trafficLeft >= 0) {
                    outExtra["traffic left"] = formatTraffic(trafficLeft)
                }
            }
        }

        /** The allowance is counted in gigabytes, where the shared formatter stops at megabytes. */
        private fun formatTraffic(bytes: Long): String =
            if (bytes >= 1000L * 1000L * 1000L) {
                String.format(Locale.US, "%.2f GB", bytes / 1000.0 / 1000.0 / 1000.0)
            } else {
                StringUtils.formatFileSize(bytes, true)
            }

        /**
         * The sticky ports the plan allows, lowest first. A plan may be allowed only a part of the
         * range, and the listing is the only place that says which -- so when it fails or answers in a
         * shape this does not read, the range is taken from its start instead and a port the plan does
         * not allow is left to fail as the forum's own traffic.
         */
        @Throws(ServiceException::class)
        private fun readStickyPorts(
            holder: HttpHolder,
            configuration: Configuration,
            count: Int,
        ): List<Int> {
            val quantity = max(count, STICKY_PORTS_MINIMUM)
            val listed =
                try {
                    val uri =
                        buildUri("list")
                            .buildUpon()
                            .appendQueryParameter("type", "sticky")
                            .appendQueryParameter("protocol", if (configuration.socks) "socks5" else "http")
                            .appendQueryParameter("quantity", quantity.toString())
                            .build()
                    // One "login:password@host:port" per line
                    read(holder, configuration, uri)
                        .orEmpty()
                        .lineSequence()
                        .mapNotNull { it.substringAfterLast(':', "").trim().toIntOrNull() }
                        .filter { it in STICKY_PORT_FIRST..STICKY_PORT_LAST }
                        .distinct()
                        .sorted()
                        .toList()
                } catch (e: HttpException) {
                    Log.w(TAG, "Failed to read the sticky ports of the plan", e)
                    emptyList()
                }
            return listed.ifEmpty { List(quantity) { STICKY_PORT_FIRST + it } }
        }

        /** The sticky port [chan] already holds, as long as this class wrote it. */
        private fun boundPort(chan: Chan): Int? {
            val proxy = appliedProxy(chan) ?: return null
            if (proxy[Preferences.SUB_KEY_PROXY_HOST] != HOST) {
                return null
            }
            // A sticky port serves either protocol, so a forum keeps it across a change of the setting
            return proxy[Preferences.SUB_KEY_PROXY_PORT]
                ?.toIntOrNull()
                ?.takeIf { it in STICKY_PORT_FIRST..STICKY_PORT_LAST }
        }

        /**
         * The service's countries by the two-letter code and by the name it lists them under. Only the
         * countries it currently holds addresses in are listed, which is what makes it worth asking
         * rather than reading the platform's own list. It is the same for every account, so it is read
         * once.
         */
        private var countries: Map<String, String>? = null

        @Throws(HttpException::class, ServiceException::class)
        private fun readCountries(
            holder: HttpHolder,
            configuration: Configuration,
        ): Map<String, String> {
            synchronized(this) {
                countries?.let { return it }
            }
            val uri =
                buildUri("pool_stats_with_parameters")
                    .buildUpon()
                    .appendQueryParameter("groupby", "country")
                    .build()
            val jsonObject = request(holder, configuration, uri)
            val items = jsonObject.optJSONArray("items") ?: throw createInvalidResponse(jsonObject.toString())
            val map = HashMap<String, String>()
            for (i in 0..<items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // "key" is the code the login takes, "label" the name it reads as in the dashboard
                val code = StringUtils.nullIfEmpty(item.optString("key"))?.uppercase(Locale.US) ?: continue
                map[code] = code
                StringUtils.nullIfEmpty(item.optString("label"))?.let { map[it.uppercase(Locale.US)] = code }
            }
            if (map.isEmpty()) {
                throw createInvalidResponse(jsonObject.toString())
            }
            synchronized(this) {
                countries = map
            }
            return map
        }

        /** The two-letter code of a country named by code or by name in the settings, as the login spells it. */
        @Throws(HttpException::class, ServiceException::class)
        private fun resolveCountryCode(
            holder: HttpHolder,
            configuration: Configuration,
            country: String,
        ): String {
            val name = country.uppercase(Locale.US)
            readCountries(holder, configuration)[name]?.let { return it.lowercase(Locale.US) }
            // A directory that answered in a shape this does not read must not stand in the way of a
            // code that is already a code
            if (name.length == 2) {
                return name.lowercase(Locale.US)
            }
            throw UnknownCountryException()
        }
    }
}
