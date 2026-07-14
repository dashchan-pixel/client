package com.mishiranu.dashchan.content.storage

import android.os.Parcel
import android.os.Parcelable
import chan.util.CommonUtils
import chan.util.StringUtils
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// isNull() is true for both an absent key and a JSON null, which is exactly when the
// Java's optString(name, null) fell back to null. Null must be preserved here: callers
// such as HidePerformer distinguish a null boardName from an empty one.
private fun JSONObject.optStringOrNull(name: String): String? =
		if (isNull(name)) null else optString(name)

class AutohideStorage private constructor() :
		StorageManager.JsonOrgStorage<List<AutohideStorage.AutohideItem>>("autohide", 1000, 10000) {
	private val autohideItems = ArrayList<AutohideItem>()

	init {
		startRead()
	}

	fun getItems(): ArrayList<AutohideItem> = autohideItems

	override fun onClone(): List<AutohideItem> {
		val autohideItems = ArrayList<AutohideItem>(this.autohideItems.size)
		for (autohideItem in this.autohideItems) {
			autohideItems.add(AutohideItem(autohideItem))
		}
		return autohideItems
	}

	override fun onDeserialize(jsonObject: JSONObject) {
		val jsonArray = jsonObject.optJSONArray(KEY_DATA) ?: return
		for (i in 0 until jsonArray.length()) {
			val item = jsonArray.optJSONObject(i)
			if (item != null) {
				var chanNames: HashSet<String>? = null
				val chanNamesArray = item.optJSONArray(KEY_CHAN_NAMES)
				if (chanNamesArray != null) {
					for (j in 0 until chanNamesArray.length()) {
						val chanName = chanNamesArray.optString(j, null)
						if (!chanName.isNullOrEmpty()) {
							if (chanNames == null) {
								chanNames = HashSet()
							}
							chanNames.add(chanName)
						}
					}
				}
				val boardName = item.optStringOrNull(KEY_BOARD_NAME)
				val threadNumber = item.optStringOrNull(KEY_THREAD_NUMBER)
				val optionOriginalPost = item.optBoolean(KEY_OPTION_ORIGINAL_POST)
				val optionSage = item.optBoolean(KEY_OPTION_SAGE)
				val optionSubject = item.optBoolean(KEY_OPTION_SUBJECT)
				val optionComment = item.optBoolean(KEY_OPTION_COMMENT)
				val optionName = item.optBoolean(KEY_OPTION_NAME)
				val optionFileName = item.optBoolean(KEY_OPTION_FILE_NAME)
				val value = item.optStringOrNull(KEY_VALUE)
				autohideItems.add(AutohideItem(chanNames, boardName, threadNumber, optionOriginalPost,
						optionSage, optionSubject, optionComment, optionName, optionFileName, value))
			}
		}
	}

	@Throws(JSONException::class)
	override fun onSerialize(data: List<AutohideItem>): JSONObject? {
		if (data.isNotEmpty()) {
			val jsonArray = JSONArray()
			for (autohideItem in data) {
				val jsonObject = JSONObject()
				val chanNames = autohideItem.chanNames
				if (!chanNames.isNullOrEmpty()) {
					val chanNamesArray = JSONArray()
					for (chanName in chanNames) {
						chanNamesArray.put(chanName)
					}
					jsonObject.put(KEY_CHAN_NAMES, chanNamesArray)
				}
				putJson(jsonObject, KEY_BOARD_NAME, autohideItem.boardName)
				putJson(jsonObject, KEY_THREAD_NUMBER, autohideItem.threadNumber)
				putJson(jsonObject, KEY_OPTION_ORIGINAL_POST, autohideItem.optionOriginalPost)
				putJson(jsonObject, KEY_OPTION_SAGE, autohideItem.optionSage)
				putJson(jsonObject, KEY_OPTION_SUBJECT, autohideItem.optionSubject)
				putJson(jsonObject, KEY_OPTION_COMMENT, autohideItem.optionComment)
				putJson(jsonObject, KEY_OPTION_NAME, autohideItem.optionName)
				putJson(jsonObject, KEY_OPTION_FILE_NAME, autohideItem.optionFileName)
				putJson(jsonObject, KEY_VALUE, autohideItem.value)
				jsonArray.put(jsonObject)
			}
			val jsonObject = JSONObject()
			jsonObject.put(KEY_DATA, jsonArray)
			return jsonObject
		}
		return null
	}

	fun add(autohideItem: AutohideItem) {
		autohideItems.add(autohideItem)
		serialize()
	}

	fun update(index: Int, autohideItem: AutohideItem) {
		autohideItems[index] = autohideItem
		serialize()
	}

	fun delete(index: Int) {
		autohideItems.removeAt(index)
		serialize()
	}

	class AutohideItem : Parcelable {
		@JvmField var chanNames: HashSet<String>? = null

		@JvmField var boardName: String? = null
		@JvmField var threadNumber: String? = null

		@JvmField var optionOriginalPost = false
		@JvmField var optionSage = false

		@JvmField var optionSubject = false
		@JvmField var optionComment = false
		@JvmField var optionName = false
		@JvmField var optionFileName = false

		@JvmField var value: String? = null

		@Volatile private var ready = false
		private var pattern: Pattern? = null

		constructor()

		constructor(autohideItem: AutohideItem) : this(autohideItem.chanNames, autohideItem.boardName,
				autohideItem.threadNumber, autohideItem.optionOriginalPost, autohideItem.optionSage,
				autohideItem.optionSubject, autohideItem.optionComment, autohideItem.optionName,
				autohideItem.optionFileName, autohideItem.value)

		constructor(chanNames: HashSet<String>?, boardName: String?, threadNumber: String?,
				optionOriginalPost: Boolean, optionSage: Boolean, optionSubject: Boolean,
				optionComment: Boolean, optionName: Boolean, optionFileName: Boolean, value: String?) {
			update(chanNames, boardName, threadNumber, optionOriginalPost, optionSage,
					optionSubject, optionComment, optionName, optionFileName, value)
		}

		fun update(chanNames: HashSet<String>?, boardName: String?, threadNumber: String?,
				optionOriginalPost: Boolean, optionSage: Boolean, optionSubject: Boolean,
				optionComment: Boolean, optionName: Boolean, optionFileName: Boolean, value: String?) {
			this.chanNames = chanNames
			this.boardName = boardName
			this.threadNumber = threadNumber
			this.optionOriginalPost = optionOriginalPost
			this.optionSage = optionSage
			this.optionSubject = optionSubject
			this.optionComment = optionComment
			this.optionName = optionName
			this.optionFileName = optionFileName
			this.value = StringUtils.emptyIfNull(value)
		}

		fun find(data: String): String? {
			if (!ready) {
				synchronized(this) {
					if (!ready) {
						try {
							pattern = makePattern(value!!)
						} catch (e: Exception) {
							// Invalid pattern syntax, ignore exception
						}
						ready = true
					}
				}
			}
			try {
				val matcher = pattern!!.matcher(data)
				if (matcher.find()) {
					var result: String? = matcher.group()
					if (StringUtils.isEmpty(result)) {
						result = value
					}
					return result
				}
			} catch (e: Exception) {
				// Ignore matching exceptions
			}
			return null
		}

		enum class ReasonSource { NAME, SUBJECT, COMMENT, FILE }

		fun getReason(reasonSource: ReasonSource, text: String?, findResult: String?): String {
			val builder = StringBuilder()
			if (optionSage) {
				builder.append("sage ")
			}
			if (optionSubject && reasonSource == ReasonSource.SUBJECT) {
				builder.append("subject ")
			}
			if (optionName && reasonSource == ReasonSource.NAME) {
				builder.append("name ")
			}
			if (optionFileName && reasonSource == ReasonSource.FILE) {
				builder.append("file ")
			}
			if (!findResult.isNullOrEmpty()) {
				builder.append(findResult)
			} else if (!text.isNullOrEmpty()) {
				builder.append(StringUtils.cutIfLongerToLine(text, 80, true))
			}
			return builder.toString()
		}

		override fun describeContents(): Int = 0

		override fun writeToParcel(dest: Parcel, flags: Int) {
			dest.writeStringArray(CommonUtils.toArray(chanNames, String::class.java))
			dest.writeString(boardName)
			dest.writeString(threadNumber)
			dest.writeByte(if (optionOriginalPost) 1.toByte() else 0.toByte())
			dest.writeByte(if (optionSage) 1.toByte() else 0.toByte())
			dest.writeByte(if (optionSubject) 1.toByte() else 0.toByte())
			dest.writeByte(if (optionComment) 1.toByte() else 0.toByte())
			dest.writeByte(if (optionName) 1.toByte() else 0.toByte())
			dest.writeByte(if (optionFileName) 1.toByte() else 0.toByte())
			dest.writeString(value)
		}

		companion object {
			@JvmStatic
			fun makePattern(value: String): Pattern {
				return Pattern.compile(value, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
			}

			@JvmField
			val CREATOR = object : Parcelable.Creator<AutohideItem> {
				override fun createFromParcel(source: Parcel): AutohideItem {
					val autohideItem = AutohideItem()
					val chanNames = source.createStringArray()
					if (chanNames != null) {
						autohideItem.chanNames = HashSet(chanNames.asList())
					}
					autohideItem.boardName = source.readString()
					autohideItem.threadNumber = source.readString()
					autohideItem.optionOriginalPost = source.readByte().toInt() != 0
					autohideItem.optionSage = source.readByte().toInt() != 0
					autohideItem.optionSubject = source.readByte().toInt() != 0
					autohideItem.optionComment = source.readByte().toInt() != 0
					autohideItem.optionName = source.readByte().toInt() != 0
					autohideItem.optionFileName = source.readByte().toInt() != 0
					autohideItem.value = source.readString()
					return autohideItem
				}

				override fun newArray(size: Int): Array<AutohideItem?> = arrayOfNulls(size)
			}
		}
	}

	companion object {
		private const val KEY_DATA = "data"
		private const val KEY_CHAN_NAMES = "chanNames"
		private const val KEY_BOARD_NAME = "boardName"
		private const val KEY_THREAD_NUMBER = "threadNumber"
		private const val KEY_OPTION_ORIGINAL_POST = "optionOriginalPost"
		private const val KEY_OPTION_SAGE = "optionSage"
		private const val KEY_OPTION_SUBJECT = "optionSubject"
		private const val KEY_OPTION_COMMENT = "optionComment"
		private const val KEY_OPTION_NAME = "optionName"
		private const val KEY_OPTION_FILE_NAME = "optionFileName"
		private const val KEY_VALUE = "value"

		private val INSTANCE = AutohideStorage()

		@JvmStatic
		fun getInstance(): AutohideStorage = INSTANCE

		@JvmStatic
		@Throws(JSONException::class)
		fun putJson(jsonObject: JSONObject, name: String, value: String?) {
			if (!value.isNullOrEmpty()) {
				jsonObject.put(name, value)
			}
		}

		@JvmStatic
		@Throws(JSONException::class)
		fun putJson(jsonObject: JSONObject, name: String, value: Boolean) {
			if (value) {
				jsonObject.put(name, true)
			}
		}
	}
}
