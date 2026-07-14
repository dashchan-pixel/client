package com.mishiranu.dashchan.util

import java.lang.ref.WeakReference

class WeakIterator<T, R, N>(
    private val iterator: MutableIterator<T>,
    private val provider: Provider<T, R, N>,
) : MutableIterator<N> {
    interface Provider<T, R, N> {
        fun getWeakReference(data: T): WeakReference<R>?

        fun transform(
            data: T,
            referenced: R,
        ): N?

        fun onFinished() {}

        companion object {
            private val PROVIDER_IDENTITY =
                object : Provider<WeakReference<Any>, Any, Any> {
                    override fun getWeakReference(data: WeakReference<Any>): WeakReference<Any> = data

                    override fun transform(
                        data: WeakReference<Any>,
                        referenced: Any,
                    ): Any = referenced
                }

            @JvmStatic
            @Suppress("UNCHECKED_CAST")
            fun <T : Any> identity(): Provider<WeakReference<T>, T, T> = PROVIDER_IDENTITY as Provider<WeakReference<T>, T, T>
        }
    }

    private var next: N? = null

    override fun hasNext(): Boolean {
        if (next == null) {
            while (iterator.hasNext()) {
                val data = iterator.next()
                val reference = provider.getWeakReference(data)
                val referenced = reference?.get()
                if (referenced != null) {
                    val next = provider.transform(data, referenced)
                    if (next != null) {
                        this.next = next
                        break
                    }
                } else {
                    iterator.remove()
                }
            }
        }
        if (next == null) {
            provider.onFinished()
            return false
        }
        return true
    }

    override fun next(): N {
        if (!hasNext()) {
            throw NoSuchElementException()
        }
        val next = this.next!!
        this.next = null
        return next
    }

    override fun remove(): Unit = throw UnsupportedOperationException()
}
