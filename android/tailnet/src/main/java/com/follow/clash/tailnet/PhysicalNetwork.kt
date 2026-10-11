package com.follow.clash.tailnet

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal data class PhysicalNetworkCandidate<T>(val network: T, val validated: Boolean, val wifi: Boolean)

internal fun <T> selectPhysicalNetwork(active: T?, candidates: List<PhysicalNetworkCandidate<T>>): T? {
    candidates.firstOrNull { it.network == active }?.let { return it.network }
    return candidates.maxByOrNull { (if (it.validated) 2 else 0) + (if (it.wifi) 1 else 0) }?.network
}

internal fun physicalNetwork(connectivity: ConnectivityManager): Network? {
    val candidates = connectivity.allNetworks.mapNotNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
        PhysicalNetworkCandidate(network,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }
    return selectPhysicalNetwork(connectivity.activeNetwork, candidates)
}
