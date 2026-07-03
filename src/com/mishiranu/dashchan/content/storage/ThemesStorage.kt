package com.mishiranu.dashchan.content.storage

import org.json.JSONException
import org.json.JSONObject

class ThemesStorage private constructor() :
		StorageManager.JsonOrgStorage<List<JSONObject>>("themes", 1000, 10000) {
	private val themes = HashMap<String, JSONObject>()

	init {
		startRead()
	}

	override fun onClone(): List<JSONObject> = ArrayList(themes.values)

	override fun onDeserialize(jsonObject: JSONObject) {
		val jsonArray = jsonObject.optJSONArray(KEY_DATA) ?: return
		for (i in 0 until jsonArray.length()) {
			val item = jsonArray.optJSONObject(i)
			if (item != null) {
				val name = item.optString("name")
				if (!name.isNullOrEmpty()) {
					themes[name] = item
				}
			}
		}
	}

	@Throws(JSONException::class)
	override fun onSerialize(data: List<JSONObject>): JSONObject {
		val jsonArray = org.json.JSONArray()
		for (jsonObject in data) {
			jsonArray.put(jsonObject)
		}
		val jsonObject = JSONObject()
		jsonObject.put(KEY_DATA, jsonArray)
		return jsonObject
	}

	fun getItems(): HashMap<String, JSONObject> = themes

	companion object {
		private const val KEY_DATA = "data"

		private val INSTANCE = ThemesStorage()

		@JvmStatic
		fun getInstance(): ThemesStorage = INSTANCE
	}
}
