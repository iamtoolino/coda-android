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
    /** Every callback, signal and bandwidth updates included. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Real network switches only ([NetworkSwitch]); emitted before [changes]. */
    val switches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val switch = NetworkSwitch(identity, mutable.value)
    private fun changed() {
        identity = readIdentity(); mutable.value = read()
        if (switch.update(identity, mutable.value)) switches.tryEmit(Unit)
        changes.tryEmit(Unit)
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
    }
    init { underlying.start { if (it.link) changed() }; manager.registerDefaultNetworkCallback(callback, NetworkPresence.handler) }
    override fun close() { manager.unregisterNetworkCallback(callback); underlying.close() }
}

/**
 * A real network switch is a new active network or transports ([NetworkMonitor.identity], the
 * Identity) or a new [NetworkState] (available, metered, cellular: e.g. the network under a VPN handed over
 * from Wi-Fi to mobile data). A capability update of the same network with the same state (signal strength,
 * bandwidth, validation) is not one. Not thread-safe; the monitor calls it on its callback handler.
 */
class NetworkSwitch(private var identity: String, private var state: NetworkState) {
    /** True when [identity] or [state] differs from the last call (or the initial values). */
    fun update(identity: String, state: NetworkState): Boolean {
        val switched = identity != this.identity || state != this.state
        this.identity = identity; this.state = state
        return switched
    }
}
