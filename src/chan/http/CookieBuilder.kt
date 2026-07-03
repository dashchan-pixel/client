package chan.http

import chan.annotation.Public
import chan.util.StringUtils

@Public
class CookieBuilder {
	private var list: ArrayList<String>? = null

	@Public
	constructor()

	constructor(builder: CookieBuilder?) {
		append(builder)
	}

	@Public
	fun append(name: String, value: String?): CookieBuilder {
		if (!StringUtils.isEmpty(value)) {
			val list = list ?: ArrayList<String>().also { list = it }
			list.add(name)
			list.add(value!!)
		}
		return this
	}

	fun append(cookie: String?): CookieBuilder {
		if (!StringUtils.isEmpty(cookie)) {
			val nameValues = cookie!!.split("; *".toRegex()).dropLastWhile { it.isEmpty() }
			for (nameValue in nameValues) {
				val index = nameValue.indexOf("=")
				if (index > 0 && index + 1 < nameValue.length) {
					val name = nameValue.substring(0, index)
					val value = nameValue.substring(index + 1)
					val list = list ?: ArrayList<String>().also { list = it }
					list.add(name)
					list.add(value)
				}
			}
		}
		return this
	}

	fun append(builder: CookieBuilder?): CookieBuilder {
		val builderList = builder?.list
		if (builderList != null && builderList.isNotEmpty()) {
			val list = list ?: ArrayList<String>().also { list = it }
			list.addAll(builderList)
		}
		return this
	}

	fun getKeys(): List<String> {
		val list = this.list
		return if (list == null || list.isEmpty()) {
			emptyList()
		} else {
			val keys = ArrayList<String>(list.size / 2)
			for (i in 0 until list.size / 2) {
				keys.add(list[2 * i])
			}
			keys
		}
	}

	val isEmpty: Boolean
		get() = list.isNullOrEmpty()

	@Public
	fun build(): String {
		val list = this.list
		return if (list == null || list.isEmpty()) {
			""
		} else {
			val builder = StringBuilder()
			for (i in 0 until list.size / 2) {
				if (builder.isNotEmpty()) {
					builder.append("; ")
				}
				builder.append(list[2 * i]).append('=').append(list[2 * i + 1])
			}
			builder.toString()
		}
	}

	override fun toString(): String = build()
}
