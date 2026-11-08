package dev.coda.android.playback

import dev.coda.core.playback.FakeAudioEngine
import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.PlaybackState
import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.TrackSource
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverySessionTest {
    private fun offlineController(engine: FakeAudioEngine) =
        PlaybackController(engine, networkAvailable = { false }, nowMs = { 5_000 }).apply {
            replaceQueue(listOf(QueueEntry("remote")), startPositionMs = 1234)
        }

    @Test fun retryAttemptsImmediatelyReschedulesCountdownAndReconnectsWithoutChangingIntent() = runTest {
        val engine = FakeAudioEngine()
        var now = 5_000L
        var online = false
        var attempts = 0
        val controller = PlaybackController(engine, networkAvailable = { online }, nowMs = { now }).apply {
            replaceQueue(listOf(QueueEntry("remote")), startPositionMs = 1234)
        }
        val session = ConnectionProbe.Session(UUID.randomUUID()) {
            attempts++
            if (!online) throw java.io.IOException("Synthetic outage")
        }
        val probe = ConnectionProbe(controller, { session }, {}, { now })
        probe.runIfDue()
        assertEquals(0, attempts)
        controller.retryNow()
        probe.runIfDue()
        assertEquals(1, attempts)
        assertTrue(controller.recovery.waiting!!.autoResume)
        assertTrue(controller.playWhenReady)
        assertEquals(now + 2_000, controller.recovery.waiting!!.retryAtMs)
        probe.runIfDue()
        assertEquals(1, attempts) // No busy-loop between scheduled retries.
        online = true
        now += 2_000
        probe.runIfDue()
        assertNull(controller.state.value.waiting)
        assertTrue(engine.loadCalls.single().playWhenReady)
        assertEquals(1234, engine.loadCalls.single().startPositionMs)
    }

    @Test fun retryHonoursBothPausedBeforeOutageAndExplicitPauseWhileWaiting() = runTest {
        for (pausedBeforeOutage in listOf(true, false)) {
            val engine = FakeAudioEngine()
            val controller = PlaybackController(engine, nowMs = { 5_000 }).apply {
                replaceQueue(listOf(QueueEntry("remote")), startsPlaying = !pausedBeforeOutage)
            }
            engine.failCurrent(dev.coda.core.playback.PlaybackFailure.Network)
            if (!pausedBeforeOutage) controller.pause() // Notification/headset Pause.
            assertFalse(controller.recovery.waiting!!.autoResume)
            controller.retryNow()
            var attempts = 0
            val session = ConnectionProbe.Session(UUID.randomUUID()) { attempts++ }
            ConnectionProbe(controller, { session }, {}, { 5_000 }).runIfDue()
            assertEquals(1, attempts)
            assertNull(controller.state.value.waiting)
            assertFalse(engine.loadCalls.last().playWhenReady)
            assertFalse(controller.playWhenReady)
        }
    }

    @Test fun staleUnauthorizedProbeCannotStopNewerPlaybackOrInvalidateANewerLogin() = runTest {
        val engine = FakeAudioEngine()
        val controller = offlineController(engine)
        val waiting = controller.recovery.waiting!!
        controller.retryNow()
        val stops = engine.stopCalls
        val gate = CompletableDeferred<Unit>()
        val old = ConnectionProbe.Session(UUID.randomUUID()) { gate.await(); throw SubsonicError.Unauthorized() }
        val newer = ConnectionProbe.Session(UUID.randomUUID()) { throw SubsonicError.Unauthorized() }
        var current: ConnectionProbe.Session? = old
        val invalidated = mutableListOf<UUID>()
        val probe = ConnectionProbe(controller, { current }, { invalidated += it }, { 5_000 })
        val pending = launch { probe.runIfDue() }
        runCurrent()
        current = newer // Sign out and in again while the old ping is outstanding.
        gate.complete(Unit); pending.join()
        assertTrue(invalidated.isEmpty())
        assertEquals(stops, engine.stopCalls)
        assertEquals(waiting.target, controller.recovery.waiting?.target)
        assertEquals("Waiting for connection", controller.status)
        // The newer session's own rejection is still honoured.
        probe.runIfDue()
        assertEquals(listOf(newer.token), invalidated)
        assertNull(controller.recovery.waiting); assertEquals("Stopped", controller.status)
    }

    @Test fun waitingToResumeHoldsLocksWithoutFocusWhenPlaybackStartsOffline() {
        val controller = offlineController(FakeAudioEngine())
        val state = controller.state.value
        assertTrue(state.waiting!!.autoResume)
        assertEquals(SessionLocks(wake = true, wifi = true), sessionLocks(state, focused = false))
        controller.pause()
        assertEquals(SessionLocks(wake = false, wifi = false), sessionLocks(controller.state.value, focused = false))
        assertEquals(SessionLocks(wake = false, wifi = false), sessionLocks(PlaybackState(isPlaying = true), focused = false))
        val cached = PlaybackController(FakeAudioEngine()).apply {
            replaceQueue(listOf(QueueEntry("c", source = TrackSource.CachedFile("/synthetic"))))
        }.state.value
        assertEquals(SessionLocks(wake = true, wifi = false), sessionLocks(cached, focused = true))
    }
}
