package com.mishiranu.dashchan.content.net

import android.net.Uri
import chan.http.HttpClient
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.http.SimpleEntity
import chan.util.StringUtils
import com.mishiranu.dashchan.R
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
 * [CaptchaSolving] is.
 *
 * The app neither buys nor deletes ports: it works with the port already bought, the one the
 * forums' proxy settings point at. What it does ask the service for is a new external address for
 * that port -- the endpoint the forums connect to stays the same, only the address they see
 * changes, which is exactly the way out of a ban on the visible address. When the settings name a
 * country, the port is moved there first, so the new address exits from where it was asked to.
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

    private class Configuration(
        val token: String,
        /** The port to rotate, or `null` to take the first one the account has. */
        val portId: Int?,
        /** The country the address should exit from, as a code or a name, or `null` for any. */
        val country: String?,
    )

    /** A proxy port of the account. [id] is what rotation is asked for. */
    class Port(
        val id: Int,
        val name: String?,
        /** The address the port currently exits from, as the service last saw it. */
        val address: String?,
        /** The two-letter code of the country the port exits from. */
        val location: String?,
    )

    private fun getConfiguration(): Configuration? {
        val map = Preferences.proxyProvider
        val token = map[Preferences.SUB_KEY_PROXY_PROVIDER_TOKEN]
        if (StringUtils.isEmpty(token)) {
            return null
        }
        val portId = map[Preferences.SUB_KEY_PROXY_PROVIDER_PORT]?.trim()?.toIntOrNull()
        val country = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_COUNTRY]?.trim())
        return Configuration(token!!, if (portId != null && portId > 0) portId else null, country)
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

    @Throws(HttpException::class, InvalidTokenException::class)
    private fun request(
        holder: HttpHolder,
        uri: Uri,
        entity: SimpleEntity? = null,
    ): JSONObject {
        val request = HttpRequest(uri, holder).setSuccessOnly(false)
        if (entity != null) {
            request.setPatchMethod(entity)
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
        // A served request answers "success": true, except for the update, which answers "data"
        if (!jsonObject.optBoolean("success") && !jsonObject.has("data")) {
            // A refused request carries its reason in "message"; a served one carries its payload
            // there, so this is only ever a string on this branch.
            val message = StringUtils.nullIfEmpty(jsonObject.optString("message"))
            throw if (responseCode == HttpURLConnection.HTTP_OK) {
                createInvalidResponse(message)
            } else {
                HttpException(responseCode, message)
            }
        }
        return jsonObject
    }

    @Throws(HttpException::class, InvalidTokenException::class)
    private fun readBalance(
        holder: HttpHolder,
        token: String,
    ): String? = StringUtils.nullIfEmpty(request(holder, createUri(token, "user", "balance")).optString("balance"))

    private fun parsePort(jsonObject: JSONObject): Port? {
        val id = jsonObject.optInt("id")
        return if (id > 0) {
            Port(
                id,
                StringUtils.nullIfEmpty(jsonObject.optString("name")),
                StringUtils.nullIfEmpty(jsonObject.optString("externalIp")),
                StringUtils.nullIfEmpty(jsonObject.optString("countryCode")),
            )
        } else {
            null
        }
    }

    /**
     * The port the rotation applies to: the configured one, or -- when the settings name no port --
     * one already exiting from the wanted country, so that an account holding a port per country
     * needs no further setup. Falls back to the first port the account holds, which the country is
     * then moved to.
     */
    @Throws(HttpException::class, InvalidTokenException::class, NoPortsException::class)
    private fun readPort(
        holder: HttpHolder,
        configuration: Configuration,
    ): Port {
        val builder = createUri(configuration.token, "proxy", "ports").buildUpon()
        if (configuration.portId != null) {
            builder.appendQueryParameter("id", configuration.portId.toString())
        }
        val proxies =
            request(holder, builder.build())
                .optJSONObject("message")
                ?.optJSONArray("proxies")
                ?: throw NoPortsException()
        val ports = ArrayList<Port>(proxies.length())
        for (i in 0..<proxies.length()) {
            proxies.optJSONObject(i)?.let { parsePort(it) }?.let { ports.add(it) }
        }
        if (ports.isEmpty()) {
            throw NoPortsException()
        }
        if (configuration.portId == null && configuration.country != null) {
            ports.firstOrNull { configuration.country.equals(it.location, ignoreCase = true) }?.let { return it }
        }
        return ports[0]
    }

    /**
     * The service's countries by everything they can be named with -- the two-letter code the ports
     * report and the name the directory lists -- against the identifier the port update takes. The
     * directory is the same for every account, so it is read once.
     */
    private var countries: Map<String, Int>? = null

    @Throws(HttpException::class, InvalidTokenException::class)
    private fun readCountries(
        holder: HttpHolder,
        token: String,
    ): Map<String, Int> {
        synchronized(this) {
            countries?.let { return it }
        }
        val jsonObject = request(holder, createUri(token, "dir", "countries"))
        val list: JSONArray =
            jsonObject.optJSONArray("countries")
                ?: jsonObject.optJSONObject("countries")?.optJSONArray("countries")
                ?: throw createInvalidResponse(jsonObject.toString())
        val map = HashMap<String, Int>()
        for (i in 0..<list.length()) {
            val country = list.optJSONObject(i) ?: continue
            val id = country.optInt("id")
            if (id <= 0) {
                continue
            }
            // The directory is not documented field by field: take the code under any of the names
            // it may go by, and the name as a second way to spell the same country
            for (key in COUNTRY_KEYS) {
                val value = StringUtils.nullIfEmpty(country.optString(key))
                if (value != null) {
                    map[value.uppercase(Locale.US)] = id
                }
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

    @Throws(HttpException::class, InvalidTokenException::class, UnknownCountryException::class)
    private fun readCountryId(
        holder: HttpHolder,
        token: String,
        country: String,
    ): Int = readCountries(holder, token)[country.uppercase(Locale.US)] ?: throw UnknownCountryException()

    /**
     * Move the port to another country. The service picks the new address out of that country the
     * next time the port is refreshed, so this is only ever half of the change.
     */
    @Throws(HttpException::class, InvalidTokenException::class, UnknownCountryException::class)
    private fun applyCountry(
        holder: HttpHolder,
        configuration: Configuration,
        port: Port,
        country: String,
    ) {
        val countryId = readCountryId(holder, configuration.token, country)
        val entity = SimpleEntity()
        entity.setContentType("application/json")
        entity.setData(
            JSONObject()
                .apply { put("geo_country_ids", JSONArray().put(countryId)) }
                .toString(),
        )
        request(
            holder,
            createUri(configuration.token, "proxy", "update-port", port.id.toString()),
            entity,
        )
    }

    /** What the settings screen shows about the account: its balance and the port rotation will act on. */
    @Throws(
        HttpException::class,
        InvalidTokenException::class,
        NoPortsException::class,
        UnknownCountryException::class,
    )
    fun checkService(holder: HttpHolder): Map<String, String> {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val extra = LinkedHashMap<String, String>()
        readBalance(holder, configuration.token)?.let { extra["balance"] = it }
        val port = readPort(holder, configuration)
        extra["port"] = if (port.name != null) "${port.id} (${port.name})" else port.id.toString()
        port.address?.let { extra["address"] = VisibleAddress.format(VisibleAddress.Result(it, port.location)) }
        if (configuration.country != null) {
            // Fails loudly on a country the service does not know, rather than at the rotation the
            // ban warning offers
            readCountryId(holder, configuration.token, configuration.country)
            extra["country"] = configuration.country.uppercase(Locale.US)
        }
        return extra
    }

    /**
     * Ask the service for a new external address on the configured port, out of the configured
     * country. The pooled connections are dropped along with it: one kept alive through the port
     * would still ride the old address.
     */
    @Throws(
        HttpException::class,
        InvalidTokenException::class,
        NoPortsException::class,
        UnknownCountryException::class,
    )
    fun refreshExternalAddress(holder: HttpHolder): Boolean {
        val configuration = getConfiguration() ?: throw InvalidTokenException()
        val port = readPort(holder, configuration)
        val country = configuration.country
        if (country != null && !country.equals(port.location, ignoreCase = true)) {
            applyCountry(holder, configuration, port, country)
        }
        val uri = createUri(configuration.token, "proxy", "refresh", port.id.toString())
        val success = request(holder, uri).optBoolean("success")
        if (success) {
            HttpClient.getInstance().dropCachedConnections()
        }
        return success
    }

    private val COUNTRY_KEYS = listOf("code", "country_code", "iso", "alpha2", "short_name", "name")

    private fun createInvalidResponse(
        message: String?,
        cause: Throwable? = null,
    ): HttpException = HttpException(ErrorItem.Type.INVALID_RESPONSE, true, false, Exception(message, cause))
}
