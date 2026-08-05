package com.mishiranu.dashchan.content.net

import chan.http.HttpException
import chan.http.HttpHolder
import java.util.Locale

/**
 * The countries one [ProxyService] offers, by every name they can be given in the settings: the
 * two-letter code and whatever the service's own directory lists them under. The directory is the
 * same for every account, so it is read once and kept for the process.
 *
 * [load] answers the map, uppercase names against uppercase codes. A service that spells its codes
 * lowercase on the wire lowercases what [resolve] answers, rather than storing them that way -- the
 * fallback below has to be able to recognize a code the directory never mentioned.
 */
internal class CountryDirectory(
    private val load: (HttpHolder, ProxyProvider.Configuration) -> Map<String, String>,
) {
    private var countries: Map<String, String>? = null

    /**
     * The two-letter code of a country named by code or by name in the settings, uppercase. A
     * directory that answered in a shape the service does not read must not stand in the way of a
     * code that is already a code, so one is taken at its word.
     */
    @Throws(HttpException::class, ProxyProvider.ServiceException::class)
    fun resolve(
        holder: HttpHolder,
        configuration: ProxyProvider.Configuration,
        country: String,
    ): String {
        val name = country.uppercase(Locale.US)
        read(holder, configuration)[name]?.let { return it }
        if (name.length == 2) {
            return name
        }
        throw ProxyProvider.UnknownCountryException()
    }

    @Throws(HttpException::class, ProxyProvider.ServiceException::class)
    private fun read(
        holder: HttpHolder,
        configuration: ProxyProvider.Configuration,
    ): Map<String, String> {
        synchronized(this) {
            countries?.let { return it }
        }
        val map = load(holder, configuration)
        synchronized(this) {
            countries = map
        }
        return map
    }
}
