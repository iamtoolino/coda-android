package dev.coda.android.playback

import dev.coda.android.net.NetworkSwitch
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.connectivity.RecoveryPolicy
import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.QueueEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * An auto-resume wait (Play pressed, server unreachable) keeps today's probes, locks and
 * foreground for its first 10 min (the lock cap). Then it sleeps like a paused wait: no timed probe, no locks (the
 * foreground service stays); only Play, Retry or a real network switch ([NetworkSwitch], as the
 * service wires `NetworkMonitor.switches`) probe it, and a successful probe still resumes playback by itself. Fake clock;
 * the 200 ms poll and the lock driver run as the service runs them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LongAutoResumeWaitTest {
    private val minute = 60_000L
    private val wifi = NetworkState(available = true, unmetered = true, cellular = false)
    private val engine = FakeAudioEngine()
    private var serverUp = false
    private val pings = mutableListOf<Long>()
    private var locks = SessionLocks(false, false)
    private val switch = NetworkSwitch("100/[1]", wifi)

    private fun TestScope.waitingController(): PlaybackController {
        // Offline at start (as OfflineLockCapTest): the wait starts at once at the queue's position.
        val controller = PlaybackController(engine, networkAvailable = { serverUp }, nowMs = { testScheduler.currentTime }, recoveryPolicy = RecoveryPolicy())
        SessionLockDriver(backgroundScope, controller.state, { true }, { testScheduler.currentTime }) { locks = it }
        val probe = ConnectionProbe(controller, { ConnectionProbe.Session(TOKEN) {
            pings += testScheduler.currentTime
            if (!serverUp) throw java.io.IOException("down")
        } }, {}, { testScheduler.currentTime })
        backgroundScope.launch { while (isActive) { probe.runIfDue(); delay(200) } }
        controller.replaceQueue(listOf(QueueEntry("remote")), startPositionMs = 1234)
        runCurrent()
        assertTrue(controller.recovery.waiting!!.autoResume)
        return controller
    }
    private fun PlaybackController.deliver(identity: String) { if (switch.update(identity, wifi)) networkChanged() }

    @Test fun theFirstTenMinutesKeepTimedProbesAndLocks() = runTest {
        val controller = waitingController()
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
        advanceTimeBy(10 * minute - 1); runCurrent()
        assertTrue("Fast backoff, 30 s apart once saturated", pings.size > 20)
        assertTrue(pings.zipWithNext { a, b -> b - a }.all { it <= 30_000 })
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
        assertTrue(controller.recovery.waiting!!.scheduled)
    }

    @Test fun pastTheCapItSleepsAndOnlyARealSwitchWakesItAndThenItPlaysByItself() = runTest {
        val controller = waitingController()
        advanceTimeBy(10 * minute + 31_000); runCurrent() // the last fast probe (scheduled before the cap) has failed
        assertEquals(SessionLocks(false, false), locks)
        assertFalse(controller.recovery.waiting!!.scheduled)
        val asleep = pings.size
        advanceTimeBy(60 * minute); runCurrent()
        assertEquals("No timed probe while asleep", asleep, pings.size)
        // Signal-only updates of the same network: nothing.
        repeat(20) { controller.deliver("100/[1]"); advanceTimeBy(1_000); runCurrent() }
        assertEquals(asleep, pings.size)
        // A real switch: one probe now, then the 30 s fast-retry window (locks with it), then asleep again.
        controller.deliver("200/[0]"); advanceTimeBy(250); runCurrent()
        assertEquals(asleep + 1, pings.size)
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
        advanceTimeBy(RecoveryPolicy.SWITCH_FAST_RETRY_MS + 31_000); runCurrent()
        val windowEnd = pings.size
        assertTrue("Fast retries inside the window", windowEnd > asleep + 2)
        assertEquals(SessionLocks(false, false), locks)
        advanceTimeBy(60 * minute); runCurrent()
        assertEquals(windowEnd, pings.size)
        // The next switch finds the server: it plays by itself at the saved position.
        serverUp = true
        controller.deliver("300/[1]"); advanceTimeBy(250); runCurrent()
        assertEquals(windowEnd + 1, pings.size)
        assertNull(controller.state.value.waiting)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(1234L, engine.loadCalls.last().startPositionMs)
        assertTrue(engine.loadCalls.last().playWhenReady)
    }

    /** Rex: Play or Retry on a sleeping wait opens the 30 s fast-retry window (with its locks), then it sleeps again. */
    @Test fun retryAndPlayOpenTheFastRetryWindowOfASleepingWait() = runTest {
        val controller = waitingController()
        advanceTimeBy(10 * minute + 31_000); runCurrent()
        val asleep = pings.size
        controller.retryNow(); advanceTimeBy(250); runCurrent()
        assertEquals(asleep + 1, pings.size)
        assertEquals(SessionLocks(wake = true, wifi = true), locks)
        advanceTimeBy(RecoveryPolicy.SWITCH_FAST_RETRY_MS + 31_000); runCurrent()
        val afterRetry = pings.size
        assertTrue("Fast retries after Retry", afterRetry > asleep + 2)
        assertEquals(SessionLocks(false, false), locks)
        advanceTimeBy(60 * minute); runCurrent()
        assertEquals("Asleep again", afterRetry, pings.size)
        controller.pause(); controller.play(); advanceTimeBy(250); runCurrent()
        assertEquals(afterRetry + 1, pings.size)
        advanceTimeBy(RecoveryPolicy.SWITCH_FAST_RETRY_MS + 31_000); runCurrent()
        val afterPlay = pings.size
        assertTrue("Fast retries after Play", afterPlay > afterRetry + 2)
        advanceTimeBy(60 * minute); runCurrent()
        assertEquals("Asleep again", afterPlay, pings.size)
        assertNotNull(controller.recovery.waiting)
        assertTrue(controller.recovery.waiting!!.autoResume)
        serverUp = true
        controller.retryNow(); advanceTimeBy(250); runCurrent()
        assertNull(controller.state.value.waiting)
        assertTrue(controller.state.value.isPlaying)
    }

    private companion object { val TOKEN: UUID = UUID.randomUUID() }
}
