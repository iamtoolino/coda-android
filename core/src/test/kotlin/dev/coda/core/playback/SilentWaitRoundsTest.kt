package dev.coda.core.playback

import dev.coda.core.connectivity.ConnectivityRecovery
import dev.coda.core.connectivity.RecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-1: a remote stream that keeps failing as Network while the ping succeeds (HTTP 5xx, a refused
 * redirect) keeps its backoff and lock window across wait rounds of the same item while it produced no sound, and after
 * [PlaybackController.MAX_SILENT_WAIT_ROUNDS] rounds fails the normal way instead of waiting forever. Fake clock throughout.
 */
class SilentWaitRoundsTest {
    private var now = 0L
    private val policy = RecoveryPolicy()
    private val engine = FakeAudioEngine()
    private val player = PlaybackController(engine, nowMs = { now }, recoveryPolicy = policy)
    private fun mp3(id: String) = QueueEntry(id, formatName = "MP3", bitRate = 256, durationMs = 240_000)
    private val waiting get() = checkNotNull(player.recovery.waiting)
    private fun failNetwork() = engine.emit(EngineEvent.Failed(checkNotNull(engine.current).occurrence, PlaybackFailure.Network))
    private fun sounds() = engine.emit(EngineEvent.State(checkNotNull(engine.current).occurrence, isPlaying = true, isBuffering = false))
    /** The probe fires when due and the ping answers: the item is loaded again. */
    private fun pingOk() { now = waiting.retryAtMs; player.probeSucceeded(waiting.target) }
    private fun pingFails() { now = waiting.retryAtMs; player.probeFailed() }
    private fun assertWaits(attempt: Int, sinceMs: Long) {
        assertEquals("Waiting for connection", player.status)
        assertNull(player.state.value.failure)
        assertEquals(attempt, waiting.attempt)
        assertEquals(now + ConnectivityRecovery.retryDelayMs(attempt), waiting.retryAtMs)
        assertEquals(sinceMs, waiting.sinceMs)
        assertEquals(sinceMs + policy.lockCapMs, waiting.lockUntilMs)
    }

    @Test fun aServerErrorWithPingOkKeepsBackoffAndLockWindowAcrossRounds() { // (a)
        player.replaceQueue(listOf(mp3("a")))
        now = 500
        failNetwork()
        assertWaits(attempt = 0, sinceMs = 500)
        pingOk(); failNetwork()
        assertWaits(attempt = 1, sinceMs = 500)
        pingOk(); failNetwork()
        assertWaits(attempt = 2, sinceMs = 500)
    }

    @Test fun afterThreeRoundsTheTrackFailsAndWaitingEnds() { // (b); moving on to a next item: FR-1b SkipBrokenItemsTest
        player.replaceQueue(listOf(mp3("a")))
        failNetwork()
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { pingOk(); failNetwork() }
        assertNotNull("Three rounds still wait", player.recovery.waiting)
        val loads = engine.loadCalls.size
        pingOk(); failNetwork()
        assertEquals(loads + 1, engine.loadCalls.size)
        assertNull(player.recovery.waiting)
        assertNull(player.state.value.waiting)
        assertEquals(PlaybackFailure.Unknown, player.state.value.failure)
        assertEquals("Playback failed", player.status)
        assertEquals(false, player.state.value.isPlaying)
        assertEquals("The last item stays on its failure", 0, player.currentIndex)
    }

    @Test fun aRoundThatProducedSoundResetsTheCount() { // (c)
        player.replaceQueue(listOf(mp3("a")))
        failNetwork()
        pingOk(); failNetwork()
        pingOk(); sounds()
        now += 60_000
        engine.position = 60_000
        failNetwork()
        assertWaits(attempt = 0, sinceMs = now)
        pingOk(); failNetwork()
        val since = now
        assertWaits(attempt = 0, sinceMs = since)
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { round ->
            pingOk(); failNetwork()
            assertWaits(attempt = round + 1, sinceMs = since)
        }
        pingOk(); failNetwork()
        assertEquals(PlaybackFailure.Unknown, player.state.value.failure)
        assertNull(player.recovery.waiting)
    }

    @Test fun aDifferentItemStartsFresh() { // (d)
        player.replaceQueue(listOf(mp3("a"), mp3("b")))
        failNetwork()
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { pingOk(); failNetwork() }
        now += 1_000
        player.next()
        assertEquals(1, player.currentIndex)
        // Nothing of a's three rounds carries over: b's first silent round starts fresh, and b gets all three.
        assertWaits(attempt = 0, sinceMs = now)
        pingOk(); failNetwork()
        val since = now
        assertWaits(attempt = 0, sinceMs = since)
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { round ->
            pingOk(); failNetwork()
            assertWaits(attempt = round + 1, sinceMs = since)
        }
        pingOk(); failNetwork()
        assertEquals(PlaybackFailure.Unknown, player.state.value.failure)
        assertEquals(1, player.currentIndex)
    }

    @Test fun aLongOutageThenASilentFailureRetriesInOneSecondWithAFreshLockWindow() { // Rex P2-1
        player.replaceQueue(listOf(mp3("a")))
        sounds()
        engine.position = 30_000
        failNetwork()
        now = policy.lockCapMs + 60_000 // the outage outlasted the lock cap
        player.probeFailed()
        assertFalse("BAT-6b: asleep past the cap", waiting.scheduled)
        pingOk(); failNetwork()
        assertWaits(attempt = 0, sinceMs = now)
    }

    @Test fun aNetworkChangeGivesTheItemItsFullRoundsAgain() {
        player.replaceQueue(listOf(mp3("a")))
        failNetwork()
        pingOk(); failNetwork()
        assertNotNull(player.recovery.waiting)
        now += 5_000
        player.networkChanged()
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS) {
            pingOk(); failNetwork()
            assertNotNull("Rounds after the network change still wait", player.recovery.waiting)
        }
        pingOk(); failNetwork()
        assertEquals(PlaybackFailure.Unknown, player.state.value.failure)
        assertNull(player.recovery.waiting)
    }

    @Test fun theCutOffStartRetryCountsAsARound() {
        player.replaceQueue(listOf(mp3("a")))
        engine.emit(EngineEvent.Failed(checkNotNull(engine.current).occurrence, PlaybackFailure.Decode, cutOffStart = true))
        assertWaits(attempt = 0, sinceMs = 0)
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { round ->
            pingOk(); failNetwork()
            assertWaits(attempt = round + 1, sinceMs = 0)
        }
        pingOk(); failNetwork()
        assertEquals(PlaybackFailure.Unknown, player.state.value.failure)
        assertNull(player.recovery.waiting)
    }

    @Test fun aRealOutageWithFailingPingWaitsAsBefore() { // (e)
        player.replaceQueue(listOf(mp3("a")))
        sounds()
        engine.position = 30_000
        now = 1_000
        failNetwork()
        assertWaits(attempt = 0, sinceMs = 1_000)
        repeat(8) { i ->
            pingFails()
            assertEquals(minOf(i + 1, 5), waiting.attempt)
            assertEquals(1_000 + policy.lockCapMs, waiting.lockUntilMs)
        }
        assertEquals(30_000, waiting.positionMs)
        now = 2_000_000 // past the lock cap: asleep, still waiting
        player.probeFailed()
        assertFalse(waiting.scheduled)
        pingOk(); sounds()
        assertNull(player.recovery.waiting)
        assertNull(player.state.value.failure)
        // The connection drops again mid-song: a fresh wait, however often it happens.
        repeat(5) {
            now += 10_000
            failNetwork()
            assertWaits(attempt = 0, sinceMs = now)
            pingOk(); sounds()
        }
        assertNull(player.state.value.failure)
    }
}
