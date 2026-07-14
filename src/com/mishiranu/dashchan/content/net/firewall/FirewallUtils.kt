package com.mishiranu.dashchan.content.net.firewall

internal object FirewallUtils {
    @JvmStatic
    fun parseCookies(cookiesString: String?): Map<String, String> {
        if (cookiesString.isNullOrEmpty()) {
            return emptyMap()
        }
        val cookies = HashMap<String, String>()
        for (pair in cookiesString.split(";\\s*".toRegex())) {
            val index = pair.indexOf('=')
            if (index >= 0) {
                cookies[pair.substring(0, index)] = pair.substring(index + 1)
            }
        }
        return cookies
    }
}
