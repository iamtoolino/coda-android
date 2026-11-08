package dev.coda.android.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import dev.coda.core.connectivity.NetworkState

/**
 * A VPN with nothing underneath is no network. Tailscale keeps its VPN as the default network in airplane mode,
 * so "there is a default network" alone kept Coda online. A VPN counts only while a non-VPN network (Wi-Fi, cellular,
 * Ethernet) exists, and then the metered flag and transport come from that network, not from the VPN.
 */
object NetworkPresence {
    data class Link(val vpn: Boolean, val unmetered: Boolean, val cellular: Boolean)

    /** [active]: the default network (null: none). [underlying]: the best non-VPN network (null: none). Pure, unit-tested. */
    fun present(active: Link?, underlying: Link?): Boolean = active != null && (!active.vpn || underlying != null)

    fun state(active: Link?, underlying: Link?): NetworkState {
        if (!present(active, underlying)) return NetworkState(available = false, unmetered = false, cellular = false)
        val source = if (active!!.vpn) underlying!! else active
        return NetworkState(available = true, unmetered = source.unmetered, cellular = source.cellular)
    }

    fun link(caps: NetworkCapabilities?): Link? = caps?.let {
        Link(it.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    /** One thread for the default and underlying callbacks, so each owner sees them in order. */
    val handler: Handler by lazy { Handler(HandlerThread("coda-network").apply { start() }.looper) }
}

/**
 * The underlying network's callback sequence (pure, unit-tested with fake network ids). [changed] and [lost] say
 * what one callback means: [Update.link], the [link] changed; [Update.handover], another network replaced the one
 * tracked with no loss between (Wi-Fi to mobile data under a VPN, even with equal [link]s). A signal-strength-only update
 * is neither.
 */
class Underlay<N : Any>(link: NetworkPresence.Link?) {
    data class Update(val link: Boolean, val handover: Boolean) { val any get() = link || handover }
    var link: NetworkPresence.Link? = link
        private set
    private var current: N? = null
    fun changed(network: N, value: NetworkPresence.Link?): Update {
        val handover = current != null && network != current
        current = network
        return set(value, handover)
    }
    fun lost(network: N): Update = if (network == current) { current = null; set(null, false) } else Update(false, false)
    private fun set(value: NetworkPresence.Link?, handover: Boolean): Update {
        val changed = value != link
        link = value
        return Update(changed, handover)
    }
}

/**
 * Tracks the network the system would use without a VPN. After [start], its listener runs on [NetworkPresence.handler]
 * when [link] changes or on a handover to another underlying network (start only once the owner is fully built:
 * the callback may fire at once).
 */
class UnderlyingNetwork(private val manager: ConnectivityManager) : AutoCloseable {
    private var onChange: (Underlay.Update) -> Unit = {}
    /** Read once at start (any non-VPN network with internet, unmetered first), then from the callback only. */
    private val state = Underlay<Network>(@Suppress("DEPRECATION") manager.allNetworks
        .mapNotNull { NetworkPresence.link(manager.getNetworkCapabilities(it)?.takeIf { caps ->
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }) }
        .maxByOrNull { it.unmetered })
    @Volatile var link: NetworkPresence.Link? = state.link
        private set

    private fun notify(update: Underlay.Update) { link = state.link; if (update.any) onChange(update) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            notify(state.changed(network, NetworkPresence.link(capabilities)))
        override fun onLost(network: Network) = notify(state.lost(network))
    }
    /** [onChange] gets each [Underlay.Update]; an owner that cares only about [link] checks [Underlay.Update.link]. */
    fun start(onChange: (Underlay.Update) -> Unit) {
        this.onChange = onChange
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        manager.registerBestMatchingNetworkCallback(request, callback, NetworkPresence.handler)
    }
    override fun close() { manager.unregisterNetworkCallback(callback) }
}
