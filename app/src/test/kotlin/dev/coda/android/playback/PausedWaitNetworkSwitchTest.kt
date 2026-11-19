package dev.coda.android.playback

import dev.coda.android.net.NetworkSwitch
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.connectivity.RecoveryPolicy
import dev.coda.core.playback.EngineEvent
import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.PlaybackFailure
import dev.coda.core.playback.QueueEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The service calls `controller.networkChanged()` only on a real network switch
 * ([NetworkSwitch], as PlaybackService wires `NetworkMonitor.switches`), and a paused wait probes only on Play or such a
 * switch. [deliver] is that wiring; [NetworkSwitch] gets the identity and state each callback reads. Fake clock.
 */
class PausedWaitNetworkSwitchTest {
    private val wifi = NetworkState(available = true, unmetered = true, cellular = false)
    private var now = 0L
    private var pings = 0
    private var serverUp = false
    private val engine = FakeAudioEngine()
    private val controller = PlaybackController(engine, nowMs = { now }, recoveryPolicy = RecoveryPolicy())
    private val probe = ConnectionProbe(controller, { ConnectionProbe.Session(TOKEN) { pings++; if (!serverUp) throw java.io.IOException("down") } }, {}, { now })
    private val switch = NetworkSwitch("100/[1]", wifi)
    private var networkChangedCalls = 0
    private fun deliver(identity: String, state: NetworkState = wifi) {
        if (switch.update(identity, state)) { networkChangedCalls++; controller.networkChanged() }
    }
    /** The service's 200 ms probe poll over [ms]. */
    private suspend fun pollFor(ms: Long) { val end = now + ms; while (now < end) { probe.runIfDue(); now += 200 } }
    private fun pausedWait() {
        controller.replaceQueue(listOf(QueueEntry("s0", durationMs = 240_000), QueueEntry("s1", durationMs = 240_000)), startsPlaying = false)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Network))
        assertFalse(controller.recovery.waiting!!.autoResume)
    }

    @Test fun signalOnlyUpdatesAreNoSwitch() {
        assertFalse(switch.update("100/[1]", wifi))
        assertTrue(switch.update("101/[1]", wifi))
        assertTrue("A VPN underlay handover changes the state", switch.update("101/[1]", wifi.copy(unmetered = false, cellular = true)))
        assertFalse(switch.update("101/[1]", wifi.copy(unmetered = false, cellular = true)))
    }

    @Test fun aPausedWaitDoesNotProbeOnSignalOnlyChangesAndProbesOnceOnASwitch() = runTest {
        pausedWait()
        pollFor(30 * 60_000L)
        repeat(20) { deliver("100/[1]"); pollFor(1_000) }
        assertEquals(0, pings)
        assertEquals(0, networkChangedCalls)
        deliver("200/[0]")
        pollFor(60 * 60_000L)
        assertEquals("One probe for the switch, none after its failure", 1, pings)
        assertNotNull(controller.recovery.waiting)
        controller.play()
        pollFor(200)
        assertEquals(2, pings)
    }

    @Test fun anAutoResumeWaitKeepsItsTimedProbes() = runTest {
        controller.replaceQueue(listOf(QueueEntry("s0", durationMs = 240_000)), startsPlaying = true)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Network))
        assertTrue(controller.recovery.waiting!!.autoResume)
        pollFor(1_000 + 2_000 + 4_000 + 1)
        assertEquals(3, pings)
    }

    @Test fun signalOnlyChangesDoNotResetTheFr1SilentRounds() = runTest {
        serverUp = true
        controller.replaceQueue(listOf(QueueEntry("s0", durationMs = 240_000), QueueEntry("s1", durationMs = 240_000)), startsPlaying = true)
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS) {
            engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Network))
            deliver("100/[1]") // signal update between rounds
            pollFor(60_000)
        }
        assertEquals(0, networkChangedCalls)
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Network))
        assertNull("The rounds ran out: no further wait", controller.recovery.waiting)
        assertEquals(1, controller.currentIndex)
    }

    private companion object { val TOKEN: UUID = UUID.randomUUID() }
}
