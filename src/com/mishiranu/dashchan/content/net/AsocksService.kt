package com.mishiranu.dashchan.content.net

import android.net.Uri
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.http.SimpleEntity
import chan.util.StringUtils
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.ProxyProvider.Binding
import com.mishiranu.dashchan.content.net.ProxyProvider.Configuration
import com.mishiranu.dashchan.content.net.ProxyProvider.NoPortsException
import com.mishiranu.dashchan.content.net.ProxyProvider.RefusedException
import com.mishiranu.dashchan.content.net.ProxyProvider.ServiceException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Asocks (https://docs.asocks.com), whose ports are bought one at a time. A port serves a single
 * country, so a forum wanting an address the account has no port for at all gets one bought for it --
 * the only thing here that spends the account's balance. Ports are never deleted.
 */
internal object AsocksService : ProxyService {
    override val id = "asocks"
    override val title = "Asocks"

    // The referral link is a build config field because the build that ships it is not this repo
    override val signUpUri = BuildConfig.URI_PROXIES

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
        /** Where the forums connect. Rotating the visible address leaves this endpoint untouched. */
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
        buyPorts: Boolean,
    ): Map<Chan, Binding> {
        readBalance(holder, configuration)?.let { outExtra?.put("balance", it) }
        val ports = readPorts(holder, configuration)
        // Fails loudly on a country the service does not know, rather than at the rotation the
        // ban warning offers
        for (chan in chans) {
            ProxyProvider.countryFor(configuration, chan)?.let { countries.resolve(holder, configuration, it) }
        }
        val assigned = LinkedHashMap<Chan, Binding>()
        for ((chan, port) in assignPorts(holder, configuration, chans, ports, buyPorts)) {
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
        val uri = createUri(configuration, "proxy", "refresh", binding.rotationId)
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
            ProxyProvider.buildProxy(configuration, port.host!!, port.port, port.login, port.password),
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
        configuration: Configuration,
        vararg path: String,
    ): Uri {
        val builder = Uri.parse(ENDPOINT).buildUpon()
        for (segment in path) {
            builder.appendPath(segment)
        }
        // The documentation names the parameter "apiKey" while the service's own PHP and Go clients
        // send "apikey": pass both and let the service read whichever it knows.
        builder.appendQueryParameter("apiKey", configuration.token)
        builder.appendQueryParameter("apikey", configuration.token)
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
        val request = HttpRequest(uri, holder)
        if (entity != null) {
            request.setPostMethod(entity)
        }
        val answer = ProxyProvider.perform(request)
        val jsonObject =
            try {
                JSONObject(answer.text.orEmpty())
            } catch (e: JSONException) {
                throw ProxyProvider.createInvalidResponse(answer.text, e)
            }
        // A served request answers "success": true, except for the port creation, which answers "data"
        if (!jsonObject.optBoolean("success") && !jsonObject.has("data")) {
            // A refused request carries its reason in "message"; a served one carries its payload
            // there, so it is only ever the reason on this branch.
            val message =
                readErrorMessage(jsonObject)
                    ?: if (answer.code == HttpURLConnection.HTTP_OK) {
                        // A refusal with nothing said about it and nothing to read it off of
                        throw ProxyProvider.createInvalidResponse(answer.text)
                    } else {
                        null
                    }
            // The status is worth showing on its own when the service named no reason, and worth
            // dropping when there is nothing wrong with it but the answer underneath
            val code = if (answer.code == HttpURLConnection.HTTP_OK) 0 else answer.code
            throw RefusedException(ErrorItem(code, message))
        }
        return jsonObject
    }

    @Throws(HttpException::class, ServiceException::class)
    private fun readBalance(
        holder: HttpHolder,
        configuration: Configuration,
    ): String? =
        StringUtils.nullIfEmpty(
            request(holder, createUri(configuration, "user", "balance")).optString("balance"),
        )

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
        val builder = createUri(configuration, "proxy", "ports").buildUpon()
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
     * The countries by everything they can be named with -- the two-letter code the ports report and
     * the name the directory lists -- against the identifier the port creation takes.
     */
    private val countries =
        CountryDirectory { holder, configuration ->
            val jsonObject = request(holder, createUri(configuration, "dir", "countries"))
            val list: JSONArray =
                jsonObject.optJSONArray("countries")
                    ?: jsonObject.optJSONObject("countries")?.optJSONArray("countries")
                    ?: throw ProxyProvider.createInvalidResponse(jsonObject.toString())
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
            map.ifEmpty { throw ProxyProvider.createInvalidResponse(jsonObject.toString()) }
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
                    put("country_code", countries.resolve(holder, configuration, country))
                    // The service requires both of these alongside the country and refuses the
                    // whole request without them
                    put("type_id", TYPE_ID_KEEP_PROXY)
                    put("proxy_type_id", PROXY_TYPE_ID_ALL)
                    put("name", PORT_NAME_PREFIX + StringUtils.emptyIfNull(chan.configuration.getTitle()))
                    put("count", 1)
                }.toString(),
        )
        request(holder, createUri(configuration, "proxy", "create-port"), entity)
        val knownIds = known.mapTo(HashSet()) { it.id }
        return readPorts(holder, configuration).firstOrNull { it.id !in knownIds } ?: throw NoPortsException()
    }

    /** The port a forum is already bound to: its proxy is the binding, as long as this class wrote it. */
    private fun boundPort(
        ports: List<Port>,
        chan: Chan,
    ): Port? {
        val proxy = ProxyProvider.appliedProxy(chan) ?: return null
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
        buyPorts: Boolean,
    ): Map<Chan, Port> {
        val known = ArrayList(ports)
        val assigned = LinkedHashMap<Chan, Port>()
        val claimed = HashSet<Int>()
        for (chan in chans) {
            val port = boundPort(known, chan)
            val country = ProxyProvider.countryFor(configuration, chan)
            if (port != null && !claimed.contains(port.id) && matchesCountry(port, country)) {
                assigned[chan] = port
                claimed.add(port.id)
            }
        }
        for (chan in chans) {
            if (assigned.containsKey(chan)) {
                continue
            }
            val country = ProxyProvider.countryFor(configuration, chan)
            val port =
                known.firstOrNull { !claimed.contains(it.id) && matchesCountry(it, country) }
                    // Every port has been handed out: share one that already exits from the country
                    // this forum wants rather than buy the account a second port just like it
                    ?: known.firstOrNull { matchesCountry(it, country) }
                    // Nothing in the country, so it has to be bought -- unless no country was
                    // named, in which case any port would have done and the account simply has none,
                    // or the caller may not spend the balance on one
                    ?: (
                        country?.takeIf { buyPorts }?.let {
                            createPort(holder, configuration, chan, it, known).also { port -> known.add(port) }
                        } ?: throw NoPortsException()
                    )
            assigned[chan] = port
            claimed.add(port.id)
        }
        return assigned
    }
}
