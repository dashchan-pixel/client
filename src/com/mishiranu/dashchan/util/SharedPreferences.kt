package com.mishiranu.dashchan.util

import android.annotation.SuppressLint
import android.content.Context
import java.io.Closeable
import java.util.Collections

class SharedPreferences(context: Context, name: String) {
	class Editor @SuppressLint("CommitPrefEdits") internal constructor(
			private val preferences: SharedPreferences) : Closeable {
		private val editor: android.content.SharedPreferences.Editor = preferences.shared.edit()

		fun put(key: String, value: String?): Editor {
			if (value != null) {
				editor.putString(key, value)
			} else {
				editor.remove(key)
			}
			return this
		}

		fun put(key: String, values: Set<String>?): Editor {
			if (values != null) {
				editor.putStringSet(key, values)
			} else {
				editor.remove(key)
			}
			return this
		}

		fun put(key: String, value: Int): Editor {
			editor.putInt(key, value)
			return this
		}

		fun put(key: String, value: Long): Editor {
			editor.putLong(key, value)
			return this
		}

		fun put(key: String, value: Float): Editor {
			editor.putFloat(key, value)
			return this
		}

		fun put(key: String, value: Boolean): Editor {
			editor.putBoolean(key, value)
			return this
		}

		fun remove(key: String): Editor {
			editor.remove(key)
			return this
		}

		private fun commitInternal(): Boolean {
			return try {
				preferences.shouldUpdateMap = true
				editor.commit()
			} finally {
				preferences.updateMapIfNeeded()
			}
		}

		override fun close() {
			if (ConcurrentUtils.isMain()) {
				commitInternal()
			} else {
				// Uninterruptible process
				ConcurrentUtils.mainGet { commitInternal() }
			}
		}
	}

	fun interface Listener {
		fun onChanged(key: String?)
	}

	private val shared: android.content.SharedPreferences =
			context.getSharedPreferences(name, Context.MODE_PRIVATE)
	private var map: Map<String, *> = emptyMap<String, Any>()
	private var shouldUpdateMap = true

	init {
		updateMapIfNeeded()
	}

	private fun updateMapIfNeeded() {
		if (shouldUpdateMap) {
			// SharedPreferences.getAll() returns a new HashMap instance
			map = Collections.unmodifiableMap(shared.all)
			shouldUpdateMap = false
		}
	}

	fun getAll(): Map<String, *> = map

	fun getString(key: String, defValue: String?): String? {
		val value = map[key]
		return if (value is String) value else defValue
	}

	fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
		val value = map[key]
		@Suppress("UNCHECKED_CAST")
		return if (value is Set<*>) value as Set<String> else defValues
	}

	fun getInt(key: String, defValue: Int): Int {
		val value = map[key]
		return if (value is Number) value.toInt() else defValue
	}

	fun getLong(key: String, defValue: Long): Long {
		val value = map[key]
		return if (value is Number) value.toLong() else defValue
	}

	fun getFloat(key: String, defValue: Float): Float {
		val value = map[key]
		return if (value is Number) value.toFloat() else defValue
	}

	fun getBoolean(key: String, defValue: Boolean): Boolean {
		val value = map[key]
		return if (value is Boolean) value else defValue
	}

	fun contains(key: String): Boolean = map.containsKey(key)

	fun edit(): Editor = Editor(this)

	private val listeners =
			HashMap<Listener, android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

	fun register(listener: Listener) {
		synchronized(listeners) {
			if (!listeners.containsKey(listener)) {
				val internalListener =
						android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
					updateMapIfNeeded()
					listener.onChanged(key)
				}
				shared.registerOnSharedPreferenceChangeListener(internalListener)
				listeners[listener] = internalListener
			}
		}
	}

	fun unregister(listener: Listener) {
		synchronized(listeners) {
			val internalListener = listeners.remove(listener)
			if (internalListener != null) {
				shared.unregisterOnSharedPreferenceChangeListener(internalListener)
			}
		}
	}
}
