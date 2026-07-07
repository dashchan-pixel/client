package com.mishiranu.dashchan.content

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Pair
import androidx.annotation.RequiresApi
import com.mishiranu.dashchan.util.ConcurrentUtils

class NetworkObserver private constructor() {
	private enum class NetworkState { WIFI, MOBILE, UNDEFINED }

	private val connectivityManager: ConnectivityManager

	private var networkState = NetworkState.UNDEFINED
	private var last3GChecked: Long = 0
	private var last3GAvailable = false

	init {
		val context: Context = MainApplication.getInstance()
		connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		onActiveNetworkChange()
		val networkChangeRunnable = Runnable { onActiveNetworkChange() }
		connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
			private fun handleChange() {
				ConcurrentUtils.HANDLER.removeCallbacks(networkChangeRunnable)
				ConcurrentUtils.HANDLER.postDelayed(networkChangeRunnable, 500L)
			}

			override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
				handleChange()
			}

			override fun onLost(network: Network) {
				handleChange()
			}
		})
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

	fun isWifiConnected(): Boolean {
		return networkState == NetworkState.WIFI
	}

	fun isMobile3GConnected(): Boolean {
		return when (networkState) {
			NetworkState.WIFI -> true
			NetworkState.MOBILE -> {
				if (SystemClock.elapsedRealtime() - last3GChecked >= 2000) {
					update3GConnected28()
					last3GChecked = SystemClock.elapsedRealtime()
				}
				last3GAvailable
			}
			NetworkState.UNDEFINED -> false
		}
	}

	@Suppress("DEPRECATION")
	@RequiresApi(Build.VERSION_CODES.M)
	private fun update3GConnected28() {
		var is3GAvailable = false
		val pair = getNetwork28()
		if (pair!!.second != null && pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
			// Is there a non-deprecated way to get network subtype without READ_PHONE_STATE permission?
			val networkInfo = connectivityManager.getNetworkInfo(pair.first)
			is3GAvailable = isNetworkType3G(networkInfo!!.subtype)
		}
		last3GAvailable = is3GAvailable
	}

	// NETWORK_TYPE_EVDO_0/A/B and EHRPD are deprecated with no replacement constants; still valid subtype values.
	@Suppress("DEPRECATION")
	private fun isNetworkType3G(type: Int): Boolean {
		return when (type) {
			TelephonyManager.NETWORK_TYPE_UMTS,
			TelephonyManager.NETWORK_TYPE_EVDO_0,
			TelephonyManager.NETWORK_TYPE_EVDO_A,
			TelephonyManager.NETWORK_TYPE_HSDPA,
			TelephonyManager.NETWORK_TYPE_HSUPA,
			TelephonyManager.NETWORK_TYPE_HSPA,
			TelephonyManager.NETWORK_TYPE_EVDO_B,
			TelephonyManager.NETWORK_TYPE_EHRPD,
			TelephonyManager.NETWORK_TYPE_HSPAP,
			TelephonyManager.NETWORK_TYPE_TD_SCDMA -> true // 3G
			TelephonyManager.NETWORK_TYPE_LTE,
			TelephonyManager.NETWORK_TYPE_IWLAN -> true // 4G
			TelephonyManager.NETWORK_TYPE_NR -> true // 5G
			else -> false
		}
	}

	private fun onActiveNetworkChange() {
		updateNetworkState28()
		last3GChecked = 0L
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private fun updateNetworkState28() {
		var networkState = NetworkState.UNDEFINED
		val pair = getNetwork28()
		if (pair != null) {
			networkState = if (pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
				NetworkState.MOBILE else NetworkState.WIFI
		}
		this.networkState = networkState
	}

	companion object {
		private val INSTANCE = NetworkObserver()

		@JvmStatic
		fun getInstance(): NetworkObserver {
			return INSTANCE
		}
	}
}
