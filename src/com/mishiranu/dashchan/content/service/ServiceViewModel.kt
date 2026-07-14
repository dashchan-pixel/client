package com.mishiranu.dashchan.content.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import com.mishiranu.dashchan.content.MainApplication

open class ServiceViewModel<ServiceBinder : IBinder>(
    private val serviceClass: Class<out Service>,
) : ViewModel() {
    private val context: Context = MainApplication.getInstance()

    private var binder: ServiceBinder? = null
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                handleDisconnected()
                @Suppress("UNCHECKED_CAST")
                val binder = service as ServiceBinder
                this@ServiceViewModel.binder = binder
                onConnected(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val reconnect = binder != null
                handleDisconnected()
                if (reconnect) {
                    connect()
                }
            }
        }

    init {
        connect()
    }

    private fun connect() {
        context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
    }

    private fun handleDisconnected() {
        val binder = binder
        if (binder != null) {
            this.binder = null
            onDisconnected(binder)
        }
    }

    protected fun getBinder(): ServiceBinder? = binder

    open fun onConnected(binder: ServiceBinder) {}

    open fun onDisconnected(binder: ServiceBinder) {}

    override fun onCleared() {
        handleDisconnected()
        context.unbindService(connection)
    }
}
