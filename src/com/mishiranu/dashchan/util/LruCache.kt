package com.mishiranu.dashchan.util

class LruCache<K, V> @JvmOverloads constructor(maxEntries: Int,
		private val callback: RemoveCallback<in K, in V>? = null) :
		LinkedHashMap<K, V>(0, 0.75f, true) {
	private var maxEntries = 0

	private var callRemove = false
	private var callRemoveEntry: MutableMap.MutableEntry<K, V>? = null

	init {
		setMaxEntries(maxEntries)
	}

	fun setMaxEntries(maxEntries: Int) {
		this.maxEntries = maxEntries
	}

	override fun put(key: K, value: V): V? {
		val oldValue = super.put(key, value)
		if (callback != null) {
			val callRemove = this.callRemove
			val callRemoveEntry = this.callRemoveEntry
			this.callRemove = false
			this.callRemoveEntry = null
			if (oldValue != null) {
				callback.onRemoveEntry(key, oldValue)
			}
			if (callRemove) {
				callback.onRemoveEntry(callRemoveEntry!!.key, callRemoveEntry.value)
			}
		}
		return oldValue
	}

	override fun remove(key: K): V? {
		val result = super.remove(key)
		if (result != null && callback != null) {
			callback.onRemoveEntry(key, result)
		}
		return result
	}

	override fun clear() {
		var copied: ArrayList<MutableMap.MutableEntry<K, V>>? = null
		if (callback != null && isNotEmpty()) {
			copied = ArrayList(entries)
		}
		super.clear()
		copied?.forEach { callback!!.onRemoveEntry(it.key, it.value) }
	}

	override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
		val remove = size > maxEntries
		if (remove) {
			callRemove = true
			callRemoveEntry = eldest
			return true
		}
		return false
	}

	fun interface RemoveCallback<K, V> {
		fun onRemoveEntry(key: K, value: V)
	}
}
