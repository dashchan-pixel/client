package com.mishiranu.dashchan.util

class ConcatIterable<T>(private vararg val iterables: Iterable<T>) : Iterable<T> {
	override fun iterator(): Iterator<T> {
		return object : MutableIterator<T> {
			private var iterator: Iterator<T>? = null
			private var next = 0

			override fun hasNext(): Boolean {
				val iterator = this.iterator
				if (iterator != null && iterator.hasNext()) {
					return true
				}
				if (iterables.size > next) {
					this.iterator = iterables[next++].iterator()
					return hasNext()
				}
				return false
			}

			override fun next(): T = iterator!!.next()

			override fun remove() {
				(iterator as? MutableIterator<T>)?.remove()
			}
		}
	}
}
