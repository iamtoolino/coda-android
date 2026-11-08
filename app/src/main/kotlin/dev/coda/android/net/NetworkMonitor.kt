package dev.coda.android.net

import android.content.Context
import android.net.*
import dev.coda.core.connectivity.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/** LAN reachability deliberately does not depend on VALIDATED (internet access). */
class NetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    /** The underlying network decides whether a VPN counts, and gives its metered flag and transport. */
    private val underlying = UnderlyingNetwork(manager)
    private fun read(): NetworkState =
        NetworkPresence.state(NetworkPresence.link(manager.getNetworkCapabilities(manager.activeNetwork)), underlying.link)
    /** Identifies the active network and its transports; changes only on a real switch, not on RSSI or bandwidth updates. */
    private fun readIdentity(): String {
        val network = manager.activeNetwork ?: return "none"
        val caps = manager.getNetworkCapabilities(network)
        val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN).filter { caps?.hasTransport(it) == true }
        return "${network.networkHandle}/$transports"
    }
    @Volatile var identity: String = readIdentity(); private set
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private fun changed() { identity = readIdentity(); mutable.value = read(); changes.tryEmit(Unit) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
    }
    init { underlying.start { if (it.link) changed() }; manager.registerDefaultNetworkCallback(callback, NetworkPresence.handler) }
    override fun close() { manager.unregisterNetworkCallback(callback); underlying.close() }
}
