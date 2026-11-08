package dev.coda.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.coda.core.connectivity.Connectivity
import dev.coda.core.connectivity.Reachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default network can carry background requests: `INTERNET` and not `CAPTIVE_PORTAL` ([usable]). Not
 * `VALIDATED`: a Wi-Fi that reaches a LAN server without internet, or a blocked validation probe, must not close the gate
 * for good (the same rule as [NetworkMonitor]). A network that is up but unusable is left to the shared backoff.
 * Read once at start, then from callbacks only. [onNetworkChanged] runs on another default network or when one becomes
 * usable again (it resets the server backoff), and when the network under a VPN default is handed over.
 */
class AndroidConnectivity(context: Context, private val onNetworkChanged: () -> Unit) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private fun usable(caps: NetworkCapabilities?) = caps == null || // unknown counts as online
        usable(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
    @Volatile private var current: Network? = manager.activeNetwork
    @Volatile private var caps: NetworkCapabilities? = current?.let { manager.getNetworkCapabilities(it) }
    /** A VPN with no non-VPN network underneath (Tailscale in airplane mode) is no network. */
    private val underlying = UnderlyingNetwork(manager)
    private fun present() = NetworkPresence.present(
        current?.let { NetworkPresence.link(caps) ?: NetworkPresence.Link(vpn = false, unmetered = false, cellular = false) },
        underlying.link)
    private val mutable = MutableStateFlow(present() && usable(caps))
    override val online = mutable.asStateFlow()
    private val mutableNetwork = MutableStateFlow(level(present(), mutable.value))
    /** The same reading for the shared state, which also tells "no network" from "a network that can't be used". */
    val network = mutableNetwork.asStateFlow()
    private val mutableBlocked = MutableStateFlow(false)
    /**
     * Android blocks the default network for Coda (Doze, app standby, Battery Saver, Data Saver, the background
     * block). Not a network state: failures meanwhile just don't count (Reachability.blocked).
     */
    val blocked = mutableBlocked.asStateFlow()
    /**
     * Diagnostics only: the Data Saver state for Coda (`RESTRICT_BACKGROUND_STATUS_*`: 1 off, 2 allowed, 3 on).
     * Cached from the network callbacks, so the log line never calls the system under Reachability's lock.
     */
    @Volatile var restrictBackground: Int = manager.restrictBackgroundStatus
        private set
    private fun level(present: Boolean, usable: Boolean) = when {
        !present -> Reachability.Network.NONE
        usable -> Reachability.Network.USABLE
        else -> Reachability.Network.BLOCKED
    }
    private fun update(switched: Boolean) {
        val present = present()
        val was = mutable.value
        mutable.value = present && usable(caps)
        mutableNetwork.value = level(present, mutable.value)
        if (mutable.value && (switched || !was)) onNetworkChanged()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val switched = network != current
            current = network
            caps = capabilities
            restrictBackground = manager.restrictBackgroundStatus
            update(switched)
        }
        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            caps = null
            mutableBlocked.value = false
            update(false)
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            // Delivered after onCapabilitiesChanged for a new default network, so [current] is already set.
            restrictBackground = manager.restrictBackgroundStatus
            if (network == current) mutableBlocked.value = blocked
        }
    }
    init {
        // Under a VPN default the underlay's handover is the real network change (the VPN itself stays).
        underlying.start { update(switched(it, NetworkPresence.link(caps)?.vpn == true)) }
        manager.registerDefaultNetworkCallback(callback, NetworkPresence.handler)
    }

    companion object {
        /** The capability rule (pure, unit-tested): internet, and no captive portal detected. VALIDATED does not matter. */
        fun usable(internet: Boolean, captivePortal: Boolean): Boolean = internet && !captivePortal
        /** An underlying-network update is a network change when the default is a VPN. */
        fun switched(update: Underlay.Update, activeVpn: Boolean): Boolean = activeVpn && update.handover
    }
}
