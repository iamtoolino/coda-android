package dev.coda.core.connectivity

import dev.coda.core.playback.OccurrenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paused wait (autoResume false) probes only on Play, Retry or a network switch; an
 * auto-resume wait keeps its backoff. Fake clock.
 */
class PausedWaitProbeTest {
    private val recovery = ConnectivityRecovery()
    private val target = OccurrenceId()
    private fun dueAnywhereWithin(fromMs: Long, toMs: Long) = (fromMs..toMs step 1_000).any { recovery.due(it) }

    @Test fun aPausedWaitHasNoTimedProbe() {
        recovery.waitFor(target, 0, autoResume = false, nowMs = 0)
        assertFalse(recovery.waiting!!.scheduled)
        assertFalse(dueAnywhereWithin(0, 60 * 60_000))
    }

    @Test fun aNetworkSwitchProbesAPausedWaitOnceAndAFailureDoesNotReschedule() {
        recovery.waitFor(target, 0, autoResume = false, nowMs = 0)
        recovery.networkChanged(10_000)
        assertTrue(recovery.due(10_000))
        recovery.failed(10_100)
        assertFalse(dueAnywhereWithin(10_100, 60 * 60_000))
    }

    @Test fun playProbesAtOnceAndThenFollowsTheAutoResumeBackoff() {
        recovery.waitFor(target, 0, autoResume = false, nowMs = 0)
        recovery.play(5_000)
        assertTrue(recovery.due(5_000))
        recovery.failed(5_000)
        assertEquals(5_000 + ConnectivityRecovery.retryDelayMs(1), recovery.waiting!!.retryAtMs)
    }

    @Test fun pausingAnAutoResumeWaitStopsItsTimedProbes() {
        recovery.waitFor(target, 0, autoResume = true, nowMs = 0)
        recovery.pause()
        assertFalse(dueAnywhereWithin(0, 60 * 60_000))
    }

    @Test fun anAutoResumeWaitKeepsItsBackoff() {
        recovery.waitFor(target, 0, autoResume = true, nowMs = 0)
        assertEquals(1_000, recovery.waiting!!.retryAtMs)
        recovery.failed(1_000)
        assertEquals(3_000, recovery.waiting!!.retryAtMs)
        recovery.networkChanged(2_000)
        assertTrue(recovery.due(2_000))
    }

    /** Past the lock cap, a probe that fails right after a network switch is retried with the fast backoff. */
    @Test fun aSwitchAfterTheLockCapGivesAShortFastRetryWindow() {
        val policy = RecoveryPolicy()
        recovery.waitFor(target, 0, autoResume = true, nowMs = 0)
        val afterCap = policy.lockCapMs + 60_000
        recovery.failed(afterCap)
        assertFalse("BAT-6b: asleep past the cap", recovery.waiting!!.scheduled)
        recovery.networkChanged(afterCap + 1_000)
        recovery.failed(afterCap + 1_200) // the tunnel is not up yet
        assertEquals(afterCap + 1_200 + ConnectivityRecovery.retryDelayMs(1), recovery.waiting!!.retryAtMs)
        assertEquals(afterCap + 1_000 + RecoveryPolicy.SWITCH_FAST_RETRY_MS, recovery.waiting!!.lockUntilMs)
    }

    /**
     * An auto-resume wait keeps the fast backoff until its lock cap, then sleeps like a paused
     * wait: no timed probe; Play, Retry or a switch probe it (each with the 30 s fast window); it stays autoResume.
     */
    @Test fun anAutoResumeWaitSleepsAfterItsLockCap() {
        val cap = RecoveryPolicy().lockCapMs
        recovery.waitFor(target, 0, autoResume = true, nowMs = 0)
        recovery.failed(cap - 1)
        assertEquals("Fast backoff up to the cap", cap - 1 + ConnectivityRecovery.retryDelayMs(1), recovery.waiting!!.retryAtMs)
        assertFalse(recovery.waiting!!.asleep)
        recovery.failed(cap)
        assertTrue(recovery.waiting!!.asleep); assertTrue(recovery.waiting!!.autoResume)
        assertFalse(dueAnywhereWithin(cap, cap + 60 * 60_000))
        // Rex: Retry and Play open the switch's fast-retry window too.
        val retryAt = cap + 60 * 60_000
        recovery.retryNow(retryAt); assertTrue(recovery.due(retryAt))
        recovery.failed(retryAt + 100); assertTrue(recovery.waiting!!.scheduled)
        recovery.failed(retryAt + RecoveryPolicy.SWITCH_FAST_RETRY_MS); assertFalse(recovery.waiting!!.scheduled)
        val playAt = cap + 61 * 60_000
        recovery.play(playAt); assertTrue(recovery.due(playAt))
        recovery.failed(playAt + 100); assertTrue(recovery.waiting!!.scheduled)
        recovery.failed(playAt + RecoveryPolicy.SWITCH_FAST_RETRY_MS); assertFalse(recovery.waiting!!.scheduled)
        val switchAt = cap + 62 * 60_000
        recovery.networkChanged(switchAt); assertTrue(recovery.due(switchAt))
        recovery.failed(switchAt + 100)
        assertTrue("Fast retry inside the switch window", recovery.waiting!!.scheduled)
        recovery.failed(switchAt + RecoveryPolicy.SWITCH_FAST_RETRY_MS)
        assertFalse("Asleep again after the window", recovery.waiting!!.scheduled)
        assertTrue(recovery.waiting!!.asleep)
    }

    /** Play on a paused wait older than its lock cap gets the 30 s fast-retry window (attempt reset), then sleeps. */
    @Test fun playOnAPausedWaitPastItsCapOpensTheFastRetryWindow() {
        val cap = RecoveryPolicy().lockCapMs
        recovery.waitFor(target, 0, autoResume = false, nowMs = 0)
        repeat(6) { recovery.networkChanged(it * 1_000L); recovery.failed(it * 1_000L + 100) } // attempt saturated
        val playAt = cap + 60_000
        recovery.play(playAt); assertTrue(recovery.due(playAt))
        recovery.failed(playAt + 100)
        assertEquals("Fast retry", playAt + 100 + ConnectivityRecovery.retryDelayMs(1), recovery.waiting!!.retryAtMs)
        assertEquals(playAt + RecoveryPolicy.SWITCH_FAST_RETRY_MS, recovery.waiting!!.lockUntilMs)
        recovery.failed(playAt + RecoveryPolicy.SWITCH_FAST_RETRY_MS)
        assertFalse("Asleep after the window", recovery.waiting!!.scheduled)
    }
}
