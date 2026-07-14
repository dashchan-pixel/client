package com.mishiranu.dashchan.content.async

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class CallbackProxy<Callback> private constructor(
    private val method: Method,
    private val args: Array<Any?>?,
) {
    fun interface Handler<Callback> {
        fun handle(proxy: CallbackProxy<Callback>)
    }

    fun invoke(callback: Callback) {
        try {
            method.invoke(callback, *(args ?: EMPTY_ARGS))
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            when (val target = e.targetException) {
                is RuntimeException -> throw target
                is Error -> throw target
                else -> throw RuntimeException(target)
            }
        }
    }

    companion object {
        private val EMPTY_ARGS = arrayOfNulls<Any>(0)

        @JvmStatic
        fun <Callback> create(
            callbackClass: Class<Callback>,
            handler: Handler<Callback>,
        ): Callback {
            val invocationHandler =
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "equals" -> {
                            args != null && args.size == 1 && args[0] === proxy
                        }

                        "hashCode" -> {
                            handler.hashCode()
                        }

                        "toString" -> {
                            handler.toString()
                        }

                        else -> {
                            handler.handle(CallbackProxy(method, args))
                            null
                        }
                    }
                }
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf<Class<*>>(callbackClass),
                invocationHandler,
            ) as Callback
        }
    }
}
