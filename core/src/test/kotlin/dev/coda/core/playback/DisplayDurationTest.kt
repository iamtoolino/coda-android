package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** Defect B: the UI used the server's whole-second duration. */
class DisplayDurationTest {
    @Test fun theEnginesRealLengthReplacesTheServersWholeSeconds() {
        assertEquals(20_299L, resolveDisplayDurationMs(serverMs = 20_000, engineMs = 20_299))
        assertEquals(19_720L, resolveDisplayDurationMs(serverMs = 20_000, engineMs = 19_720))
    }

    @Test fun theServerValueIsTheFallbackUntilTheEngineKnows() {
        assertEquals(20_000L, resolveDisplayDurationMs(20_000, null))
        assertEquals(20_000L, resolveDisplayDurationMs(20_000, 0))
    }

    /** Header-less VBR MP3: Media3 estimates 3488 ms for a 20.3 s file. */
    @Test fun anImplausibleEngineEstimateIsNotTrusted() {
        assertEquals(20_000L, resolveDisplayDurationMs(20_000, 3_488))
        assertEquals(240_000L, resolveDisplayDurationMs(240_000, 600_000))
    }

    @Test fun withoutAServerValueTheEngineIsAllThereIs() {
        assertEquals(20_299L, resolveDisplayDurationMs(0, 20_299))
        assertEquals(0L, resolveDisplayDurationMs(0, null))
    }

    @Test fun controllerShowsTheEngineLengthOfTheCurrentItemOnly() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        val entries = listOf(entry("a", 20_000), entry("b", 30_000))
        player.replaceQueue(entries, startAt = 0)
        assertEquals(20_000L, player.displayDurationMs)
        val first = engine.current!!.occurrence
        engine.emit(EngineEvent.State(first, true, false, durationMs = 20_299))
        assertEquals(20_299L, player.displayDurationMs)
        assertEquals(20_299L, player.state.value.displayDurationMs)
        assertEquals(20_000L, player.durationMs) // the server value stays for the queue and the waveform gate
        // An estimate that disagrees with the server's length is ignored.
        engine.emit(EngineEvent.State(first, true, false, durationMs = 3_488))
        assertEquals(20_000L, player.displayDurationMs)
        // Another item: the first item's length does not carry over.
        engine.emit(EngineEvent.State(first, true, false, durationMs = 20_299))
        player.seek(1, 0)
        assertEquals(30_000L, player.displayDurationMs)
    }

    private fun entry(id: String, durationMs: Long) = QueueEntry(songId = id, albumId = id, durationMs = durationMs)
}
