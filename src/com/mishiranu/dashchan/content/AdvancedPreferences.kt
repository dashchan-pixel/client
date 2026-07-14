package com.mishiranu.dashchan.content

import chan.content.ChanManager
import chan.http.CookieBuilder
import chan.util.StringUtils
import com.mishiranu.dashchan.content.net.UserAgentProvider
import com.mishiranu.dashchan.util.IOUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.HashMap
import java.util.HashSet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AdvancedPreferences {
	private val USER_AGENTS = HashMap<String, String>()
	private val SINGLE_CONNECTIONS = HashSet<String>()
	private val GOOGLE_COOKIE: String?
	private val TAB_SIZE: Int

	init {
		var googleCookieBuilder: CookieBuilder? = null
		var tabSize = 0
		var file = MainApplication.getInstance().externalCacheDir
		if (file != null) {
			file = File(file.parentFile, "files/advanced.json")
			if (file.exists()) {
				var jsonString: String? = null
				try {
					jsonString = FileInputStream(file).use { input ->
						val output = ByteArrayOutputStream()
						IOUtils.copyStream(input, output)
						String(output.toByteArray(), Charsets.UTF_8)
					}
				} catch (e: IOException) {
					e.printStackTrace()
				}
				val json = jsonString
				if (json != null) {
					try {
						val jsonObject = JSONObject(json)
						val userAgentObject = jsonObject.optJSONObject("userAgent")
						if (userAgentObject != null) {
							val keys = userAgentObject.keys()
							while (keys.hasNext()) {
								val chanName = keys.next()
								val userAgent = userAgentObject.getString(chanName)
								if (!StringUtils.isEmpty(userAgent)) {
									USER_AGENTS[chanName] = userAgent
								}
							}
						} else {
							// optString(name) yields "" when absent, which the isEmpty guard
							// below rejects just like the original null fallback did.
							val userAgent = jsonObject.optString("userAgent")
							if (!StringUtils.isEmpty(userAgent)) {
								USER_AGENTS[ChanManager.EXTENSION_NAME_CLIENT] = userAgent
							}
						}
						val singleConnectionArray = jsonObject.optJSONArray("singleConnection")
						if (singleConnectionArray != null) {
							for (i in 0 until singleConnectionArray.length()) {
								SINGLE_CONNECTIONS.add(singleConnectionArray.getString(i))
							}
						}
						val googleCookieObject = jsonObject.optJSONObject("googleCookie")
						if (googleCookieObject != null) {
							val keys = googleCookieObject.keys()
							while (keys.hasNext()) {
								val name = keys.next()
								val value = googleCookieObject.getString(name)
								if (!StringUtils.isEmpty(value)) {
									val builder = googleCookieBuilder
											?: CookieBuilder().also { googleCookieBuilder = it }
									builder.append(name, value)
								}
							}
						} else {
							val googleCookie = jsonObject.optString("googleCookie")
							if (!StringUtils.isEmpty(googleCookie)) {
								googleCookieBuilder = CookieBuilder().append(googleCookie)
							}
						}
						tabSize = jsonObject.optInt("tabSize")
					} catch (e: JSONException) {
						e.printStackTrace()
					}
				}
			}
		}
		GOOGLE_COOKIE = googleCookieBuilder?.build()
		TAB_SIZE = tabSize
	}

	@JvmStatic
	fun getUserAgent(chanName: String?): String {
		return chanName?.let { USER_AGENTS[it] }
				?: USER_AGENTS[ChanManager.EXTENSION_NAME_CLIENT]
				?: UserAgentProvider.getInstance().getUserAgent()!!
	}

	@JvmStatic
	fun isSingleConnection(chanName: String?): Boolean {
		return SINGLE_CONNECTIONS.contains(chanName ?: ChanManager.EXTENSION_NAME_CLIENT)
	}

	// Google reCAPTCHA becomes easier with HSID, SSID, SID, NID cookies
	@JvmStatic
	fun getGoogleCookie(): String? = GOOGLE_COOKIE

	@JvmStatic
	fun getTabSize(): Int = TAB_SIZE
}
