package com.mishiranu.dashchan.content.net

import chan.content.Chan
import chan.http.HttpClient
import com.mishiranu.dashchan.content.Preferences
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/**
 * A forum's proxy as the one line every service hands it out on -- `scheme://user:password@host:port`.
 *
 * The settings screen keeps a proxy as five separate fields, which is the right shape to type into by
 * hand and the wrong one to pass anywhere: a service writes its endpoint as a connection string, and a
 * script that has to change one (see [com.mishiranu.dashchan.content.CommandApp]) would otherwise have
 * to know how the five are packed into the preference. So this is the form the script speaks, and the
 * packing stays where it belongs.
 *
 * `scheme` says which of the two kinds of proxy the app is being handed, since nothing else in the
 * string does: `http`/`https` for one, `socks`/`socks4`/`socks4a`/`socks5`/`socks5h` for the other. It
 * is required rather than guessed -- a string read as the wrong kind connects to nothing, and does it
 * silently.
 *
 * The credentials are percent-decoded, because a proxy password routinely holds an `@` or a `:` and a
 * service that hands one out has to escape it. `+` is left alone: it is a literal in a userinfo field,
 * whatever a query string would make of it.
 */
object ProxyConnection {
    /** The schemes a connection string may name, against the proxy type the app stores for each. */
    private val SCHEMES =
        mapOf(
            "http" to Preferences.VALUE_PROXY_TYPE_HTTP,
            "https" to Preferences.VALUE_PROXY_TYPE_HTTP,
            "socks" to Preferences.VALUE_PROXY_TYPE_SOCKS,
            "socks4" to Preferences.VALUE_PROXY_TYPE_SOCKS,
            "socks4a" to Preferences.VALUE_PROXY_TYPE_SOCKS,
            "socks5" to Preferences.VALUE_PROXY_TYPE_SOCKS,
            "socks5h" to Preferences.VALUE_PROXY_TYPE_SOCKS,
        )

    /** How a proxy of each type is written back out, the scheme being all that tells them apart. */
    private val TYPE_SCHEMES =
        mapOf(
            Preferences.VALUE_PROXY_TYPE_HTTP to "http",
            Preferences.VALUE_PROXY_TYPE_SOCKS to "socks5",
        )

    /**
     * The proxy [value] spells out, as the settings store one. Throws with what is wrong with it --
     * the message reaches whoever wrote the string, so it names the part that failed rather than the
     * string as a whole.
     */
    fun parse(value: String): Map<String, String> {
        val text = value.trim()
        val schemeEnd = text.indexOf("://")
        require(schemeEnd > 0) {
            "A proxy is written scheme://user:password@host:port, and the scheme is not optional " +
                "(${SCHEMES.keys.joinToString("/")})"
        }
        val scheme = text.substring(0, schemeEnd).lowercase()
        val proxyType =
            requireNotNull(SCHEMES[scheme]) {
                "\"$scheme\" is not a proxy scheme this app speaks (${SCHEMES.keys.joinToString("/")})"
            }
        // A dashboard that copies its endpoint with a trailing slash has still named a proxy, and a
        // proxy has no path for anything after it to belong to.
        val authority = text.substring(schemeEnd + 3).substringBefore('/')
        // Last '@', because the credentials may hold one and the host may not
        val at = authority.lastIndexOf('@')
        val credentials = if (at >= 0) authority.substring(0, at) else null
        val endpoint = authority.substring(at + 1)
        // Same for the port separator: an IPv6 host is written [::1]:1080, colons and all
        val portStart = endpoint.lastIndexOf(':')
        require(portStart > 0) { "\"$endpoint\" names no port, and a proxy is reached at host:port" }
        val host = endpoint.substring(0, portStart)
        val port = endpoint.substring(portStart + 1).toIntOrNull()
        require(port != null && port in 1..65535) {
            "\"${endpoint.substring(portStart + 1)}\" is not a port number"
        }
        require(host.none { it.isWhitespace() }) { "\"$host\" is not a host name" }
        val proxy = LinkedHashMap<String, String>()
        proxy[Preferences.SUB_KEY_PROXY_HOST] = host
        proxy[Preferences.SUB_KEY_PROXY_PORT] = port.toString()
        proxy[Preferences.SUB_KEY_PROXY_TYPE] = proxyType
        if (!credentials.isNullOrEmpty()) {
            // First ':', the other way round from the host: a password may hold one, a login may not
            val passwordStart = credentials.indexOf(':')
            val login = if (passwordStart >= 0) credentials.substring(0, passwordStart) else credentials
            val password = if (passwordStart >= 0) credentials.substring(passwordStart + 1) else ""
            if (login.isNotEmpty()) {
                proxy[Preferences.SUB_KEY_PROXY_USERNAME] = decode(login)
            }
            if (password.isNotEmpty()) {
                proxy[Preferences.SUB_KEY_PROXY_PASSWORD] = decode(password)
            }
        }
        return proxy
    }

    /**
     * [proxy] as one connection string, or `null` when there is no proxy in it -- a stored proxy with
     * no host is what a forum with none looks like, the preference being a packing of five fields
     * rather than a value that can be absent field by field.
     */
    fun format(proxy: Map<String, String>?): String? {
        val host = proxy?.get(Preferences.SUB_KEY_PROXY_HOST)?.takeIf { it.isNotEmpty() } ?: return null
        val port = proxy[Preferences.SUB_KEY_PROXY_PORT]?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = TYPE_SCHEMES[proxy[Preferences.SUB_KEY_PROXY_TYPE]] ?: TYPE_SCHEMES.getValue(Preferences.VALUE_PROXY_TYPE_HTTP)
        val login = proxy[Preferences.SUB_KEY_PROXY_USERNAME]?.takeIf { it.isNotEmpty() }
        val password = proxy[Preferences.SUB_KEY_PROXY_PASSWORD]?.takeIf { it.isNotEmpty() }
        return buildString {
            append(scheme).append("://")
            if (login != null || password != null) {
                append(encode(login.orEmpty()))
                if (password != null) {
                    append(':').append(encode(password))
                }
                append('@')
            }
            append(host).append(':').append(port)
        }
    }

    /** The proxy [chan] is set to use, as a connection string, or `null` when it has none. */
    fun get(chan: Chan): String? = format(Preferences.getProxy(chan))

    /**
     * Give [chan] the proxy [value] spells out, or take its proxy away when [value] is `null`, and drop
     * the pooled connections either way.
     *
     * The drop is the half that cannot be left to the caller: a connection kept alive through the old
     * proxy would go on being used, so the request the change was made for would still take the old
     * route and nothing about the failure would point here.
     */
    fun set(
        chan: Chan,
        value: String?,
    ) {
        val proxy = value?.let { parse(it) }
        Preferences.setPackedProxy(chan, proxy?.let { Preferences.packProxy(it) })
        HttpClient.getInstance().dropCachedConnections()
    }

    /** `%XX` only -- `+` is a literal here, unlike in a query string (see [java.net.URLDecoder]). */
    private fun decode(value: String): String {
        if ('%' !in value) {
            return value
        }
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            val escape =
                if (character == '%' && index + 2 < value.length) {
                    value.substring(index + 1, index + 3).toIntOrNull(16)
                } else {
                    null
                }
            if (escape != null) {
                bytes.write(escape)
                index += 3
            } else {
                // Not an escape after all: a stray '%' is a character like any other here
                bytes.write(character.toString().toByteArray())
                index++
            }
        }
        return String(bytes.toByteArray())
    }

    /**
     * Escapes what would otherwise be read as a delimiter of the string being built, and what is not a
     * character at all -- and only that, so a password stays as readable as the service wrote it.
     */
    private fun encode(value: String): String =
        buildString {
            for (byte in value.toByteArray()) {
                val code = byte.toInt() and 0xff
                val character = code.toChar()
                if (code >= 0x80 || character in "%:@/?#" || character.isWhitespace()) {
                    append('%').append("%02X".format(code))
                } else {
                    append(character)
                }
            }
        }
}
