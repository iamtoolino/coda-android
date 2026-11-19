package dev.coda.core.playback

import dev.coda.core.connectivity.RecoveryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a pause of [BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS] with no read-ahead download
 * running, the player's stream is released (queue, item, position kept); Play reloads at the position. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepSleepTest {
    private val grace = BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS
    private val engine = FakeAudioEngine()
    private val player = PlaybackController(engine, nowMs = { 0 }, recoveryPolicy = RecoveryPolicy())
    private val busy = MutableStateFlow(false)
    private val call = MutableStateFlow(false)
    private val sleep = DeepSleep(player.state, busy, player::releaseIdle, transientHold = call)
    private var baseStops = 0
    private val releases get() = engine.stopCalls - baseStops
    private fun queue() = listOf(QueueEntry("a", durationMs = 240_000), QueueEntry("b", durationMs = 240_000))

    private fun TestScope.playThenPauseAt(positionMs: Long) {
        backgroundScope.launch { sleep.run() }
        player.replaceQueue(queue(), startsPlaying = true)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, isPlaying = true, isBuffering = false))
        runCurrent()
        baseStops = engine.stopCalls
        engine.position = positionMs
        player.pause()
        runCurrent()
    }

    @Test fun nothingIsReleasedWithinTheGrace() = runTest {
        playThenPauseAt(42_000)
        advanceTimeBy(grace - 1); runCurrent()
        assertEquals(0, releases)
        assertFalse(sleep.asleep.value)
        player.play() // a quick pause plays on from the loaded item, no reload
        assertEquals(1, engine.loadCalls.size)
        assertTrue(engine.playCalls > 0)
    }

    @Test fun afterTheGraceTheStreamIsReleasedAndPlayReloadsAtThePosition() = runTest {
        playThenPauseAt(42_000)
        advanceTimeBy(grace + 1); runCurrent()
        assertEquals(1, releases)
        assertTrue(sleep.asleep.value)
        // Visible state unchanged: the paused item at its position.
        assertEquals(0, player.currentIndex)
        assertEquals(42_000, player.positionMs)
        assertTrue(player.state.value.isPrepared)
        assertEquals("Paused", player.status)
        player.play(); runCurrent()
        assertEquals(2, engine.loadCalls.size)
        assertEquals(42_000, engine.loadCalls.last().startPositionMs)
        assertTrue(engine.loadCalls.last().playWhenReady)
        assertFalse(sleep.asleep.value)
    }

    /** The release also closes idle pooled connections, once, and not before the grace ends. */
    @Test fun theReleaseClosesIdleConnectionsOnce() = runTest {
        var closes = 0
        val sleep = DeepSleep(player.state, busy, player::releaseIdle, transientHold = call, closeIdleConnections = { closes++ })
        backgroundScope.launch { sleep.run() }
        player.replaceQueue(queue(), startsPlaying = true)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, isPlaying = true, isBuffering = false))
        runCurrent()
        advanceTimeBy(10 * grace); runCurrent()
        assertEquals("playing: nothing closed", 0, closes)
        player.pause(); runCurrent()
        advanceTimeBy(grace - 1); runCurrent()
        assertEquals("within the grace: nothing closed", 0, closes)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, closes)
        assertTrue(sleep.asleep.value)
        advanceTimeBy(10 * grace); runCurrent()
        assertEquals("once per release", 1, closes)
    }

    @Test fun aRunningReadAheadDelaysTheReleaseUntilItIsDone() = runTest {
        busy.value = true
        playThenPauseAt(10_000)
        advanceTimeBy(5 * grace); runCurrent()
        assertEquals(0, releases)
        busy.value = false; runCurrent()
        assertEquals(1, releases)
        assertTrue(sleep.asleep.value)
    }

    /** A call (transient loss that resumes on gain) longer than the grace releases nothing; after it the grace runs. */
    @Test fun aLongTransientLossIsNotReleasedAndItsEndStartsTheGrace() = runTest {
        call.value = true // the focus policy reports it before the suspension is published
        playThenPauseAt(30_000)
        advanceTimeBy(10 * grace); runCurrent()
        assertEquals(0, releases)
        assertFalse(sleep.asleep.value)
        call.value = false; runCurrent() // the call ended without a resume (e.g. the user paused meanwhile)
        advanceTimeBy(grace + 1); runCurrent()
        assertEquals(1, releases)
    }

    @Test fun playingIsNeverReleased() = runTest {
        backgroundScope.launch { sleep.run() }
        player.replaceQueue(queue(), startsPlaying = true)
        baseStops = engine.stopCalls
        advanceTimeBy(10 * grace); runCurrent()
        assertEquals(0, releases)
        assertFalse(sleep.asleep.value)
    }
}

/**
 * The grace counts wall time. Virtual time is uptime; [FakeWallAlarm] has its own realtime clock that tests move
 * while uptime stays frozen (a sleeping CPU), and fires due alarms only on [FakeWallAlarm.wake] (a non-wakeup alarm).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallClockDeepSleepTest {
    private class FakeWallAlarm {
        var realtimeMs = 0L
        var cancels = 0
        private class Pending(val atMs: Long, val onFire: () -> Unit) { var done = false }
        private val pending = mutableListOf<Pending>()
        val set get() = pending.count { !it.done }
        val alarm = WallAlarm { afterMs, onFire ->
            val p = Pending(realtimeMs + afterMs, onFire).also { pending += it }
            return@WallAlarm { if (!p.done) { p.done = true; cancels++ } }
        }
        fun wake() = pending.filter { !it.done && it.atMs <= realtimeMs }.forEach { it.done = true; it.onFire() }
    }

    private val grace = BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS
    private val engine = FakeAudioEngine()
    private val player = PlaybackController(engine, nowMs = { 0 }, recoveryPolicy = RecoveryPolicy())
    private val busy = MutableStateFlow(false)
    private val call = MutableStateFlow(false)
    private val clock = FakeWallAlarm()
    private val sleep = DeepSleep(player.state, busy, player::releaseIdle, transientHold = call,
        wait = { wallClockDelay(it, clock.alarm) })
    private var baseStops = 0
    private val releases get() = engine.stopCalls - baseStops

    private fun TestScope.playThenPause() {
        backgroundScope.launch { sleep.run() }
        player.replaceQueue(listOf(QueueEntry("a", durationMs = 240_000)), startsPlaying = true)
        engine.emit(EngineEvent.State(engine.current!!.occurrence, isPlaying = true, isBuffering = false))
        runCurrent()
        baseStops = engine.stopCalls
        engine.position = 42_000
        player.pause()
        runCurrent()
    }

    @Test fun releasedAtTheFirstWakeAfterTheGraceOfRealtimeWithUptimeFrozen() = runTest {
        playThenPause()
        val uptime = testScheduler.currentTime
        assertEquals(1, clock.set)
        clock.realtimeMs += grace - 1; clock.wake(); runCurrent()
        assertEquals(0, releases)
        clock.realtimeMs += 2 * 60 * 60_000L; runCurrent() // hours of realtime, the CPU still asleep: nothing runs
        assertEquals(0, releases)
        clock.wake(); runCurrent() // the first wake after the deadline
        assertEquals(1, releases)
        assertTrue(sleep.asleep.value)
        assertEquals(uptime, testScheduler.currentTime)
        assertEquals(0, clock.set)
        advanceTimeBy(10 * grace); runCurrent() // the uptime delay was cancelled with the wait: no second release
        assertEquals(1, releases)
        player.play(); runCurrent() // Play after the release reloads at the position
        assertEquals(42_000, engine.loadCalls.last().startPositionMs)
        assertFalse(sleep.asleep.value)
    }

    @Test fun playBeforeTheDeadlineCancelsTheWaitAndTheAlarm() = runTest {
        playThenPause()
        clock.realtimeMs += grace / 2
        player.play(); runCurrent()
        assertEquals(0, clock.set)
        assertEquals(1, clock.cancels)
        clock.realtimeMs += 10 * grace; clock.wake(); advanceTimeBy(10 * grace); runCurrent()
        assertEquals(0, releases)
        assertEquals(1, engine.loadCalls.size) // played on from the loaded item
        assertFalse(sleep.asleep.value)
    }

    @Test fun aCallReleasesNothingAndSetsNoAlarm() = runTest {
        call.value = true
        playThenPause()
        assertEquals(0, clock.set)
        clock.realtimeMs += 10 * grace; clock.wake(); advanceTimeBy(10 * grace); runCurrent()
        assertEquals(0, releases)
        assertFalse(sleep.asleep.value)
    }

    @Test fun awakeTheUptimeDelayStillReleasesAndCancelsTheAlarm() = runTest {
        playThenPause()
        advanceTimeBy(grace + 1); runCurrent()
        assertEquals(1, releases)
        assertEquals(0, clock.set)
        assertEquals(1, clock.cancels)
    }

    /** The service unstarts itself only when asleep with no play requested. */
    @Test fun theServiceStopsOnlyWhenAsleepAndNoPlayRequested() {
        assertTrue(BackgroundWorkPolicy.stopsService(asleep = true, playWhenReady = false))
        assertFalse(BackgroundWorkPolicy.stopsService(asleep = true, playWhenReady = true))
        assertFalse(BackgroundWorkPolicy.stopsService(asleep = false, playWhenReady = false))
        assertFalse(BackgroundWorkPolicy.stopsService(asleep = false, playWhenReady = true))
    }

    @Test fun foregroundIsDroppedOnlyWhenAsleepAndNoPlayRequested() {
        assertFalse(BackgroundWorkPolicy.runsInForeground(required = true, asleep = true, playWhenReady = false))
        assertTrue(BackgroundWorkPolicy.runsInForeground(required = true, asleep = true, playWhenReady = true))
        assertTrue(BackgroundWorkPolicy.runsInForeground(required = true, asleep = false, playWhenReady = false))
        assertFalse(BackgroundWorkPolicy.runsInForeground(required = false, asleep = false, playWhenReady = true))
    }
}

/** Idle server connections close whenever nothing plays; screen on and playing keeps the pool. */
class IdleConnectionPolicyTest {
    @Test fun closesWheneverNothingPlaysOrScreenOffPlaying() {
        assertTrue(BackgroundWorkPolicy.closesIdleConnections(screenOn = true, playing = false))
        assertTrue(BackgroundWorkPolicy.closesIdleConnections(screenOn = false, playing = false))
        assertTrue(BackgroundWorkPolicy.closesIdleConnections(screenOn = false, playing = true))
        assertFalse(BackgroundWorkPolicy.closesIdleConnections(screenOn = true, playing = true))
    }
}
