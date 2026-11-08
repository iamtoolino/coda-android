package dev.coda.android.net

import dev.coda.android.net.NetworkPresence.Link
import dev.coda.core.connectivity.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A VPN with nothing underneath (Tailscale in airplane mode) is no network; over Wi-Fi or mobile data it is. */
class NetworkPresenceTest {
    private val wifi = Link(vpn = false, unmetered = true, cellular = false)
    private val meteredWifi = Link(vpn = false, unmetered = false, cellular = false)
    private val cellular = Link(vpn = false, unmetered = false, cellular = true)
    private val offline = NetworkState(available = false, unmetered = false, cellular = false)

    @Test fun vpnWithoutUnderlyingNetworkIsNoNetwork() {
        val vpn = Link(vpn = true, unmetered = true, cellular = false)
        assertFalse(NetworkPresence.present(vpn, null))
        assertEquals(offline, NetworkPresence.state(vpn, null))
    }

    @Test fun vpnOverWifiIsOnlineAndUnmetered() {
        // The VPN's own flags are ignored: a VPN app may declare itself metered (the VpnService default).
        val vpn = Link(vpn = true, unmetered = false, cellular = false)
        assertTrue(NetworkPresence.present(vpn, wifi))
        assertEquals(NetworkState(available = true, unmetered = true, cellular = false), NetworkPresence.state(vpn, wifi))
    }

    @Test fun vpnOverMobileDataIsOnlineAndMetered() {
        val vpn = Link(vpn = true, unmetered = true, cellular = false)
        assertEquals(NetworkState(available = true, unmetered = false, cellular = true), NetworkPresence.state(vpn, cellular))
    }

    @Test fun vpnOverMeteredWifiStaysMeteredWifi() {
        val vpn = Link(vpn = true, unmetered = true, cellular = false)
        assertEquals(NetworkState(available = true, unmetered = false, cellular = false), NetworkPresence.state(vpn, meteredWifi))
    }

    @Test fun withoutVpnTheDefaultNetworkDecidesAsBefore() {
        assertEquals(NetworkState(available = true, unmetered = true, cellular = false), NetworkPresence.state(wifi, wifi))
        assertEquals(NetworkState(available = true, unmetered = false, cellular = true), NetworkPresence.state(cellular, cellular))
        // The underlying reading lags or differs: the default network still decides when it is not a VPN.
        assertEquals(NetworkState(available = true, unmetered = true, cellular = false), NetworkPresence.state(wifi, null))
        assertEquals(NetworkState(available = true, unmetered = false, cellular = true), NetworkPresence.state(cellular, wifi))
    }

    @Test fun noDefaultNetworkIsNoNetwork() {
        assertFalse(NetworkPresence.present(null, wifi))
        assertEquals(offline, NetworkPresence.state(null, wifi))
        assertEquals(offline, NetworkPresence.state(null, null))
    }
}
