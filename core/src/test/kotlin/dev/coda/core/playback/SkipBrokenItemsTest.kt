package dev.coda.core.playback

import dev.coda.core.connectivity.RecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-1b: a song that fails for good while the server answers (NotFound, Http, Decode, Unknown, the FR-1
 * silent rounds) shows the failure and the queue moves on to the next song. No skip on Network (waits / continues from the
 * local copy) or Unauthorized. After [PlaybackController.MAX_CONSECUTIVE_SKIPS] broken songs in a row without any sound it
 * stops on the failure. Fake clock throughout.
 */
class SkipBrokenItemsTest {
    private var now = 0L
    private var cached = false
    private val engine = FakeAudioEngine()
    private val player = PlaybackController(engine, nowMs = { now }, recoveryPolicy = RecoveryPolicy(),
        sourceResolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source })
    private fun mp3(id: String) = QueueEntry(id, formatName = "MP3", bitRate = 256, durationMs = 240_000)
    private fun queue(n: Int) = (0 until n).map { mp3("s$it") }
    private val loadedSongs get() = engine.loadCalls.map { (it.current.source as? TrackSource.Remote)?.songId ?: it.current.source }
    private val waiting get() = checkNotNull(player.recovery.waiting)
    private fun fail(failure: PlaybackFailure) = engine.emit(EngineEvent.Failed(checkNotNull(engine.current).occurrence, failure))
    private fun sounds() = engine.emit(EngineEvent.State(checkNotNull(engine.current).occurrence, isPlaying = true, isBuffering = false))
    private fun pingOk() { now = waiting.retryAtMs; player.probeSucceeded(waiting.target) }
    private fun pingFails() { now = waiting.retryAtMs; player.probeFailed() }

    private fun assertPlays(index: Int) {
        assertEquals(index, player.currentIndex)
        assertNull(player.state.value.failure)
        assertNull(player.recovery.waiting)
        assertTrue(player.isPlaying)
        assertEquals(player.queue[index].songId, loadedSongs.last())
    }
    private fun assertStopsOnFailure(index: Int, failure: PlaybackFailure) {
        assertEquals(index, player.currentIndex)
        assertEquals(failure, player.state.value.failure)
        assertEquals("Playback failed", player.status)
        assertEquals(false, player.isPlaying)
        assertNull(player.recovery.waiting)
    }

    @Test fun aFinalFailureWhileTheServerAnswersAdvancesToTheNextItemAndPlays() {
        for (failure in listOf(PlaybackFailure.NotFound, PlaybackFailure.Http(500), PlaybackFailure.Decode, PlaybackFailure.Unknown)) {
            val items = queue(2)
            player.replaceQueue(items)
            fail(failure)
            assertPlays(1)
            assertEquals("The failure stays recorded on the skipped item", failure, player.state.value.entryFailures[items[0].id])
            assertTrue(engine.loadCalls.last().playWhenReady)
        }
    }

    @Test fun aDecodeFailureOfACachedFileAdvances() {
        cached = true
        player.replaceQueue(queue(2))
        fail(PlaybackFailure.Decode)
        assertEquals(1, player.currentIndex)
        assertEquals(TrackSource.CachedFile("/cache/s1"), engine.loadCalls.last().current.source)
        assertTrue(player.isPlaying)
    }

    @Test fun theFr1FailureAfterTheSilentRoundsAdvances() {
        player.replaceQueue(queue(2))
        fail(PlaybackFailure.Network)
        repeat(PlaybackController.MAX_SILENT_WAIT_ROUNDS - 1) { pingOk(); fail(PlaybackFailure.Network) }
        assertEquals(0, player.currentIndex)
        assertNotNull("Still within the silent rounds", player.recovery.waiting)
        pingOk(); fail(PlaybackFailure.Network)
        assertPlays(1)
        assertEquals(PlaybackFailure.Unknown, player.state.value.entryFailures[player.queue[0].id])
    }

    @Test fun aNetworkFailureWithAFailingPingAndNoLocalCopyWaitsAndDoesNotSkip() {
        player.replaceQueue(queue(2))
        sounds()
        engine.position = 30_000
        fail(PlaybackFailure.Network)
        repeat(10) { pingFails() }
        assertEquals(0, player.currentIndex)
        assertEquals("Waiting for connection", player.status)
        assertNull(player.state.value.failure)
        assertEquals(1, engine.loadCalls.size)
    }

    @Test fun aNetworkFailureWithACompleteLocalCopyContinuesFromCache() {
        player.replaceQueue(queue(2))
        sounds()
        engine.position = 42_000
        cached = true
        fail(PlaybackFailure.Network)
        assertEquals(0, player.currentIndex)
        assertNull(player.recovery.waiting)
        assertNull(player.state.value.failure)
        assertEquals(listOf<Pair<TrackSource, Long>>(TrackSource.CachedFile("/cache/s0") to 42_000L), engine.replacedSources)
        assertEquals(1, engine.loadCalls.size)
    }

    @Test fun anUnauthorizedFailureIsNotSkipped() {
        player.replaceQueue(queue(2))
        fail(PlaybackFailure.Unauthorized)
        assertStopsOnFailure(0, PlaybackFailure.Unauthorized)
        assertEquals(1, engine.loadCalls.size)
    }

    @Test fun fiveBrokenItemsInARowStopOnTheFifthWithTheFailure() {
        player.replaceQueue(queue(8))
        repeat(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1) { i ->
            fail(PlaybackFailure.NotFound)
            assertPlays(i + 1)
        }
        fail(PlaybackFailure.NotFound)
        assertStopsOnFailure(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1, PlaybackFailure.NotFound)
        assertEquals(PlaybackController.MAX_CONSECUTIVE_SKIPS, engine.loadCalls.size)
        // A user action (Play) starts a new run: the item fails again and the queue moves on.
        player.play()
        fail(PlaybackFailure.NotFound)
        assertPlays(PlaybackController.MAX_CONSECUTIVE_SKIPS)
    }

    @Test fun aPlayableItemInBetweenResetsTheCount() {
        player.replaceQueue(queue(12))
        repeat(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1) { fail(PlaybackFailure.NotFound) }
        assertPlays(4)
        sounds()
        engine.advance() // item 4 played through, gapless into item 5
        assertEquals(5, player.currentIndex)
        repeat(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1) { fail(PlaybackFailure.NotFound) }
        assertPlays(9)
        fail(PlaybackFailure.NotFound)
        assertStopsOnFailure(9, PlaybackFailure.NotFound)
    }

    @Test fun theLastItemWithRepeatOffStops() {
        player.replaceQueue(queue(2), startAt = 1)
        fail(PlaybackFailure.NotFound)
        assertStopsOnFailure(1, PlaybackFailure.NotFound)
        assertEquals(1, engine.loadCalls.size)
    }

    @Test fun repeatAllWithAllItemsBrokenStopsAfterFive() {
        player.setRepeatMode(RepeatMode.ALL)
        player.replaceQueue(queue(3))
        repeat(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1) { i ->
            fail(PlaybackFailure.NotFound)
            assertPlays((i + 1) % 3)
        }
        fail(PlaybackFailure.NotFound)
        assertStopsOnFailure(1, PlaybackFailure.NotFound)
        assertEquals(PlaybackController.MAX_CONSECUTIVE_SKIPS, engine.loadCalls.size)
    }

    /** FR-1b Rex P3-1: a broken song that reports playing before it fails (no real sound) still counts as broken. */
    @Test fun repeatAllWithItemsThatReportPlayingThenFailStopsAfterFive() {
        player.setRepeatMode(RepeatMode.ALL)
        player.replaceQueue(queue(3))
        repeat(PlaybackController.MAX_CONSECUTIVE_SKIPS - 1) { i ->
            sounds(); fail(PlaybackFailure.Decode)
            assertPlays((i + 1) % 3)
        }
        sounds(); fail(PlaybackFailure.Decode)
        assertStopsOnFailure(1, PlaybackFailure.Decode)
        assertEquals(PlaybackController.MAX_CONSECUTIVE_SKIPS, engine.loadCalls.size)
    }

    @Test fun repeatOneWithABrokenItemStops() {
        player.setRepeatMode(RepeatMode.ONE)
        player.replaceQueue(queue(2))
        fail(PlaybackFailure.NotFound)
        assertStopsOnFailure(0, PlaybackFailure.NotFound)
        assertEquals(1, engine.loadCalls.size)
    }

    @Test fun theEndOfASongSkipsASuccessorThatAlreadyFailed() {
        player.replaceQueue(queue(3))
        sounds()
        engine.emit(EngineEvent.Failed(checkNotNull(engine.next).occurrence, PlaybackFailure.NotFound))
        engine.finish()
        assertPlays(2)
        assertEquals(PlaybackFailure.NotFound, player.state.value.entryFailures[player.queue[1].id])
    }
}
