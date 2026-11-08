package dev.coda.core.cache

import dev.coda.core.testing.OggFixture
import dev.coda.core.testing.SyntheticLibrary
import org.junit.Assert.*
import org.junit.Test

class OggStreamTest {
    private val good = OggFixture.transcode(serial = 7, seed = 1)

    @Test fun aWholeStreamIsComplete() {
        assertTrue(OggStream.isComplete(good))
        assertTrue("the synthetic Opus fixture", OggStream.isComplete(SyntheticLibrary.albums.first().tracks.first().bytes("opus")))
    }

    @Test fun aTruncatedOrExtendedStreamIsNot() {
        assertFalse(OggStream.isComplete(good.copyOf(good.size - 1)))
        assertFalse("cut inside a page", OggStream.isComplete(good.copyOf(30_000)))
        assertFalse("no end-of-stream page", OggStream.isComplete(good.copyOf(good.size - 20_000 - 27 - 79)))
        assertFalse(OggStream.isComplete(good + byteArrayOf(0)))
        assertFalse(OggStream.isComplete(ByteArray(0)))
    }

    @Test fun changedBytesAndSplicedStreamsAreNot() {
        assertFalse("one flipped bit in a body", OggStream.isComplete(good.clone().also { it[good.size / 2] = (it[good.size / 2] + 1).toByte() }))
        // The same audio under another serial: the first part of one and the rest of the other.
        val other = OggFixture.transcode(serial = 8, seed = 1)
        val spliced = good.copyOf(10_000) + other.copyOfRange(10_000, other.size)
        assertFalse(OggStream.isComplete(spliced))
        assertFalse("pages of one stream, then the pages of another with a gap", OggStream.isComplete(
            OggFixture.stream(7, listOf(ByteArray(19), ByteArray(30))).let { it + OggFixture.stream(7, listOf(ByteArray(5))) }))
    }
}
