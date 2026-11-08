package dev.coda.android.net

import dev.coda.android.net.NetworkPresence.Link
import dev.coda.core.connectivity.Reachability
import dev.coda.core.connectivity.ServerGate
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With a VPN as the default network, the network under it is handed over from Wi-Fi to mobile data
 * without a loss in between. That is a network change: the failure streak and the server backoff start again, as
 * AndroidConnectivity's other network changes do (CodaApp: `reach.networkChanged(); gate.reset()`). A signal-strength
 * update of the same network changes nothing. Fake network ids stand in for android.net.Network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnUnderlayHandoverTest {
    private val wifi = Link(vpn = false, unmetered = true, cellular = false)
    private val cellular = Link(vpn = false, unmetered = false, cellular = true)

    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - testScheduler.currentTime); runCurrent() }

    private class Wiring(val r: Reachability, val gate: ServerGate, val underlay: Underlay<String>) {
        /** AndroidConnectivity's underlay listener with a VPN default, and CodaApp's onNetworkChanged. */
        fun deliver(update: Underlay.Update) {
            if (AndroidConnectivity.switched(update, activeVpn = true)) { r.networkChanged(); gate.reset() }
        }
    }

    private fun TestScope.wiring(): Wiring {
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, probe = { throw SubsonicError.Network(java.io.IOException("down")) })
        val gate = ServerGate(r, { testScheduler.currentTime })
        val underlay = Underlay<String>(null)
        underlay.changed("wifi-1", wifi)
        return Wiring(r, gate, underlay)
    }

    @Test fun anUnderlayHandoverUnderAVpnResetsTheStreakAndTheBackoff() = runTest {
        val w = wiring()
        w.r.failed(); w.gate.failed()
        assertFalse("backoff running", w.gate.isOpen())
        at(5_000)
        w.deliver(w.underlay.changed("cell-1", cellular))
        assertTrue("the handover ends the backoff", w.gate.isOpen())
        assertEquals(0L, w.gate.backoffMs)
        at(12_000)
        assertEquals("the old failure no longer confirms: a new run started at the settle probe (6 s)",
            Reachability.Link.ONLINE, w.r.state.value.link)
    }

    @Test fun aHandoverBetweenNetworksWithEqualLinksStillCounts() = runTest {
        val w = wiring()
        w.r.failed(); w.gate.failed()
        at(5_000)
        val update = w.underlay.changed("wifi-2", wifi)
        assertTrue("another Wi-Fi with the same link properties is a handover", update.handover)
        w.deliver(update)
        assertTrue(w.gate.isOpen())
        at(12_000)
        assertEquals(Reachability.Link.ONLINE, w.r.state.value.link)
    }

    @Test fun aSignalOnlyUpdateChangesNothing() = runTest {
        val w = wiring()
        w.r.failed(); w.gate.failed()
        at(5_000)
        val update = w.underlay.changed("wifi-1", wifi)
        assertFalse("same network, same link: nothing to tell", update.any)
        w.deliver(update)
        assertFalse("the backoff goes on", w.gate.isOpen())
        at(10_000)
        assertEquals("the streak goes on: the 10 s ping confirms", Reachability.Link.UNREACHABLE, w.r.state.value.link)
    }

    @Test fun withoutAVpnTheDefaultCallbackOwnsNetworkChanges() {
        val underlay = Underlay<String>(null)
        underlay.changed("wifi-1", wifi)
        assertFalse(AndroidConnectivity.switched(underlay.changed("cell-1", cellular), activeVpn = false))
        assertFalse("a loss then a new network is no handover (availability reports it)",
            underlay.lost("cell-1").handover || underlay.changed("wifi-1", wifi).handover)
    }
}
