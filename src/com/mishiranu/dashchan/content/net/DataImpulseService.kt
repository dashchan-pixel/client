package com.mishiranu.dashchan.content.net

import android.net.Uri
import android.util.Base64
import android.util.Log
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.util.StringUtils
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.ProxyProvider.Binding
import com.mishiranu.dashchan.content.net.ProxyProvider.Configuration
import com.mishiranu.dashchan.content.net.ProxyProvider.NoPortsException
import com.mishiranu.dashchan.content.net.ProxyProvider.RefusedException
import com.mishiranu.dashchan.content.net.ProxyProvider.ServiceException
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

/**
 * DataImpulse (https://docs.dataimpulse.com), whose ports cost nothing: a plan comes with a range of
 * sticky ports on one shared gateway, and a forum is given one of them. The country is a parameter on
 * the login rather than a property of the port, so a forum naming its own costs nothing either.
 *
 * The user API the proxy documentation does not mention -- documented at
 * https://documenter.getpostman.com/view/7041120/2sAY4rGRZC -- takes the same login and password as
 * the proxies themselves, over HTTP basic authorization, and is what reads the plan, lists the sticky
 * ports it allows, names the countries it holds addresses in, and rotates a port.
 *
 * Kept as a second option rather than the one to reach for: the service asks for 50 USD once the
 * trial plan is spent, which is more than a user is going to put down to unstick one forum.
 */
internal object DataImpulseService : ProxyService {
    override val id = "dataimpulse"
    override val title = "DataImpulse"
    override val signUpUri = "https://dataimpulse.com/?aff=692183e5-8fff-4fe6-86e6-26514220f9bd"

    private const val TAG = "DataImpulseService"

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

    /** The sticky ports come with the plan, so [buyPorts] has nothing to forbid here. */
    override fun assign(
        holder: HttpHolder,
        configuration: Configuration,
        chans: List<Chan>,
        outExtra: MutableMap<String, String>?,
        buyPorts: Boolean,
    ): Map<Chan, Binding> {
        // Reading the plan is also what proves the credentials: there is nothing else here that a
        // wrong password would fail on until a forum's traffic quietly stopped leaving the gateway
        readPlan(holder, configuration, outExtra)
        if (chans.isEmpty()) {
            return emptyMap()
        }
        // Fails loudly on a country the service does not know, rather than at the rotation the
        // ban warning offers
        val resolved = HashMap<Chan, String>()
        for (chan in chans) {
            ProxyProvider
                .countryFor(configuration, chan)
                ?.let { resolved[chan] = countries.resolve(holder, configuration, it) }
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
                assigned[chan] = bind(configuration, port, resolved[chan])
            }
        }
        for (chan in chans) {
            if (assigned.containsKey(chan)) {
                continue
            }
            val port = ports.firstOrNull { it !in claimed } ?: throw NoPortsException()
            claimed.add(port)
            assigned[chan] = bind(configuration, port, resolved[chan])
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
        // A refusal -- another address asked for before the service is willing to give one, which it
        // is not within 30 seconds of the last -- comes back as a status the request helper repeats
        request(holder, configuration, uri)
        return true
    }

    private fun bind(
        configuration: Configuration,
        port: Int,
        country: String?,
    ): Binding {
        // The service spells its country codes lowercase, where the directory answers them uppercase
        val code = country?.lowercase(Locale.US)
        val login =
            if (code != null) {
                "${configuration.token}$PARAMETERS_PREFIX$PARAMETER_COUNTRY.$code"
            } else {
                configuration.token
            }
        return Binding(
            ProxyProvider.buildProxy(configuration, HOST, port, login, configuration.password),
            port.toString(),
            if (country != null) "$port · $country" else port.toString(),
        )
    }

    private fun buildUri(path: String): Uri =
        Uri
            .parse(API)
            .buildUpon()
            .appendPath(path)
            .build()

    /**
     * The raw answer of an endpoint. Both halves of the service take the same credentials, which the
     * API expects as basic authorization rather than as a parameter.
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
        val answer = ProxyProvider.perform(HttpRequest(uri, holder).addHeader("Authorization", "Basic $credentials"))
        if (answer.code != HttpURLConnection.HTTP_OK) {
            throw HttpException(answer.code, readMessage(answer.text))
        }
        return answer.text
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
                throw ProxyProvider.createInvalidResponse(responseText, e)
            }
        // Every endpoint answers a status, and a served request answers "ok"
        val status = jsonObject.optString("status")
        if (status != STATUS_OK) {
            val reason =
                StringUtils.nullIfEmpty(jsonObject.optString("message"))
                    ?: StringUtils.nullIfEmpty(status)
                    ?: throw ProxyProvider.createInvalidResponse(responseText)
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
     * The sticky ports the plan allows, lowest first. A plan may be allowed only a part of the range,
     * and the listing is the only place that says which -- so when it fails or answers in a shape this
     * does not read, the range is taken from its start instead and a port the plan does not allow is
     * left to fail as the forum's own traffic.
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
        val proxy = ProxyProvider.appliedProxy(chan) ?: return null
        if (proxy[Preferences.SUB_KEY_PROXY_HOST] != HOST) {
            return null
        }
        // A sticky port serves either protocol, so a forum keeps it across a change of the setting
        return proxy[Preferences.SUB_KEY_PROXY_PORT]
            ?.toIntOrNull()
            ?.takeIf { it in STICKY_PORT_FIRST..STICKY_PORT_LAST }
    }

    /**
     * The countries by code and by the name the service lists them under. Only the ones it currently
     * holds addresses in are listed, which is what makes it worth asking rather than reading the
     * platform's own list of countries.
     */
    private val countries =
        CountryDirectory { holder, configuration ->
            val uri =
                buildUri("pool_stats_with_parameters")
                    .buildUpon()
                    .appendQueryParameter("groupby", "country")
                    .build()
            val jsonObject = request(holder, configuration, uri)
            val items = jsonObject.optJSONArray("items") ?: throw ProxyProvider.createInvalidResponse(jsonObject.toString())
            val map = HashMap<String, String>()
            for (i in 0..<items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // "key" is the code the login takes, "label" the name it reads as in the dashboard
                val code = StringUtils.nullIfEmpty(item.optString("key"))?.uppercase(Locale.US) ?: continue
                map[code] = code
                StringUtils.nullIfEmpty(item.optString("label"))?.let { map[it.uppercase(Locale.US)] = code }
            }
            map.ifEmpty { throw ProxyProvider.createInvalidResponse(jsonObject.toString()) }
        }
}
