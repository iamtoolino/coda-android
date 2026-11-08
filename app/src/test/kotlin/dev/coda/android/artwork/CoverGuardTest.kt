package dev.coda.android.artwork

import org.junit.Assert.*
import org.junit.Test

class CoverGuardTest {
    @Test fun compliantCoversPassUnchanged() {
        val bytes = ByteArray(30_000)
        assertSame(bytes, CoverGuard.fit(bytes, 288, CoverGuard.BROWSE_MAX_BYTES, { _, _ -> fail(); null }, { 288 }))
    }

    @Test fun edgesStepDownFromTheLimitAndStayInRange() {
        assertEquals(listOf(288, 216, 144, 72, 64), CoverGuard.edges(288))
        assertEquals(listOf(512, 384, 256, 128, 64), CoverGuard.edges(512))
        assertEquals(listOf(64), CoverGuard.edges(64))
    }

    @Test fun a3000pxCoverAndAFiveMegabyteJpegAreShrunkNotDropped() {
        for ((size, edge) in listOf(8_000 to 3000, 5_000_000 to 3000, 5_000_000 to 400)) {
            val tried = mutableListOf<Int>()
            val out = CoverGuard.fit(ByteArray(size), 512, CoverGuard.BROWSE_MAX_BYTES, { _, e -> tried += e; ByteArray(40_000) }, { edge })
            assertEquals(40_000, out!!.size)
            assertEquals(listOf(512), tried)
        }
    }

    @Test fun keepsSteppingDownUntilTheBytesFit() {
        val out = CoverGuard.fit(ByteArray(2_000_000), 512, 100_000, { _, e -> ByteArray(if (e > 200) 300_000 else 90_000) }, { 4000 })
        assertEquals(90_000, out!!.size)
    }

    @Test fun onlyUndecodableOrUnshrinkableDataYieldsNull() {
        assertNull(CoverGuard.fit(ByteArray(10), 512, 1000, { _, _ -> ByteArray(1) }, { null }))
        assertNull(CoverGuard.fit(ByteArray(2_000_000), 512, 1000, { _, _ -> null }, { 4000 }))
    }
}
