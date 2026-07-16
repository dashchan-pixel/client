package com.mishiranu.dashchan.content

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mishiranu.dashchan.util.ConcurrentUtils

class NetworkObserver private constructor() {
    private enum class NetworkState { WIFI, MOBILE, UNDEFINED }

    private val connectivityManager: ConnectivityManager

    private var networkState = NetworkState.UNDEFINED

    init {
        val context: Context = MainApplication.getInstance()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        onActiveNetworkChange()
        val networkChangeRunnable = Runnable { onActiveNetworkChange() }
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                private fun handleChange() {
                    ConcurrentUtils.HANDLER.removeCallbacks(networkChangeRunnable)
                    ConcurrentUtils.HANDLER.postDelayed(networkChangeRunnable, 500L)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    handleChange()
                }

                override fun onLost(network: Network) {
                    handleChange()
                }
            },
        )
    }

    fun isWifiConnected(): Boolean = networkState == NetworkState.WIFI

    private fun onActiveNetworkChange() {
        val capabilities =
            connectivityManager.activeNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
            }
        networkState =
            when {
                capabilities == null -> NetworkState.UNDEFINED
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.MOBILE
                else -> NetworkState.WIFI
            }
    }

    companion object {
        private val INSTANCE = NetworkObserver()

        @JvmStatic
        fun getInstance(): NetworkObserver = INSTANCE
    }
}
