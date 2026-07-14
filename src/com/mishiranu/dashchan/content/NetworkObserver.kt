package com.mishiranu.dashchan.content

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Pair
import androidx.annotation.RequiresApi
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

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getNetwork28(): Pair<Network, NetworkCapabilities>? {
        val network = connectivityManager.activeNetwork
        if (network != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return if (capabilities != null) Pair(network, capabilities) else null
        }
        return null
    }

    fun isWifiConnected(): Boolean = networkState == NetworkState.WIFI

    private fun onActiveNetworkChange() {
        updateNetworkState28()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun updateNetworkState28() {
        var networkState = NetworkState.UNDEFINED
        val pair = getNetwork28()
        if (pair != null) {
            networkState =
                if (pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    NetworkState.MOBILE
                } else {
                    NetworkState.WIFI
                }
        }
        this.networkState = networkState
    }

    companion object {
        private val INSTANCE = NetworkObserver()

        @JvmStatic
        fun getInstance(): NetworkObserver = INSTANCE
    }
}
