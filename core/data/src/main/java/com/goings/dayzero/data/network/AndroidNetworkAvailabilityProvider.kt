package com.goings.dayzero.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.goings.dayzero.domain.network.NetworkAvailabilityProvider

/**
 * Android implementation of [NetworkAvailabilityProvider].
 *
 * Reports validated internet availability based on the active default network.
 * Requires the [android.Manifest.permission.ACCESS_NETWORK_STATE] permission.
 */
class AndroidNetworkAvailabilityProvider(
    private val context: Context
) : NetworkAvailabilityProvider {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var callbackValidatedInternet: Boolean = queryValidatedInternet()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshFromCallback()
        override fun onLost(network: Network) = refreshFromCallback()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refreshFromCallback()
    }

    init {
        val manager = connectivityManager
        if (manager != null) {
            runCatching {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback
                )
            }.onFailure {
                Log.w(TAG, "network callback registration failed type=${it::class.java.simpleName}")
            }
        }
    }

    override fun hasValidatedInternet(): Boolean {
        val manager = connectivityManager
        val active = manager?.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        val hasInternet = activeCapabilities.hasCapabilityOrFalse(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
        val isValidated = activeCapabilities.hasCapabilityOrFalse(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
        val hasValidatedPhysicalTransport = manager?.allNetworks.orEmpty().any { network ->
            manager?.getNetworkCapabilities(network).isValidatedPhysicalInternet()
        }
        val available = active != null && hasInternet && isValidated && hasValidatedPhysicalTransport
        callbackValidatedInternet = available
        Log.i(
            TAG,
            "network snapshot active=${active != null} internet=$hasInternet validated=$isValidated " +
                "physicalValidated=$hasValidatedPhysicalTransport callback=$callbackValidatedInternet " +
                "available=$available"
        )
        return available
    }

    private fun refreshFromCallback() {
        callbackValidatedInternet = queryValidatedInternet()
        Log.i(TAG, "network callback available=$callbackValidatedInternet")
    }

    private fun queryValidatedInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val active = manager.activeNetwork ?: return false
        val activeCapabilities = manager.getNetworkCapabilities(active) ?: return false
        if (!activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return false
        }
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network).isValidatedPhysicalInternet()
        }
    }

    private fun NetworkCapabilities?.hasCapabilityOrFalse(capability: Int): Boolean =
        this?.hasCapability(capability) == true

    private fun NetworkCapabilities?.isValidatedPhysicalInternet(): Boolean {
        if (this == null) return false
        val physicalTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return physicalTransport &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "DayZeroNetworkGate"
    }
}
