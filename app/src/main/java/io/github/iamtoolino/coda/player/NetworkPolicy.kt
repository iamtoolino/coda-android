package io.github.iamtoolino.coda.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun isMobileNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val active = connectivity.activeNetwork ?: return true
    val activeCapabilities = connectivity.getNetworkCapabilities(active) ?: return true

    val candidates = connectivity.allNetworks
        .filterNot { it == active }
        .mapNotNull(connectivity::getNetworkCapabilities)

    return shouldUseMobileQuality(
        active = activeCapabilities.toNetworkSnapshot(),
        underlyingCandidates = candidates.map(NetworkCapabilities::toNetworkSnapshot),
    )
}

internal data class NetworkSnapshot(
    val wifi: Boolean = false,
    val ethernet: Boolean = false,
    val cellular: Boolean = false,
    val vpn: Boolean = false,
    val validatedInternet: Boolean = false,
)

internal fun shouldUseMobileQuality(
    active: NetworkSnapshot,
    underlyingCandidates: List<NetworkSnapshot> = emptyList(),
): Boolean {
    if (!active.vpn) {
        if (active.wifi || active.ethernet) return false
        if (active.cellular) return true
        return true
    }

    val validatedCandidates = underlyingCandidates.filter(NetworkSnapshot::validatedInternet)
    val hasWifiOrEthernet = validatedCandidates.any { it.wifi || it.ethernet }
    val hasCellular = validatedCandidates.any(NetworkSnapshot::cellular)

    return when {
        hasWifiOrEthernet -> false
        hasCellular -> true
        // Some VPN implementations propagate their physical transport onto the VPN itself.
        active.validatedInternet && (active.wifi || active.ethernet) && !active.cellular -> false
        else -> true
    }
}

private fun NetworkCapabilities.toNetworkSnapshot() = NetworkSnapshot(
    wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    ethernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
    cellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    validatedInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)
