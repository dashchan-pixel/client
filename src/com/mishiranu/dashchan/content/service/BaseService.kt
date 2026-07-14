package com.mishiranu.dashchan.content.service

import android.app.Service

abstract class BaseService : Service() {
    private var onDestroyListeners: ArrayList<Runnable>? = null

    fun addOnDestroyListener(listener: Runnable) {
        val listeners = onDestroyListeners ?: ArrayList<Runnable>().also { onDestroyListeners = it }
        listeners.add(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroyListeners?.let { listeners ->
            for (listener in ArrayList(listeners)) {
                listener.run()
            }
        }
    }
}
