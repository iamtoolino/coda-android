package dev.coda.android.cache

import android.net.Uri
import dev.coda.android.playback.RemoteStreamResolver
import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.TrackSource
import org.junit.Assert.*
import org.junit.Test

/** The per-occurrence transcode-offset decision of [TrackByteSource.Factory]. */
class TrackByteSourceOffsetTest {
    private val opus = TrackSource.Remote("song", StreamFormat.OPUS_128)

    private class Server(var answer: () -> Boolean) : RemoteStreamResolver {
        var asked = 0
        override fun resolve(source: TrackSource.Remote): Uri = error("not opened in this test")
        override fun supportsTranscodeOffset(): Boolean { asked++; return answer() }
    }

    @Test fun theFirstOpenThatSucceedsPinsTheOffsetForEveryLaterOpen() {
        val server = Server { true }
        val factory = TrackByteSource.Factory(opus, server, requestedOffsetSeconds = 8)
        assertEquals("nothing opened yet", 0L, factory.startOffsetUs)
        val first = factory.opening()
        assertEquals(opus.copy(timeOffsetSeconds = 8), first)
        factory.opened(first)
        assertEquals(8_000_000L, factory.startOffsetUs)
        server.answer = { false } // e.g. a new answer must not change a stream already being read
        assertEquals("a retry or BAT-1b re-open reads the same stream", first, factory.opening())
        assertEquals("asked once", 1, server.asked)
        assertEquals("the item's own source keeps offset 0", opus, factory.source)
    }

    @Test fun withoutServerSupportTheStreamStartsAtZero() {
        val factory = TrackByteSource.Factory(opus, Server { false }, requestedOffsetSeconds = 8)
        val first = factory.opening()
        assertEquals(opus, first)
        factory.opened(first)
        assertEquals(0L, factory.startOffsetUs)
    }

    @Test fun aFailedCapabilityQueryPinsNothingUntilAStreamOpened() {
        val server = Server { throw java.io.IOException("flaky network") }
        val factory = TrackByteSource.Factory(opus, server, requestedOffsetSeconds = 8)
        assertEquals("a failed query asks without an offset", opus, factory.opening())
        // That open failed too (no bytes read): the loader's retry asks again and gets the offset.
        server.answer = { true }
        val retry = factory.opening()
        assertEquals(opus.copy(timeOffsetSeconds = 8), retry)
        factory.opened(retry)
        assertEquals(8_000_000L, factory.startOffsetUs)
        assertEquals(2, server.asked)
    }

    @Test fun aStreamOpenedWithoutTheOffsetStaysWithoutIt() {
        val server = Server { throw java.io.IOException("flaky network") }
        val factory = TrackByteSource.Factory(opus, server, requestedOffsetSeconds = 8)
        factory.opened(factory.opening()) // the query failed, the stream opened from 0
        server.answer = { true }
        assertEquals("later opens read the same stream from 0", opus, factory.opening())
        assertEquals(0L, factory.startOffsetUs)
    }

    @Test fun aFallbackToTheOriginalDropsTheOffset() {
        val factory = TrackByteSource.Factory(opus, Server { true }, requestedOffsetSeconds = 8)
        factory.opened(factory.opening())
        val original = opus.copy(format = StreamFormat.RAW)
        assertTrue(factory.fellBack(original))
        assertFalse("already in use", factory.fellBack(original))
        assertEquals(0L, factory.startOffsetUs)
        assertEquals("the original ignores timeOffset and is asked without it", original, factory.opening())
    }

    /**
     * Navidrome without a usable transcoder answers an offset request successfully with the original,
     * which ignores `timeOffset` and starts at 0. Its own type gives it away (the shared [dev.coda.core.cache.ArrivalDetector]):
     * no offset is pinned, and the occurrence falls back to the original as for a rejected request.
     */
    @Test fun anOriginalAnsweringAnOffsetRequestStartsAtZeroAndIsTheFallback() {
        val fellBack = mutableListOf<TrackSource>()
        val factory = TrackByteSource.Factory(opus, Server { true }, requestedOffsetSeconds = 8) { fellBack += it }
        val first = factory.opening()
        assertEquals(opus.copy(timeOffsetSeconds = 8), first)
        factory.opened(first, "audio/flac")
        val original = opus.copy(format = StreamFormat.RAW)
        assertEquals("the stream starts at 0", 0L, factory.startOffsetUs)
        assertFalse(factory.mayStartAtOffset)
        assertEquals(original, factory.source)
        assertEquals("reported once, like a rejected request", listOf<TrackSource>(original), fellBack)
        assertEquals("later opens ask for the original without the offset", original, factory.opening())
    }

    @Test fun aTranscodeAnsweringAnOffsetRequestPinsIt() {
        val fellBack = mutableListOf<TrackSource>()
        val factory = TrackByteSource.Factory(opus, Server { true }, requestedOffsetSeconds = 8) { fellBack += it }
        factory.opened(factory.opening(), "audio/ogg")
        assertEquals(8_000_000L, factory.startOffsetUs)
        assertEquals(opus, factory.source)
        assertTrue(fellBack.isEmpty())
    }

    @Test fun startsBelowOneSecondAndCachedFilesNeverAsk() {
        val server = Server { true }
        val zero = TrackByteSource.Factory(opus, server, requestedOffsetSeconds = 0)
        assertEquals(opus, zero.opening())
        val file = TrackSource.CachedFile("/cache/song.opus")
        assertEquals(file, TrackByteSource.Factory(file, server, requestedOffsetSeconds = 8).opening())
        assertEquals(0, server.asked)
    }

    /** Until an open pinned it, a stream asked at an offset may still start there. */
    @Test fun anUnpinnedOffsetMayStillApply() {
        val factory = TrackByteSource.Factory(opus, Server { true }, requestedOffsetSeconds = 45)
        assertTrue(factory.mayStartAtOffset)
        assertEquals(0L, factory.startOffsetUs)
        factory.opened(factory.opening())
        assertFalse(factory.mayStartAtOffset)
        assertEquals(45_000_000L, factory.startOffsetUs)
        assertFalse("from 0: never", TrackByteSource.Factory(opus, Server { true }).mayStartAtOffset)
    }
}
