package com.mishiranu.dashchan.content

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.os.ConfigurationCompat
import chan.content.ChanManager
import com.mishiranu.dashchan.BuildConfig
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Locale

class LocaleManager private constructor() {
	private var systemLocaleJellyBean: Locale? = null

	@Suppress("DEPRECATION", "UNUSED_PARAMETER")
	fun updateConfiguration(configuration: Configuration) {
	}

	private var lastLocales: List<Locale> = emptyList()
	private var applicationContext: Context? = null

	@Suppress("DEPRECATION")
	fun apply(context: Context): Context {
		var context = context
		val resources = context.resources
		var configuration = resources.configuration
		var locale = VALUES_LOCALE_OBJECTS[Preferences.getLocale()]
		if (locale != null) {
			configuration = Configuration(configuration)
			configuration.setLocales(if (locale != Locale.US) {
				LocaleList(locale, Locale.US)
			} else {
				LocaleList(Locale.US)
			})
			configuration.locale = locale
			Locale.setDefault(locale)
			context = context.createConfigurationContext(configuration)
		} else {
			val localeList = configuration.locales
			locale = if (localeList.size() > 0) localeList.get(0) else null
			if (locale == null) {
				locale = Locale.US
			}
			Locale.setDefault(locale)
		}
		val localeList = configuration.locales
		val lastLocales = ArrayList<Locale>(localeList.size())
		for (i in 0 until localeList.size()) {
			lastLocales.add(localeList.get(i))
		}

		if (this.lastLocales != lastLocales) {
			this.lastLocales = lastLocales
			applicationContext = null
			ChanManager.getInstance().updateConfiguration(configuration, resources.displayMetrics)
		}
		return context
	}

	fun applyApplication(context: Context): Context {
		var applicationContext = applicationContext
		if (applicationContext == null) {
			applicationContext = apply(context.applicationContext)
			this.applicationContext = applicationContext
		}
		return applicationContext
	}

	fun getLocales(configuration: Configuration): List<Locale> {
		val locales = ArrayList<Locale>()
		val localeList = ConfigurationCompat.getLocales(configuration)
		for (i in 0 until localeList.size()) {
			localeList.get(i)?.let { locales.add(it) }
		}
		return locales
	}

	companion object {
		const val DEFAULT_LOCALE = ""

		@JvmField
		val ENTRIES_LOCALE: List<CharSequence>
		@JvmField
		val VALUES_LOCALE: List<String>
		private val VALUES_LOCALE_OBJECTS: Map<String, Locale>

		init {
			val total = BuildConfig.LOCALES.size + 2
			val codes = arrayOfNulls<String>(total)
			val names = arrayOfNulls<CharSequence>(total)
			val locales = arrayOfNulls<Locale>(total)
			codes[0] = DEFAULT_LOCALE
			codes[1] = "en"
			for (i in 2 until total) {
				val locale = BuildConfig.LOCALES[i - 2]
				val index = locale.indexOf("-r")
				codes[i] = if (index >= 0) {
					locale.substring(0, index) + "_" + locale.substring(index + 2)
				} else {
					locale
				}
			}
			names[0] = "System"
			for (i in 1 until names.size) {
				val splitted = codes[i]!!.split("_")
				val language = splitted[0]
				val country = if (splitted.size > 1) splitted[1] else null
				val locale = if (country != null) Locale(language, country) else Locale(language)
				val displayName = locale.getDisplayName(locale)
				names[i] = displayName.substring(0, 1).uppercase(locale) + displayName.substring(1)
				locales[i] = locale
			}
			ENTRIES_LOCALE = names.map { it!! }
			VALUES_LOCALE = codes.map { it!! }
			val valueLocaleObjects = HashMap<String, Locale>()
			for (i in codes.indices) {
				val locale = locales[i]
				if (locale != null) {
					valueLocaleObjects[codes[i]!!] = locale
				}
			}
			VALUES_LOCALE_OBJECTS = Collections.unmodifiableMap(valueLocaleObjects)
		}

		private val INSTANCE = LocaleManager()

		@JvmStatic
		fun getInstance(): LocaleManager = INSTANCE
	}
}
