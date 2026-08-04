package com.mishiranu.dashchan.content.net

import android.net.Uri
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

/**
 * The Asocks proxy service (https://docs.asocks.com), configured once for the whole app the way
 * [CaptchaSolving] is, and applied to the forums the same way: the ones picked in the settings, or
 * all of them when none is picked.
 *
 * A covered forum is bound to a port of the account, which is written into its proxy settings --
 * that is what turns a valid API key into a working proxy without another step. A forum gets a port
 * of its own while the account has one to spare, because a port serves a single country and a forum
 * may name its own; past that the forums double up on the ports there are. Only a country the
 * account holds no port in at all is worth buying one for, which is the only thing here that spends
 * the account's balance. Ports are never deleted.
 *
 * The other half is rotation -- "refresh external ip" in the service's own words: asking for a new
 * address on a forum's port. The endpoint the forum connects to stays the same, only the address it
 * is seen at changes, which is the way out of a ban on the visible address.
 */
object ProxyProvider {
    private const val ENDPOINT = "https://api.asocks.com/v2"

    /**
     * A refusal the settings are to blame for, as opposed to an [HttpException]: each one names the
     * way it reads in the UI, so the callers need no table of their own.
     */
    sealed class ServiceException : Exception() {
        abstract val errorItem: ErrorItem
    }

    /** The service answered, but not to an account this token opens. */
    class InvalidTokenException : ServiceException() {
        override val errorItem: ErrorItem
            get() = ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA)
    }

    /** The token is good but the account holds no port to rotate. */
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
     * buy a port with, a field it wanted was missing. Repeating its words beats the generic
     * "unknown error" a message it never sent used to come out as.
     */
    class RefusedException(
        override val errorItem: ErrorItem,
    ) : ServiceException()

    private class Configuration(
        val token: String,
        /** The country a forum's address exits from unless it names one of its own, or `null` for any. */
        val country: String?,
        /** The protocol the port was bought with -- a port serves one, and the service names neither. */
        val proxyType: String,
    )

    /** A proxy port of the account. [id] is what rotation is asked for. */
    class Port(
        val id: Int,
        val name: String?,
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

    private fun getConfiguration(): Configuration? {
        val map = Preferences.proxyProvider
        val token = map[Preferences.SUB_KEY_PROXY_PROVIDER_TOKEN]
        if (StringUtils.isEmpty(token)) {
            return null
        }
        val country = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_COUNTRY]?.trim())
        val proxyType =
            map[Preferences.SUB_KEY_PROXY_PROVIDER_TYPE].takeIf { it in Preferences.VALUES_PROXY_TYPE }
                ?: Preferences.VALUE_PROXY_TYPE_HTTP
        return Configuration(token!!, country, proxyType)
    }

    fun hasConfiguration(): Boolean = getConfiguration() != null

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
            StringUtils.nullIfEmpty(jsonObject.optString("name")),
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

    /** The port as the forums' proxy settings spell it, or `null` for a port with no endpoint yet. */
    private fun buildProxy(
        configuration: Configuration,
        port: Port,
    ): Map<String, String>? {
        if (StringUtils.isEmpty(port.host) || port.port <= 0) {
            return null
        }
        val proxy = LinkedHashMap<String, String>()
        proxy[Preferences.SUB_KEY_PROXY_HOST] = port.host!!
        proxy[Preferences.SUB_KEY_PROXY_PORT] = port.port.toString()
        proxy[Preferences.SUB_KEY_PROXY_TYPE] = configuration.proxyType
        port.login?.let { proxy[Preferences.SUB_KEY_PROXY_USERNAME] = it }
        port.password?.let { proxy[Preferences.SUB_KEY_PROXY_PASSWORD] = it }
        return proxy
    }

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

    private fun matchesCountry(
        port: Port,
        country: String?,
    ): Boolean = country == null || country.equals(port.location, ignoreCase = true)

    /** The port a forum is already bound to: its proxy is the binding, as long as this class wrote it. */
    private fun boundPort(
        ports: List<Port>,
        chan: Chan,
    ): Port? {
        val applied = Preferences.getProxyProviderApplied(chan) ?: return null
        if (applied != Preferences.getPackedProxy(chan)) {
            // The user has since set a proxy of their own, and it is not ours to move
            return null
        }
        val proxy = Preferences.getProxy(chan) ?: return null
        val host = proxy[Preferences.SUB_KEY_PROXY_HOST]
        val port = proxy[Preferences.SUB_KEY_PROXY_PORT]?.toIntOrNull()
        return ports.firstOrNull { it.host == host && it.port == port }
    }

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
        ports: List<Port>,
    ): Map<Chan, Port> {
        val known = ArrayList(ports)
        val assigned = LinkedHashMap<Chan, Port>()
        val claimed = HashSet<Int>()
        val chans = coveredChans()
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

    /**
     * Write each forum's port into its proxy settings, and take the proxy back off the forums the
     * provider no longer covers. Only a proxy this class wrote is ever overwritten or removed: one
     * set by hand does not match what was written for that forum last time, and is left alone.
     *
     * A port keeps its endpoint when its visible address is refreshed, so this runs on a check, not
     * on every request.
     */
    private fun applyToChans(
        configuration: Configuration?,
        assigned: Map<Chan, Port>,
    ) {
        for (chan in ChanManager.getInstance().availableChans) {
            if (chan.name == null) {
                continue
            }
            val port = assigned[chan]
            val packed = if (configuration != null && port != null) buildProxy(configuration, port)?.let { Preferences.packProxy(it) } else null
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
    fun clearFromChans() = applyToChans(null, emptyMap())

    /**
     * What the settings screen shows about the account: its balance and the port each covered forum
     * was given. The check is also what puts those ports into the forums' settings, so validating
     * the service and enabling it are one step.
     */
    @Throws(HttpException::class, ServiceException::class)
    fun checkService(holder: HttpHolder): Map<String, String> {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val extra = LinkedHashMap<String, String>()
        readBalance(holder, configuration.token)?.let { extra["balance"] = it }
        val ports = readPorts(holder, configuration)
        // Fails loudly on a country the service does not know, rather than at the rotation the ban
        // warning offers
        for (chan in coveredChans()) {
            countryFor(configuration, chan)?.let { resolveCountryCode(holder, configuration.token, it) }
        }
        val assigned = assignPorts(holder, configuration, ports)
        applyToChans(configuration, assigned)
        for ((chan, port) in assigned) {
            extra[StringUtils.emptyIfNull(chan.configuration.getTitle())] = describePort(port)
        }
        if (assigned.isEmpty()) {
            extra["forums"] = MainApplication.getInstance().localizedContext.getString(R.string.unavailable)
        }
        return extra
    }

    /** A port as the settings summary names it: which one it is and where it currently exits. */
    private fun describePort(port: Port): String {
        val address = port.address?.let { VisibleAddress.format(VisibleAddress.Result(it, port.location)) }
        return if (address != null) "${port.id} · $address" else port.id.toString()
    }

    /**
     * Ask the service for a new address on the port [chan] uses -- or on the port the settings
     * would give it, when it has none yet -- out of the country that forum asks for. The pooled
     * connections are dropped along with it: one kept alive through the port would still ride the
     * old address.
     */
    @Throws(HttpException::class, ServiceException::class)
    fun refreshVisibleAddress(
        holder: HttpHolder,
        chan: Chan,
    ): Boolean {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val ports = readPorts(holder, configuration)
        val assigned = assignPorts(holder, configuration, ports)
        // The forum is covered by the provider, or the caller would not offer the rotation
        // A country of its own has already put the forum on a port of its own
        val port = assigned[chan] ?: assigned.values.firstOrNull() ?: throw NoPortsException()
        val uri = createUri(configuration.token, "proxy", "refresh", port.id.toString())
        val success = request(holder, uri).optBoolean("success")
        if (success) {
            // The endpoint is unchanged, but the credentials behind it may not be
            applyToChans(configuration, assigned)
            HttpClient.getInstance().dropCachedConnections()
        }
        return success
    }

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

    private fun createInvalidResponse(
        message: String?,
        cause: Throwable? = null,
    ): HttpException = HttpException(ErrorItem.Type.INVALID_RESPONSE, true, false, Exception(message, cause))
}
