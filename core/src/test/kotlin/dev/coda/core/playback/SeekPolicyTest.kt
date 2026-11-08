package dev.coda.core.playback

import org.junit.Assert.*
import org.junit.Test

/** A server transcode is seekable only once its whole file is cached. */
class SeekPolicyTest {
    private val opus = TrackSource.Remote("s", StreamFormat.OPUS_128)
    private val raw = TrackSource.Remote("s", StreamFormat.RAW)
    private fun entry(id: String) = QueueEntry(id, durationMs = 120_000, source = TrackSource.Remote(id, StreamFormat.OPUS_128))

    @Test fun policyTable() {
        assertFalse(SeekPolicy.isSeekable(opus, completeInCache = false))
        assertTrue(SeekPolicy.isSeekable(opus, completeInCache = true))
        assertTrue(SeekPolicy.isSeekable(raw, completeInCache = false))
        assertTrue(SeekPolicy.isSeekable(TrackSource.CachedFile("/x"), completeInCache = false))
        assertTrue(SeekPolicy.isSeekable(null, completeInCache = false))
        assertTrue("MP-2c: the server starts transcodes at timeOffset", SeekPolicy.isSeekable(opus, completeInCache = false, transcodeSeeks = true))
    }

    @Test fun withTimeOffsetASeekInAnUncachedTranscodeGoesToTheEngineClampedToTheSong() {
        val engine = FakeAudioEngine().apply { transcodeSeeks = true }
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        player.seek(60_000)
        player.seek(500_000)
        assertEquals(listOf(60_000L, 119_999L), engine.seeks)
        assertTrue(engine.replacedSources.isEmpty())
    }

    @Test fun withTimeOffsetACompleteCacheCopyStillWins() {
        val engine = FakeAudioEngine().apply { transcodeSeeks = true }
        var cached = false
        val resolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source }
        val player = PlaybackController(engine, sourceResolver = resolver)
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        cached = true
        player.seek(60_000)
        assertTrue(engine.seeks.isEmpty())
        assertEquals(1, engine.replacedSources.size)
    }

    @Test fun seekInAnUncachedTranscodeIsIgnoredAndNeverRestartsFromZero() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        engine.position = 5_000
        player.seek(60_000)
        assertTrue("No engine seek on an unseekable stream", engine.seeks.isEmpty())
        assertTrue(engine.replacedSources.isEmpty())
        assertEquals(5_000L, player.positionMs)
    }

    @Test fun seekAfterTheFileIsCompleteContinuesFromTheCacheAtThePosition() {
        val engine = FakeAudioEngine()
        var cached = false
        val resolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source }
        val player = PlaybackController(engine, sourceResolver = resolver)
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        val occurrence = engine.current!!.occurrence
        player.seek(60_000)
        assertTrue(engine.replacedSources.isEmpty())
        cached = true
        player.seek(60_000)
        assertEquals(listOf<Pair<TrackSource, Long>>(TrackSource.CachedFile("/cache/a") to 60_000L), engine.replacedSources)
        assertEquals(occurrence, engine.current!!.occurrence)
        assertEquals(TrackSource.CachedFile("/cache/a"), player.state.value.currentOccurrence!!.item.source)
        assertEquals(1, engine.loadCalls.size)
        // The next item is untouched: gapless hand-over is unchanged.
        assertEquals("b", player.nextEntry?.songId)
        player.seek(10_000)
        assertEquals(listOf(10_000L), engine.seeks)
    }

    @Test fun restartingATranscodeAtZeroAndSeekingTheOriginalStreamStillWork() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine)
        player.replaceQueue(listOf(QueueEntry("a", durationMs = 1000), entry("b")), startAt = 0)
        player.seek(500)
        assertEquals(listOf(500L), engine.seeks)
        player.seek(1, 0)
        player.seek(0)
        assertEquals(listOf(500L, 0L), engine.seeks)
    }

    /** An interrupted transcode stream continues from the complete read-ahead copy instead of re-downloading. */
    @Test fun interruptedTranscodeContinuesFromTheCompleteCacheCopy() {
        val engine = FakeAudioEngine()
        var cached = false
        val resolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source }
        val player = PlaybackController(engine, sourceResolver = resolver)
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        val occurrence = engine.current!!.occurrence
        engine.position = 42_000
        engine.emit(EngineEvent.StreamInterrupted(occurrence))
        assertTrue("No copy yet: the engine retries the stream", engine.replacedSources.isEmpty())
        cached = true
        engine.emit(EngineEvent.StreamInterrupted(OccurrenceId()))
        assertTrue("Another occurrence is ignored", engine.replacedSources.isEmpty())
        engine.emit(EngineEvent.StreamInterrupted(occurrence))
        assertEquals(listOf<Pair<TrackSource, Long>>(TrackSource.CachedFile("/cache/a") to 42_000L), engine.replacedSources)
        assertEquals(occurrence, player.state.value.currentOccurrence!!.item.occurrence)
        engine.emit(EngineEvent.StreamInterrupted(occurrence))
        assertEquals("Already playing the copy", 1, engine.replacedSources.size)
    }

    @Test fun interruptedOriginalStreamKeepsItsOwnRetry() {
        val engine = FakeAudioEngine()
        var cached = false
        val player = PlaybackController(engine, sourceResolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source })
        player.replaceQueue(listOf(QueueEntry("a", durationMs = 1000, source = raw), entry("b")), startAt = 0)
        cached = true
        engine.emit(EngineEvent.StreamInterrupted(engine.current!!.occurrence))
        assertTrue("An original stream resumes with a Range request", engine.replacedSources.isEmpty())
    }

    /** The network is really gone (fatal Network failure) but the read-ahead finished the song: continue from it. */
    @Test fun aFatalNetworkFailureWithACompleteLocalCopyContinuesFromItInsteadOfWaiting() {
        val engine = FakeAudioEngine()
        var cached = false
        val player = PlaybackController(engine, sourceResolver = SourceResolver { e -> if (cached) TrackSource.CachedFile("/cache/${e.songId}") else e.source })
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        val occurrence = engine.current!!.occurrence
        engine.position = 42_000
        cached = true   // the read-ahead completed while playing
        engine.emit(EngineEvent.Failed(occurrence, PlaybackFailure.Network))
        assertNull("not waiting for the connection", player.state.value.waiting)
        assertEquals(listOf<Pair<TrackSource, Long>>(TrackSource.CachedFile("/cache/a") to 42_000L), engine.replacedSources)
        assertEquals("same occurrence: no end/start pair", occurrence, player.state.value.currentOccurrence!!.item.occurrence)
        assertEquals(TrackSource.CachedFile("/cache/a"), player.state.value.currentOccurrence!!.item.source)
        assertEquals(1, engine.loadCalls.size)
        assertEquals("the gapless successor is untouched", "b", player.nextEntry?.songId)
    }

    @Test fun aFatalNetworkFailureWithoutACompleteCopyStillWaitsForTheConnection() {
        val engine = FakeAudioEngine()
        val player = PlaybackController(engine, sourceResolver = SourceResolver { e -> e.source })
        player.replaceQueue(listOf(entry("a"), entry("b")), startAt = 0)
        engine.position = 42_000
        engine.emit(EngineEvent.Failed(engine.current!!.occurrence, PlaybackFailure.Network))
        assertNotNull(player.state.value.waiting)
        assertTrue(engine.replacedSources.isEmpty())
    }

    /**
     * Every seek inside the playing track reaches the engine's seekTo or replaceCurrentSource, where the seek
     * fade is (ExoAudioEngine + SeekSilence): the seek bar, the notification / lock screen / Bluetooth / Android Auto
     * (MediaSession handleSeek -> seek(index, ms)), "previous" after 3 s, a tap on the playing row (seek(index, 0)).
     * A change of track (previous in the first 3 s, the cover swipe) is a load, as before: no seek fade.
     */
    @Test fun everySeekInsideThePlayingTrackGoesThroughTheEngineSeek() {
        for (source in listOf(TrackSource.CachedFile("/cache/a"), TrackSource.Remote("a", StreamFormat.RAW), TrackSource.Remote("a", StreamFormat.OPUS_128))) {
            val engine = FakeAudioEngine().apply { transcodeSeeks = true }
            val player = PlaybackController(engine)
            player.replaceQueue(listOf(entry("z"), entry("a").copy(source = source), entry("b")), startAt = 1)
            val loads = engine.loadCalls.size
            player.seek(30_000)              // seek bar (drag end), in-app
            player.seek(1, 40_000)           // MediaSession: notification, lock screen, Bluetooth, Android Auto
            engine.position = 45_000
            player.previous()                // after 3 s: back to the start of this track
            player.seek(1, 0)                // a tap on the playing row in the queue
            assertEquals("$source", listOf(30_000L, 40_000L, 0L, 0L), engine.seeks)
            assertEquals("$source: no reload", loads, engine.loadCalls.size)
            engine.position = 1_000
            player.previous()                // within the first 3 s: the previous track (a load, no seek fade)
            assertEquals("$source", loads + 1, engine.loadCalls.size)
            assertEquals(4, engine.seeks.size)
        }
    }
}
