package com.mishiranu.dashchan.content.async

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Pair
import chan.content.Chan
import chan.http.HttpException
import chan.http.HttpHolder
import com.mishiranu.dashchan.BuildConfig
import com.mishiranu.dashchan.content.Preferences
import com.mishiranu.dashchan.content.model.ErrorItem
import com.mishiranu.dashchan.content.net.GithubRepository
import java.util.Locale
import java.util.TreeMap
import org.json.JSONException
import org.json.JSONObject

private const val PREFIX_EXPERIMENTAL_VERSION = "3.1.4-experimental-"

class ReadChangelogTask(private val callback: Callback, private val locales: List<Locale>) :
		HttpHolderTask<Void, Pair<ErrorItem?, List<ReadChangelogTask.Entry>?>?>(Chan.getFallback()) {
	fun interface Callback {
		fun onReadChangelogComplete(entries: List<Entry>?, errorItem: ErrorItem?)
	}

	class Entry(@JvmField val versions: MutableList<Version>,
			@JvmField val texts: MutableList<String>) : Parcelable {
		class Version(@JvmField val name: String, @JvmField val date: String) {
			fun getMajorMinor(): String {
				if (name.contains(PREFIX_EXPERIMENTAL_VERSION)) {
					return getExperimentalMajorMinor()
				}
				var index = name.indexOf('.')
				index = name.indexOf('.', index + 1)
				return if (index >= 0) name.substring(0, index) else name
			}

			private fun getExperimentalMajorMinor(): String {
				val experimentalName = name.replace(PREFIX_EXPERIMENTAL_VERSION, "")
				var index = experimentalName.indexOf('.')
				index = experimentalName.indexOf('.', index + 1)
				return if (index >= 0) experimentalName.substring(0, index) else name
			}
		}

		fun getSingleMajorMinorOrNull(): String? {
			var majorMinor: String? = null
			for (version in versions) {
				val versionMajorMinor = version.getMajorMinor()
				if (majorMinor == null) {
					majorMinor = versionMajorMinor
				} else if (versionMajorMinor != majorMinor) {
					majorMinor = null
					break
				}
			}
			return majorMinor
		}

		override fun describeContents(): Int = 0

		override fun writeToParcel(dest: Parcel, flags: Int) {
			dest.writeInt(versions.size)
			for (version in versions) {
				dest.writeString(version.name)
				dest.writeString(version.date)
			}
			dest.writeInt(texts.size)
			for (text in texts) {
				dest.writeString(text)
			}
		}

		companion object {
			@JvmField
			val CREATOR = object : Parcelable.Creator<Entry> {
				override fun createFromParcel(source: Parcel): Entry {
					val versionsSize = source.readInt()
					val versions = ArrayList<Version>(versionsSize)
					for (i in 0 until versionsSize) {
						val name = source.readString()!!
						val date = source.readString()!!
						versions.add(Version(name, date))
					}
					val textsSize = source.readInt()
					val texts = ArrayList<String>(textsSize)
					for (i in 0 until textsSize) {
						val text = source.readString()!!
						texts.add(text)
					}
					return Entry(versions, texts)
				}

				override fun newArray(size: Int): Array<Entry?> = arrayOfNulls(size)
			}
		}
	}

	override fun run(holder: HttpHolder): Pair<ErrorItem?, List<Entry>?>? {
		val githubUri = Chan.getFallback().locator
				.setSchemeIfEmpty(Uri.parse(Preferences.getGithubUriMetadata()), null)
		val metadataPath = BuildConfig.GITHUB_PATH_METADATA
		try {
			val repository = GithubRepository(holder, githubUri)
			val metadataFiles = repository.listFiles(metadataPath)
			if (isCancelled()) {
				return null
			}
			val metadataDirs = HashSet<String>()
			for ((key, value) in metadataFiles) {
				if (value.directory) {
					metadataDirs.add(key)
				}
			}
			if (metadataDirs.isEmpty()) {
				return Pair(ErrorItem(ErrorItem.Type.UNKNOWN), null)
			}
			val versionsFile = metadataFiles["versions.json"]
					?: return Pair(ErrorItem(ErrorItem.Type.UNKNOWN), null)
			val versionsArray = JSONObject(String(repository.readFile(versionsFile)!!))
					.getJSONArray("versions")

			val downloadLocales = ArrayList<String>()
			for (locale in locales) {
				val language = locale.language
				val country: String? = locale.country
				val languageCountry = if (country != null) "$language-$country" else language
				if (metadataDirs.contains(languageCountry)) {
					downloadLocales.add(languageCountry)
				} else {
					if (country == null) {
						for (metadataDir in metadataDirs) {
							if (metadataDir.startsWith("$language-")) {
								downloadLocales.add(metadataDir)
							}
						}
					} else if (metadataDirs.contains(language)) {
						downloadLocales.add(language)
					}
				}
			}
			for (fallbackLocaleDir in listOf("en-US", "en")) {
				if (metadataDirs.contains(fallbackLocaleDir)) {
					downloadLocales.add(fallbackLocaleDir)
				}
			}
			if (downloadLocales.isEmpty()) {
				return Pair(ErrorItem(ErrorItem.Type.UNKNOWN), null)
			}

			val checkedLocales = HashSet<String>()
			var changelogFiles: Map<String, GithubRepository.Entry>? = null
			for (localeDir in downloadLocales) {
				if (!checkedLocales.contains(localeDir)) {
					checkedLocales.add(localeDir)
					try {
						changelogFiles = repository.listFiles("$metadataPath/$localeDir/changelogs")
						if (isCancelled()) {
							return null
						}
						if (changelogFiles != null && changelogFiles.isNotEmpty()) {
							break
						}
					} catch (e: HttpException) {
						if (!e.isHttpException()) {
							throw e
						}
					}
				}
			}
			if (changelogFiles == null || changelogFiles.isEmpty()) {
				throw HttpException.createNotFoundException()
			}

			val entriesMap = TreeMap<Long, Entry>()
			for (i in 0 until versionsArray.length()) {
				val jsonObject = versionsArray.getJSONObject(i)
				val code = jsonObject.getLong("code")
				val name = jsonObject.getString("name")
				val date = jsonObject.getString("date")
				var entry = entriesMap[code]
				if ((entry == null || entry.texts.isEmpty()) && jsonObject.optBoolean("changelog")) {
					val file = changelogFiles["$code.txt"]
					val fileBytes = if (file != null) repository.readFile(file) else null
					val changelog = if (fileBytes != null) String(fileBytes) else null
					if (changelog != null) {
						entry = Entry(entry?.versions ?: ArrayList(), entry?.texts ?: ArrayList())
						entry.texts.add(changelog)
						entriesMap[code] = entry
					}
				}
				if (entry == null) {
					entry = Entry(ArrayList(), ArrayList())
					entriesMap[code] = entry
				}
				entry.versions.add(Entry.Version(name, date))
			}

			val entries = ArrayList<Entry>(entriesMap.size)
			for (entry in entriesMap.values) {
				if (entries.isNotEmpty()) {
					val lastEntry = entries[entries.size - 1]
					if (entry.texts.isEmpty()) {
						lastEntry.versions.addAll(entry.versions)
					} else {
						val majorMinor = entry.getSingleMajorMinorOrNull()
						val lastMajorMinor = lastEntry.getSingleMajorMinorOrNull()
						if (majorMinor != null && majorMinor == lastMajorMinor) {
							lastEntry.versions.addAll(entry.versions)
							lastEntry.texts.addAll(entry.texts)
						} else {
							entries.add(entry)
						}
					}
				} else if (entry.texts.isNotEmpty()) {
					entries.add(entry)
				}
			}
			entries.reverse()
			return Pair(null, entries)
		} catch (e: HttpException) {
			return Pair(e.getErrorItemAndHandle(), null)
		} catch (e: JSONException) {
			e.printStackTrace()
			return Pair(ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null)
		}
	}

	override fun onComplete(result: Pair<ErrorItem?, List<Entry>?>?) {
		callback.onReadChangelogComplete(result!!.second, result.first)
	}
}
