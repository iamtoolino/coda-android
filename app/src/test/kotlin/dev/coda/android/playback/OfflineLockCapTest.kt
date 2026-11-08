package dev.coda.android.playback

import dev.coda.core.connectivity.RecoveryPolicy
import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.QueueEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Note: locks only for the first 10 minutes of the wait, then relaxed retries; the callback still resumes. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineLockCapTest {
    private val minute = 60_000L

    @Test fun locksReleaseAtTheCapRetriesRelaxWithTheScreenAndTheCallbackResumes() = runTest {
        var online = false
        var screenOn = true
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, networkAvailable = { online }, nowMs = { testScheduler.currentTime },
            recoveryPolicy = RecoveryPolicy(lockCapMs = 10 * minute, relaxedScreenOnMs = 2 * minute, relaxedScreenOffMs = 5 * minute),
            screenOn = { screenOn })
        var locks = SessionLocks(false, false)
        val lockChanges = mutableListOf<Pair<Long, SessionLocks>>()
        // Focus is still held from earlier audible playback: the cap must release the locks anyway.
        SessionLockDriver(backgroundScope, controller.state, { true }, { testScheduler.currentTime }) {
            if (it != locks) lockChanges += testScheduler.currentTime to it
            locks = it
        }
        val pings = mutableListOf<Long>()
        val token = UUID.randomUUID()
        val probe = ConnectionProbe(controller, { ConnectionProbe.Session(token) {
            pings += testScheduler.currentTime
            if (!online) throw java.io.IOException("offline")
        } }, {}, { testScheduler.currentTime })
        backgroundScope.launch { while (isActive) { probe.runIfDue(); delay(200) } }

        controller.replaceQueue(listOf(QueueEntry("remote")), startPositionMs = 1234)
        runCurrent()
        assertEquals(SessionLocks(wake = true, wifi = true), locks)

        // Fast backoff for the first 10 minutes: probes at 30 s intervals once saturated; locks held to the last ms.
        advanceTimeBy(10 * minute - 1); runCurrent()
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
        assertTrue(pings.size > 20)
        advanceTimeBy(1); runCurrent()
        assertEquals(SessionLocks(wake = false, wifi = false), locks)
        assertEquals(listOf(0L to SessionLocks(true, true), 10 * minute to SessionLocks(false, false)), lockChanges)
        assertNotNull(controller.recovery.waiting)

        // Relaxed: the first probe past the cap fails and schedules 2 min (screen on); the next, with the
        // screen off by then, schedules 5 min.
        advanceTimeBy(2 * minute - 1_000); runCurrent()
        val relaxed = pings.filter { it >= 10 * minute }
        assertEquals(1, relaxed.size)
        val first = relaxed.single()
        screenOn = false
        advanceTimeBy(2 * minute); runCurrent()
        val second = pings.last()
        assertEquals(2 * minute, second - first)
        advanceTimeBy(5 * minute - 30_000); runCurrent()
        val third = pings.last()
        assertEquals(5 * minute, third - second)
        assertEquals(SessionLocks(false, false), locks)
        assertEquals(2, lockChanges.size)

        // No timer is due for minutes, but the network-available callback probes and resumes at once.
        val before = pings.size
        advanceTimeBy(30_000); runCurrent()
        assertEquals(before, pings.size)
        online = true
        controller.networkChanged()
        advanceTimeBy(250); runCurrent()
        assertEquals(before + 1, pings.size)
        assertNull(controller.state.value.waiting)
        assertEquals(1234L, engine.loadCalls.last().startPositionMs)
        assertTrue(engine.loadCalls.last().playWhenReady)
        // Audible playback holds the locks again.
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
    }

    @Test fun pausedWaitHoldsNoLocksAndPlayBeforeTheCapKeepsTheOriginalDeadline() = runTest {
        val controller = PlaybackController(FakeAudioEngine(), networkAvailable = { false }, nowMs = { testScheduler.currentTime })
        var locks = SessionLocks(false, false)
        SessionLockDriver(backgroundScope, controller.state, { false }, { testScheduler.currentTime }) { locks = it }
        controller.replaceQueue(listOf(QueueEntry("remote")))
        runCurrent(); assertTrue(locks.wake)
        controller.pause(); runCurrent(); assertFalse(locks.wake)
        advanceTimeBy(4 * minute)
        controller.play(); runCurrent(); assertTrue(locks.wake)
        advanceTimeBy(6 * minute - 1); runCurrent(); assertTrue(locks.wake)
        advanceTimeBy(1); runCurrent(); assertFalse(locks.wake)
    }
}
