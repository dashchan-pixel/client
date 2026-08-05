package com.mishiranu.dashchan.content.net

import chan.content.Chan
import chan.content.ChanManager
import chan.http.HttpClient
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest
import chan.util.StringUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import java.net.HttpURLConnection
import java.util.LinkedHashMap

/**
 * A proxy service, configured once for the whole app the way [CaptchaSolving] is, and applied to the
 * forums the same way: the ones picked in the settings, or all of them when none is picked.
 *
 * A covered forum is bound to an endpoint of the account, which is written into its proxy settings --
 * that is what turns valid credentials into a working proxy without another step. A forum gets an
 * endpoint of its own while the account has one to spare, because an endpoint serves a single country
 * and a forum may name its own; past that the forums double up on the ones there are.
 *
 * The other half is rotation -- asking the service for a new address on a forum's endpoint. No
 * service moves the endpoint the forum connects to, only the address it is seen at changes, which is
 * the way out of a ban on the visible address.
 *
 * Everything a service does differently lives behind [ProxyService], one object per file, and
 * [SERVICES] is the whole list of them. That interface documents what writing another one takes.
 */
object ProxyProvider {
    /**
     * The services, in the order the settings offer them. Adding one is adding it here.
     *
     * The first is what a configuration that names no service reads as -- which is every one written
     * before there was a choice -- so the order of the first entry is not free.
     */
    private val SERVICES: List<ProxyService> = listOf(AsocksService, DataImpulseService)

    /** The service dropdown of the settings row, both halves off the one list. */
    val serviceEntries: List<CharSequence?>
        get() = SERVICES.map { it.title }

    val serviceValues: List<String>
        get() = SERVICES.map { it.id }

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

    /** The credentials are good but the account holds no endpoint to bind a forum to. */
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

    /** The settings as a service reads them. Which fields mean anything is the service's business. */
    internal class Configuration(
        val service: ProxyService,
        /** The API key of the account, or its proxy login for a service that authorizes by those. */
        val token: String,
        /** Only a service whose API takes the proxy credentials has one. */
        val password: String?,
        /** The country a forum's address exits from unless it names one of its own, or `null` for any. */
        val country: String?,
        /** The protocol the forums speak to their endpoint with. */
        val proxyType: String,
    ) {
        val socks: Boolean
            get() = proxyType == Preferences.VALUE_PROXY_TYPE_SOCKS
    }

    /** What a forum is bound to: the proxy settings it is given, and how those read in the UI. */
    internal class Binding(
        val proxy: Map<String, String>,
        /** The endpoint as the service's own rotation names it. */
        val rotationId: String,
        /** The single line the settings summary shows for the forum. */
        val description: String,
    )

    /** The answer of a request, once the shared handling has had it. */
    internal class Answer(
        val code: Int,
        val text: String?,
    )

    private fun getConfiguration(): Configuration? {
        val map = Preferences.proxyProvider
        val token = StringUtils.nullIfEmpty(map[Preferences.SUB_KEY_PROXY_PROVIDER_TOKEN]?.trim()) ?: return null
        val id = map[Preferences.SUB_KEY_PROXY_PROVIDER_SERVICE]
        // A configuration written before the choice existed names no service and is the first one
        val service = SERVICES.firstOrNull { it.id == id } ?: SERVICES.first()
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
    internal fun countryFor(
        configuration: Configuration,
        chan: Chan,
    ): String? = Preferences.getProxyProviderCountry(chan) ?: configuration.country

    /**
     * The proxy this class last wrote for [chan], or `null` when the user has since set one of their
     * own -- which is how a forum's endpoint is recovered, so that the address it has built a session
     * on survives a re-check.
     */
    internal fun appliedProxy(chan: Chan): Map<String, String>? {
        val applied = Preferences.getProxyProviderApplied(chan) ?: return null
        if (applied != Preferences.getPackedProxy(chan)) {
            // The user has since set a proxy of their own, and it is not ours to move
            return null
        }
        return Preferences.getProxy(chan)
    }

    /** An endpoint of the account as the forums' proxy settings spell it. */
    internal fun buildProxy(
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
     * Run [request] and answer what it said, with the two answers no service reads any differently
     * already dealt with: no answer at all is an invalid response, and credentials the account does
     * not open are an [InvalidTokenException]. Everything else is the service's to read, because a
     * status alone does not say whether an answer is a payload or a refusal.
     */
    @Throws(HttpException::class, ServiceException::class)
    internal fun perform(request: HttpRequest): Answer {
        val response = request.setSuccessOnly(false).perform() ?: throw createInvalidResponse(null)
        val code = response.getResponseCode()
        val text = response.readString()
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw InvalidTokenException()
        }
        return Answer(code, text)
    }

    internal fun createInvalidResponse(
        message: String?,
        cause: Throwable? = null,
    ): HttpException = HttpException(ErrorItem.Type.INVALID_RESPONSE, true, false, Exception(message, cause))

    /**
     * Write each forum's endpoint into its proxy settings, and take the proxy back off the forums the
     * provider no longer covers. A proxy set by hand on one of those is left alone: it does not
     * match what was written for that forum last time, and only what this class wrote is removed.
     *
     * An endpoint is unchanged by a refresh of its visible address, so this runs on a check, not on
     * every request.
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
     * What the settings screen shows about the account: what the service says about it, and the
     * endpoint each covered forum was given. The check is also what puts those endpoints into the
     * forums' settings, so validating the service and enabling it are one step.
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
     * Ask the service for a new address on the endpoint [chan] uses -- or on the one the settings
     * would give it, when it has none yet. The pooled connections are dropped along with it: one kept
     * alive through the endpoint would still ride the old address.
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
}
