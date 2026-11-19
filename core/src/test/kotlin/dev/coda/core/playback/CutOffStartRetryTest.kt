package dev.coda.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Item 1 (a), kept by WF-3i: a parser/format failure before the first sound of a remote stream
 * is a start cut off by a weak network. It waits and retries once; the second one is a real bad file. Cached files fail at once.
 */
class CutOffStartRetryTest {
    private fun mp3(id: String) = QueueEntry(id, formatName = "MP3", bitRate = 256, durationMs = 240_000)
    private val FakeAudioEngine.source get() = loadCalls.last().current.source
    private fun PlaybackController.reconnect() = probeSucceeded(checkNotNull(recovery.waiting).target)
    private fun FakeAudioEngine.failParser() = emit(EngineEvent.Failed(checkNotNull(current).occurrence, PlaybackFailure.Decode, cutOffStart = true))
    private fun FakeAudioEngine.sounds() = emit(EngineEvent.State(checkNotNull(current).occurrence, isPlaying = true, isBuffering = false))

    @Test fun aParserFailureBeforeTheFirstSoundOfARemoteStreamWaitsAndRetries() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, nowMs = { 0 })
        player.replaceQueue(listOf(mp3("a")))
        engine.failParser()
        assertEquals("Waiting for connection", player.status)
        assertNull(player.state.value.failure)
        assertNotNull(player.recovery.waiting)
        val loads = engine.loadCalls.size
        player.reconnect()
        assertEquals("The same item is loaded again", loads + 1, engine.loadCalls.size)
        assertEquals(TrackSource.Remote("a", StreamFormat.RAW), engine.source)
    }

    @Test fun aSecondParserFailureAfterTheReloadIsARealBadFile() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, nowMs = { 0 })
        player.replaceQueue(listOf(mp3("a")))
        engine.failParser()
        player.reconnect()
        engine.failParser()
        assertEquals(PlaybackFailure.Decode, player.state.value.failure)
        assertEquals("Playback failed", player.status)
        assertNull(player.recovery.waiting)
    }

    @Test fun aParserFailureOfACachedFileOrAfterTheFirstSoundFailsAtOnce() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, nowMs = { 0 })
        player.replaceQueue(listOf(QueueEntry("a", source = TrackSource.CachedFile("/cache/a.raw"), durationMs = 240_000)))
        engine.failParser()
        assertEquals(PlaybackFailure.Decode, player.state.value.failure)
        assertNull(player.recovery.waiting)

        player.replaceQueue(listOf(mp3("b")))
        engine.sounds()
        engine.position = 30_000
        engine.failParser()
        assertEquals("Mid-song a parser failure is a bad file as today", PlaybackFailure.Decode, player.state.value.failure)
        assertNull(player.recovery.waiting)
    }
}
