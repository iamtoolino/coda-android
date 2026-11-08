package dev.coda.android.playback

import dev.coda.android.artwork.CoverArtKeyer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionCoverTest {
    private val max = CurrentArtworkData.MAX_BYTES

    @Test fun requestsTheSessionSizeAndKeysItSeparatelyFromTheUiCover() {
        val art = SessionCover.art("c1")
        assertEquals(1024, art.size)
        assertNotEquals(CoverArtKeyer.key("s", dev.coda.android.artwork.CoverArt("c1")), CoverArtKeyer.key("s", art))
        assertEquals("art:s:c1:1024", CoverArtKeyer.key("s", art))
    }

    @Test fun smallCoversAreUsedAsFetched() = runBlocking {
        var asked: Int? = null
        val out = SessionCover.select("c1", { asked = it.size; ByteArray(10) }, { 300 }, { _, _ -> fail("no shrink"); null })
        assertEquals(10, out!!.size); assertEquals(1024, asked)
    }

    @Test fun largeOriginalsAreShrunkInSteps_neverDropped() = runBlocking {
        val edges = mutableListOf<Int>()
        val out = SessionCover.select("c1", { ByteArray(max + 1) }, { 3000 }) { _, edge -> edges += edge; ByteArray(if (edge <= 256) 100 else max + 5) }
        assertEquals(100, out!!.size)
        assertEquals(listOf(1024, 768, 512, 384, 256), edges)
    }

    /** The player calls this from Main; decoding and re-encoding must happen elsewhere. */
    @Test fun decodesAndShrinksOffTheCallersThread() = runBlocking {
        val caller = Thread.currentThread()
        val threads = mutableSetOf<Thread>()
        SessionCover.select("c1", { ByteArray(max + 1) }, { threads += Thread.currentThread(); 3000 }) { _, _ -> threads += Thread.currentThread(); ByteArray(10) }
        assertEquals(1, threads.size)
        assertNotSame(caller, threads.single())
    }

    @Test fun fullResolutionAtTheByteCapIsKept() = runBlocking {
        val bytes = ByteArray(max)
        assertSame(bytes, SessionCover.select("c1", { bytes }, { 1024 }) { _, _ -> fail("no shrink"); null })
    }

    @Test fun overCapArtworkFallsBackTo768Before512() = runBlocking {
        val edges = mutableListOf<Int>()
        val out = SessionCover.select("c1", { ByteArray(max + 1) }, { 1024 }) { _, edge ->
            edges += edge
            ByteArray(if (edge == 768) max else max + 1)
        }
        assertEquals(max, out!!.size)
        assertEquals(listOf(1024, 768), edges)
    }

    @Test fun onlyAnUnshrinkableCoverYieldsNull() = runBlocking {
        assertNull(SessionCover.select("c1", { ByteArray(max + 1) }, { 3000 }) { _, _ -> null })
    }

    @Test fun smallBytesWithHugePixelDimensionsAreStillShrunk() = runBlocking {
        // A 3000 x 3000 flat-colour PNG is only a few KiB but must not reach a host at that size.
        var shrunkTo: Int? = null
        val out = SessionCover.select("c1", { ByteArray(4000) }, { 3000 }) { _, edge -> shrunkTo = edge; ByteArray(50) }
        assertEquals(50, out!!.size); assertEquals(1024, shrunkTo)
    }

    @Test fun undecodableBytesAreNotForwarded() = runBlocking {
        assertNull(SessionCover.select("c1", { ByteArray(10) }, { null }) { _, _ -> ByteArray(5) })
    }
}
