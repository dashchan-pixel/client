package com.mishiranu.dashchan.content.net

import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder

/**
 * One proxy service behind [ProxyProvider]. Every service is one object in one file, and adding a
 * service is writing that file and naming it in [ProxyProvider.SERVICES] -- nothing else knows how
 * many there are.
 *
 * ## What a service is asked for
 *
 * Two things, and the shared half of the feature does the rest:
 *
 * - [assign] gives every covered forum an endpoint of the account. Reading the account is also what
 *   proves the credentials, so this doubles as the check the settings screen runs -- a service with
 *   no account endpoint to read has to prove them some other way before it returns.
 * - [rotate] asks for another visible address on the endpoint one forum holds. It must leave the
 *   endpoint itself alone: [ProxyProvider] writes the same proxy back afterwards and drops the
 *   pooled connections, and a service that moved the forum elsewhere would strand it.
 *
 * ## What a service gets
 *
 * - [ProxyProvider.appliedProxy] -- the proxy last written for a forum, or `null` once the user has
 *   set one of their own. This is how a forum keeps the endpoint it already holds, which is what
 *   makes the address it has built a session on survive a re-check. Every service wants this.
 * - [ProxyProvider.buildProxy] -- an endpoint as the forums' proxy settings spell it.
 * - [ProxyProvider.perform] -- a request with the answers every service handles the same way already
 *   handled: no answer at all, and credentials the account does not open.
 * - [CountryDirectory] -- the countries a service offers, read once, resolving a name or a code from
 *   the settings to the code the service spells it with.
 * - The [ProxyProvider.ServiceException] family. Throw [ProxyProvider.InvalidTokenException] for
 *   credentials, [ProxyProvider.NoPortsException] when the account has no endpoint to hand out,
 *   [ProxyProvider.UnknownCountryException] for a country the service does not offer, and
 *   [ProxyProvider.RefusedException] to repeat a refusal in the service's own words. Each one already
 *   knows how it reads in the UI; the callers need no table of their own.
 *
 * ## What a service does not get
 *
 * Fields of its own on the settings row. [com.mishiranu.dashchan.ui.preference.MultipleEditPreference]
 * builds the dialog once from a fixed list, so the fields cannot follow the service dropdown -- they
 * are named for every service at once (`API key or login`, `Password`) and a service reads the ones
 * it needs. A service wanting something none of the others do has to widen that fixed list, and the
 * labels have to stay true for the services that leave it empty.
 */
internal interface ProxyService {
    /**
     * What the settings store to name this service. It is written into the preferences, so it
     * outlives any renaming of the object and must never change.
     */
    val id: String

    /** The service as it calls itself, which is how the settings list it. Not translated. */
    val title: String

    /**
     * Where an account is opened, as the row for users who have none offers it. A referral link
     * where the service has one -- the app is given away, and this is what pays for it.
     */
    val signUpUri: String

    /** Whether the settings hold everything this service needs -- a password, say, if it takes one. */
    fun isConfigured(configuration: ProxyProvider.Configuration): Boolean

    /**
     * Bind every forum of [chans] to an endpoint of the account, writing what the settings screen
     * shows about the account itself -- a balance, an allowance -- into [outExtra]. It is `null` when
     * nothing is going to be shown, which is the rotation asking for the bindings on its way through.
     *
     * A forum left out of the answer is left unproxied: [ProxyProvider] takes its proxy back off.
     *
     * [buyPorts] `false` forbids spending anything of the user's: a forum the account holds no
     * endpoint for is refused with [ProxyProvider.NoPortsException] rather than given one that has to
     * be bought. A service whose endpoints come with the plan has nothing to forbid.
     */
    @Throws(HttpException::class, ProxyProvider.ServiceException::class)
    fun assign(
        holder: HttpHolder,
        configuration: ProxyProvider.Configuration,
        chans: List<Chan>,
        outExtra: MutableMap<String, String>?,
        buyPorts: Boolean = true,
    ): Map<Chan, ProxyProvider.Binding>

    /**
     * Ask for another visible address on the endpoint [chan] is bound to, [assigned] being what
     * [assign] has just answered. `false` is a refusal with nothing to say about itself; anything the
     * service did explain belongs in a [ProxyProvider.RefusedException] instead.
     */
    @Throws(HttpException::class, ProxyProvider.ServiceException::class)
    fun rotate(
        holder: HttpHolder,
        configuration: ProxyProvider.Configuration,
        chan: Chan,
        assigned: Map<Chan, ProxyProvider.Binding>,
    ): Boolean
}
