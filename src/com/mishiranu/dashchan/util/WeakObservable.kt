package com.mishiranu.dashchan.util

import java.lang.ref.WeakReference

class WeakObservable<T : Any> : Iterable<T> {
	private val observers = ArrayList<WeakReference<T>>()

	fun register(observer: T) {
		observers.add(WeakReference(observer))
	}

	fun unregister(observer: T) {
		val iterator = observers.iterator()
		while (iterator.hasNext()) {
			val item = iterator.next().get()
			if (item === observer || item == null) {
				iterator.remove()
			}
		}
	}

	override fun iterator(): Iterator<T> {
		return WeakIterator(observers.iterator(), WeakIterator.Provider.identity())
	}
}
