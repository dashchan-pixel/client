package com.mishiranu.dashchan.content.net

import android.net.Uri
import android.util.Log
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import chan.http.HttpRequest

/**
 * The address a forum sees this device at -- the one its bans are assigned to, and the one that
 * tells whether the proxy configured for the forum is actually carrying its traffic.
 *
 * It is read from Cloudflare's `/cdn-cgi/trace`, which reports back the address of whoever asked.
 * The forum's own endpoint is asked first, so the answer is literally what the forum sees. Not
 * every forum is behind Cloudflare -- for the rest the question goes to Cloudflare directly, still
 * through [HttpHolder]'s chan, so it takes the same proxy that forum's traffic takes and comes back
 * with the address of the same exit.
 */
object VisibleAddress {
    private const val TAG = "VisibleAddress"

    /** The response is a couple dozen `key=value` lines; anything longer isn't a trace. */
    private const val MAX_TRACE_LINES = 64

    private val FALLBACK_URI: Uri = Uri.parse("https://1.1.1.1/cdn-cgi/trace")

    /** [location] is the two-letter country Cloudflare places the address in, when it can. */
    class Result(
        val address: String,
        val location: String?,
    )

    fun resolve(
        chan: Chan,
        holder: HttpHolder,
    ): Result? = read(chan.locator.buildPath("cdn-cgi", "trace"), holder) ?: read(FALLBACK_URI, holder)

    private fun read(
        uri: Uri?,
        holder: HttpHolder,
    ): Result? =
        if (uri == null) {
            // A forum with no host of its own, e.g. a local one: only the fallback can answer
            null
        } else {
            try {
                parse(HttpRequest(uri, holder).perform()?.readString())
            } catch (e: HttpException) {
                // A forum that isn't behind Cloudflare answers /cdn-cgi/trace with a 404.
                Log.w(TAG, "Failed to read the trace from $uri", e)
                null
            }
        }

    private fun parse(text: String?): Result? {
        if (text.isNullOrEmpty()) {
            return null
        }
        var address: String? = null
        var location: String? = null
        for (line in text.lineSequence().take(MAX_TRACE_LINES)) {
            val separator = line.indexOf('=')
            if (separator >= 0) {
                when (line.substring(0, separator)) {
                    "ip" -> address = line.substring(separator + 1).trim()
                    "loc" -> location = line.substring(separator + 1).trim()
                }
            }
        }
        if (address.isNullOrEmpty()) {
            return null
        }
        // Cloudflare answers "XX" when it can't place the address, common behind Tor exits.
        return Result(address, if (location.isNullOrEmpty() || location == "XX") null else location)
    }

    /** The address with its country appended, for a single line of UI. */
    fun format(result: Result?): String =
        when {
            result == null -> ""
            result.location == null -> result.address
            else -> "${result.address} · ${result.location}"
        }
}
